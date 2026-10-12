package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
//? if <26.1 {
//?} else {
import com.quickskin.mod.client.gui.GuiCompat;
//?}
import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.gui.panel.ActionButtonsPanel;
import com.quickskin.mod.client.gui.panel.LinkButtonsPanel;
import com.quickskin.mod.client.gui.panel.PlayerPreviewPanel;
import com.quickskin.mod.client.gui.panel.SkinListPanel;
import com.quickskin.mod.client.gui.util.BackgroundRenderer;
import com.quickskin.mod.client.gui.util.FileDialogHelper;
import com.quickskin.mod.client.gui.util.GuiScaleManager;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.gui.widget.ErrorToast;
import com.quickskin.mod.client.gui.widget.ImportTileButton;
//? if <1.21 {
//?} else {
import com.quickskin.mod.client.gui.widget.PlayerWidget;
//?}
import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.LocalAssetFolderWatch;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.MojangApiService;
//? if <1.21 {
//?} else {
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinSortMode;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
//? if <1.21.11 {
import net.minecraft.Util;
//?} else {
import net.minecraft.util.Util;
//?}

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Main skin selection menu for QuickSkin
 * Opens when K key is pressed
 */
@Environment(EnvType.CLIENT)
public class PlayerSkinMenuScreen extends Screen implements com.quickskin.mod.client.gui.RestylableScreen {
    @Override
    public Screen recreateForStyleChange() {
        return new PlayerSkinMenuScreen(null);
    }


    @Nullable
    private final Screen parent;

    // Panels
    private SkinListPanel skinListPanel;
    private PlayerPreviewPanel playerPreviewPanel;
    private ActionButtonsPanel actionButtonsPanel;
    private ImportTileButton importTile;

    // Controls that stay above the player preview (drawn after it, clicked before it)
    private final List<AbstractWidget> foregroundControls = new ArrayList<>();

    // Layout of the panel-free menu, recomputed by init()
    private static final int MARGIN = 8;
    private static final int COLUMN_WIDTH = 72;
    private static final int CONTROL_HEIGHT = 20;
    private int skinColumnX;
    private int skinHeaderY;
    private int skinListBottom;

    // GUI scale management
    private boolean guiScaleForced = false;
    private boolean isClosing = false;
    private boolean openingSubScreen = false;

    // Picks up skins copied into the uploads folder from outside the game
    private final LocalAssetFolderWatch localAssetWatch = new LocalAssetFolderWatch();

    // Player preview rotation state (preserved across resizes)
    private float savedBodyYaw = 20.0f;
    private float savedTargetRotation = 20.0f;

    // The look being previewed. Nothing is applied until Confirm; it survives resizes and the
    // sub-screens that return to this instance.
    @Nullable
    private String pendingHash;
    @Nullable
    private String pendingModelType;

    // Dragging the model turns it (from 1.21; before, the model widget turns itself)
    private boolean rotatingModel;
    private double rotationDragStartX;
    private float rotationDragStartYaw;
    private static final float ROTATION_DRAG_SENSITIVITY = 0.8f;

    // Error toasts
    private final List<ErrorToast> errorToasts = new ArrayList<>();

    // Mojang search widgets
    private EditBox usernameSearchField;
    private Button searchButton;
    private Button sortButton;
    private boolean isSearching = false;
    private long lastEnterKeyAt;

    public PlayerSkinMenuScreen(@Nullable Screen parent) {
//? if <1.21 {
        super(Component.translatable("quickskin.screen.skin_menu.title"));
//?} else {
        super(Component.literal("Quick Skin"));
//?}
        this.parent = parent;
    }

    @Override
    public void tick() {
        super.tick();
        updateConfirmButtonState();
        pollLocalAssetFolder();
    }

    /**
     * Surface skins copied into {@code quickskin/uploads/skins} from outside the game.
     *
     * <p>Runs from {@link #tick()} rather than {@code init()} because {@code init()} also re-runs on
     * every window resize and on every return from a sub-screen. The folder walk is metadata-only
     * and happens on {@link ClientIoExecutor}; the catalog rebuild stays on the render thread, where
     * every other rescan in this screen already runs, and only happens when something changed.
     */
    private void pollLocalAssetFolder() {
        Minecraft client = this.minecraft;
        if (client == null || !localAssetWatch.beginPoll(System.currentTimeMillis())) {
            return;
        }

        LocalAssetManager assets = LocalAssetManager.getInstance();
        LocalAssetManager.ScanRequest scanRequest = assets.snapshotScanRequest();
        ClientIoExecutor.supplyAsync(() -> LocalAssetFolderWatch.fingerprint(scanRequest.directories()))
                .whenComplete((fingerprint, error) -> client.execute(() -> {
                    localAssetWatch.finishPoll();
                    if (error != null || fingerprint == null) {
                        return;
                    }
                    if (assets.refreshIfChanged(scanRequest, fingerprint)) {
                        applyExternalSkinFolderChange();
                    }
                }));
    }

    /** Rebuild the list after an external folder change, keeping the current selection. */
    private void applyExternalSkinFolderChange() {
        if (skinListPanel == null) {
            return;
        }

        SkinEntry selected = skinListPanel.getSelected();
        String selectedHash = selected != null ? selected.getMetadata().hash() : null;

        refreshSkinList();

        if (selectedHash != null) {
            AssetMetadata stillPresent = LocalAssetManager.getInstance().getMetadata(selectedHash);
            if (stillPresent != null) {
                // Re-point the list without re-firing selection side effects on the preview.
                skinListPanel.setSelected(stillPresent, false);
            }
        }
    }

    private void updateConfirmButtonState() {
        if (this.actionButtonsPanel == null) return;
        Button confirmButton = this.actionButtonsPanel.getConfirmButton();
        if (confirmButton == null) return;

        // Cooldown does not apply in singleplayer
//? if <26.2 {
        boolean singleplayer = this.minecraft != null && this.minecraft.isSingleplayer();
//?} else {
        boolean singleplayer = this.minecraft != null && this.minecraft.hasSingleplayerServer();
//?}
        long remainingSeconds = singleplayer ? 0 : CooldownService.getInstance().getRemainingCooldownSeconds();
        if (remainingSeconds > 0) {
            confirmButton.active = false;
            confirmButton.setMessage(Component.translatable("quickskin.cooldown.button", remainingSeconds));
            confirmButton.setTooltip(Tooltip.create(Component.translatable("quickskin.cooldown.tooltip")));
        } else if (!confirmButton.active) {
            confirmButton.active = true;
            confirmButton.setMessage(Component.translatable("quickskin.button.confirm"));
            confirmButton.setTooltip(null);
        }
    }

    @Override
    protected void init() {
        // Force GUI scale for consistent appearance
        if (!guiScaleForced && !isClosing) {
            guiScaleForced = true;
            int optimalScale = GuiScaleManager.getOptimalMenuScale();
            if (GuiScaleManager.setMenuGuiScale(optimalScale)) {
                // Scale was changed and resizeDisplay() was called, which will trigger init() again
                return;
            }
        }

        super.init();
        clearWidgets();
        foregroundControls.clear();
        rotatingModel = false;

        // Save rotation state from the existing player preview before it's destroyed
        if (playerPreviewPanel != null) {
            com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null) {
                savedBodyYaw = widget.getBodyYaw();
                savedTargetRotation = widget.getTargetYRotation();
            }
        }

