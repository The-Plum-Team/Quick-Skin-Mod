package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.gui.util.UiText;
import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.GuiCompat;
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
import com.quickskin.mod.client.gui.widget.NameTagButton;
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
import com.quickskin.mod.client.services.LocalHiddenArmor;
import com.quickskin.mod.client.services.LocalNicknameService;
import com.quickskin.mod.client.api.ClientNetworkActions;
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
import net.minecraft.network.chat.MutableComponent;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/**
 * Main skin selection menu for QuickSkin
 * Opens when K key is pressed
 */
@Environment(EnvType.CLIENT)
public class PlayerSkinMenuScreen extends Screen implements com.quickskin.mod.client.gui.RestylableScreen,
        MenuGuiScale.Scaled {
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
    /** Whether the import zone fills the free space (and needs no tooltip), or is the bar. */
    private Boolean importZoneShown;

    // Controls that stay above the player preview (drawn after it, clicked before it)
    private final List<AbstractWidget> foregroundControls = new ArrayList<>();
    /** The menu a right click on a skin opens; drawn above every other control. */
    private com.quickskin.mod.client.gui.widget.SkinContextMenu skinContextMenu;

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
    /** The player's name above the model, which opens the nickname dialog. */
    private NameTagButton nameTagButton;
    /** What the tag's tooltip was made for (name and whether the tag works), so it is made once. */
    private String nameTagTooltipFor;
    /** The last nickname answer a skin menu looked at; only a newer one can become a toast. */
    private static int nicknameAnswerShown;
    /** Whether this menu sent a nickname whose answer it shows, whatever it is. */
    private boolean awaitingNicknameAnswer;
    private int skinListBottom;

    // Picks up skins copied into the uploads folder from outside the game
    private final LocalAssetFolderWatch localAssetWatch = new LocalAssetFolderWatch();

    // Player preview rotation state (preserved across resizes); the model starts facing the viewer
    private float savedBodyYaw = 0.0f;
    private float savedTargetRotation = 0.0f;

    // The look being previewed. A click on a skin wears it at once; one the menu selects itself
    // waits for Confirm. It survives resizes and the sub-screens that return to this instance.
    @Nullable
    private String pendingHash;
    @Nullable
    private String pendingModelType;
    // Whether a click in this visit changed the look, which a server with a cooldown allows once
    private boolean changedThisVisit;

    // Whether the turntable ring has played its appearing animation for this menu
    private boolean ringIntroPlayed;

    // Dragging the model turns it (from 1.21; before, the model widget turns itself)
    private boolean rotatingModel;
    /** The armour piece a left click pressed on the model, which a release in place toggles. */
    private int armorPressPiece;
    private double armorPressX;
    private double armorPressY;
    /** The piece drawn see-through on the model, its alpha, and when the mouse came onto it. */
    private int ghostPiece;
    private float ghostAlpha = 1.0f;
    private long ghostSinceNanos;
    private long ghostFrameNanos;
    /** A piece just clicked, shown as it now is until the mouse leaves it. */
    private int ghostSettledPiece;
    private double rotationDragStartX;
    private float rotationDragStartYaw;
    private static final float ROTATION_DRAG_SENSITIVITY = 0.8f;

    // Error toasts
    private final List<ErrorToast> errorToasts = new ArrayList<>();
    private boolean assignedSkinHintShown;

    // Mojang search widgets
    private EditBox usernameSearchField;
    private IconButton searchButton;
    private com.quickskin.mod.client.gui.widget.PixelIconButton sortButton;
    private com.quickskin.mod.client.gui.widget.PixelIconButton viewButton;
    private com.quickskin.mod.client.gui.widget.PixelIconButton deleteModeButton;
    // The delete mode: a click marks skins, and the Confirm button deletes them together. Kept on
    // the screen so it survives resizes and the confirmation dialog returning to this instance.
    private boolean deleteMode;
    private final Set<String> markedForDeletion = new LinkedHashSet<>();
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
        pollNicknameAnswer();
        // Once per opening: a skin an admin gave shows instead of what is picked here
        if (!assignedSkinHintShown) {
            assignedSkinHintShown = true;
            if (this.minecraft != null && this.minecraft.player != null
                    && com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                            .hasAssignedSkin(this.minecraft.player.getUUID())) {
                showToast(Component.translatable("quickskin.skin.assigned_hint"), ErrorToast.Kind.INFO);
            }
        }
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
        com.quickskin.mod.client.gui.widget.ConfirmButton styled =
                confirmButton instanceof com.quickskin.mod.client.gui.widget.ConfirmButton button ? button : null;
        if (deleteMode) {
            // The delete mode turns Confirm into a red Delete (N) for the marked skins
            int marked = markedForDeletion.size();
            if (styled != null) styled.setDanger(true);
            confirmButton.active = marked > 0;
            confirmButton.setMessage(Component.translatable("quickskin.button.delete_count", marked));
            confirmButton.setTooltip(null);
            return;
        }
        if (styled != null && styled.isDanger()) {
            styled.setDanger(false);
            confirmButton.active = false; // relabelled as Confirm below
        }
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
        // square Search button standing flat beside it. The field is as wide as both sides leave room
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
        // Rebuilt when a dialog returns here: the skins it deleted are still falling
        SkinListWidget previousList = skinListPanel == null ? null : skinListPanel.getSkinListWidget();
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
        if (previousList != null && skinListPanel.getSkinListWidget() != null) {
            skinListPanel.getSkinListWidget().continueAnimations(previousList);
        }

        // The header is as wide as its widest line and its two buttons, at least as wide as the tiles
        String widestCount = skinListPanel.size() + "/" + skinListPanel.size();
        int headerTextWidth = Math.max(this.font.width(Component.translatable("quickskin.menu.skins")),
                this.font.width(widestCount));
        skinColumnWidth = Math.max(SkinListWidget.widthFor(tileSize, skinColumns),
                headerTextWidth + 4 + 3 * HEADER_BUTTON_WIDTH + 2 * HEADER_BUTTON_GAP);
        // The header's buttons are drawn like Confirm, each with a pixel icon
        sortButton = new com.quickskin.mod.client.gui.widget.PixelIconButton(
                skinColumnX + skinColumnWidth - HEADER_BUTTON_WIDTH,
                skinHeaderY,
                HEADER_BUTTON_WIDTH,
                CONTROL_HEIGHT,
                sortIcon(getCurrentSortMode()),
                Component.literal(getCurrentSortMode().getIcon()),
                button -> cycleSortMode()
        );
        sortButton.setTooltip(Tooltip.create(
                Component.translatable("quickskin.tooltip.sorting", Component.translatable(getCurrentSortMode().getTranslationKey()))
        ));
        addRenderableWidget(sortButton);

        // Grid or list: the button shows the view it switches to
        Component viewLabel = Component.translatable(listRows ? "quickskin.tooltip.view_grid" : "quickskin.tooltip.view_list");
        viewButton = new com.quickskin.mod.client.gui.widget.PixelIconButton(
                skinColumnX + skinColumnWidth - 2 * HEADER_BUTTON_WIDTH - HEADER_BUTTON_GAP,
                skinHeaderY,
                HEADER_BUTTON_WIDTH,
                CONTROL_HEIGHT,
                listRows ? VIEW_GRID_ICON : VIEW_LIST_ICON,
                viewLabel,
                button -> toggleListRows()
        );
        viewButton.setTooltip(Tooltip.create(viewLabel));
        addRenderableWidget(viewButton);

        // The delete mode, left of the view button: red while the mode is on
        Component deleteLabel = Component.translatable("quickskin.tooltip.delete_mode");
        deleteModeButton = new com.quickskin.mod.client.gui.widget.PixelIconButton(
                skinColumnX + skinColumnWidth - 3 * HEADER_BUTTON_WIDTH - 2 * HEADER_BUTTON_GAP,
                skinHeaderY,
                HEADER_BUTTON_WIDTH,
                CONTROL_HEIGHT,
                DELETE_MODE_ICON,
                deleteLabel,
                button -> toggleDeleteMode()
        );
        updateDeleteModeButton();
        addRenderableWidget(deleteModeButton);

        // The list's cards and the import tile span the header, from "Skins" to the last button
        if (listRows && skinListPanel.getSkinListWidget() != null) {
            skinListPanel.setWidth(skinColumnWidth);
            skinListPanel.getSkinListWidget().setWidth(skinColumnWidth);
        }
        int importWidth = listRows ? skinColumnWidth : SkinListWidget.gridWidthFor(tileSize, skinColumns);
        importTile = new ImportTileButton(skinColumnX, skinListBottom + 4, importWidth,
                CONTROL_HEIGHT, button -> openImportDialog());
        importZoneShown = null; // a new tile: its tooltip is set on the first layout
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
        // Three rows from the shoulders down, the last well clear of the turntable ring
        int rowGap = ActionButtonsPanel.ICON_BUTTON_SIZE + 4;
        int shoulderY = Math.max(skinHeaderY, feetY - (int) (modelScale * 1.6f));
        int waistY = Math.max(shoulderY + rowGap, feetY - (int) (modelScale * 1.1f));
        int bottomY = Math.min(Math.max(waistY + rowGap, feetY - (int) (modelScale * 0.6f)),
                confirmY - ActionButtonsPanel.ICON_BUTTON_SIZE - 4);
        ActionButtonsPanel.Layout layout = new ActionButtonsPanel.Layout(
                leftX, rightX, shoulderY, waistY, bottomY, buttonWidth,
                confirmX, confirmY, confirmWidth, confirmHeight);
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
                this::onConfirmPressed,
                this::uploadPendingSkin
        );
        actionButtonsPanel = new ActionButtonsPanel(layout);
        actionButtonsPanel.init(this, layout, callbacks);
        for (Button button : actionButtonsPanel.buttons()) {
            playerPreviewPanel.addPriorityControl(button);
        }
        // The pose button is the right column's third button
        playerPreviewPanel.initPoseControl(this, rightX, bottomY, ActionButtonsPanel.ICON_BUTTON_SIZE, this.width);

        LinkButtonsPanel linkButtonsPanel = new LinkButtonsPanel(linkRowX, searchY);
        linkButtonsPanel.init(this);

        // The name above the model opens the nickname dialog; it is laid out every frame
        nameTagButton = new NameTagButton(button -> showNicknameDialog());
        nameTagTooltipFor = null;
        registerForegroundControl(nameTagButton);

        // Last, so it is drawn over every other control
        skinContextMenu = new com.quickskin.mod.client.gui.widget.SkinContextMenu(
                new com.quickskin.mod.client.gui.widget.SkinContextMenu.Actions() {
                    @Override
                    public void toggleFavorite(AssetMetadata metadata) {
                        PlayerSkinMenuScreen.this.toggleFavorite(metadata);
                    }

                    @Override
                    public void rename(AssetMetadata metadata) {
                        showRenameDialog(metadata);
                    }

                    @Override
                    public void delete(AssetMetadata metadata) {
                        deleteSkin(metadata);
                    }
                });
        registerForegroundControl(skinContextMenu);

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
    /** How opaque a ghost is under the mouse, how far it breathes either way, and in how long. */
    private static final float GHOST_ALPHA = 0.45f;
    private static final float GHOST_BREATH = 0.10f;
    private static final float GHOST_BREATH_SECONDS = 1.0f;
    /** How quickly a ghost fades in and out, as the time constant of an exponential approach. */
    private static final float GHOST_EASE_SECONDS = 0.05f;
    private static final int[] ARMOR_PIECES_BY_PRIORITY = {
            LocalHiddenArmor.HEAD, LocalHiddenArmor.CHEST, LocalHiddenArmor.FEET, LocalHiddenArmor.LEGS};
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
        // While a skin's menu is open nothing else lights up under the mouse: a click elsewhere
        // only closes the menu. The menu itself still follows the mouse.
        int menuMouseX = mouseX;
        int menuMouseY = mouseY;
        if (skinContextMenu != null && skinContextMenu.isOpen()) {
            mouseX = -10_000;
            mouseY = -10_000;
        }

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
        layoutImportZone();
        layoutNameTag();

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
            int controlMouseX = control == skinContextMenu ? menuMouseX : mouseX;
            int controlMouseY = control == skinContextMenu ? menuMouseY : mouseY;
