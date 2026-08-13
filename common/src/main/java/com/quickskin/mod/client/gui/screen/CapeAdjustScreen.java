package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.NativeImage;
//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.BackgroundRenderer;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.CapeElytraSilhouette;
import com.quickskin.mod.common.util.CapeOpaqueFill;
import com.quickskin.mod.common.util.CapeZoomRange;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.util.Mth;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Screen for adjusting cape image position and resolution when the imported
 * image doesn't match a standard cape format.
 *
 * The user sees their source image behind a cape template grid.
 * They can drag to reposition, scroll to zoom, and select a target resolution.
 * The "cape back" area (the main visible part) is highlighted.
 */
@Environment(EnvType.CLIENT)
public class CapeAdjustScreen extends Screen {

    private final Screen parent;
    private final BufferedImage sourceImage;
    private final Consumer<BufferedImage> onApply; // Callback with composed cape
    private final Runnable onCancel;
    private boolean completed;
    private final int frameCount;
    private final int srcFrameHeight; // Height of one frame in the source strip

    // Target cape resolution
    private static final int[][] RESOLUTIONS = {
            {64, 32}, {128, 64}, {256, 128}, {512, 256}, {1024, 512}
    };
    private static final String[] RESOLUTION_LABELS = {
            "64x32", "128x64 (2x)", "256x128 (4x)", "512x256 (8x)", "1024x512 (16x)"
    };
    private int selectedResolution = 0;

    // Image positioning in cape-space (target resolution coordinates).
    // imgScale is the screen's one and only zoom value. Everything that changes it goes through
    // rescaleAbout(), which owns the clamp, the anchor correction and the slider resync.
    private double imgOffsetX = 0;
    private double imgOffsetY = 0;
    private double imgScale = 1.0;
    // The same offsets before applySnap() rounded them. Only rescaleAbout() reads these; see
    // applySnap() for why chaining a zoom off the rounded pair does not work.
    private double exactOffsetX = 0;
    private double exactOffsetY = 0;

    // Display grid (cape template on screen)
    private int gridX, gridY, gridW, gridH;
    private double displayScale; // cape-space pixels -> screen pixels

    // Snap-to-grid
    private static final int[] SNAP_SIZES = {0, 1, 2, 4, 8};
    private static final String[] SNAP_LABELS = {"Off", "1px", "2px", "4px", "8px"};
    private int snapIndex = 1; // Default: 1px snap

    // Dragging
    private boolean isDragging = false;
    private double dragStartX, dragStartY;
    private double dragStartOffsetX, dragStartOffsetY;

    // Animation playback (for multi-frame GIFs)
    private int currentAnimFrame = 0;
    private long animStartTime = 0;

    // Mirror: copy cape back region to front
    private boolean mirrorFrontBack = false;

    // Opaque cape: flatten the cape's transparency onto a solid colour.
    // Off by default; off is exactly the behaviour this screen had before the toggle existed.
    private boolean opaqueFill = false;
    private int opaqueFillRgb = CapeOpaqueFill.DEFAULT_FILL_RGB;
    private boolean pickerOpen = false;
    /** Guards the two-way slider/hex-field sync against re-entrant responder callbacks. */
    private boolean syncingPicker = false;
    private Button opaqueToggleButton;
    private Button fillColorButton;
    private ChannelSlider redSlider;
    private ChannelSlider greenSlider;
    private ChannelSlider blueSlider;
    private EditBox hexField;
    /** Second view of {@link #imgScale}; recreated by every init(), never a value of its own. */
    private ZoomSlider zoomSlider;
    // Colour picker popover bounds, computed in init() and used for hit testing + rendering.
    private int pickerX, pickerY, pickerW, pickerH;
    private int swatchX, swatchY;
    private static final int PICKER_W = 200;
    private static final int PICKER_H = 84;
    private static final int SWATCH_SIZE = 20;

    // Control-row budget. Everything on the row except the two opaque buttons and the zoom slider
    // is fixed width: the 4px gap between the buttons, the 8px gap before the swatch, the swatch,
    // and the 6px gap before the slider.
    private static final int ROW_FIXED_W = 4 + 8 + SWATCH_SIZE + 6;
    private static final int ZOOM_PREFERRED_W = 76;
    private static final int ZOOM_MAX_W = 150;
    /** Absolute floor, reached only where the grid is too narrow to seat all four controls. */
    private static final int ZOOM_FLOOR_W = 20;
    private static final int CONTROL_MIN_W = 70;
    private static final int CONTROL_MAX_W = 110;
    /** Makes opaque-black padding distinguishable from transparent source pixels in evidence. */
    private static final int SOURCE_CHECKER_SIZE = 16;
    private static final int SOURCE_CHECKER_DARK = 0xFF20242A;
    private static final int SOURCE_CHECKER_LIGHT = 0xFF343A42;
    private static final int SOURCE_BOUNDARY_COLOR = 0xFF55FFFF;

    // Source image texture
    //? if <1.21.11 {
    private ResourceLocation sourceTextureLocation;
    //?} else {
    private Identifier sourceTextureLocation;
    //?}
    private DynamicTexture sourceDynTexture;

    // Preview texture (cape back portion)
    //? if <1.21.11 {
    private ResourceLocation previewTextureLocation;
    //?} else {
    private Identifier previewTextureLocation;
    //?}
    private DynamicTexture previewDynTexture;
    private boolean previewDirty = true;

    // 3D player model preview (mirrors the main cape menu)
    private PlayerWidget playerWidget;
    //? if <1.21.11 {
    private ResourceLocation lastPlayerWidgetCape;
    //?} else {
    private Identifier lastPlayerWidgetCape;
    //?}

    public CapeAdjustScreen(Screen parent, BufferedImage sourceImage, Consumer<BufferedImage> onApply) {
        this(parent, sourceImage, 1, onApply, () -> {});
    }

    public CapeAdjustScreen(Screen parent, BufferedImage sourceImage, int frameCount, Consumer<BufferedImage> onApply) {
        this(parent, sourceImage, frameCount, onApply, () -> {});
    }

    public CapeAdjustScreen(
            Screen parent,
            BufferedImage sourceImage,
            int frameCount,
            Consumer<BufferedImage> onApply,
            Runnable onCancel
    ) {
        super(Component.translatable("quickskin.cape.adjust_title"));
        this.parent = parent;
        this.sourceImage = sourceImage;
        this.frameCount = Math.max(1, frameCount);
        this.srcFrameHeight = sourceImage.getHeight() / this.frameCount;
        this.onApply = onApply;
        this.onCancel = onCancel != null ? onCancel : () -> {};
    }

    @Override
    protected void init() {
        // Register source image as texture (first frame only for animation strips)
        if (sourceTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(sourceTextureLocation);
        }
        BufferedImage displayFrame = (frameCount > 1)
                ? sourceImage.getSubimage(0, 0, sourceImage.getWidth(), srcFrameHeight)
                : sourceImage;
        NativeImage nativeImage = convertToNativeImage(displayFrame);
        //? if <1.21.6 {
        sourceDynTexture = new DynamicTexture(nativeImage);
        sourceTextureLocation = Minecraft.getInstance().getTextureManager()
                .register("quickskin/cape_adjust_source", sourceDynTexture);
        //?} else if <1.21.11 {
        sourceDynTexture = new DynamicTexture(() -> "quickskin_cape_adjust_source", nativeImage);
        sourceTextureLocation = ResourceLocation.fromNamespaceAndPath(
                QuickSkin.MOD_ID, "cape_adjust_source");
        Minecraft.getInstance().getTextureManager().register(sourceTextureLocation, sourceDynTexture);
        //?} else {
        sourceDynTexture = new DynamicTexture(() -> "quickskin_cape_adjust_source", nativeImage);
        sourceTextureLocation = Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "cape_adjust_source");
        Minecraft.getInstance().getTextureManager().register(sourceTextureLocation, sourceDynTexture);
        //?}

