package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.panel.SkinListPanel;
import com.quickskin.mod.client.gui.screen.DeletionConfirmScreen;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.screen.RenameScreen;
import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.gui.widget.SkinListWidget;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.SkinSortMode;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.DefaultSkinEvidenceView;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.event.ClientEvents;
import com.quickskin.mod.client.importing.PlayerOwnSkinBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skin-catalog management steps of the {@code full} scenario: rename, sort, own-skin protection,
 * refused and real deletion through the production dialogs, plus the stale-active-skin fallback.
 *
 * <p>Every catalog action goes through the real product path: {@link PlayerSkinMenuScreen} opens
 * its own {@link RenameScreen} / {@link DeletionConfirmScreen}, the harness only fills the edit
 * box and presses the dialog's real buttons, and the assertions read {@link LocalAssetManager},
 * {@link ClientConfig}, the on-disk uploads folder and the live {@link SkinListWidget}.</p>
 *
 * <p>Identity: the harness holds bare SHA-1 aliases ({@code owner.skinHash},
 * {@code owner.externalSkinHash}) while the catalog, every {@link SkinEntry} and the config values
 * production writes ({@code CpmModelWorkflow.activateSkin}) use the {@code sha256-} primary.
 * {@link LocalAssetManager#getMetadata} resolves either form, so each alias is resolved to its
 * primary through the catalog and every comparison is made on primaries.</p>
 */
final class CatalogSteps {

    /** New friendly name typed into the real rename dialog (also the new file stem). */
    static final String RENAMED_NAME = "qs_e2e_renamed";
    /** File name the external-drop step wrote into {@code uploads/skins}. */
    static final String DROPPED_FILE_NAME = "qs_e2e_external_drop.png";
    /** File name the real-deletion step drops so another skin stays listed. */
    static final String SPARE_FILE_NAME = "qs_e2e_spare_skin.png";
    /** A well-formed legacy content id that no catalogued file can have. */
    static final String STALE_SKIN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final String LAST_SKIN_ERROR_KEY = "quickskin.error.delete_last_skin";
    private static final String LOCAL_SKIN_PREFIX = "local_skin:";

    private final FullScenario owner;

    /** One-shot guards so a dialog button is pressed exactly once per step. */
    private final AtomicBoolean renameConfirmed = new AtomicBoolean();
    private final AtomicInteger renamePolls = new AtomicInteger();
    private final AtomicBoolean protectedDeleteConfirmed = new AtomicBoolean();
    private final AtomicBoolean externalDeleteConfirmed = new AtomicBoolean();
    private final AtomicBoolean externalDeleteOpened = new AtomicBoolean();
    /** Harness alias of the spare skin dropped before the real deletion. */
    private final AtomicReference<String> spareSkinHash = new AtomicReference<>();
    /** Precondition failure recorded by an action so ready() stops waiting and the assertion reports it. */
    private final AtomicReference<String> setupFailure = new AtomicReference<>();
    /** Catalog size observed before the own-skin flag flips; an importer run would change it. */
    private final AtomicInteger entriesBeforeOwnSkin = new AtomicInteger(-1);
    /** {@code ClientConfig.activeSkinHash} when the own-skin step starts. */
    private final AtomicReference<String> activeSkinBeforeOwnSkin = new AtomicReference<>();
    /** Catalog primary of the external skin, captured before its deletion removes the alias. */
    private final AtomicReference<String> externalPrimaryBeforeDelete = new AtomicReference<>();
    private volatile String restoreMethodUsed;

    CatalogSteps(FullScenario owner) {
        this.owner = owner;
    }

    // =========================================================================================
    // Steps inserted right after external_skin_drop
    // =========================================================================================

    /** Steps inserted right after external_skin_drop, in this order. */
    List<Step> buildCatalogOperations(
            Minecraft mc, UUID uuid, PlayerAppearanceService svc, String prefix, String suffix) {
        List<Step> steps = new ArrayList<>();

        // 2d. rename through the real RenameScreen -------------------------------------------
        steps.add(Step.of("catalog_rename")
                .action(() -> {
                    renameConfirmed.set(false);
                    setupFailure.set(null);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        setupFailure.set("skin menu not open before rename: " + FullScenario.screenName(mc));
                        return;
                    }
                    if (owner.externalSkinHash == null) {
                        setupFailure.set("external skin was never catalogued (no hash)");
                        return;
                    }
                    AssetMetadata external = LocalAssetManager.getInstance()
                            .getMetadata(owner.externalSkinHash);
                    if (external == null) {
                        setupFailure.set("external skin " + owner.externalSkinHash
                                + " is not catalogued before rename");
                        return;
                    }
                    screen.showRenameDialog(external);
                    E2ELog.info("catalog_rename: opened RenameScreen for " + external.friendlyName()
                            + " (primary " + external.hash() + ")");
                })
                .minTicks(20)
                .ready(() -> {
                    if (setupFailure.get() != null) return true;
                    Screen current = VanillaShim.currentScreen(mc);
                    if (current instanceof RenameScreen rename) {
                        driveRenameDialog(rename);
                        return false;
                    }
                    boolean settled = renameConfirmed.get()
                            && current instanceof PlayerSkinMenuScreen
                            && menuLayoutStable(mc)
                            && RENAMED_NAME.equals(friendlyName(owner.externalSkinHash));
                    if (!settled && renamePolls.incrementAndGet() % 40 == 0) {
                        E2ELog.info("catalog_rename: waiting; confirmed=" + renameConfirmed.get()
                                + " screen=" + FullScenario.screenName(mc)
                                + " friendlyName=" + friendlyName(owner.externalSkinHash)
                                + " guiScale=" + VanillaShim.guiScale(mc));
                    }
                    return settled;
                })
                .settleTicks(10)
                .timeoutTicks(400)
                .screenshot(prefix + "full_02d_catalog_rename" + suffix)
                .assertion(() -> {
                    String failure = setupFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        return Step.Result.fail("skin menu not open after rename: "
                                + FullScenario.screenName(mc));
                    }
                    LocalAssetManager assets = LocalAssetManager.getInstance();
                    AssetMetadata external = assets.getMetadata(owner.externalSkinHash);
                    if (external == null) {
                        return Step.Result.fail("renamed skin " + owner.externalSkinHash
                                + " vanished from the catalog");
                    }
                    if (!RENAMED_NAME.equals(external.friendlyName())) {
                        return Step.Result.fail("friendly name after rename is '"
                                + external.friendlyName() + "', expected '" + RENAMED_NAME + "'");
                    }
                    Path skinsDirectory = assets.getSkinsDirectory();
                    if (skinsDirectory == null) return Step.Result.fail("skins directory is null");
                    if (Files.exists(skinsDirectory.resolve(DROPPED_FILE_NAME))) {
                        return Step.Result.fail("old file " + DROPPED_FILE_NAME
                                + " still exists after rename");
                    }
                    Path renamedFile = findFileStartingWith(skinsDirectory, RENAMED_NAME);
                    if (renamedFile == null) {
                        return Step.Result.fail("no file starting with " + RENAMED_NAME
                                + " in " + skinsDirectory);
                    }
                    if (external.path() == null || !Files.exists(external.path())
                            || !external.path().getFileName().toString().startsWith(RENAMED_NAME)) {
                        return Step.Result.fail("catalog source path did not follow the rename: "
                                + external.path());
                    }
                    SkinListWidget list = listWidget(screen);
                    if (list == null) return Step.Result.fail("skin list widget not built");
                    String externalProblem = listEntryProblem(screen, owner.externalSkinHash);
                    if (externalProblem != null) return Step.Result.fail(externalProblem);
                    String plaidProblem = listEntryProblem(screen, owner.skinHash);
                    if (plaidProblem != null) return Step.Result.fail(plaidProblem);
                    for (SkinEntry entry : list.children()) {
                        String problem = listEntryProblem(screen, entry.getMetadata().hash());
                        if (problem != null) return Step.Result.fail(problem);
                        if (entry.getMetadata().friendlyName().startsWith("qs_e2e_external_drop")) {
                            return Step.Result.fail("a row still shows the original dropped name: "
                                    + entry.getMetadata().friendlyName());
                        }
                    }
                    SkinEntry selected = list.getSelected();
                    return Step.Result.pass("real RenameScreen renamed " + external.hash()
                            + " (alias " + owner.externalSkinHash + ") to '" + RENAMED_NAME
                            + "' (file " + renamedFile.getFileName() + ", " + DROPPED_FILE_NAME
                            + " gone); list has " + list.children().size()
                            + " entries with preview textures: " + describeEntries(list)
                            + "; selected=" + (selected == null ? "<none>"
                            : selected.getMetadata().friendlyName()));
                }));

        // 2e. sort button -> LATEST_FIRST ------------------------------------------------------
        steps.add(Step.of("catalog_sort")
                .action(() -> {
                    setupFailure.set(null);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        setupFailure.set("skin menu not open before sort: " + FullScenario.screenName(mc));
                        return;
                    }
                    SkinSortMode before = ClientConfig.getInstance().getSkinSortMode();
                    if (before != SkinSortMode.LATEST_LAST) {
                        setupFailure.set("sort mode before pressing the button is " + before
                                + ", expected the LATEST_LAST default");
                        return;
                    }
                    Button sortButton = sortButton(screen);
                    if (sortButton == null) {
                        setupFailure.set("sortButton not built on the skin menu");
                        return;
                    }
                    if (!VanillaShim.press(sortButton)) {
                        setupFailure.set("could not press the sort button");
                        return;
                    }
                    E2ELog.info("catalog_sort: pressed the real sort button once");
                })
                .minTicks(15)
                .ready(() -> setupFailure.get() != null
                        || (VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen
                        && ClientConfig.getInstance().getSkinSortMode() == SkinSortMode.LATEST_FIRST
                        && menuLayoutStable(mc)))
                .settleTicks(10)
                .timeoutTicks(300)
                .screenshot(prefix + "full_02e_catalog_sort" + suffix)
                .assertion(() -> {
                    String failure = setupFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        return Step.Result.fail("skin menu not open after sort: "
                                + FullScenario.screenName(mc));
                    }
                    SkinSortMode mode = ClientConfig.getInstance().getSkinSortMode();
                    if (mode != SkinSortMode.LATEST_FIRST) {
                        return Step.Result.fail("sort mode is " + mode + ", expected LATEST_FIRST");
                    }
                    Button sortButton = sortButton(screen);
                    if (sortButton == null) return Step.Result.fail("sortButton not built");
                    String label = sortButton.getMessage().getString();
                    if (!SkinSortMode.LATEST_FIRST.getIcon().equals(label)) {
                        return Step.Result.fail("sort button label is '" + label + "', expected '"
                                + SkinSortMode.LATEST_FIRST.getIcon() + "'");
                    }
                    SkinListWidget list = listWidget(screen);
                    if (list == null) return Step.Result.fail("skin list widget not built");
                    int externalIndex = indexOf(list, owner.externalSkinHash);
                    int plaidIndex = indexOf(list, owner.skinHash);
                    if (externalIndex < 0 || plaidIndex < 0) {
                        return Step.Result.fail("list is missing an entry: external=" + externalIndex
                                + " plaid=" + plaidIndex + " entries=" + describeEntries(list));
                    }
                    AssetMetadata external = list.children().get(externalIndex).getMetadata();
                    AssetMetadata plaid = list.children().get(plaidIndex).getMetadata();
                    if (external.lastModifiedTime() <= plaid.lastModifiedTime()) {
                        return Step.Result.fail("external skin is not newer than the plaid skin: "
                                + external.lastModifiedTime() + " <= " + plaid.lastModifiedTime()
                                + "; LATEST_FIRST order cannot be proven");
                    }
                    if (externalIndex >= plaidIndex) {
                        return Step.Result.fail("LATEST_FIRST did not move the renamed skin above the "
                                + "plaid skin: external=" + externalIndex + " plaid=" + plaidIndex
                                + " entries=" + describeEntries(list));
                    }
                    return Step.Result.pass("sort button cycled LATEST_LAST -> LATEST_FIRST (label '"
                            + label + "'); '" + RENAMED_NAME + "' (mtime " + external.lastModifiedTime()
                            + ") at index " + externalIndex + " above plaid (mtime "
                            + plaid.lastModifiedTime() + ") at index " + plaidIndex
                            + "; entries=" + describeEntries(list));
                }));

        // 2f. mark the plaid skin as the player's own skin ------------------------------------
        steps.add(Step.of("catalog_own_skin")
                .action(() -> {
                    setupFailure.set(null);
                    entriesBeforeOwnSkin.set(-1);
                    activeSkinBeforeOwnSkin.set(ClientConfig.getInstance().activeSkinHash);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        setupFailure.set("skin menu not open before own-skin: "
                                + FullScenario.screenName(mc));
                        return;
                    }
                    if (owner.skinHash == null) {
                        setupFailure.set("plaid skin hash is null");
                        return;
                    }
                    AssetMetadata plaid = LocalAssetManager.getInstance().getMetadata(owner.skinHash);
                    if (plaid == null) {
                        setupFailure.set("plaid skin " + owner.skinHash + " is not catalogued");
                        return;
                    }
                    SkinListWidget list = listWidget(screen);
                    entriesBeforeOwnSkin.set(list == null ? -1 : list.children().size());

                    // SkinEntry compares metadata.hash() (the primary) with playerOwnSkinHash
                    // verbatim, so the config must hold the primary, exactly as the importer does.
                    ClientConfig config = ClientConfig.getInstance();
                    config.enablePlayerOwnSkinSystem = true;
                    config.playerOwnSkinHash = plaid.hash();
                    config.save();
                    screen.refreshSkinList();
                    SkinListPanel panel = listPanel(screen);
                    if (panel == null) {
                        setupFailure.set("skinListPanel not built on the skin menu");
                        return;
                    }
                    panel.setSelected(plaid);
                    E2ELog.info("catalog_own_skin: playerOwnSkinHash=" + plaid.hash()
                            + " (alias " + owner.skinHash + ") selected through SkinListPanel");
                })
                .minTicks(15)
                .ready(() -> {
                    if (setupFailure.get() != null) return true;
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null || !menuLayoutStable(mc)) return false;
                    SkinListWidget list = listWidget(screen);
                    if (list == null || list.children().isEmpty()) return false;
                    String plaidPrimary = primaryOf(owner.skinHash);
                    SkinEntry selected = list.getSelected();
                    return plaidPrimary != null
                            && selected != null
                            && plaidPrimary.equals(selected.getMetadata().hash())
                            && plaidPrimary.equals(list.children().get(0).getMetadata().hash());
                })
                .settleTicks(10)
                .timeoutTicks(300)
                .screenshot(prefix + "full_02f_catalog_own_skin" + suffix)
                .assertion(() -> {
                    String failure = setupFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        return Step.Result.fail("skin menu not open: " + FullScenario.screenName(mc));
                    }
                    String plaidPrimary = primaryOf(owner.skinHash);
                    if (plaidPrimary == null) {
                        return Step.Result.fail("plaid alias " + owner.skinHash
                                + " no longer resolves to a catalog primary");
                    }
                    ClientConfig config = ClientConfig.getInstance();
                    SkinListWidget list = listWidget(screen);
                    if (list == null || list.children().isEmpty()) {
                        return Step.Result.fail("skin list widget is missing or empty");
                    }
                    SkinEntry first = list.children().get(0);
                    if (!plaidPrimary.equals(first.getMetadata().hash())) {
                        return Step.Result.fail("own skin " + plaidPrimary + " is not pinned first: "
                                + describeEntries(list));
                    }
                    SkinEntry selected = list.getSelected();
                    if (selected == null || !plaidPrimary.equals(selected.getMetadata().hash())) {
                        return Step.Result.fail("selected entry is not the own skin: "
                                + (selected == null ? "<none>" : selected.getMetadata().hash()));
                    }
                    if (!isPlayerOwnSkin(selected.getMetadata())) {
                        return Step.Result.fail("SkinEntry own-skin condition is false: enable="
                                + config.enablePlayerOwnSkinSystem + " playerOwnSkinHash="
                                + config.playerOwnSkinHash + " entry=" + selected.getMetadata().hash());
                    }
                    for (int i = 1; i < list.children().size(); i++) {
                        if (isPlayerOwnSkin(list.children().get(i).getMetadata())) {
                            return Step.Result.fail("a second row also satisfies the own-skin "
                                    + "condition: " + describeEntries(list));
                        }
                    }
                    if (config.getSkinSortMode() != SkinSortMode.LATEST_FIRST) {
                        return Step.Result.fail("sort mode changed to " + config.getSkinSortMode());
                    }
                    // No Mojang import may have run: the importer only starts at client startup,
                    // so the own-skin hash, the catalog size and the active look are all unchanged.
                    if (!plaidPrimary.equals(config.playerOwnSkinHash)) {
                        return Step.Result.fail("playerOwnSkinHash was rewritten to "
                                + config.playerOwnSkinHash + " (an importer ran?)");
                    }
                    int before = entriesBeforeOwnSkin.get();
                    if (before < 0 || before != list.children().size()) {
                        return Step.Result.fail("catalog size changed across the own-skin flag: "
                                + before + " -> " + list.children().size());
                    }
                    String importerTask = importerTaskState();
                    if (importerTask != null) return Step.Result.fail(importerTask);
                    PlayerAppearance appearance = svc.getAppearance(uuid);
                    String appliedPrimary = appearance == null ? null : skinIdPrimary(appearance.getSkinId());
                    if (!plaidPrimary.equals(appliedPrimary)) {
                        return Step.Result.fail("active skin is not the plaid skin: "
                                + (appearance == null ? "<none>" : appearance.getSkinId())
                                + " resolves to " + appliedPrimary + ", expected " + plaidPrimary);
                    }
                    // A row the menu selects itself only previews it; only a click or Confirm wears it.
                    String activeBefore = activeSkinBeforeOwnSkin.get();
                    if (!java.util.Objects.equals(activeBefore, config.activeSkinHash)) {
                        return Step.Result.fail("config.activeSkinHash changed from " + activeBefore
                                + " to " + config.activeSkinHash + " without a Confirm");
                    }
                    return Step.Result.pass("plaid skin " + plaidPrimary + " (alias " + owner.skinHash
                            + ") is the own skin: pinned first, selected with the purple own-skin "
                            + "highlight, sort still LATEST_FIRST, no import ran (activeSkinHash "
                            + "unchanged = " + config.activeSkinHash + ", " + list.children().size()
                            + " entries, importer task idle); entries=" + describeEntries(list));
                }));

        // 2g. deleting the own skin while another skin is listed hides it --------------------
        steps.add(Step.of("catalog_hide_own_skin")
                .action(() -> {
                    setupFailure.set(null);
                    protectedDeleteConfirmed.set(false);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        setupFailure.set("skin menu not open before hiding the own skin: "
                                + FullScenario.screenName(mc));
                        return;
                    }
                    AssetMetadata own = LocalAssetManager.getInstance().getMetadata(owner.skinHash);
                    if (own == null) {
                        setupFailure.set("own skin " + owner.skinHash + " is not catalogued");
                        return;
                    }
                    if (!isPlayerOwnSkin(own)) {
                        setupFailure.set("plaid skin " + own.hash() + " is not flagged as the own skin "
                                + "(playerOwnSkinHash=" + ClientConfig.getInstance().playerOwnSkinHash + ")");
                        return;
                    }
                    screen.showDeleteConfirmation(own);
                    E2ELog.info("catalog_hide_own_skin: opened DeletionConfirmScreen for own skin");
                })
                .minTicks(10)
                .ready(() -> {
                    if (setupFailure.get() != null) return true;
                    if (confirmDeleteDialog(mc, protectedDeleteConfirmed)) return false;
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    SkinListWidget list = screen == null ? null : listWidget(screen);
                    return list != null
                            && protectedDeleteConfirmed.get()
                            && indexOf(list, owner.skinHash) < 0
                            && menuLayoutStable(mc);
                })
                .settleTicks(10)
                .timeoutTicks(300)
                .screenshot(prefix + "full_02g_catalog_hide_own_skin" + suffix)
                .assertion(() -> {
                    String failure = setupFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        return Step.Result.fail("skin menu not open after hiding the own skin: "
                                + FullScenario.screenName(mc));
                    }
                    ClientConfig config = ClientConfig.getInstance();
                    if (!config.playerOwnSkinHidden) {
                        return Step.Result.fail("the own skin is not recorded as hidden");
                    }
                    LocalAssetManager assets = LocalAssetManager.getInstance();
                    AssetMetadata own = assets.getMetadata(owner.skinHash);
                    if (own == null) return Step.Result.fail("the hidden own skin was deleted from the catalog");
                    if (own.path() == null || !Files.exists(own.path())) {
                        return Step.Result.fail("the hidden own skin's file is gone: " + own.path());
                    }
                    if (!isPlayerOwnSkin(own)) {
                        return Step.Result.fail("the hidden skin is no longer the own skin");
                    }
                    SkinListWidget list = listWidget(screen);
                    if (list == null) return Step.Result.fail("skin list widget not built");
                    if (indexOf(list, owner.skinHash) >= 0) {
                        return Step.Result.fail("the hidden own skin is still listed: " + describeEntries(list));
                    }
                    if (indexOf(list, owner.externalSkinHash) < 0) {
                        return Step.Result.fail("the external skin left the list too: " + describeEntries(list));
                    }
                    if (!toasts(screen).isEmpty()) {
                        return Step.Result.fail("hiding the own skin showed a toast: '"
                                + toastText(toasts(screen).get(0)) + "'");
                    }
                    return Step.Result.pass("real Delete confirmation on the own skin hid it: "
                            + own.hash() + " still catalogued at " + own.path().getFileName()
                            + ", playerOwnSkinHidden=true, not listed; entries=" + describeEntries(list));
                }));

        // 2h. deleting the last listed skin, a custom one, is refused with a toast ---------
        steps.add(Step.of("catalog_delete_protected")
                .action(() -> {
                    setupFailure.set(null);
                    protectedDeleteConfirmed.set(false);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        setupFailure.set("skin menu not open before protected delete: "
                                + FullScenario.screenName(mc));
                        return;
                    }
                    AssetMetadata external = LocalAssetManager.getInstance()
                            .getMetadata(owner.externalSkinHash);
                    if (external == null) {
                        setupFailure.set("external skin " + owner.externalSkinHash + " is not catalogued");
                        return;
                    }
                    SkinListWidget list = listWidget(screen);
                    if (list == null || list.children().size() != 1 || indexOf(list, external.hash()) != 0) {
                        setupFailure.set("the external skin is not the only listed skin: "
                                + (list == null ? "<no list>" : describeEntries(list)));
                        return;
                    }
                    screen.showDeleteConfirmation(external);
                    E2ELog.info("catalog_delete_protected: opened DeletionConfirmScreen for the only listed skin, "
                            + external.friendlyName() + " (" + external.hash() + ")");
                })
                .minTicks(10)
                .ready(() -> {
                    if (setupFailure.get() != null) return true;
                    if (confirmDeleteDialog(mc, protectedDeleteConfirmed)) return false;
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    return screen != null
                            && protectedDeleteConfirmed.get()
                            && !toasts(screen).isEmpty()
                            && menuLayoutStable(mc);
                })
                // Short: the toast lives 3000 ms and the capture must land while it is drawn.
                .settleTicks(6)
                .timeoutTicks(300)
                .screenshot(prefix + "full_02h_catalog_delete_protected" + suffix)
                .assertion(() -> {
                    String failure = setupFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        return Step.Result.fail("skin menu not open after refused delete: "
                                + FullScenario.screenName(mc));
                    }
                    List<?> toasts = toasts(screen);
                    if (toasts.isEmpty()) return Step.Result.fail("no error toast is visible");
                    String expected = Component.translatable(LAST_SKIN_ERROR_KEY).getString();
                    String actual = toastText(toasts.get(0));
                    if (!expected.equals(actual)) {
                        return Step.Result.fail("toast text is '" + actual + "', expected '" + expected + "'");
                    }
                    LocalAssetManager assets = LocalAssetManager.getInstance();
                    AssetMetadata external = assets.getMetadata(owner.externalSkinHash);
                    if (external == null) return Step.Result.fail("the last listed skin was deleted despite the refusal");
                    if (external.path() == null || !Files.exists(external.path())) {
                        return Step.Result.fail("the last listed skin's file is gone: " + external.path());
                    }
                    SkinListWidget list = listWidget(screen);
                    if (list == null) return Step.Result.fail("skin list widget not built");
                    if (list.children().size() != 1 || indexOf(list, external.hash()) != 0) {
                        return Step.Result.fail("the external skin is not still the only listed skin: "
                                + describeEntries(list));
                    }
                    if (!ClientConfig.getInstance().playerOwnSkinHidden || indexOf(list, owner.skinHash) >= 0) {
                        return Step.Result.fail("the refusal listed the hidden own skin again: "
                                + describeEntries(list));
                    }
                    return Step.Result.pass("real Delete confirmation on the only listed skin, the custom '"
                            + external.friendlyName() + "' (" + external.hash() + "), was refused: "
                            + toasts.size() + " toast(s), first reads '" + actual + "'; still catalogued at "
                            + external.path().getFileName() + " and the only entry, own skin still hidden; "
                            + "entries=" + describeEntries(list));
                }));

        // 2i. real deletion of the renamed external skin while a dropped spare skin is listed --
        steps.add(Step.of("catalog_delete")
                .action(() -> {
                    setupFailure.set(null);
                    externalDeleteConfirmed.set(false);
                    externalDeleteOpened.set(false);
                    externalPrimaryBeforeDelete.set(null);
                    spareSkinHash.set(null);
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) {
                        setupFailure.set("skin menu not open before delete: "
                                + FullScenario.screenName(mc));
                        return;
                    }
                    try {
                        LocalAssetManager assets = LocalAssetManager.getInstance();
                        Path skinsDirectory = assets.getSkinsDirectory();
                        if (skinsDirectory == null) {
                            setupFailure.set("skins directory is null");
                            return;
                        }
                        Path target = skinsDirectory.resolve(SPARE_FILE_NAME);
                        Files.copy(TestAssets.makeSpareSkin(), target, StandardCopyOption.REPLACE_EXISTING);
                        String hash = HashUtil.computeFileHash(target);
                        if (hash == null || assets.getMetadata(hash) != null) {
                            setupFailure.set("spare skin " + hash + " is unhashable or already catalogued");
                            return;
                        }
                        spareSkinHash.set(hash);
                        E2ELog.info("catalog_delete: dropped spare skin " + target + ", hash=" + hash);
                    } catch (Exception e) {
                        setupFailure.set("could not drop the spare skin: " + e);
                    }
                })
                // Comfortably longer than LocalAssetFolderWatch.POLL_INTERVAL_MILLIS at 20 TPS.
                .minTicks(80)
                .ready(() -> {
                    if (setupFailure.get() != null) return true;
                    if (confirmDeleteDialog(mc, externalDeleteConfirmed)) return false;
                    PlayerSkinMenuScreen screen = openMenu(mc);
                    if (screen == null) return false;
                    if (!externalDeleteOpened.get()) {
                        // Delete only once the folder watch has listed the spare skin beside it
                        if (listEntryProblem(screen, spareSkinHash.get()) != null) return false;
                        AssetMetadata external = LocalAssetManager.getInstance()
                                .getMetadata(owner.externalSkinHash);
                        if (external == null) {
                            setupFailure.set("external skin " + owner.externalSkinHash
                                    + " is not catalogued before delete");
                            return true;
                        }
                        externalDeleteOpened.set(true);
                        externalPrimaryBeforeDelete.set(external.hash());
                        screen.showDeleteConfirmation(external);
                        E2ELog.info("catalog_delete: opened DeletionConfirmScreen for "
                                + external.friendlyName() + " (" + external.hash() + ")");
                        return false;
                    }
                    // The previous step's toast must have expired (3 s) so the frame is clean.
                    return externalDeleteConfirmed.get()
                            && LocalAssetManager.getInstance().getMetadata(owner.externalSkinHash) == null
                            && toasts(screen).isEmpty()
                            && menuLayoutStable(mc);
                })
                .settleTicks(10)
                .timeoutTicks(500)
                .screenshot(prefix + "full_02i_catalog_delete" + suffix)
                .assertion(() -> {
                    try {
                        String failure = setupFailure.get();
                        if (failure != null) return Step.Result.fail(failure);
                        PlayerSkinMenuScreen screen = openMenu(mc);
                        if (screen == null) {
                            return Step.Result.fail("skin menu not open after delete: "
                                    + FullScenario.screenName(mc));
                        }
                        LocalAssetManager assets = LocalAssetManager.getInstance();
                        String externalPrimary = externalPrimaryBeforeDelete.get();
                        if (assets.getMetadata(owner.externalSkinHash) != null
                                || (externalPrimary != null && assets.getMetadata(externalPrimary) != null)) {
                            return Step.Result.fail("external skin " + externalPrimary + " (alias "
                                    + owner.externalSkinHash + ") is still catalogued");
                        }
                        Path skinsDirectory = assets.getSkinsDirectory();
                        if (skinsDirectory == null) return Step.Result.fail("skins directory is null");
                        Path leftover = findFileStartingWith(skinsDirectory, RENAMED_NAME);
                        if (leftover != null) {
                            return Step.Result.fail("deleted skin file still on disk: " + leftover);
                        }
                        if (Files.exists(skinsDirectory.resolve(DROPPED_FILE_NAME))) {
                            return Step.Result.fail(DROPPED_FILE_NAME + " reappeared on disk");
                        }
                        AssetMetadata own = assets.getMetadata(owner.skinHash);
                        if (own == null || own.path() == null || !Files.exists(own.path())) {
                            return Step.Result.fail("own skin is missing after deleting the other one");
                        }
                        if (!ClientConfig.getInstance().playerOwnSkinHidden) {
                            return Step.Result.fail("deleting a custom skin listed the hidden own skin again");
                        }
                        SkinListWidget list = listWidget(screen);
                        if (list == null) return Step.Result.fail("skin list widget not built");
                        String spare = spareSkinHash.get();
                        if (list.children().size() != 1 || indexOf(list, spare) != 0) {
                            return Step.Result.fail("expected the spare skin " + spare
                                    + " as the only entry: " + describeEntries(list));
                        }
                        Object tile = FullScenario.screenField(screen, "importTile");
                        if (!(tile instanceof Button importTile) || !importTile.visible) {
                            return Step.Result.fail("the skin column's import tile is not built");
                        }
                        // The tile closes the skin column, below the grid however few skins it holds.
                        int listBottom = list.getY() + list.getHeight();
                        if (importTile.getY() < listBottom) {
                            return Step.Result.fail("import tile at y=" + importTile.getY()
                                    + " overlaps the skin grid (grid ends at y=" + listBottom + ")");
                        }
                        if (!toasts(screen).isEmpty()) {
                            return Step.Result.fail("an error toast is still visible: '"
                                    + toastText(toasts(screen).get(0)) + "'");
                        }
                        return Step.Result.pass("with the dropped spare skin " + spare + " listed, the real "
                                + "Delete confirmation removed '" + RENAMED_NAME + "' (" + externalPrimary
                                + ", alias " + owner.externalSkinHash + "): metadata null, no " + RENAMED_NAME
                                + "* file in uploads/skins; the spare skin is the only entry, the own skin "
                                + own.hash() + " is still hidden with its file kept, and the import tile is "
                                + "below the grid; no toast visible");
                    } finally {
                        // Later steps expect the defaults: no own-skin protection, LATEST_LAST
                        // sort, only the plaid skin catalogued, and it persisted as the active one
                        // (primary form, as production writes it; the alias is only a fallback if
                        // resolution fails).
                        ClientConfig config = ClientConfig.getInstance();
                        config.enablePlayerOwnSkinSystem = false;
                        config.playerOwnSkinHash = "";
                        config.playerOwnSkinHidden = false;
                        String plaid = primaryOrAlias(owner.skinHash);
                        if (plaid != null) config.activeSkinHash = plaid;
                        config.save();
                        config.setSkinSortMode(SkinSortMode.LATEST_LAST);
                        String spare = primaryOf(spareSkinHash.get());
                        if (spare != null) {
                            LocalAssetManager assets = LocalAssetManager.getInstance();
                            if (!assets.deleteAsset(spare)) {
                                E2ELog.warn("catalog_delete: could not remove the spare skin " + spare);
                            }
                            assets.discoverLocalAssets();
                        }
                    }
                }));


        return steps;
    }

    // =========================================================================================
    // Step inserted right after delete_dialog
    // =========================================================================================

    /** The stale_skin_fallback step inserted right after delete_dialog. */
    Step buildStaleSkinFallback(
            Minecraft mc, UUID uuid, PlayerAppearanceService svc, String prefix, String suffix) {
        return Step.of("stale_skin_fallback")
                .action(() -> {
                    setupFailure.set(null);
                    restoreMethodUsed = null;
                    owner.enterWorldView(mc); // closes the leftover delete dialog
                    if (owner.skinHash == null) {
                        setupFailure.set("plaid skin hash is null; nothing to restore afterwards");
                        return;
                    }
                    if (mc.player == null) {
                        setupFailure.set("player is null");
                        return;
                    }
                    // Persist a well-formed id that resolves to no catalogued file, exactly the
                    // state a user is in after deleting the active skin's file out of game.
                    ClientConfig config = ClientConfig.getInstance();
                    config.activeSkinHash = STALE_SKIN_ID;
                    config.activeCapeHash = "";
                    config.save();
                    if (!STALE_SKIN_ID.equals(config.activeSkinHash)) {
                        setupFailure.set("config normalised the stale id away: " + config.activeSkinHash);
                        return;
                    }
                    if (LocalAssetManager.getInstance().getMetadata(STALE_SKIN_ID) != null) {
                        setupFailure.set("stale id unexpectedly resolves to catalogued metadata");
                        return;
                    }
                    // Return the session to a clean look, then run the production restore path
                    // that world join uses: it must resolve the stale hash to nothing.
                    svc.applyLook(uuid, "", "", "classic");
                    try {
                        restoreMethodUsed = invokeProductionRestore(mc, uuid);
                    } catch (Throwable t) {
                        setupFailure.set("could not invoke ClientEvents saved-appearance restore: " + t);
                        E2ELog.error("stale_skin_fallback restore invocation failed", t);
                        return;
                    }
                    E2ELog.info("stale_skin_fallback: activeSkinHash=" + STALE_SKIN_ID
                            + " restored through " + restoreMethodUsed);
                })
                .minTicks(30)
                .ready(() -> setupFailure.get() != null
                        || (mc.player != null
                        && VanillaShim.isExpectedDefaultSkinResolved(mc.player)
                        && !svc.hasActiveSkin(uuid)
                        && !svc.hasActiveCape(uuid)
                        && DefaultSkinEvidenceView.hold(mc, false)))
                .settleTicks(20) // reject a one-frame generic fallback before the UUID skin lands
                .timeoutTicks(400)
                .screenshot(prefix + "full_10d_stale_skin_fallback" + suffix)
                .assertion(() -> {
                    try {
                        String failure = setupFailure.get();
                        if (failure != null) return Step.Result.fail(failure);
                        if (mc.player == null) return Step.Result.fail("player is null");
                        String expected = VanillaShim.expectedDefaultSkinTexture(mc.player);
                        String actual = VanillaShim.skinTexture(mc.player);
                        if (expected == null || !expected.equals(actual)) {
                            return Step.Result.fail("renderer did not fall back to the default skin: "
                                    + "expected=" + expected + " actual=" + actual);
                        }
                        if (svc.hasActiveSkin(uuid)) {
                            return Step.Result.fail("a skin is still active: "
                                    + svc.getAppearance(uuid).getSkinId());
                        }
                        if (svc.getSkinLocation(uuid) != null) {
                            return Step.Result.fail("skin location still resolves: "
                                    + svc.getSkinLocation(uuid));
                        }
                        if (svc.hasActiveCape(uuid) || svc.getCapeLocation(uuid) != null) {
                            return Step.Result.fail("a cape is still active: "
                                    + svc.getCapeId(uuid) + " / " + svc.getCapeLocation(uuid));
                        }
                        String cloak = VanillaShim.cloakTexture(mc.player);
                        if (cloak != null) {
                            return Step.Result.fail("renderer still has a cloak texture: " + cloak);
                        }
                        ClientConfig config = ClientConfig.getInstance();
                        if (!STALE_SKIN_ID.equals(config.activeSkinHash)) {
                            return Step.Result.fail("config.activeSkinHash changed to "
                                    + config.activeSkinHash + " during the fallback");
                        }
                        if (!config.activeCapeHash.isEmpty()) {
                            return Step.Result.fail("config.activeCapeHash is " + config.activeCapeHash);
                        }
                        if (LocalAssetManager.getInstance().getMetadata(STALE_SKIN_ID) != null) {
                            return Step.Result.fail("stale id resolved to metadata after all");
                        }
                        return Step.Result.pass("saved activeSkinHash=" + STALE_SKIN_ID
                                + " has no catalogued file; " + restoreMethodUsed
                                + " applied nothing, so the renderer shows the UUID default skin "
                                + actual + " with no active skin, no cape (cloak=null) and the "
                                + "stale id still persisted");
                    } finally {
                        // Hand the plaid skin back to the HUD step, persisted and applied.
                        if (owner.skinHash != null) {
                            ClientConfig config = ClientConfig.getInstance();
                            config.activeSkinHash = primaryOrAlias(owner.skinHash);
                            config.save();
                            svc.applySkin(uuid, LOCAL_SKIN_PREFIX + owner.skinHash, "classic");
                        }
                    }
                });
    }

    // =========================================================================================
    // Dialog driving
    // =========================================================================================

    /** Type the new name into the real RenameScreen and press its Done button, exactly once. */
    /**
     * True once the skin menu is open at its own GUI scale with a tick-over-tick stable layout,
     * exactly like every other skin-menu checkpoint.
     *
     * <p>These catalog checkpoints deliberately run on the menu instance a user keeps open across
     * the Rename and Delete dialogs, which return to that same instance: each dialog follows the
     * menu's scale, and the menu must still have its own scale when it returns.</p>
     */
    private boolean menuLayoutStable(Minecraft mc) {
        return owner.skinMenuLayoutSettled(mc);
    }

    private void driveRenameDialog(RenameScreen rename) {
        Object editBox = FullScenario.screenField(rename, "nameEditBox");
        Object confirm = FullScenario.screenField(rename, "confirmButton");
        if (!(editBox instanceof EditBox box) || !(confirm instanceof Button button)) {
            return; // init() has not built the widgets yet; poll again next tick
        }
        if (!renameConfirmed.compareAndSet(false, true)) return;
        box.setValue(RENAMED_NAME); // the responder re-enables the Done button
        if (!button.active) {
            setupFailure.set("RenameScreen Done button stayed inactive for '" + RENAMED_NAME + "'");
            return;
        }
        if (!VanillaShim.press(button)) {
            setupFailure.set("could not press the RenameScreen Done button");
        }
    }

    /**
     * Press the real Delete button of an open DeletionConfirmScreen once.
     *
     * @return true while the dialog is (still) the current screen, so ready() keeps waiting
     */
    private boolean confirmDeleteDialog(Minecraft mc, AtomicBoolean once) {
        Screen current = VanillaShim.currentScreen(mc);
        if (!(current instanceof DeletionConfirmScreen dialog)) return false;
        if (dialog.children().isEmpty()) return true; // widgets not built yet
        if (once.compareAndSet(false, true)) {
            // Buttons are locals in init(); Delete is added last, after Cancel.
            if (!owner.pressLastButton(mc)) {
                setupFailure.set("no button to press on the DeletionConfirmScreen");
            }
        }
        return true;
    }

    /** Invoke the private static world-join restore path in ClientEvents; returns the member used. */
    private static String invokeProductionRestore(Minecraft mc, UUID uuid) throws Exception {
        Method fallback = null;
        for (Method method : ClientEvents.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (method.getName().equals("restoreSavedAppearanceToPlayer") && parameter == UUID.class) {
                method.setAccessible(true);
                method.invoke(null, uuid);
                return "ClientEvents.restoreSavedAppearanceToPlayer(UUID)";
            }
            if (method.getName().equals("restoreSavedAppearance") && parameter.isInstance(mc.player)) {
                fallback = method;
            }
        }
        if (fallback != null) {
            fallback.setAccessible(true);
            fallback.invoke(null, mc.player);
            return "ClientEvents.restoreSavedAppearance(" + fallback.getParameterTypes()[0].getSimpleName() + ")";
        }
        throw new NoSuchMethodException(
                "ClientEvents.restoreSavedAppearanceToPlayer(UUID) / restoreSavedAppearance(player)");
    }

    // =========================================================================================
    // Identity resolution (alias -> catalog primary)
    // =========================================================================================

    /**
     * The catalog primary ({@code sha256-...}) for a harness alias or primary, or null when the
     * catalog no longer holds it. Both forms resolve through {@link LocalAssetManager#getMetadata}.
     */
    static String primaryOf(String hashOrAlias) {
        if (hashOrAlias == null || hashOrAlias.isEmpty()) return null;
        AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(hashOrAlias);
        return metadata == null ? null : metadata.hash();
    }

    /** The primary when it resolves, else the input unchanged (used only for config restores). */
    private static String primaryOrAlias(String hashOrAlias) {
        String primary = primaryOf(hashOrAlias);
        return primary != null ? primary : hashOrAlias;
    }

    /** True when both ids name the same catalogued asset, or are literally equal. */
    static boolean sameAsset(String left, String right) {
        if (left == null || right == null) return false;
        if (left.equals(right)) return true;
        String leftPrimary = primaryOf(left);
        String rightPrimary = primaryOf(right);
        return leftPrimary != null && leftPrimary.equals(rightPrimary);
    }

    /** The catalog primary behind a {@code local_skin:<id>} appearance id, or null. */
    private static String skinIdPrimary(String skinId) {
        if (skinId == null || !skinId.startsWith(LOCAL_SKIN_PREFIX)) return null;
        return primaryOf(skinId.substring(LOCAL_SKIN_PREFIX.length()));
    }

    // =========================================================================================
    // Screen / widget access
    // =========================================================================================

    /**
     * Null when the skin menu's list holds an entry for {@code hash} (alias or primary) whose
     * preview texture is registered; otherwise a bounded diagnostic. Shared with FullScenario's
     * external-drop check.
     */
    static String listEntryProblem(PlayerSkinMenuScreen screen, String hash) {
        if (screen == null) return "skin menu screen is null";
        if (hash == null) return "skin list lookup has no hash";
        SkinListWidget list = listWidget(screen);
        if (list == null) return "skin list widget not built (skinListPanel missing or empty)";
        int index = indexOf(list, hash);
        if (index < 0) {
            return "skin list has no entry for " + hash + " (primary " + primaryOf(hash) + "): "
                    + describeEntries(list);
        }
        SkinEntry entry = list.children().get(index);
        if (FullScenario.screenField(entry, "textureLocation") == null) {
            return "entry " + entry.getMetadata().hash() + " (" + entry.getMetadata().friendlyName()
                    + ") has no texture";
        }
        return null;
    }

    private static PlayerSkinMenuScreen openMenu(Minecraft mc) {
        return VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen screen ? screen : null;
    }

    private static SkinListPanel listPanel(PlayerSkinMenuScreen screen) {
        Object panel = FullScenario.screenField(screen, "skinListPanel");
        return panel instanceof SkinListPanel listPanel ? listPanel : null;
    }

    private static SkinListWidget listWidget(PlayerSkinMenuScreen screen) {
        SkinListPanel panel = listPanel(screen);
        return panel == null ? null : panel.getSkinListWidget();
    }

    private static Button sortButton(PlayerSkinMenuScreen screen) {
        Object button = FullScenario.screenField(screen, "sortButton");
        return button instanceof Button b ? b : null;
    }

    private static List<?> toasts(PlayerSkinMenuScreen screen) {
        Object toasts = FullScenario.screenField(screen, "errorToasts");
        return toasts instanceof List<?> list ? list : List.of();
    }

    private static String toastText(Object toast) {
        Object message = FullScenario.screenField(toast, "message");
        return message instanceof Component component ? component.getString() : String.valueOf(message);
    }

    /** Index of the entry naming the same asset as {@code hash} (alias or primary), or -1. */
    private static int indexOf(SkinListWidget list, String hash) {
        if (hash == null) return -1;
        List<SkinEntry> entries = list.children();
        for (int i = 0; i < entries.size(); i++) {
            if (sameAsset(entries.get(i).getMetadata().hash(), hash)) return i;
        }
        return -1;
    }

    private static String describeEntries(SkinListWidget list) {
        StringBuilder out = new StringBuilder("[");
        for (SkinEntry entry : list.children()) {
            if (out.length() > 1) out.append(", ");
            AssetMetadata metadata = entry.getMetadata();
            out.append(metadata.friendlyName()).append('=').append(metadata.hash());
        }
        return out.append(']').toString();
    }

    private static String friendlyName(String hash) {
        AssetMetadata metadata = hash == null ? null : LocalAssetManager.getInstance().getMetadata(hash);
        return metadata == null ? null : metadata.friendlyName();
    }

    /**
     * The exact condition SkinEntry.render uses to pick the purple own-skin highlight: the entry's
     * primary compared verbatim with {@code playerOwnSkinHash}.
     */
    private static boolean isPlayerOwnSkin(AssetMetadata metadata) {
        ClientConfig config = ClientConfig.getInstance();
        return config.enablePlayerOwnSkinSystem && metadata.hash().equals(config.playerOwnSkinHash);
    }

    /** Non-null failure text when the startup Mojang own-skin importer future is live. */
    private static String importerTaskState() {
        try {
            Field field = PlayerOwnSkinBootstrap.class.getDeclaredField("playerOwnSkinTask");
            field.setAccessible(true);
            Object task = field.get(null);
            return task == null ? null : "PlayerOwnSkinBootstrap.playerOwnSkinTask is live: " + task;
        } catch (Throwable t) {
            return "could not read PlayerOwnSkinBootstrap.playerOwnSkinTask: " + t;
        }
    }

    private static Path findFileStartingWith(Path directory, String prefix) {
        if (directory == null || !Files.isDirectory(directory)) return null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                if (candidate.getFileName().toString().startsWith(prefix)
                        && Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            E2ELog.warn("listing " + directory + ": " + e);
        }
        return null;
    }
}