        int linkColumnX = this.width - MARGIN - LinkButtonsPanel.buttonSize();

        // Player-name search, top centre
        int searchButtonWidth = 60;
        int searchFieldWidth = Math.max(80, Math.min(200,
                linkColumnX - (MARGIN + COLUMN_WIDTH) - 2 * MARGIN - searchButtonWidth - 4));
        int searchX = (this.width - (searchFieldWidth + 4 + searchButtonWidth)) / 2;
        int searchY = MARGIN;
        usernameSearchField = new EditBox(
                this.font,
                searchX,
                searchY,
                searchFieldWidth,
                CONTROL_HEIGHT,
                Component.translatable("quickskin.search.placeholder")
        );
        usernameSearchField.setSuggestion(Component.translatable("quickskin.search.suggestion").getString());
        usernameSearchField.setMaxLength(16);
        usernameSearchField.setResponder(text -> {
            onUsernameFieldChanged(text);
            // Update suggestion visibility
            if (text.isEmpty()) {
                usernameSearchField.setSuggestion(Component.translatable("quickskin.search.suggestion").getString());
            } else {
                usernameSearchField.setSuggestion("");
            }
        });
        addRenderableWidget(usernameSearchField);

        searchButton = com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                searchX + searchFieldWidth + 4,
                searchY,
                searchButtonWidth,
                CONTROL_HEIGHT,
                Component.translatable("quickskin.button.search"),
                button -> searchMojangSkin()
        );
        addRenderableWidget(searchButton);
        searchButton.active = false;

        // Skin column, left: header with the sort button, the skin tiles, then the import tile
        skinColumnX = MARGIN;
        skinHeaderY = searchY + CONTROL_HEIGHT + MARGIN;
        sortButton = com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                skinColumnX + COLUMN_WIDTH - CONTROL_HEIGHT,
                skinHeaderY,
                CONTROL_HEIGHT,
                CONTROL_HEIGHT,
                Component.literal(getCurrentSortMode().getIcon()),
                button -> cycleSortMode()
        );
        sortButton.setTooltip(Tooltip.create(
                Component.translatable("quickskin.tooltip.sorting", Component.translatable(getCurrentSortMode().getTranslationKey()))
        ));
        addRenderableWidget(sortButton);

        int tileSize = SkinListPanel.ENTRY_HEIGHT - 4;
        int listTop = skinHeaderY + CONTROL_HEIGHT + 2;
        skinListBottom = Math.max(listTop + SkinListPanel.ENTRY_HEIGHT, this.height - MARGIN - tileSize - 4);
        skinListPanel = new SkinListPanel(
                skinColumnX,
                listTop,
                COLUMN_WIDTH,
                skinListBottom - listTop,
                this.minecraft,
                this::onSkinSelected
        );
        skinListPanel.init(this);

        importTile = new ImportTileButton(skinColumnX + 4, listTop + 4, tileSize, button -> openImportDialog());
        importTile.setTooltip(Tooltip.create(Component.translatable("quickskin.tooltip.import_skin")));
        addRenderableWidget(importTile);

        // Confirm and the rotate control, bottom centre; the model stands on the rotate control
        int centerX = this.width / 2;
        int confirmY = this.height - MARGIN - CONTROL_HEIGHT;
        int confirmX = centerX - ActionButtonsPanel.CONFIRM_WIDTH / 2;
        int rotateY = confirmY - 4 - CONTROL_HEIGHT;
        int feetY = rotateY - 2;
        // Room between the search row (plus the name tag) and the feet
        int modelTop = searchY + CONTROL_HEIGHT + 34;
        float scaleCap = Math.max(40.0f, (feetY - modelTop) / MODEL_HEIGHT_PER_SCALE);

        playerPreviewPanel = new PlayerPreviewPanel(centerX, feetY, 0, 0);
        playerPreviewPanel.initPlayerWidget(this, centerX, feetY, scaleCap);
        float modelScale = playerPreviewPanel.getPlayerWidget() != null
                ? playerPreviewPanel.getPlayerWidget().getEffectiveScale() : scaleCap;
        playerPreviewPanel.setModelBox(centerX, feetY, modelScale);
        playerPreviewPanel.initControls(this, centerX - CONTROL_HEIGHT / 2, rotateY);

        // Floating buttons, left and right of the model at shoulder and waist height
        int halfBody = (int) (modelScale * 0.55f);
        int gap = 12;
        int leftX = Math.max(skinColumnX + COLUMN_WIDTH + 6,
                centerX - halfBody - gap - ActionButtonsPanel.BUTTON_WIDTH);
        int rightX = Math.min(linkColumnX - 6 - ActionButtonsPanel.BUTTON_WIDTH, centerX + halfBody + gap);
        int shoulderY = Math.max(skinHeaderY, feetY - (int) (modelScale * 1.45f));
        int waistY = Math.max(shoulderY + CONTROL_HEIGHT + 4, feetY - (int) (modelScale * 0.9f));
        ActionButtonsPanel.Layout layout = new ActionButtonsPanel.Layout(
                leftX, rightX, shoulderY, waistY, confirmX, confirmY);
        ActionButtonsPanel.ActionCallbacks callbacks = new ActionButtonsPanel.ActionCallbacks(
                this::cycleBodyWidth,
                () -> openWebsite("https://laby.net/skins?order=trending_30d"),
                () -> openWebsite("https://mcskins.top/128x128/"),
                () -> {
                    // Open cape selection screen
                    if (minecraft != null) {
                        openingSubScreen = true;
//? if <26.2 {
                        minecraft.setScreen(new PlayerCapeMenuScreen(this));
//?} else {
                        minecraft.gui.setScreen(new PlayerCapeMenuScreen(this));
//?}
                    }
                },
                this::confirmSelection
        );
        actionButtonsPanel = new ActionButtonsPanel(layout);
        actionButtonsPanel.init(this, layout, callbacks);
        for (Button button : actionButtonsPanel.buttons()) {
            playerPreviewPanel.addPriorityControl(button);
        }

        // Link column, right edge, vertically centred
        LinkButtonsPanel linkButtonsPanel = new LinkButtonsPanel(
                linkColumnX,
                (this.height - LinkButtonsPanel.columnHeight()) / 2
        );
        linkButtonsPanel.init(this);

        // Restore the previewed look and the active cape
        restoreSavedState();
        updateBodyWidthButton();
        updateConfirmButtonState();
    }

    /** Height of the drawn player per unit of model scale: a player is 1.8 blocks, plus the hat and a margin. */
    private static final float MODEL_HEIGHT_PER_SCALE = 2.0f;

    private void openWebsite(String url) {
        if (this.minecraft != null) {
            this.minecraft.options.chatLinksPrompt().set(false);
            //? if <26.3 {
            Util.getPlatform().openUri(url);
            //?} else {
            com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(url));
            //?}
        }
    }

    /**
     * Restore the saved model type and active skin from config
     */
    private void restoreSavedState() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // Back from a resize or a sub-screen: show the look that was pending, as it was.
        if (pendingHash != null && skinListPanel != null) {
            AssetMetadata pending = LocalAssetManager.getInstance().getMetadata(pendingHash);
            if (pending != null) {
                skinListPanel.setSelected(pending, false);
                previewLook(pending, pendingModelType);
            } else {
                pendingHash = null;
                pendingModelType = null;
            }
        }

        // Otherwise preview the saved look. Opening the menu is no look choice: while CPM's own
        // model is the look, no Quick Skin entry is selected (Confirm would apply it).
        boolean cpmOwnsLook = com.quickskin.mod.client.compat.CpmLook.owner()
                == com.quickskin.mod.client.compat.CpmLook.Owner.CPM;
        if (pendingHash != null) {
            // Restored above
        } else if (cpmOwnsLook) {
            // Keep CPM's model; the saved Quick Skin skin is still listed and can be chosen.
        } else if (!config.activeSkinHash.isEmpty() && skinListPanel != null) {
            AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(config.activeSkinHash);
            if (metadata != null) {
                skinListPanel.setSelected(metadata, true);
            }
        } else if (!config.activeCpmModelHash.isEmpty() && skinListPanel != null) {
//? if <1.21.11 {
            // Restore CPM model selection
            AssetMetadata cpmModel = LocalAssetManager.getInstance().getMetadata(config.activeCpmModelHash);
//?} else {
            AssetMetadata cpmModel = com.quickskin.mod.client.compat.CpmModelWorkflow
                    .getPersistedActiveModel();
//?}
            if (cpmModel != null) {
                skinListPanel.setSelected(cpmModel, true);
            }
        } else if (config.activeSkinHash.isEmpty() && !config.playerOwnSkinHash.isEmpty() && skinListPanel != null) {
            // If no active skin is set, preview the player's own skin
            AssetMetadata playerOwnSkin = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
            if (playerOwnSkin != null) {
                skinListPanel.setSelected(playerOwnSkin, true);
            }
        }

        // Restore active cape selection
        if (!config.activeCapeHash.isEmpty() && playerPreviewPanel != null) {
            String capeId = config.activeCapeHash;
//? if <1.21.11 {
            ResourceLocation capeLocation = getCapeLocationFromId(capeId);
//?} else {
            Identifier capeLocation = getCapeLocationFromId(capeId);
//?}
            if (capeLocation != null) {
                // Register animation if this is an animated cape
                registerCapeAnimationIfNeeded(capeId, capeLocation);

                playerPreviewPanel.updateCape(capeLocation, capeId);
            }
        }

        // Restore rotation state
        if (playerPreviewPanel != null) {
            com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null) {
                widget.setRotationState(savedBodyYaw, savedTargetRotation);
            }
        }
    }

    /**
     * Convert cape ID to Identifier
     * Cape ID format: "local_cape:hash" or "known:id"
     */
    @Nullable
