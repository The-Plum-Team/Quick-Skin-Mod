package com.quickskin.mod.client.gui.screen;

//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
//?} else {
//?}
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.GuiTextColor;
//? if <26.1.2 {
import com.quickskin.mod.client.gui.util.BackgroundRenderer;
//?} else {
import com.quickskin.mod.client.gui.GuiCompat;
//?}
import com.quickskin.mod.client.gui.util.CapeImportProcessor;
import com.quickskin.mod.client.gui.util.CapeImportWorkflow;
//? if <1.21 {
import com.quickskin.mod.client.gui.GuiCompat;
//?} else if <26.1.2 {
//?} else {
import com.quickskin.mod.client.gui.util.BackgroundRenderer;
//?}
import com.quickskin.mod.client.gui.util.FileDialogHelper;
import com.quickskin.mod.client.gui.util.GuiScalingUtils;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.services.LocalAssetFolderWatch;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
//? if <1.21 {
//?} else if <1.21.11 {
import com.quickskin.mod.platform.PlatformHelper;
//?} else if <26.1.2 {
import com.quickskin.mod.client.gui.GuiCompat;
//?} else {
//?}
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.KnownCapes;
import com.quickskin.mod.common.data.TextureQuality;
//? if <26.1.2 {
import com.quickskin.mod.common.util.SafeImageReader;
//?} else {
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
//? if <1.21.6 {
//?} else if <26.1.2 {
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
//?} else {
//?}
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Cape selection menu for QuickSkin with grid-based layout
 */
@Environment(EnvType.CLIENT)
public class PlayerCapeMenuScreen extends Screen {

    // Background textures
//? if <1.21 {
    private static final ResourceLocation STAR_PATTERN_TEXTURE = new ResourceLocation(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = new ResourceLocation("textures/misc/vignette.png");
//?} else if <1.21.11 {
    private static final ResourceLocation STAR_PATTERN_TEXTURE = ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");
//?} else if <1.21.6 {
    private static final ResourceLocation STAR_PATTERN_TEXTURE = ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");
//?} else {
    private static final Identifier STAR_PATTERN_TEXTURE = Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final Identifier VIGNETTE_LOCATION = Identifier.withDefaultNamespace("textures/misc/vignette.png");
//?}

    // Base dimensions (will be scaled)
    private static final int BASE_CAPE_DISPLAY_SIZE = 64;
    private static final int BASE_CAPE_PADDING = 8;
    private static final int BASE_SCROLL_SPEED = 20;
    private static final int ACTION_BUTTON_SIZE = 11;
    private static final int HEADER_HEIGHT = 20;

    // Responsive grid constraints
    private static final int MIN_GRID_WIDTH = 300;
    private static final int MAX_GRID_WIDTH = 600;
    private static final int MIN_GRID_HEIGHT = 200;
    private static final int MAX_GRID_HEIGHT = 500;

    // Adaptive dimensions
    private int capeDisplaySize;
    private int capePadding;
    private int capesPerRow;
    private int scrollSpeed;

    @Nullable
    private final Screen parent;

    private PlayerWidget playerWidget;
    private SpeedSlider animationSpeedSlider;

    // Model position offsets from grid edge (base offset + config offset when config is 0)
    private static final int MODEL_OFFSET_X = 95; // Was 80, now 80 + 15 = 95
    private static final int MODEL_OFFSET_Y = 121; // Was 85, now 85 + 36 = 121

    // Picks up capes copied into the uploads folder from outside the game
    private final LocalAssetFolderWatch localAssetWatch = new LocalAssetFolderWatch();

    private double scrollOffset = 0;
    private double targetScrollOffset = 0;
    private int maxScroll = 0;
    private boolean isDraggingScrollbar = false;
    private double scrollbarClickOffset = 0.0;
    private int totalContentHeight = 0;
    private int gridX, gridY, gridWidth, gridHeight;

    // Player widget positioning
    private int playerWidgetX, playerWidgetY;

    // Player widget rotation state (preserved across resizes)
    private float savedBodyYaw = 20.0f;
    private float savedTargetRotation = 200.0f; // Default after initial toggleRotation()
    private int playerWidgetWidth, playerWidgetHeight;

    @Nullable
    private CapeEntry selectedCape;

    // Sectioned cape lists
    private final List<CapeEntry> localCapes = new ArrayList<>(); // Contains "None" and local capes
    private final List<CapeEntry> knownCapes = new ArrayList<>(); // Contains known/default capes

    // Import feedback
    private String importMessage = "";
    private int importMessageTimer = 0;
    private int importMessageColor = 0xFFFFFFFF;
    private CapeImportWorkflow capeImportWorkflow;
    private int capeImportGeneration;

    public PlayerCapeMenuScreen(@Nullable Screen parent) {
        super(Component.empty());
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Save rotation state from existing widget before it's destroyed
        if (this.playerWidget != null) {
            this.savedBodyYaw = this.playerWidget.getBodyYaw();
            this.savedTargetRotation = this.playerWidget.getTargetYRotation();
        }

        super.init();

        // Calculate adaptive dimensions based on screen size
        calculateAdaptiveDimensions();

        // Calculate responsive grid dimensions
        int desiredGridWidth = (int)(this.width * 0.55f);
        this.gridWidth = Mth.clamp(
                desiredGridWidth,
                MIN_GRID_WIDTH,
                Math.min(MAX_GRID_WIDTH, this.width - scaleValue(200))
        );

        int gridTopY = scaleValue(40);
        int bottomButtonY = this.height - scaleValue(60);
        int availableHeight = bottomButtonY - gridTopY - scaleValue(10);

        this.gridHeight = Mth.clamp(
                availableHeight,
                MIN_GRID_HEIGHT,
                MAX_GRID_HEIGHT
        );

        // Calculate button positions
        int buttonSpacing = 10;
        int buttonWidth = Math.min(150, (this.width - 60 - buttonSpacing * 2) / 3);
        int totalButtonWidth = buttonWidth * 3 + buttonSpacing * 2;
        int buttonStartX = (this.width - totalButtonWidth) / 2;

        // Calculate grid position (aligned with back button at 25%)
        int backButtonX = buttonStartX + (buttonWidth + buttonSpacing) * 2;
        int gridRightEdge = backButtonX + (int)(buttonWidth * 0.25f);
        this.gridX = gridRightEdge - this.gridWidth;
        this.gridY = gridTopY;

        // Refine capesPerRow for actual grid width
        refineCapesPerRowForGridWidth();

        // Load capes
        refreshCapeList();
        updateGridDimensions();

        // Create buttons
        int bottomY = this.height - scaleValue(60);

        Button importButton = this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                buttonStartX, bottomY, buttonWidth, scaleValue(20),
                Component.translatable("quickskin.button.import_cape"),
                button -> importCape()
        ));

        Button removeButton = this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                buttonStartX + buttonWidth + buttonSpacing, bottomY, buttonWidth, scaleValue(20),
                Component.translatable("quickskin.button.remove_cape"),
                button -> removeCape()
        ));

        Button closeButton = this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createPrimary(
                buttonStartX + (buttonWidth + buttonSpacing) * 2, bottomY, buttonWidth, scaleValue(20),
                Component.translatable("quickskin.button.done"),
                button -> this.onClose()
        ));

        // Create animation speed slider (centered, below buttons)
        int sliderWidth = 200;
        int sliderHeight = 20;
        int sliderX = (this.width - sliderWidth) / 2;
        int sliderY = bottomY + scaleValue(20) + 5; // Position below the buttons
        this.animationSpeedSlider = this.addRenderableWidget(new SpeedSlider(sliderX, sliderY, sliderWidth, sliderHeight));
        // Initially hidden - will be shown when an animated cape is selected
        this.animationSpeedSlider.visible = false;
        this.animationSpeedSlider.active = false;

        // Create player preview widget
        int availableWidthForWidget = this.width - (this.gridX + this.gridWidth) - scaleValue(40);
        int availableHeightForWidget = bottomY - this.gridY - scaleValue(20);

        int widgetSize = Mth.clamp(
                Math.min(availableWidthForWidget, availableHeightForWidget),
                scaleValue(100),
                scaleValue(200)
        );

        this.playerWidgetWidth = widgetSize;
        this.playerWidgetHeight = (int)(widgetSize * 1.8f);

        // Position player widget
        if (this.playerWidgetX == 0 && this.playerWidgetY == 0) {
            int gridRight = this.gridX + this.gridWidth;
            int gridCenter = this.gridY + (this.gridHeight / 2);

            this.playerWidgetX = gridRight + scaleValue(20);
            this.playerWidgetY = gridCenter - (this.playerWidgetHeight / 2);
        }

        LocalPlayer player = Minecraft.getInstance().player;
//? if <1.21.11 {
        ResourceLocation skinLocation = null;
//?} else {
        Identifier skinLocation = null;