//? if <26.1 {
            control.render(graphics, controlMouseX, controlMouseY, partialTick);
//?} else {
            control.extractRenderState(graphics, controlMouseX, controlMouseY, partialTick);
//?}
        }

        if (actionButtonsPanel != null) {
            actionButtonsPanel.renderLeftTooltips(graphics, this.font, mouseX, mouseY);
        }
        renderArmorGhost(graphics, mouseX, mouseY);

        // Render error toasts (on top of everything)
        renderErrorToasts(graphics);
//? if <1.21.6 {
        graphics.pose().popPose();
//?}
    }

    /**
     * The armour piece the model wears under the mouse, as a {@link LocalHiddenArmor} bit, or 0:
     * the model's height splits into the helmet, the chestplate, the leggings and the boots, each
     * about as wide as the part it covers. Only a piece the player has on counts, shown or hidden.
     */
    private int armorPieceAt(double mouseX, double mouseY) {
        if (this.minecraft == null || this.minecraft.player == null || playerPreviewPanel == null) {
            return 0;
        }
        com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
        if (widget == null || !widget.visible) {
            return 0;
        }
        if (!com.quickskin.mod.client.rendering.PreviewEquipment.outline(LocalHiddenArmor.HEAD, false).isEmpty()) {
            // A piece is where it is seen: the traced piece nearest the viewer under the mouse,
            // unless the model itself is nearer there (the chest in front of an elytra)
            int[] traced = new int[ARMOR_PIECES_BY_PRIORITY.length];
            int count = 0;
            for (int candidate : ARMOR_PIECES_BY_PRIORITY) {
                if (!armorItem(candidate).isEmpty()
                        && com.quickskin.mod.client.rendering.PreviewArmorSilhouette.of(candidate) != null) {
                    traced[count++] = candidate;
                }
            }
            int seen = com.quickskin.mod.client.rendering.PreviewArmorSilhouette.pieceAt(
                    mouseX, mouseY, java.util.Arrays.copyOf(traced, count));
            if (seen != 0) {
                return seen;
            }
            boolean onModel = com.quickskin.mod.client.rendering.PreviewArmorSilhouette.bodyCovers(mouseX, mouseY);
            // Beside the model, a piece's outline still counts, and a piece that could not be
            // traced keeps its boxes; the head first and the boots before the leggings
            for (int candidate : ARMOR_PIECES_BY_PRIORITY) {
                if (armorItem(candidate).isEmpty()) {
                    continue;
                }
                com.quickskin.mod.client.rendering.PreviewArmorSilhouette silhouette =
                        com.quickskin.mod.client.rendering.PreviewArmorSilhouette.of(candidate);
                if (silhouette != null ? !onModel && silhouette.contains(mouseX, mouseY)
                        : insideAny(armorOutline(candidate), mouseX, mouseY, -1)) {
                    return candidate;
                }
            }
            return 0;
        }
        float height = widget.getEffectiveScale() * MODEL_HEIGHT_PER_SCALE;
        if (height <= 0) {
            return 0;
        }
        double fromTop = (mouseY - (widget.getLayoutFeetY() - height)) / height;
        double sideways = Math.abs(mouseX - widget.getLayoutCenterX()) / height;
        int piece;
        double halfWidth;
        if (fromTop < 0 || fromTop > 1) {
            return 0;
        } else if (fromTop < 0.25) {
            piece = LocalHiddenArmor.HEAD;
            halfWidth = 0.16;
        } else if (fromTop < 0.6) {
            piece = LocalHiddenArmor.CHEST;
            halfWidth = 0.28;
        } else if (fromTop < 0.84) {
            piece = LocalHiddenArmor.LEGS;
            halfWidth = 0.14;
        } else {
            piece = LocalHiddenArmor.FEET;
            halfWidth = 0.15;
        }
        return sideways <= halfWidth && !armorItem(piece).isEmpty() ? piece : 0;
    }

    /** The outline of a worn piece on the model, one convex polygon per box it covers. */
    private java.util.List<float[]> armorOutline(int piece) {
        boolean elytra = piece == LocalHiddenArmor.CHEST
                && armorItem(piece).is(net.minecraft.world.item.Items.ELYTRA);
        return com.quickskin.mod.client.rendering.PreviewEquipment.outline(piece, elytra);
    }

    /** Whether a point lies in one of the convex polygons, the one at {@code skip} aside. */
    private static boolean insideAny(java.util.List<float[]> polygons, double x, double y, int skip) {
        for (int p = 0; p < polygons.size(); p++) {
            if (p == skip) {
                continue;
            }
            float[] polygon = polygons.get(p);
            int n = polygon.length / 2;
            int sign = 0;
            boolean inside = n >= 3;
            for (int i = 0; i < n && inside; i++) {
                double ax = polygon[i * 2], ay = polygon[i * 2 + 1];
                double bx = polygon[(i + 1) % n * 2], by = polygon[(i + 1) % n * 2 + 1];
                double cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
                int s = cross > 0 ? 1 : cross < 0 ? -1 : 0;
                if (s != 0) {
                    if (sign == 0) {
                        sign = s;
                    } else if (s != sign) {
                        inside = false;
                    }
                }
            }
            if (inside) {
                return true;
            }
        }
        return false;
    }

    /** What the player has on in a piece's slot. */
    private net.minecraft.world.item.ItemStack armorItem(int piece) {
        net.minecraft.world.entity.EquipmentSlot slot = switch (piece) {
            case LocalHiddenArmor.HEAD -> net.minecraft.world.entity.EquipmentSlot.HEAD;
            case LocalHiddenArmor.CHEST -> net.minecraft.world.entity.EquipmentSlot.CHEST;
            case LocalHiddenArmor.LEGS -> net.minecraft.world.entity.EquipmentSlot.LEGS;
            default -> net.minecraft.world.entity.EquipmentSlot.FEET;
        };
        return this.minecraft.player.getItemBySlot(slot);
    }

    /** Hides a shown piece from everyone, or shows a hidden one, where the server can. */
    private void toggleArmorPiece(int piece) {
        ClientNetworkActions.ArmorHiding hiding = LocalHiddenArmor.hiding();
        if (hiding == ClientNetworkActions.ArmorHiding.ACTIVE) {
            LocalHiddenArmor.toggle(piece);
        } else if (hiding == ClientNetworkActions.ArmorHiding.NOT_ALLOWED) {
            showError(Component.translatable("quickskin.armor.not_allowed"));
        } else if (hiding == ClientNetworkActions.ArmorHiding.UNSUPPORTED) {
            showError(Component.translatable("quickskin.armor.unsupported"));
        }
    }

    /**
     * Turns the armour piece under the mouse into a see-through ghost of itself on the model, so
     * the skin beneath shows, as hiding it would; a hidden piece appears as the same ghost. It fades
     * in, breathes a little while the mouse stays, and fades back to how the piece is drawn when the
     * mouse leaves; a piece just clicked fades to its new state and stays so until the mouse leaves
     * it. A faint edge and a light tint over the piece's own silhouette keep the ghost readable.
     * The model draws the ghost from the next frame on ({@code PreviewEquipment.setGhost}).
     */
