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
import com.quickskin.mod.client.gui.util.MenuGuiScale;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.gui.widget.ErrorToast;
import com.quickskin.mod.client.gui.widget.FieldIconButton;
import com.quickskin.mod.client.gui.widget.IconButton;
import com.quickskin.mod.client.gui.widget.ImportTileButton;
//? if <1.21 {
//?} else {
import com.quickskin.mod.client.gui.widget.PlayerWidget;
//?}
import com.quickskin.mod.client.gui.widget.SearchField;
import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.gui.widget.SkinListWidget;
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
    /** The skin grid is three tiles across from this canvas width, two below it... */
    private static final int MIN_WIDTH_FOR_THREE_COLUMNS = 560;
    /** ...and four from this one. */
    private static final int MIN_WIDTH_FOR_FOUR_COLUMNS = 620;
    /** The list's cards are at least as wide as three tiles, to leave their text some room. */
    private static final int MIN_LIST_COLUMNS = 3;
    private int skinColumnWidth;
    private static final int CONTROL_HEIGHT = 20;
    /** Height of the search row, its field and its square Search button. */
    private static final int SEARCH_HEIGHT = ActionButtonsPanel.ICON_BUTTON_SIZE;
    private static final int SEARCH_GAP = 3;
    private int skinColumnX;
    private int skinHeaderY;
    /** Bottom edge of the search row, which the name tag keeps its distance from. */
    private int searchBottom;
    private int skinListBottom;

    // Picks up skins copied into the uploads folder from outside the game
    private final LocalAssetFolderWatch localAssetWatch = new LocalAssetFolderWatch();

    // Player preview rotation state (preserved across resizes); the model starts facing the viewer
    private float savedBodyYaw = 0.0f;
    private float savedTargetRotation = 0.0f;

    // The look being previewed. Nothing is applied until Confirm; it survives resizes and the
    // sub-screens that return to this instance.
    @Nullable
    private String pendingHash;
    @Nullable
    private String pendingModelType;

    // Whether the turntable ring has played its appearing animation for this menu
    private boolean ringIntroPlayed;

    // Dragging the model turns it (from 1.21; before, the model widget turns itself)
    private boolean rotatingModel;
    private double rotationDragStartX;
    private float rotationDragStartYaw;
    private static final float ROTATION_DRAG_SENSITIVITY = 0.8f;

    // Error toasts
    private final List<ErrorToast> errorToasts = new ArrayList<>();

    // Mojang search widgets
    private EditBox usernameSearchField;
    private IconButton searchButton;
    private Button sortButton;
    private Button viewButton;
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
        // Another mod can put the player's scale back on the window after init(), as some do
        // whenever a screen opens; take the menu's scale back and lay the menu out again
        if (this.minecraft != null && MenuGuiScale.apply(this.minecraft)) {
            rebuildWidgets();
        }
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
        // The menu draws at its own GUI scale, chosen from the window size, so it looks the same
        // whatever scale the player picked; a window resize runs init() again
        if (this.minecraft != null) {
            MenuGuiScale.apply(this.minecraft);
            this.width = this.minecraft.getWindow().getGuiScaledWidth();
            this.height = this.minecraft.getWindow().getGuiScaledHeight();
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

        // Skins across the skin column: four, or fewer on a canvas too narrow to leave the model and
        // its buttons room
        boolean listRows = com.quickskin.mod.config.ClientConfig.getInstance().skinListRows;
        int skinColumns = this.width >= MIN_WIDTH_FOR_FOUR_COLUMNS ? 4
                : this.width >= MIN_WIDTH_FOR_THREE_COLUMNS ? 3 : 2;
        if (listRows) {
            skinColumns = Math.max(MIN_LIST_COLUMNS, skinColumns);
        }
        int tileSize = SkinListPanel.TILE_SIZE;

        // The link buttons share the search row, at the top right
        int linkRowX = this.width - MARGIN - LinkButtonsPanel.rowWidth();

        // Player-name search, top centre: the field centred over the model and its name tag, then a
        // square Search button drawn flat like it. The field is as wide as both sides leave room
        // for: the skin column on the left, the button and the link row on the right.
        int searchCenterX = this.width / 2;
        int searchHalfRoom = Math.min(searchCenterX - (MARGIN + SkinListWidget.widthFor(tileSize, skinColumns) + MARGIN),
                linkRowX - MARGIN - SEARCH_GAP - SEARCH_HEIGHT - searchCenterX);
        int searchFieldWidth = Math.max(80, Math.min(220, 2 * searchHalfRoom));
        int searchX = searchCenterX - searchFieldWidth / 2;
        int searchY = MARGIN;
        searchBottom = searchY + SEARCH_HEIGHT;
        usernameSearchField = new SearchField(
                this.font,
                searchX,
                searchY,
                searchFieldWidth,
                SEARCH_HEIGHT,
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

        searchButton = new FieldIconButton(
                searchX + searchFieldWidth + SEARCH_GAP,
                searchY,
                SEARCH_HEIGHT,
                "search",
                Component.translatable("quickskin.button.search"),
                button -> searchMojangSkin()
        );
        searchButton.setTooltip(Tooltip.create(Component.translatable("quickskin.button.search")));
        addRenderableWidget(searchButton);
        searchButton.active = false;

        // Skin column, left: "Skins" over the counter with the view and sort buttons beside them, a
        // grid of skin tiles or a list of skin cards with its scrollbar, then the import tile across
        // the bottom of the column
        skinColumnX = MARGIN;
        skinHeaderY = searchY + SEARCH_HEIGHT + MARGIN;
        int listTop = skinHeaderY + CONTROL_HEIGHT + 4;
        skinListBottom = Math.max(listTop + tileSize, this.height - MARGIN - CONTROL_HEIGHT - 4);
        skinListPanel = new SkinListPanel(
                skinColumnX,
                listTop,
                skinColumns,
                listRows,
                skinListBottom - listTop,
                this.minecraft,
                this::onSkinSelected
        );
        skinListPanel.init(this);

        // The header is as wide as its widest line and its two buttons, at least as wide as the tiles
        String widestCount = skinListPanel.size() + "/" + skinListPanel.size();
        int headerTextWidth = Math.max(this.font.width(Component.translatable("quickskin.menu.skins")),
                this.font.width(widestCount));
        skinColumnWidth = Math.max(SkinListWidget.widthFor(tileSize, skinColumns),
                headerTextWidth + 4 + 2 * CONTROL_HEIGHT + HEADER_BUTTON_GAP);
        sortButton = com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                skinColumnX + skinColumnWidth - CONTROL_HEIGHT,
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

        // Grid or list: the button shows the view it switches to, drawn by renderSkinHeader
        viewButton = com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                skinColumnX + skinColumnWidth - 2 * CONTROL_HEIGHT - HEADER_BUTTON_GAP,
                skinHeaderY,
                CONTROL_HEIGHT,
                CONTROL_HEIGHT,
                Component.empty(),
                button -> toggleListRows()
        );
        viewButton.setTooltip(Tooltip.create(Component.translatable(
                listRows ? "quickskin.tooltip.view_grid" : "quickskin.tooltip.view_list")));
        addRenderableWidget(viewButton);

        importTile = new ImportTileButton(skinColumnX, skinListBottom + 4, SkinListWidget.gridWidthFor(tileSize, skinColumns),
                CONTROL_HEIGHT, button -> openImportDialog());
        importTile.setTooltip(Tooltip.create(Component.translatable("quickskin.tooltip.import_skin")));
        addRenderableWidget(importTile);

        // The middle of the menu is sized from the canvas, which the menu's scale keeps between
        // about 300 and 450 units tall, so it keeps its proportions in any window.
        int centerX = this.width / 2;
        int sideStart = skinColumnX + skinColumnWidth + SIDE_GAP;
        int sideEnd = this.width - MARGIN;

        // Confirm, bottom centre, growing with the canvas but never over the skin column
        int confirmHeight = Math.max(MIN_CONFIRM_HEIGHT, Math.min(MAX_CONFIRM_HEIGHT,
                Math.round(this.height * CONFIRM_HEIGHT_SHARE)));
        int confirmWidth = Math.min(Math.round(confirmHeight * CONFIRM_ASPECT), 2 * (centerX - sideStart));
        int confirmY = this.height - MARGIN - confirmHeight;
        int confirmX = centerX - confirmWidth / 2;

        // The model stands above Confirm inside the turntable ring, below the search row and the
        // name tag. It fills at most MODEL_HEIGHT_SHARE of the canvas and never outgrows the
        // buttons around it, which stay at the player's GUI scale.
        int nameTagHeight = NAME_TAG_GAP + (int) Math.ceil(10 * NAME_TAG_SCALE);
        int clusterTop = searchBottom + 4;
        int modelTop = clusterTop + nameTagHeight;
        float heightCap = Math.min(MAX_MODEL_SCALE, Math.min(
                (confirmY - RING_CLEARANCE - modelTop) / (MODEL_HEIGHT_PER_SCALE + RING_DEPTH_PER_SCALE),
                this.height * MODEL_HEIGHT_SHARE / MODEL_HEIGHT_PER_SCALE));
        // The floating buttons flank it: in a narrow canvas they narrow down to their labels
        // before the model shrinks to make room for them
        int sideRoom = Math.min(centerX - sideStart, sideEnd - centerX) - BUTTON_GAP;
        int buttonWidth = Math.max(
                ActionButtonsPanel.minimumButtonWidth(this.font),
                Math.min(PREFERRED_BUTTON_WIDTH, sideRoom - (int) (heightCap * HALF_WIDTH_PER_SCALE)));
        float widthCap = (sideRoom - buttonWidth) / HALF_WIDTH_PER_SCALE;
        float scaleCap = Math.max(MIN_MODEL_SCALE, Math.min(heightCap, widthCap));
        int ringDepth = RING_CLEARANCE + (int) Math.ceil(scaleCap * RING_DEPTH_PER_SCALE);
        // On a canvas taller than the group needs, centre the name tag, model and Confirm between
        // the search row and the bottom edge instead of leaving the gap above the head
        int clusterHeight = nameTagHeight + (int) Math.ceil(scaleCap * MODEL_HEIGHT_PER_SCALE)
                + ringDepth + confirmHeight;
        int spare = (this.height - MARGIN) - clusterTop - clusterHeight;
        if (spare > 0) {
            confirmY -= spare / 2;
        }
        int feetY = confirmY - ringDepth;

        playerPreviewPanel = new PlayerPreviewPanel(centerX, feetY, 0, 0);
        playerPreviewPanel.initPlayerWidget(this, centerX, feetY, scaleCap);
        float modelScale = playerPreviewPanel.getPlayerWidget() != null
                ? playerPreviewPanel.getPlayerWidget().getEffectiveScale() : scaleCap;
        playerPreviewPanel.setModelBox(centerX, feetY, modelScale);
        // The turntable ring lies around the ankles, a little wider than the model
        int ringRadiusX = (int) (modelScale * 0.55f) + 8;
        // The ring's appearing animation plays when the menu opens, not on resizes or returns
        playerPreviewPanel.initControls(this, centerX, feetY - 3, ringRadiusX, !ringIntroPlayed);
        ringIntroPlayed = true;

        // Floating buttons, left and right of the model at shoulder and waist height
        int halfBody = (int) (modelScale * HALF_WIDTH_PER_SCALE);
        int leftX = Math.max(sideStart, centerX - halfBody - BUTTON_GAP - buttonWidth);
        int rightX = Math.min(sideEnd - buttonWidth, centerX + halfBody + BUTTON_GAP);
        int shoulderY = Math.max(skinHeaderY, feetY - (int) (modelScale * 1.45f));
        int waistY = Math.max(shoulderY + ActionButtonsPanel.ICON_BUTTON_SIZE + 4, feetY - (int) (modelScale * 0.9f));
        ActionButtonsPanel.Layout layout = new ActionButtonsPanel.Layout(
                leftX, rightX, shoulderY, waistY, buttonWidth, confirmX, confirmY, confirmWidth, confirmHeight);
        ActionButtonsPanel.ActionCallbacks callbacks = new ActionButtonsPanel.ActionCallbacks(
                this::cycleBodyWidth,
                () -> openWebsite("https://laby.net/skins?order=trending_30d"),
                () -> openWebsite("https://mcskins.top/128x128/"),
                () -> {
                    // Open cape selection screen
                    if (minecraft != null) {
//? if <26.2 {
                        minecraft.setScreen(new PlayerCapeMenuScreen(this));
//?} else {
                        minecraft.gui.setScreen(new PlayerCapeMenuScreen(this));
//?}
                    }
                },
                this::confirmSelection,
                this::uploadPendingSkin
        );
        actionButtonsPanel = new ActionButtonsPanel(layout);
        actionButtonsPanel.init(this, layout, callbacks);
        for (Button button : actionButtonsPanel.buttons()) {
            playerPreviewPanel.addPriorityControl(button);
        }

        LinkButtonsPanel linkButtonsPanel = new LinkButtonsPanel(linkRowX, searchY);
        linkButtonsPanel.init(this);

        // Restore the previewed look and the active cape
        restoreSavedState();
        updateBodyWidthButton();
        updateUploadButton();
        updateConfirmButtonState();
    }

    /** The player's name above the model is drawn this much larger than GUI text... */
    private static final float NAME_TAG_SCALE = 1.5f;
    /** ...and the layout leaves at least this much room between it and the head. */
    private static final int NAME_TAG_GAP = 10;
    /**
     * Distance from the tag's top to the middle of the name's capitals, in font pixels: the name
     * is drawn a pixel down the tag and its capitals are seven pixels tall.
     */
    private static final float NAME_TAG_CAPITALS_MIDDLE = 4.5f;

    /** Height of the drawn player per unit of model scale: a player is 1.8 blocks, plus the hat and a margin. */
    private static final float MODEL_HEIGHT_PER_SCALE = 2.0f;
    /** Half the drawn player's width per unit of model scale, arms included. */
    private static final float HALF_WIDTH_PER_SCALE = 0.55f;
    /**
     * The drawn player is at most this share of the canvas height: the share it has at 1080p,
     * where the buttons beside it limit its width, so taller or wider canvases do not draw it
     * larger than there.
     */
    private static final float MODEL_HEIGHT_SHARE = 0.47f;
    private static final float MIN_MODEL_SCALE = 30.0f;
    /** The largest model scale, in GUI units, so the model stays in proportion with its buttons. */
    private static final float MAX_MODEL_SCALE = 120.0f;
    /** How far the turntable ring reaches below the feet per unit of model scale... */
    private static final float RING_DEPTH_PER_SCALE = 0.13f;
    /** ...and the room left between the ring and Confirm. */
    private static final int RING_CLEARANCE = 6;

    /** Confirm's height is this share of the canvas height, within the bounds below. */
    private static final float CONFIRM_HEIGHT_SHARE = 0.09f;
    private static final int MIN_CONFIRM_HEIGHT = 24;
    private static final int MAX_CONFIRM_HEIGHT = 36;
    private static final float CONFIRM_ASPECT = 6.25f;

    /** Width of the floating buttons when there is room for it. */
    private static final int PREFERRED_BUTTON_WIDTH = 104;
    /** Gap between the floating buttons and the model... */
    private static final int BUTTON_GAP = 10;
    /** ...and between them and the side columns. */
    private static final int SIDE_GAP = 6;

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

        // The turntable ring goes behind the model, which stands inside it
        if (playerPreviewPanel != null && playerPreviewPanel.getRotationArc() != null) {
            playerPreviewPanel.getRotationArc().setHighlighted(rotatingModel);
            playerPreviewPanel.getRotationArc().renderRing(graphics, mouseX, mouseY);
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

        // Render error toasts (on top of everything)
        renderErrorToasts(graphics);
//? if <1.21.6 {
        graphics.pose().popPose();
//?}
    }

    /**
     * "Skins" above the skin column, and under it the selected position and the number of skins,
     * beside the view and sort buttons, and the view button's icon.
     */