//?}
        String modelType = "classic";

        // First priority: Use saved skin from config (works on title screen when player is null)
        ClientConfig config = ClientConfig.getInstance();
        if (!config.activeSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                // Load the saved skin texture
                skinLocation = assetManager.getTextureLocation(config.activeSkinHash, TextureQuality.FULL);

                // Get saved model type preference for this skin
                modelType = assetManager.getSkinModelPreference(config.activeSkinHash);

                // If auto mode, use the detected model type from metadata
                if ("auto".equals(modelType)) {
                    modelType = metadata.skinModel();
                }
            }
        }

        // Second priority: Use current player skin (when in-game)
        if (skinLocation == null && player != null) {
//? if <1.21 {
            skinLocation = player.getSkinTextureLocation();
//?} else if <1.21.9 {
            skinLocation = player.getSkin().texture();
//?} else {
            skinLocation = player.getSkin().body().texturePath();
//?}

            // Get model type from the active skin if available
            if (!config.activeSkinHash.isEmpty()) {
                LocalAssetManager assetManager = LocalAssetManager.getInstance();
                modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

                // If auto mode, detect from the active custom skin (if any)
                if ("auto".equals(modelType) && metadata != null) {
                    // Use the detected model type from the custom skin metadata
                    modelType = metadata.skinModel();
                } else {
                    // Fallback: detect from the vanilla player's model
//? if <1.21 {
                    modelType = player.getModelName(); // "default" or "slim"
                    // Convert Minecraft model names to our format
                    if ("default".equals(modelType)) {
                        modelType = "classic";
                    }
//?} else if <1.21.9 {
                    modelType = player.getSkin().model().id(); // "default" or "slim"
                    // Convert Minecraft model names to our format
                    if ("default".equals(modelType)) {
                        modelType = "classic";
                    }
//?} else {
                    modelType = player.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM ? "slim" : "classic";
//?}
                }
            } else if ("auto".equals(modelType)) {
                // No custom skin active, use vanilla player's model
//? if <1.21 {
                modelType = player.getModelName(); // "default" or "slim"
                // Convert Minecraft model names to our format
                if ("default".equals(modelType)) {
                    modelType = "classic";
                }
//?} else if <1.21.9 {
                modelType = player.getSkin().model().id(); // "default" or "slim"
                // Convert Minecraft model names to our format
                if ("default".equals(modelType)) {
                    modelType = "classic";
                }
//?} else {
                modelType = player.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM ? "slim" : "classic";
//?}
            }
        }

        // Fallback: Use default Steve skin
        if (skinLocation == null) {
//? if <1.21 {
            skinLocation = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
//?} else if <1.21.11 {
            skinLocation = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
//?} else {
            skinLocation = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
//?}
            modelType = "classic";
        }

        this.playerWidget = addRenderableWidget(new PlayerWidget(
                this.playerWidgetX, this.playerWidgetY,
                this.playerWidgetWidth, this.playerWidgetHeight,
                skinLocation, null, null, modelType));
        this.playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.CAPE_MENU);

        // Set custom reference point to right side center of capes grid with fixed offset
        int referenceX = this.gridX + this.gridWidth + MODEL_OFFSET_X;
        int referenceY = this.gridY + (this.gridHeight / 2) + MODEL_OFFSET_Y;
        this.playerWidget.setCustomReferencePoint(referenceX, referenceY);

        // Initialize selected cape based on config/currently equipped cape (AFTER widget is created)
        initializeSelectedCape();

        // Update speed slider visibility based on selected cape
        updateSpeedSliderVisibility();

        // Restore saved rotation state
        this.playerWidget.setRotationState(this.savedBodyYaw, this.savedTargetRotation);
    }

    private void calculateAdaptiveDimensions() {
        float scale = GuiScalingUtils.getScaleMultiplier(this.width, this.height);

        this.capeDisplaySize = Math.max(48, Math.min(96, Math.round(BASE_CAPE_DISPLAY_SIZE * scale)));
        this.capePadding = Math.max(4, Math.round(BASE_CAPE_PADDING * scale));
        this.scrollSpeed = Math.max(10, Math.round(BASE_SCROLL_SPEED * scale));

        int estimatedGridWidth = (int)(this.width * 0.55f);
        int capeWithPadding = capeDisplaySize + capePadding;
        this.capesPerRow = Mth.clamp(
                (estimatedGridWidth - capePadding * 2) / capeWithPadding,
                2,
                8
        );

        if (GuiScalingUtils.isSmallScreen(this.width, this.height)) {
            this.capesPerRow = Math.max(2, this.capesPerRow - 1);
            this.capeDisplaySize = Math.round(capeDisplaySize * 0.85f);
        }

        if (GuiScalingUtils.isLargeScreen(this.width, this.height)) {
            this.capesPerRow = Math.min(10, this.capesPerRow + 1);
        }
    }

    private void refineCapesPerRowForGridWidth() {
        int capeWithPadding = capeDisplaySize + capePadding;
        int availableWidth = this.gridWidth - (capePadding * 2);
        int maxPerRow = Math.max(2, availableWidth / capeWithPadding);
        this.capesPerRow = Mth.clamp(maxPerRow, 2, 10);
    }

    private int scaleValue(int baseValue) {
        return GuiScalingUtils.scaleValue(baseValue, this.width, this.height);
    }

    /**
     * Refresh the cape list UI
     * Public so it can be called when textures are reloaded
     */
    public void refreshCapeList() {
        this.localCapes.clear();
        this.knownCapes.clear();

        // --- Section 1: My Capes ---
        // Add "None" option first
        this.localCapes.add(CapeEntry.fromKnown(KnownCapes.NONE));

        // Then add local capes
        List<AssetMetadata> localCapeAssets = LocalAssetManager.getInstance()
                .getAssetsByType("cape");
        for (AssetMetadata localCape : localCapeAssets) {
            this.localCapes.add(CapeEntry.fromLocal(localCape));
        }

        // --- Section 2: Default Capes ---
        // Add all known capes except NONE (that's in My Capes section)
        // Only populate if user hasn't hidden built-in capes
        ClientConfig config = ClientConfig.getInstance();
        if (!config.hideBuiltInCapes) {
            for (KnownCapes knownCape : KnownCapes.values()) {
                if (!knownCape.isNoCape()) {
                    this.knownCapes.add(CapeEntry.fromKnown(knownCape));
                }
            }
        }

        // Animations are registered lazily when capes become visible in the grid
    }

    /**
     * Ensure an animated cape's animation is registered (lazy loading).
     * Uses async registration for local capes to avoid freezing the render thread.
     * The static first-frame texture is shown until the animation loads.
     */
    private void ensureAnimationRegistered(CapeEntry cape) {
        if (!cape.isAnimated()) return;

        String capeId = cape.getCapeId();
        String animationId = getAnimationIdForCape(capeId);
        if (animationId == null) return;

        com.quickskin.mod.client.services.AnimatedTextureManager animManager =
            com.quickskin.mod.client.services.AnimatedTextureManager.getInstance();

        if (animManager.isAnimated(animationId)) return;

//? if <1.21.11 {
        ResourceLocation texLoc = cape.getTextureLocation();
//?} else {
        Identifier texLoc = cape.getTextureLocation();
//?}
        if (texLoc == null) return;

        if (capeId.startsWith("local_cape:")) {
            // Async: disk I/O + pixel conversion on background thread
            String hash = capeId.substring("local_cape:".length());
            animManager.registerAnimationAsync(animationId, capeId, texLoc, hash);
        } else if (capeId.startsWith("known:")) {
            // Known capes are in resources (fast), use sync path
            com.quickskin.mod.client.services.CapeService.getInstance().getCapeLocation(null, capeId);
        }
    }

    /**
     * Initialize the selected cape based on the saved config or player's currently equipped cape
     */
    private void initializeSelectedCape() {
        String activeCapeId = null;

        // First priority: Check config (works on title screen)
        ClientConfig config = ClientConfig.getInstance();
        if (!config.activeCapeHash.isEmpty()) {
            activeCapeId = config.activeCapeHash;
        }

        // Second priority: Check PlayerAppearanceService (in-game only)
        if (activeCapeId == null && minecraft != null && minecraft.player != null) {
            java.util.UUID playerId = minecraft.player.getUUID();
            com.quickskin.mod.common.data.PlayerAppearance appearance =
                    PlayerAppearanceService.getInstance().getAppearance(playerId);

            if (appearance != null && appearance.getCapeId() != null && !appearance.getCapeId().isEmpty()) {
                activeCapeId = appearance.getCapeId();
            }
        }

        // No active cape found
        if (activeCapeId == null || activeCapeId.isEmpty()) {
            this.selectedCape = null;
            return;
        }

        // Every local tile below is a catalogue asset addressed by its SHA-256 primary, while the
        // saved reference may still be a SHA-1 alias whose migration has not run yet. Compare the
        // two in the catalogue's own vocabulary so the active cape is highlighted, previewed and
        // retimed instead of silently reading as "no cape selected".
        activeCapeId = com.quickskin.mod.client.services.CapeAnimationIds.canonicalCapeId(
                activeCapeId, this::catalogPrimaryOf);

        // Find the matching cape in both lists and update preview
        for (CapeEntry cape : this.localCapes) {
            if (cape.getCapeId().equals(activeCapeId)) {
                this.selectedCape = cape;
                // Update the preview widget with the saved cape
//? if <1.21.11 {
                ResourceLocation capeLocation = cape.getTextureLocation();
//?} else {
                Identifier capeLocation = cape.getTextureLocation();
//?}
                if (capeLocation != null && playerWidget != null) {
                    playerWidget.setCape(capeLocation, cape.getCapeId());
                }
                return;
            }
        }

        for (CapeEntry cape : this.knownCapes) {
            if (cape.getCapeId().equals(activeCapeId)) {
                this.selectedCape = cape;
                // Update the preview widget with the saved cape
//? if <1.21.11 {
                ResourceLocation capeLocation = cape.getTextureLocation();
//?} else {
                Identifier capeLocation = cape.getTextureLocation();
//?}
                if (capeLocation != null && playerWidget != null) {
                    playerWidget.setCape(capeLocation, cape.getCapeId());
                }
                return;
            }
        }

        this.selectedCape = null;
    }

    /**
     * Update the animation speed slider visibility and value based on selected cape
     */
    private void updateSpeedSliderVisibility() {
        if (this.animationSpeedSlider == null) return;

        boolean show = false;
        if (this.selectedCape != null && this.selectedCape.isAnimated()) {
            show = true;
            // Load the speed for the newly selected cape
            this.animationSpeedSlider.loadSpeedForCurrentCape();
        }

        this.animationSpeedSlider.visible = show;
        this.animationSpeedSlider.active = show;
    }

    private void updateGridDimensions() {
        int totalHeight = 0;

        // "My Capes" section height
        if (!this.localCapes.isEmpty()) {
            totalHeight += HEADER_HEIGHT;
            int localRows = (int) Math.ceil((double) this.localCapes.size() / capesPerRow);
            totalHeight += localRows * (capeDisplaySize + capePadding);
        }

        // "Default Capes" section height
        if (!this.knownCapes.isEmpty()) {
            totalHeight += HEADER_HEIGHT + 20; // Extra spacing between sections
            int knownRows = (int) Math.ceil((double) this.knownCapes.size() / capesPerRow);
            totalHeight += knownRows * (capeDisplaySize + capePadding);
        }

        this.totalContentHeight = totalHeight + capePadding;
        this.maxScroll = Math.max(0, this.totalContentHeight - this.gridHeight);
    }

    private void importCape() {
        FileDialogHelper.openCapeFileDialog("Select Cape File", this::handleCapeImport);
    }

    /**
     * Handle imported cape file
     */
    private void handleCapeImport(Path filePath) {
        if (filePath == null) {
            return;
        }

        showImportMessage(Component.translatable("quickskin.cape.processing").getString(),
                GuiTextColor.opaqueRgb(0x55AAFF), 60);
        startCapeImports(List.of(filePath));
    }

    private void startCapeImports(List<Path> sources) {
        Minecraft client = this.minecraft;
        if (client == null) {
            return;
        }

        CapeImportWorkflow previous = this.capeImportWorkflow;
        if (previous != null) {
            previous.cancel();
        }

        int generation = ++this.capeImportGeneration;
        LocalAssetManager assets = LocalAssetManager.getInstance();
        CapeImportWorkflow workflow = new CapeImportWorkflow(
                sources,
                assets.getCapesDirectory(),
                assets.getCacheDirectory(),
                getVanillaElytraImage(),
                client::execute,
//? if <26.1.2 {
                (prepared, apply, cancel) -> client.setScreen(new CapeAdjustScreen(
                        this, prepared.atlas(), prepared.frameCount(), apply, cancel)),
//?} else if <26.2 {
                (prepared, apply, cancel) -> {
                    client.setScreen(new CapeAdjustScreen(
                            this, prepared.atlas(), prepared.frameCount(), apply, cancel));
                },
//?} else {
                (prepared, apply, cancel) -> {
                    client.gui.setScreen(new CapeAdjustScreen(
                            this, prepared.atlas(), prepared.frameCount(), apply, cancel));
                },
//?}
                summary -> completeCapeImports(generation, summary));
        this.capeImportWorkflow = workflow;
        workflow.start();
    }

    private void completeCapeImports(int generation, CapeImportWorkflow.Summary summary) {
        if (generation != this.capeImportGeneration) {
            return;
        }
        this.capeImportWorkflow = null;

        if (summary.succeeded() > 0) {
            LocalAssetManager.getInstance().reload();
            refreshCapeList();
            updateGridDimensions();

            String message = summary.succeeded() == 1
                    ? Component.translatable("quickskin.cape.imported").getString()
                    : String.format("Imported %d capes", summary.succeeded());
            int notImported = summary.failed() + summary.cancelled();
            if (notImported > 0) {
                message += String.format(" (%d not imported)", notImported);
            }
            showImportMessage(message,
                    GuiTextColor.opaqueRgb(notImported == 0 ? 0x55FF55 : 0xFFAA00), 200);
            return;
        }

        String message = summary.firstError() != null
                ? Component.translatable("quickskin.cape.error", summary.firstError()).getString()
                : Component.translatable("quickskin.cape.no_valid").getString();
        showImportMessage(message, GuiTextColor.opaqueRgb(0xFF5555), 200);
    }

    private void removeCape() {
        // Always update preview widget (works both in-game and on title screen)
        playerWidget.setCape(null, null);
        this.selectedCape = null;

        // Update speed slider visibility (hide it since no cape is selected)
        updateSpeedSliderVisibility();

        // Clear from config for persistence
        ClientConfig config = ClientConfig.getInstance();
        config.activeCapeHash = "";
        config.save();

        // Remove from PlayerAppearanceService
        // Note: We use applyCape with empty string instead of removeCape
        // to avoid unregistering animations while the menu is open
        if (minecraft != null && minecraft.player != null) {
//? if <1.21 {
            // Use ReplayModHelper to get the correct player UUID (handles replay mode)
            java.util.UUID targetUUID = com.quickskin.mod.client.compat.ReplayModHelper.getTargetPlayerUUID();
            if (targetUUID != null) {
                PlayerAppearanceService.getInstance().applyCape(targetUUID, "");
            }
//?} else {
            // In-game: use the real player's UUID
            PlayerAppearanceService.getInstance()
                    .applyCape(minecraft.player.getUUID(), "");
//?}
        } else {
            // Title screen: use cached player UUID if available
            java.util.UUID dummyUUID = getDummyPlayerUUID();
            if (dummyUUID != null) {
                PlayerAppearanceService.getInstance()
                        .applyCape(dummyUUID, "");
            }
        }
    }

    public void showDeleteConfirmation(CapeEntry capeEntry) {
        // Only allow deletion of local capes
        if (!capeEntry.isLocal()) {
            return;
        }

        if (minecraft == null) {
            return;
        }

        String displayName = truncateFileName(capeEntry.getFriendlyName());
//? if <26.2 {
        minecraft.setScreen(new DeletionConfirmScreen(
//?} else {
        minecraft.gui.setScreen(new DeletionConfirmScreen(
//?}
                this,
                Component.translatable("quickskin.screen.delete_cape.title"),
                Component.translatable("quickskin.dialog.confirm_delete_cape", displayName),
                (confirmed) -> {
                    if (confirmed) {
                        deleteCape(capeEntry);
                    }
                    // Return to cape menu screen
//? if <26.2 {
                    minecraft.setScreen(this);
//?} else {
                    minecraft.gui.setScreen(this);
//?}
                },
                true
        ));
    }

    /**
     * Truncate filename to 35 characters, adding ellipsis if needed
     */
    private String truncateFileName(String name) {
        int maxLength = 35;
        if (name.length() <= maxLength) {
            return name;
        }
        return name.substring(0, maxLength - 3) + "...";
    }

    private void deleteCape(CapeEntry capeEntry) {
        if (!capeEntry.isLocal() || capeEntry.getLocalCape() == null) {
            return;
        }

        Path capePath = capeEntry.getPath();
        if (capePath == null) {
            return;
        }

        // Check if the cape being deleted is the one currently selected for preview.
        final boolean wasSelected = this.selectedCape != null && this.selectedCape.getCapeId().equals(capeEntry.getCapeId());

        try {
            Files.deleteIfExists(capePath);
            LocalAssetManager.getInstance().discoverLocalAssets();
            refreshCapeList();
            updateGridDimensions();

            // If the deleted cape was the one being previewed, call removeCape()
            // to update the preview widget and clear the active cape from the config.
            if (wasSelected) {
                removeCape();
            }

            showImportMessage(Component.translatable("quickskin.cape.deleted").getString(),
                    GuiTextColor.opaqueRgb(0x55FF55), 100);
        } catch (Exception e) {
            showImportMessage(Component.translatable("quickskin.cape.error", e.getMessage()).getString(),
                    GuiTextColor.opaqueRgb(0xFF5555), 100);
        }
    }

    private void showImportMessage(String message, int argb, int duration) {
        this.importMessage = message;
        this.importMessageColor = argb;
        this.importMessageTimer = duration;
    }

    @Override
    public void tick() {
        super.tick();
        if (importMessageTimer > 0) {
            importMessageTimer--;
        }
        pollLocalAssetFolder();
    }

    /**
     * Surface capes copied into {@code quickskin/uploads/capes} from outside the game.
     *
     * <p>Skins and capes share one catalog, so this is the same poll the skin menu runs: a
     * metadata-only folder walk on {@link ClientIoExecutor}, then a catalog rebuild on the render
     * thread only when something actually changed. The follow-up mirrors
     * {@link #completeCapeImports}, which is the existing post-rescan refresh path.
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
                        refreshCapeList();
                        updateGridDimensions();
                    }
                }));
    }

    /**
     * Renders a moving star pattern background similar to the skin menu.
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
        // Render the animated star background
        this.renderBackgroundEffects(graphics, partialTick);

//? if <1.21 {
        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, scaleValue(15), 0xFFFFFFFF);
//?} else if <1.21.6 {
        // Flush and ensure clean render state
        graphics.flush();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
//?} else {
//?}

//? if <1.21 {
        // Grid background (darker semi-transparent for better visibility)
        graphics.fill(this.gridX - 5, this.gridY - 5,
                this.gridX + this.gridWidth + 5, this.gridY + this.gridHeight + 5,
                0xB0000000);
//?} else if <1.21.6 {
        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, scaleValue(15), 0xFFFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);

        // Push pose and translate forward in Z to render on top
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 100);
//?} else if <26.1.2 {
        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, scaleValue(15), 0xFFFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);

        // Push pose (1.21.11: Matrix3x2fStack uses pushMatrix/popMatrix, no Z translate in 2D)
        graphics.pose().pushMatrix();
//?} else {
        // Title
        graphics.centeredText(this.font, this.title, this.width / 2, scaleValue(15), 0xFFFFFFFF);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Push pose (1.21.11: Matrix3x2fStack uses pushMatrix/popMatrix, no Z translate in 2D)
        graphics.pose().pushMatrix();
//?}

        // Enable scissor for grid content
        graphics.enableScissor(this.gridX, this.gridY,
                this.gridX + this.gridWidth, this.gridY + this.gridHeight);
        this.scrollOffset += (this.targetScrollOffset - this.scrollOffset) * 0.5;
        renderCapeGrid(graphics, mouseX, mouseY);
        graphics.disableScissor();

        this.renderScrollbar(graphics);
//? if <1.21 {
        super.render(graphics, mouseX, mouseY, partialTick);
//?} else if <1.21.6 {

        // Pop pose
        graphics.pose().popPose();
//?} else {

        // Pop pose
        graphics.pose().popMatrix();
//?}

        // Render import message
        if (importMessageTimer > 0 && !importMessage.isEmpty()) {
            int messageY = this.gridY + this.gridHeight + 10;
//? if <26.1.2 {
            graphics.drawCenteredString(this.font, importMessage, this.width / 2, messageY, importMessageColor);
//?} else {
            graphics.centeredText(this.font, importMessage, this.width / 2, messageY, importMessageColor);
//?}
        }

        // Tooltip logic
        if (isMouseOverGrid(mouseX, mouseY)) {
            CapeEntry hoveredCape = getCapeAt(mouseX, mouseY);
            if (hoveredCape != null) {
                boolean deleteHovered = false;
                int[] pos = getCapePosition(hoveredCape);
                if (pos != null && hoveredCape.isLocal()) {
                    int x = pos[0];
                    int y = pos[1];
                    int margin = 2;

                    int deleteButtonX = x + capeDisplaySize - ACTION_BUTTON_SIZE - margin;
                    int deleteButtonY = y + margin;
                    if (isMouseOver(mouseX, mouseY, deleteButtonX, deleteButtonY, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)) {
//? if <1.21 {
                        GuiCompat.tooltip(
                                graphics, this.font, Component.translatable("quickskin.tooltip.delete_cape"),
                                mouseX, mouseY);
//?} else if <1.21.6 {
                        graphics.renderTooltip(this.font, Component.translatable("quickskin.tooltip.delete_cape"), mouseX, mouseY);
//?} else if <26.1.2 {
                        // 1.21.11: renderTooltip takes List<ClientTooltipComponent>
                        Component tooltipText = Component.translatable("quickskin.tooltip.delete_cape");
                        List<ClientTooltipComponent> tooltipComponents = List.of(
                            ClientTooltipComponent.create(tooltipText.getVisualOrderText())
                        );
                        graphics.renderTooltip(this.font, tooltipComponents, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
//?} else {
                        GuiCompat.tooltip(
                                graphics, this.font,
                                Component.translatable("quickskin.tooltip.delete_cape"), mouseX, mouseY
                        );
//?}
                        deleteHovered = true;
                    }
                }
                if (!deleteHovered) {
//? if <1.21 {
                    GuiCompat.tooltip(graphics, this.font, getCapeTooltip(hoveredCape), mouseX, mouseY);
//?} else if <1.21.6 {
                    graphics.renderTooltip(this.font, getCapeTooltip(hoveredCape), Optional.empty(), mouseX, mouseY);
//?} else if <26.1.2 {
                    // 1.21.11: renderTooltip takes List<ClientTooltipComponent>
                    List<Component> capeTooltip = getCapeTooltip(hoveredCape);
                    List<ClientTooltipComponent> tooltipComponents = capeTooltip.stream()
                        .map(c -> ClientTooltipComponent.create(c.getVisualOrderText()))
                        .collect(java.util.stream.Collectors.toList());
                    graphics.renderTooltip(this.font, tooltipComponents, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
//?} else {
                    GuiCompat.tooltip(graphics, this.font, getCapeTooltip(hoveredCape), mouseX, mouseY);
//?}
                }
            }
        }
    }

//? if <26.1.2 {
    private void renderCapeGrid(GuiGraphics graphics, int mouseX, int mouseY) {
//?} else {
    private void renderCapeGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
//?}
        int currentY = gridY - (int) scrollOffset;

        // --- SECTION 1: MY CAPES ---
        if (!localCapes.isEmpty()) {
            currentY = renderSection(graphics, "quickskin.cape.section.my_capes", localCapes, currentY, mouseX, mouseY, true);
        }

        // --- SECTION 2: DEFAULT CAPES ---
        if (!knownCapes.isEmpty()) {
            renderSection(graphics, "quickskin.cape.section.default_capes", knownCapes, currentY + 20, mouseX, mouseY, false);
        }
    }

//? if <26.1.2 {
    private int renderSection(GuiGraphics graphics, String titleKey, List<CapeEntry> capes, int startY, int mouseX, int mouseY, boolean isLocalSection) {
//?} else {
    private int renderSection(GuiGraphicsExtractor graphics, String titleKey, List<CapeEntry> capes, int startY, int mouseX, int mouseY, boolean isLocalSection) {
//?}
        // Render Header (centered within the grid)
        int headerY = startY + HEADER_HEIGHT / 2 - 4;
        if (headerY > gridY - 8 && headerY < gridY + gridHeight + 8) {
            int gridCenterX = this.gridX + (this.gridWidth / 2);
//? if <1.21.9 {
            graphics.drawCenteredString(this.font, Component.translatable(titleKey), gridCenterX, headerY, 0xFFFFFFFF);
//?} else if <26.1.2 {
            graphics.drawCenteredString(this.font, Component.translatable(titleKey), gridCenterX, headerY, 0xFFFFFFFF);
//?} else {
            graphics.centeredText(this.font, Component.translatable(titleKey), gridCenterX, headerY, 0xFFFFFFFF);
//?}
        }
        int currentY = startY + HEADER_HEIGHT;

        // Render Grid Items
        for (int i = 0; i < capes.size(); i++) {
            CapeEntry cape = capes.get(i);
            int row = i / capesPerRow;
            int col = i % capesPerRow;

            int x = gridX + capePadding + col * (capeDisplaySize + capePadding);
            int y = currentY + capePadding + row * (capeDisplaySize + capePadding);

            if (y + capeDisplaySize < gridY || y > gridY + gridHeight) {
                continue; // Cull capes outside the visible area
            }

            renderCapeEntry(graphics, cape, x, y, mouseX, mouseY);
        }

        // For "My Capes", if the first row isn't full, render a drop zone in the remaining space
        if (isLocalSection && capes.size() < capesPerRow) {
            int col = capes.size() % capesPerRow;
            int dropZoneX = gridX + capePadding + col * (capeDisplaySize + capePadding);
            int dropZoneY = currentY + capePadding; // Y position of the first row
            int dropZoneWidth = (gridX + gridWidth) - dropZoneX - capePadding;
            int dropZoneHeight = capeDisplaySize;

            if (dropZoneWidth > capePadding) {
                renderDropZone(graphics, dropZoneX, dropZoneY, dropZoneWidth, dropZoneHeight, mouseX, mouseY);
            }
        }

        int rows = (int) Math.ceil((double) capes.size() / capesPerRow);
        return currentY + rows * (capeDisplaySize + capePadding);
    }

//? if <26.1.2 {
    private void renderDropZone(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
//?} else {
    private void renderDropZone(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
//?}
        boolean isHovering = isMouseOver(mouseX, mouseY, x, y, width, height) &&
                mouseY >= gridY && mouseY < gridY + gridHeight;

        int bgColor = isHovering ? 0x2AFFFFFF : 0x1AFFFFFF;
        graphics.fill(x, y, x + width, y + height, bgColor);

        // Draw dashed border
        drawDashedBorder(graphics, x, y, width, height, isHovering);

        // Draw text
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        Component mainMessage = Component.translatable("quickskin.dropzone.capes.main");
        Component subMessage = Component.translatable("quickskin.dropzone.capes.sub");

//? if <1.21.9 {
        int mainColor = isHovering ? 0xFFFFFFFF : 0xFFE0E0E0;
        int subColor = isHovering ? 0xFFB0B0B0 : 0xFF909090;
//?} else {
        int mainColor = isHovering ? 0xFFFFFFFF : 0xFFE0E0E0;
        int subColor = isHovering ? 0xFFB0B0B0 : 0xFF909090;
//?}

        if (height > font.lineHeight * 2.5 && width > font.width(subMessage)) {
//? if <26.1.2 {
            graphics.drawCenteredString(this.font, mainMessage, centerX, centerY - font.lineHeight / 2 - 1, mainColor);
            graphics.drawCenteredString(this.font, subMessage, centerX, centerY + font.lineHeight / 2 + 1, subColor);
//?} else {
            graphics.centeredText(this.font, mainMessage, centerX, centerY - font.lineHeight / 2 - 1, mainColor);
            graphics.centeredText(this.font, subMessage, centerX, centerY + font.lineHeight / 2 + 1, subColor);
//?}
        } else if (width > font.width(mainMessage)) {
//? if <26.1.2 {
            graphics.drawCenteredString(this.font, mainMessage, centerX, centerY - font.lineHeight / 2, mainColor);
//?} else {
            graphics.centeredText(this.font, mainMessage, centerX, centerY - font.lineHeight / 2, mainColor);
//?}
        }
    }

//? if <26.1.2 {
    private void drawDashedBorder(GuiGraphics graphics, int x, int y, int width, int height, boolean highlight) {
//?} else {
    private void drawDashedBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean highlight) {
//?}
        int color = highlight ? 0xFFFFFFFF : 0x80FFFFFF;
        int dashLength = 8;
        int gapLength = 4;
        int totalLength = dashLength + gapLength;

        // Top border
        for (int i = 0; i < width; i += totalLength) {
            int segmentLength = Math.min(dashLength, width - i);
            graphics.fill(x + i, y, x + i + segmentLength, y + 1, color);
        }

        // Bottom border
        for (int i = 0; i < width; i += totalLength) {
            int segmentLength = Math.min(dashLength, width - i);
            graphics.fill(x + i, y + height - 1, x + i + segmentLength, y + height, color);
        }

        // Left border
        for (int i = 0; i < height; i += totalLength) {
            int segmentLength = Math.min(dashLength, height - i);
            graphics.fill(x, y + i, x + 1, y + i + segmentLength, color);
        }

        // Right border
        for (int i = 0; i < height; i += totalLength) {
            int segmentLength = Math.min(dashLength, height - i);
            graphics.fill(x + width - 1, y + i, x + width, y + i + segmentLength, color);
        }
    }

//? if <26.1.2 {
    private void renderCapeEntry(GuiGraphics graphics, CapeEntry cape, int x, int y, int mouseX, int mouseY) {
//?} else {
    private void renderCapeEntry(GuiGraphicsExtractor graphics, CapeEntry cape, int x, int y, int mouseX, int mouseY) {
//?}
        boolean hovered = isMouseOver(mouseX, mouseY, x, y, capeDisplaySize, capeDisplaySize);

        // Special handling for "None" option
        if (cape.isKnown() && cape.getKnownCape() != null && cape.getKnownCape().isNoCape()) {
            // Render black background
            graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0x90000000);

            // Render "None" text centered
//? if <1.21.9 {
            graphics.drawCenteredString(this.font, Component.translatable("quickskin.cape.option.none"), x + capeDisplaySize / 2,
                    y + capeDisplaySize / 2 - 4, 0xFFFFFFFF);
//?} else if <26.1.2 {
            graphics.drawCenteredString(this.font, Component.translatable("quickskin.cape.option.none"), x + capeDisplaySize / 2,
                    y + capeDisplaySize / 2 - 4, 0xFFFFFFFF);
//?} else {
            graphics.centeredText(this.font, Component.translatable("quickskin.cape.option.none"), x + capeDisplaySize / 2,
                    y + capeDisplaySize / 2 - 4, 0xFFFFFFFF);
//?}

            // Highlight if selected or hovered
            if (isSelected(cape)) {
//? if <1.21.9 {
                graphics.renderOutline(x - 2, y - 2, capeDisplaySize + 4, capeDisplaySize + 4, 0xFFFFFF00);
//?} else {
                drawOutline(graphics,x - 2, y - 2, capeDisplaySize + 4, capeDisplaySize + 4, 0xFFFFFF00);
//?}
            } else if (hovered) {
                graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0x33FFFFFF);
            }
            return;
        }

        // Regular cape rendering
//? if <1.21.11 {
        ResourceLocation texture = cape.getTextureLocation();
//?} else {
        Identifier texture = cape.getTextureLocation();
//?}

        // If animated, ensure registration (lazy) and get the current frame texture
        if (texture != null && cape.isAnimated()) {
            ensureAnimationRegistered(cape);
            texture = com.quickskin.mod.client.services.AnimatedTextureManager.getInstance()
                .getAnimationFrame(texture)
                .orElse(texture);
        }

        // Render cape texture
        if (texture != null) {
            renderCapeTexture(graphics, texture, cape, x, y);
        } else {
            renderLoadingTexture(graphics, x, y);
        }

        // Render custom indicator
        if (cape.isCustom()) {
            renderCustomIndicator(graphics, x, y);
        }

        // Render animated indicator if applicable
        if (cape.isAnimated()) {
            renderAnimatedIndicator(graphics, x, y);
        }

        // Highlight if selected or hovered
        if (isSelected(cape)) {
//? if <1.21.9 {
            graphics.renderOutline(x - 2, y - 2, capeDisplaySize + 4, capeDisplaySize + 4, 0xFFFFFF00);
//?} else {
            drawOutline(graphics,x - 2, y - 2, capeDisplaySize + 4, capeDisplaySize + 4, 0xFFFFFF00);
//?}
        } else if (hovered) {
            graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0x33FFFFFF);
        }

        // Render delete button on hover (only for local capes, not "None")
        if (hovered && cape.isLocal() && !cape.isKnown()) {
            int margin = 2;
            int deleteButtonX = x + capeDisplaySize - ACTION_BUTTON_SIZE - margin;
            int deleteButtonY = y + margin;
            boolean deleteHovered = isMouseOver(mouseX, mouseY, deleteButtonX, deleteButtonY, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE);
            int deleteBgColor = deleteHovered ? 0xA0E04040 : 0x80C00000;
            graphics.fill(deleteButtonX, deleteButtonY, deleteButtonX + ACTION_BUTTON_SIZE, deleteButtonY + ACTION_BUTTON_SIZE, deleteBgColor);
//? if <26.1.2 {
            graphics.drawString(this.font, "x", deleteButtonX + 3, deleteButtonY + 1, 0xFFFFFFFF);
//?} else {
            graphics.text(this.font, "x", deleteButtonX + 3, deleteButtonY + 1, 0xFFFFFFFF);
//?}
        }
    }

//? if <1.21.6 {
    private void renderCapeTexture(GuiGraphics graphics, ResourceLocation texture, CapeEntry cape, int x, int y) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
//?} else if <1.21.11 {
    private void renderCapeTexture(GuiGraphics graphics, ResourceLocation texture, CapeEntry cape, int x, int y) {
//?} else if <26.1.2 {
    private void renderCapeTexture(GuiGraphics graphics, Identifier texture, CapeEntry cape, int x, int y) {
        // RenderSystem.setShaderColor() removed in 1.21.11
//?} else {
    private void renderCapeTexture(GuiGraphicsExtractor graphics, Identifier texture, CapeEntry cape, int x, int y) {
        // RenderSystem.setShaderColor() removed in 1.21.11
//?}

        int textureWidth = 64;
        int textureHeight = 32;

        // Cape coordinates (show back of cape)
        int u = 1;
        int v = 1;
        int uWidth = 10;
        int vHeight = 16;

        // Check if it's a high resolution cape - scale texture dimensions and UV coordinates
        if (cape.isLocal() && cape.getLocalCape() != null && cape.getLocalCape().resolution() != null && cape.getLocalCape().resolution().isHD()) {
            int scale = cape.getLocalCape().resolution().getScale();
            textureWidth *= scale;
            textureHeight *= scale;
            u *= scale;
            v *= scale;
            uWidth *= scale;
            vHeight *= scale;
        }

        float scaleFactor = capeDisplaySize / 56f;

//? if <1.21.6 {
        graphics.pose().pushPose();
        graphics.pose().translate(x + capeDisplaySize / 2f, y + capeDisplaySize / 2f, 0);
        graphics.pose().scale(scaleFactor * 3.5f, scaleFactor * 3.5f, 1.0f);
        graphics.pose().translate(-5, -8, 0);
//?} else {
        // 1.21.11: Matrix3x2fStack uses pushMatrix/popMatrix and 2D translate/scale
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + capeDisplaySize / 2f, y + capeDisplaySize / 2f);
        graphics.pose().scale(scaleFactor * 3.5f, scaleFactor * 3.5f);
        graphics.pose().translate(-5, -8);
//?}

//? if <1.21 {
        GuiCompat.blit(graphics, texture,
                0, 0, 10, 16, u, v, uWidth, vHeight, textureWidth, textureHeight);
//?} else if <1.21.11 {
        PlatformHelper.blit(graphics, texture, 0, 0, 10, 16, u, v, uWidth, vHeight, textureWidth, textureHeight);
//?} else {
        GuiCompat.blit(graphics, texture, 0, 0, 10, 16, u, v, uWidth, vHeight, textureWidth, textureHeight);
//?}

//? if <1.21.6 {
        graphics.pose().popPose();
//?} else {
        graphics.pose().popMatrix();
//?}
    }

//? if <26.1.2 {
    private void renderLoadingTexture(GuiGraphics graphics, int x, int y) {
//?} else {
    private void renderLoadingTexture(GuiGraphicsExtractor graphics, int x, int y) {
//?}
        graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0xFF222222);
//? if <1.21.9 {
        graphics.drawCenteredString(this.font, Component.translatable("quickskin.cape.loading"),
                x + capeDisplaySize / 2, y + capeDisplaySize / 2 - 4, 0xFF888888);
//?} else if <26.1.2 {
        graphics.drawCenteredString(this.font, Component.translatable("quickskin.cape.loading"),
                x + capeDisplaySize / 2, y + capeDisplaySize / 2 - 4, 0xFF888888);
//?} else {
        graphics.centeredText(this.font, Component.translatable("quickskin.cape.loading"),
                x + capeDisplaySize / 2, y + capeDisplaySize / 2 - 4, 0xFF888888);
//?}
    }

//? if <26.1.2 {
    private void renderCustomIndicator(GuiGraphics graphics, int x, int y) {
//?} else {
    private void renderCustomIndicator(GuiGraphicsExtractor graphics, int x, int y) {
//?}
        int indicatorSize = Math.max(4, capeDisplaySize / 16);
        int rarityColor = 0xFF5555FF; // Purple for custom
        graphics.fill(x + capeDisplaySize - indicatorSize * 2,
                y + capeDisplaySize - indicatorSize * 2,
                x + capeDisplaySize - indicatorSize / 2,
                y + capeDisplaySize - indicatorSize / 2,
                rarityColor);
    }

//? if <26.1.2 {
    private void renderAnimatedIndicator(GuiGraphics graphics, int x, int y) {
//?} else {
    private void renderAnimatedIndicator(GuiGraphicsExtractor graphics, int x, int y) {
//?}
        String badgeText = Component.translatable("quickskin.cape.animated_badge").getString();
        int textWidth = this.font.width(badgeText);
        int badgeWidth = textWidth + 4;
        int badgeHeight = this.font.lineHeight + 2;
        int margin = 2;

        int badgeX = x + margin;
        int badgeY = y + margin;

        int bgColor = 0xD000CCFF;
        graphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight, bgColor);

        int borderColor = 0xFF00AADD;
//? if <1.21.9 {
        graphics.renderOutline(badgeX, badgeY, badgeWidth, badgeHeight, borderColor);
//?} else {
        drawOutline(graphics,badgeX, badgeY, badgeWidth, badgeHeight, borderColor);
//?}

//? if <26.1.2 {
        graphics.drawString(this.font, badgeText, badgeX + 2, badgeY + 1, 0xFFFFFFFF);
//?} else {
        graphics.text(this.font, badgeText, badgeX + 2, badgeY + 1, 0xFFFFFFFF);
//?}
    }

    private boolean isSelected(CapeEntry cape) {
        if (cape == null) return false;

        // If no cape is selected and this is the "None" option, it's selected
        if (selectedCape == null) {
            return cape.isKnown() && cape.getKnownCape() != null && cape.getKnownCape().isNoCape();
        }

        return cape.getCapeId().equals(selectedCape.getCapeId());
    }

    private List<Component> getCapeTooltip(CapeEntry cape) {
        List<Component> tooltip = new ArrayList<>();

        // TextColor intentionally expects 24-bit RGB rather than GuiGraphics ARGB.
        tooltip.add(Component.literal(cape.getFriendlyName()).withStyle(s -> s.withBold(true)
                .withColor(cape.isKnown() ? 0xFFD700 : 0x55FF55))); // Gold for known, green for local

        tooltip.add(Component.literal(cape.getDescription()).withStyle(s -> s.withColor(0xCCCCCC)));

        if (cape.isAnimated()) {
            tooltip.add(Component.translatable("quickskin.tooltip.animated_cape").withStyle(s -> s.withColor(0xFFAA00)));
        } else {
            tooltip.add(Component.translatable("quickskin.tooltip.static_cape").withStyle(s -> s.withColor(0xAAAAAA)));
        }

        if (cape.isLocal() && cape.getLocalCape() != null && cape.getLocalCape().resolution() != null) {
            String resolutionText = cape.getLocalCape().resolution().name();
            tooltip.add(Component.translatable("quickskin.tooltip.resolution", resolutionText).withStyle(s -> s.withColor(0x55FFFF)));
        }

        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("quickskin.tooltip.click_preview").withStyle(s -> s.withColor(0x808080).withItalic(true)));

        return tooltip;
    }

    @Override
//? if <1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
//?} else if <26.1.2 {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        if (super.mouseClicked(event, focused)) return true;
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.buttonInfo().button();
//?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        if (super.mouseClicked(event, focused)) return true;
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
//?}

        // Handle scrollbar dragging
        if (button == 0 && this.maxScroll > 0) {
            int scrollbarWidth = scaleValue(6);
            int scrollbarX = this.gridX + this.gridWidth + 3;
            int scrollbarTrackHeight = this.gridHeight;

            if (mouseX >= scrollbarX && mouseX < scrollbarX + scrollbarWidth &&
                    mouseY >= this.gridY && mouseY < this.gridY + scrollbarTrackHeight) {

                this.isDraggingScrollbar = true;

                int thumbHeight = Mth.clamp((int) ((float) (scrollbarTrackHeight * scrollbarTrackHeight) / (float) this.totalContentHeight),
                        scaleValue(32), scrollbarTrackHeight - 8);
                int thumbY = this.gridY + (int) (this.scrollOffset * (double) (scrollbarTrackHeight - thumbHeight) / (double) this.maxScroll);

                if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
                    this.scrollbarClickOffset = mouseY - thumbY;
                } else {
                    this.scrollbarClickOffset = thumbHeight / 2.0;
                    updateScrollFromMouse(mouseY);
                }
                return true;
            }
        }

        // Handle cape selection
        if (button == 0 && isMouseOverGrid((int) mouseX, (int) mouseY)) {
            CapeEntry clickedCape = getCapeAt((int) mouseX, (int) mouseY);
            if (clickedCape != null) {
                // Check for delete button click (only for local capes, not "None")
                if (clickedCape.isLocal() && !clickedCape.isKnown()) {
                    int[] pos = getCapePosition(clickedCape);
                    if (pos != null) {
                        int x = pos[0];
                        int y = pos[1];
                        int margin = 2;

                        int deleteButtonX = x + capeDisplaySize - ACTION_BUTTON_SIZE - margin;
                        int deleteButtonY = y + margin;
                        if (isMouseOver((int) mouseX, (int) mouseY, deleteButtonX, deleteButtonY, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)) {
                            showDeleteConfirmation(clickedCape);
                            return true;
                        }
                    }
                }

                // Play selection sound
                if (minecraft != null && minecraft.getSoundManager() != null) {
                    minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f
                    ));
                }

                // Select cape (includes "None" option)
                this.selectedCape = clickedCape;
                if (clickedCape.isKnown() && clickedCape.getKnownCape() != null && clickedCape.getKnownCape().isNoCape()) {
                    removeCape();
                } else {
                    applyCape(clickedCape);
                }
                return true;
            }
        }

        return false;
    }

    private void applyCape(CapeEntry cape) {
        String capeId = cape.getCapeId();

        // Get the config
        ClientConfig config = ClientConfig.getInstance();

        // NOTE: We do NOT unregister the old animation while the menu is open.
        // All animated capes are pre-registered in registerAllAnimations() for thumbnail display.
        // If we unregister an animation when switching capes, the old cape's thumbnail will
        // fall back to using the full atlas texture, displaying all frames at once instead of
        // animating properly. Animations will be cleaned up appropriately when the menu closes
        // or when the game state changes (e.g., leaving the world).

        // IMPORTANT: Call CapeService.getCapeLocation() to trigger animation registration
        // This must be done BEFORE setting the preview widget
//? if <1.21.11 {
        ResourceLocation capeLocation = com.quickskin.mod.client.services.CapeService.getInstance()
//?} else {
        Identifier capeLocation = com.quickskin.mod.client.services.CapeService.getInstance()
//?}
                .getCapeLocation(null, capeId);

        // Fallback to direct texture if service returns null
        if (capeLocation == null) {
            capeLocation = cape.getTextureLocation();
        }

        // Always update preview widget (works both in-game and on title screen)
        playerWidget.setCape(capeLocation, capeId);

        // Save to config for persistence
        config.activeCapeHash = capeId;
        config.save();

        // Apply to PlayerAppearanceService
        if (minecraft != null && minecraft.player != null) {
//? if <1.21 {
            // Use ReplayModHelper to get the correct player UUID (handles replay mode)
            java.util.UUID targetUUID = com.quickskin.mod.client.compat.ReplayModHelper.getTargetPlayerUUID();
            if (targetUUID != null) {
                PlayerAppearanceService.getInstance().applyCape(targetUUID, capeId);
            }
//?} else {
            // In-game: use the real player's UUID
            PlayerAppearanceService.getInstance()
                    .applyCape(minecraft.player.getUUID(), capeId);
//?}
        } else {
            // Title screen: use a dummy UUID that matches the cached player if it exists
            // This allows entity rendering to work on title screen with cached player
            java.util.UUID dummyUUID = getDummyPlayerUUID();
            if (dummyUUID != null) {
                PlayerAppearanceService.getInstance()
                        .applyCape(dummyUUID, capeId);
            }
        }

        // Update speed slider visibility based on whether the selected cape is animated
        updateSpeedSliderVisibility();
    }

    /**
     * Get the UUID of the cached player entity used for rendering
     */
    private java.util.UUID getDummyPlayerUUID() {
        return PlayerModelRenderer.getCachedPlayerUUID();
    }

    private void updateScrollFromMouse(double mouseY) {
        int scrollbarTrackHeight = this.gridHeight;
        int thumbHeight = Mth.clamp((int) ((float) (scrollbarTrackHeight * scrollbarTrackHeight) / (float) this.totalContentHeight),
                scaleValue(32), scrollbarTrackHeight - 8);

        double scrollableTrackHeight = scrollbarTrackHeight - thumbHeight;
        if (scrollableTrackHeight > 0) {
            double scrollRatio = (mouseY - this.gridY - this.scrollbarClickOffset) / scrollableTrackHeight;
            this.targetScrollOffset = Mth.clamp(scrollRatio * this.maxScroll, 0, this.maxScroll);
        }
    }

    @Override
//? if <1.21.9 {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
//?} else if <26.1.2 {
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseY = event.y();
//?} else {
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseY = GuiCompat.mouseY(event);
//?}
        if (this.isDraggingScrollbar) {
            updateScrollFromMouse(mouseY);
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
//?} else if <26.1.2 {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        int button = event.buttonInfo().button();
//?} else {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        int button = GuiCompat.mouseButton(event);
//?}
        if (button == 0) {
            this.isDraggingScrollbar = false;
        }
//? if <1.21.9 {
        return super.mouseReleased(mouseX, mouseY, button);
//?} else {
        return super.mouseReleased(event);
//?}
    }

    @Override
//? if <1.21 {
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
//?} else {
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
//?}
        if (isMouseOverGrid((int) mouseX, (int) mouseY)) {
//? if <1.21 {
            this.targetScrollOffset = Mth.clamp(this.targetScrollOffset - delta * scrollSpeed, 0.0D, this.maxScroll);
//?} else {
            this.targetScrollOffset = Mth.clamp(this.targetScrollOffset - deltaY * scrollSpeed, 0.0D, this.maxScroll);
//?}
            return true;
        }
//? if <1.21 {
        return super.mouseScrolled(mouseX, mouseY, delta);
//?} else {
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
//?}
    }

//? if <26.1.2 {
    private void renderScrollbar(GuiGraphics graphics) {
//?} else {
    private void renderScrollbar(GuiGraphicsExtractor graphics) {
//?}
        if (this.maxScroll <= 0) return;

        int scrollbarWidth = scaleValue(6);
        int scrollbarX = this.gridX + this.gridWidth + 3;
        int scrollbarTrackEnd = this.gridY + this.gridHeight;

        graphics.fill(scrollbarX, this.gridY, scrollbarX + scrollbarWidth, scrollbarTrackEnd, 0x80000000);

        int thumbHeight = Mth.clamp((int) ((float) (this.gridHeight * this.gridHeight) / (float) this.totalContentHeight),
                scaleValue(32), this.gridHeight - 8);
        int thumbY = this.gridY + (int) (this.scrollOffset * (double) (this.gridHeight - thumbHeight) / (double) this.maxScroll);

        graphics.fill(scrollbarX, thumbY, scrollbarX + scrollbarWidth, thumbY + thumbHeight, -8355712);
        graphics.fill(scrollbarX + 1, thumbY + 1, scrollbarX + scrollbarWidth - 1, thumbY + thumbHeight - 1, -4144960);
    }

    @Nullable
    private CapeEntry getCapeAt(int mouseX, int mouseY) {
        if (!isMouseOverGrid(mouseX, mouseY)) return null;

        int absoluteMouseY = mouseY + (int) scrollOffset;
        int currentY = gridY;

        // --- Check "My Capes" section ---
        if (!localCapes.isEmpty()) {
            currentY += HEADER_HEIGHT;
            int rows = (int) Math.ceil((double) localCapes.size() / capesPerRow);
            int sectionHeight = rows * (capeDisplaySize + capePadding) + capePadding;

            if (absoluteMouseY >= currentY && absoluteMouseY < currentY + sectionHeight) {
                CapeEntry cape = findCapeInGrid(mouseX, mouseY, absoluteMouseY, currentY, localCapes);
                if (cape != null) return cape;
            }
            currentY += sectionHeight;
        }

        // --- Check "Default Capes" section ---
        if (!knownCapes.isEmpty()) {
            // The rendering logic in renderCapeGridOptimized adds a 20px gap before this section.
            // We must add it here as well to keep the click detection synchronized with the visuals.
            currentY += 20;

            currentY += HEADER_HEIGHT;
            int rows = (int) Math.ceil((double) knownCapes.size() / capesPerRow);
            int sectionHeight = rows * (capeDisplaySize + capePadding) + capePadding;

            if (absoluteMouseY >= currentY && absoluteMouseY < currentY + sectionHeight) {
                return findCapeInGrid(mouseX, mouseY, absoluteMouseY, currentY, knownCapes);
            }
        }

        return null;
    }

    private CapeEntry findCapeInGrid(int mouseX, int mouseY, int absoluteMouseY, int sectionTopY, List<CapeEntry> capes) {
        int relX = mouseX - this.gridX - capePadding;
        int relY = absoluteMouseY - sectionTopY - capePadding;

        int col = relX / (capeDisplaySize + capePadding);
        int row = relY / (capeDisplaySize + capePadding);

        if (col < 0 || col >= capesPerRow) return null;

        int index = row * capesPerRow + col;

        if (index >= 0 && index < capes.size()) {
            int capeX = this.gridX + capePadding + col * (capeDisplaySize + capePadding);
            // We need the on-screen Y to check bounds, not the absolute Y
            int capeY = sectionTopY + capePadding + row * (capeDisplaySize + capePadding) - (int) this.scrollOffset;
            if (isMouseOver(mouseX, mouseY, capeX, capeY, capeDisplaySize, capeDisplaySize)) {
                return capes.get(index);
            }
        }
        return null;
    }

    @Nullable
    private int[] getCapePosition(CapeEntry cape) {
        int currentY = gridY - (int) scrollOffset;

        // Check "My Capes" section
        if (!localCapes.isEmpty()) {
            currentY += HEADER_HEIGHT;
            int index = localCapes.indexOf(cape);
            if (index != -1) {
                int row = index / capesPerRow;
                int col = index % capesPerRow;
                int x = gridX + capePadding + col * (capeDisplaySize + capePadding);
                int y = currentY + capePadding + row * (capeDisplaySize + capePadding);
                return new int[]{x, y};
            }
            int rows = (int) Math.ceil((double) localCapes.size() / capesPerRow);
            currentY += rows * (capeDisplaySize + capePadding);
        }

        // Check "Default Capes" section
        if (!knownCapes.isEmpty()) {
            currentY += 20; // Extra spacing
            currentY += HEADER_HEIGHT;
            int index = knownCapes.indexOf(cape);
            if (index != -1) {
                int row = index / capesPerRow;
                int col = index % capesPerRow;
                int x = gridX + capePadding + col * (capeDisplaySize + capePadding);
                int y = currentY + capePadding + row * (capeDisplaySize + capePadding);
                return new int[]{x, y};
            }
        }
        return null;
    }

    private boolean isMouseOverGrid(int mouseX, int mouseY) {
        return mouseX >= this.gridX && mouseX < this.gridX + this.gridWidth &&
                mouseY >= this.gridY && mouseY < this.gridY + this.gridHeight;
    }

    private boolean isMouseOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        List<Path> validFiles = paths.stream()
                .filter(CapeImportProcessor::isSupported)
                .toList();

        if (validFiles.isEmpty()) {
            showImportMessage(Component.translatable("quickskin.cape.no_files").getString(),
                    GuiTextColor.opaqueRgb(0xFFAA00), 100);
            return;
        }

        showImportMessage(Component.translatable("quickskin.cape.processing_count", validFiles.size()).getString(),
                GuiTextColor.opaqueRgb(0x55AAFF), 60);
        startCapeImports(validFiles);
    }

    /**
     * Vanilla Elytra texture paths, newest layout first.
     *
     * <p>1.21.2 moved the Elytra texture into the equipment asset tree at
     * {@code textures/entity/equipment/wings/elytra.png}; earlier versions keep it at
     * {@code textures/entity/elytra.png}. Import compositing needs the real vanilla wings on every
     * supported version, so ask the resource manager for each known layout instead of binding one
     * era's path: a miss here silently saves the user's source atlas with a transparent Elytra
     * area.</p>
     */
    private static final String[] VANILLA_ELYTRA_TEXTURE_PATHS = {
            "textures/entity/equipment/wings/elytra.png",
            "textures/entity/elytra.png"
    };

    @Nullable
    private java.awt.image.BufferedImage getVanillaElytraImage() {
        for (String texturePath : VANILLA_ELYTRA_TEXTURE_PATHS) {
            java.awt.image.BufferedImage elytra = readVanillaElytraImage(texturePath);
            if (elytra != null) {
                return elytra;
            }
        }
        return null;
    }

    @Nullable
    private java.awt.image.BufferedImage readVanillaElytraImage(String texturePath) {
        try {
//? if <1.21 {
            ResourceLocation VANILLA_ELYTRA_TEXTURE = new ResourceLocation("minecraft", texturePath);
//?} else if <1.21.11 {
            ResourceLocation VANILLA_ELYTRA_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", texturePath);
//?} else {
            Identifier VANILLA_ELYTRA_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", texturePath);
//?}
            var resourceOptional = Minecraft.getInstance().getResourceManager().getResource(VANILLA_ELYTRA_TEXTURE);
            if (resourceOptional.isEmpty()) {
                return null;
            }
            try (InputStream stream = resourceOptional.get().open()) {
//? if <26.1.2 {
                return SafeImageReader.readPng(stream);
//?} else {
                byte[] encoded = com.quickskin.mod.common.util.BoundedFileReader.readBytes(
                        stream,
                        (int) com.quickskin.mod.common.util.SafeImageReader.MAX_ENCODED_BYTES);
                return com.quickskin.mod.common.util.SafeImageReader.readPng(encoded);
//?}
            }
        } catch (IOException e) {
//? if <26.1.2 {
//?} else {
            QuickSkin.LOGGER.debug("Unable to load the vanilla elytra texture", e);
//?}
            return null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        // Don't pause game when this screen is open
        return false;
//? if <1.21 {
//?} else if <1.21.6 {
    }

    @Override
    public void renderBlurredBackground(float partialTick) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
//?} else if <26.1.2 {
    }

    @Override
    protected void renderBlurredBackground(net.minecraft.client.gui.GuiGraphics guiGraphics) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
//?} else {
    }

    @Override
    protected void extractBlurredBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
