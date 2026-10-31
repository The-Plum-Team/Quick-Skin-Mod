package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.platform.MinecraftCompat;
import com.quickskin.mod.platform.MinecraftGifDecoder;

//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
//?} else {
//?}
import com.quickskin.mod.platform.QuickSkinInfo;
//? if <26.1 {
import com.quickskin.mod.client.gui.util.BackgroundRenderer;
//?} else {
import com.quickskin.mod.client.gui.GuiCompat;
//?}
import com.quickskin.mod.client.gui.util.CapeEditorSources;
import com.quickskin.mod.client.gui.util.CapeImportProcessor;
import com.quickskin.mod.client.gui.util.CapeImportWorkflow;
//? if <1.21 {
import com.quickskin.mod.client.gui.GuiCompat;
//?} else if <26.1 {
//?} else {
import com.quickskin.mod.client.gui.util.BackgroundRenderer;
//?}
import com.quickskin.mod.client.gui.util.FileDialogHelper;
import com.quickskin.mod.client.gui.util.MenuGuiScale;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.rendering.LocalLookPreview;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.services.LocalAssetFolderWatch;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
//? if <1.21 {
//?} else if <1.21.11 {
//?} else if <26.1 {
import com.quickskin.mod.client.gui.GuiCompat;
//?} else {
//?}
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.KnownCapes;
import com.quickskin.mod.common.data.TextureQuality;
//? if <26.1 {
import com.quickskin.mod.common.util.SafeImageReader;
//?} else {
//?}
import com.quickskin.mod.common.util.UserFacingIOException;
import com.quickskin.mod.client.gui.widget.ErrorToast;
import com.quickskin.mod.client.gui.widget.ImportZone;
import com.quickskin.mod.client.gui.widget.CapeEditorStyle;
import com.quickskin.mod.client.gui.util.UiText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
//? if <1.21.6 {
//?} else if <26.1 {
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
//?} else {
//?}
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
public class PlayerCapeMenuScreen extends Screen implements com.quickskin.mod.client.gui.RestylableScreen,
        MenuGuiScale.Scaled {
    @Override
    public Screen recreateForStyleChange() {
        return new PlayerCapeMenuScreen(null);
    }


    // Background textures
//? if <1.21 {
    private static final ResourceLocation STAR_PATTERN_TEXTURE = new ResourceLocation(QuickSkinInfo.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = new ResourceLocation("textures/misc/vignette.png");
//?} else if <1.21.11 {
    private static final ResourceLocation STAR_PATTERN_TEXTURE = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");
//?} else if <1.21.6 {
    private static final ResourceLocation STAR_PATTERN_TEXTURE = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");
//?} else {
    private static final Identifier STAR_PATTERN_TEXTURE = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final Identifier VIGNETTE_LOCATION = Identifier.withDefaultNamespace("textures/misc/vignette.png");
//?}

    /** The edit and delete buttons on a cape of the player's own, square. */
    private static final int ACTION_BUTTON_SIZE = 11;
    /** How far the buttons on a card, and a favourite's star, sit from its top and right edges. */
    private static final int ACTION_BUTTON_INSET = 4;

    // Layout, in GUI units of the menu's own scale (see MenuGuiScale): the capes on the left, the
    // player wearing one of them on the right
    private static final int MARGIN = 8;
    private static final int CONTROL_HEIGHT = 20;
    private static final int BUTTON_SPACING = 6;
    /** Room right of the list for its scrollbar. */
    private static final int SCROLLBAR_ROOM = 12;
    /** A cape is this share of the canvas height tall, within the bounds below. */
    private static final float CAPE_SIZE_SHARE = 0.15f;
    private static final int MIN_CAPE_SIZE = 32;
    private static final int MAX_CAPE_SIZE = 64;
    /** Above and below each cape, the edge of the card it sits on. */
    private static final int CARD_PADDING = 6;
    /** Beside each cape: wider than above and below, so the badges fit. */
    private static final int CARD_PADDING_X = 13;
    /** Between two cards, across and down. */
    private static final int CARD_GAP = 6;
    /** The preview's share of the canvas width, within the bounds below. */
    private static final float PREVIEW_WIDTH_SHARE = 0.40f;
    private static final int MIN_PREVIEW_WIDTH = 150;
    private static final int MAX_PREVIEW_WIDTH = 320;
    private static final int SPEED_SLIDER_WIDTH = 160;
    /** Left of the speed slider's track, on its plate: the speed icon. */
    private static final int SPEED_ICON_ROOM = 18;
    /** Two chevrons: the speed slider's icon. */
    private static final String[] ICON_SPEED = {"#..#...", ".#..#..", "..#..#.", "...#..#", "..#..#.", ".#..#..", "#..#..."};
    /** The heading that stays at the top of the list while it scrolls. */
    private static final int STICKY_HEIGHT = 15;
    /** A section's heading and the space under it: the sticky heading's band and a gap, so that band never covers the first row. */
    private static final int SHELF_HEADER_HEIGHT = STICKY_HEIGHT + 6;
    private static final int SHELF_SECTION_GAP = 6;
    /** Height of the drawn player per unit of model scale: a player is 1.8 blocks, plus the hat and a margin. */
    private static final float MODEL_HEIGHT_PER_SCALE = 2.0f;
    /** The drawn player's width per unit of model scale, arms included. */
    private static final float MODEL_WIDTH_PER_SCALE = 1.2f;
    /** The largest model scale, so the model stays in proportion with the tiles and buttons. */
    private static final float MAX_MODEL_SCALE = 130.0f;

    private static final int GOLD = 0xFFFFD24A;
    private static final int SECTION_TEXT = 0xFFB0B0B0;
    private static final int MUTED_TEXT = 0xFF8A8A8A;
    private static final int RULE = 0xFF46474A;
    /** The worn cape's card: a blue edge round a dark blue face. */
    private static final int WORN_EDGE = 0xFF4080FF;
    private static final int WORN_FACE = 0xFF263652;
    private static final int CARD_EDGE = 0xFF55565A;
    private static final int CARD_EDGE_HOVER = 0xFF8D8E93;
    private static final int CARD_FACE = 0xFF2E2F32;
    private static final int CARD_FACE_HOVER = 0xFF404145;
    private static final int GIF_TAG = 0xFF138FC4;
    private static final int HD_TAG = 0xFF2E9A45;
    /** Quick Skin's own capes keep their blue-violet corner mark. */
    private static final int QUICK_SKIN_MARK = 0xFF5555FF;
    private static final int EDIT_TAG = 0xFF2F8F8A;
    private static final int DELETE_TAG = 0xFFC03030;
    private static final int FAVORITE_TAG = 0xFFB07C14;

    private static final String[] ICON_FOLDER = {"###....", "#######", "#.....#", "#.....#", "#.....#", "#.....#", "#######"};
    private static final String[] ICON_QUICK_SKIN = {".......", ".#####.", ".#####.", ".#####.", ".#####.", ".#####.", "......."};
    private static final String[] ICON_SPARKLE = {"...#...", "...#...", "..###..", "#######", "..###..", "...#...", "...#..."};
    private static final String[] ICON_GLOBE = {"..###..", ".#.#.#.", "#######", "#..#..#", "#######", ".#.#.#.", "..###.."};
    private static final String[] ICON_GIFT = {".#...#.", "..#.#..", "#######", "#..#..#", "#######", "#..#..#", "#######"};
    private static final String[] ICON_CREEPER = {"#######", "#..#..#", "#..#..#", "###.###", "##...##", "##.#.##", "#######"};
    private static final String[] ICON_MOJANG = {"#.....#", "##...##", "#.#.#.#", "#..#..#", "#.....#", "#.....#", "#.....#"};
    private static final String[] ICON_CROWN = {".......", "#..#..#", "##.#.##", "#######", "#######", ".......", "#######"};
    private static final String[] ICON_PEN = {".....##", "....###", "...###.", "..###..", ".###...", "###....", "##....."};
    private static final String[] ICON_CROSS = {"#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#"};

    // Adaptive dimensions
    /** The height of a cape on its card; it is ten sixteenths of that wide. */
    private int capeDisplaySize;
    private int capeWidth;
    private int cardWidth;
    private int cardHeight;
    private int scrollSpeed;
    private int stageX, stageWidth;

    @Nullable
    private final Screen parent;

    /** The player, wearing the cape under the mouse, or the worn one. */
    private PlayerWidget playerWidget;
    private SpeedSlider animationSpeedSlider;
    private CapeContextMenu contextMenu;

    // Picks up capes copied into the uploads folder from outside the game
    private final LocalAssetFolderWatch localAssetWatch = new LocalAssetFolderWatch();

    private double scrollOffset = 0;
    private double targetScrollOffset = 0;
    private int maxScroll = 0;
    private boolean isDraggingScrollbar = false;
    private double scrollbarClickOffset = 0.0;
    private int totalContentHeight = 0;
    private int gridX, gridY, gridWidth, gridHeight;
    private int bottomButtonY;

    // Player widget rotation state (preserved across resizes)
    private float savedBodyYaw = 20.0f;
    private float savedTargetRotation = 200.0f; // Default after initial toggleRotation()

    @Nullable
    private CapeEntry selectedCape;
    /** The cape under the mouse, which the model shows; null shows the worn one. */
    @Nullable
    private CapeEntry previewCape;

    // Sectioned cape lists
    private final List<CapeEntry> localCapes = new ArrayList<>(); // Contains "None" and local capes
    /** Every built-in cape, in the order the sections below list them. */
    private final List<CapeEntry> knownCapes = new ArrayList<>();
    /** The built-in capes by where they come from, each section under its own heading. */
    private final List<CapeSection> builtInSections = new ArrayList<>();
    /** Every section the list shows, in order: Favourites (when there are any), My Capes, the built-in ones. */
    private final List<ShelfSection> shelfSections = new ArrayList<>();
    private CapeGridLayout shelf = new CapeGridLayout(1, 1, 1, 0, 0, new int[0], new boolean[0], new int[0]);

    private record CapeSection(String titleKey, List<CapeEntry> capes) {
    }

    /** One section of the list under its heading. */
    private record ShelfSection(String titleKey, String[] icon, int iconColor, List<CapeEntry> capes,
                                boolean importSlot) {
    }

    /** What the menu has to say (imports, edits, deletions, failures), as toasts in the top-right corner. */
    private final List<ErrorToast> toasts = new ArrayList<>();
    /** The "processing" toast of the import or edit under way, taken down once it is done. */
    @Nullable
    private ErrorToast processingToast;
    private static final int TOAST_GAP = 4;
    private static final int TOAST_MARGIN = 8;
    private CapeImportWorkflow capeImportWorkflow;
    private int capeImportGeneration;
    private int capeEditGeneration;
    /** Set while an editor source is being loaded or an edit is being saved */
    private boolean capeEditBusy;

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

        // The menu draws at its own GUI scale, chosen from the window size, so it looks the same
        // whatever scale the player picked; a window resize runs init() again
        if (this.minecraft != null) {
            MenuGuiScale.apply(this.minecraft);
            this.width = this.minecraft.getWindow().getGuiScaledWidth();
            this.height = this.minecraft.getWindow().getGuiScaledHeight();
        }
        super.init();

        // The layout is sized from the canvas, which the menu's own scale keeps between about 300
        // and 450 units tall: the capes on the left with Import, Remove and Done under them, the
        // player on the right with the worn cape's animation speed under it
        int bottomY = this.height - MARGIN - CONTROL_HEIGHT;
        this.bottomButtonY = bottomY;

        this.stageWidth = Mth.clamp(Math.round(this.width * PREVIEW_WIDTH_SHARE), MIN_PREVIEW_WIDTH, MAX_PREVIEW_WIDTH);
        this.stageX = this.width - MARGIN - this.stageWidth;

        this.capeDisplaySize = Mth.clamp(Math.round(this.height * CAPE_SIZE_SHARE), MIN_CAPE_SIZE, MAX_CAPE_SIZE);
        this.capeWidth = Math.round(this.capeDisplaySize * 10 / 16f);
        // Wide enough for the import tile's words too, with ImportZone's margins, in every language
        int importText = Math.max(
                this.font.width(Component.translatable("quickskin.dropzone.capes.short_main")),
                this.font.width(Component.translatable("quickskin.dropzone.capes.short_click")));
        this.cardWidth = Math.max(this.capeWidth + 2 * CARD_PADDING_X, importText + 18);
        this.cardHeight = this.capeDisplaySize + 2 * CARD_PADDING;
        this.scrollSpeed = Math.max(10, (this.cardHeight + CARD_GAP) / 3);

        this.gridX = MARGIN + 6;
        this.gridY = MARGIN;
        this.gridWidth = Math.max(this.cardWidth, this.stageX - SCROLLBAR_ROOM - 8 - this.gridX);
        this.gridHeight = bottomY - 6 - this.gridY;

        // Load capes
        refreshCapeList();
        updateGridDimensions();

        // Import, Remove and Done under the list
        int buttonsRight = this.gridX + this.gridWidth + SCROLLBAR_ROOM;
        int buttonWidth = (buttonsRight - MARGIN - BUTTON_SPACING * 2) / 3;
        this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                MARGIN, bottomY, buttonWidth, CONTROL_HEIGHT,
                Component.translatable("quickskin.button.import_cape"),
                button -> importCape()
        ));
        this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                MARGIN + buttonWidth + BUTTON_SPACING, bottomY, buttonWidth, CONTROL_HEIGHT,
                Component.translatable("quickskin.button.remove_cape"),
                button -> removeCape()
        ));
        this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createPrimary(
                MARGIN + (buttonWidth + BUTTON_SPACING) * 2, bottomY,
                buttonsRight - MARGIN - (buttonWidth + BUTTON_SPACING) * 2, CONTROL_HEIGHT,
                Component.translatable("quickskin.button.done"),
                button -> this.onClose()
        ));

        // The worn cape's animation speed, centred under the player while an animated cape is
        // worn: a plate with the speed icon, the slider's track and the percentage. The widget is
        // the track alone, so vanilla's mapping of a click puts the knob under the mouse
        int plateWidth = Math.min(this.stageWidth, SPEED_SLIDER_WIDTH);
        int plateX = this.stageX + (this.stageWidth - plateWidth) / 2;
        int trackX = plateX + SPEED_ICON_ROOM;
        int trackWidth = plateWidth - SPEED_ICON_ROOM - speedPercentRoom();
        this.animationSpeedSlider = this.addRenderableWidget(new SpeedSlider(
                trackX, bottomY + 3, trackWidth, CONTROL_HEIGHT - 6));
        this.animationSpeedSlider.visible = false;
        this.animationSpeedSlider.active = false;

        // The player, as large as the space right of the list allows, centred in it
        int stageTop = MARGIN;
        int stageBottom = bottomY - 6;
        float modelScale = Math.min(MAX_MODEL_SCALE, Math.min(
                (stageBottom - stageTop) / MODEL_HEIGHT_PER_SCALE, this.stageWidth / MODEL_WIDTH_PER_SCALE));
        int modelHeight = Math.round(modelScale * MODEL_HEIGHT_PER_SCALE);
        int feetY = stageTop + (stageBottom - stageTop + modelHeight) / 2;
        int centerX = this.stageX + this.stageWidth / 2;

        LocalPlayer player = Minecraft.getInstance().player;