//? if <26.1 {
    private void renderArmorGhost(GuiGraphics graphics, int mouseX, int mouseY) {
//?} else {
    private void renderArmorGhost(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
//?}
        boolean onMenu = !rotatingModel && this.minecraft != null && GuiCompat.currentScreen() == this;
        int hovered = onMenu ? armorPieceAt(mouseX, mouseY) : 0;
        long now = System.nanoTime();
        float elapsed = ghostFrameNanos == 0 ? 0.0f : Math.min(0.1f, (now - ghostFrameNanos) / 1.0e9f);
        ghostFrameNanos = now;
        if (hovered != ghostSettledPiece) {
            ghostSettledPiece = 0;
        }
        boolean ghosting = hovered != 0 && hovered != ghostSettledPiece;
        if (ghosting && hovered != ghostPiece) {
            // A new piece starts from how it is drawn now
            ghostPiece = hovered;
            ghostAlpha = restingAlpha(hovered);
            ghostSinceNanos = now;
        }
        if (ghostPiece == 0) {
            com.quickskin.mod.client.rendering.PreviewEquipment.setGhost(0, 1.0f);
            return;
        }
        boolean held = ghosting && hovered == ghostPiece;
        float target = held
                ? GHOST_ALPHA + GHOST_BREATH * (float) Math.sin(2.0 * Math.PI * ((now - ghostSinceNanos) / 1.0e9f) / GHOST_BREATH_SECONDS)
                : restingAlpha(ghostPiece);
        ghostAlpha += (target - ghostAlpha) * (1.0f - (float) Math.exp(-elapsed / GHOST_EASE_SECONDS));
        if (!held && Math.abs(ghostAlpha - target) < 0.01f) {
            ghostPiece = 0;
            ghostAlpha = 1.0f;
            com.quickskin.mod.client.rendering.PreviewEquipment.setGhost(0, 1.0f);
            return;
        }
        com.quickskin.mod.client.rendering.PreviewEquipment.setGhost(ghostPiece, ghostAlpha);

        // How far the piece is from how it rests, 0 to 1
        float rest = restingAlpha(ghostPiece);
        float ghostliness = Math.min(1.0f, Math.abs(rest - ghostAlpha) / Math.abs(rest - GHOST_ALPHA));
        com.quickskin.mod.client.rendering.PreviewArmorSilhouette silhouette =
                com.quickskin.mod.client.rendering.PreviewArmorSilhouette.of(ghostPiece);
        if (silhouette == null || ghostliness <= 0.01f || this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        // Only where the ghost is seen: not where the model or another piece drawn on it is nearer
        int[] drawn = new int[ARMOR_PIECES_BY_PRIORITY.length];
        int count = 0;
        for (int piece : ARMOR_PIECES_BY_PRIORITY) {
            if (piece != ghostPiece && restingAlpha(piece) > 0.0f && !armorItem(piece).isEmpty()) {
                drawn[count++] = piece;
            }
        }
        com.quickskin.mod.client.rendering.PreviewArmorSilhouette.Seen seen =
                silhouette.seen(java.util.Arrays.copyOf(drawn, count));
        int tint = Math.round(0.12f * ghostliness * 255) << 24 | 0xD2F0FF;
        int[] spans = seen.spans();
        for (int i = 0; i < spans.length; i += 3) {
            graphics.fill(spans[i], spans[i + 2], spans[i + 1], spans[i + 2] + 1, tint);
        }
        int edge = Math.round(0.30f * ghostliness * 255) << 24 | 0xE1F5FF;
        int[] ring = seen.ring();
        for (int i = 0; i < ring.length; i += 2) {
            graphics.fill(ring[i], ring[i + 1], ring[i] + 1, ring[i + 1] + 1, edge);
        }
    }

    /** How opaque a piece is drawn when no ghost: hidden from everyone, it is not drawn at all. */
    private static float restingAlpha(int piece) {
        return (LocalHiddenArmor.inEffect() & piece) != 0 ? 0.0f : 1.0f;
    }

    /**
     * "Skins" above the skin column, and under it the selected position and the number of skins,
     * beside the delete mode, view and sort buttons.
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
        UiText.draw(graphics, this.font, label, skinColumnX, labelY, 0xFFFFFFFF);
        UiText.draw(graphics, this.font, count, skinColumnX, countY, 0xFFC8C8C8);
    }

    /**
     * Places the player's name above the model, like a name tag in the world: their nickname, or
     * their account name. The tag opens the nickname dialog, except during a replay or for an
     * account whose identity cannot be stored.
     */
    private void layoutNameTag() {
        if (nameTagButton == null) {
            return;
        }
        com.quickskin.mod.client.gui.widget.PlayerWidget widget =
                playerPreviewPanel != null ? playerPreviewPanel.getPlayerWidget() : null;
        nameTagButton.visible = widget != null && widget.visible && this.minecraft != null;
        if (!nameTagButton.visible) {
            return;
        }
        String fullName = LocalNicknameService.shownName();
        int centerX = widget.getLayoutCenterX();
        // A long nickname is cut to the room between the skin column and the right edge, pencil
        // included, so the tag never covers another control
        int leftRoom = centerX - (skinColumnX + skinColumnWidth + SIDE_GAP);
        int rightRoom = this.width - MARGIN - NameTagButton.PENCIL_ROOM - centerX;
        int maxTextWidth = (int) (2 * Math.max(0, Math.min(leftRoom, rightRoom)) / NAME_TAG_SCALE) - 4;
        String name = fullName;
        if (this.font.width(fullName) > maxTextWidth) {
            name = this.font.plainSubstrByWidth(fullName, Math.max(0, maxTextWidth - this.font.width("\u2026")))
                    .stripTrailing() + "\u2026";
        }
        int headTop = widget.getLayoutFeetY() - (int) (widget.getEffectiveScale() * MODEL_HEIGHT_PER_SCALE);
        // The name's capitals sit halfway between the search row and the head
        float middle = (searchBottom + headTop) / 2.0f;
        nameTagButton.place(this.font, name, centerX, middle - NAME_TAG_CAPITALS_MIDDLE * NAME_TAG_SCALE);
        // An admin's name or lock leaves nothing to choose, so the tag only says why
        ClientNetworkActions.NicknameAnswer.Outcome fixedBy = LocalNicknameService.fixedByAdmin();
        nameTagButton.active = LocalNicknameService.canChoose() && fixedBy == null
                && !com.quickskin.mod.client.compat.ReplayModHelper.isInReplay();
        String tooltipFor = fullName + '\u0000' + nameTagButton.active + '\u0000' + fixedBy;
        if (!tooltipFor.equals(nameTagTooltipFor)) {
            nameTagTooltipFor = tooltipFor;
            if (fixedBy != null) {
                nameTagButton.setTooltip(Tooltip.create(Component.translatable(
                        fixedBy == ClientNetworkActions.NicknameAnswer.Outcome.ASSIGNED
                                ? "quickskin.nickname.assigned" : "quickskin.nickname.locked")));
            } else if (!nameTagButton.active) {
                // A tag that cannot change says nothing a click would not do
                nameTagButton.setTooltip(null);
            } else {
                MutableComponent tooltip = Component.empty();
                if (!name.equals(fullName)) {
                    tooltip.append(fullName).append("\n");
                }
                tooltip.append(Component.translatable("quickskin.nickname.tooltip"));
                String accountName = LocalNicknameService.accountName();
                if (!fullName.equals(accountName)) {
                    tooltip.append("\n").append(Component.translatable("quickskin.nickname.tooltip_account", accountName));
                }
                nameTagButton.setTooltip(Tooltip.create(tooltip));
            }
        }
    }

    /** Opens the dialog where the player picks the nickname others see them by. */
    private void showNicknameDialog() {
        if (minecraft == null) {
            return;
        }
//? if <26.2 {
        minecraft.setScreen(new NicknameScreen(
//?} else {
        minecraft.gui.setScreen(new NicknameScreen(
//?}
                this,
                LocalNicknameService.accountName(),
                LocalNicknameService.nickname(),
                // The dialog returns to this menu right after the callback
                this::chooseNickname));
    }

    /** Keeps the nickname for this account and tells the player where it shows. */
    private void chooseNickname(String typed) {
        ClientNetworkActions.NicknameDelivery delivery = LocalNicknameService.choose(typed);
        nameTagTooltipFor = null;
        if (delivery == null) {
            showError(Component.translatable("quickskin.nickname.invalid"));
            return;
        }
        boolean hasNickname = !LocalNicknameService.nickname().isEmpty();
        switch (delivery) {
            case SENT, PENDING -> awaitingNicknameAnswer = true;
            case NOT_CONNECTED -> {
                if (hasNickname) {
                    showToast(Component.translatable("quickskin.nickname.saved_offline"), ErrorToast.Kind.INFO);
                }
            }
            case UNSUPPORTED -> {
                if (hasNickname) {
                    showToast(Component.translatable("quickskin.nickname.unsupported"), ErrorToast.Kind.INFO);
                }
            }
            case DISABLED -> showError(Component.translatable("quickskin.nickname.disabled"));
        }
    }

    /**
     * Shows each answer of the server to the player's nickname once: every answer to a nickname
     * this menu sent, and a refusal of one sent by itself (when joining, or after a wait), which
     * the player would not hear about otherwise.
     */
    private void pollNicknameAnswer() {
        ClientNetworkActions.NicknameAnswer answer = LocalNicknameService.lastAnswer();
        if (answer == null || answer.sequence() == nicknameAnswerShown) {
            return;
        }
        nicknameAnswerShown = answer.sequence();
        nameTagTooltipFor = null;
        ClientNetworkActions.NicknameAnswer.Outcome outcome = answer.outcome();
        boolean refused = outcome != ClientNetworkActions.NicknameAnswer.Outcome.ACCEPTED
                && outcome != ClientNetworkActions.NicknameAnswer.Outcome.UNCHANGED
                && outcome != ClientNetworkActions.NicknameAnswer.Outcome.COOLDOWN;
        if (!awaitingNicknameAnswer && !refused) {
            return;
        }
        // After a wait the nickname is sent again by itself, and that answer still comes here
        awaitingNicknameAnswer = outcome == ClientNetworkActions.NicknameAnswer.Outcome.COOLDOWN;
        switch (outcome) {
            case ACCEPTED -> showToast(answer.nickname().isEmpty()
                            ? Component.translatable("quickskin.nickname.cleared")
                            : Component.translatable("quickskin.nickname.accepted", answer.nickname()),
                    ErrorToast.Kind.SUCCESS);
            case UNCHANGED -> {
            }
            case TAKEN -> showError(Component.translatable("quickskin.nickname.taken"));
            case INVALID -> showError(Component.translatable("quickskin.nickname.refused"));
            case COOLDOWN -> showToast(Component.translatable("quickskin.nickname.cooldown",
                    Math.max(1L, (answer.retryAfterMillis() + 999L) / 1000L)), ErrorToast.Kind.INFO);
            case DISABLED -> showError(Component.translatable("quickskin.nickname.disabled"));
            case LOCKED -> showError(Component.translatable("quickskin.nickname.locked"));
            case ASSIGNED -> showError(Component.translatable("quickskin.nickname.assigned"));
        }
    }

    @Override
    public void removed() {
        BackgroundRenderer.cleanup();
        com.quickskin.mod.client.rendering.PreviewEquipment.forgetPreview();
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
        // ESC closes a skin's menu, leaves the delete mode, and otherwise closes the menu
        if (keyCode == InputConstants.KEY_ESCAPE) {
            if (skinContextMenu != null && skinContextMenu.isOpen()) {
                skinContextMenu.close();
            } else if (deleteMode) {
                setDeleteMode(false);
            } else {
                this.onClose();
            }
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
        if (playerPreviewPanel != null) {
            playerPreviewPanel.closePoseListUnlessOver(mouseX, mouseY);
        }
        // A click on a piece of armour the model wears hides or shows it once released in place
        armorPressPiece = button == InputConstants.MOUSE_BUTTON_LEFT ? armorPieceAt(mouseX, mouseY) : 0;
        armorPressX = mouseX;
        armorPressY = mouseY;
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
        // An open skin menu takes the click first, if only to close itself
        if (skinContextMenu != null && skinContextMenu.isOpen()) {
            if (click.test(skinContextMenu)) {
                this.setFocused(skinContextMenu);
                return true;
            }
            return false;
        }
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
        if (button == InputConstants.MOUSE_BUTTON_LEFT && armorPressPiece != 0) {
            int piece = armorPressPiece;
            armorPressPiece = 0;
            // A press that turned the model is no click
            if (Math.abs(mouseX - armorPressX) <= 2 && Math.abs(mouseY - armorPressY) <= 2) {
                toggleArmorPiece(piece);
                // It shows its new state until the mouse leaves it
                ghostSettledPiece = piece;
            }
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
            showError(Component.translatable("quickskin.error.unsupported_format"));
            return;
        }

//? if <1.21.11 {
        // Check CPM availability for .cpmmodel files
        if (!cpmModelFiles.isEmpty() && !com.quickskin.mod.client.compat.CPMCompatIntegration.isAvailable()) {
//?} else {
        if (!cpmModelFiles.isEmpty()
                && !com.quickskin.mod.client.compat.CPMCompatIntegration.isAvailable()) {
//?}
            showError(Component.translatable("quickskin.error.cpm_required"));
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
                    imported.forEach(PlayerSkinMenuScreen::relistOwnSkin);
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
     * Called when the player clicks a skin in the list: previews it and wears it at once, unless
     * the server's skin-change cooldown is running, when it is only previewed until Confirm.
     */
    public void onSkinClicked(SkinEntry entry) {
        onSkinSelected(entry);
        if (entry != null && canApplyNow() && applySelection()) {
            changedThisVisit = true;
        }
    }

    /**
     * Called when the menu selects a skin itself (on opening, after an import, a rename or a
     * delete): previews it. Nothing is applied until a click or Confirm.
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
        // Like a click on a skin, the new arm width is worn at once
        if (canApplyNow() && applySelection()) {
            changedThisVisit = true;
        }
    }

    /**
     * Whether a click can wear a look now: always in singleplayer, otherwise outside the cooldown.
     * A server with a cooldown takes one change per visit to the menu, since its cooldown only
     * reaches the client after the server accepted that change; later clicks only preview.
     */
    private boolean canApplyNow() {
//? if <26.2 {
        boolean singleplayer = this.minecraft != null && this.minecraft.isSingleplayer();
//?} else {
        boolean singleplayer = this.minecraft != null && this.minecraft.hasSingleplayerServer();
//?}
        if (singleplayer) {
            return true;
        }
        com.quickskin.mod.config.ServerConfig server = com.quickskin.mod.config.ClientConfig.getInstance().getServerOverride();
        boolean serverHasCooldown = server != null && server.skinChangeCooldownSeconds > 0;
        return !CooldownService.getInstance().isCooldownActive() && !(serverHasCooldown && changedThisVisit);
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

    /** The Confirm button: deletes the marked skins in the delete mode, and otherwise confirms. */
    private void onConfirmPressed() {
        if (deleteMode) {
            showBulkDeleteConfirmation();
        } else {
            confirmSelection();
        }
    }

    /** Whether the delete mode is on. */
    public boolean isDeleteMode() {
        return deleteMode;
    }

    /** Whether a skin is marked for deletion. */
    public boolean isMarkedForDeletion(AssetMetadata metadata) {
        return markedForDeletion.contains(metadata.hash());
    }

    /**
     * Marks or unmarks a skin for deletion. A skin can be marked only while another listed skin
     * stays unmarked: the list always keeps at least one skin.
     */
    public void toggleMarkedForDeletion(AssetMetadata metadata) {
        if (!markedForDeletion.contains(metadata.hash())) {
            Set<String> marked = new LinkedHashSet<>(markedForDeletion);
            marked.add(metadata.hash());
            if (!LocalAssetManager.getInstance().keepsAListedSkin(marked)) {
                showError(Component.translatable("quickskin.error.delete_last_skin"));
                return;
            }
        }
        if (!markedForDeletion.remove(metadata.hash())) {
            markedForDeletion.add(metadata.hash());
        }
        playClick();
        updateConfirmButtonState();
    }

    private void toggleDeleteMode() {
        setDeleteMode(!deleteMode);
    }

    /** Turns the delete mode on or off; either way no skin is left marked. */
    private void setDeleteMode(boolean on) {
        deleteMode = on;
        updateDeleteModeButton();
        markedForDeletion.clear();
        playClick();
        updateConfirmButtonState();
    }

    /** The delete mode's button: the bin, or while the mode is on a red button with an X to cancel it. */
    private void updateDeleteModeButton() {
        if (deleteModeButton == null) {
            return;
        }
        deleteModeButton.setDanger(deleteMode);
        deleteModeButton.setIcon(deleteMode ? CANCEL_DELETE_ICON : DELETE_MODE_ICON);
        Component label = Component.translatable(deleteMode ? "quickskin.button.cancel" : "quickskin.tooltip.delete_mode");
        deleteModeButton.setMessage(label);
        deleteModeButton.setTooltip(Tooltip.create(label));
    }

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f));
        }
    }

    /** The faces of skins for the Delete dialog; a skin whose texture is not loaded has none. */
    private List<DeletionConfirmScreen.Preview> deletePreviews(List<AssetMetadata> skins) {
        List<DeletionConfirmScreen.Preview> previews = new ArrayList<>();
        for (AssetMetadata metadata : skins) {
            DeletionConfirmScreen.Preview preview = deletePreview(metadata);
            if (preview != null) previews.add(preview);
        }
        return previews;
    }

    /** A skin's face for the Delete dialog, or a CPM model's whole icon. */
    @Nullable
    private DeletionConfirmScreen.Preview deletePreview(AssetMetadata metadata) {
        var texture = LocalAssetManager.getInstance().getTextureLocation(metadata.hash(), TextureQuality.PREVIEW);
        if (texture == null) {
            return null;
        }
        if (metadata.isCpmModel() || metadata.resolution() == null) {
            return new DeletionConfirmScreen.Preview(texture, 64, 64, true);
        }
        return new DeletionConfirmScreen.Preview(texture,
                metadata.resolution().getWidth(), metadata.resolution().getHeight(), false);
    }

    /** Asks before deleting every marked skin that still exists, then leaves the delete mode. */
    private void showBulkDeleteConfirmation() {
        if (minecraft == null) return;
        List<AssetMetadata> skins = new ArrayList<>();
        for (String hash : markedForDeletion) {
            AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(hash);
            if (metadata != null) skins.add(metadata);
        }
        if (skins.isEmpty()) {
            markedForDeletion.clear();
            return;
        }
        Component title = Component.translatable(skins.size() == 1
                ? "quickskin.screen.delete.title" : "quickskin.screen.delete_many.title");
        Component message = skins.size() == 1
                ? Component.translatable("quickskin.dialog.confirm_delete", truncateFileName(skins.get(0).friendlyName()))
                : Component.translatable("quickskin.dialog.confirm_delete_many", skins.size());
//? if <26.2 {
        minecraft.setScreen(new DeletionConfirmScreen(
//?} else {
        minecraft.gui.setScreen(new DeletionConfirmScreen(
//?}
                this,
                title,
                message,
                (confirmed) -> {
                    if (confirmed) {
                        deleteSkins(skins);
                        deleteMode = false;
                        markedForDeletion.clear();
                    }
                    if (minecraft != null) {
//? if <26.2 {
                        minecraft.setScreen(this);
//?} else {
                        minecraft.gui.setScreen(this);
//?}
                    }
                },
                deletePreviews(skins)
        ));
    }

    /**
     * Confirm: applies the previewed look, when a click has not already (a searched player, or a
     * skin chosen during the cooldown), and closes the menu.
     */
    private void confirmSelection() {
        applySelection();
        onClose();
    }

    /**
     * Applies the pending look exactly as choosing it in the list used to: a CPM model through CPM,
     * a skin and its arm width to the player and the saved selection.
     *
     * @return whether the look changed
     */
    private boolean applySelection() {
        if (pendingHash == null) {
            return false;
        }
        AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(pendingHash);
        if (metadata == null) {
            return false;
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
                showError(Component.translatable("quickskin.error.cpm_select_failed"));
                return false;
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
                if (targetUUID != null && appearances.hasOwnActiveSkin(targetUUID)) {
                    appearances.applySkin(targetUUID, "", null);
                }
            }
            return !modelIsTheLook;
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
            return false;
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
        return true;
    }

    /**
     * Get the font renderer
     */
    public net.minecraft.client.gui.Font getFont() {
        return this.font;
    }

    /**
     * Opens the menu of a skin the player right-clicked in the skin column: favorite, rename and
     * delete. Not in the delete mode, where clicks mark skins.
     */
    public void openSkinContextMenu(AssetMetadata metadata, int mouseX, int mouseY) {
        if (skinContextMenu == null || deleteMode) {
            return;
        }
        skinContextMenu.open(metadata, isPlayerOwnSkin(metadata),
                LocalAssetManager.getInstance().isFavorite(metadata.hash()), mouseX, mouseY, width, height);
    }

    /** Closes the skin menu, as the column scrolls under it. */
    public void closeSkinContextMenu() {
        if (skinContextMenu != null) {
            skinContextMenu.close();
        }
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
                        showError(Component.translatable("quickskin.error.cpm_required"));
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
     * Marks or unmarks a skin as a favorite. Favorites are listed first after the player's own
     * skin, so the list is rebuilt; the selection stays, and the list scrolls to the skin's new place.
     */
    public void toggleFavorite(AssetMetadata metadata) {
        LocalAssetManager assets = LocalAssetManager.getInstance();
        boolean favorite = !assets.isFavorite(metadata.hash());
        assets.setFavorite(metadata.hash(), favorite);
        if (skinListPanel == null) {
            return;
        }
        refreshSkinList();
        if (favorite) {
            skinListPanel.celebrateFavorite(metadata.hash());
        }
        skinListPanel.reveal(metadata.hash());
    }

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

    /** Shows a toast of the given kind, such as a confirmation. */
    public void showToast(Component message, ErrorToast.Kind kind) {
        errorToasts.add(new ErrorToast(message, kind));
    }

    /** The import zone's least height that holds its icon and text; below it, it is a bar. */
    private static final int IMPORT_ZONE_MIN_HEIGHT = 64;

    /**
     * The import zone fills the column under the last skin. While the skins leave at least the
     * zone's least height free, the list is only as tall as they are and the zone takes the rest;
     * otherwise the list fills the column, scrolling, and the zone is the bar at its bottom.
     */
    private void layoutImportZone() {
        if (skinListPanel == null || importTile == null || skinListPanel.getSkinListWidget() == null) {
            return;
        }
        SkinListWidget list = skinListPanel.getSkinListWidget();
        int listTop = list.getY();
        int columnBottom = skinListBottom + 4 + CONTROL_HEIGHT;
        int fullHeight = skinListBottom - listTop;
        int content = list.contentHeight();
        boolean zone = columnBottom - (listTop + content + 4) >= IMPORT_ZONE_MIN_HEIGHT;
        if (zone) {
            list.setViewportHeight(content);
            importTile.setBounds(listTop + content + 4, columnBottom - (listTop + content + 4));
        } else {
            list.setViewportHeight(fullHeight);
            importTile.setBounds(skinListBottom + 4, CONTROL_HEIGHT);
        }
        // The zone says what it does; the bar, a plus alone, keeps its tooltip
        if (importZoneShown == null || zone != importZoneShown) {
            importZoneShown = zone;
            importTile.setTooltip(zone ? null : Tooltip.create(Component.translatable("quickskin.tooltip.import_skin")));
        }
    }

    /** Space between the toasts, and between them and the screen's corner. */
    private static final int TOAST_GAP = 4;
    private static final int TOAST_MARGIN = 8;

    /** The toasts in the bottom-right corner, the newest at the bottom and the older ones above. */
//? if <26.1 {
    private void renderErrorToasts(GuiGraphics graphics) {
//?} else {
    private void renderErrorToasts(GuiGraphicsExtractor graphics) {
//?}
        errorToasts.removeIf(ErrorToast::isExpired);
        int x = width - TOAST_MARGIN - ErrorToast.WIDTH;
        int bottom = height - TOAST_MARGIN;
        for (int i = errorToasts.size() - 1; i >= 0; i--) {
            ErrorToast toast = errorToasts.get(i);
            int top = bottom - toast.height();
            toast.render(graphics, x, top);
            bottom = top - TOAST_GAP;
        }
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
                deletePreviews(List.of(metadata))
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

    private static boolean isPlayerOwnSkin(AssetMetadata metadata) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        return config.enablePlayerOwnSkinSystem && metadata.hash().equals(config.playerOwnSkinHash);
    }

    /** Importing or searching the hidden own skin again lists it again: the player asked for it. */
    private static void relistOwnSkin(AssetMetadata metadata) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (config.playerOwnSkinHidden && isPlayerOwnSkin(metadata)) {
            config.playerOwnSkinHidden = false;
            config.save();
        }
    }

    /** Delete a skin from local storage. */
    private void deleteSkin(AssetMetadata metadata) {
        deleteSkins(List.of(metadata));
    }

    /**
     * Delete skins from local storage, then rescan the catalog once. The list always keeps at
     * least one skin: a deletion that would leave it empty is refused whole. The player's own skin
     * is never deleted, only hidden from the list. A skin that cannot be deleted shows an error and
     * the others are still deleted.
     */
    private void deleteSkins(List<AssetMetadata> skins) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        LocalAssetManager assets = LocalAssetManager.getInstance();
        if (!assets.keepsAListedSkin(skins.stream().map(AssetMetadata::hash).toList())) {
            showError(Component.translatable("quickskin.error.delete_last_skin"));
            return;
        }
        boolean deletedAny = false;
        boolean wasApplied = false;
        boolean hideOwnSkin = false;
        if (skinListPanel != null) {
            skinListPanel.prepareDeparture(skins.stream().map(AssetMetadata::hash).toList());
        }
        for (AssetMetadata metadata : skins) {
            if (isPlayerOwnSkin(metadata)) {
                hideOwnSkin = true;
                continue;
            }
            try {
                if (!assets.deleteAsset(metadata.hash())) {
                    showError(Component.translatable("quickskin.error.delete_failed", metadata.friendlyName()));
                    continue;
                }
            } catch (RuntimeException e) {
                QuickSkinInfo.LOGGER.warn("Unable to delete skin {}", metadata.friendlyName(), e);
                showError(Component.translatable("quickskin.error.delete_failed", metadata.friendlyName()));
                continue;
            }
            deletedAny = true;
            wasApplied |= metadata.hash().equals(config.activeSkinHash);
            if (metadata.hash().equals(pendingHash)) {
                pendingHash = null;
                pendingModelType = null;
            }
        }

        try {
            if (deletedAny) {
                assets.discoverLocalAssets();
            }
            // The own skin stays hidden while another skin is listed; should none be left (its
            // files removed outside the game), it is listed again for good
            boolean othersLeft = assets.hasSkinsBesidesOwnSkin();
            boolean hidden = config.playerOwnSkinHidden;
            config.playerOwnSkinHidden = othersLeft && (hidden || hideOwnSkin);
            if (config.playerOwnSkinHidden != hidden) {
                config.save();
            } else if (!deletedAny) {
                return;
            }

            playClick();
            refreshSkinList();

            // Deleting the skin that is worn wears the first listed skin straight away (the own
            // skin, pinned first, when it is listed), so the look never names a file that no
            // longer exists. Otherwise the worn skin stays selected, when it is listed.
            List<AssetMetadata> listed = assets.getAllSkins();
            if (skinListPanel != null && !listed.isEmpty()) {
                if (wasApplied) {
                    AssetMetadata next = listed.stream().filter(meta -> !meta.isCpmModel())
                            .findFirst().orElse(listed.get(0));
                    skinListPanel.setSelected(next, true);
                    applySelection();
                } else {
                    AssetMetadata worn = config.activeSkinHash.isEmpty()
                            ? null : assets.getMetadata(config.activeSkinHash);
                    if (worn != null && listed.stream().anyMatch(meta -> meta.hash().equals(worn.hash()))) {
                        skinListPanel.setSelected(worn, true);
                    }
                }
            }
        } catch (RuntimeException e) {
            // The skins are deleted; only refreshing the list after them failed
            QuickSkinInfo.LOGGER.warn("Unable to refresh the skin list after deleting skins", e);
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
        searchButton.setLoading(true);
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
            case RATE_LIMITED -> Component.translatable("quickskin.error.mojang_rate_limited");
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
        long start = System.nanoTime();
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
                        relistOwnSkin(metadata);
                        // Refresh the skin list
                        refreshSkinList();

                        // Auto-select the imported skin
                        if (skinListPanel != null) {
                            skinListPanel.setSelected(metadata);
                        }

                        // Clear the search field
                        usernameSearchField.setValue("");
                        QuickSkinInfo.LOGGER.info("Skin of '{}' saved and listed in {} ms",
                                skinData.username, (System.nanoTime() - start) / 1_000_000L);

                        // Play success sound
                        if (minecraft != null) {
                            minecraft.getSoundManager().play(
                                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                            SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                                    )
                            );
                        }
                    } else {
                        QuickSkinInfo.LOGGER.warn("The saved skin of '{}' has no catalogue entry", skinData.username);
                        showError(Component.translatable("quickskin.error.save_searched_skin", skinData.username));
                    }
                } else {
                    QuickSkinInfo.LOGGER.warn("Unable to hash the saved skin of '{}'", skinData.username);
                    showError(Component.translatable("quickskin.error.save_searched_skin", skinData.username));
                }
            } else {
                QuickSkinInfo.LOGGER.warn("Unable to save the skin of '{}'", skinData.username);
                showError(Component.translatable("quickskin.error.save_searched_skin", skinData.username));
            }
        } catch (Exception e) {
            QuickSkinInfo.LOGGER.warn("Unable to save the skin of '{}'", skinData.username, e);
            showError(Component.translatable("quickskin.error.save_searched_skin", skinData.username));
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
            searchButton.setLoading(false);
            searchButton.setTooltip(Tooltip.create(Component.translatable("quickskin.button.search")));
            searchButton.active = usernameSearchField != null && !usernameSearchField.getValue().trim().isEmpty();
        }
    }

    /**
     * Get current skin sort mode
     */
    /** Space between the header's buttons. */
    private static final int HEADER_BUTTON_GAP = 2;
    /** Width of the header's buttons: one more than their height, to centre their icons. */
    private static final int HEADER_BUTTON_WIDTH = CONTROL_HEIGHT + 1;
    /**
     * The header buttons' icons, nine pixels square. The buttons are one pixel wider than they
     * are tall, so an odd-width icon centres exactly on the odd face inside the border. The view
     * button's: four tiles, or three rows like the list's cards, each a square beside a line and a
     * shorter line under it.
     */
    private static final String[] VIEW_GRID_ICON = {
            "####.####", "####.####", "####.####", "####.####", ".........",
            "####.####", "####.####", "####.####", "####.####"};
    private static final String[] VIEW_LIST_ICON = {
            "##.######", "##.####..", ".........", "##.######", "##.####..",
            ".........", "##.######", "##.####.."};
    /** The sort button's icons: an arrow toward the newest skin's end, or A over Z beside an arrow. */
    private static final String[] SORT_DOWN_ICON = {
            "....#....", "....#....", "....#....", "....#....", "....#....",
            ".#######.", "..#####..", "...###...", "....#...."};
    private static final String[] SORT_UP_ICON = {
            "....#....", "...###...", "..#####..", ".#######.", "....#....",
            "....#....", "....#....", "....#....", "....#...."};
    private static final String[] SORT_ALPHABETICAL_ICON = {
            ".#....#..", "#.#...#..", "###...#..", "#.#...#..", "###...#..",
            "..#...#..", ".#..#####", "#....###.", "###...#.."};

    private static String[] sortIcon(SkinSortMode mode) {
        return switch (mode) {
            case LATEST_LAST -> SORT_DOWN_ICON;
            case LATEST_FIRST -> SORT_UP_ICON;
            case ALPHABETICAL -> SORT_ALPHABETICAL_ICON;
        };
    }

    /** The X that cancels the delete mode, on its button while the mode is on. */
    private static final String[] CANCEL_DELETE_ICON = {
            "##.....##", "###...###", ".###.###.", "..#####..", "...###...",
            "..#####..", ".###.###.", "###...###", "##.....##"};
    /** The delete mode's bin. */
    private static final String[] DELETE_MODE_ICON = {
            "...###...", "#########", ".#######.", ".#.#.#.#.", ".#.#.#.#.",
            ".#.#.#.#.", ".#.#.#.#.", ".#.#.#.#.", ".#######."};

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
        sortButton.setIcon(sortIcon(nextMode));
        sortButton.setTooltip(Tooltip.create(
                Component.translatable("quickskin.tooltip.sorting", Component.translatable(nextMode.getTranslationKey()))
        ));

        // Refresh the skin list with new sorting; the selected skin stays selected and in view
        if (skinListPanel != null) {
            skinListPanel.refresh();
            SkinEntry selected = skinListPanel.getSelected();
            if (selected != null) {
                skinListPanel.reveal(selected.getMetadata().hash());
            }
        }

        // Play click sound
        minecraft.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
        );
    }
}
