package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.compat.EssentialCompatIntegration;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.gui.widget.IconActionButton;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import com.quickskin.mod.client.services.CapeService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.DefaultSkinEvidenceView;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session scenario ({@code -Dquickskin.e2e.scenario=session}): the vanilla surfaces that show the
 * local player's Quick Skin look outside the mod's own screens, and the disconnect boundary that
 * must keep the saved look while releasing every connection-owned cache.
 *
 * <ol>
 *   <li>apply_look &mdash; import the plaid skin, wear it with the bundled {@code known:test} cape,
 *       and persist both to {@link ClientConfig} exactly as the skin menu would.</li>
 *   <li>pause_menu_preview &mdash; the real {@link PauseScreen}; production {@code INIT_POST}
 *       injects the Change Skin button and a {@link PlayerWidget} whose preview data must carry the
 *       active skin/cape locations.</li>
 *   <li>inventory_paper_doll &mdash; the vanilla inventory opened through the keybind flow. Its
 *       paper doll samples {@code PlayerInfo.getSkinLocation()}, which the mod overrides.</li>
 *   <li>tab_list_head &mdash; the player-list overlay held open over the world; its head icon
 *       samples the same {@code PlayerInfo} location.</li>
 *   <li>quit_to_title &mdash; vanilla's pause-menu disconnect sequence, then the title screen with
 *       the preview restored purely from configuration and every session cache empty.</li>
 * </ol>
 *
 * <p>Optional-mod lanes: with Essential present the title/pause preview belongs to Essential and
 * Quick Skin contributes only its icon button; with CPM present {@code PlayerInfo} exposes CPM's
 * HTTP-texture bridge location instead of Quick Skin's local texture location.</p>
 */
public final class SessionScenario implements Scenario {

    static final String KNOWN_CAPE_ID = "known:test";
    static final String CHANGE_SKIN_KEY = "quickskin.button.change_skin";
    /** Plain text so vanilla's random splash cannot vary the title checkpoint between runs. */
    static final String TITLE_SPLASH = "Quick Skin E2E session splash";
    /** Rendered ticks a menu must hold its widget state before the capture is armed. */
    static final int MENU_HOLD_TICKS = 20;
    /** Rendered ticks the title screen must hold after the disconnect sequence. */
    static final int TITLE_HOLD_TICKS = 25;

    volatile String skinHash;
    private final AtomicReference<String> quitFailure = new AtomicReference<>();

    @Override
    public ScenarioId id() { return ScenarioId.SESSION; }