//? if <1.21.11 {
    private ResourceLocation getCapeLocationFromId(String capeId) {
//?} else {
    private Identifier getCapeLocationFromId(String capeId) {
//?}
        if (capeId.startsWith("local_cape:")) {
            // Local cape - extract hash and get texture location
            String hash = capeId.substring("local_cape:".length());
            return LocalAssetManager.getInstance().getTextureLocation(hash, com.quickskin.mod.common.data.TextureQuality.FULL);
        } else if (capeId.startsWith("known:")) {
            // Known cape - extract ID and get from KnownCapes enum
            String id = capeId.substring("known:".length());
            com.quickskin.mod.common.data.KnownCapes knownCape = com.quickskin.mod.common.data.KnownCapes.getById(id);
            if (knownCape != null) {
                return knownCape.getTextureLocation();
            } else {
                return null;
            }
        }
        return null;
    }

    /**
     * Register cape animation if the cape is animated
     * @param capeId Cape ID (format: "local_cape:hash" or "known:id")
     * @param capeLocation Texture location (atlas)
     */
//? if <1.21.11 {
    private void registerCapeAnimationIfNeeded(String capeId, ResourceLocation capeLocation) {
//?} else {
    private void registerCapeAnimationIfNeeded(String capeId, Identifier capeLocation) {
//?}
        // Determine animation ID from cape ID
        String hash = com.quickskin.mod.client.services.CapeAnimationIds.localHash(capeId);
        if (hash != null) {
            String animationId =
                    com.quickskin.mod.client.services.CapeAnimationIds.deriveAnimationId(capeId);

//? if <26.1 {
            // Check if this local cape has animation metadata
            com.quickskin.mod.common.data.AnimationMetadata metadata =
                    LocalAssetManager.getInstance().getAnimationMetadata(hash);

            if (metadata != null && metadata.frameCount() > 1) {
                // Load atlas image from cache
                java.awt.image.BufferedImage atlasImage =
                        LocalAssetManager.getInstance().getSourceImage(hash);

                if (atlasImage != null) {
                    // Register animation
                    com.quickskin.mod.client.services.AnimatedTextureManager.getInstance()
                            .registerAnimation(animationId, capeId, capeLocation, atlasImage, metadata);
                }
            }
//?} else {
            com.quickskin.mod.client.services.AnimatedTextureManager.getInstance()
                    .registerAnimationAsync(animationId, capeId, capeLocation, hash);
//?}
        }
        // Known capes might also be animated
        // For now, we'll skip this as known capes use a different system
        // but you could add similar logic if needed
    }

    // --- NEW ---: Method to render the animated background
    /**
     * Renders a moving star pattern background similar to the effect on the example website.
     * This includes a tiled, scrolling texture and a vignette overlay for depth.
     */
//? if <26.1 {
    private void renderBackgroundEffects(GuiGraphics graphics, float partialTick) {
//?} else {
    private void renderBackgroundEffects(GuiGraphicsExtractor graphics, float partialTick) {
//?}
        BackgroundRenderer.renderBackground(this, graphics, partialTick);
    }

    @Override
//? if <26.1 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
//?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
//?}
        // Render the animated background
        this.renderBackgroundEffects(graphics, partialTick);

        // Keep the import tile right after the last skin, or below the column once it scrolls
        if (importTile != null && skinListPanel != null && skinListPanel.getSkinListWidget() != null) {
            int afterLast = skinListPanel.getSkinListWidget().contentBottom();
            importTile.setY(Math.min(Math.max(afterLast, skinListPanel.getY() + 4), skinListBottom + 2));
        }

//? if <1.21 {
//?} else if <1.21.6 {
        // Push pose
        graphics.pose().pushPose();

//?} else {
        // Push pose (1.21.11: Matrix3x2fStack uses pushMatrix/popMatrix)
        graphics.pose().pushMatrix();

//?}
        // Render widgets (buttons, etc.)
//? if <1.21 {
        super.render(graphics, mouseX, mouseY, partialTick);
//?} else if <1.21.6 {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Pop pose
        graphics.pose().popPose();
//?} else if <26.1 {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Pop pose
        graphics.pose().popMatrix();
//?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Pop pose
        graphics.pose().popMatrix();
//?}

        renderSkinHeader(graphics);
        renderNameTag(graphics);

        // Foreground controls and error toasts go above the player preview. Up to 1.21.5 the GUI
        // is depth tested: the model sits at z 50 and reaches forward by its scale (200 at most),
        // tooltips are at z 400. From 1.21.6 a later stratum is drawn on top.
//? if <1.21.6 {
        graphics.pose().pushPose();
        // Stay flat behind a dialog that draws this screen as its backdrop
        if (Minecraft.getInstance().screen == this) {
            graphics.pose().translate(0.0F, 0.0F, 350.0F);
        }
//?} else {
        graphics.nextStratum();
//?}
        for (AbstractWidget control : foregroundControls) {
//? if <26.1 {
            control.render(graphics, mouseX, mouseY, partialTick);
//?} else {
            control.extractRenderState(graphics, mouseX, mouseY, partialTick);
//?}
        }

        renderSkinTooltip(graphics, mouseX, mouseY);

        // Render error toasts (on top of everything)
        renderErrorToasts(graphics);
//? if <1.21.6 {
        graphics.pose().popPose();
//?}
    }

    /** "Skins 2/4" above the skin column: the selected position and the number of skins. */
//? if <26.1 {
    private void renderSkinHeader(GuiGraphics graphics) {
//?} else {
    private void renderSkinHeader(GuiGraphicsExtractor graphics) {
//?}
        if (skinListPanel == null) {
            return;
        }
        int position = skinListPanel.selectedPosition();
        Component header = Component.translatable("quickskin.menu.skins_count",
                position > 0 ? String.valueOf(position) : "-", String.valueOf(skinListPanel.size()));
        int textY = skinHeaderY + (CONTROL_HEIGHT - 8) / 2;
//? if <26.1 {
        graphics.drawString(this.font, header, skinColumnX + 2, textY, 0xFFFFFFFF);
//?} else {
        graphics.text(this.font, header, skinColumnX + 2, textY, 0xFFFFFFFF);
//?}
    }

    /** The player's name above the model, like a name tag in the world. */
//? if <26.1 {
    private void renderNameTag(GuiGraphics graphics) {
//?} else {
    private void renderNameTag(GuiGraphicsExtractor graphics) {
//?}
        if (playerPreviewPanel == null || playerPreviewPanel.getPlayerWidget() == null
                || !playerPreviewPanel.getPlayerWidget().visible || this.minecraft == null) {
            return;
        }
        com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
        String name = this.minecraft.player != null
                ? this.minecraft.player.getName().getString()
                : this.minecraft.getUser().getName();
        int textWidth = this.font.width(name);
        int centerX = widget.getLayoutCenterX();
        int top = widget.getLayoutFeetY() - (int) (widget.getEffectiveScale() * MODEL_HEIGHT_PER_SCALE) - 24;
        graphics.fill(centerX - textWidth / 2 - 2, top - 1, centerX + (textWidth + 1) / 2 + 2, top + 9, 0x40000000);
//? if <26.1 {
        graphics.drawString(this.font, name, centerX - textWidth / 2, top, 0xFFFFFFFF, false);
//?} else {
        graphics.text(this.font, name, centerX - textWidth / 2, top, 0xFFFFFFFF, false);
//?}

        // The previewed skin's name, in grey under the name tag
        AssetMetadata previewed = pendingHash != null ? LocalAssetManager.getInstance().getMetadata(pendingHash) : null;
        if (previewed != null) {
            String skinName = previewed.friendlyName();
            int maxWidth = 160;
            if (this.font.width(skinName) > maxWidth) {
                skinName = this.font.plainSubstrByWidth(skinName, maxWidth - this.font.width("...")) + "...";
            }
            int skinNameWidth = this.font.width(skinName);
//? if <26.1 {
            graphics.drawString(this.font, skinName, centerX - skinNameWidth / 2, top + 11, 0xFFAAAAAA);
//?} else {
            graphics.text(this.font, skinName, centerX - skinNameWidth / 2, top + 11, 0xFFAAAAAA);
//?}
        }
    }

    /** The hovered skin's name and model, or what its hover button does. */
//? if <26.1 {
    private void renderSkinTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
//?} else {
    private void renderSkinTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
//?}
        if (skinListPanel == null || skinListPanel.getSkinListWidget() == null) {
            return;
        }
        SkinEntry hovered = skinListPanel.getSkinListWidget().getHoveredEntry();
        if (hovered != null) {
            com.quickskin.mod.client.gui.GuiCompat.tooltip(graphics, this.font, hovered.tooltipLines(), mouseX, mouseY);
        }
    }

    public void setOpeningSubScreen(boolean opening) {
        this.openingSubScreen = opening;
    }

    @Override
    public void removed() {
        BackgroundRenderer.cleanup();
        super.removed();

        if (isClosing) {
            // Normal close via onClose() - safety restore (usually no-op)
            restoreGuiScaleIfNeeded();
        } else if (!openingSubScreen) {
            // External close (Essential bypass) - must restore here
            isClosing = true;  // Prevent init() from re-forcing during resizeDisplay()
            restoreGuiScaleIfNeeded();
        }
        // else: sub-screen navigation, skip restore

        // The restore's resizeDisplay() has finished, so the guard is no longer needed. Leaving
        // it latched would make a later setScreen(this) come back at the user's scale forever:
        // a dialog that returns to this instance twice (callback plus its own onClose) hits the
        // external-close branch above, and init() must be able to force the menu scale again.
        isClosing = false;
        openingSubScreen = false;
    }

    @Override
    public void onClose() {
        // Mark that we're truly closing (not just opening a modal)
        isClosing = true;

        // Restore GUI scale before closing
        restoreGuiScaleIfNeeded();

        // Return to parent screen (or null to return to game)
        if (this.minecraft != null) {
//? if <26.2 {
            this.minecraft.setScreen(parent);
//?} else {
            this.minecraft.gui.setScreen(parent);
//?}
        }
    }

    /**
     * Restore the original GUI scale if it was forced by this screen.
     * This method is idempotent and can be called multiple times safely.
     */
    private void restoreGuiScaleIfNeeded() {
        if (guiScaleForced) {
            guiScaleForced = false;
            GuiScaleManager.restoreOriginalGuiScale();
        }
    }

    @Override
    public boolean isPauseScreen() {
        // Don't pause game when this screen is open
        return false;
    }

    @Override
//? if <1.21 {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
//?} else if <1.21.6 {
//? if <1.21.2 {
    public void renderBlurredBackground(float partialTick) {
//?} else {
    protected void renderBlurredBackground() {
//?}
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
//?} else if <26.1 {
    protected void renderBlurredBackground(net.minecraft.client.gui.GuiGraphics guiGraphics) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }

    @Override
//? if <1.21.9 {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
//?} else {
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.scancode();
        int modifiers = event.modifiers();
//?}
//?} else {
    protected void extractBlurredBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = GuiCompat.keyCode(event);
//?}
        // Allow ESC to close
        if (keyCode == InputConstants.KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        // Enter in the username field submits the search, like clicking Search. A held key
        // repeats this event, so only an Enter that follows a pause starts a search.
        if ((keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER)
                && usernameSearchField != null && usernameSearchField.isFocused()) {
            long now = System.currentTimeMillis();
            boolean held = now - lastEnterKeyAt < 1000L;
            lastEnterKeyAt = now;
            if (!held) searchMojangSkin();
            return true;
        }
//? if <1.21.9 {
        return super.keyPressed(keyCode, scanCode, modifiers);
//?} else {
        return super.keyPressed(event);
//?}
    }

    @Override
//? if <1.21 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (clickForegroundControls(button, control -> control.mouseClicked(mouseX, mouseY, button))) return true;
//?} else if <1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (clickForegroundControls(button, control -> control.mouseClicked(mouseX, mouseY, button))) return true;
        // Give PlayerWidget input priority for its customization feature
        if (playerPreviewPanel != null) {
            PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null && widget.mouseClicked(mouseX, mouseY, button)) {
                this.setFocused(widget);
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    this.setDragging(true);
                }
                return true;
            }
        }

//?} else if <26.1 {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.buttonInfo().button();
        if (clickForegroundControls(button, control -> control.mouseClicked(event, focused))) return true;
        // Give PlayerWidget input priority for its customization feature
        if (playerPreviewPanel != null) {
            PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null && widget.mouseClicked(event, focused)) {
                this.setFocused(widget);
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    this.setDragging(true);
                }
                return true;
            }
        }