//?}
    }

    @Override
    public void onClose() {
        this.capeImportGeneration++;
        CapeImportWorkflow workflow = this.capeImportWorkflow;
        this.capeImportWorkflow = null;
        if (workflow != null) {
            workflow.cancel();
        }
        BackgroundRenderer.cleanup();

        // Animations are kept alive -- each uses only one small GPU texture (~512KB).
        // They are cleaned up on disconnect (clearAnimations) or resource reload.
        // This avoids a micro-freeze from freeing large atlas NativeImages synchronously.

        if (minecraft != null) {
//? if <26.2 {
            minecraft.setScreen(parent);
//?} else {
            minecraft.gui.setScreen(parent);
//?}
        }
    }

    /**
     * Speed slider for controlling per-cape animation speed
     * Uses quadratic mapping for finer control at lower speeds
     */
    private class SpeedSlider extends AbstractSliderButton {
        // Speed range: 0.1 (10%) to 3.0 (300%)
        final double minSpeed = 0.1;
        final double maxSpeed = 3.0;

        public SpeedSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0);

            this.setTooltip(Tooltip.create(Component.translatable("quickskin.cape.animation_speed_tooltip")));
            loadSpeedForCurrentCape();
        }

        /**
         * Load the speed for the currently selected cape
         */
        public void loadSpeedForCurrentCape() {
            if (selectedCape == null || !selectedCape.isAnimated()) {
                this.value = 0.5; // Default to middle (100%)
                updateMessage();
                return;
            }

            // Get speed for this specific cape
            String capeId = selectedCape.getCapeId();
            double currentSpeed = ClientConfig.getInstance().getCapeAnimationSpeed(capeId);
            double clampedSpeed = Mth.clamp(currentSpeed, minSpeed, maxSpeed);

            // Reverse the quadratic mapping to find the slider position
            // speed = minSpeed + (v^2) * (maxSpeed - minSpeed)
            // v = sqrt((speed - minSpeed) / (maxSpeed - minSpeed))
            this.value = Math.sqrt((clampedSpeed - minSpeed) / (maxSpeed - minSpeed));

            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // Display percentage (10% to 300%)
            int percentage = (int) Math.round(10 + this.value * this.value * 290);
            setMessage(Component.translatable("quickskin.cape.animation_speed", percentage));
        }

        @Override
        protected void applyValue() {
            if (selectedCape == null || !selectedCape.isAnimated()) {
                return;
            }

            // Apply quadratic mapping for finer control at lower speeds
            double v = this.value;
            double speed = minSpeed + (v * v) * (maxSpeed - minSpeed);
            // Clamp to prevent invalid values
            speed = Math.max(0.01, Math.min(speed, 10.0));

            // Save to config for this specific cape
            String capeId = selectedCape.getCapeId();
            ClientConfig.getInstance().setCapeAnimationSpeed(capeId, (float) speed);

            // Update the active animation's speed in real-time
            String animationId = getAnimationIdForCape(capeId);
            if (animationId != null) {
                com.quickskin.mod.client.services.AnimatedTextureManager.getInstance()
                    .setAnimationSpeed(animationId, (float) speed);
            }
        }

        @Override