//? if <1.21.11 {
        ResourceLocation skinLocation = null;
//?} else {
        Identifier skinLocation = null;
//?}
        String modelType = "classic";

        ClientConfig config = ClientConfig.getInstance();
        // The saved skin, unless a CPM model is the latest look choice: then the player's own
        // skin (the live one in a world, the imported one on the title screen).
        String previewSkinHash = LocalLookPreview.skinHash(player != null);

        // First priority: Use that skin from the catalog (works on title screen when player is null)
        if (!previewSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(previewSkinHash);

            if (metadata != null) {
                // Load the saved skin texture
                skinLocation = assetManager.getTextureLocation(previewSkinHash, TextureQuality.FULL);

                // Get saved model type preference for this skin
                modelType = assetManager.getSkinModelPreference(previewSkinHash);

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
            if (!previewSkinHash.isEmpty()) {
                LocalAssetManager assetManager = LocalAssetManager.getInstance();
                modelType = assetManager.getSkinModelPreference(previewSkinHash);
                AssetMetadata metadata = assetManager.getMetadata(previewSkinHash);

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
            } else {
                // No custom skin active, use the player's own model
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
                this.stageX, stageTop, this.stageWidth, stageBottom - stageTop,
                skinLocation, null, null, modelType));
        this.playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.CAPE_MENU);
        this.playerWidget.setCustomReferencePoint(centerX, feetY);
        this.playerWidget.setScaleCap(modelScale);

        // The right-click menu, drawn over everything else
        this.contextMenu = new CapeContextMenu(new CapeContextMenu.Actions() {
            @Override
            public void toggleFavorite(CapeEntry cape) {
                PlayerCapeMenuScreen.this.toggleFavorite(cape);
            }

            @Override
            public void edit(CapeEntry cape) {
                startCapeEdit(cape);
            }

            @Override
            public void delete(CapeEntry cape) {
                showDeleteConfirmation(cape);
            }
        });

        // Initialize selected cape based on config/currently equipped cape (AFTER widget is created)
        initializeSelectedCape();

        // Update speed slider visibility based on selected cape
        updateSpeedSliderVisibility();

        // Restore saved rotation state
        this.playerWidget.setRotationState(this.savedBodyYaw, this.savedTargetRotation);
    }

    /**
     * Refresh the cape list UI
     * Public so it can be called when textures are reloaded
     */
    public void refreshCapeList() {
        this.localCapes.clear();
        this.knownCapes.clear();
        this.builtInSections.clear();

        // --- Section 1: My Capes ---
        // Add "None" option first
        this.localCapes.add(CapeEntry.fromKnown(KnownCapes.NONE));

        // Then add local capes, each with the editor source retained for it (if any)
        List<AssetMetadata> localCapeAssets = LocalAssetManager.getInstance()
                .getAssetsByType("cape");
        Path cacheDirectory = LocalAssetManager.getInstance().getCacheDirectory();
        for (AssetMetadata localCape : localCapeAssets) {
            this.localCapes.add(CapeEntry.fromLocal(
                    localCape, CapeEditorSources.find(cacheDirectory, localCape.hash())));
        }

        // --- Then the built-in capes, a section for each place they come from ---
        // None is in My Capes. Only listed if the player hasn't hidden built-in capes.
        ClientConfig config = ClientConfig.getInstance();
        if (!config.hideBuiltInCapes) {
            for (KnownCapes.Category category : KnownCapes.Category.values()) {
                List<CapeEntry> capes = new ArrayList<>();
                for (KnownCapes knownCape : KnownCapes.values()) {
                    if (knownCape.category() == category) {
                        capes.add(CapeEntry.fromKnown(knownCape));
                    }
                }
                if (!capes.isEmpty()) {
                    this.builtInSections.add(new CapeSection(category.titleKey(), capes));
                    this.knownCapes.addAll(capes);
                }
            }
        }

        buildShelf();
        // The entries are new: keep showing the same cape on the model, if it is still listed
        if (this.previewCape != null) {
            this.previewCape = findCape(this.previewCape.getCapeId());
            updateStage();
        }
        // Animations are registered lazily when capes become visible in the grid
    }

    /** Lays out the list's sections: Favourites first when there are any, then My Capes and the built-in ones. */
    private void buildShelf() {
        this.shelfSections.clear();
        List<CapeEntry> favorites = favoriteCapes();
        if (!favorites.isEmpty()) {
            this.shelfSections.add(new ShelfSection("quickskin.cape.section.favorites",
                    CapeContextMenu.ICON_STAR, GOLD, favorites, false));
        }
        this.shelfSections.add(new ShelfSection("quickskin.cape.section.my_capes",
                ICON_FOLDER, 0xFFE8E8E8, this.localCapes, true));
        for (CapeSection section : this.builtInSections) {
            this.shelfSections.add(new ShelfSection(section.titleKey(),
                    sectionIcon(section.titleKey()), sectionColor(section.titleKey()), section.capes(), false));
        }
        int[] counts = new int[this.shelfSections.size()];
        boolean[] importSlots = new boolean[counts.length];
        int[] spareRows = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = this.shelfSections.get(i).capes().size();
            importSlots[i] = this.shelfSections.get(i).importSlot();
            // A row of air under My Capes, so the menu opens on the player's own capes with the
            // built-in ones further down rather than on a wall of capes
            spareRows[i] = importSlots[i] ? 1 : 0;
        }
        // Each cell is a card and the gap after it; the list is one gap wider, so the last card
        // of a row ends at its right edge
        this.shelf = new CapeGridLayout(Math.max(1, this.gridWidth + CARD_GAP), Math.max(1, this.cardWidth + CARD_GAP),
                Math.max(1, this.cardHeight + CARD_GAP), SHELF_HEADER_HEIGHT, SHELF_SECTION_GAP, counts, importSlots, spareRows);
    }

    /** The favourite capes still listed, in the order they were marked. */
    private List<CapeEntry> favoriteCapes() {
        List<CapeEntry> favorites = new ArrayList<>();
        ClientConfig config = ClientConfig.getInstance();
        if (config.favoriteCapes == null) {
            return favorites;
        }
        for (String id : config.favoriteCapes) {
            CapeEntry cape = findCape(id);
            if (cape != null && !cape.isNone()) {
                favorites.add(cape);
            }
        }
        return favorites;
    }

    @Nullable
    private CapeEntry findCape(String capeId) {
        for (CapeEntry cape : this.localCapes) {
            if (cape.getCapeId().equals(capeId)) {
                return cape;
            }
        }
        for (CapeEntry cape : this.knownCapes) {
            if (cape.getCapeId().equals(capeId)) {
                return cape;
            }
        }
        return null;
    }

    private static String[] sectionIcon(String titleKey) {
        return switch (titleKey) {
            case "quickskin.cape.section.quick_skin" -> ICON_QUICK_SKIN;
            case "quickskin.cape.section.new" -> ICON_SPARKLE;
            case "quickskin.cape.section.everyone" -> ICON_GLOBE;
            case "quickskin.cape.section.events" -> ICON_GIFT;
            case "quickskin.cape.section.in_person" -> ICON_CREEPER;
            case "quickskin.cape.section.mojang" -> ICON_MOJANG;
            default -> ICON_CROWN;
        };
    }

    private static int sectionColor(String titleKey) {
        return switch (titleKey) {
            case "quickskin.cape.section.quick_skin" -> 0xFF6A6AFF;
            case "quickskin.cape.section.new" -> 0xFFFFE05A;
            case "quickskin.cape.section.everyone" -> 0xFF6FD0FF;
            case "quickskin.cape.section.events" -> 0xFFFF8FC8;
            case "quickskin.cape.section.in_person" -> 0xFF6FDB5A;
            case "quickskin.cape.section.mojang" -> 0xFFFF6A5A;
            default -> 0xFFE8B84A;
        };
    }

    /** Marks or unmarks a favourite, and lays the list out again with the Favourites section changed. */
    private void toggleFavorite(CapeEntry cape) {
        ClientConfig config = ClientConfig.getInstance();
        config.setFavoriteCape(cape.getCapeId(), !config.isFavoriteCape(cape.getCapeId()));
        config.save();
        double before = this.shelf.contentHeight();
        buildShelf();
        updateGridDimensions();
        // Keep the capes on screen where they were: the Favourites section above them grew or shrank
        this.targetScrollOffset = Mth.clamp(this.targetScrollOffset + this.shelf.contentHeight() - before, 0, this.maxScroll);
        this.scrollOffset = this.targetScrollOffset;
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
        this.selectedCape = null;
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

        if (activeCapeId != null && !activeCapeId.isEmpty()) {
            // Every local cape is a catalogue asset addressed by its SHA-256 primary, while the
            // saved reference may still be a SHA-1 alias whose migration has not run yet. Compare
            // the two in the catalogue's own vocabulary so the active cape is marked, previewed and
            // retimed instead of silently reading as "no cape selected".
            activeCapeId = com.quickskin.mod.client.services.CapeAnimationIds.canonicalCapeId(
                    activeCapeId, this::catalogPrimaryOf);
            this.selectedCape = findCape(activeCapeId);
        }
        updateStage();
    }

    /** Dresses the model in the cape under the mouse, or else the worn one. */
    private void updateStage() {
        if (this.playerWidget != null) {
            dress(this.playerWidget, this.previewCape != null ? this.previewCape : this.selectedCape);
        }
    }

    private static void dress(PlayerWidget widget, @Nullable CapeEntry cape) {
        if (cape == null || cape.isNone()) {
            widget.setCape(null, null);
            return;
        }
//? if <1.21.11 {
        ResourceLocation location = com.quickskin.mod.client.services.CapeService.getInstance()
//?} else {
        Identifier location = com.quickskin.mod.client.services.CapeService.getInstance()
//?}
                .getCapeLocation(null, cape.getCapeId());
        if (location == null) {
            location = cape.getTextureLocation();
        }
        widget.setCape(location, cape.getCapeId());
    }

    /** Shows the cape under the mouse on the model, and the worn one again once the mouse leaves it. */
    private void preview(@Nullable CapeEntry cape) {
        if (cape == this.previewCape) {
            return;
        }
        this.previewCape = cape;
        updateStage();
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
        this.totalContentHeight = this.shelf.contentHeight() + 8;
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

        showProcessing(Component.translatable("quickskin.cape.processing"));
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
        this.capeEditGeneration++;
        LocalAssetManager assets = LocalAssetManager.getInstance();
        CapeImportWorkflow workflow = new CapeImportWorkflow(
                sources,
                assets.getCapesDirectory(),
                assets.getCacheDirectory(),
                getVanillaElytraImage(),
                MinecraftGifDecoder.INSTANCE,
                client::execute,
//? if <26.1 {
                (prepared, apply, cancel) -> client.setScreen(new CapeAdjustScreen(
                        this, prepared.atlas(), prepared.frameCount(),
                        prepared.animationMetadata(), apply, cancel)),
//?} else if <26.2 {
                (prepared, apply, cancel) -> {
                    client.setScreen(new CapeAdjustScreen(
                            this, prepared.atlas(), prepared.frameCount(),
                        prepared.animationMetadata(), apply, cancel));
                },
//?} else {
                (prepared, apply, cancel) -> {
                    client.gui.setScreen(new CapeAdjustScreen(
                            this, prepared.atlas(), prepared.frameCount(),
                        prepared.animationMetadata(), apply, cancel));
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
        dismissProcessing();

        if (summary.succeeded() > 0) {
            LocalAssetManager.getInstance().reload();
            refreshCapeList();
            updateGridDimensions();

            MutableComponent message = summary.succeeded() == 1
                    ? Component.translatable("quickskin.cape.imported")
                    : Component.translatable("quickskin.cape.imported_many", summary.succeeded());
            int notImported = summary.failed() + summary.cancelled();
            if (notImported > 0) {
                message.append(Component.translatable("quickskin.cape.not_imported", notImported));
            }
            showToast(message, notImported == 0 ? ErrorToast.Kind.SUCCESS : ErrorToast.Kind.INFO);
            if (summary.firstError() != null) {
                showImportError(summary.firstError(), summary.firstErrorMessage(),
                        "quickskin.cape.error.import_failed");
            }
            return;
        }

        if (summary.firstError() != null) {
            showImportError(summary.firstError(), summary.firstErrorMessage(),
                    "quickskin.cape.error.import_failed");
        }
        // Otherwise the player cancelled every editor: nothing went wrong, so nothing to say
    }

    /**
     * Tells the player why something failed, in a toast: the failure's own message when it has one
     * for the player, otherwise {@code fallbackKey}'s; the detail goes to the log either way.
     */
    private void showImportError(String detail, @Nullable UserFacingIOException.UserMessage message,
                                 String fallbackKey) {
        QuickSkinInfo.LOGGER.warn("Cape import failed: {}", detail);
        Component text = message != null
                ? Component.translatable(message.translationKey(), message.args().toArray())
                : Component.translatable(fallbackKey);
        showToast(text, ErrorToast.Kind.ERROR);
    }

    private void showImportError(Throwable error, String fallbackKey) {
        showImportError(rootMessage(error), UserFacingIOException.find(error), fallbackKey);
    }

    /**
     * Reopen the cape editor with the original image retained when this cape was imported
     */
    private void startCapeEdit(CapeEntry capeEntry) {
        Minecraft client = this.minecraft;
        Path source = capeEntry.getEditorSource();
        if (client == null || source == null || this.capeImportWorkflow != null || this.capeEditBusy) {
            return;
        }

        int generation = ++this.capeEditGeneration;
        this.capeEditBusy = true;
        showProcessing(Component.translatable("quickskin.cape.processing"));
        ClientIoExecutor.supplyAsync(() -> {
            try {
                return CapeImportProcessor.prepare(source, MinecraftGifDecoder.INSTANCE);
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }).whenComplete((prepared, error) -> client.execute(() -> {
            this.capeEditBusy = false;
            if (generation != this.capeEditGeneration
                    || com.quickskin.mod.client.gui.GuiCompat.currentScreen() != this) {
                return;
            }
            if (error != null) {
                // The source vanished or no longer decodes; never fall back to another image
                dismissProcessing();
                showImportError(error, "quickskin.cape.error.edit_failed");
                refreshCapeList();
                return;
            }
            // Loaded: a cancelled editor must not come back to a stale "processing" toast
            dismissProcessing();
            com.quickskin.mod.client.gui.GuiCompat.openScreen(new CapeAdjustScreen(
                    this, prepared.atlas(), prepared.frameCount(), prepared.animationMetadata(),
                    (adjusted, timing) -> saveCapeEdit(capeEntry, prepared, adjusted, timing), () -> { }));
        }));
    }

    private void saveCapeEdit(CapeEntry capeEntry, CapeImportProcessor.PreparedCape prepared,
                              java.awt.image.BufferedImage adjusted,
                              @Nullable com.quickskin.mod.common.data.AnimationMetadata timing) {
        Minecraft client = this.minecraft;
        AssetMetadata localCape = capeEntry.getLocalCape();
        Path capePath = capeEntry.getPath();
        if (client == null || localCape == null || capePath == null) {
            return;
        }

        // Captured here: the entry and the active cape may both change while the file is written
        String previousId = capeEntry.getCapeId();
        boolean wasSelected = isSelected(capeEntry);
        String activeAtApply = ClientConfig.getInstance().activeCapeHash;
        Path cacheDirectory = LocalAssetManager.getInstance().getCacheDirectory();
        java.awt.image.BufferedImage vanillaElytra = getVanillaElytraImage();

        this.capeEditBusy = true;
        showProcessing(Component.translatable("quickskin.cape.processing"));
        ClientIoExecutor.supplyAsync(() -> {
            try {
                CapeImportProcessor.replaceAdjusted(
                        prepared, adjusted, timing, capePath, cacheDirectory, vanillaElytra);
                return (Throwable) null;
            } catch (IOException | RuntimeException error) {
                return error;
            }
        }).whenComplete((error, throwable) -> client.execute(() -> completeCapeEdit(
                capePath, localCape.hash(), previousId, wasSelected, activeAtApply,
                throwable != null ? throwable : error)));
    }

    /**
     * Runs even when the menu was closed meanwhile: the file has already been replaced, so the
     * active cape and its animation speed must follow it to the new content ID.
     */
    private void completeCapeEdit(Path capePath, String previousHash, String previousId,
                                  boolean wasSelected, String activeAtApply, @Nullable Throwable error) {
        this.capeEditBusy = false;
        dismissProcessing();
        if (error != null) {
            showImportError(error, "quickskin.cape.error.edit_failed");
            return;
        }

        LocalAssetManager.getInstance().reload();
        refreshCapeList();
        updateGridDimensions();

        CapeEntry updated = null;
        for (CapeEntry candidate : this.localCapes) {
            if (capePath.equals(candidate.getPath())) {
                updated = candidate;
                break;
            }
        }

        ClientConfig config = ClientConfig.getInstance();
        // Decided now, not at Apply: the old tile could still be selected while the file was written
        boolean stillActive = previousId.equals(config.activeCapeHash)
                || (wasSelected && activeAtApply.equals(config.activeCapeHash));
        if (updated == null) {
            if (stillActive) {
                removeCape();
            }
        } else if (!updated.getCapeId().equals(previousId)) {
            // The new bytes have a new content ID: carry the animation speed and selection over
            Float speed = config.capeAnimationSpeeds != null
                    ? config.capeAnimationSpeeds.remove(previousId) : null;
            if (speed != null) {
                config.setCapeAnimationSpeed(updated.getCapeId(), speed);
            }
            if (config.isFavoriteCape(previousId)) {
                config.renameFavoriteCape(previousId, updated.getCapeId());
                config.save();
                buildShelf();
                updateGridDimensions();
            }
            if (stillActive) {
                this.selectedCape = updated;
                applyCape(updated);
            } else if (speed != null) {
                config.save();
            }
        }
        releaseEditorSource(previousHash);

        showToast(Component.translatable("quickskin.cape.edited"), ErrorToast.Kind.SUCCESS);
    }

    /**
     * Drop a retained editor source once no catalogued cape carries its content ID any more.
     * Byte-identical capes share one ID and one source, so the catalogue decides, not the caller.
     */
    private static void releaseEditorSource(String capeHash) {
        LocalAssetManager assets = LocalAssetManager.getInstance();
        if (assets.getMetadata(capeHash) != null) {
            return;
        }
        try {
            CapeEditorSources.delete(assets.getCacheDirectory(), capeHash);
        } catch (IOException ignored) {
            // An orphaned source is never listed, rendered, or offered for another cape
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private void removeCape() {
        // Always update preview widget (works both in-game and on title screen)
        this.selectedCape = null;
        updateStage();

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

        // An edit in flight still reads this cape's source or is about to replace its file
        if (minecraft == null || this.capeEditBusy) {
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
            releaseEditorSource(capeEntry.getLocalCape().hash());
            ClientConfig config = ClientConfig.getInstance();
            if (config.isFavoriteCape(capeEntry.getCapeId())) {
                config.setFavoriteCape(capeEntry.getCapeId(), false);
                config.save();
            }
            refreshCapeList();
            updateGridDimensions();

            // If the deleted cape was the one being previewed, call removeCape()
            // to update the preview widget and clear the active cape from the config.
            if (wasSelected) {
                removeCape();
            }

            showToast(Component.translatable("quickskin.cape.deleted"), ErrorToast.Kind.SUCCESS);
        } catch (Exception e) {
            showImportError(e, "quickskin.cape.error.delete_failed");
        }
    }

    private void showToast(Component message, ErrorToast.Kind kind) {
        toasts.add(new ErrorToast(message, kind));
    }

    /** Shows what is under way until {@link #dismissProcessing()}, replacing an earlier one. */
    private void showProcessing(Component message) {
        dismissProcessing();
        processingToast = new ErrorToast(message, ErrorToast.Kind.INFO);
        toasts.add(processingToast);
    }

    private void dismissProcessing() {
        if (processingToast != null) {
            toasts.remove(processingToast);
            processingToast = null;
        }
    }

    @Override
    public void tick() {
        super.tick();
        // Another mod can put the player's scale back on the window after init(), as some do
        // whenever a screen opens; take the menu's scale back and lay the menu out again
        if (this.minecraft != null && MenuGuiScale.apply(this.minecraft)) {
            rebuildWidgets();
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
        // Render the animated star background
        this.renderBackgroundEffects(graphics, partialTick);

//? if <1.21 {
//?} else if <1.21.6 {
        // Flush and ensure clean render state
        graphics.flush();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
//? if <1.21.5 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
//?}
//?} else {
//?}

//? if <1.21 {
//?} else if <1.21.6 {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Push pose and translate forward in Z to render on top
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 100);
//?} else if <26.1 {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Push pose (1.21.11: Matrix3x2fStack uses pushMatrix/popMatrix, no Z translate in 2D)
        graphics.pose().pushMatrix();
//?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Push pose (1.21.11: Matrix3x2fStack uses pushMatrix/popMatrix, no Z translate in 2D)
        graphics.pose().pushMatrix();
//?}

        // The list's panel, then the capes clipped to it
        graphics.fill(this.gridX - 6, this.gridY - 2, this.gridX + this.gridWidth + 6,
                this.gridY + this.gridHeight + 2, 0x9C0C0C0E);
        graphics.fill(this.gridX - 6, this.gridY - 2, this.gridX + this.gridWidth + 6, this.gridY - 1, 0x40FFFFFF);
        boolean menuOpen = this.contextMenu.isOpen();
        int hoverX = menuOpen ? -1 : mouseX;
        int hoverY = menuOpen ? -1 : mouseY;
        graphics.enableScissor(this.gridX - 6, this.gridY + STICKY_HEIGHT, this.gridX + this.gridWidth + 6,
                this.gridY + this.gridHeight);
        this.scrollOffset += (this.targetScrollOffset - this.scrollOffset) * 0.5;
        renderCapeGrid(graphics, hoverX, hoverY);
        graphics.disableScissor();
        renderStickyHeading(graphics);

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

        renderToasts(graphics);

        if (this.contextMenu.isOpen()) {
            this.contextMenu.drawOnTop(graphics, mouseX, mouseY);
            return;
        }

        // Tooltip logic
        if (isMouseOverGrid(mouseX, mouseY)) {
            CapeGridLayout.Hit hoveredHit = shelfHit(mouseX, mouseY);
            CapeEntry hoveredCape = hoveredHit == null || hoveredHit.importSlot() ? null
                    : this.shelfSections.get(hoveredHit.section()).capes().get(hoveredHit.index());
            if (hoveredCape != null) {
                Component actionTooltip = null;
                int[] card = cardOrigin(hoveredHit);
                int[] favorite = favoriteButtonBounds(hoveredCape, card[0], card[1]);
                int[] delete = deleteButtonBounds(hoveredCape, card[0], card[1]);
                int[] edit = editButtonBounds(hoveredCape, card[0], card[1]);
                if (favorite != null) {
                    if (isMouseOver(mouseX, mouseY, favorite[0], favorite[1], favorite[2], favorite[3])) {
                        actionTooltip = Component.translatable(ClientConfig.getInstance().isFavoriteCape(hoveredCape.getCapeId())
                                ? "quickskin.context.unfavorite" : "quickskin.context.favorite");
                    } else if (delete != null && isMouseOver(mouseX, mouseY, delete[0], delete[1], delete[2], delete[3])) {
                        actionTooltip = Component.translatable("quickskin.tooltip.delete_cape");
                    } else if (edit != null && isMouseOver(mouseX, mouseY, edit[0], edit[1], edit[2], edit[3])) {
                        actionTooltip = Component.translatable("quickskin.tooltip.edit_cape");
                    }
                    if (actionTooltip != null) {
//? if <1.21 {
                        GuiCompat.tooltip(
                                graphics, this.font, actionTooltip,
                                mouseX, mouseY);
//?} else if <1.21.6 {
                        graphics.renderTooltip(this.font, actionTooltip, mouseX, mouseY);
//?} else if <26.1 {
                        // 1.21.11: renderTooltip takes List<ClientTooltipComponent>
                        List<ClientTooltipComponent> tooltipComponents = List.of(
                            ClientTooltipComponent.create(actionTooltip.getVisualOrderText())
                        );
                        graphics.renderTooltip(this.font, tooltipComponents, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
//?} else {
                        GuiCompat.tooltip(
                                graphics, this.font,
                                actionTooltip, mouseX, mouseY
                        );
//?}
                    }
                }
                if (actionTooltip == null) {
//? if <1.21 {
                    GuiCompat.tooltip(graphics, this.font, getCapeTooltip(hoveredCape), mouseX, mouseY);
//?} else if <1.21.6 {
                    graphics.renderTooltip(this.font, getCapeTooltip(hoveredCape), Optional.empty(), mouseX, mouseY);
//?} else if <26.1 {
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

    /** The toasts in the top-right corner, the newest at the top. */
//? if <26.1 {
    private void renderToasts(GuiGraphics graphics) {
//?} else {
    private void renderToasts(GuiGraphicsExtractor graphics) {
//?}
        toasts.removeIf(ErrorToast::isExpired);
        if (toasts.isEmpty()) {
            return;
        }
//? if <1.21.6 {
        // Up to 1.21.5 the GUI is depth tested: the model reaches forward to z 250 at most
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 350.0F);
//?} else {
        graphics.nextStratum();
//?}
        // The top-right corner, clear of the buttons along the bottom; the newest on top
        int x = width - TOAST_MARGIN - ErrorToast.WIDTH;
        int top = TOAST_MARGIN;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            ErrorToast toast = toasts.get(i);
            toast.render(graphics, x, top);
            top += toast.height() + TOAST_GAP;
        }
//? if <1.21.6 {
        graphics.pose().popPose();
//?}
    }

//? if <26.1 {
    private void renderCapeGrid(GuiGraphics graphics, int mouseX, int mouseY) {
//?} else {
    private void renderCapeGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
//?}
        int top = this.gridY - (int) this.scrollOffset;
        boolean inView = mouseY >= this.gridY + STICKY_HEIGHT && mouseY < this.gridY + this.gridHeight;
        CapeEntry hovered = null;
        for (int i = 0; i < this.shelfSections.size(); i++) {
            ShelfSection section = this.shelfSections.get(i);
            CapeGridLayout.Section place = this.shelf.sections().get(i);
            int headingY = top + place.top() + 3;
            if (headingY > this.gridY - 12 && headingY < this.gridY + this.gridHeight) {
                renderHeading(graphics, section, headingY);
            }
            for (int slot = 0; slot < place.slots(); slot++) {
                int x = this.gridX + this.shelf.slotX(slot);
                int y = top + this.shelf.cellY(i, slot);
                if (y + this.cardHeight < this.gridY || y > this.gridY + this.gridHeight) {
                    continue;
                }
                if (slot == section.capes().size()) {
                    // The import tile stretches to the end of its row, up to the list's right edge
                    int w = this.shelf.importWidth(slot) - CARD_GAP;
                    renderImportTile(graphics, x, y, w,
                            inView && isMouseOver(mouseX, mouseY, x, y, w, this.cardHeight));
                    continue;
                }
                boolean over = inView && isMouseOver(mouseX, mouseY, x, y, this.cardWidth, this.cardHeight);
                CapeEntry cape = section.capes().get(slot);
                if (over) {
                    hovered = cape;
                }
                renderCapeTile(graphics, cape, x, y, over, mouseX, mouseY);
            }
        }
        preview(hovered);
    }

    /** A section's heading: its icon, its name, a rule and how many capes it holds. */
//? if <26.1 {
    private void renderHeading(GuiGraphics graphics, ShelfSection section, int y) {
//?} else {
    private void renderHeading(GuiGraphicsExtractor graphics, ShelfSection section, int y) {
//?}
        CapeEditorStyle.icon(graphics, section.icon(), this.gridX + 2, y, section.iconColor());
        Component title = Component.translatable(section.titleKey());
        int textX = this.gridX + 12;
        UiText.draw(graphics, this.font, title, textX, y, SECTION_TEXT);
        String count = String.valueOf(capeCount(section));
        int right = this.gridX + this.gridWidth - 2;
        UiText.draw(graphics, this.font, count, right - this.font.width(count), y, MUTED_TEXT);
        int ruleStart = textX + this.font.width(title) + 6;
        int ruleEnd = right - this.font.width(count) - 6;
        if (ruleEnd - ruleStart > 8) {
            graphics.fill(ruleStart, y + 4, ruleEnd, y + 5, RULE);
        }
    }

    /** The heading of the section at the top of the list, which stays there while the list scrolls. */
//? if <26.1 {
    private void renderStickyHeading(GuiGraphics graphics) {
//?} else {
    private void renderStickyHeading(GuiGraphicsExtractor graphics) {
//?}
        int active = this.shelf.sectionAt(this.scrollOffset, this.gridHeight);
        if (active < 0) {
            return;
        }
        int x0 = this.gridX - 6;
        int x1 = this.gridX + this.gridWidth + 6;
        graphics.fill(x0, this.gridY - 2, x1, this.gridY + STICKY_HEIGHT, 0xF0141518);
        graphics.fill(x0, this.gridY + STICKY_HEIGHT, x1, this.gridY + STICKY_HEIGHT + 1, 0xFF2C3440);
        renderHeading(graphics, this.shelfSections.get(active), this.gridY + 4);
    }

    /**
     * One cape on its card: the worn one on a blue card, its kind on badges (GIF and HD in the top
     * corner, Quick Skin's own capes with a mark in the bottom one), a favourite with a gold star,
     * and, while the mouse is over it, the favourite button and, on a cape of the player's own, the
     * delete and edit buttons under it.
     */
//? if <26.1 {
    private void renderCapeTile(GuiGraphics graphics, CapeEntry cape, int x, int y, boolean hovered, int mouseX, int mouseY) {
//?} else {
    private void renderCapeTile(GuiGraphicsExtractor graphics, CapeEntry cape, int x, int y, boolean hovered, int mouseX, int mouseY) {
//?}
        boolean worn = isSelected(cape);
        CapeTileArt.card(graphics, x, y, this.cardWidth, this.cardHeight,
                worn ? WORN_EDGE : hovered ? CARD_EDGE_HOVER : CARD_EDGE,
                worn ? WORN_FACE : hovered ? CARD_FACE_HOVER : CARD_FACE);
        if (worn) {
            // A second line of the edge, so the worn card stands out from a hovered one
            CapeTileArt.card(graphics, x + 1, y + 1, this.cardWidth - 2, this.cardHeight - 2, WORN_EDGE, WORN_FACE);
        }
        int cx = x + this.cardWidth / 2;
        int capeLeft = x + CARD_PADDING_X;
        int capeTop = y + CARD_PADDING;
        int capeRight = capeLeft + this.capeWidth;
        if (cape.isNone()) {
            CapeTileArt.dashedOutline(graphics, capeLeft, capeTop, this.capeWidth, this.capeDisplaySize, 0x60FFFFFF);
            UiText.drawCentered(graphics, this.font, Component.translatable("quickskin.cape.option.none"), cx,
                    capeTop + this.capeDisplaySize / 2 - 4, 0xFFE6E6E6);
        } else {
//? if <1.21.11 {
            ResourceLocation texture = cape.getTextureLocation();
//?} else {
            Identifier texture = cape.getTextureLocation();
//?}
            if (texture != null && cape.isAnimated()) {
                ensureAnimationRegistered(cape);
                texture = com.quickskin.mod.client.services.AnimatedTextureManager.getInstance()
                        .getAnimationFrame(texture)
                        .orElse(texture);
            }
            if (texture != null) {
                renderCapeTexture(graphics, texture, cape, cx - this.capeDisplaySize / 2, capeTop);
            } else {
                renderLoadingTexture(graphics, capeLeft, capeTop);
            }
            int badgeY = y + 2;
            if (cape.isAnimated()) {
                CapeTileArt.badge(graphics, this.font, x + 2, badgeY,
                        Component.translatable("quickskin.cape.animated_badge"), GIF_TAG);
                badgeY += this.font.lineHeight + 3;
            }
            if (isHd(cape)) {
                CapeTileArt.badge(graphics, this.font, x + 2, badgeY, Component.literal("HD"), HD_TAG);
            }
            if (cape.isCustom()) {
                int mark = 5;
                graphics.fill(capeRight - mark, capeTop + this.capeDisplaySize - mark,
                        capeRight + 1, capeTop + this.capeDisplaySize + 1, CapeTileArt.OUTLINE);
                graphics.fill(capeRight - mark + 1, capeTop + this.capeDisplaySize - mark + 1,
                        capeRight, capeTop + this.capeDisplaySize, QUICK_SKIN_MARK);
            }
        }

        int[] favorite = favoriteButtonBounds(cape, x, y);
        if (favorite == null) {
            return;
        }
        boolean isFavorite = ClientConfig.getInstance().isFavoriteCape(cape.getCapeId());
        if (hovered) {
            renderActionButton(graphics, favorite, CapeContextMenu.ICON_STAR,
                    isFavorite ? FAVORITE_TAG : CARD_FACE, isFavorite ? 0xFFFFFFFF : GOLD, mouseX, mouseY);
            int[] delete = deleteButtonBounds(cape, x, y);
            if (delete != null) {
                renderActionButton(graphics, delete, ICON_CROSS, DELETE_TAG, 0xFFFFFFFF, mouseX, mouseY);
            }
            int[] edit = editButtonBounds(cape, x, y);
            if (edit != null) {
                renderActionButton(graphics, edit, ICON_PEN, EDIT_TAG, 0xFFFFFFFF, mouseX, mouseY);
            }
        } else if (isFavorite) {
            // On the button's gold plate, like the GIF and HD badges, so it reads over any cape
            renderActionButton(graphics, favorite, CapeContextMenu.ICON_STAR, FAVORITE_TAG, 0xFFFFFFFF, -1, -1);
        }
    }

//? if <26.1 {
    private void renderActionButton(GuiGraphics graphics, int[] bounds, String[] icon, int face, int iconColor, int mouseX, int mouseY) {
//?} else {
    private void renderActionButton(GuiGraphicsExtractor graphics, int[] bounds, String[] icon, int face, int iconColor, int mouseX, int mouseY) {
//?}
        boolean over = isMouseOver(mouseX, mouseY, bounds[0], bounds[1], bounds[2], bounds[3]);
        graphics.fill(bounds[0], bounds[1], bounds[0] + bounds[2], bounds[1] + bounds[3], CapeTileArt.OUTLINE);
        graphics.fill(bounds[0] + 1, bounds[1] + 1, bounds[0] + bounds[2] - 1, bounds[1] + bounds[3] - 1,
                over ? CapeTileArt.lighter(face) : face);
        CapeEditorStyle.icon(graphics, icon, bounds[0] + 2, bounds[1] + 2, iconColor);
    }

    /** The last tile of My Capes: the import zone, {@code width} wide. */
//? if <26.1 {
    private void renderImportTile(GuiGraphics graphics, int x, int y, int width, boolean hovered) {
//?} else {
    private void renderImportTile(GuiGraphicsExtractor graphics, int x, int y, int width, boolean hovered) {
//?}
        ImportZone.draw(graphics, this.font, x, y, width, this.cardHeight,
                Component.translatable("quickskin.dropzone.capes.short_main"),
                Component.translatable("quickskin.dropzone.capes.short_click"), hovered);
    }

    private static boolean isHd(CapeEntry cape) {
        return cape.isLocal() && cape.getLocalCape() != null && cape.getLocalCape().resolution() != null
                && cape.getLocalCape().resolution().isHD();
    }

    /** The capes a section holds, None not counted. */
    private static int capeCount(ShelfSection section) {
        int count = 0;
        for (CapeEntry cape : section.capes()) {
            if (!cape.isNone()) {
                count++;
            }
        }
        return count;
    }

//? if <1.21.6 {
    private void renderCapeTexture(GuiGraphics graphics, ResourceLocation texture, CapeEntry cape, int x, int y) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
//?} else if <1.21.11 {
    private void renderCapeTexture(GuiGraphics graphics, ResourceLocation texture, CapeEntry cape, int x, int y) {
//?} else if <26.1 {
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

        // Immediate GUI blits (1.20.1, 1.21.1) use the current blend state, which is disabled by the
        // time the grid draws, so a half-transparent cape face showed opaque only on those targets.
        // Later GUI render types always blend it, as the worn cape does on every version.
//? if <1.21.2 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
//?}
//? if <1.21 {
        GuiCompat.blit(graphics, texture,
                0, 0, 10, 16, u, v, uWidth, vHeight, textureWidth, textureHeight);
//?} else if <1.21.11 {
        MinecraftCompat.INSTANCE.blit(graphics, texture, 0, 0, 10, 16, u, v, uWidth, vHeight, textureWidth, textureHeight);
//?} else {
        GuiCompat.blit(graphics, texture, 0, 0, 10, 16, u, v, uWidth, vHeight, textureWidth, textureHeight);
//?}
//? if <1.21.2 {
        RenderSystem.disableBlend();
//?}

//? if <1.21.6 {
        graphics.pose().popPose();
//?} else {
        graphics.pose().popMatrix();
//?}
    }

//? if <26.1 {
    private void renderLoadingTexture(GuiGraphics graphics, int x, int y) {
//?} else {
    private void renderLoadingTexture(GuiGraphicsExtractor graphics, int x, int y) {
//?}
        CapeTileArt.dashedOutline(graphics, x, y, this.capeWidth, this.capeDisplaySize, 0xFF6E6F73);
        for (int i = 0; i < 3; i++) {
            int dot = x + this.capeWidth / 2 - 6 + i * 5;
            graphics.fill(dot, y + this.capeDisplaySize / 2, dot + 2, y + this.capeDisplaySize / 2 + 2,
                    i == 0 ? 0xFFE0E0E0 : i == 1 ? 0xFF909090 : 0xFF5A5B60);
        }
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
        if (this.contextMenu.isOpen() && (this.contextMenu.click(mouseX, mouseY, button) || this.contextMenu.isOpen())) {
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
//?} else if <26.1 {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.buttonInfo().button();
        if (this.contextMenu.isOpen() && (this.contextMenu.click(mouseX, mouseY, button) || this.contextMenu.isOpen())) {
            return true;
        }
        if (super.mouseClicked(event, focused)) return true;
//?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
        if (this.contextMenu.isOpen() && (this.contextMenu.click(mouseX, mouseY, button) || this.contextMenu.isOpen())) {
            return true;
        }
        if (super.mouseClicked(event, focused)) return true;
//?}

        // Handle scrollbar dragging
        if (button == InputConstants.MOUSE_BUTTON_LEFT && this.maxScroll > 0) {
            int scrollbarX = scrollbarX();
            if (mouseX >= scrollbarX && mouseX < scrollbarX + scrollbarWidth()
                    && mouseY >= this.gridY && mouseY < this.gridY + this.gridHeight) {
                this.isDraggingScrollbar = true;
                int thumbHeight = scrollbarThumbHeight();
                int thumbY = this.gridY + (int) (this.scrollOffset * (double) (this.gridHeight - thumbHeight) / (double) this.maxScroll);
                if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
                    this.scrollbarClickOffset = mouseY - thumbY;
                } else {
                    this.scrollbarClickOffset = thumbHeight / 2.0;
                    updateScrollFromMouse(mouseY);
                }
                return true;
            }
        }

        if (!isMouseOverGrid((int) mouseX, (int) mouseY)) {
            return false;
        }
        CapeGridLayout.Hit hit = shelfHit((int) mouseX, (int) mouseY);
        if (hit == null) {
            return false;
        }

        // The import tile opens the file dialog, as the Import Cape button does
        if (hit.importSlot()) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                playClick(1.0f);
                importCape();
                return true;
            }
            return false;
        }

        CapeEntry clickedCape = this.shelfSections.get(hit.section()).capes().get(hit.index());
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (!clickedCape.isNone()) {
                this.contextMenu.open(clickedCape, ClientConfig.getInstance().isFavoriteCape(clickedCape.getCapeId()),
                        (int) mouseX, (int) mouseY, this.width, this.height);
            }
            return true;
        }
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }

        // The favourite button, and the edit and delete buttons of a cape of the player's own
        int[] card = cardOrigin(hit);
        int[] favorite = favoriteButtonBounds(clickedCape, card[0], card[1]);
        if (favorite != null && isMouseOver((int) mouseX, (int) mouseY, favorite[0], favorite[1], favorite[2], favorite[3])) {
            playClick(1.0f);
            toggleFavorite(clickedCape);
            return true;
        }
        int[] delete = deleteButtonBounds(clickedCape, card[0], card[1]);
        if (delete != null && isMouseOver((int) mouseX, (int) mouseY, delete[0], delete[1], delete[2], delete[3])) {
            showDeleteConfirmation(clickedCape);
            return true;
        }
        int[] edit = editButtonBounds(clickedCape, card[0], card[1]);
        if (edit != null && isMouseOver((int) mouseX, (int) mouseY, edit[0], edit[1], edit[2], edit[3])) {
            startCapeEdit(clickedCape);
            return true;
        }

        // Wear it at once (None takes the cape off)
        playClick(0.25f);
        this.selectedCape = clickedCape;
        this.previewCape = clickedCape;
        if (clickedCape.isNone()) {
            removeCape();
        } else {
            applyCape(clickedCape);
        }
        return true;
    }

    private void playClick(float volume) {
        if (minecraft != null && minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, volume
            ));
        }
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

        // Dress the model (works both in-game and on title screen)
        updateStage();

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
        double scrollableTrackHeight = this.gridHeight - scrollbarThumbHeight();
        if (scrollableTrackHeight > 0) {
            double scrollRatio = (mouseY - this.gridY - this.scrollbarClickOffset) / scrollableTrackHeight;
            this.targetScrollOffset = Mth.clamp(scrollRatio * this.maxScroll, 0, this.maxScroll);
        }
    }

    private int scrollbarX() {
        return this.gridX + this.gridWidth + 8;
    }

    /** Right of the speed slider's track, on its plate: room for the widest percentage. */
    private int speedPercentRoom() {
        return this.font.width("300%") + 14;
    }

    private static int scrollbarWidth() {
        return 4;
    }

    private int scrollbarThumbHeight() {
        return Mth.clamp((int) ((float) (this.gridHeight * this.gridHeight) / (float) this.totalContentHeight),
                24, this.gridHeight - 8);
    }

    @Override
//? if <1.21.9 {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
//?} else if <26.1 {
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
//?} else if <26.1 {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        int button = event.buttonInfo().button();
//?} else {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        int button = GuiCompat.mouseButton(event);
//?}
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
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
        this.contextMenu.close();
        // The wheel scrolls the list wherever the mouse is, unless something under it, such as the
        // player preview's zoom, takes the notch
//? if <1.21 {
        if (!isMouseOverGrid((int) mouseX, (int) mouseY) && super.mouseScrolled(mouseX, mouseY, delta)) {
            return true;
        }
        this.targetScrollOffset = Mth.clamp(this.targetScrollOffset - delta * scrollSpeed, 0.0D, this.maxScroll);
//?} else {
        if (!isMouseOverGrid((int) mouseX, (int) mouseY) && super.mouseScrolled(mouseX, mouseY, deltaX, deltaY)) {
            return true;
        }
        this.targetScrollOffset = Mth.clamp(this.targetScrollOffset - deltaY * scrollSpeed, 0.0D, this.maxScroll);
//?}
        return true;
    }

//? if <26.1 {
    private void renderScrollbar(GuiGraphics graphics) {
//?} else {
    private void renderScrollbar(GuiGraphicsExtractor graphics) {
//?}
        if (this.maxScroll <= 0) return;

        int x = scrollbarX();
        int w = scrollbarWidth();
        graphics.fill(x, this.gridY, x + w, this.gridY + this.gridHeight, 0x80000000);
        int thumbHeight = scrollbarThumbHeight();
        int thumbY = this.gridY + (int) (this.scrollOffset * (double) (this.gridHeight - thumbHeight) / (double) this.maxScroll);
        graphics.fill(x, thumbY, x + w, thumbY + thumbHeight, 0xFF808080);
        graphics.fill(x, thumbY, x + w - 1, thumbY + thumbHeight - 1, 0xFFC0C0C0);
    }

    /** What lies under the mouse in the list, below its sticky heading; null for nothing. */
    @Nullable
    private CapeGridLayout.Hit shelfHit(int mouseX, int mouseY) {
        if (!isMouseOverGrid(mouseX, mouseY) || mouseY < this.gridY + STICKY_HEIGHT) {
            return null;
        }
        int x = mouseX - this.gridX;
        int y = mouseY - this.gridY + (int) this.scrollOffset;
        CapeGridLayout.Hit hit = this.shelf.hitTest(x, y);
        // A cell ends with the gap after its card, which holds nothing; the import tile reaches
        // the end of its row
        int width = hit == null ? 0 : hit.importSlot() ? this.shelf.importWidth(hit.index()) - CARD_GAP : this.cardWidth;
        if (hit == null || x - this.shelf.slotX(hit.index()) >= width
                || y - this.shelf.cellY(hit.section(), hit.index()) >= this.cardHeight) {
            return null;
        }
        return hit;
    }

    @Nullable
    private CapeEntry getCapeAt(int mouseX, int mouseY) {
        CapeGridLayout.Hit hit = shelfHit(mouseX, mouseY);
        if (hit == null || hit.importSlot()) {
            return null;
        }
        return this.shelfSections.get(hit.section()).capes().get(hit.index());
    }

    /**
     * Where a cape is on screen: the top-left corner of a square of {@link #capeDisplaySize}
     * centred on the cape, whose middle is the middle of the cape. The first place it is listed: a
     * favourite is found in Favourites.
     */
    @Nullable
    private int[] getCapePosition(CapeEntry cape) {
        for (int i = 0; i < this.shelfSections.size(); i++) {
            int slot = this.shelfSections.get(i).capes().indexOf(cape);
            if (slot >= 0) {
                int cx = this.gridX + this.shelf.slotX(slot) + this.cardWidth / 2;
                int capeTop = this.gridY - (int) this.scrollOffset + this.shelf.cellY(i, slot) + CARD_PADDING;
                return new int[]{cx - this.capeDisplaySize / 2, capeTop};
            }
        }
        return null;
    }

    /** The top-left corner of the card a hit landed on. */
    private int[] cardOrigin(CapeGridLayout.Hit hit) {
        return new int[]{this.gridX + this.shelf.slotX(hit.index()),
                this.gridY - (int) this.scrollOffset + this.shelf.cellY(hit.section(), hit.index())};
    }

    /**
     * The {@code row}th button down the top-right corner of the card at cardX, cardY, as
     * {x, y, width, height}. A cape listed twice (a favourite) has buttons on both cards.
     */
    private int[] cardButton(int cardX, int cardY, int row) {
        return new int[]{cardX + this.cardWidth - ACTION_BUTTON_SIZE - ACTION_BUTTON_INSET,
                cardY + ACTION_BUTTON_INSET + row * (ACTION_BUTTON_SIZE + 2), ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE};
    }

    /** The favourite button at the top of a card, or null for None. A favourite keeps its gold star there. */
    @Nullable
    private int[] favoriteButtonBounds(CapeEntry cape, int cardX, int cardY) {
        return cape.isNone() ? null : cardButton(cardX, cardY, 0);
    }

    /** The delete button under the favourite button of a cape of the player's own; null for others. */
    @Nullable
    private int[] deleteButtonBounds(CapeEntry cape, int cardX, int cardY) {
        return !cape.isLocal() || cape.isKnown() ? null : cardButton(cardX, cardY, 1);
    }

    /** The delete button on the first card a cape is listed on. */
    @Nullable
    private int[] deleteButtonBounds(CapeEntry cape) {
        int[] pos = getCapePosition(cape);
        if (pos == null) {
            return null;
        }
        int cardX = pos[0] + this.capeDisplaySize / 2 - this.cardWidth / 2;
        return deleteButtonBounds(cape, cardX, pos[1] - CARD_PADDING);
    }

    /** The edit button under the delete button, for a cape whose editor source was kept; null otherwise. */
    @Nullable
    private int[] editButtonBounds(CapeEntry cape, int cardX, int cardY) {
        return deleteButtonBounds(cape, cardX, cardY) == null || !cape.isEditable() ? null : cardButton(cardX, cardY, 2);
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
            showToast(Component.translatable("quickskin.cape.no_files"), ErrorToast.Kind.ERROR);
            return;
        }

        showProcessing(Component.translatable("quickskin.cape.processing_count", validFiles.size()));
        startCapeImports(validFiles);
    }

    /**
     * Vanilla's Elytra texture lives under the equipment asset tree on modern runtimes and at the
     * pre-equipment entity path on older ones. Both candidates are probed, newest first, so the
     * import path composites real vanilla wings on every supported version instead of silently
     * saving a cape whose Elytra faces stay fully transparent.
     */
    @Nullable
    private java.awt.image.BufferedImage getVanillaElytraImage() {
        for (String texturePath : List.of(
                "textures/entity/equipment/wings/elytra.png",
                "textures/entity/elytra.png")) {
            try {
//? if <1.21 {
                ResourceLocation vanillaElytraTexture = new ResourceLocation("minecraft", texturePath);
//?} else if <1.21.11 {
                ResourceLocation vanillaElytraTexture = ResourceLocation.fromNamespaceAndPath("minecraft", texturePath);
//?} else {
                Identifier vanillaElytraTexture = Identifier.fromNamespaceAndPath("minecraft", texturePath);
//?}
                var resourceOptional = Minecraft.getInstance().getResourceManager()
                        .getResource(vanillaElytraTexture);
                if (resourceOptional.isEmpty()) {
                    continue;
                }
                try (InputStream stream = resourceOptional.get().open()) {
//? if <26.1 {
                    return SafeImageReader.readPng(stream);
//?} else {
                    byte[] encoded = com.quickskin.mod.common.util.BoundedFileReader.readBytes(
                            stream,
                            (int) com.quickskin.mod.common.util.SafeImageReader.MAX_ENCODED_BYTES);
                    return com.quickskin.mod.common.util.SafeImageReader.readPng(encoded);
//?}
                }
            } catch (IOException e) {
//? if <26.1 {
//?} else {
                QuickSkinInfo.LOGGER.debug("Unable to load the vanilla elytra texture", e);
//?}
                continue;
            }
        }
        return null;
    }

    @Override
    public boolean isPauseScreen() {
        // Don't pause game when this screen is open
        return false;
//? if <1.21 {
//?} else if <1.21.6 {
    }

    @Override
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
//?} else if <26.1 {
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
    public void removed() {
        // The next screen, the cape editor or the world opens at the player's own scale
        if (this.minecraft != null) {
            MenuGuiScale.restore(this.minecraft);
        }
        super.removed();
    }

    @Override
    public void onClose() {
        this.capeImportGeneration++;
        this.capeEditGeneration++;
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
            setMessage(Component.translatable("quickskin.cape.animation_speed", percentage()));
        }

        private int percentage() {
            return (int) Math.round(10 + this.value * this.value * 290);
        }

        /**
         * The plate round the track, as the menu's cards are drawn, with the speed icon on its
         * left and the percentage on its right, then the track in the cape editor's slider style.
         */
        @Override
        //? if <26.1 {
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //?} else {
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //?}
            boolean lit = this.isHovered() || this.isFocused();
            int plateX = getX() - SPEED_ICON_ROOM;
            int plateY = getY() - 3;
            int plateRight = getX() + getWidth() + speedPercentRoom();
            CapeTileArt.card(graphics, plateX, plateY, plateRight - plateX, CONTROL_HEIGHT,
                    lit ? CARD_EDGE_HOVER : CARD_EDGE, CARD_FACE);
            CapeEditorStyle.icon(graphics, ICON_SPEED, plateX + 6, plateY + (CONTROL_HEIGHT - 7) / 2,
                    lit ? 0xFFFFFFFF : 0xFFC6C7CC);
            CapeEditorStyle.slider(graphics, getX(), getY(), getWidth(), getHeight(), this.value, lit, this.active);
            String percent = percentage() + "%";
            UiText.draw(graphics, font, percent, plateRight - 6 - font.width(percent),
                    plateY + (CONTROL_HEIGHT - 8) / 2, 0xFFFFFFFF);
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
//?} else if <26.1 {

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