//?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
        if (clickForegroundControls(button, control -> control.mouseClicked(event, focused))) return true;
        // Give PlayerWidget input priority for its customization feature
        if (playerPreviewPanel != null) {
            PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null && widget.mouseClicked(event, focused)) {
                this.setFocused(widget);
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    this.setDragging(true);
                }
                return true;
            }
        }

//?}
        // Handle debug positioning mode
        if (com.quickskin.mod.client.rendering.PlayerModelRenderer.handleDebugMousePressed((int)mouseX, (int)mouseY, button)) {
            return true;
        }
//? if <1.21.9 {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
//?} else {
        boolean handled = super.mouseClicked(event, focused);
//?}
//? if >=1.21 {
        if (!handled && button == InputConstants.MOUSE_BUTTON_LEFT && startModelRotation(mouseX, mouseY)) {
            return true;
        }
//?}
        return handled;
    }

    /** Starts turning the model when a click lands on it; returns whether it did. */
    private boolean startModelRotation(double mouseX, double mouseY) {
        if (playerPreviewPanel == null) {
            return false;
        }
        com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
        if (widget == null || !widget.visible || !playerPreviewPanel.isOverModelBox(mouseX, mouseY)) {
            return false;
        }
        rotatingModel = true;
        rotationDragStartX = mouseX;
        rotationDragStartYaw = widget.getTargetYRotation();
        return true;
    }

    /**
     * Offer a click to the foreground controls before the player preview can take it
     */
    private boolean clickForegroundControls(int button, java.util.function.Predicate<AbstractWidget> click) {
        for (AbstractWidget control : foregroundControls) {
            if (click.test(control)) {
                this.setFocused(control);
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    this.setDragging(true);
                }
                return true;
            }
        }
        return false;
    }

    @Override