        // Calculate grid display area (left 65% of screen, vertically centered)
        int availW = (int) (this.width * 0.6);
        int availH = (int) (this.height * 0.65);
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];

        // Scale to fit while maintaining 2:1 aspect
        double scaleX = (double) availW / capeW;
        double scaleY = (double) availH / capeH;
        displayScale = Math.min(scaleX, scaleY);

        gridW = (int) (capeW * displayScale);
        gridH = (int) (capeH * displayScale);
        gridX = 20 + (availW - gridW) / 2;
        gridY = 30 + (availH - gridH) / 2;

        // Center image initially to cover the cape area
        resetImagePosition();

        // Buttons
        this.clearWidgets();

        int rightPanelX = gridX + availW + 15;
        int rightPanelW = this.width - rightPanelX - 10;
        int btnW = Math.min(rightPanelW, 120);

        // Resolution buttons — disable resolutions larger than the source image
        // For animated GIFs, cap at 4x (256x128) to avoid lag on servers
        int resY = gridY;
        int srcW = sourceImage.getWidth();
        int srcH = srcFrameHeight;
        // Max resolution index: 2 (256x128 / 4x) for GIFs, all for static
        int maxResIndex = (frameCount > 1) ? 2 : RESOLUTIONS.length - 1;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            final int idx = i;
            Button btn = Button.builder(Component.literal(RESOLUTION_LABELS[i]), b -> {
                int oldRes = selectedResolution;
                selectedResolution = idx;
                // Scale position/zoom proportionally to new resolution
                double ratio = (double) RESOLUTIONS[idx][0] / RESOLUTIONS[oldRes][0];
                imgOffsetX *= ratio;
                imgOffsetY *= ratio;
                imgScale *= ratio;
                applySnap();
                recalculateGrid();
                // Both ends of the legal range carry the same factor of capeW, so this leaves the
                // slider exactly where it was — resyncing it is the discipline, not a correction.
                syncZoomSlider();
                previewDirty = true;
            }).bounds(rightPanelX, resY, btnW, 20).build();
            // Disable if target resolution exceeds source dimensions or GIF cap
            if (RESOLUTIONS[i][0] > srcW || RESOLUTIONS[i][1] > srcH || i > maxResIndex) {
                btn.active = false;
            }
            this.addRenderableWidget(btn);
            resY += 24;
        }
        // If the currently selected resolution got disabled, fall back to the largest enabled one
        boolean currentDisabled = RESOLUTIONS[selectedResolution][0] > srcW
                || RESOLUTIONS[selectedResolution][1] > srcH
                || selectedResolution > maxResIndex;
        if (currentDisabled) {
            for (int i = Math.min(maxResIndex, RESOLUTIONS.length - 1); i >= 0; i--) {
                if (RESOLUTIONS[i][0] <= srcW && RESOLUTIONS[i][1] <= srcH) {
                    selectedResolution = i;
                    recalculateGrid();
                    break;
                }
            }
        }

        // Reset position button
        this.addRenderableWidget(Button.builder(
                Component.translatable("quickskin.cape.adjust_reset"),
                b -> { resetImagePosition(); previewDirty = true; }
        ).bounds(rightPanelX, resY + 10, btnW, 20).build());

        // Snap-to-grid toggle button (cycles through snap sizes)
        this.addRenderableWidget(Button.builder(
                Component.literal("Snap: " + SNAP_LABELS[snapIndex]),
                b -> {
                    snapIndex = (snapIndex + 1) % SNAP_SIZES.length;
                    b.setMessage(Component.literal("Snap: " + SNAP_LABELS[snapIndex]));
                    applySnap();
                    previewDirty = true;
                }
        ).bounds(rightPanelX, resY + 34, btnW, 20).build());

        // Mirror front=back toggle button
        this.addRenderableWidget(Button.builder(
                Component.literal("Mirror: " + (mirrorFrontBack ? "ON" : "OFF")),
                b -> {
                    mirrorFrontBack = !mirrorFrontBack;
                    b.setMessage(Component.literal("Mirror: " + (mirrorFrontBack ? "ON" : "OFF")));
                    previewDirty = true;
                }
        ).bounds(rightPanelX, resY + 58, btnW, 20).build());

        // Apply / Cancel
        int bottomY = this.height - 30;
        int totalBtnWidth = 200 + 10 + 80;
        int btnStartX = (this.width - totalBtnWidth) / 2;

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"),
                b -> onClose()
        ).bounds(btnStartX, bottomY, 80, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("quickskin.cape.adjust_apply"),
                b -> applyAndClose()
        ).bounds(btnStartX + 90, bottomY, 120, 20).build());

        // Opaque-cape toggle + colour picker, under the grid so the right-hand preview column
        // (2D thumbnails and the 3D player widget) keeps every pixel it has today.
        initOpaqueControls(bottomY);

        // 3D player model preview (same widget used by PlayerCapeMenuScreen).
        // Placed below the 2D cape/elytra previews, aligned with the right-panel
        // column, above the Apply/Cancel bar.
        int maxPreviewW = Math.min(100, rightPanelW);
        int previewStartY = gridY + RESOLUTIONS.length * 24 + 100;
        int estimatedPreviewsBottom = previewStartY
                + (int) ((maxPreviewW / 2 - 2) * 1.6)   // cape back / front thumbnails
                + 18
                + (int) ((maxPreviewW / 2 - 2) * 2.0);  // elytra thumbnail
        int widgetAreaLeft = rightPanelX;
        int widgetAreaRight = this.width - 10;
        int widgetAreaTop = estimatedPreviewsBottom + 15;
        int widgetAreaBottom = bottomY - 20;
        int widgetAreaW = widgetAreaRight - widgetAreaLeft;
        int widgetAreaH = widgetAreaBottom - widgetAreaTop;
        if (widgetAreaW >= 80 && widgetAreaH >= 120) {
            int widgetHeight = Mth.clamp(widgetAreaH, 120, 220);
            int widgetWidth = Mth.clamp((int) (widgetHeight / 1.8f), 70, widgetAreaW);
            int widgetX = widgetAreaLeft + (widgetAreaW - widgetWidth) / 2;
            int widgetY = widgetAreaTop;

            //? if <1.21.11 {
            ResourceLocation skinLocation = null;
            //?} else {
            Identifier skinLocation = null;
            //?}
            String modelType = "classic";
            LocalPlayer player = Minecraft.getInstance().player;
            ClientConfig config = ClientConfig.getInstance();

            if (!config.activeSkinHash.isEmpty()) {
                LocalAssetManager assetManager = LocalAssetManager.getInstance();
                AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);
                if (metadata != null) {
                    skinLocation = assetManager.getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                    modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                    if ("auto".equals(modelType)) {
                        modelType = metadata.skinModel();
                    }
                }
            }
            if (skinLocation == null && player != null) {
                //? if <1.21.9 {
                    //? if <1.21 {
                skinLocation = player.getSkinTextureLocation();
                    //?} else {
                skinLocation = player.getSkin().texture();
                    //?}
                //?} else {
                skinLocation = player.getSkin().body().texturePath();
                //?}
                if ("auto".equals(modelType)) {
                    //? if <1.21.9 {
                        //? if <1.21 {
                    String vanillaModel = player.getModelName(); // "default" or "slim"
                    modelType = "slim".equals(vanillaModel) ? "slim" : "classic";
                        //?} else {
                    modelType = "slim".equals(player.getSkin().model().id()) ? "slim" : "classic";
                        //?}
                    //?} else {
                    modelType = player.getSkin().model()
                            == net.minecraft.world.entity.player.PlayerModelType.SLIM ? "slim" : "classic";
                    //?}
                }
            }
            if (skinLocation == null) {
                //? if <1.21.11 {
                    //? if <1.21 {
                skinLocation = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
                    //?} else {
                skinLocation = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
                    //?}
                //?} else {
                skinLocation = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
                //?}
                modelType = "classic";
            }

            playerWidget = addRenderableWidget(new PlayerWidget(
                    widgetX, widgetY, widgetWidth, widgetHeight,
                    skinLocation, null, null, modelType));
            playerWidget.setContext(PlayerWidget.WidgetContext.CAPE_MENU);
            int referenceX = widgetX + widgetWidth / 2;
            int referenceY = widgetY + widgetHeight / 2;
            playerWidget.setCustomReferencePoint(referenceX, referenceY);
            // Face the player's back to the camera so the cape is visible (same default as cape menu).
            playerWidget.setRotationState(20.0f, 200.0f);
        }
    }

    // --- Opaque cape fill ---
    //
    // The toggle owns two pieces of state — on/off and the fill colour — and both feed exactly one
    // place: finalizeCapeFrame(), the pass that ends both composers. Nothing else in this screen
    // touches cape pixels on their behalf.

    /**
     * Lay out the opaque toggle, the fill-colour button and the colour picker popover.
     *
     * <p>The control row goes under the grid, clamped so it can never reach the Apply/Cancel bar on
     * a short window, which leaves the right-hand column (resolution buttons, the 2D thumbnails and
     * the 3D player widget) at exactly the coordinates it has today. The picker is a popover drawn
     * over the lower-left of the grid: the grid is the largest region on screen at every GUI scale,
     * so the picker always has room without reflowing anything that already exists.
     */
    private void initOpaqueControls(int bottomY) {
        // The row is budgeted from the grid width, the widest span that still clears the right-hand
        // column: gridW <= availW, so gridX + gridW always sits 15px left of rightPanelX. There is
        // no room for a second row — at the GUI scale Minecraft picks by default for both 1280x720
        // and 854x480 the screen is 427x240, controlsY is already against its Apply/Cancel clamp,
        // and only about 38px separate the grid from the bar — so the zoom slider shares this one.
        // The two opaque buttons keep their formulas and their existing 70px floor; the slider then
        // takes whatever is left up to the grid's right edge. Giving the leftover to the slider
        // rather than letting it claim a fixed width is what keeps the row out of the right-hand
        // column on a narrow grid — the column's buttons are registered first, so anything of ours
        // that reached them would lose the click to Reset Position rather than zoom.
        int controlW = Mth.clamp((gridW - ZOOM_PREFERRED_W - ROW_FIXED_W) / 2,
                CONTROL_MIN_W, CONTROL_MAX_W);
        int controlsY = Math.min(gridY + gridH + 20, bottomY - 24);

        opaqueToggleButton = this.addRenderableWidget(Button.builder(
                opaqueToggleMessage(),
                b -> setOpaqueFill(!opaqueFill)
        ).bounds(gridX, controlsY, controlW, 20).build());

        fillColorButton = this.addRenderableWidget(Button.builder(
                fillColorMessage(),
                b -> setPickerOpen(!pickerOpen)
        ).bounds(gridX + controlW + 4, controlsY, controlW, 20).build());
        fillColorButton.active = opaqueFill;

        swatchX = Math.min(gridX + 2 * controlW + 12, this.width - SWATCH_SIZE - 4);
        swatchY = controlsY;

        // Zoom slider, last on the row, taking the width between the swatch and the grid's right
        // edge. 16px tall like the picker's channel sliders, so it clears the "Drag to move ·
        // Scroll to zoom" hint by as much as the 20px buttons beside it do.
        int zoomX = Math.min(swatchX + SWATCH_SIZE + 6, this.width - ZOOM_FLOOR_W - 4);
        int zoomW = Mth.clamp(gridX + gridW - zoomX, ZOOM_FLOOR_W, ZOOM_MAX_W);
        zoomSlider = this.addRenderableWidget(new ZoomSlider(zoomX, controlsY + 2, zoomW, 16));

        pickerW = Math.min(PICKER_W, Math.max(120, gridW - 8));
        pickerH = PICKER_H;
        pickerX = gridX + 4;
        pickerY = Math.max(gridY + 2, gridY + gridH - pickerH - 4);

        int sliderX = pickerX + 5;
        int sliderW = Math.max(60, pickerW - 10 - SWATCH_SIZE - 5);
        redSlider = this.addRenderableWidget(new ChannelSlider(
                sliderX, pickerY + 5, sliderW, 16, 16, "quickskin.cape.adjust_red"));
        greenSlider = this.addRenderableWidget(new ChannelSlider(
                sliderX, pickerY + 23, sliderW, 16, 8, "quickskin.cape.adjust_green"));
        blueSlider = this.addRenderableWidget(new ChannelSlider(
                sliderX, pickerY + 41, sliderW, 16, 0, "quickskin.cape.adjust_blue"));

        hexField = new EditBox(this.font, sliderX, pickerY + 61, Math.min(74, sliderW), 16,
                Component.translatable("quickskin.cape.adjust_hex"));
        hexField.setMaxLength(CapeOpaqueFill.HEX_DIGITS + 1);
        hexField.setValue(CapeOpaqueFill.toHex(opaqueFillRgb));
        hexField.setResponder(this::onHexTyped);
        this.addRenderableWidget(hexField);

        updatePickerVisibility();
    }

    private Component opaqueToggleMessage() {
        return Component.translatable(opaqueFill
                ? "quickskin.cape.adjust_opaque_on"
                : "quickskin.cape.adjust_opaque_off");
    }

    private Component fillColorMessage() {
        return Component.translatable("quickskin.cape.adjust_fill",
                CapeOpaqueFill.toHex(opaqueFillRgb));
    }

    private void setOpaqueFill(boolean enabled) {
        if (opaqueFill == enabled) {
            return;
        }
        opaqueFill = enabled;
        if (!enabled) {
            pickerOpen = false;
        }
        if (opaqueToggleButton != null) {
            opaqueToggleButton.setMessage(opaqueToggleMessage());
        }
        if (fillColorButton != null) {
            fillColorButton.active = enabled;
        }
        updatePickerVisibility();
        previewDirty = true;
    }

    /**
     * Set both halves of the toggle in one call. Only used by the packaged E2E harness, which
     * drives this instead of synthesising clicks so the scenario exercises the same state
     * transitions the buttons do.
     */
    private void setOpaqueFill(boolean enabled, int fillRgb) {
        setFillColor(fillRgb, null);
        setOpaqueFill(enabled);
    }

    private void setPickerOpen(boolean open) {
        pickerOpen = open && opaqueFill;
        updatePickerVisibility();
    }

    private void updatePickerVisibility() {
        boolean visible = pickerOpen && opaqueFill;
        setPickerWidgetVisible(redSlider, visible);
        setPickerWidgetVisible(greenSlider, visible);
        setPickerWidgetVisible(blueSlider, visible);
        setPickerWidgetVisible(hexField, visible);
    }

    /**
     * Hiding a widget does not take its focus. The screen keeps dispatching keys to whatever it
     * focused last, and a slider only stops answering the arrow keys once it is told it lost focus,
     * so a picker dismissed by a click that landed on nothing would otherwise leave an off-screen
     * slider still editing the fill colour.
     */
    private void setPickerWidgetVisible(AbstractWidget widget, boolean visible) {
        if (widget == null) {
            return;
        }
        widget.visible = visible;
        if (!visible) {
            widget.setFocused(false);
            if (this.getFocused() == widget) {
                this.setFocused(null);
            }
        }
    }

    /**
     * Adopt a new fill colour and push it back into whichever picker widgets did not originate it.
     * The preview only needs recomposing while the toggle is on, since the colour is inert
     * otherwise.
     */
    private void setFillColor(int rgb, Object source) {
        int next = CapeOpaqueFill.clampRgb(rgb);
        if (next == opaqueFillRgb) {
            return;
        }
        opaqueFillRgb = next;
        syncPickerWidgets(source);
        if (opaqueFill) {
            previewDirty = true;
        }
    }

    private void syncPickerWidgets(Object source) {
        boolean wasSyncing = syncingPicker;
        syncingPicker = true;
        try {
            if (redSlider != null && redSlider != source) {
                redSlider.syncFromColor();
            }
            if (greenSlider != null && greenSlider != source) {
                greenSlider.syncFromColor();
            }
            if (blueSlider != null && blueSlider != source) {
                blueSlider.syncFromColor();
            }
            if (fillColorButton != null) {
                fillColorButton.setMessage(fillColorMessage());
            }
            if (hexField != null && hexField != source) {
                String hex = CapeOpaqueFill.toHex(opaqueFillRgb);
                if (!hex.equals(hexField.getValue())) {
                    hexField.setValue(hex);
                }
            }
        } finally {
            syncingPicker = wasSyncing;
        }
    }

    /** Half-typed input parses as {@code -1} and leaves the colour where it was. */
    private void onHexTyped(String text) {
        if (syncingPicker) {
            return;
        }
        int parsed = CapeOpaqueFill.parseHex(text);
        if (parsed >= 0) {
            setFillColor(parsed, hexField);
        }
    }

    private boolean isInPicker(double mouseX, double mouseY) {
        return pickerOpen && opaqueFill
                && mouseX >= pickerX && mouseX < pickerX + pickerW
                && mouseY >= pickerY && mouseY < pickerY + pickerH;
    }

    /** One R/G/B track of the colour picker. */
    private final class ChannelSlider extends AbstractSliderButton {
        private final int shift;
        private final String labelKey;

        ChannelSlider(int x, int y, int width, int height, int shift, String labelKey) {
            super(x, y, width, height, Component.empty(), 0.0);
            this.shift = shift;
            this.labelKey = labelKey;
            this.value = CapeOpaqueFill.channel(opaqueFillRgb, shift) / 255.0;
            updateMessage();
        }

        void syncFromColor() {
            this.value = CapeOpaqueFill.channel(opaqueFillRgb, shift) / 255.0;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(labelKey,
                    String.valueOf(CapeOpaqueFill.channel(opaqueFillRgb, shift))));
        }

        @Override
        protected void applyValue() {
            if (syncingPicker) {
                return;
            }
            setFillColor(CapeOpaqueFill.withChannel(
                    opaqueFillRgb, shift, (int) Math.round(this.value * 255.0)), this);
        }
    }

    /**
     * The zoom, as a slider.
     *
     * <p>Holds no zoom of its own: {@code value} is only ever {@link CapeZoomRange#position} of the
     * screen's {@code imgScale}, and every edit runs straight back through {@link #rescaleAbout}.
     * The round trip position -> scale -> position is a fixed point, so a drag does not fight the
     * resync that follows it.
     *
     * <p>Like {@link ChannelSlider} this needs no Stonecutter branch: the constructor, the {@code
     * value} field and the two hooks below are the only parts of {@code AbstractSliderButton} that
     * are identical across all five eras. {@code onRelease}, {@code onDrag}, {@code keyPressed} and
     * {@code setValue} all changed signature or visibility at 1.21.11, and none of them is
     * overridden or called here.
     */
    private final class ZoomSlider extends AbstractSliderButton {

        ZoomSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0.0);
            // Read the live zoom rather than start at a constant. A window resize re-runs init(),
            // which resets the transform and then rebuilds every widget, so this is what makes the
            // rebuilt slider show the reset zoom instead of whatever it happened to be built with.
            this.value = currentZoomPosition();
            updateMessage();
        }

        void syncFromScale() {
            this.value = currentZoomPosition();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("quickskin.cape.adjust_zoom",
                    CapeZoomRange.formatPercent(imgScale, RESOLUTIONS[selectedResolution][0],
                            sourceImage.getWidth(), srcFrameHeight)));
        }

        @Override
        protected void applyValue() {
            int capeW = RESOLUTIONS[selectedResolution][0];
            int capeH = RESOLUTIONS[selectedResolution][1];
            // A slider has no cursor, so it zooms about the centre of the cape area — the same
            // point resetImagePosition() centres the image on. That makes the two agree: the
            // correction below is algebraically the centred offset at the new scale, so an image
            // left centred by Reset stays centred however far the slider is dragged (exactly, up to
            // the offset snap the wheel and the drag already apply). And because the anchor is
            // fixed, the content under the middle of the grid — where the user is looking — holds
            // still instead of crawling across it.
            rescaleAbout(
                    CapeZoomRange.scaleAt(this.value, capeW,
                            sourceImage.getWidth(), srcFrameHeight),
                    capeW / 2.0, capeH / 2.0);
        }

        /** Drive the slider exactly as a drag does. Only used by the packaged E2E harness. */
        void dragTo(double position) {
            this.value = Mth.clamp(position, 0.0, 1.0);
            applyValue();
            updateMessage();
        }

        /** {@code value} is protected, so the enclosing screen reads it through here. */
        double position() {
            return this.value;
        }
    }

    private double currentZoomPosition() {
        return CapeZoomRange.position(imgScale, RESOLUTIONS[selectedResolution][0],
                sourceImage.getWidth(), srcFrameHeight);
    }

    /**
     * Move the zoom slider as a drag would. Only used by the packaged E2E harness, which drives
     * this instead of synthesising mouse events so the scenario exercises the real widget path.
     */
    private void setZoomPosition(double position) {
        if (zoomSlider != null) {
            zoomSlider.dragTo(position);
        }
    }

    /** What the slider is showing. Paired with {@link #zoomPosition()} to assert they agree. */
    private double zoomSliderValue() {
        return zoomSlider == null ? -1.0 : zoomSlider.position();
    }

    /** Where {@code imgScale} says the slider should be. */
    private double zoomPosition() {
        return currentZoomPosition();
    }

    /** The number the slider's label prints. Only used by the packaged E2E harness. */
    private double zoomPercent() {
        return CapeZoomRange.percent(imgScale, RESOLUTIONS[selectedResolution][0],
                sourceImage.getWidth(), srcFrameHeight);
    }

    private void recalculateGrid() {
        int availW = (int) (this.width * 0.6);
        int availH = (int) (this.height * 0.65);
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];

        double scaleX = (double) availW / capeW;
        double scaleY = (double) availH / capeH;
        displayScale = Math.min(scaleX, scaleY);

        gridW = (int) (capeW * displayScale);
        gridH = (int) (capeH * displayScale);
        gridX = 20 + (availW - gridW) / 2;
        gridY = 30 + (availH - gridH) / 2;
    }

    private void resetImagePosition() {
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];

        // Scale so the source image covers the cape area (cover fit, using first frame dimensions)
        double scaleToFitW = (double) capeW / sourceImage.getWidth();
        double scaleToFitH = (double) capeH / srcFrameHeight;
        imgScale = Math.max(scaleToFitW, scaleToFitH);

        // Center
        imgOffsetX = (capeW - sourceImage.getWidth() * imgScale) / 2.0;
        imgOffsetY = (capeH - srcFrameHeight * imgScale) / 2.0;
        applySnap();
        // init() resets before it rebuilds, so on the first open this finds no slider and on a
        // resize it finds the outgoing one. Either way the slider initOpaqueControls() builds a few
        // lines later reads imgScale in its own constructor, so it lands on the reset value.
        syncZoomSlider();
        previewDirty = true;
    }

    /**
     * Change the zoom, keeping one point of cape space pinned where it is.
     *
     * <p>Every zoom input lands here: the wheel passes the cursor, the slider passes the centre of
     * the cape area. That is the whole reason the two cannot drift — one clamp, one anchor
     * correction, one resync, one dirty flag.
     *
     * <p>The correction is the wheel's existing one: with {@code a} the anchor and {@code r} the
     * scale ratio, {@code off' = a - (a - off) * r} leaves the image point that was under {@code a}
     * still under {@code a}. It is also path independent — zooming in two steps lands exactly where
     * one step of the combined ratio would — so dragging the slider slowly and flicking it produce
     * the same framing. That last property is why it chains off {@link #exactOffsetX} rather than
     * the snapped offset; see {@link #applySnap()}.
     *
     * <p>Offsets are snapped afterwards for the same reason the wheel snaps them: both composers
     * truncate the offset to whole cape pixels ({@code (int) imgOffsetX}), so an unsnapped preview
     * would show a framing the applied atlas cannot reproduce. The scale itself is never snapped —
     * quantising it would put notches in a control the user is dragging, and would change what the
     * wheel's 1.15 steps land on.
     */
    private void rescaleAbout(double targetScale, double anchorCapeX, double anchorCapeY) {
        int capeW = RESOLUTIONS[selectedResolution][0];
        double oldScale = imgScale;
        imgScale = CapeZoomRange.clampScale(
                targetScale, capeW, sourceImage.getWidth(), srcFrameHeight);
        if (oldScale > 0) {
            double ratio = imgScale / oldScale;
            imgOffsetX = CapeZoomRange.reanchorOffset(exactOffsetX, anchorCapeX, ratio);
            imgOffsetY = CapeZoomRange.reanchorOffset(exactOffsetY, anchorCapeY, ratio);
        }
        applySnap();
        syncZoomSlider();
        previewDirty = true;
    }

    /**
     * Push {@link #imgScale} back into the slider.
     *
     * <p>Writes the widget's {@code value} field directly, exactly as {@link
     * ChannelSlider#syncFromColor()} does. {@code AbstractSliderButton} only calls {@code
     * applyValue()} from its own {@code setValue}, which in turn is only reached from a click, a
     * drag or an arrow key — and which is {@code private} before 1.21.11 anyway. So state-to-slider
     * cannot re-enter slider-to-state, and this direction needs no re-entrancy guard; the {@code
     * syncingPicker} flag exists for the hex field, whose {@code setValue} really does call its
     * responder back.
     */
    private void syncZoomSlider() {
        if (zoomSlider != null) {
            zoomSlider.syncFromScale();
        }
    }

    /**
     * Snap offset to the nearest grid position in cape-space pixels.
     *
     * <p>The unrounded pair is kept as well, because a zoom's anchor correction is expressed
     * relative to the current offset and so has to chain off the value the last zoom really
     * produced. Chaining off the rounded one loses a whole class of input: a slider drag arrives as
     * many small steps, each asking for an offset change well under a pixel, and rounding each of
     * those to zero in turn leaves the offset frozen while the scale keeps climbing — the image
     * stops re-centring and grows out of a fixed corner instead. Measured on a 64x32 atlas, a drag
     * across half the track in single-pixel steps drifted about 149 cape pixels off centre that
     * way, against zero here.
     *
     * <p>Nothing but {@link #rescaleAbout} reads the unrounded pair, so what the grid draws and what
     * the composers write are exactly the snapped values they have always been.
     */
    private void applySnap() {
        exactOffsetX = imgOffsetX;
        exactOffsetY = imgOffsetY;
        int snap = SNAP_SIZES[snapIndex];
        if (snap > 0) {
            imgOffsetX = Math.round(imgOffsetX / snap) * snap;
            imgOffsetY = Math.round(imgOffsetY / snap) * snap;
        }
    }

    /**
     * Advance the animation frame and update the source texture in-place.
     */
    private void tickAnimation() {
        long now = System.currentTimeMillis();
        if (animStartTime == 0) animStartTime = now;

        // ~100ms per frame (10 FPS preview)
        int newFrame = (int) ((now - animStartTime) / 100) % frameCount;
        if (newFrame != currentAnimFrame) {
            currentAnimFrame = newFrame;
            updateSourceFrame();
            previewDirty = true;
        }
    }

    /**
     * Copy the current animation frame's pixels into the source DynamicTexture and upload to GPU.
     */
    private void updateSourceFrame() {
        if (sourceDynTexture == null) return;
        NativeImage pixels = sourceDynTexture.getPixels();
        if (pixels == null) return;

        int srcW = sourceImage.getWidth();
        int srcYOffset = currentAnimFrame * srcFrameHeight;

        for (int y = 0; y < srcFrameHeight; y++) {
            for (int x = 0; x < srcW; x++) {
                int argb = sourceImage.getRGB(x, srcYOffset + y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                MinecraftCompat.INSTANCE.setPixel(pixels, x, y, abgr);
            }
        }
        sourceDynTexture.upload();
    }

    /**
     * Compose a single frame at the current transform for the preview.
     */
    private BufferedImage composeFrame(int frameIndex) {
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];
        int srcW = sourceImage.getWidth();

        int drawX = (int) imgOffsetX;
        int drawY = (int) imgOffsetY;
        int drawW = scaledSourcePixels(srcW);
        int drawH = scaledSourcePixels(srcFrameHeight);

        BufferedImage cape = new BufferedImage(capeW, capeH, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = cape.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        int srcY = frameIndex * srcFrameHeight;
        g.drawImage(sourceImage,
                drawX, drawY, drawX + drawW, drawY + drawH,
                0, srcY, srcW, srcY + srcFrameHeight,
                null);
        g.dispose();

        if (mirrorFrontBack) {
            mirrorBackToFront(cape, 0);
        }
        finalizeCapeFrame(cape, 0, capeH);

        return cape;
    }

    /**
     * Finish one composed frame: clear the pixels outside the cape body and elytra UV face regions,
     * flatten every pixel onto the chosen fill when requested, then restore the transparent
     * complete vanilla alpha envelope that gives the Elytra cuboid its tapered silhouette.
     *
     * <p>This is the only pass either composer makes over individual pixels, and it is the last
     * step of both {@link #composeFrame(int)} (which feeds the 2D thumbnails and, through
     * {@code updatePreviewTexture} and {@code PlayerWidget.setCape}, the 3D player preview) and
     * {@link #composeCapeImage()} (what {@code applyAndClose} hands to {@code onApply}). Putting
     * the fill here is what makes the preview and the applied cape structurally unable to diverge:
     * there is one rule, invoked from one loop, and both callers run that loop over every frame.
     *
     * <p>With the toggle off the cleared value is {@code 0x00000000}; visible in-region pixels are
     * unchanged, while invalid pixels outside the complete vanilla Elytra UV envelope are
     * normalized to transparent. That includes the otherwise rectangular inner 10x20 face, which
     * vanilla leaves transparent and which the 2D preview intentionally does not present as art.
     *
     * At 1x (64x32):
     *   Cape body:                (0,0)-(22,17)
     *   Elytra top/bottom faces:  (24,0)-(44,2)
     *   Elytra side/front/back:   (22,2)-(46,22)
     * Everything else is cleared, and the complete Elytra region is then intersected with
     * vanilla's structural alpha envelope.
     */
    private void finalizeCapeFrame(BufferedImage image, int yOffset, int frameH) {
        int capeW = image.getWidth();
        int scale = capeW / 64;

        int capeBodyMaxX = 22 * scale;
        int capeBodyMaxY = yOffset + 17 * scale;

        int elytraTopMinX = 24 * scale;
        int elytraTopMaxX = 44 * scale;
        int elytraTopMaxY = yOffset + 2 * scale;

        int elytraSideMinX = 22 * scale;
        int elytraSideMaxX = 46 * scale;
        int elytraSideMaxY = yOffset + 22 * scale;

        // Snapshot the toggle so every frame of an animation strip is filled identically even if a
        // slider moves mid-compose.
        boolean fill = opaqueFill;
        int fillRgb = opaqueFillRgb;
        // Unused margins become the fill too so the toggle changes no cape-facing semantics. The
        // one intentional exception is the complete Elytra alpha envelope restored after this pass.
        int clearedValue = fill ? CapeOpaqueFill.opaque(fillRgb) : 0x00000000;

        for (int y = yOffset; y < yOffset + frameH; y++) {
            for (int x = 0; x < capeW; x++) {
                boolean inCapeBody = (x < capeBodyMaxX && y < capeBodyMaxY);
                boolean inElytraTop = (x >= elytraTopMinX && x < elytraTopMaxX && y < elytraTopMaxY);
                boolean inElytraSide = (x >= elytraSideMinX && x < elytraSideMaxX
                        && y >= elytraTopMaxY && y < elytraSideMaxY);
                if (!inCapeBody && !inElytraTop && !inElytraSide) {
                    image.setRGB(x, y, clearedValue);
                } else if (fill) {
                    int argb = image.getRGB(x, y);
                    int flattened = CapeOpaqueFill.flatten(argb, fillRgb);
                    if (flattened != argb) {
                        image.setRGB(x, y, flattened);
                    }
                }
            }
        }
        CapeElytraSilhouette.applyToFrame(image, yOffset, frameH);
    }

    /**
     * Convert the continuous zoom into the raster span used by both composers.
     *
     * <p>The slider mapping is logarithmic, so an exact-looking scale can return from its
     * position round trip one ULP below the requested value (for example {@code 1.0} becomes
     * {@code 0.9999999999999999}). Truncating that product turned a centred 128x64 source into a
     * 127x63 draw and resampled every UV edge. Nearest-pixel rounding preserves the intended
     * integer span while retaining smooth fractional zoom everywhere else.</p>
     */
    private int scaledSourcePixels(int sourcePixels) {
        return (int) Math.round(sourcePixels * imgScale);
    }

    /**
     * Copy the front face onto the back face within a single frame so both sides match.
     * UI "Front" face: UV (1, 1) size (10, 16) at 1x scale
     * UI "Cape Back" face: UV (12, 1) size (10, 16) at 1x scale
     * @param image The cape image
     * @param yOffset Y offset for the frame within an animation strip
     */
    private void mirrorBackToFront(BufferedImage image, int yOffset) {
        int scale = image.getWidth() / 64;
        int srcX = 1 * scale;
        int dstX = 12 * scale;
        int y0 = yOffset + 1 * scale;
        int regionW = 10 * scale;
        int regionH = 16 * scale;

        for (int y = 0; y < regionH; y++) {
            for (int x = 0; x < regionW; x++) {
                int pixel = image.getRGB(srcX + x, y0 + y);
                image.setRGB(dstX + x, y0 + y, pixel);
            }
        }
    }

    @Override
    //? if <26.1.2 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        // Advance animation frame for multi-frame GIFs
        if (frameCount > 1) {
            tickAnimation();
        }

        BackgroundRenderer.renderBackground(this, graphics, partialTick);

        // Title
        //? if <26.1.2 {
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
        //?} else {
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
        //?}

        // Instructions (centered under grid, underline only the action phrases)
        Component hintText = Component.literal("Drag to move").withStyle(style -> style.withUnderlined(true))
                .append(Component.literal(" · "))
                .append(Component.literal("Scroll to zoom").withStyle(style -> style.withUnderlined(true)));
        int hintX = gridX + (gridW - this.font.width(hintText)) / 2;
        //? if <26.1.2 {
        graphics.drawString(this.font, hintText, hintX, gridY + gridH + 8, 0xFFFFFFFF);
        //?} else {
        graphics.text(this.font, hintText, hintX, gridY + gridH + 8, 0xFFFFFFFF);
        //?}

        // Draw dark background for the grid area
        graphics.fill(gridX - 2, gridY - 2, gridX + gridW + 2, gridY + gridH + 2, 0xFF111111);
        renderSourceTransparencyBackdrop(graphics);

        // Enable scissor to clip the source image to the grid area
        graphics.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);

        // Render the source image behind the grid
        renderSourceImage(graphics);

        graphics.disableScissor();

        // Render cape grid overlay
        renderCapeGridOverlay(graphics);
        renderSourceBoundary(graphics);

        // The source and output are intentionally different concepts. Keeping both dimensions
        // visible prevents a padded 128x64 import targeting a 64x32 atlas from looking like the
        // editor silently resized or selected the wrong input.
        //? if <26.1.2 {
        graphics.drawString(this.font,
        //?} else {
        graphics.text(this.font,
        //?}
                Component.translatable("quickskin.cape.adjust_source_dimensions",
                        sourceDimensions()),
                gridX, gridY - 14, SOURCE_BOUNDARY_COLOR);

        // Render preview panel
        renderPreview(graphics);

        // Resolution label
        int rightPanelX = gridX + (int) (this.width * 0.6) + 15;
        //? if <26.1.2 {
        graphics.drawString(this.font,
        //?} else {
        graphics.text(this.font,
        //?}
                Component.translatable("quickskin.cape.adjust_output_resolution",
                        outputDimensions()),
                rightPanelX, gridY - 14, 0xFFFFFF55);

        // Highlight selected resolution by drawing an outline around its button area
        int resBtnX = gridX + (int) (this.width * 0.6) + 15;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (i == selectedResolution) {
                int btnW2 = Math.min(this.width - resBtnX - 10, 120);
                int by = gridY + i * 24;
                //? if <1.21.9 {
                graphics.renderOutline(resBtnX - 1, by - 1, btnW2 + 2, 22, 0xFF55FF55);
                //?} else {
                drawOutline(graphics, resBtnX - 1, by - 1, btnW2 + 2, 22, 0xFF55FF55);
                //?}
            }
        }

        // Note about GIF resolution cap (to the right of the resolution buttons)
        if (frameCount > 1) {
            int btnW3 = Math.min(this.width - resBtnX - 10, 120);
            int noteX = resBtnX + btnW3 + 8;
            int noteY = gridY;
            int noteMaxW = Math.max(60, this.width - noteX - 5);
            //? if <26.1.2 {
            graphics.drawWordWrap(this.font,
            //?} else {
            graphics.textWithWordWrap(this.font,
            //?}
                    Component.literal("Max 4x for animated capes to optimize performance and avoid server lag."),
                    noteX, noteY, noteMaxW, 0xFFFFAA00);
        }

        // Drawn before the widget pump so the picker's own sliders and hex field land on top of
        // its backdrop rather than under it.
        renderOpaqueControls(graphics);

        //? if <26.1.2 {
        super.render(graphics, mouseX, mouseY, partialTick);
        //?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        //?}
    }

    /** Swatch beside the control row, plus the picker popover chrome while it is open. */
    //? if <26.1.2 {
    private void renderOpaqueControls(GuiGraphics graphics) {
    //?} else {
    private void renderOpaqueControls(GuiGraphicsExtractor graphics) {
    //?}
        if (!opaqueFill) {
            return;
        }

        int fillArgb = CapeOpaqueFill.opaque(opaqueFillRgb);

        graphics.fill(swatchX - 1, swatchY - 1,
                swatchX + SWATCH_SIZE + 1, swatchY + SWATCH_SIZE + 1, 0xFF000000);
        graphics.fill(swatchX, swatchY, swatchX + SWATCH_SIZE, swatchY + SWATCH_SIZE, fillArgb);

        if (!pickerOpen) {
            return;
        }

        graphics.fill(pickerX, pickerY, pickerX + pickerW, pickerY + pickerH, 0xF0111111);
        //? if <1.21.9 {
        graphics.renderOutline(pickerX, pickerY, pickerW, pickerH, 0x80FFFFFF);
        //?} else {
        drawOutline(graphics, pickerX, pickerY, pickerW, pickerH, 0x80FFFFFF);
        //?}

        int bigSwatchX = pickerX + pickerW - 5 - SWATCH_SIZE;
        int bigSwatchBottom = pickerY + 57;
        graphics.fill(bigSwatchX, pickerY + 5, bigSwatchX + SWATCH_SIZE, bigSwatchBottom, fillArgb);
        //? if <1.21.9 {
        graphics.renderOutline(bigSwatchX, pickerY + 5, SWATCH_SIZE, bigSwatchBottom - pickerY - 5, 0x80FFFFFF);
        //?} else {
        drawOutline(graphics, bigSwatchX, pickerY + 5, SWATCH_SIZE, bigSwatchBottom - pickerY - 5, 0x80FFFFFF);
        //?}
    }

    //? if <26.1.2 {
    private void renderSourceTransparencyBackdrop(GuiGraphics graphics) {
    //?} else {
    private void renderSourceTransparencyBackdrop(GuiGraphicsExtractor graphics) {
    //?}
        graphics.fill(gridX, gridY, gridX + gridW, gridY + gridH, SOURCE_CHECKER_DARK);
        for (int y = gridY; y < gridY + gridH; y += SOURCE_CHECKER_SIZE) {
            int row = (y - gridY) / SOURCE_CHECKER_SIZE;
            for (int x = gridX; x < gridX + gridW; x += SOURCE_CHECKER_SIZE) {
                int column = (x - gridX) / SOURCE_CHECKER_SIZE;
                if (((row + column) & 1) == 0) {
                    graphics.fill(x, y,
                            Math.min(x + SOURCE_CHECKER_SIZE, gridX + gridW),
                            Math.min(y + SOURCE_CHECKER_SIZE, gridY + gridH),
                            SOURCE_CHECKER_LIGHT);
                }
            }
        }
    }

    //? if <26.1.2 {
    private void renderSourceImage(GuiGraphics graphics) {
    //?} else {
    private void renderSourceImage(GuiGraphicsExtractor graphics) {
    //?}
        if (sourceTextureLocation == null) return;

        //? if <1.21.6 {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        //?}

        // Convert cape-space offset to display-space (uses first frame dimensions)
        int drawX = gridX + (int) (imgOffsetX * displayScale);
        int drawY = gridY + (int) (imgOffsetY * displayScale);
        int drawW = (int) (sourceImage.getWidth() * imgScale * displayScale);
        int drawH = (int) (srcFrameHeight * imgScale * displayScale);

        GuiCompat.blit(graphics, sourceTextureLocation, drawX, drawY, drawW, drawH,
                0, 0, sourceImage.getWidth(), srcFrameHeight,
                sourceImage.getWidth(), srcFrameHeight);
    }

    //? if <26.1.2 {
    private void renderSourceBoundary(GuiGraphics graphics) {
    //?} else {
    private void renderSourceBoundary(GuiGraphicsExtractor graphics) {
    //?}
        int left = gridX + (int) (imgOffsetX * displayScale);
        int top = gridY + (int) (imgOffsetY * displayScale);
        int right = left + (int) (sourceImage.getWidth() * imgScale * displayScale);
        int bottom = top + (int) (srcFrameHeight * imgScale * displayScale);
        int clippedLeft = Math.max(left, gridX);
        int clippedTop = Math.max(top, gridY);
        int clippedRight = Math.min(right, gridX + gridW);
        int clippedBottom = Math.min(bottom, gridY + gridH);
        if (clippedRight - clippedLeft < 2 || clippedBottom - clippedTop < 2) {
            return;
        }
        if (left >= gridX && left <= gridX + gridW) {
            graphics.fill(left, clippedTop, Math.min(left + 2, clippedRight),
                    clippedBottom, SOURCE_BOUNDARY_COLOR);
        }
        if (right >= gridX && right <= gridX + gridW) {
            graphics.fill(Math.max(right - 2, clippedLeft), clippedTop, right,
                    clippedBottom, SOURCE_BOUNDARY_COLOR);
        }
        if (top >= gridY && top <= gridY + gridH) {
            graphics.fill(clippedLeft, top, clippedRight,
                    Math.min(top + 2, clippedBottom), SOURCE_BOUNDARY_COLOR);
        }
        if (bottom >= gridY && bottom <= gridY + gridH) {
            graphics.fill(clippedLeft, Math.max(bottom - 2, clippedTop), clippedRight,
                    bottom, SOURCE_BOUNDARY_COLOR);
        }
    }

    /** Stable dimension strings shared by the rendered labels and packaged evidence assertions. */
    private String sourceDimensions() {
        return sourceImage.getWidth() + "x" + srcFrameHeight;
    }

    private String outputDimensions() {
        return RESOLUTIONS[selectedResolution][0] + "x" + RESOLUTIONS[selectedResolution][1];
    }

    //? if <26.1.2 {
    private void renderCapeGridOverlay(GuiGraphics graphics) {
    //?} else {
    private void renderCapeGridOverlay(GuiGraphicsExtractor graphics) {
    //?}
        // Cape template overlay — Outer border
        //? if <1.21.9 {
        graphics.renderOutline(gridX, gridY, gridW, gridH, 0xAAFFFFFF);
        //?} else {
        drawOutline(graphics, gridX, gridY, gridW, gridH, 0xAAFFFFFF);
        //?}

        // --- Cape body faces ---
        // Cape back: (1,1) size 10x16 at 1x
        int backX = gridX + (int) (1.0 / 64.0 * gridW);
        int backY = gridY + (int) (1.0 / 32.0 * gridH);
        int backW = (int) (10.0 / 64.0 * gridW);
        int backH = (int) (16.0 / 32.0 * gridH);
        // Cape front: (12,1) size 10x16 at 1x
        int frontX = gridX + (int) (12.0 / 64.0 * gridW);
        int frontW = (int) (10.0 / 64.0 * gridW);

        // --- Elytra wing (from ElytraModel: texOffs(22,0), box 10x20x2) ---
        // Elytra UV occupies (22,0)->(46,22) on the 64x32 texture
        int eTopX = gridX + (int) (24.0 / 64.0 * gridW);   // top face X
        int eTopW = (int) (10.0 / 64.0 * gridW);
        int eBotX = gridX + (int) (34.0 / 64.0 * gridW);   // bottom face X
        int eBotW = (int) (10.0 / 64.0 * gridW);
        int eLX = gridX + (int) (22.0 / 64.0 * gridW);     // left side X
        int eLY = gridY + (int) (2.0 / 32.0 * gridH);      // body strip Y
        int eLH = (int) (20.0 / 32.0 * gridH);             // body strip H
        int eBackX = gridX + (int) (36.0 / 64.0 * gridW);  // back/outer face X
        int eBackW = (int) (10.0 / 64.0 * gridW);

        // Key boundary positions for dimming
        int capeBodyRightX = gridX + (int) (22.0 / 64.0 * gridW);
        int capeBodyBottomY = gridY + (int) (17.0 / 32.0 * gridH);
        int elytraTopStripBottom = gridY + (int) (2.0 / 32.0 * gridH);
        int elytraBottomY = gridY + (int) (22.0 / 32.0 * gridH);
        int elytraRightX = gridX + (int) (46.0 / 64.0 * gridW);

        // Dim unused areas (5 rectangles covering everything outside cape body + elytra)
        // 1. Gap between cape body top-right and elytra top: (22,0)->(24,2)
        graphics.fill(capeBodyRightX, gridY, eTopX, elytraTopStripBottom, 0x88000000);
        // 2. Right of elytra top strip: (44,0)->(64,2)
        graphics.fill(eBotX + eBotW, gridY, gridX + gridW, elytraTopStripBottom, 0x88000000);
        // 3. Right of elytra body: (46,2)->(64,22)
        graphics.fill(elytraRightX, elytraTopStripBottom, gridX + gridW, elytraBottomY, 0x88000000);
        // 4. Below cape body, left of elytra: (0,17)->(22,22)
        graphics.fill(gridX, capeBodyBottomY, capeBodyRightX, elytraBottomY, 0x88000000);
        // 5. Full bottom strip: (0,22)->(64,32)
        graphics.fill(gridX, elytraBottomY, gridX + gridW, gridY + gridH, 0x88000000);

        // --- Cape outlines ---
        //? if <1.21.9 {
        graphics.renderOutline(backX, backY, backW, backH, 0xFF5599FF);
        //?} else {
        drawOutline(graphics, backX, backY, backW, backH, 0xFF5599FF);
        //?}
        if (!mirrorFrontBack) {
            //? if <1.21.9 {
            graphics.renderOutline(frontX, backY, frontW, backH, 0xFF55FF55);
            //?} else {
            drawOutline(graphics, frontX, backY, frontW, backH, 0xFF55FF55);
            //?}
        } else {
            graphics.fill(frontX, backY, frontX + frontW, backY + backH, 0x88000000);
        }

        // Dim all elytra faces except the back/outer wing (barely visible in-game)
        // Top + bottom faces: (24,0)->(44,2)
        graphics.fill(eTopX, gridY, eBotX + eBotW, elytraTopStripBottom, 0x88000000);
        // Left side + front/inner + right side: (22,2)->(36,22)
        graphics.fill(eLX, eLY, eBackX, eLY + eLH, 0x88000000);

        // --- Back/outer wing: wing silhouette outline + corner dimming ---
        // Wing shape from elytra default texture (MinecraftCapes convention)
        // Each row: {startCol, endColExclusive} in 10-wide face space
        // Top-right = shoulder cutoff, bottom-left = wing tip taper
        int eBackFaceY = eLY; // same Y as other elytra body faces
        double colW = eBackW / 10.0;
        double rowH = eLH / 20.0;
        int wingColor = 0xFFFFAA00;

        for (int row = 0; row < 20; row++) {
            int left = CapeElytraSilhouette.wingStartColumn(row);
            int right = CapeElytraSilhouette.wingEndColumn(row);
            int sLeft = eBackX + (int) (left * colW);
            int sRight = eBackX + (int) (right * colW);
            int sTop = eBackFaceY + (int) (row * rowH);
            int sBot = eBackFaceY + (int) ((row + 1) * rowH);

            // Dim transparent corner pixels
            if (left > 0) {
                graphics.fill(eBackX, sTop, sLeft, sBot, 0x88000000);
            }
            if (right < 10) {
                graphics.fill(sRight, sTop, eBackX + eBackW, sBot, 0x88000000);
            }

            // Left & right border lines (1px)
            graphics.fill(sLeft, sTop, sLeft + 1, sBot, wingColor);
            graphics.fill(sRight - 1, sTop, sRight, sBot, wingColor);

            // Top edge (first row)
            if (row == 0) {
                graphics.fill(sLeft, sTop, sRight, sTop + 1, wingColor);
            }
            // Bottom edge (last row)
            if (row == 19) {
                graphics.fill(sLeft, sBot - 1, sRight, sBot, wingColor);
            }

            // Horizontal staircase connectors where boundary changes
            if (row > 0) {
                int prevLeft = CapeElytraSilhouette.wingStartColumn(row - 1);
                int prevRight = CapeElytraSilhouette.wingEndColumn(row - 1);
                if (left != prevLeft) {
                    int sPrevLeft = eBackX + (int) (Math.min(left, prevLeft) * colW);
                    int sMaxLeft = eBackX + (int) (Math.max(left, prevLeft) * colW);
                    graphics.fill(sPrevLeft, sTop, sMaxLeft, sTop + 1, wingColor);
                }
                if (right != prevRight) {
                    int sMinRight = eBackX + (int) (Math.min(right, prevRight) * colW);
                    int sMaxRight = eBackX + (int) (Math.max(right, prevRight) * colW);
                    graphics.fill(sMinRight, sTop, sMaxRight, sTop + 1, wingColor);
                }
            }
        }

        // --- Labels ---
        // Cape front (inner side, against player's body)
        String label = Component.translatable("quickskin.cape.adjust_front").getString();
        int labelW = this.font.width(label);
        //? if <26.1.2 {
        graphics.drawString(this.font, label,
        //?} else {
        graphics.text(this.font, label,
        //?}
                backX + (backW - labelW) / 2, backY + backH / 2 - 4, 0xFF5599FF);
        // Cape back (outer side, visible from behind) — hidden when mirroring from front
        if (!mirrorFrontBack) {
            String frontLabel = Component.translatable("quickskin.cape.adjust_back").getString();
            int frontLabelW = this.font.width(frontLabel);
            //? if <26.1.2 {
            graphics.drawString(this.font, frontLabel,
            //?} else {
            graphics.text(this.font, frontLabel,
            //?}
                    frontX + (frontW - frontLabelW) / 2, backY + backH / 2 - 4, 0xFF55FF55);
        }
        // Elytra back/outer (centered in wing shape)
        String eOuterLabel = Component.translatable("quickskin.cape.adjust_elytra").getString();
        int eOuterLabelW = this.font.width(eOuterLabel);
        if (eBackW > eOuterLabelW + 4) {
            //? if <26.1.2 {
            graphics.drawString(this.font, eOuterLabel,
            //?} else {
            graphics.text(this.font, eOuterLabel,
            //?}
                    eBackX + (eBackW - eOuterLabelW) / 2, eLY + eLH / 2 - 4, 0xFFFFAA00);
        }
    }

    //? if <26.1.2 {
    private void renderPreview(GuiGraphics graphics) {
    //?} else {
    private void renderPreview(GuiGraphicsExtractor graphics) {
    //?}
        int rightPanelX = gridX + (int) (this.width * 0.6) + 15;
        // Account for resolution buttons + reset + snap button spacing
        int previewStartY = gridY + RESOLUTIONS.length * 24 + 100;
        int maxPreviewW = Math.min(100, this.width - rightPanelX - 10);

        // Compose texture if dirty
        if (previewDirty) {
            updatePreviewTexture();
            previewDirty = false;
        }

        // Keep the 3D player widget in sync with the latest composed preview.
        if (playerWidget != null && previewTextureLocation != null
                && previewTextureLocation != lastPlayerWidgetCape) {
            playerWidget.setCape(previewTextureLocation, null);
            lastPlayerWidgetCape = previewTextureLocation;
        }

        if (previewTextureLocation == null) return;

        int capeW = RESOLUTIONS[selectedResolution][0];
        int scale = capeW / 64;

        // --- Cape Back preview (the visible part in-game) ---
        int backPreviewW = maxPreviewW / 2 - 2;
        int backPreviewH = (int) (backPreviewW * 1.6); // 10:16 aspect

        if (backPreviewH + previewStartY > this.height - 40) {
            backPreviewH = this.height - 40 - previewStartY;
            backPreviewW = (int) (backPreviewH / 1.6);
        }

        String backLabel = Component.translatable("quickskin.cape.adjust_front").getString();
        //? if <26.1.2 {
        graphics.drawString(this.font, backLabel, rightPanelX, previewStartY - 12, 0xFF5599FF);
        //?} else {
        graphics.text(this.font, backLabel, rightPanelX, previewStartY - 12, 0xFF5599FF);
        //?}

        graphics.fill(rightPanelX - 1, previewStartY - 1,
                rightPanelX + backPreviewW + 1, previewStartY + backPreviewH + 1, 0xFF333333);

        //? if <1.21.6 {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        //?}
        // Cape back UV: (1*s, 1*s) size (10*s, 16*s)
        GuiCompat.blit(graphics, previewTextureLocation,
                rightPanelX, previewStartY, backPreviewW, backPreviewH,
                1 * scale, 1 * scale, 10 * scale, 16 * scale, capeW, capeW / 2);

        // --- Cape Front preview (against the player's body) ---
        int frontX = rightPanelX + backPreviewW + 6;

        String frontLabel = Component.translatable("quickskin.cape.adjust_back").getString();
        //? if <26.1.2 {
        graphics.drawString(this.font, frontLabel, frontX, previewStartY - 12, 0xFF55FF55);
        //?} else {
        graphics.text(this.font, frontLabel, frontX, previewStartY - 12, 0xFF55FF55);
        //?}

        graphics.fill(frontX - 1, previewStartY - 1,
                frontX + backPreviewW + 1, previewStartY + backPreviewH + 1, 0xFF333333);

        //? if <1.21.6 {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        //?}
        // Cape front UV: (12*s, 1*s) size (10*s, 16*s)
        GuiCompat.blit(graphics, previewTextureLocation,
                frontX, previewStartY, backPreviewW, backPreviewH,
                12 * scale, 1 * scale, 10 * scale, 16 * scale, capeW, capeW / 2);

        // --- Elytra preview (outer/back wing — the visible part in-game) ---
        // ElytraModel: texOffs(22,0), box 10x20x2, texture 64x32
        // Back/outer face UV: (36, 2) size (10, 20)
        int elytraPreviewW = backPreviewW;
        int elytraPreviewH = (int) (elytraPreviewW * 2.0); // 10:20 aspect

        int elytraY = previewStartY + backPreviewH + 18;
        if (elytraPreviewH + elytraY > this.height - 40) {
            elytraPreviewH = this.height - 40 - elytraY;
            elytraPreviewW = (int) (elytraPreviewH / 2.0);
        }

        if (elytraPreviewH > 4) {
            // Elytra outer (back face — what you see from behind)
            String eOuterLabel = Component.translatable("quickskin.cape.adjust_elytra").getString();
            //? if <26.1.2 {
            graphics.drawString(this.font, eOuterLabel, rightPanelX, elytraY - 12, 0xFFFFAA00);
            //?} else {
            graphics.text(this.font, eOuterLabel, rightPanelX, elytraY - 12, 0xFFFFAA00);
            //?}

            graphics.fill(rightPanelX - 1, elytraY - 1,
                    rightPanelX + elytraPreviewW + 1, elytraY + elytraPreviewH + 1, 0xFF333333);

            //? if <1.21.6 {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            //?}
            // Back/outer wing UV: (36*s, 2*s) size (10*s, 20*s)
            GuiCompat.blit(graphics, previewTextureLocation,
                    rightPanelX, elytraY, elytraPreviewW, elytraPreviewH,
                    36 * scale, 2 * scale, 10 * scale, 20 * scale, capeW, capeW / 2);

            // Mask the preview corners to match the wing silhouette
            double pColW = elytraPreviewW / 10.0;
            double pRowH = elytraPreviewH / 20.0;
            for (int row = 0; row < 20; row++) {
                int left = CapeElytraSilhouette.wingStartColumn(row);
                int right = CapeElytraSilhouette.wingEndColumn(row);
                int rTop = elytraY + (int) (row * pRowH);
                int rBot = elytraY + (int) ((row + 1) * pRowH);
                if (left > 0) {
                    graphics.fill(rightPanelX, rTop,
                            rightPanelX + (int) (left * pColW), rBot, 0xFF333333);
                }
                if (right < 10) {
                    graphics.fill(rightPanelX + (int) (right * pColW), rTop,
                            rightPanelX + elytraPreviewW, rBot, 0xFF333333);
                }
            }
        }
    }

    private void updatePreviewTexture() {
        // Preview shows the current animation frame (or the only frame for static images)
        BufferedImage cape = composeFrame(currentAnimFrame);

        if (previewTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(previewTextureLocation);
        }

        NativeImage ni = convertToNativeImage(cape);
        //? if <1.21.6 {
        previewDynTexture = new DynamicTexture(ni);
        previewTextureLocation = Minecraft.getInstance().getTextureManager()
                .register("quickskin/cape_adjust_preview", previewDynTexture);
        //?} else if <1.21.11 {
        previewDynTexture = new DynamicTexture(() -> "quickskin_cape_adjust_preview", ni);
        previewTextureLocation = ResourceLocation.fromNamespaceAndPath(
                QuickSkin.MOD_ID, "cape_adjust_preview");
        Minecraft.getInstance().getTextureManager().register(previewTextureLocation, previewDynTexture);
        //?} else {
        previewDynTexture = new DynamicTexture(() -> "quickskin_cape_adjust_preview", ni);
        previewTextureLocation = Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "cape_adjust_preview");
        Minecraft.getInstance().getTextureManager().register(previewTextureLocation, previewDynTexture);
        //?}
    }

    /**
     * Compose the final cape image from source + positioning.
     * For animation strips, applies the same transform to each frame.
     */
    private BufferedImage composeCapeImage() {
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];
        int srcW = sourceImage.getWidth();

        int drawX = (int) imgOffsetX;
        int drawY = (int) imgOffsetY;
        int drawW = scaledSourcePixels(srcW);
        int drawH = scaledSourcePixels(srcFrameHeight);

        BufferedImage cape = new BufferedImage(capeW, capeH * frameCount, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = cape.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        for (int i = 0; i < frameCount; i++) {
            int srcY = i * srcFrameHeight;
            int dstYOffset = i * capeH;
            // Draw this frame at the user's chosen position/scale in cape space
            g.drawImage(sourceImage,
                    drawX, dstYOffset + drawY, drawX + drawW, dstYOffset + drawY + drawH,
                    0, srcY, srcW, srcY + srcFrameHeight,
                    null);
        }
        g.dispose();

        if (mirrorFrontBack) {
            for (int i = 0; i < frameCount; i++) {
                mirrorBackToFront(cape, i * capeH);
            }
        }
        for (int i = 0; i < frameCount; i++) {
            finalizeCapeFrame(cape, i * capeH, capeH);
        }

        return cape;
    }

    // --- Input handling ---

    @Override
    //? if <1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
    //?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        if (super.mouseClicked(event, focused)) return true;
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
    //?}

        // The picker's own widgets already had their chance above. Whatever is left is either the
        // popover's chrome (swallowed) or a click elsewhere, which dismisses it — in both cases the
        // click must not also grab the image underneath.
        if (pickerOpen && opaqueFill) {
            if (!isInPicker(mouseX, mouseY)) {
                setPickerOpen(false);
            }
            return true;
        }

        // Start dragging if clicked inside the grid area
        if (button == 0 && mouseX >= gridX && mouseX <= gridX + gridW
                && mouseY >= gridY && mouseY <= gridY + gridH) {
            isDragging = true;
            dragStartX = mouseX;
            dragStartY = mouseY;
            dragStartOffsetX = imgOffsetX;
            dragStartOffsetY = imgOffsetY;
            return true;
        }
        return false;
    }

    @Override
    //? if <1.21.9 {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
    //?} else {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        int button = GuiCompat.mouseButton(event);
    //?}
        if (button == 0 && isDragging) {
            isDragging = false;
            previewDirty = true;
            return true;
        }
        //? if <1.21.9 {
        return super.mouseReleased(mouseX, mouseY, button);
        //?} else {
        return super.mouseReleased(event);
        //?}
    }

    @Override
    //? if <1.21.9 {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    //?} else {
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
    //?}
        if (isDragging && button == 0) {
            // Convert display-space drag to cape-space
            double deltaX = (mouseX - dragStartX) / displayScale;
            double deltaY = (mouseY - dragStartY) / displayScale;
            imgOffsetX = dragStartOffsetX + deltaX;
            imgOffsetY = dragStartOffsetY + deltaY;
            applySnap();
            previewDirty = true;
            return true;
        }
        //? if <1.21.9 {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        //?} else {
        return super.mouseDragged(event, dragX, dragY);
        //?}
    }

    @Override
    //? if <1.21 {
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
    //?} else {
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
    //?}
        // The picker sits over the grid; scrolling on it must not zoom the image behind it.
        if (isInPicker(mouseX, mouseY)) {
            return true;
        }
        //? if <1.21 {
        if (zoomAtCursor(mouseX, mouseY, delta > 0)) {
        //?} else {
        if (zoomAtCursor(mouseX, mouseY, deltaY > 0)) {
        //?}
            return true;
        }
        //? if <1.21 {
        return super.mouseScrolled(mouseX, mouseY, delta);
        //?} else {
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
        //?}
    }

    /**
     * One wheel notch over the grid.
     *
     * <p>The whole body of the wheel's zoom lives here rather than in {@code mouseScrolled} for two
     * reasons: {@code mouseScrolled}'s signature is era-branched and its body is not, and the name
     * of a Minecraft override is remapped in a shipped jar while a mod-owned one is not — which is
     * what lets the packaged E2E harness deliver a real wheel notch by reflection.
     *
     * @return false when the cursor is outside the grid, so the caller can pass the scroll on
     */
    private boolean zoomAtCursor(double mouseX, double mouseY, boolean zoomIn) {
        if (mouseX < gridX || mouseX > gridX + gridW
                || mouseY < gridY || mouseY > gridY + gridH) {
            return false;
        }
        double zoomFactor = zoomIn ? CapeZoomRange.WHEEL_STEP : 1.0 / CapeZoomRange.WHEEL_STEP;
        // Zoom toward the cursor: the anchor is the cape-space point under the mouse, which is what
        // has always kept the pixel under the pointer from sliding away.
        rescaleAbout(imgScale * zoomFactor,
                (mouseX - gridX) / displayScale,
                (mouseY - gridY) / displayScale);
        return true;
    }

    private void applyAndClose() {
        BufferedImage composedCape = composeCapeImage();
        completed = true;
        onApply.accept(composedCape);
        onClose();
    }

    @Override
    public void onClose() {
        BackgroundRenderer.cleanup();

        if (!completed) {
            completed = true;
            onCancel.run();
        }

        // Clean up textures
        if (sourceTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(sourceTextureLocation);
            sourceTextureLocation = null;
        }
        if (previewTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(previewTextureLocation);
            previewTextureLocation = null;
        }

        if (this.minecraft != null) {
            //? if <26.2 {
            this.minecraft.setScreen(parent);
            //?} else {
            this.minecraft.gui.setScreen(parent);
            //?}
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    //? if >=1.21.6 {
        //? if <26.1.2 {
    @Override
    protected void renderBlurredBackground(GuiGraphics guiGraphics) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }
        //?} else {
    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor guiGraphics) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }
        //?}
    //?}

    //? if >=1.21.9 {
    /**
     * Draws an outline immediately using fill calls.
     */
    private static void drawOutline(
            //? if <26.1.2 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }
    //?}

    /**
     * Convert BufferedImage to NativeImage for texture registration
     */
    private static NativeImage convertToNativeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage nativeImage = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                MinecraftCompat.INSTANCE.setPixel(nativeImage, x, y, abgr);
            }
        }
        return nativeImage;
    }
}