    @Override
    public List<Step> build(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final UUID uuid = mc.player.getUUID();
        final PlayerAppearanceService svc = PlayerAppearanceService.getInstance();
        final String prefix = v + "_session_";
        final String suffix = "_" + role + ".png";
        final boolean cpm = CPMCompatIntegration.isAvailable();
        final boolean essential = EssentialCompatIntegration.isAvailable();

        List<Step> steps = new ArrayList<>();

        // 1. apply the look and persist it -------------------------------------------------------
        steps.add(Step.of("apply_look")
                .action(() -> {
                    try {
                        Path file = TestAssets.makeClassicSkin();
                        AssetMetadata meta = SkinImporter.importSkin(file);
                        if (meta == null) {
                            E2ELog.warn("SkinImporter.importSkin returned null");
                            return;
                        }
                        skinHash = meta.hash();
                        // The skin menu records the chosen model with the asset; do the same so the
                        // title/pause preview resolves the model from the persisted preference.
                        LocalAssetManager.getInstance().setSkinModelPreference(skinHash, "classic");
                        svc.applyLook(uuid, "local_skin:" + skinHash, KNOWN_CAPE_ID, "classic");
                        ClientConfig config = ClientConfig.getInstance();
                        config.activeSkinHash = skinHash;
                        config.activeCapeHash = KNOWN_CAPE_ID;
                        config.save();
                        enterRearWorldView(mc);
                        E2ELog.info("applied local_skin:" + skinHash + " + " + KNOWN_CAPE_ID
                                + " and persisted both to ClientConfig");
                    } catch (Exception e) {
                        E2ELog.error("apply_look action failed", e);
                    }
                })
                .minTicks(30)
                .ready(() -> skinHash != null && lookProblem(mc, svc, uuid, cpm) == null)
                .timeoutTicks(300)
                .assertion(() -> {
                    if (skinHash == null) return Step.Result.fail("skin import failed (no hash)");
                    String problem = lookProblem(mc, svc, uuid, cpm);
                    if (problem != null) return Step.Result.fail(problem);
                    ClientConfig config = ClientConfig.getInstance();
                    if (!skinHash.equals(config.activeSkinHash)
                            || !KNOWN_CAPE_ID.equals(config.activeCapeHash)) {
                        return Step.Result.fail("config did not persist the look: activeSkinHash="
                                + config.activeSkinHash + " activeCapeHash=" + config.activeCapeHash);
                    }
                    return Step.Result.pass("wearing local_skin:" + skinHash + " (renderer skin="
                            + VanillaShim.skinTexture(mc.player) + (cpm ? ", CPM bridge" : "")
                            + ") and " + KNOWN_CAPE_ID + " (cloak="
                            + VanillaShim.cloakTexture(mc.player) + "); ClientConfig activeSkinHash="
                            + config.activeSkinHash + " activeCapeHash=" + config.activeCapeHash);
                }));

        // 2. pause menu: injected Change Skin button + preview widget -------------------------------
        steps.add(Step.of("pause_menu_preview")
                .action(() -> {
                    enterRearWorldView(mc);
                    VanillaShim.setScreen(mc, new PauseScreen(true));
                })
                .minTicks(MENU_HOLD_TICKS)
                .ready(() -> holdWorldPose(mc) && pauseMenuProblem(mc, essential) == null)
                .settleTicks(MENU_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "01_pause" + suffix)
                .assertion(() -> {
                    String problem = pauseMenuProblem(mc, essential);
                    if (problem != null) return Step.Result.fail(problem);
                    Screen screen = VanillaShim.currentScreen(mc);
                    if (essential) {
                        return Step.Result.pass("PauseScreen open; Essential owns the player model: "
                                + "Quick Skin added its IconActionButton and no PlayerWidget; "
                                + "config activeSkinHash=" + ClientConfig.getInstance().activeSkinHash);
                    }
                    return Step.Result.pass("PauseScreen open with the Change Skin button and "
                            + describePreview(findPlayerWidget(screen)));
                }));

        // 3. vanilla inventory paper doll ---------------------------------------------------------
        steps.add(Step.of("inventory_paper_doll")
                .action(() -> {
                    enterRearWorldView(mc);
                    // The keybind flow: a creative player is handed to the creative inventory by
                    // InventoryScreen.init(); its Inventory tab draws the same paper doll.
                    VanillaShim.setScreen(mc, new InventoryScreen(mc.player));
                })
                .minTicks(MENU_HOLD_TICKS)
                .ready(() -> holdWorldPose(mc)
                        && inventoryProblem(mc) == null
                        && paperDollProblem(mc, svc, uuid, cpm) == null)
                .settleTicks(MENU_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "02_inventory" + suffix)
                .assertion(() -> {
                    String problem = inventoryProblem(mc);
                    if (problem == null) problem = paperDollProblem(mc, svc, uuid, cpm);
                    if (problem != null) return Step.Result.fail(problem);
                    Screen screen = VanillaShim.currentScreen(mc);
                    String surface = screen instanceof CreativeModeInventoryScreen
                            ? "creative inventory (Inventory tab) paper doll"
                            : "survival inventory paper doll";
                    return Step.Result.pass(surface + " open; " + describePaperDoll(mc, svc, uuid, cpm));
                }));

        // 4. player list overlay over the world -----------------------------------------------------
        steps.add(Step.of("tab_list_head")
                .action(() -> {
                    enterRearWorldView(mc);
                    mc.options.keyPlayerList.setDown(true);
                })
                .minTicks(MENU_HOLD_TICKS)
                .ready(() -> {
                    if (VanillaShim.currentScreen(mc) != null || mc.player == null) return false;
                    pinRearView(mc);
                    // Closing a screen re-syncs every KeyMapping with the physical keyboard, so
                    // keep asserting the synthetic hold rather than trusting the action's call.
                    mc.options.keyPlayerList.setDown(true);
                    return tabListProblem(mc, svc, uuid, cpm) == null;
                })
                .settleTicks(MENU_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "03_tab_list" + suffix)
                .assertion(() -> {
                    String problem = tabListProblem(mc, svc, uuid, cpm);
                    Boolean visible = tabListVisible(mc);
                    mc.options.keyPlayerList.setDown(false);
                    if (problem != null) return Step.Result.fail(problem);
                    return Step.Result.pass("player list held open (keyPlayerList down, overlay visible="
                            + (visible == null ? "unreadable; key state only" : visible)
                            + ") in a pinned rear view; " + describePaperDoll(mc, svc, uuid, cpm));
                }));

        // 5. disconnect to the title screen ---------------------------------------------------------
        steps.add(Step.of("quit_to_title")
                .action(() -> {
                    mc.options.keyPlayerList.setDown(false);
                    quitFailure.set(null);
                    String failure = VanillaShim.disconnectToTitle(mc);
                    if (failure != null) {
                        quitFailure.set(failure);
                        return;
                    }
                    String splash = VanillaShim.installDeterministicSplash(
                            VanillaShim.currentScreen(mc), TITLE_SPLASH);
                    if (splash != null) quitFailure.set(splash);
                    E2ELog.info("disconnected; level=" + mc.level + " player=" + mc.player
                            + " screen=" + screenName(mc));
                })
                .minTicks(TITLE_HOLD_TICKS)
                .ready(() -> quitFailure.get() != null || titleProblem(mc, essential) == null)
                .settleTicks(TITLE_HOLD_TICKS)
                .timeoutTicks(600)
                .screenshot(prefix + "04_title" + suffix)
                .assertion(() -> {
                    String failure = quitFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    String problem = titleProblem(mc, essential);
                    if (problem != null) return Step.Result.fail(problem);

                    PlayerAppearance retained = PlayerAppearanceRepository.getInstance().getAppearance(uuid);
                    if (retained != null) {
                        // Essential's title model is fed by re-registering the saved look after the
                        // session ends; that deliberate menu appearance must carry the saved skin.
                        boolean essentialMenuLook = essential
                                && ("local_skin:" + skinHash).equals(retained.getSkinId());
                        if (!essentialMenuLook) {
                            return Step.Result.fail("session teardown left an appearance for " + uuid
                                    + ": skin=" + retained.getSkinId() + " cape=" + retained.getCapeId());
                        }
                    }
                    int networkTextures = NetworkTextureCache.getInstance().size();
                    if (networkTextures != 0) {
                        return Step.Result.fail("session teardown left " + networkTextures
                                + " network textures cached");
                    }
                    Screen screen = VanillaShim.currentScreen(mc);
                    String preview = essential
                            ? "Essential owns the title model; Quick Skin IconActionButton present"
                            : "Change Skin button present with " + describePreview(findPlayerWidget(screen));
                    return Step.Result.pass("title screen after vanilla disconnect: level=null, "
                            + "player=null, connection=" + mc.getConnection() + "; " + preview
                            + "; ClientConfig activeSkinHash=" + ClientConfig.getInstance().activeSkinHash
                            + " activeCapeHash=" + ClientConfig.getInstance().activeCapeHash
                            + "; appearance repository " + (retained == null ? "cleared" : "holds only the Essential menu look")
                            + "; network texture cache size=0");
                }));

        return steps;
    }