//? if <1.21.9 {
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
//?} else {
        public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
            super.onRelease(event);
//?}
            // Save config when slider is released
            ClientConfig.getInstance().save();

            if (selectedCape != null) {
                float speed = ClientConfig.getInstance().getCapeAnimationSpeed(selectedCape.getCapeId());
            }
        }

    }

    /**
     * Get the animation ID for a given cape ID.
     * Shared by SpeedSlider, lazy registration, and render logic.
     */
    private String getAnimationIdForCape(String capeId) {
        return com.quickskin.mod.client.services.CapeAnimationIds.deriveAnimationId(capeId);
    }

    /**
     * Resolves a local content ID to its catalogue primary, or {@code null} when the catalogue
     * does not hold it or deliberately refuses to resolve an ambiguous alias.
     */
    @Nullable
    private String catalogPrimaryOf(String contentId) {
        AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(contentId);
        return metadata == null ? null : metadata.hash();
    }
//? if <1.21.9 {
//?} else if <26.1.2 {

    /**
     * Draws an outline immediately using fill calls instead of submitOutline,
     * which defers rendering and can cause z-order issues with modals.
     */
    private static void drawOutline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }
//?} else {

    /**
     * Draws an outline immediately using fill calls instead of submitOutline,
     * which defers rendering and can cause z-order issues with modals.
     */
    private static void drawOutline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }
//?}
}
