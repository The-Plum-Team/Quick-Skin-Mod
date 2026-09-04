package com.quickskin.mod.client.gui.screen;

//? if <26.1.2 {
//?} else {
import com.quickskin.mod.client.gui.GuiCompat;
//?}
import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.QuickSkin;
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
import com.quickskin.mod.platform.PlatformHelper;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinSortMode;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1.2 {
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
public class PlayerSkinMenuScreen extends Screen {

    @Nullable
    private final Screen parent;

    // Panels
    private SkinListPanel skinListPanel;
    private PlayerPreviewPanel playerPreviewPanel;
    private ActionButtonsPanel actionButtonsPanel;

    // Panel dimensions
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    // GUI scale management
    private boolean guiScaleForced = false;
    private boolean isClosing = false;
    private boolean openingSubScreen = false;

    // Picks up skins copied into the uploads folder from outside the game
    private final LocalAssetFolderWatch localAssetWatch = new LocalAssetFolderWatch();

    // Player preview rotation state (preserved across resizes)
    private float savedBodyYaw = 20.0f;
    private float savedTargetRotation = 20.0f;

    // Current model type (preserved across resizes)
    private String savedModelType = null;

    // Constants
    private static final int MIN_PANEL_WIDTH = 340;
    private static final int MAX_PANEL_WIDTH = 600;
    private static final int MIN_PANEL_HEIGHT = 280;

    // --- NEW ---: Constants for the background effect
//? if <1.21 {
    private static final ResourceLocation STAR_PATTERN_TEXTURE = new ResourceLocation(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = new ResourceLocation("textures/misc/vignette.png");
//?} else if <1.21.11 {
    private static final ResourceLocation STAR_PATTERN_TEXTURE = ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");
//?} else {
    private static final Identifier STAR_PATTERN_TEXTURE = Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final Identifier VIGNETTE_LOCATION = Identifier.withDefaultNamespace("textures/misc/vignette.png");
//?}

    // Error toasts
    private final List<ErrorToast> errorToasts = new ArrayList<>();

    // Mojang search widgets
    private EditBox usernameSearchField;
    private Button searchButton;
    private Button sortButton;
    private boolean isSearching = false;

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
        updateDoneButtonState();
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

    private void updateDoneButtonState() {
        if (this.actionButtonsPanel == null) return;
        Button doneButton = this.actionButtonsPanel.getDoneButton();
        if (doneButton == null) return;

        // Cooldown does not apply in singleplayer
//? if <26.2 {
        if (this.minecraft != null && this.minecraft.isSingleplayer()) {
//?} else {
        if (this.minecraft != null && this.minecraft.hasSingleplayerServer()) {
//?}
            if (!doneButton.active) {
                doneButton.active = true;
                doneButton.setMessage(Component.translatable("quickskin.button.done"));
                doneButton.setTooltip(null);
            }
            return;
        }

        long remainingSeconds = CooldownService.getInstance().getRemainingCooldownSeconds();
        if (remainingSeconds > 0) {
            doneButton.active = false;
            doneButton.setMessage(Component.translatable("quickskin.cooldown.button", remainingSeconds));
            doneButton.setTooltip(Tooltip.create(Component.translatable("quickskin.cooldown.tooltip")));
        } else {
            if (!doneButton.active) {
                doneButton.active = true;
                doneButton.setMessage(Component.translatable("quickskin.button.done"));
                doneButton.setTooltip(null);
            }
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

        // Save rotation state and model type from existing player preview panel before it's destroyed
        if (playerPreviewPanel != null) {
            com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null) {
                savedBodyYaw = widget.getBodyYaw();
                savedTargetRotation = widget.getTargetYRotation();
            }
            // Save the current model type to preserve it across resizes
            savedModelType = playerPreviewPanel.getCurrentModelType();
        }

        // Calculate panel dimensions based on screen size
        calculatePanelDimensions();

        // Use consistent sizing values
        int scaledPadding = 6;
        int scaledSpacing = 4;
        int scaledComponentHeight = 20;

        // Calculate panel areas
        int leftPanelWidth = (int) (panelWidth * 0.6f);
        int rightPanelWidth = (int) (panelWidth * 0.35f);

        int componentX = panelX + scaledPadding;
        int yPos = panelY + scaledPadding + scaledComponentHeight + scaledPadding;

        // Create Mojang username search field (below title)
        // Match the width of the skin list panel
        int searchButtonWidth = 60;
        int sortButtonSize = 20;
        // Align with skin entry highlight containers
        // Entry highlights: left = getRowLeft() (list x + ~4px), highlightLeft = left - 4px
        int searchFieldX = componentX + 4;
        int searchFieldWidth = leftPanelWidth - 4;

        // Available width for search field (reserve space for both buttons)
        int reservedWidth = sortButtonSize + searchButtonWidth + (scaledSpacing * 2);
        int searchFieldAvailableWidth = searchFieldWidth - reservedWidth;

        usernameSearchField = new EditBox(
                this.font,
                searchFieldX,
                yPos,
                searchFieldAvailableWidth,
                scaledComponentHeight,
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

        // Sort button (between search field and search button)
        int sortButtonX = searchFieldX + searchFieldAvailableWidth + scaledSpacing;
        sortButton = com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                sortButtonX,
                yPos,
                sortButtonSize,
                scaledComponentHeight,
                Component.literal(getCurrentSortMode().getIcon()),
                button -> cycleSortMode()
        );
        sortButton.setTooltip(Tooltip.create(
                Component.translatable("quickskin.tooltip.sorting", getCurrentSortMode().getDisplayName())
        ));
        addRenderableWidget(sortButton);

        // Search button (at the right edge)
        int searchButtonX = sortButtonX + sortButtonSize + scaledSpacing;
        searchButton = com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                searchButtonX,
                yPos,
                searchButtonWidth,
                scaledComponentHeight,
                Component.translatable("quickskin.button.search"),
                button -> searchMojangSkin()
        );
        addRenderableWidget(searchButton);
        searchButton.active = false;

        // Adjust the yPos for components below the search field
        yPos += scaledComponentHeight + scaledSpacing;

        // Calculate list height with proper spacing
        // Title + padding + search field + spacing + extra spacing for the list
        int topSectionHeight = scaledPadding + scaledComponentHeight + scaledPadding + scaledComponentHeight + scaledSpacing + scaledSpacing;
        int bottomSectionHeight = (scaledComponentHeight * 3) + (scaledSpacing * 2) + scaledPadding;
        int listHeight = panelHeight - topSectionHeight - bottomSectionHeight;

        // Create Skin List Panel (left side)
        skinListPanel = new SkinListPanel(
                componentX,
                yPos,
                leftPanelWidth,
                listHeight,
                this.minecraft,
                this::onSkinSelected
        );
        skinListPanel.init(this);

        // Calculate bottom section dimensions first (needed for player preview panel)
        int fullWidthX = panelX + scaledPadding;
        int fullComponentWidth = panelWidth - (scaledPadding * 2);
        int fourButtonWidth = (fullComponentWidth - (scaledSpacing * 3)) / 4;

        // Calculate where action buttons will be
        int actionButtonsBottomY = panelY + panelHeight - scaledPadding;
        int actionPanelHeight = (scaledComponentHeight * 2) + scaledSpacing;

        // Model buttons row (Row 3: above Import/HD/Skin/Cape buttons)
        int modelButtonsY = actionButtonsBottomY - actionPanelHeight - scaledComponentHeight - scaledSpacing;
        int modelButtonsX = fullWidthX + (fourButtonWidth + scaledSpacing) * 3;

        // Create Player Preview Panel (right side)
        int playerWidgetX = panelX + panelWidth - rightPanelWidth - scaledPadding;
        int playerWidgetY = yPos;
        int availableHeightForWidget = panelHeight - topSectionHeight - bottomSectionHeight;

        playerPreviewPanel = new PlayerPreviewPanel(
                playerWidgetX,
                playerWidgetY,
                rightPanelWidth,
                availableHeightForWidget
        );
        playerPreviewPanel.initPlayerWidget(this);

        // Set up model type change callback to apply model to actual player
        playerPreviewPanel.setModelTypeChangeCallback(this::onModelTypeChanged);

        // Create model buttons positioned above the cape button
        playerPreviewPanel.initModelButtons(
                this,
                modelButtonsX,
                modelButtonsY,
                fourButtonWidth,
                scaledComponentHeight,
                scaledSpacing
        );

        // Create Action Buttons Panel (bottom)
        int bottomY = actionButtonsBottomY - (scaledComponentHeight * 2) - scaledSpacing;

        ActionButtonsPanel.ActionCallbacks callbacks = new ActionButtonsPanel.ActionCallbacks(
                this::openImportDialog,
                () -> {
                    // HD Skin Website
                    if (this.minecraft != null) {
                        this.minecraft.options.chatLinksPrompt().set(false);
                        Util.getPlatform().openUri("https://mcskins.top/128x128/");
                    }
                },
                () -> {
                    // Skin Website
                    if (this.minecraft != null) {
                        this.minecraft.options.chatLinksPrompt().set(false);
                        Util.getPlatform().openUri("https://laby.net/skins?order=trending_30d");
                    }
                },
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
                this::onClose
        );

        actionButtonsPanel = new ActionButtonsPanel(
                fullWidthX,
                bottomY,
                fullComponentWidth,
                actionPanelHeight,
                callbacks
        );
        actionButtonsPanel.init(this, callbacks);

        // Create Link Buttons Panel (top-right)
        int linkButtonY = panelY + scaledPadding;
        int linkPanelWidth = (scaledComponentHeight + scaledSpacing) * 4;
        int linkPanelX = panelX + panelWidth - linkPanelWidth - scaledPadding;

        LinkButtonsPanel linkButtonsPanel = new LinkButtonsPanel(
                linkPanelX,
                linkButtonY,
                linkPanelWidth,
                scaledComponentHeight
        );
        linkButtonsPanel.init(this);

        // Restore saved model type and active skin from config
        restoreSavedState();
    }

    /**
     * Restore the saved model type and active skin from config
     */
    private void restoreSavedState() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // Check if we're restoring from a resize (savedModelType is not null)
        boolean isResizing = savedModelType != null;

        // Restore active skin selection first
        AssetMetadata selectedSkin = null;
        if (!config.activeSkinHash.isEmpty() && skinListPanel != null) {
            AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(config.activeSkinHash);
            if (metadata != null) {
                // Don't trigger callback during resize - we'll set model type manually
                skinListPanel.setSelected(metadata, !isResizing);
                selectedSkin = metadata;
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
                skinListPanel.setSelected(cpmModel, !isResizing);
                selectedSkin = cpmModel;
            }
        } else if (config.activeSkinHash.isEmpty() && !config.playerOwnSkinHash.isEmpty() && skinListPanel != null) {
            // If no active skin is set, auto-select the player's own skin
            AssetMetadata playerOwnSkin = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
            if (playerOwnSkin != null) {
                // Don't trigger callback during resize - we'll set model type manually
                skinListPanel.setSelected(playerOwnSkin, !isResizing);
                selectedSkin = playerOwnSkin;
            }
        }

        // Restore model type preference for the selected skin
        if (playerPreviewPanel != null && selectedSkin != null && isResizing) {
            // During resize, use the saved model type
            playerPreviewPanel.setCurrentModelType(savedModelType);

            // Update the preview with the correct skin
            playerPreviewPanel.updateSkin(
                    selectedSkin,
                    LocalAssetManager.getInstance().getTextureLocation(selectedSkin.hash(), com.quickskin.mod.common.data.TextureQuality.FULL)
            );

            // Clear saved model type after using it
            savedModelType = null;
        }
        // Note: If not resizing, onSkinSelected callback will handle loading the preference

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

//? if <26.1.2 {
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

    /**
     * Calculate panel dimensions based on screen size
     * Uses FIXED sizes since we're forcing GUI scale
     */
    private void calculatePanelDimensions() {
        // Calculate panel dimensions as percentages of screen for flexible sizing
        int desiredWidth = (int)(this.width * 0.5f);
        int desiredHeight = (int)(this.height * 0.8f);

        panelWidth = Mth.clamp(
                desiredWidth,
                MIN_PANEL_WIDTH,
                Math.min(MAX_PANEL_WIDTH, this.width - 80)
        );

        panelHeight = Mth.clamp(
                desiredHeight,
                MIN_PANEL_HEIGHT,
                this.height - 80
        );

        // Adjust panel size if components don't fit
        int minRequiredHeight = calculateMinRequiredHeight();
        if (panelHeight < minRequiredHeight) {
            panelHeight = Math.min(minRequiredHeight, this.height - 40);
        }

        int minRequiredWidth = calculateMinRequiredWidth();
        if (panelWidth < minRequiredWidth) {
            panelWidth = Math.min(minRequiredWidth, this.width - 40);
        }

        // Center the panel
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;
    }

    /**
     * Calculate minimum required height for all components
     */
    private int calculateMinRequiredHeight() {
        int scaledPadding = 6;
        int scaledComponentHeight = 20;
        int scaledSpacing = 4;
        // Title + username row + list (min 3 entries) + 3 button rows
        return scaledPadding * 4 + scaledComponentHeight * 7 + scaledSpacing * 4 + 120; // 120 for min list height
    }

    /**
     * Calculate minimum required width for all components
     */
    private int calculateMinRequiredWidth() {
        int scaledPadding = 6;
        int scaledSpacing = 4;
        // Need space for left panel + right panel (player widget) + padding
        return 220 + 150 + scaledPadding * 3 + scaledSpacing * 2;
    }

    // --- NEW ---: Method to render the animated background
    /**
     * Renders a moving star pattern background similar to the effect on the example website.
     * This includes a tiled, scrolling texture and a vignette overlay for depth.
     */
//? if <26.1.2 {
    private void renderBackgroundEffects(GuiGraphics graphics, float partialTick) {
//?} else {
    private void renderBackgroundEffects(GuiGraphicsExtractor graphics, float partialTick) {
//?}
        BackgroundRenderer.renderBackground(this, graphics, partialTick);
    }

    @Override
//? if <26.1.2 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
//?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
//?}
        // Render the animated background
        this.renderBackgroundEffects(graphics, partialTick);

        // Render panel background (frosted glass effect)
        renderPanel(graphics);

        // Render title
//? if <26.1.2 {
        graphics.drawCenteredString(
//?} else {
        graphics.centeredText(
//?}
                this.font,
                this.title,
                this.width / 2,
                panelY + 10,
//? if <1.21.9 {
                0xFFFFFFFF
//?} else {
                0xFFFFFFFF
//?}
        );

//? if <1.21 {
//?} else if <1.21.11 {
        // Push pose
        graphics.pose().pushPose();

//?} else {
        // Push pose (1.21.11: Matrix3x2fStack uses pushMatrix/popMatrix)
        graphics.pose().pushMatrix();

//?}
        // Render widgets (buttons, etc.)
//? if <1.21 {
        super.render(graphics, mouseX, mouseY, partialTick);
//?} else if <1.21.11 {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Pop pose
        graphics.pose().popPose();
//?} else if <26.1.2 {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Pop pose
        graphics.pose().popMatrix();
//?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Pop pose
        graphics.pose().popMatrix();
//?}

        // Render error toasts (on top of everything)
        renderErrorToasts(graphics);
    }

    /**
     * Render the main panel with frosted glass effect
     */
//? if <26.1.2 {
    private void renderPanel(GuiGraphics graphics) {
//?} else {
    private void renderPanel(GuiGraphicsExtractor graphics) {
//?}
        // Panel background (dark semi-transparent)
        graphics.fill(
                panelX, panelY,
                panelX + panelWidth, panelY + panelHeight,
                0xB0000000
        );

        // Panel outline (subtle white)
        // Top
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0x60FFFFFF);
        // Bottom
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0x60FFFFFF);
        // Left (shortened by 1px at top and bottom to avoid corner overlap)
        graphics.fill(panelX, panelY + 1, panelX + 1, panelY + panelHeight - 1, 0x60FFFFFF);
        // Right (shortened by 1px at top and bottom to avoid corner overlap)
        graphics.fill(panelX + panelWidth - 1, panelY + 1, panelX + panelWidth, panelY + panelHeight - 1, 0x60FFFFFF);
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
//?} else if <1.21.11 {
    //? if <1.21.4 {
    public void renderBlurredBackground(float partialTick) {
    //?} else if <1.21.6 {
    protected void renderBlurredBackground() {
    //?} else {
    protected void renderBlurredBackground(net.minecraft.client.gui.GuiGraphics guiGraphics) {
    //?}
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
//?} else if <26.1.2 {
    protected void renderBlurredBackground(net.minecraft.client.gui.GuiGraphics guiGraphics) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.scancode();
        int modifiers = event.modifiers();
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
        int scanCode = event.scancode();
        int modifiers = event.modifiers();
//?}
        // Allow ESC to close
        if (keyCode == 256) { // ESC key
            this.onClose();
            return true;
        }
//? if <1.21.11 {
        return super.keyPressed(keyCode, scanCode, modifiers);
//?} else {
        return super.keyPressed(event);
//?}
    }

    @Override
//? if <1.21 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
//?} else if <1.21.11 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Give PlayerWidget input priority for its customization feature
        if (playerPreviewPanel != null) {
            PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null && widget.mouseClicked(mouseX, mouseY, button)) {
                this.setFocused(widget);
                if (button == 0) {
                    this.setDragging(true);
                }
                return true;
            }
        }

//?} else if <26.1.2 {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.buttonInfo().button();
        // Give PlayerWidget input priority for its customization feature
        if (playerPreviewPanel != null) {
            PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null && widget.mouseClicked(event, focused)) {
                this.setFocused(widget);
                if (button == 0) {
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
        // Give PlayerWidget input priority for its customization feature
        if (playerPreviewPanel != null) {
            PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null && widget.mouseClicked(event, focused)) {
                this.setFocused(widget);
                if (button == 0) {
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
//? if <1.21.11 {
        return super.mouseClicked(mouseX, mouseY, button);
//?} else {
        return super.mouseClicked(event, focused);
//?}
    }

    @Override
//? if <1.21.11 {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
//?} else if <26.1.2 {
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
//? if <1.21.11 {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
//?} else {
        return super.mouseDragged(event, dragX, dragY);
//?}
    }

    @Override
//? if <1.21.11 {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
//?} else if <26.1.2 {
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
//? if <1.21 {
        return super.mouseReleased(mouseX, mouseY, button);
//?} else if <1.21.11 {
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
     * Called when a skin is selected from the list
     */
    public void onSkinSelected(SkinEntry entry) {
        if (playerPreviewPanel != null && entry != null) {
            AssetMetadata metadata = entry.getMetadata();

            if (metadata.isCpmModel()) {
                // CPM model selected - tell CPM to use this model
                // Update preview with the model icon
                playerPreviewPanel.updateSkin(
                        metadata,
//? if <1.21.11 {
                        LocalAssetManager.getInstance().getTextureLocation(metadata.hash(), TextureQuality.PREVIEW)
//?} else {
                        LocalAssetManager.getInstance().getTextureLocation(
                                metadata.hash(), TextureQuality.PREVIEW)
//?}
                );
                if (!com.quickskin.mod.client.compat.CpmModelWorkflow.activateModel(metadata)) {
                    showError(Component.literal("Unable to select CPM model."));
                }
                return;
            }

            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

            // Check if this skin is already the active skin
            boolean isSkinAlreadyActive = metadata.hash().equals(config.activeSkinHash)
                    && config.activeCpmModelHash.isEmpty();
            // Get the model type preference for this specific skin
//? if <1.21 {
            String modelPreference = LocalAssetManager.getInstance().getSkinModelPreference(metadata.hash());
//?} else {
            String modelType = LocalAssetManager.getInstance().getSkinModelPreference(metadata.hash());
//?}

            // Update the model buttons to reflect this skin's preference
//? if <1.21 {
            // This ensures the UI correctly shows "Auto" as selected if that is the preference.
            playerPreviewPanel.setCurrentModelType(modelPreference);
//?} else {
            playerPreviewPanel.setCurrentModelType(modelType);
//?}

            // Update player preview with selected skin
            playerPreviewPanel.updateSkin(
                    metadata,
                    LocalAssetManager.getInstance().getTextureLocation(metadata.hash(), TextureQuality.FULL)
            );

            // Apply the change if it's a new skin selection.
            if (!isSkinAlreadyActive) {
                com.quickskin.mod.client.compat.CpmModelWorkflow.activateSkin(metadata.hash());
                // Always save the active skin hash to config, regardless of being in-game.
                // This makes the selection persist on the title screen.
                // If in-game, apply the skin to the actual player entity.
                if (this.minecraft != null && this.minecraft.player != null) {
                    String skinId = "local_skin:" + metadata.hash();

                    // The user's preference for "auto" is passed to the service to be saved correctly.
//? if <1.21 {
                    String modelForService = modelPreference;
//?} else {
                    String modelForService = modelType;
//?}

//? if <1.21 {
                    // Use ReplayModHelper to get the correct player UUID (handles replay mode)
                    java.util.UUID targetUUID = com.quickskin.mod.client.compat.ReplayModHelper.getTargetPlayerUUID();
                    if (targetUUID != null) {
                        com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                                .applySkin(targetUUID, skinId, modelForService);
                    }
//?} else {
                    com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                            .applySkin(this.minecraft.player.getUUID(), skinId, modelForService);
//?}
                }
            }
        }
    }

    /**
     * Called when model type is changed via the model buttons
     */
    private void onModelTypeChanged(String newModelType) {
        // Get the currently selected skin entry
        SkinEntry selectedEntry = skinListPanel != null ? skinListPanel.getSelected() : null;

        if (selectedEntry != null) {
            AssetMetadata metadata = selectedEntry.getMetadata();
            if (metadata.isCpmModel()) {
                return;
            }
            String skinId = "local_skin:" + metadata.hash();

            // Save the model type preference for THIS SPECIFIC SKIN
            LocalAssetManager.getInstance().setSkinModelPreference(metadata.hash(), newModelType);

            // Apply to the actual player in-game (if in-game)
            if (this.minecraft != null && this.minecraft.player != null) {
//? if <1.21 {
                // Use ReplayModHelper to get the correct player UUID (handles replay mode)
                java.util.UUID targetUUID = com.quickskin.mod.client.compat.ReplayModHelper.getTargetPlayerUUID();
                if (targetUUID != null) {
                    // Pass the model type directly - ModelService will handle "auto" detection
                    com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                            .applySkin(targetUUID, skinId, newModelType);
                }
//?} else {
                // Pass the model type directly - ModelService will handle "auto" detection
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(this.minecraft.player.getUUID(), skinId, newModelType);
//?}
            }
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
//? if <26.1.2 {
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

            // Refresh the asset manager and skin list
            LocalAssetManager.getInstance().discoverLocalAssets();
            refreshSkinList();

            // Auto-select the player's own skin after deletion
            if (!config.playerOwnSkinHash.isEmpty() && skinListPanel != null) {
                AssetMetadata playerOwnSkin = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
                if (playerOwnSkin != null) {
                    skinListPanel.setSelected(playerOwnSkin, true);
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
                        this.minecraft.execute(() -> {
                            if (skinData != null) {
                                handleMojangSkinFetched(skinData);
                            } else {
                                showError(Component.translatable("quickskin.error.player_not_found", username));
                                resetSearchButton();
                            }
                        });
                    }
                })
                .exceptionally(throwable -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            showError(Component.translatable("quickskin.error.fetch_skin_failed", throwable.getMessage()));
                            resetSearchButton();
                        });
                    }
                    return null;
                });
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
                Component.translatable("quickskin.tooltip.sorting", nextMode.getDisplayName())
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