    // ===== world view helpers ===================================================================

    /** Close any screen, switch to third-person-back and pin the same rear pose as the full suite. */
    private static void enterRearWorldView(Minecraft mc) {
        try {
            VanillaShim.setScreen(mc, null);
            if (mc.options != null) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                mc.options.keyShift.setDown(false);
            }
            if (mc.player != null) {
                mc.player.setShiftKeyDown(false);
                pinRearView(mc);
            }
        } catch (Throwable t) {
            E2ELog.warn("enterRearWorldView: " + t);
        }
    }

    /** Pin current and previous yaw/head/body rotations so the world behind a menu stays stable. */
    private static void pinRearView(Minecraft mc) {
        if (mc.player == null) return;
        DefaultSkinEvidenceView.pinStandingPose(mc.player, 180f);
    }

    /** Keep the player still while a screen is open (the harness only pins the HUD case). */
    private static boolean holdWorldPose(Minecraft mc) {
        if (mc.player == null) return false;
        try {
            DefaultSkinEvidenceView.pinStandingMotion(mc.player);
            return true;
        } catch (Throwable t) {
            E2ELog.warn("holdWorldPose: " + t);
            return false;
        }
    }

    // ===== look / renderer evidence =============================================================

    /** {@code null} once the renderer samples the applied skin and cape, else a diagnostic. */
    private String lookProblem(Minecraft mc, PlayerAppearanceService svc, UUID uuid, boolean cpm) {
        if (mc.player == null) return "player is null";
        PlayerAppearance appearance = svc.getAppearance(uuid);
        if (appearance == null) return "no appearance for the local player";
        String expectedSkinId = "local_skin:" + skinHash;
        if (!expectedSkinId.equals(appearance.getSkinId())) {
            return "skinId=" + appearance.getSkinId() + " expected " + expectedSkinId;
        }
        if (!KNOWN_CAPE_ID.equals(appearance.getCapeId())) {
            return "capeId=" + appearance.getCapeId() + " expected " + KNOWN_CAPE_ID;
        }
        Object serviceSkin = svc.getSkinLocation(uuid);
        if (serviceSkin == null) return "service skin location did not resolve";
        String rendererSkin = VanillaShim.skinTexture(mc.player);
        String skinProblem = skinLocationProblem(mc, rendererSkin, String.valueOf(serviceSkin), cpm, "renderer skin");
        if (skinProblem != null) return skinProblem;

        Object knownCape = CapeService.getInstance().getCapeLocation(null, KNOWN_CAPE_ID);
        if (knownCape == null) return "CapeService did not resolve " + KNOWN_CAPE_ID;
        Object serviceCape = svc.getCapeLocation(uuid);
        if (!knownCape.equals(serviceCape)) {
            return "service cape location=" + serviceCape + " expected " + knownCape;
        }
        String cloak = VanillaShim.cloakTexture(mc.player);
        if (!String.valueOf(knownCape).equals(cloak)) {
            return "renderer cloak=" + cloak + " expected " + knownCape;
        }
        return null;
    }

    /**
     * Compare a renderer-facing skin location with Quick Skin's own location. CPM's bridge replaces
     * that location with its HTTP texture, so that lane can only prove the vanilla default was left.
     */
    private static String skinLocationProblem(Minecraft mc, String actual, String quickSkinLocation,
                                              boolean cpm, String label) {
        if (actual == null) return label + " is null";
        if (cpm) {
            String vanilla = VanillaShim.expectedDefaultSkinTexture(mc.player);
            if (vanilla != null && vanilla.equals(actual)) {
                return label + " still samples the vanilla default " + vanilla + " under CPM";
            }
            return null;
        }
        if (!quickSkinLocation.equals(actual)) {
            return label + "=" + actual + " expected " + quickSkinLocation;
        }
        return null;
    }

    // ===== pause / title widgets ================================================================

    private String pauseMenuProblem(Minecraft mc, boolean essential) {
        Screen screen = VanillaShim.currentScreen(mc);
        if (!(screen instanceof PauseScreen)) return "pause screen not open: " + screenName(mc);
        return injectedMenuProblem(screen, essential, "pause menu");
    }

    private String titleProblem(Minecraft mc, boolean essential) {
        if (mc.level != null) return "level is still loaded after disconnect";
        if (mc.player != null) return "player is still present after disconnect";
        Screen screen = VanillaShim.currentScreen(mc);
        if (!(screen instanceof TitleScreen)) return "title screen not open: " + screenName(mc);
        ClientConfig config = ClientConfig.getInstance();
        if (skinHash == null || !skinHash.equals(config.activeSkinHash)) {
            return "ClientConfig.activeSkinHash=" + config.activeSkinHash + " expected " + skinHash;
        }
        if (!KNOWN_CAPE_ID.equals(config.activeCapeHash)) {
            return "ClientConfig.activeCapeHash=" + config.activeCapeHash + " expected " + KNOWN_CAPE_ID;
        }
        return injectedMenuProblem(screen, essential, "title screen");
    }

    /** The button and preview widget that production {@code INIT_POST} injects into a menu. */
    private String injectedMenuProblem(Screen screen, boolean essential, String label) {
        if (findChangeSkinButton(screen, essential) == null) {
            return label + " has no Quick Skin "
                    + (essential ? "IconActionButton" : "Change Skin button");
        }
        PlayerWidget widget = findPlayerWidget(screen);
        if (essential) {
            return widget == null
                    ? null
                    : "Quick Skin rendered a duplicate PlayerWidget beside Essential's model on the " + label;
        }
        if (widget == null) return label + " has no Quick Skin PlayerWidget";
        return previewProblem(widget);
    }

    /** {@code null} when the widget's preview carries the active skin/cape, else a diagnostic. */
    private String previewProblem(PlayerWidget widget) {
        PreviewPlayerData data = previewData(widget);
        if (data == null) return "PlayerWidget exposes no preview data";
        Object expectedSkin = LocalAssetManager.getInstance()
                .getTextureLocation(skinHash, TextureQuality.FULL);
        if (expectedSkin == null) return "LocalAssetManager did not resolve the active skin texture";
        Object skin = data.getSkinLocation();
        if (!expectedSkin.equals(skin)) {
            return "preview skin=" + skin + " expected " + expectedSkin;
        }
        if (!KNOWN_CAPE_ID.equals(data.getCapeId())) {
            return "preview capeId=" + data.getCapeId() + " expected " + KNOWN_CAPE_ID;
        }
        Object expectedCape = CapeService.getInstance().getCapeLocation(null, KNOWN_CAPE_ID);
        Object cape = data.getCapeLocation();
        if (expectedCape == null || !expectedCape.equals(cape)) {
            return "preview cape=" + cape + " expected " + expectedCape;
        }
        String expectedModel = expectedPreviewModel();
        if (!expectedModel.equals(data.getModelType())) {
            return "preview model=" + data.getModelType() + " expected " + expectedModel;
        }
        return null;
    }

    /** The model the production menu code derives for the active skin: preference, then metadata. */
    private String expectedPreviewModel() {
        LocalAssetManager assets = LocalAssetManager.getInstance();
        String model = assets.getSkinModelPreference(skinHash);
        if ("auto".equals(model)) {
            AssetMetadata metadata = assets.getMetadata(skinHash);
            if (metadata != null && metadata.skinModel() != null) model = metadata.skinModel();
        }
        return model == null ? "classic" : model;
    }

    private String describePreview(PlayerWidget widget) {
        PreviewPlayerData data = widget == null ? null : previewData(widget);
        if (data == null) return "PlayerWidget preview unavailable";
        return "PlayerWidget preview skin=" + data.getSkinLocation() + " cape=" + data.getCapeId()
                + "@" + data.getCapeLocation() + " model=" + data.getModelType();
    }

    /** The widget's private preview state; a mod-owned name, so it survives remapping. */
    private static PreviewPlayerData previewData(PlayerWidget widget) {
        try {
            Field field = findField(widget.getClass(), "previewData");
            Object data = field == null ? null : field.get(widget);
            return data instanceof PreviewPlayerData preview ? preview : null;
        } catch (Throwable t) {
            E2ELog.warn("PlayerWidget.previewData: " + t);
            return null;
        }
    }

    private static PlayerWidget findPlayerWidget(Screen screen) {
        if (screen == null) return null;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof PlayerWidget widget) return widget;
        }
        return null;
    }

    private static Button findChangeSkinButton(Screen screen, boolean essential) {
        if (screen == null) return null;
        for (GuiEventListener child : screen.children()) {
            if (essential) {
                if (child instanceof IconActionButton icon) return icon;
                continue;
            }
            if (child instanceof Button button && isChangeSkinLabel(button.getMessage())) return button;
        }
        return null;
    }

    private static boolean isChangeSkinLabel(Component message) {
        if (message == null) return false;
        if (message.getContents() instanceof TranslatableContents translated
                && CHANGE_SKIN_KEY.equals(translated.getKey())) {
            return true;
        }
        String translated = Component.translatable(CHANGE_SKIN_KEY).getString();
        return !translated.isEmpty() && translated.equals(message.getString());
    }

    // ===== inventory / tab list evidence ========================================================

    /**
     * {@code null} once a vanilla inventory that draws the paper doll is open. A creative player is
     * redirected to the creative inventory by {@code InventoryScreen.init()}; only its Inventory tab
     * renders the player, so select that tab when another one is showing.
     */
    private static String inventoryProblem(Minecraft mc) {
        Screen screen = VanillaShim.currentScreen(mc);
        if (screen instanceof InventoryScreen) return null;
        if (!(screen instanceof CreativeModeInventoryScreen creative)) {
            return "inventory screen not open: " + screenName(mc);
        }
        if (creative.isInventoryOpen()) return null;
        String selection = selectCreativeInventoryTab(creative);
        return selection != null ? selection : "creative Inventory tab is not selected yet";
    }

    private static String selectCreativeInventoryTab(CreativeModeInventoryScreen creative) {
        try {
            // The registry lookup returns Optional<Holder.Reference<T>> from 1.21.11 onward.
            Object looked = BuiltInRegistries.CREATIVE_MODE_TAB.get(CreativeModeTabs.INVENTORY);
            CreativeModeTab inventoryTab =
                    VanillaShim.unwrapRegistryValue(looked) instanceof CreativeModeTab tab ? tab : null;
            if (inventoryTab == null || inventoryTab.getType() != CreativeModeTab.Type.INVENTORY) {
                return "creative Inventory tab is unavailable in the registry";
            }
            Method selectTab = null;
            for (Class<?> type = creative.getClass(); type != null && selectTab == null; type = type.getSuperclass()) {
                for (Method candidate : type.getDeclaredMethods()) {
                    String name = candidate.getName();
                    if ((name.equals("selectTab") || name.equals("method_2466") || name.equals("m_98560_"))
                            && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0].isInstance(inventoryTab)) {
                        candidate.setAccessible(true);
                        selectTab = candidate;
                        break;
                    }
                }
            }
            if (selectTab == null) return "CreativeModeInventoryScreen.selectTab is unavailable";
            selectTab.invoke(creative, inventoryTab);
            return null;
        } catch (Throwable failure) {
            return "could not select the creative Inventory tab: " + describe(failure);
        }
    }

    /** {@code null} while PlayerInfo (the paper doll's and tab list's skin source) shows the look. */
    private static String paperDollProblem(Minecraft mc, PlayerAppearanceService svc, UUID uuid, boolean cpm) {
        if (mc.player == null || mc.getConnection() == null) return "no live connection";
        Object info = mc.getConnection().getPlayerInfo(uuid);
        if (info == null) return "no PlayerInfo for " + uuid;
        Object serviceSkin = svc.getSkinLocation(uuid);
        if (serviceSkin == null) return "service skin location did not resolve";
        String infoSkin = playerInfoSkinTexture(info);
        String problem = skinLocationProblem(mc, infoSkin, String.valueOf(serviceSkin), cpm, "PlayerInfo skin");
        if (problem != null) return problem;
        String rendererSkin = VanillaShim.skinTexture(mc.player);
        if (cpm) {
            if (!String.valueOf(serviceSkin).equals(rendererSkin)) {
                return "renderer skin=" + rendererSkin + " expected Quick Skin location " + serviceSkin
                        + " while PlayerInfo exposes the CPM bridge " + infoSkin;
            }
            return null;
        }
        if (infoSkin == null || !infoSkin.equals(rendererSkin)) {
            return "renderer skin=" + rendererSkin + " differs from PlayerInfo skin=" + infoSkin;
        }
        return null;
    }

    private static String describePaperDoll(Minecraft mc, PlayerAppearanceService svc, UUID uuid, boolean cpm) {
        Object info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(uuid);
        String infoSkin = playerInfoSkinTexture(info);
        String rendererSkin = VanillaShim.skinTexture(mc.player);
        Object serviceSkin = svc.getSkinLocation(uuid);
        if (cpm) {
            return "PlayerInfo skin=" + infoSkin + " (CPM bridge; vanilla default="
                    + VanillaShim.expectedDefaultSkinTexture(mc.player) + "), renderer skin="
                    + rendererSkin + " == Quick Skin location " + serviceSkin;
        }
        return "PlayerInfo skin=" + infoSkin + " == renderer skin=" + rendererSkin
                + " == Quick Skin location " + serviceSkin;
    }

    private static String tabListProblem(Minecraft mc, PlayerAppearanceService svc, UUID uuid, boolean cpm) {
        if (VanillaShim.currentScreen(mc) != null) return "a screen is open over the HUD: " + screenName(mc);
        if (mc.options == null || !mc.options.keyPlayerList.isDown()) return "keyPlayerList is not held";
        Boolean visible = tabListVisible(mc);
        if (Boolean.FALSE.equals(visible)) return "player list overlay is not visible";
        return paperDollProblem(mc, svc, uuid, cpm);
    }

    /**
     * The overlay's private visibility flag, set by {@code Gui.render} while the key is held, or
     * {@code null} when this runtime's mapping does not expose it.
     */
    private static Boolean tabListVisible(Minecraft mc) {
        try {
            Object gui = mc.gui;
            if (gui == null) return null;
            Method getTabList = findNoArg(gui.getClass(), "getTabList", "method_1750", "m_93088_");
            Object overlay = getTabList == null ? null : getTabList.invoke(gui);
            if (overlay == null) return null;
            Field visible = findField(overlay.getClass(), "visible", "field_2158", "f_94524_");
            if (visible == null || visible.getType() != boolean.class) return null;
            return visible.getBoolean(overlay);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@code PlayerInfo.getSkinLocation()} (1.20.1) or the texture component of
     * {@code PlayerInfo.getSkin()} (1.21.x/26.x), as a string.
     */
    private static String playerInfoSkinTexture(Object info) {
        if (info == null) return null;
        try {
            Method direct = findNoArg(info.getClass(), "getSkinLocation", "method_2968", "m_105337_");
            if (direct != null) {
                Object location = direct.invoke(info);
                return location == null ? null : location.toString();
            }
            Method getSkin = findNoArg(info.getClass(), "getSkin", "method_52810");
            Object skin = getSkin == null ? null : getSkin.invoke(info);
            Method texture = skin == null
                    ? null
                    : findNoArg(skin.getClass(), "texture", "body", "comp_1626");
            Object value = texture == null ? null : texture.invoke(skin);
            if (value == null) return null;
            Method texturePath = findNoArg(value.getClass(), "texturePath", "comp_3627");
            Object location = texturePath != null ? texturePath.invoke(value) : value;
            return location == null ? null : location.toString();
        } catch (Throwable t) {
            E2ELog.warn("playerInfoSkinTexture: " + t);
            return null;
        }
    }

    // ===== reflection utilities ==================================================================

    private static Method findNoArg(Class<?> type, String... names) {
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0) continue;
            for (String name : names) {
                if (method.getName().equals(name)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String... names) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // Try the next mapping name or superclass.
                }
            }
        }
        return null;
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : failure;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static String screenName(Minecraft mc) {
        Screen screen = VanillaShim.currentScreen(mc);
        return screen == null ? "<none>" : screen.getClass().getName();
    }
}