//? if <1.21.9 {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
//?} else if <26.1 {
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.buttonInfo().button();
//?} else {
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
//?}
        // Handle debug positioning mode
        if (com.quickskin.mod.client.rendering.PlayerModelRenderer.handleDebugMouseDragged((int)mouseX, (int)mouseY, button)) {
            return true;
        }
        if (rotatingModel && playerPreviewPanel != null && playerPreviewPanel.getPlayerWidget() != null) {
            playerPreviewPanel.getPlayerWidget().setTargetYRotation(
                    rotationDragStartYaw - (float) (mouseX - rotationDragStartX) * ROTATION_DRAG_SENSITIVITY);
            return true;
        }
//? if <1.21.9 {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
//?} else {
        return super.mouseDragged(event, dragX, dragY);
//?}
    }

    @Override
//? if <1.21.9 {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
//?} else if <26.1 {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.buttonInfo().button();
//?} else {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
//?}
        // Handle debug positioning mode
        if (com.quickskin.mod.client.rendering.PlayerModelRenderer.handleDebugMouseReleased((int)mouseX, (int)mouseY, button)) {
            return true;
        }
        if (rotatingModel && button == InputConstants.MOUSE_BUTTON_LEFT) {
            rotatingModel = false;
            return true;
        }
//? if <1.21 {
        return super.mouseReleased(mouseX, mouseY, button);
