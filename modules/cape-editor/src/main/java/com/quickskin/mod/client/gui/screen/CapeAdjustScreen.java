package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.BackgroundRenderer;
import com.quickskin.mod.client.gui.util.UiText;
import com.quickskin.mod.client.gui.widget.CapeEditorStyle;
import com.quickskin.mod.client.gui.widget.CapeSwatchButton;
import com.quickskin.mod.client.gui.widget.CapeToggleSwitch;
import com.quickskin.mod.client.gui.widget.CapeToolButton;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.gui.widget.PrimaryButton;
import com.quickskin.mod.client.gui.widget.StyledButton;
import com.quickskin.mod.client.rendering.LocalLookPreview;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.CapeElytraSilhouette;
import com.quickskin.mod.common.util.CapeOpaqueFill;
import com.quickskin.mod.common.util.CapeZoomRange;
import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.quickskin.mod.platform.MinecraftTextureUploads;
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
    private static final String[] RESOLUTION_LABELS = {"1x", "2x", "4x", "8x", "16x"};
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
    private CapeToggleSwitch opaqueToggleButton;
    private CapeSwatchButton fillColorButton;
    private ChannelSlider redSlider;
    private ChannelSlider greenSlider;
    private ChannelSlider blueSlider;
    private EditBox hexField;
    /** Second view of {@link #imgScale}; recreated by every init(), never a value of its own. */
    private ZoomSlider zoomSlider;
    // Colour picker popover bounds, computed in init() and used for hit testing + rendering.
    private int pickerX, pickerY, pickerW, pickerH;
    private static final int PICKER_W = 200;
    private static final int PICKER_H = 84;
    private static final int SWATCH_SIZE = 20;

    // Layout, in GUI pixels
    private static final int HEADER_H = 22;
    private static final int HEADER_COLOR = 0xFF2E3A4A;
    private static final int MARGIN = 8;
    private static final int CANVAS_FRAME = 3;
    private static final int PANEL_GAP = 4;
    private static final int PANEL_PAD = 6;
    private static final int ZOOM_PANEL_H = 40;
    private static final int RIGHT_MIN_W = 170;
    private static final int RIGHT_MAX_W = 210;
    private static final int FOOTER_BUTTON_H = 20;
    private static final int SECTION_LABEL_H = 12;
    private static final int SECTION_GAP = 8;
    private static final int RES_BUTTON_H = 16;
    private static final int TOOL_BUTTON_H = 18;
    private static final int OPAQUE_ROW_H = 16;
    private static final int SWATCH_BUTTON_SIZE = 16;
    private static final int SWITCH_W = 24;
    private static final int SWITCH_H = 14;
    private static final int ZOOM_STEP_BUTTON = 18;
    /** Absolute floor, reached only where the canvas is too narrow to seat the zoom row. */
    private static final int ZOOM_FLOOR_W = 20;
    private static final int CHANNEL_LABEL_W = 36;
    private static final int VIEW_BUTTON_H = 16;

    // Where init() put the canvas, the panels and the right panel's sections
    private int canvasLeft, canvasTop, canvasAvailW, canvasAvailH;
    private int frameX0, frameY0, frameX1, frameY1;
    private int zoomX0, zoomY0, zoomX1, zoomY1;
    private int panelX0, panelY0, panelX1, panelY1;
    private int previewX0, previewY0, previewX1, previewY1;
    private int resolutionLabelY, resolutionButtonsY, outputTextY;
    private int positionLabelY, toolButtonsY, opaqueRowY;
    private int zoomPercentX;
    private CapeToolButton[] resolutionButtons;
    private CapeToolButton snapButton;
    private CapeToolButton mirrorButton;
    private CapeToolButton capeViewButton;
    private CapeToolButton elytraViewButton;
    /** Whether the 3D preview wears the cape's texture on elytra wings rather than as the cape. */
    private boolean elytraView;
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
        sourceDynTexture = MinecraftTextureUploads.create(() -> "quickskin_cape_adjust_source", nativeImage);
        //? if <1.21.4 {
        sourceTextureLocation = Minecraft.getInstance().getTextureManager()
                .register("quickskin/cape_adjust_source", sourceDynTexture);
        //?} else if <1.21.11 {
        sourceTextureLocation = ResourceLocation.fromNamespaceAndPath(
                QuickSkinInfo.MOD_ID, "cape_adjust_source");
        Minecraft.getInstance().getTextureManager().register(sourceTextureLocation, sourceDynTexture);
        //?} else {
        sourceTextureLocation = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "cape_adjust_source");
        Minecraft.getInstance().getTextureManager().register(sourceTextureLocation, sourceDynTexture);
        //?}

        // A resolution larger than the source, or past the animated-cape cap, cannot be chosen: fall
        // back to the largest one that can
        if (!isResolutionAvailable(selectedResolution)) {
            for (int i = maxResolutionIndex(); i >= 0; i--) {
                if (isResolutionAvailable(i)) {
                    selectedResolution = i;
                    break;
                }
            }
        }

        layoutPanels();
        // Center image initially to cover the cape area
        resetImagePosition();

        this.clearWidgets();
        initResolutionControls();
        initPositionControls();
        initOpaqueControls();
        initZoomControls();
        initFooter();
        initPlayerPreview();
    }

    /**
     * Places the screen's parts: a header strip with the title and the hint; the canvas on the left
     * with the zoom panel under it; a panel on the right with the previews, then the Resolution,
     * Position and Opaque fill sections from the bottom up, ending level with the zoom panel; and
     * Cancel and Apply at the bottom right.
     */
    private void layoutPanels() {
        int footerY = this.height - MARGIN - FOOTER_BUTTON_H;
        int bodyTop = HEADER_H + MARGIN;
        int bodyBottom = footerY - MARGIN;
        int rightWidth = Mth.clamp(this.width * 3 / 10, RIGHT_MIN_W, RIGHT_MAX_W);
        canvasLeft = MARGIN + CANVAS_FRAME;
        canvasTop = bodyTop + CANVAS_FRAME;
        canvasAvailW = Math.max(64, this.width - 3 * MARGIN - rightWidth - 2 * CANVAS_FRAME);
        canvasAvailH = Math.max(32, bodyBottom - bodyTop - ZOOM_PANEL_H - PANEL_GAP - 2 * CANVAS_FRAME);
        recalculateGrid();

        frameX0 = gridX - CANVAS_FRAME;
        frameY0 = gridY - CANVAS_FRAME;
        frameX1 = gridX + gridW + CANVAS_FRAME;
        frameY1 = gridY + gridH + CANVAS_FRAME;
        zoomX0 = frameX0;
        zoomX1 = frameX1;
        zoomY0 = frameY1 + PANEL_GAP;
        zoomY1 = zoomY0 + ZOOM_PANEL_H;
        // A canvas narrower than its room, on a short window, leaves the right panel the rest
        panelX0 = frameX1 + MARGIN;
        panelX1 = this.width - MARGIN;
        panelY0 = frameY0;
        panelY1 = zoomY1;

        opaqueRowY = panelY1 - PANEL_PAD - OPAQUE_ROW_H;
        toolButtonsY = opaqueRowY - SECTION_GAP - TOOL_BUTTON_H;
        positionLabelY = toolButtonsY - SECTION_LABEL_H;
        outputTextY = positionLabelY - SECTION_GAP - 9;
        resolutionButtonsY = outputTextY - 3 - RES_BUTTON_H;
        resolutionLabelY = resolutionButtonsY - SECTION_LABEL_H;
        previewX0 = panelX0 + PANEL_PAD;
        previewX1 = panelX1 - PANEL_PAD;
        previewY0 = panelY0 + PANEL_PAD;
        previewY1 = resolutionLabelY - 4;
    }

    private int maxResolutionIndex() {
        // Animated capes stop at 4x (256x128) to avoid lag on servers
        return frameCount > 1 ? 2 : RESOLUTIONS.length - 1;
    }

    private boolean isResolutionAvailable(int index) {
        return index <= maxResolutionIndex()
                && RESOLUTIONS[index][0] <= sourceImage.getWidth()
                && RESOLUTIONS[index][1] <= srcFrameHeight;
    }

    /** One button per output size, 1x to 16x, in a row; the chosen one is blue. */
    private void initResolutionControls() {
        int x0 = panelX0 + PANEL_PAD;
        int innerWidth = panelX1 - PANEL_PAD - x0;
        int gap = 2;
        int buttonWidth = (innerWidth - gap * (RESOLUTIONS.length - 1)) / RESOLUTIONS.length;
        resolutionButtons = new CapeToolButton[RESOLUTIONS.length];
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            final int index = i;
            CapeToolButton button = new CapeToolButton(
                    x0 + i * (buttonWidth + gap), resolutionButtonsY, buttonWidth, RES_BUTTON_H, null,
                    Component.literal(RESOLUTION_LABELS[i]),
                    Component.literal(RESOLUTIONS[i][0] + "x" + RESOLUTIONS[i][1]),
                    b -> selectResolution(index));
            // No tooltip: the output size under the row names the chosen one
            button.active = isResolutionAvailable(i);
            resolutionButtons[i] = this.addRenderableWidget(button);
        }
        updateResolutionButtons();
    }

    private void selectResolution(int index) {
        int oldRes = selectedResolution;
        selectedResolution = index;
        // Scale position/zoom proportionally to new resolution
        double ratio = (double) RESOLUTIONS[index][0] / RESOLUTIONS[oldRes][0];
        imgOffsetX *= ratio;
        imgOffsetY *= ratio;
        imgScale *= ratio;
        applySnap();
        recalculateGrid();
        // Both ends of the legal range carry the same factor of capeW, so this leaves the
        // slider exactly where it was — resyncing it is the discipline, not a correction.
        syncZoomSlider();
        updateResolutionButtons();
        previewDirty = true;
    }

    private void updateResolutionButtons() {
        if (resolutionButtons == null) {
            return;
        }
        for (int i = 0; i < resolutionButtons.length; i++) {
            resolutionButtons[i].setSelected(i == selectedResolution);
        }
    }

    /**
     * Center, Snap and Mirror, side by side, each an icon and a short label. Each is as wide as its
     * label needs, and the rest of the row is shared out evenly; Snap's label is the shortest.
     */
    private void initPositionControls() {
        int x0 = panelX0 + PANEL_PAD;
        int innerWidth = panelX1 - PANEL_PAD - x0;
        int gap = 4;
        Component centerLabel = Component.translatable("quickskin.cape.adjust_center");
        Component mirrorLabel = Component.translatable("quickskin.cape.adjust_mirror");
        int snapLabelWidth = 0;
        for (int i = 0; i < SNAP_LABELS.length; i++) {
            Component label = i == 0 ? Component.translatable("quickskin.cape.adjust_snap_off")
                    : Component.literal(SNAP_LABELS[i]);
            snapLabelWidth = Math.max(snapLabelWidth, this.font.width(label));
        }
        int[] widths = {
                toolButtonWidth(this.font.width(centerLabel)),
                toolButtonWidth(snapLabelWidth),
                toolButtonWidth(this.font.width(mirrorLabel))};
        int spare = innerWidth - 2 * gap - widths[0] - widths[1] - widths[2];
        for (int i = 0; i < widths.length; i++) {
            // A narrow panel shares out the shortfall too, and the labels then give way to icons
            widths[i] += spare / 3 + (i < spare % 3 ? 1 : 0);
        }
        int x = x0;

        CapeToolButton reset = new CapeToolButton(x, toolButtonsY, widths[0], TOOL_BUTTON_H,
                CapeEditorStyle.ICON_CENTER, centerLabel,
                Component.translatable("quickskin.cape.adjust_reset"),
                b -> { resetImagePosition(); previewDirty = true; });
        reset.setTooltip(Tooltip.create(Component.translatable("quickskin.cape.adjust_reset_tip")));
        this.addRenderableWidget(reset);
        x += widths[0] + gap;

        // Cycles through the snap sizes
        snapButton = this.addRenderableWidget(new CapeToolButton(
                x, toolButtonsY, widths[1], TOOL_BUTTON_H,
                CapeEditorStyle.ICON_SNAP, snapLabel(), snapMessage(),
                b -> {
                    snapIndex = (snapIndex + 1) % SNAP_SIZES.length;
                    updateSnapButton();
                    applySnap();
                    previewDirty = true;
                }));
        updateSnapButton();
        x += widths[1] + gap;

        // Mirror front=back toggle
        mirrorButton = this.addRenderableWidget(new CapeToolButton(
                x, toolButtonsY, x0 + innerWidth - x, TOOL_BUTTON_H,
                CapeEditorStyle.ICON_MIRROR, mirrorLabel, mirrorMessage(),
                b -> {
                    mirrorFrontBack = !mirrorFrontBack;
                    updateMirrorButton();
                    previewDirty = true;
                }));
        updateMirrorButton();
    }

    /** The width a {@link CapeToolButton} needs for its icon and a label {@code labelWidth} wide. */
    private static int toolButtonWidth(int labelWidth) {
        return CapeToolButton.contentWidth(labelWidth) + CapeToolButton.TEXT_MARGIN;
    }

    private Component snapLabel() {
        return snapIndex == 0
                ? Component.translatable("quickskin.cape.adjust_snap_off")
                : Component.literal(SNAP_LABELS[snapIndex]);
    }

    private Component snapMessage() {
        return Component.translatable("quickskin.cape.adjust_snap", snapLabel());
    }

    private void updateSnapButton() {
        snapButton.setLabel(snapLabel());
        snapButton.setMessage(snapMessage());
        snapButton.setTooltip(Tooltip.create(snapMessage().copy().append("\n")
                .append(Component.translatable("quickskin.cape.adjust_snap_tip"))));
    }

    private Component mirrorMessage() {
        return Component.translatable(mirrorFrontBack
                ? "quickskin.cape.adjust_mirror_on"
                : "quickskin.cape.adjust_mirror_off");
    }

    private void updateMirrorButton() {
        mirrorButton.setSelected(mirrorFrontBack);
        mirrorButton.setMessage(mirrorMessage());
        mirrorButton.setTooltip(Tooltip.create(mirrorMessage().copy().append("\n")
                .append(Component.translatable("quickskin.cape.adjust_mirror_tip"))));
    }

    /** Zoom out, the zoom slider and its percentage, and zoom in, in the panel under the canvas. */
    private void initZoomControls() {
        int rowY = zoomY0 + 18;
        int x0 = zoomX0 + PANEL_PAD;
        int x1 = zoomX1 - PANEL_PAD;
        CapeToolButton zoomOut = new CapeToolButton(x0, rowY, ZOOM_STEP_BUTTON, ZOOM_STEP_BUTTON,
                CapeEditorStyle.ICON_MINUS, Component.empty(),
                Component.translatable("quickskin.cape.adjust_zoom_out"), b -> zoomStep(false));
        zoomOut.setTooltip(Tooltip.create(zoomOut.getMessage()));
        this.addRenderableWidget(zoomOut);
        CapeToolButton zoomIn = new CapeToolButton(x1 - ZOOM_STEP_BUTTON, rowY, ZOOM_STEP_BUTTON,
                ZOOM_STEP_BUTTON, CapeEditorStyle.ICON_PLUS, Component.empty(),
                Component.translatable("quickskin.cape.adjust_zoom_in"), b -> zoomStep(true));
        zoomIn.setTooltip(Tooltip.create(zoomIn.getMessage()));
        this.addRenderableWidget(zoomIn);

        int percentRoom = this.font.width("9999%") + 6;
        int sliderX = x0 + ZOOM_STEP_BUTTON + 6;
        int sliderRight = x1 - ZOOM_STEP_BUTTON - 6 - percentRoom;
        zoomPercentX = sliderRight + 6;
        zoomSlider = this.addRenderableWidget(new ZoomSlider(
                sliderX, rowY + 2, Math.max(ZOOM_FLOOR_W, sliderRight - sliderX), 14));
    }

    /** One step of the zoom buttons: a wheel notch, about the middle of the cape like the slider. */
    private void zoomStep(boolean zoomIn) {
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];
        double factor = zoomIn ? CapeZoomRange.WHEEL_STEP : 1.0 / CapeZoomRange.WHEEL_STEP;
        rescaleAbout(imgScale * factor, capeW / 2.0, capeH / 2.0);
    }

    private void initFooter() {
        int footerY = this.height - MARGIN - FOOTER_BUTTON_H;
        Component applyLabel = Component.translatable("quickskin.cape.adjust_apply");
        Component cancelLabel = Component.translatable("gui.cancel");
        int applyWidth = Math.max(90, this.font.width(applyLabel) + 24);
        int cancelWidth = Math.max(70, this.font.width(cancelLabel) + 24);
        int applyX = this.width - MARGIN - applyWidth;
        this.addRenderableWidget(new StyledButton(applyX - 6 - cancelWidth, footerY, cancelWidth,
                FOOTER_BUTTON_H, cancelLabel, b -> onClose()));
        this.addRenderableWidget(new PrimaryButton(applyX, footerY, applyWidth, FOOTER_BUTTON_H,
                applyLabel, b -> applyAndClose()));
    }

    /**
     * The top of the right panel: Cape and Elytra buttons choosing what the model wears, and the
     * player wearing the composed cape, from behind, under them. Without room for them both are
     * left out.
     */
    private void initPlayerPreview() {
        // A rebuild (a window resize) makes a new model, which takes the preview cape afresh
        playerWidget = null;
        lastPlayerWidgetCape = null;
        capeViewButton = null;
        elytraViewButton = null;
        int areaWidth = previewX1 - previewX0;
        if (previewY1 - previewY0 < 24 + VIEW_BUTTON_H + 4) {
            return;
        }
        initViewButtons();
        int areaTop = previewY0 + VIEW_BUTTON_H + 4;
        int areaHeight = previewY1 - areaTop;
        int modelX0 = previewX0;
        int modelWidth = areaWidth;
        if (modelWidth < 30 || areaHeight < 40) {
            return;
        }

        //? if <1.21.11 {
        ResourceLocation skinLocation = null;
        //?} else {
        Identifier skinLocation = null;
        //?}
        String modelType = "classic";
        LocalPlayer player = Minecraft.getInstance().player;
        // The saved skin, unless a CPM model is the latest look choice: then the player's own
        // skin (the live one in a world, the imported one on the title screen).
        String previewSkinHash = LocalLookPreview.skinHash(player != null);

        if (!previewSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(previewSkinHash);
            if (metadata != null) {
                skinLocation = assetManager.getTextureLocation(previewSkinHash, TextureQuality.FULL);
                modelType = assetManager.getSkinModelPreference(previewSkinHash);
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
            // The player's own skin is drawn, so its model is the player's too.
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
                modelX0, areaTop, modelWidth, areaHeight,
                skinLocation, null, null, modelType));
        playerWidget.setContext(PlayerWidget.WidgetContext.CAPE_MENU);
        // Feet on the bottom of the area, no taller than the area
        playerWidget.setCustomReferencePoint(modelX0 + modelWidth / 2, previewY1 - 2);
        playerWidget.setScaleCap((areaHeight - 4) / 1.95f);
        // Face the player's back to the camera so the cape is visible (same default as cape menu).
        playerWidget.setRotationState(20.0f, 200.0f);
        playerWidget.setElytraPreview(elytraView);
    }

    /** Cape and Elytra, side by side across the panel; the one the model wears is blue. */
    private void initViewButtons() {
        int width = previewX1 - previewX0;
        int half = width / 2;
        Component capeLabel = Component.translatable("quickskin.cape.adjust_view_cape");
        Component elytraLabel = Component.translatable("quickskin.cape.adjust_elytra");
        capeViewButton = this.addRenderableWidget(new CapeToolButton(
                previewX0, previewY0, half + 1, VIEW_BUTTON_H, CapeEditorStyle.ICON_CAPE,
                capeLabel, capeLabel, b -> setElytraView(false)));
        capeViewButton.setTooltip(Tooltip.create(Component.translatable("quickskin.cape.adjust_view_cape_tip")));
        elytraViewButton = this.addRenderableWidget(new CapeToolButton(
                previewX0 + half, previewY0, width - half, VIEW_BUTTON_H, CapeEditorStyle.ICON_WINGS,
                elytraLabel, elytraLabel, b -> setElytraView(true)));
        elytraViewButton.setTooltip(Tooltip.create(Component.translatable("quickskin.cape.adjust_view_elytra_tip")));
        updateViewButtons();
    }

    private void setElytraView(boolean elytra) {
        elytraView = elytra;
        if (playerWidget != null) {
            playerWidget.setElytraPreview(elytra);
        }
        updateViewButtons();
    }

    private void updateViewButtons() {
        if (capeViewButton != null) {
            capeViewButton.setSelected(!elytraView);
            elytraViewButton.setSelected(elytraView);
        }
    }

    // --- Opaque cape fill ---
    //
    // The toggle owns two pieces of state — on/off and the fill colour — and both feed exactly one
    // place: finalizeCapeFrame(), the pass that ends both composers. Nothing else in this screen
    // touches cape pixels on their behalf.

    /**
     * Lay out the opaque switch, the fill-colour swatch and the colour picker popover.
     *
     * <p>The switch and the swatch end the Opaque fill row at the bottom of the right panel. The
     * picker is a popover over the canvas's lower-left corner: the canvas holds no controls, so the
     * popover covers none, and its own widgets draw over its frame. Anchored there, its hex field
     * keeps the same place at every window size the packaged evidence uses.
     */
    private void initOpaqueControls() {
        int right = panelX1 - PANEL_PAD;
        fillColorButton = this.addRenderableWidget(new CapeSwatchButton(
                right - SWATCH_BUTTON_SIZE, opaqueRowY, SWATCH_BUTTON_SIZE,
                () -> CapeOpaqueFill.opaque(opaqueFillRgb), fillColorMessage(),
                b -> setPickerOpen(!pickerOpen)));
        fillColorButton.active = opaqueFill;
        fillColorButton.setTooltip(Tooltip.create(fillColorMessage()));

        opaqueToggleButton = this.addRenderableWidget(new CapeToggleSwitch(
                right - SWATCH_BUTTON_SIZE - 6 - SWITCH_W, opaqueRowY + 1, SWITCH_W, SWITCH_H,
                () -> opaqueFill, opaqueToggleMessage(), b -> setOpaqueFill(!opaqueFill)));
        opaqueToggleButton.setTooltip(Tooltip.create(opaqueToggleMessage()));

        pickerW = Math.min(PICKER_W, Math.max(140, gridW - 8));
        pickerH = PICKER_H;
        pickerX = gridX + 4;
        pickerY = Math.max(gridY + 4, gridY + gridH - pickerH - 4);

        // Each channel's label goes in a column left of its slider, so the slider's own track runs
        // the widget's full width and a click lands where its knob is drawn
        int sliderX = pickerX + PANEL_PAD + CHANNEL_LABEL_W;
        int sliderW = Math.max(40, pickerW - 2 * PANEL_PAD - CHANNEL_LABEL_W - SWATCH_SIZE - 6);
        redSlider = this.addRenderableWidget(new ChannelSlider(
                sliderX, pickerY + 6, sliderW, 14, 16, "quickskin.cape.adjust_red"));
        greenSlider = this.addRenderableWidget(new ChannelSlider(
                sliderX, pickerY + 24, sliderW, 14, 8, "quickskin.cape.adjust_green"));
        blueSlider = this.addRenderableWidget(new ChannelSlider(
                sliderX, pickerY + 42, sliderW, 14, 0, "quickskin.cape.adjust_blue"));

        hexField = new EditBox(this.font, pickerX + PANEL_PAD + 1, pickerY + 62,
                Math.min(80, pickerW - 2 * PANEL_PAD - 2), 16,
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
            opaqueToggleButton.setTooltip(Tooltip.create(opaqueToggleMessage()));
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
                fillColorButton.setTooltip(Tooltip.create(fillColorMessage()));
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
        //? if <26.1 {
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //?} else {
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //?}
            CapeEditorStyle.slider(graphics, getX(), getY(), getWidth(), getHeight(), this.value,
                    this.isHovered() || this.isFocused(), this.active);
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

        @Override
        //? if <26.1 {
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //?} else {
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //?}
            CapeEditorStyle.slider(graphics, getX(), getY(), getWidth(), getHeight(), this.value,
                    this.isHovered() || this.isFocused(), this.active);
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

    /** Fits the 2:1 cape grid into the canvas's room at the top left of it. */
    private void recalculateGrid() {
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];

        double scaleX = (double) canvasAvailW / capeW;
        double scaleY = (double) canvasAvailH / capeH;
        displayScale = Math.min(scaleX, scaleY);

        gridW = (int) (capeW * displayScale);
        gridH = (int) (capeH * displayScale);
        gridX = canvasLeft;
        gridY = canvasTop;
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
    //? if <26.1 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        // Advance animation frame for multi-frame GIFs
        if (frameCount > 1) {
            tickAnimation();
        }

        BackgroundRenderer.renderBackground(this, graphics, partialTick);
        renderHeader(graphics);

        // The canvas, in its frame
        CapeEditorStyle.panel(graphics, frameX0, frameY0, frameX1, frameY1);
        renderSourceTransparencyBackdrop(graphics);

        // Enable scissor to clip the source image to the grid area
        graphics.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);

        // Render the source image behind the grid
        renderSourceImage(graphics);

        graphics.disableScissor();

        // Render cape grid overlay
        renderCapeGridOverlay(graphics);
        renderSourceBoundary(graphics);

        renderZoomPanel(graphics);
        renderSidePanel(graphics);
        renderPreview(graphics);

        // Drawn before the widget pump so the picker's own sliders and hex field land on top of
        // its backdrop rather than under it.
        renderOpaqueControls(graphics);

        //? if <26.1 {
        super.render(graphics, mouseX, mouseY, partialTick);
        //?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        //?}
    }

    /** The strip across the top: the title on the left, how to use the canvas on the right. */
    //? if <26.1 {
    private void renderHeader(GuiGraphics graphics) {
    //?} else {
    private void renderHeader(GuiGraphicsExtractor graphics) {
    //?}
        graphics.fill(0, 0, this.width, HEADER_H - 1, HEADER_COLOR);
        graphics.fill(0, HEADER_H - 1, this.width, HEADER_H, CapeEditorStyle.ACCENT);
        int textY = (HEADER_H - 1 - 8) / 2 + 1;
        UiText.draw(graphics, this.font, this.title, MARGIN, textY, 0xFFFFFFFF);
        Component hint = Component.translatable("quickskin.cape.adjust_hint");
        int hintX = this.width - MARGIN - this.font.width(hint);
        // The hint gives way on a window too narrow for both
        if (hintX > MARGIN + this.font.width(this.title) + 12) {
            UiText.draw(graphics, this.font, hint, hintX, textY, CapeEditorStyle.SECTION_TEXT);
        }
    }

    /** A section's name and a rule from it to {@code ruleEnd}. */
    //? if <26.1 {
    private void renderSectionLabel(GuiGraphics graphics, Component label, int x, int y, int ruleEnd) {
    //?} else {
    private void renderSectionLabel(GuiGraphicsExtractor graphics, Component label, int x, int y, int ruleEnd) {
    //?}
        UiText.draw(graphics, this.font, label, x, y, CapeEditorStyle.SECTION_TEXT);
        int ruleStart = x + this.font.width(label) + 5;
        if (ruleEnd - ruleStart >= 6) {
            graphics.fill(ruleStart, y + 4, ruleEnd, y + 5, CapeEditorStyle.RULE);
        }
    }

    /**
     * The panel under the canvas: Zoom with the source's size, then zoom out, the slider, its
     * percentage and zoom in. The source and output are intentionally different concepts; keeping
     * both dimensions visible prevents a padded 128x64 import targeting a 64x32 atlas from looking
     * like the editor silently resized or selected the wrong input.
     */
    //? if <26.1 {
    private void renderZoomPanel(GuiGraphics graphics) {
    //?} else {
    private void renderZoomPanel(GuiGraphicsExtractor graphics) {
    //?}
        CapeEditorStyle.panel(graphics, zoomX0, zoomY0, zoomX1, zoomY1);
        Component source = Component.translatable("quickskin.cape.adjust_source_dimensions",
                sourceDimensions());
        int sourceX = zoomX1 - PANEL_PAD - this.font.width(source);
        renderSectionLabel(graphics, Component.translatable("quickskin.cape.adjust_section_zoom"),
                zoomX0 + PANEL_PAD, zoomY0 + 6, sourceX - 6);
        UiText.draw(graphics, this.font, source, sourceX, zoomY0 + 6, SOURCE_BOUNDARY_COLOR);
        if (zoomSlider != null) {
            UiText.draw(graphics, this.font, zoomPercentText(), zoomPercentX, zoomSlider.getY() + 3,
                    0xFFE0E0E0);
        }
    }

    private String zoomPercentText() {
        return CapeZoomRange.formatPercent(imgScale, RESOLUTIONS[selectedResolution][0],
                sourceImage.getWidth(), srcFrameHeight);
    }

    /** The right panel's frame, its section names and the output size under the resolutions. */
    //? if <26.1 {
    private void renderSidePanel(GuiGraphics graphics) {
    //?} else {
    private void renderSidePanel(GuiGraphicsExtractor graphics) {
    //?}
        CapeEditorStyle.panel(graphics, panelX0, panelY0, panelX1, panelY1);
        int x0 = panelX0 + PANEL_PAD;
        int x1 = panelX1 - PANEL_PAD;
        renderSectionLabel(graphics, Component.translatable("quickskin.cape.adjust_section_resolution"),
                x0, resolutionLabelY + 1, x1);
        UiText.drawCentered(graphics, this.font,
                Component.translatable("quickskin.cape.adjust_output_resolution", outputDimensions()),
                (x0 + x1) / 2, outputTextY, 0xFFE0E0E0);
        renderSectionLabel(graphics, Component.translatable("quickskin.cape.adjust_section_position"),
                x0, positionLabelY + 1, x1);
        renderSectionLabel(graphics, Component.translatable("quickskin.cape.adjust_section_opaque"),
                x0, opaqueRowY + 4, opaqueToggleButton != null ? opaqueToggleButton.getX() - 6 : x1);
    }

    /** The colour picker popover's frame, its channel labels and a large swatch, while it is open. */
    //? if <26.1 {
    private void renderOpaqueControls(GuiGraphics graphics) {
    //?} else {
    private void renderOpaqueControls(GuiGraphicsExtractor graphics) {
    //?}
        if (!opaqueFill || !pickerOpen) {
            return;
        }
        CapeEditorStyle.panel(graphics, pickerX, pickerY, pickerX + pickerW, pickerY + pickerH);
        for (ChannelSlider slider : new ChannelSlider[] {redSlider, greenSlider, blueSlider}) {
            if (slider != null) {
                UiText.draw(graphics, this.font, slider.getMessage(), pickerX + PANEL_PAD,
                        slider.getY() + 3, 0xFFE0E0E0);
            }
        }
        int bigSwatchX = pickerX + pickerW - PANEL_PAD - SWATCH_SIZE;
        int bigSwatchTop = pickerY + 6;
        int bigSwatchBottom = pickerY + pickerH - 6;
        graphics.fill(bigSwatchX, bigSwatchTop, bigSwatchX + SWATCH_SIZE, bigSwatchBottom,
                CapeEditorStyle.OUTLINE);
        graphics.fill(bigSwatchX + 1, bigSwatchTop + 1, bigSwatchX + SWATCH_SIZE - 1, bigSwatchBottom - 1,
                CapeOpaqueFill.opaque(opaqueFillRgb));
    }

    //? if <26.1 {
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

    //? if <26.1 {
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

    //? if <26.1 {
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

    //? if <26.1 {
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
        // The strip ends where the dimmed strip under it starts, so rounding leaves no bright line
        int eLH = gridY + (int) (22.0 / 32.0 * gridH) - eLY; // body strip H
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
        // A label may reach a cape pixel past each side of its face, into the gap between the two;
        // a longer one is left out rather than drawn over the next label
        int labelRoom = (int) (12.0 / 64.0 * gridW);
        String label = Component.translatable("quickskin.cape.adjust_front").getString();
        int labelW = this.font.width(label);
        if (labelW <= labelRoom) {
            //? if <26.1 {
            graphics.drawString(this.font, label,
            //?} else {
            graphics.text(this.font, label,
            //?}
                    backX + (backW - labelW) / 2, backY + backH / 2 - 4, 0xFF5599FF);
        }
        // Cape back (outer side, visible from behind) — hidden when mirroring from front
        String frontLabel = Component.translatable("quickskin.cape.adjust_back").getString();
        int frontLabelW = this.font.width(frontLabel);
        if (!mirrorFrontBack && frontLabelW <= labelRoom) {
            //? if <26.1 {
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
            //? if <26.1 {
            graphics.drawString(this.font, eOuterLabel,
            //?} else {
            graphics.text(this.font, eOuterLabel,
            //?}
                    eBackX + (eBackW - eOuterLabelW) / 2, eLY + eLH / 2 - 4, 0xFFFFAA00);
        }
    }

    //? if <26.1 {
    private void renderPreview(GuiGraphics graphics) {
    //?} else {
    private void renderPreview(GuiGraphicsExtractor graphics) {
    //?}
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
    }

    private void updatePreviewTexture() {
        // Preview shows the current animation frame (or the only frame for static images)
        BufferedImage cape = composeFrame(currentAnimFrame);

        if (previewTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(previewTextureLocation);
        }

        NativeImage ni = convertToNativeImage(cape);
        previewDynTexture = MinecraftTextureUploads.create(() -> "quickskin_cape_adjust_preview", ni);
        //? if <1.21.4 {
        previewTextureLocation = Minecraft.getInstance().getTextureManager()
                .register("quickskin/cape_adjust_preview", previewDynTexture);
        //?} else if <1.21.11 {
        previewTextureLocation = ResourceLocation.fromNamespaceAndPath(
                QuickSkinInfo.MOD_ID, "cape_adjust_preview");
        Minecraft.getInstance().getTextureManager().register(previewTextureLocation, previewDynTexture);
        //?} else {
        previewTextureLocation = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "cape_adjust_preview");
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
        if (button == InputConstants.MOUSE_BUTTON_LEFT && mouseX >= gridX && mouseX <= gridX + gridW
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
        if (button == InputConstants.MOUSE_BUTTON_LEFT && isDragging) {
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
        if (isDragging && button == InputConstants.MOUSE_BUTTON_LEFT) {
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

    //? if >=1.21 {
        //? if <1.21.2 {
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Minecraft 1.21.1 invokes this from super.render() after our custom UI has been drawn.
    }
        //?}
    //?}

    //? if >=1.21.2 {
        //? if <1.21.6 {
    @Override
    protected void renderBlurredBackground() {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }
        //?} else if <26.1 {
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
            //? if <26.1 {
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