//? if <26.1 {
    private void renderSkinHeader(GuiGraphics graphics) {
//?} else {
    private void renderSkinHeader(GuiGraphicsExtractor graphics) {
//?}
        if (skinListPanel == null) {
            return;
        }
        int position = skinListPanel.selectedPosition();
        Component label = Component.translatable("quickskin.menu.skins");
        String count = (position > 0 ? String.valueOf(position) : "-") + "/" + skinListPanel.size();
        // Two lines filling the sort button's height: the label, then the count a little dimmer
        int labelY = skinHeaderY + 1;
        int countY = skinHeaderY + CONTROL_HEIGHT - 8;
//? if <26.1 {
        graphics.drawString(this.font, label, skinColumnX, labelY, 0xFFFFFFFF);
        graphics.drawString(this.font, count, skinColumnX, countY, 0xFFC8C8C8);
//?} else {
        graphics.text(this.font, label, skinColumnX, labelY, 0xFFFFFFFF);
        graphics.text(this.font, count, skinColumnX, countY, 0xFFC8C8C8);
//?}

        // The view button's icon, white with a text-like shadow like the sort button's arrow
        if (viewButton != null) {
            String[] icon = com.quickskin.mod.config.ClientConfig.getInstance().skinListRows
                    ? VIEW_GRID_ICON : VIEW_LIST_ICON;
            int iconX = viewButton.getX() + (viewButton.getWidth() - 9) / 2;
            int iconY = viewButton.getY() + (viewButton.getHeight() - 9) / 2;
            for (int pass = 0; pass < 2; pass++) {
                int offset = pass == 0 ? 1 : 0;
                int color = pass == 0 ? 0xFF3F3F3F : viewButton.active ? 0xFFFFFFFF : 0xFFA0A0A0;
                for (int row = 0; row < icon.length; row++) {
                    for (int column = 0; column < icon[row].length(); column++) {
                        if (icon[row].charAt(column) == '#') {
                            int x = iconX + column + offset;
                            int y = iconY + row + offset;
                            graphics.fill(x, y, x + 1, y + 1, color);
                        }
                    }
                }
            }
        }
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
        int headTop = widget.getLayoutFeetY() - (int) (widget.getEffectiveScale() * MODEL_HEIGHT_PER_SCALE);
        // The name's capitals sit halfway between the search row and the head
        float middle = (searchBottom + headTop) / 2.0f;
        float top = middle - NAME_TAG_CAPITALS_MIDDLE * NAME_TAG_SCALE;
//? if <1.21.6 {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, top, 0.0F);
        graphics.pose().scale(NAME_TAG_SCALE, NAME_TAG_SCALE, 1.0F);
//?} else {
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, top);
        graphics.pose().scale(NAME_TAG_SCALE, NAME_TAG_SCALE);
//?}
        graphics.fill(-textWidth / 2 - 2, 0, (textWidth + 1) / 2 + 2, 10, 0x40000000);
//? if <26.1 {
        graphics.drawString(this.font, name, -textWidth / 2, 1, 0xFFFFFFFF, false);
//?} else {
        graphics.text(this.font, name, -textWidth / 2, 1, 0xFFFFFFFF, false);
//?}
//? if <1.21.6 {
        graphics.pose().popPose();
//?} else {
        graphics.pose().popMatrix();
//?}
    }

    @Override
    public void removed() {
        BackgroundRenderer.cleanup();
        // The next screen, a dialog or the world opens at the player's own scale
        if (this.minecraft != null) {
            MenuGuiScale.restore(this.minecraft);
        }
        super.removed();
    }

    @Override
    public void onClose() {
        // Return to parent screen (or null to return to game)
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
        if (!handled && button == InputConstants.MOUSE_BUTTON_LEFT && startModelRotation(mouseX, mouseY)) {
            return true;
        }
        return handled;
    }

    /**
     * Starts turning the model from a press on the turntable ring or (from 1.21, where the model
     * takes no clicks itself) on the model.
     */
    private boolean startModelRotation(double mouseX, double mouseY) {
        if (playerPreviewPanel == null) {
            return false;
        }
        com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
        if (widget == null || !widget.visible) {
            return false;
        }
        com.quickskin.mod.client.gui.widget.RotationArcWidget arc = playerPreviewPanel.getRotationArc();
        boolean onArc = arc != null && arc.isOnArc(mouseX, mouseY);
//? if <1.21 {
        boolean onModel = false;
//?} else {
        boolean onModel = playerPreviewPanel.isOverModelBox(mouseX, mouseY);
//?}
        if (!onArc && !onModel) {
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
            // The arm width this skin was last confirmed with (or detected on import), unless one
            // is already pending
            pendingModelType = PlayerPreviewPanel.normalizedModelType(modelType != null
                    ? modelType
                    : LocalAssetManager.getInstance().getSkinModelPreference(metadata.hash()));
            playerPreviewPanel.previewSkin(
                    metadata,
                    LocalAssetManager.getInstance().getTextureLocation(metadata.hash(), TextureQuality.FULL),
                    pendingModelType
            );
        }
        updateBodyWidthButton();
        updateUploadButton();
    }

    /** Body width: Wide or Slim, on the previewed skin only. */
    private void cycleBodyWidth() {
        if (pendingHash == null || pendingModelType == null || playerPreviewPanel == null) {
            return;
        }
        pendingModelType = "slim".equals(PlayerPreviewPanel.normalizedModelType(pendingModelType)) ? "classic" : "slim";
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
        boolean slim = "slim".equals(model);
        Component name = Component.translatable(slim ? "quickskin.preview.model.slim" : "quickskin.preview.model.wide");
        boolean selectable = pendingHash != null && pendingModelType != null && !playerPreviewPanel.isCpmLook();
        actionButtonsPanel.updateBodyWidth(slim, name, selectable);
    }

    /**
     * Upload to Mojang works on the previewed skin, for a premium account and a skin rather than a
     * CPM model.
     */
    private void updateUploadButton() {
        if (actionButtonsPanel == null) {
            return;
        }
        AssetMetadata metadata = pendingHash != null ? LocalAssetManager.getInstance().getMetadata(pendingHash) : null;
        actionButtonsPanel.setUploadAvailable(com.quickskin.mod.client.util.PremiumDetector.isPremiumAccount()
                && metadata != null && !metadata.isCpmModel());
    }

    /** Opens the Upload to Mojang dialog for the previewed skin. */
    private void uploadPendingSkin() {
        AssetMetadata metadata = pendingHash != null ? LocalAssetManager.getInstance().getMetadata(pendingHash) : null;
        if (metadata != null && !metadata.isCpmModel()) {
            showUploadToMojangDialog(metadata);
        }
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
        searchButton.setIcon("searching");
        searchButton.setTooltip(Tooltip.create(Component.translatable("quickskin.button.searching")));

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
            searchButton.setIcon("search");
            searchButton.setTooltip(Tooltip.create(Component.translatable("quickskin.button.search")));
            searchButton.active = usernameSearchField != null && !usernameSearchField.getValue().trim().isEmpty();
        }
    }

    /**
     * Get current skin sort mode
     */
    /** Space between the view and sort buttons. */
    private static final int HEADER_BUTTON_GAP = 2;
    /** The view button's icons, nine pixels square: four tiles, or three rows with a dot each. */
    private static final String[] VIEW_GRID_ICON = {
            "####.####", "####.####", "####.####", "####.####", ".........",
            "####.####", "####.####", "####.####", "####.####"};
    private static final String[] VIEW_LIST_ICON = {
            "##.######", "##.######", ".........", ".........", "##.######",
            "##.######", ".........", ".........", "##.######"};

    /** Switches the skin column between the grid of tiles and the list of cards, and remembers it. */
    private void toggleListRows() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        config.skinListRows = !config.skinListRows;
        config.save();
        // The column's width can change with the view, and the layout around it with that
        rebuildWidgets();
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f));
        }
    }

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