//?} else if <1.21.9 {
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // Give PlayerWidget input priority for its customization feature
        if (playerPreviewPanel != null) {
            PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null && widget.mouseScrolled(mouseX, mouseY, deltaX, deltaY)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
//?} else {
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // Give PlayerWidget input priority for its customization feature
        if (playerPreviewPanel != null) {
            PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null && widget.mouseScrolled(mouseX, mouseY, deltaX, deltaY)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
//?}
    }

    @Override
    public void onFilesDrop(List<Path> files) {

        // Split files into image files and cpmmodel files
        List<Path> imageFiles = new ArrayList<>();
        List<Path> cpmModelFiles = new ArrayList<>();
        for (Path path : files) {
            String lower = path.toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg")) {
                imageFiles.add(path);
//? if <1.21.11 {
            } else if (lower.endsWith(".cpmmodel")) {
//?} else {
            } else if (com.quickskin.mod.client.compat.CpmModelWorkflow.isModelFile(path)) {
//?}
                cpmModelFiles.add(path);
            }
        }

        if (imageFiles.isEmpty() && cpmModelFiles.isEmpty()) {
            showError(Component.literal("Unsupported file format. Use PNG, WebP, JPG, or .cpmmodel."));
            return;
        }

//? if <1.21.11 {
        // Check CPM availability for .cpmmodel files
        if (!cpmModelFiles.isEmpty() && !com.quickskin.mod.client.compat.CPMCompatIntegration.isAvailable()) {
//?} else {
        if (!cpmModelFiles.isEmpty()
                && !com.quickskin.mod.client.compat.CPMCompatIntegration.isAvailable()) {
//?}
            showError(Component.literal("CPM mod required for .cpmmodel files."));
            cpmModelFiles.clear();
        }

//? if <1.21.11 {
        final List<Path> finalCpmModelFiles = cpmModelFiles;
//?} else {
        List<Path> acceptedCpmModels = List.copyOf(cpmModelFiles);
//?}

        if (this.minecraft != null) {
            this.minecraft.execute(() -> {
                List<AssetMetadata> imported = new ArrayList<>();
//? if <1.21.11 {

                // Import image files as skins
                if (!imageFiles.isEmpty()) {
                    imported.addAll(SkinImporter.importSkins(imageFiles.toArray(new Path[0])));
                }

                // Import .cpmmodel files
                for (Path cpmPath : finalCpmModelFiles) {
                    AssetMetadata meta = SkinImporter.importCpmModel(cpmPath);
                    if (meta != null) {
                        imported.add(meta);
//?} else {
                imported.addAll(SkinImporter.importSkins(imageFiles.toArray(new Path[0])));
                for (Path cpmModel : acceptedCpmModels) {
                    AssetMetadata metadata = SkinImporter.importCpmModel(cpmModel);
                    if (metadata != null) {
                        imported.add(metadata);
//?}
                    }
                }

                if (!imported.isEmpty()) {
                    // Reload the skin list
                    refreshSkinList();

                    // Auto-select the first imported asset
                    if (skinListPanel != null) {
                        skinListPanel.setSelected(imported.get(0));
                    }
                }
            });
        }
    }

    /**
     * Called when a skin is selected from the list: previews it. Nothing is applied until Confirm.
     */
    public void onSkinSelected(SkinEntry entry) {
        if (playerPreviewPanel == null || entry == null) {
            return;
        }
        AssetMetadata metadata = entry.getMetadata();
        // Re-selecting the pending skin (after a rename or refresh) keeps its pending arm width.
        String modelType = metadata.hash().equals(pendingHash) ? pendingModelType : null;
        previewLook(metadata, modelType);
    }

    /** Shows a skin or CPM model on the preview and makes it the pending look. */
    private void previewLook(AssetMetadata metadata, @Nullable String modelType) {
        pendingHash = metadata.hash();
        if (metadata.isCpmModel()) {
            pendingModelType = null;
            playerPreviewPanel.previewSkin(
                    metadata,
                    LocalAssetManager.getInstance().getTextureLocation(metadata.hash(), TextureQuality.PREVIEW),
                    null
            );
        } else {
            // The arm width this skin was last confirmed with, unless one is already pending
            pendingModelType = modelType != null
                    ? modelType
                    : LocalAssetManager.getInstance().getSkinModelPreference(metadata.hash());
            playerPreviewPanel.previewSkin(
                    metadata,
                    LocalAssetManager.getInstance().getTextureLocation(metadata.hash(), TextureQuality.FULL),
                    pendingModelType
            );
        }
        updateBodyWidthButton();
    }

    /** Body width: Auto, then Wide, then Slim, on the previewed skin only. */
    private void cycleBodyWidth() {
        if (pendingHash == null || pendingModelType == null || playerPreviewPanel == null) {
            return;
        }
        pendingModelType = switch (PlayerPreviewPanel.normalizedModelType(pendingModelType)) {
            case "auto" -> "classic";
            case "classic" -> "slim";
            default -> "auto";
        };
        playerPreviewPanel.previewModelType(pendingModelType);
        updateBodyWidthButton();
    }

    private void updateBodyWidthButton() {
        if (actionButtonsPanel == null || playerPreviewPanel == null) {
            return;
        }
        String model = pendingModelType != null
                ? PlayerPreviewPanel.normalizedModelType(pendingModelType)
                : PlayerPreviewPanel.normalizedModelType(playerPreviewPanel.getCurrentModelType());
        Component name = Component.translatable(switch (model) {
            case "auto" -> "quickskin.preview.model.auto";
            case "slim" -> "quickskin.preview.model.slim";
            default -> "quickskin.preview.model.wide";
        });
        boolean selectable = pendingHash != null && pendingModelType != null && !playerPreviewPanel.isCpmLook();
        actionButtonsPanel.updateBodyWidth(name, selectable);
    }

    /** Confirm: applies the previewed look and closes the menu. */
    private void confirmSelection() {
        applySelection();
        onClose();
    }

    /**
     * Applies the pending look exactly as choosing it in the list used to: a CPM model through CPM,
     * a skin and its arm width to the player and the saved selection.
     */
    private void applySelection() {
        if (pendingHash == null) {
            return;
        }
        AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(pendingHash);
        if (metadata == null) {
            return;
        }

        if (metadata.isCpmModel()) {
            // Re-selecting Quick Skin's model while it is still the look (the menu opening on
            // it) is no new choice: CPM already has it.
            boolean modelIsTheLook = metadata.hash().equals(
                    com.quickskin.mod.config.ClientConfig.getInstance().activeCpmModelHash)
                    && com.quickskin.mod.client.compat.CpmLook.owner()
                            == com.quickskin.mod.client.compat.CpmLook.Owner.QUICK_SKIN_MODEL;
            if (!modelIsTheLook
                    && !com.quickskin.mod.client.compat.CpmModelWorkflow.activateModel(metadata)) {
                showError(Component.literal("Unable to select CPM model."));
                return;
            }
            // The model replaces the applied Quick Skin skin. Withdraw it here and on the
            // server (the cape stays), or other players keep drawing the old skin.
            if (this.minecraft != null && this.minecraft.player != null) {
//? if <1.21 {
                java.util.UUID targetUUID = com.quickskin.mod.client.compat.ReplayModHelper.getTargetPlayerUUID();
//?} else {
                java.util.UUID targetUUID = this.minecraft.player.getUUID();
//?}
                com.quickskin.mod.client.services.PlayerAppearanceService appearances =
                        com.quickskin.mod.client.services.PlayerAppearanceService.getInstance();
                if (targetUUID != null && appearances.hasActiveSkin(targetUUID)) {
                    appearances.applySkin(targetUUID, "", null);
                }
            }
            return;
        }

        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // Check if this skin is already the active skin. While a CPM model is the look, the
        // saved skin is only remembered, and choosing it applies it.
        boolean isSkinAlreadyActive = metadata.hash().equals(config.activeSkinHash)
                && config.activeCpmModelHash.isEmpty()
                && !com.quickskin.mod.client.compat.CpmLook.owner().withholdsQuickSkinSkin();

        // The arm width is saved for this specific skin; "auto" is saved as the user's preference.
        String modelType = pendingModelType != null
                ? pendingModelType
                : LocalAssetManager.getInstance().getSkinModelPreference(metadata.hash());
        boolean modelChanged = !modelType.equals(
                LocalAssetManager.getInstance().getSkinModelPreference(metadata.hash()));
        if (modelChanged) {
            LocalAssetManager.getInstance().setSkinModelPreference(metadata.hash(), modelType);
            // Like a skin selection, this choice outranks a server record not seen yet.
            com.quickskin.mod.config.AccountSkinSession.getInstance().selected();
        }

        if (isSkinAlreadyActive && !modelChanged) {
            return;
        }
        if (!isSkinAlreadyActive) {
            com.quickskin.mod.client.compat.CpmModelWorkflow.activateSkin(metadata.hash());
        }
        // The skin is saved to config even outside a world, so the selection persists on the
        // title screen. In a world it is also applied to the player entity.
        if (this.minecraft != null && this.minecraft.player != null) {
            String skinId = "local_skin:" + metadata.hash();
//? if <1.21 {
            // Use ReplayModHelper to get the correct player UUID (handles replay mode)
            java.util.UUID targetUUID = com.quickskin.mod.client.compat.ReplayModHelper.getTargetPlayerUUID();
            if (targetUUID != null) {
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(targetUUID, skinId, modelType);
            }
//?} else {
            com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                    .applySkin(this.minecraft.player.getUUID(), skinId, modelType);
//?}
        }
    }

    /**
     * Get the font renderer
     */
    public net.minecraft.client.gui.Font getFont() {
        return this.font;
    }

    /**
     * Public wrapper for addRenderableWidget to allow panels to add widgets
     */
    public <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry> void registerWidget(T widget) {
        this.addRenderableWidget(widget);
    }

    /**
     * Register a control that stays above the player preview. It is not a renderable:
     * render() draws it in its own pass after the preview.
     */
    public void registerForegroundControl(AbstractWidget widget) {
        foregroundControls.add(widget);
        this.addWidget(widget);
    }

    /**
     * Open file dialog to import a skin
     */
    private void openImportDialog() {
        FileDialogHelper.openSkinFileDialog("Select Skin File", this::handleSkinImport);
    }

    /**
     * Handle imported skin file
     */
    private void handleSkinImport(Path filePath) {
        if (filePath == null) {
            return;
        }

        // Import on main thread (Minecraft.getInstance().execute runs on main thread)
        if (this.minecraft != null) {
            this.minecraft.execute(() -> {
                AssetMetadata metadata;
//? if <1.21.11 {
                if (filePath.toString().toLowerCase(Locale.ROOT).endsWith(".cpmmodel")) {
//?} else {
                if (com.quickskin.mod.client.compat.CpmModelWorkflow.isModelFile(filePath)) {
//?}
                    if (!com.quickskin.mod.client.compat.CPMCompatIntegration.isAvailable()) {
                        showError(Component.literal("CPM mod required for .cpmmodel files."));
                        return;
                    }
                    metadata = SkinImporter.importCpmModel(filePath);
                } else {
                    metadata = SkinImporter.importSkin(filePath);
                }
                if (metadata != null) {
                    // Reload the skin list
                    refreshSkinList();

                    // Auto-select the imported skin
                    if (skinListPanel != null) {
                        skinListPanel.setSelected(metadata);
                    }
                } else {
                    // Show error message to user
                    showError(Component.translatable("quickskin.error.import_failed"));
                }
            });
        }
    }

    /**
     * Refresh the skin list after importing
     */
    /**
     * Refresh the skin list UI
     * Public so it can be called when textures are reloaded
     */
    public void refreshSkinList() {
        if (skinListPanel != null) {
            skinListPanel.refresh();
        }
    }

    /**
     * Show an error toast message
     */
    public void showError(Component message) {
        errorToasts.add(new ErrorToast(message));
    }

    /**
     * Render error toasts
     */
//? if <26.1 {
    private void renderErrorToasts(GuiGraphics graphics) {
//?} else {
    private void renderErrorToasts(GuiGraphicsExtractor graphics) {
//?}
        errorToasts.removeIf(toast -> !toast.render(graphics, width));
    }

    /**
     * Show deletion confirmation dialog
     */
    public void showDeleteConfirmation(AssetMetadata metadata) {
        if (minecraft == null) return;

        String displayName = truncateFileName(metadata.friendlyName());
        openingSubScreen = true;
//? if <26.2 {
        minecraft.setScreen(new DeletionConfirmScreen(
//?} else {
        minecraft.gui.setScreen(new DeletionConfirmScreen(
//?}
                this,
                Component.translatable("quickskin.screen.delete.title"),
                Component.translatable("quickskin.dialog.confirm_delete", displayName),
                (confirmed) -> {
                    if (confirmed) {
                        // Confirm deletion
                        deleteSkin(metadata);
                    }
                    // Return to skin menu screen
                    if (minecraft != null) {
//? if <26.2 {
                        minecraft.setScreen(this);
//?} else {
                        minecraft.gui.setScreen(this);
//?}
                    }
                },
                true
        ));
    }

    /**
     * Show rename dialog for a skin
     */
    public void showRenameDialog(AssetMetadata metadata) {
        if (minecraft == null) return;

        openingSubScreen = true;
//? if <26.2 {
        minecraft.setScreen(new RenameScreen(
//?} else {
        minecraft.gui.setScreen(new RenameScreen(
//?}
                this,
                Component.translatable("quickskin.screen.rename.title"),
                Component.empty(),
                metadata.friendlyName(),
                (newName) -> {
                    // Rename the skin. RenameScreen's Done button closes itself back to this
                    // parent right after the callback, so returning here as well would set the
                    // same screen twice and run removed() on the menu while it is being shown.
                    renameSkin(metadata, newName);
                }
        ));
    }

    /**
     * Show upload to Mojang dialog for a skin
     */
    public void showUploadToMojangDialog(AssetMetadata metadata) {
        if (minecraft == null) return;

        openingSubScreen = true;
//? if <26.2 {
        minecraft.setScreen(new UploadToMojangScreen(
//?} else {
        minecraft.gui.setScreen(new UploadToMojangScreen(
//?}
                this,
                metadata,
                (confirmed) -> {
                    if (minecraft != null) {
//? if <26.2 {
                        minecraft.setScreen(this);
//?} else {
                        minecraft.gui.setScreen(this);
//?}
                    }
                }
        ));
    }

    /**
     * Truncate filename to 35 characters, adding ellipsis if needed
     */
    private String truncateFileName(String name) {
        if (name.length() <= 35) {
            return name;
        }
        return name.substring(0, 32) + "...";
    }

    /**
     * Delete a skin from local storage
     */
    private void deleteSkin(AssetMetadata metadata) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // Prevent deletion of the player's own skin
        if (config.enablePlayerOwnSkinSystem && metadata.hash().equals(config.playerOwnSkinHash)) {
            showError(Component.translatable("quickskin.error.delete_own_skin"));
            return;
        }

        try {
            if (!LocalAssetManager.getInstance().deleteAsset(metadata.hash())) {
                showError(Component.translatable("quickskin.error.delete_failed", metadata.friendlyName()));
                return;
            }

            if (minecraft != null) {
                minecraft.getSoundManager().play(
                        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                        )
                );
            }

            boolean wasApplied = metadata.hash().equals(config.activeSkinHash);
            if (metadata.hash().equals(pendingHash)) {
                pendingHash = null;
                pendingModelType = null;
            }

            // Refresh the asset manager and skin list
            LocalAssetManager.getInstance().discoverLocalAssets();
            refreshSkinList();

            // Preview the player's own skin after deletion. Deleting the skin that is worn applies
            // the own skin straight away, so the look never names a file that no longer exists.
            if (!config.playerOwnSkinHash.isEmpty() && skinListPanel != null) {
                AssetMetadata playerOwnSkin = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
                if (playerOwnSkin != null) {
                    skinListPanel.setSelected(playerOwnSkin, true);
                    if (wasApplied) {
                        applySelection();
                    }
                }
            }

        } catch (RuntimeException e) {
            showError(Component.translatable("quickskin.error.delete_failed", e.getMessage()));
        }
    }

    /**
     * Rename a skin file
     */
    private void renameSkin(AssetMetadata metadata, String newName) {
        LocalAssetManager.RenameResult result = LocalAssetManager.getInstance()
                .renameLocalAsset(metadata.hash(), newName);

        switch (result) {
            case SUCCESS:

                // Play success sound
                if (minecraft != null) {
                    minecraft.getSoundManager().play(
                            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                    SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                            )
                    );
                }

                // Refresh the skin list to show the new name
                refreshSkinList();

                // Re-select the renamed skin
                if (skinListPanel != null) {
                    AssetMetadata updatedMetadata = LocalAssetManager.getInstance().getMetadata(metadata.hash());
                    if (updatedMetadata != null) {
                        skinListPanel.setSelected(updatedMetadata);
                    }
                }
                break;

            case NAME_TAKEN:
                showError(Component.translatable("quickskin.error.rename_exists"));
                break;

            case INVALID_NAME:
                showError(Component.translatable("quickskin.error.rename_invalid"));
                break;

            case IO_ERROR:
                showError(Component.translatable("quickskin.error.rename_failed"));
                break;

            case NOT_FOUND:
                showError(Component.translatable("quickskin.error.rename_not_found"));
                break;
        }
    }

    /**
     * Called when the username search field changes
     */
    private void onUsernameFieldChanged(String text) {
        if (searchButton != null) {
            searchButton.active = !text.trim().isEmpty() && !isSearching;
        }
    }

    /**
     * Search for a skin using Mojang API
     */
    private void searchMojangSkin() {
        if (usernameSearchField == null || isSearching) {
            return;
        }

        String username = usernameSearchField.getValue().trim();
        if (username.isEmpty()) {
            return;
        }

        // Disable search while fetching
        isSearching = true;
        searchButton.active = false;
        searchButton.setMessage(Component.translatable("quickskin.button.searching"));

        // Fetch skin asynchronously
        MojangApiService.getInstance().fetchSkinByUsername(username)
                .thenAccept(skinData -> {
                    // Execute on main thread
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> handleMojangSkinFetched(skinData));
                    }
                })
                .exceptionally(throwable -> {
                    logMojangSearchFailure(username, throwable);
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            showError(mojangSearchError(throwable, username));
                            resetSearchButton();
                        });
                    }
                    return null;
                });
    }

    /** One log line per failed search, so a report can quote the step that failed. */
    private static void logMojangSearchFailure(String username, Throwable throwable) {
        MojangApiService.ImportFailure failure = MojangApiService.findFailure(throwable);
        if (failure == null) {
            QuickSkinInfo.LOGGER.warn("Username skin search for '{}' failed unexpectedly", username, throwable);
            return;
        }
        switch (failure.reason()) {
            case INVALID_USERNAME, NOT_FOUND, NO_CUSTOM_SKIN ->
                    QuickSkinInfo.LOGGER.info("Username skin search for '{}': {}", username, failure.getMessage());
            // A transport error names the host or timeout. A parse error could quote the response
            // body, so every other cause is logged by its class only.
            case NETWORK -> QuickSkinInfo.LOGGER.warn("Username skin search for '{}' failed: {} - {}", username,
                    failure.getMessage(), String.valueOf(failure.getCause()));
            default -> QuickSkinInfo.LOGGER.warn("Username skin search for '{}' failed: {}{}", username,
                    failure.getMessage(), failure.getCause() == null ? ""
                            : " [" + failure.getCause().getClass().getSimpleName() + "]");
        }
    }

    private static Component mojangSearchError(Throwable throwable, String username) {
        MojangApiService.ImportFailure failure = MojangApiService.findFailure(throwable);
        if (failure == null) return Component.translatable("quickskin.error.skin_search_failed");
        return switch (failure.reason()) {
            case INVALID_USERNAME -> Component.translatable("quickskin.error.invalid_username");
            case NOT_FOUND -> Component.translatable("quickskin.error.player_not_found", username);
            case NO_CUSTOM_SKIN -> Component.translatable("quickskin.error.no_custom_skin");
            case INVALID_RESPONSE, RESPONSE_TOO_LARGE -> Component.translatable("quickskin.error.invalid_skin_response");
            case HTTP, NETWORK -> Component.translatable(switch (failure.stage()) {
                case LOOKUP -> "quickskin.error.lookup_unavailable";
                case PROFILE -> "quickskin.error.profile_unavailable";
                case DOWNLOAD -> "quickskin.error.download_unavailable";
            });
        };
    }

    /**
     * Handle the fetched Mojang skin data
     */
    private void handleMojangSkinFetched(MojangApiService.MojangSkinData skinData) {
        try {
            // Save the skin image to local storage
            Path skinPath = SkinImporter.saveSkinImage(skinData.image, skinData.username);

            if (skinPath != null) {

                // Reload the asset manager to pick up the new file
                LocalAssetManager.getInstance().reload();

                // Get the metadata for the saved file
                String hash = com.quickskin.mod.common.util.HashUtil.computeFileContentId(skinPath);
                if (hash != null) {
                    AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(hash);

                    if (metadata != null) {
                        // Refresh the skin list
                        refreshSkinList();

                        // Auto-select the imported skin
                        if (skinListPanel != null) {
                            skinListPanel.setSelected(metadata);
                        }

                        // Clear the search field
                        usernameSearchField.setValue("");

                        // Play success sound
                        if (minecraft != null) {
                            minecraft.getSoundManager().play(
                                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                            SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                                    )
                            );
                        }
                    } else {
                        showError(Component.translatable("quickskin.error.load_metadata_failed"));
                    }
                } else {
                    showError(Component.translatable("quickskin.error.compute_hash_failed"));
                }
            } else {
                showError(Component.translatable("quickskin.error.save_image_failed"));
            }
        } catch (Exception e) {
            showError(Component.translatable("quickskin.error.generic", e.getMessage()));
        } finally {
            resetSearchButton();
        }
    }

    /**
     * Reset the search button state
     */
    private void resetSearchButton() {
        isSearching = false;
        if (searchButton != null) {
            searchButton.setMessage(Component.translatable("quickskin.button.search"));
            searchButton.active = usernameSearchField != null && !usernameSearchField.getValue().trim().isEmpty();
        }
    }

    /**
     * Get current skin sort mode
     */
    private SkinSortMode getCurrentSortMode() {
        return com.quickskin.mod.config.ClientConfig.getInstance().getSkinSortMode();
    }

    /**
     * Cycle to next sort mode
     */
    private void cycleSortMode() {
        SkinSortMode currentMode = getCurrentSortMode();
        SkinSortMode nextMode = currentMode.next();

        // Save preference
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        config.setSkinSortMode(nextMode);

        // Update button appearance
        sortButton.setMessage(Component.literal(nextMode.getIcon()));
        sortButton.setTooltip(Tooltip.create(
                Component.translatable("quickskin.tooltip.sorting", Component.translatable(nextMode.getTranslationKey()))
        ));

        // Refresh the skin list with new sorting
        if (skinListPanel != null) {
            skinListPanel.refresh();
        }

        // Play click sound
        minecraft.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
        );
    }
}
