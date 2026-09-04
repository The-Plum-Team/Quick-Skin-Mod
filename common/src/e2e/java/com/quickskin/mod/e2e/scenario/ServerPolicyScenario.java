package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.panel.ActionButtonsPanel;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.util.GuiScaleManager;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.services.CapeService;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.e2e.DefaultSkinEvidenceView;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import com.quickskin.mod.networking.NetworkSyncService;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-policy scenario ({@code -Dquickskin.e2e.scenario=server-policy}): a single client joins a
 * dedicated server whose {@code config/quickskin-server.json} was seeded by the orchestrator with
 * {@code disableSkinTransparency=true} and {@code skinChangeCooldownSeconds=600}.
 *
 * <ol>
 *   <li>baseline &mdash; the server config has arrived ({@link ClientConfig#getServerOverride()}),
 *       the client's own transparency setting is untouched, and no cooldown is active.</li>
 *   <li>transparent_skin_server_policy &mdash; the transparent-window plaid skin is imported and
 *       applied; the served full-quality texture has the window filled with opaque black and the
 *       translucent sleeves made solid, the server echoes the appearance (first accepted change),
 *       and the 600 s cooldown starts.</li>
 *   <li>cooldown_skin_menu &mdash; a second, different skin is applied locally; the server drops it
 *       silently, so its sync never completes, and the skin menu's Done button reads
 *       {@code On Cooldown (Ns)} and is inactive.</li>
 *   <li>cape_change_during_cooldown &mdash; a cape change that keeps the server-accepted skin id is
 *       accepted and echoed while the cooldown stays active.</li>
 * </ol>
 *
 * <p><b>Acknowledgement observables.</b> {@link NetworkSyncService} has no public "is this
 * appearance acknowledged" query, so the harness reads its private session state reflectively
 * (mod-owned names, the established harness pattern):</p>
 * <ul>
 *   <li>{@code latestDesired} ({@code DesiredSync}: {@code token}, {@code skinId}, {@code capeId},
 *       {@code model}) is the last locally requested appearance; its {@code token} must equal
 *       {@code syncSequence.get()} to still be the current request.</li>
 *   <li>{@code preparingToken != 0}, {@code activeSync != null}, {@code queuedSync != null} mean the
 *       request is still being prepared/uploaded; {@code awaitingAcknowledgement}
 *       ({@code serverSkinId}, {@code serverCapeId}, {@code model}, {@code appearanceAcknowledged})
 *       is non-null from {@code armAcknowledgement} until the exact S2C echo hits
 *       {@code confirmAppearance}; {@code retryAtMillis > 0} while a retry is scheduled. All of
 *       these are null/zero only once {@code completeAcknowledgementIfReady} ran for the current
 *       token, i.e. the server echoed exactly the sent appearance.</li>
 *   <li>{@code confirmedUploadHashes} ({@code UploadKey(localHash, textureType) -> networkHash})
 *       gains the skin entry only through {@code confirmTextureId}, which runs on the server's own
 *       echo; {@code sentUploadHashes} holds the entry while the bytes were uploaded but no echo
 *       arrived.</li>
 * </ul>
 * <p>On cooldown {@code ServerNetworkHandler.applyAppearance} returns before
 * {@code broadcastAppearanceToPlayers}, so no S2C appearance reaches the local player at all: the
 * dropped skin stays "pending" (awaiting/retrying, never confirmed) and the previously confirmed
 * skin remains the server-side appearance.</p>
 */
public final class ServerPolicyScenario implements Scenario {

    /** Same close-but-unclipped rear view the full scenario uses for model evidence. */
    static final int REAR_EVIDENCE_FOV = 50;
    private static final int EXPECTED_COOLDOWN_SECONDS = 600;
    private static final String KNOWN_CAPE_ID = "known:test";
    private static final int WINDOW_PROBE_X = TestAssets.TRANSPARENT_SKIN_WINDOW_X + 1;
    private static final int WINDOW_PROBE_Y = TestAssets.TRANSPARENT_SKIN_WINDOW_Y + 1;

    private volatile Integer originalFov;
    volatile String transparentSkinHash;
    volatile String plaidSkinHash;
    /** Previous-poll layout stamp of the open skin menu; {@code Long.MIN_VALUE} = not held yet. */
    private long skinMenuLayoutStamp = Long.MIN_VALUE;
    /** Evidence text from the last acknowledged sync, carried into later step messages. */
    private final AtomicReference<String> acknowledgedTransparentSync = new AtomicReference<>();

    @Override
    public ScenarioId id() { return ScenarioId.SERVER_POLICY; }

    @Override
    public List<Step> build(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final UUID uuid = mc.player.getUUID();
        final PlayerAppearanceService svc = PlayerAppearanceService.getInstance();
        final String prefix = v + "_";
        final String suffix = "_" + role + ".png";

        List<Step> steps = new ArrayList<>();

        // 1. baseline: server policy received, client setting untouched, no cooldown -------------
        steps.add(Step.of("baseline")
                .action(() -> DefaultSkinEvidenceView.hold(mc, false))
                .minTicks(40) // ~2s render warmup so the first frame is real
                .ready(() -> ClientConfig.getInstance().getServerOverride() != null
                        && VanillaShim.isExpectedDefaultSkinResolved(mc.player)
                        && DefaultSkinEvidenceView.hold(mc, false))
                .settleTicks(20) // reject a one-frame generic fallback before the UUID skin lands
                .timeoutTicks(400)
                .screenshot(prefix + "policy_01_baseline" + suffix)
                .assertion(() -> {
                    if (mc.player == null) return Step.Result.fail("player is null");
                    String expected = VanillaShim.expectedDefaultSkinTexture(mc.player);
                    String actual = VanillaShim.skinTexture(mc.player);
                    if (expected == null || !expected.equals(actual)) {
                        return Step.Result.fail("default skin did not stabilize: expected="
                                + expected + " actual=" + actual);
                    }
                    ClientConfig config = ClientConfig.getInstance();
                    ServerConfig override = config.getServerOverride();
                    if (override == null) {
                        return Step.Result.fail("server config never arrived (serverOverride null)");
                    }
                    if (!override.disableSkinTransparency) {
                        return Step.Result.fail(
                                "server override disableSkinTransparency=false, expected true");
                    }
                    if (override.skinChangeCooldownSeconds != EXPECTED_COOLDOWN_SECONDS) {
                        return Step.Result.fail("server override skinChangeCooldownSeconds="
                                + override.skinChangeCooldownSeconds + ", expected "
                                + EXPECTED_COOLDOWN_SECONDS);
                    }
                    if (config.disableSkinTransparency) {
                        return Step.Result.fail(
                                "client disableSkinTransparency flipped to true; the server policy "
                                        + "must be an override, not a client setting change");
                    }
                    if (!config.shouldDisableSkinTransparency()) {
                        return Step.Result.fail(
                                "shouldDisableSkinTransparency()=false despite the server override");
                    }
                    CooldownService cooldown = CooldownService.getInstance();
                    if (cooldown.isCooldownActive()) {
                        return Step.Result.fail("cooldown already active before any skin change: "
                                + cooldown.getRemainingCooldownSeconds() + "s remaining");
                    }
                    if (svc.hasActiveSkin(uuid) || svc.hasActiveCape(uuid)) {
                        return Step.Result.fail("baseline has a custom appearance: skin="
                                + svc.hasActiveSkin(uuid) + " cape=" + svc.hasActiveCape(uuid));
                    }
                    return Step.Result.pass("player present: " + VanillaShim.playerName(mc.player)
                            + " defaultSkin=" + actual
                            + "; server override disableSkinTransparency=true"
                            + " skinChangeCooldownSeconds=" + override.skinChangeCooldownSeconds
                            + "; client disableSkinTransparency=false"
                            + " shouldDisableSkinTransparency=true; cooldown inactive;"
                            + " full-body evidence held");
                }));

        // 2. transparent skin under the server transparency policy ------------------------------
        steps.add(Step.of("transparent_skin_server_policy")
                .action(() -> {
                    prepareRearEvidenceView(mc);
                    try {
                        Path file = TestAssets.makeTransparentSkin();
                        AssetMetadata meta = SkinImporter.importSkin(file);
                        if (meta == null) { E2ELog.warn("importSkin returned null"); return; }
                        transparentSkinHash = meta.hash();
                        svc.applySkin(uuid, "local_skin:" + transparentSkinHash, "classic");
                        E2ELog.info("applied transparent local_skin:" + transparentSkinHash);
                    } catch (Exception e) {
                        E2ELog.error("transparent_skin_server_policy action failed", e);
                    }
                })
                .minTicks(30)
                .ready(() -> {
                    String hash = transparentSkinHash;
                    if (hash == null || !holdRearEvidenceView(mc)) return false;
                    if (!rendererShowsServiceSkin(mc, svc, uuid)
                            || !"classic".equals(VanillaShim.playerModel(mc.player))) return false;
                    if (servedTransparencyEvidence(hash) == null) return false;
                    if (!CooldownService.getInstance().isCooldownActive()) return false;
                    SyncState sync = SyncState.read();
                    return sync != null
                            && sync.acknowledged("local_skin:" + hash, "", "classic") != null;
                })
                .settleTicks(12)
                .timeoutTicks(400)
                .screenshot(prefix + "policy_02_transparent_skin_server_policy" + suffix)
                .assertion(() -> {
                    String hash = transparentSkinHash;
                    if (hash == null) return Step.Result.fail("transparent skin import failed");
                    Step.Result view = assertRearEvidenceView(mc, svc, uuid, hash, "classic");
                    if (!view.pass()) return view;

                    // The source really is transparent; only the served texture is not.
                    BufferedImage source = SafeImageReader.readPng(TestAssets.makeTransparentSkin());
                    int sourceWindow = source.getRGB(WINDOW_PROBE_X, WINDOW_PROBE_Y);
                    int sourceSleeve = source.getRGB(TestAssets.TRANSLUCENT_SLEEVE_PROBE_X,
                            TestAssets.TRANSLUCENT_SLEEVE_PROBE_Y);
                    if ((sourceWindow >>> 24) != 0
                            || (sourceSleeve >>> 24) != TestAssets.TRANSLUCENT_SLEEVE_ALPHA) {
                        return Step.Result.fail("transparent skin fixture drifted: window="
                                + hex(sourceWindow) + " sleeve=" + hex(sourceSleeve));
                    }
                    String served = servedTransparencyEvidence(hash);
                    if (served == null) {
                        return Step.Result.fail("served FULL texture of " + hash
                                + " still carries transparency: " + servedProbeDescription(hash));
                    }

                    CooldownService cooldown = CooldownService.getInstance();
                    long remaining = cooldown.getRemainingCooldownSeconds();
                    if (!cooldown.isCooldownActive() || remaining <= 0) {
                        return Step.Result.fail(
                                "server did not start the skin-change cooldown: remaining="
                                        + remaining);
                    }
                    if (remaining > EXPECTED_COOLDOWN_SECONDS) {
                        return Step.Result.fail("cooldown remaining " + remaining
                                + "s exceeds the configured " + EXPECTED_COOLDOWN_SECONDS + "s");
                    }
                    SyncState sync = SyncState.read();
                    if (sync == null) return Step.Result.fail("could not read NetworkSyncService");
                    String acknowledged = sync.acknowledged("local_skin:" + hash, "", "classic");
                    if (acknowledged == null) {
                        return Step.Result.fail("server never echoed the transparent skin: "
                                + sync.describe());
                    }
                    acknowledgedTransparentSync.set(acknowledged);
                    return Step.Result.pass("local_skin:" + hash + " classic at rear FOV "
                            + REAR_EVIDENCE_FOV + "; source window alpha 0 / sleeve alpha "
                            + TestAssets.TRANSLUCENT_SLEEVE_ALPHA + " served as " + served
                            + " (server policy, client setting off); " + acknowledged
                            + "; cooldown active " + remaining + "s of "
                            + EXPECTED_COOLDOWN_SECONDS + "s");
                }));

        // 3. second skin change is refused; skin menu shows the cooldown --------------------------
        steps.add(Step.of("cooldown_skin_menu")
                .action(() -> {
                    skinMenuLayoutStamp = Long.MIN_VALUE;
                    try {
                        Path file = TestAssets.makeClassicSkin();
                        AssetMetadata meta = SkinImporter.importSkin(file);
                        if (meta == null) { E2ELog.warn("importSkin returned null"); return; }
                        plaidSkinHash = meta.hash();
                        if (plaidSkinHash.equals(transparentSkinHash)) {
                            E2ELog.warn("second skin has the same hash as the first; "
                                    + "the cooldown check would be vacuous");
                            plaidSkinHash = null;
                            return;
                        }
                        svc.applySkin(uuid, "local_skin:" + plaidSkinHash, "classic");
                        E2ELog.info("applied second local_skin:" + plaidSkinHash
                                + " during cooldown");
                    } catch (Exception e) {
                        E2ELog.error("cooldown_skin_menu action failed", e);
                    }
                    VanillaShim.setScreen(mc, new PlayerSkinMenuScreen(null));
                })
                .minTicks(30)
                .ready(() -> {
                    if (plaidSkinHash == null || !skinMenuLayoutSettled(mc)) return false;
                    if (cooldownButtonFailure(mc) != null) return false;
                    SyncState sync = SyncState.read();
                    return sync != null
                            && sync.pending("local_skin:" + plaidSkinHash, "", "classic") != null;
                })
                .settleTicks(20)
                .timeoutTicks(400)
                .screenshot(prefix + "policy_03_cooldown_skin_menu" + suffix)
                .assertion(() -> {
                    if (plaidSkinHash == null) return Step.Result.fail("second skin import failed");
                    if (!(VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen)) {
                        return Step.Result.fail("skin menu not open: " + screenName(mc));
                    }
                    int scale = VanillaShim.guiScale(mc);
                    if (scale != GuiScaleManager.getOptimalMenuScale()) {
                        return Step.Result.fail("menu GUI scale never settled: window=" + scale
                                + " expected=" + GuiScaleManager.getOptimalMenuScale());
                    }
                    String buttonFailure = cooldownButtonFailure(mc);
                    if (buttonFailure != null) return Step.Result.fail(buttonFailure);
                    Button done = doneButton(mc);
                    String label = done == null ? null : done.getMessage().getString();
                    long remaining = CooldownService.getInstance().getRemainingCooldownSeconds();

                    PlayerAppearance app = svc.getAppearance(uuid);
                    String localSkinId = "local_skin:" + plaidSkinHash;
                    if (app == null || !localSkinId.equals(app.getSkinId())) {
                        return Step.Result.fail("local appearance skinId="
                                + (app == null ? null : app.getSkinId()) + " expected "
                                + localSkinId);
                    }
                    SyncState sync = SyncState.read();
                    if (sync == null) return Step.Result.fail("could not read NetworkSyncService");
                    String pending = sync.pending(localSkinId, "", "classic");
                    if (pending == null) {
                        return Step.Result.fail("second skin was not left pending by the server: "
                                + sync.describe());
                    }
                    String firstHash = transparentSkinHash;
                    if (firstHash == null || !sync.confirmedSkinUploads.containsKey(firstHash)) {
                        return Step.Result.fail("first (transparent) skin lost its server "
                                + "confirmation: " + sync.describe());
                    }
                    return Step.Result.pass("PlayerSkinMenuScreen open at scale " + scale
                            + "; Done button inactive, label=\"" + label + "\" (cooldown "
                            + remaining + "s remaining); second " + localSkinId
                            + " applied locally but " + pending
                            + "; server-side skin remains the confirmed transparent skin "
                            + firstHash + " -> " + sync.confirmedSkinUploads.get(firstHash)
                            + " (" + acknowledgedTransparentSync.get() + ")");
                }));

        // 4. cape change with the accepted skin id is allowed during the cooldown ----------------
        steps.add(Step.of("cape_change_during_cooldown")
                .action(() -> {
                    prepareRearEvidenceView(mc);
                    String hash = transparentSkinHash;
                    if (hash == null) {
                        E2ELog.warn("no transparent skin hash; cannot restore the accepted skin");
                        return;
                    }
                    // Keep the skin id the server currently holds so only the cape changes;
                    // a cape-only change is not a skin change and passes the cooldown check.
                    svc.applyLook(uuid, "local_skin:" + hash, KNOWN_CAPE_ID, "classic");
                    E2ELog.info("applied " + KNOWN_CAPE_ID + " with local_skin:" + hash);
                })
                .minTicks(30)
                .ready(() -> {
                    String hash = transparentSkinHash;
                    if (hash == null || !holdRearEvidenceView(mc)) return false;
                    if (!FullScenario.hasExpectedCape(svc, uuid, KNOWN_CAPE_ID)) return false;
                    if (!rendererShowsServiceSkin(mc, svc, uuid)) return false;
                    String cloak = VanillaShim.cloakTexture(mc.player);
                    if (cloak == null || !cloak.equals(String.valueOf(svc.getCapeLocation(uuid)))) {
                        return false;
                    }
                    if (!CooldownService.getInstance().isCooldownActive()) return false;
                    SyncState sync = SyncState.read();
                    return sync != null && sync.acknowledged(
                            "local_skin:" + hash, KNOWN_CAPE_ID, "classic") != null;
                })
                .settleTicks(12)
                .timeoutTicks(400)
                .screenshot(prefix + "policy_04_cape_change_during_cooldown" + suffix)
                .assertion(() -> {
                    try {
                        String hash = transparentSkinHash;
                        if (hash == null) return Step.Result.fail("no transparent skin hash");
                        Step.Result view = assertRearEvidenceView(mc, svc, uuid, hash, "classic");
                        if (!view.pass()) return view;
                        Step.Result cape = FullScenario.assertCapeRoute(
                                mc, svc, uuid, KNOWN_CAPE_ID, false);
                        if (!cape.pass()) return cape;
                        Object expectedCape = CapeService.getInstance()
                                .getCapeLocation(null, KNOWN_CAPE_ID);
                        CooldownService cooldown = CooldownService.getInstance();
                        long remaining = cooldown.getRemainingCooldownSeconds();
                        if (!cooldown.isCooldownActive() || remaining <= 0) {
                            return Step.Result.fail("cooldown ended prematurely: remaining="
                                    + remaining);
                        }
                        SyncState sync = SyncState.read();
                        if (sync == null) {
                            return Step.Result.fail("could not read NetworkSyncService");
                        }
                        String acknowledged = sync.acknowledged(
                                "local_skin:" + hash, KNOWN_CAPE_ID, "classic");
                        if (acknowledged == null) {
                            return Step.Result.fail("server never echoed the cape change: "
                                    + sync.describe());
                        }
                        return Step.Result.pass(KNOWN_CAPE_ID + " resolved to " + expectedCape
                                + " on the renderer cloak over local_skin:" + hash
                                + " at rear FOV " + REAR_EVIDENCE_FOV + "; " + acknowledged
                                + "; cooldown still active " + remaining + "s");
                    } finally {
                        restoreEvidenceView(mc);
                    }
                }));

        return steps;
    }

    // ===== rear evidence view (mirrors FullScenario's model-evidence helpers) ===================

    private void enterRearView(Minecraft mc) {
        try {
            VanillaShim.setScreen(mc, null);
            if (mc.options != null) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                mc.options.keyShift.setDown(false);
            }
            if (mc.player != null) {
                mc.player.setShiftKeyDown(false);
                DefaultSkinEvidenceView.pinStandingPose(mc.player, 180f);
            }
        } catch (Throwable t) {
            E2ELog.warn("enterRearView: " + t);
        }
    }

    /** Zoom the rear view so the window and sleeves are inspectable; restored at the end. */
    private void prepareRearEvidenceView(Minecraft mc) {
        enterRearView(mc);
        Integer current = VanillaShim.fieldOfView(mc);
        if (originalFov == null && current != null) originalFov = current;
        VanillaShim.setFieldOfView(mc, REAR_EVIDENCE_FOV);
        if (mc.player != null) DefaultSkinEvidenceView.pinStandingPose(mc.player, 180f);
    }

    /** Hold camera, pose and FOV through the screenshot settle window. */
    private boolean holdRearEvidenceView(Minecraft mc) {
        if (mc.player == null || mc.options == null) return false;
        VanillaShim.setScreen(mc, null);
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        mc.options.keyShift.setDown(false);
        mc.player.setShiftKeyDown(false);
        DefaultSkinEvidenceView.pinStandingPose(mc.player, 180f);
        return VanillaShim.setFieldOfView(mc, REAR_EVIDENCE_FOV)
                && Integer.valueOf(REAR_EVIDENCE_FOV).equals(VanillaShim.fieldOfView(mc));
    }

    private void restoreEvidenceView(Minecraft mc) {
        Integer original = originalFov;
        if (original != null) {
            VanillaShim.setFieldOfView(mc, original);
            originalFov = null;
        }
    }

    private static boolean rendererShowsServiceSkin(
            Minecraft mc, PlayerAppearanceService svc, UUID uuid) {
        Object location = svc.getSkinLocation(uuid);
        return location != null && mc.player != null
                && String.valueOf(location).equals(VanillaShim.skinTexture(mc.player));
    }

    private static Step.Result assertRearEvidenceView(
            Minecraft mc, PlayerAppearanceService svc, UUID uuid,
            String skinHash, String expectedModel) {
        if (mc.player == null) return Step.Result.fail("player is null");
        PlayerAppearance app = svc.getAppearance(uuid);
        if (app == null) return Step.Result.fail("no appearance");
        String expectedSkinId = "local_skin:" + skinHash;
        if (!expectedSkinId.equals(app.getSkinId())) {
            return Step.Result.fail("skinId=" + app.getSkinId() + " expected " + expectedSkinId);
        }
        if (!expectedModel.equals(app.getModel())) {
            return Step.Result.fail("stored model=" + app.getModel() + " expected " + expectedModel);
        }
        String renderedModel = VanillaShim.playerModel(mc.player);
        if (!expectedModel.equals(renderedModel)) {
            return Step.Result.fail("renderer model=" + renderedModel + " expected " + expectedModel);
        }
        Object location = svc.getSkinLocation(uuid);
        String rendered = VanillaShim.skinTexture(mc.player);
        if (location == null || !String.valueOf(location).equals(rendered)) {
            return Step.Result.fail("renderer skin=" + rendered + " expected " + location);
        }
        if (!Integer.valueOf(REAR_EVIDENCE_FOV).equals(VanillaShim.fieldOfView(mc))) {
            return Step.Result.fail("evidence FOV=" + VanillaShim.fieldOfView(mc)
                    + " expected " + REAR_EVIDENCE_FOV);
        }
        if (mc.options == null || mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
            return Step.Result.fail("camera is not third-person back");
        }
        return Step.Result.pass("rear view held");
    }

    // ===== served transparency evidence =========================================================

    /**
     * Decode the FULL-quality texture the client serves for {@code hash} and return a bounded
     * description when the server policy visibly applied: the transparent window pixel is opaque
     * black and the translucent sleeve pixel is fully opaque. {@code null} otherwise.
     */
    private static String servedTransparencyEvidence(String hash) {
        try {
            byte[] png = LocalAssetManager.getInstance().loadTexture(hash, TextureQuality.FULL);
            if (png == null) return null;
            BufferedImage image = SafeImageReader.readPng(png);
            if (image == null || image.getWidth() < 64 || image.getHeight() < 64) return null;
            int window = image.getRGB(WINDOW_PROBE_X, WINDOW_PROBE_Y);
            int sleeve = image.getRGB(TestAssets.TRANSLUCENT_SLEEVE_PROBE_X,
                    TestAssets.TRANSLUCENT_SLEEVE_PROBE_Y);
            if (window != 0xFF000000 || (sleeve >>> 24) != 0xFF) return null;
            return "window(" + WINDOW_PROBE_X + "," + WINDOW_PROBE_Y + ")=" + hex(window)
                    + " sleeve(" + TestAssets.TRANSLUCENT_SLEEVE_PROBE_X + ","
                    + TestAssets.TRANSLUCENT_SLEEVE_PROBE_Y + ")=" + hex(sleeve)
                    + " in " + image.getWidth() + "x" + image.getHeight();
        } catch (Exception e) {
            E2ELog.warn("servedTransparencyEvidence: " + e);
            return null;
        }
    }

    private static String servedProbeDescription(String hash) {
        try {
            byte[] png = LocalAssetManager.getInstance().loadTexture(hash, TextureQuality.FULL);
            if (png == null) return "loadTexture returned null";
            BufferedImage image = SafeImageReader.readPng(png);
            if (image == null) return "decode failed";
            return "window=" + hex(image.getRGB(WINDOW_PROBE_X, WINDOW_PROBE_Y))
                    + " sleeve=" + hex(image.getRGB(TestAssets.TRANSLUCENT_SLEEVE_PROBE_X,
                            TestAssets.TRANSLUCENT_SLEEVE_PROBE_Y));
        } catch (Exception e) {
            return "probe failed: " + e;
        }
    }

    private static String hex(int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    // ===== skin menu / Done button ==============================================================

    /** Same settled-layout predicate as {@code FullScenario.skinMenuLayoutSettled}. */
    private boolean skinMenuLayoutSettled(Minecraft mc) {
        Screen sc = VanillaShim.currentScreen(mc);
        if (!(sc instanceof PlayerSkinMenuScreen)
                || VanillaShim.guiScale(mc) != GuiScaleManager.getOptimalMenuScale()) {
            skinMenuLayoutStamp = Long.MIN_VALUE;
            return false;
        }
        long stamp = ((long) mc.getWindow().getGuiScaledWidth() * 31L
                + mc.getWindow().getGuiScaledHeight()) * 31L + sc.children().size();
        if (stamp != skinMenuLayoutStamp) {
            skinMenuLayoutStamp = stamp;
            return false;
        }
        return true;
    }

    private static Button doneButton(Minecraft mc) {
        if (!(VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen screen)) return null;
        Object panel = FullScenario.screenField(screen, "actionButtonsPanel");
        if (!(panel instanceof ActionButtonsPanel buttons)) return null;
        return buttons.getDoneButton();
    }

    /**
     * {@code null} when the Done button is inactive and labelled with the translated
     * {@code quickskin.cooldown.button} text carrying a positive countdown that agrees with
     * {@link CooldownService}; otherwise a failure description.
     */
    private static String cooldownButtonFailure(Minecraft mc) {
        Button done = doneButton(mc);
        if (done == null) return "skin menu Done button not built yet";
        if (done.active) return "Done button is still active during the cooldown";
        String label = done.getMessage().getString();
        String template = Component.translatable("quickskin.cooldown.button", 0).getString();
        String shape = template.replaceAll("[0-9]+", "#");
        if (!label.replaceAll("[0-9]+", "#").equals(shape)) {
            return "Done label \"" + label + "\" does not match cooldown template \""
                    + template + "\"";
        }
        long shown;
        try {
            shown = Long.parseLong(label.replaceAll("[^0-9]+", ""));
        } catch (NumberFormatException e) {
            return "Done label \"" + label + "\" carries no countdown";
        }
        long remaining = CooldownService.getInstance().getRemainingCooldownSeconds();
        if (shown <= 0 || remaining <= 0 || Math.abs(shown - remaining) > 3) {
            return "Done label countdown " + shown + "s disagrees with CooldownService "
                    + remaining + "s";
        }
        return null;
    }

    private static String screenName(Minecraft mc) {
        Screen sc = VanillaShim.currentScreen(mc);
        return sc == null ? "<none>" : sc.getClass().getName();
    }

    // ===== NetworkSyncService acknowledgement observables =======================================

    /** Reflective snapshot of the private session state documented in the class Javadoc. */
    static final class SyncState {
        final boolean desiredCurrent;
        final String desiredSkinId;
        final String desiredCapeId;
        final String desiredModel;
        final boolean preparing;
        final boolean activeSync;
        final boolean queuedSync;
        final long retryAtMillis;
        /** {@code null} when nothing is armed. */
        final String awaitingSkinId;
        final String awaitingCapeId;
        final String awaitingModel;
        final boolean awaitingAppearanceAcknowledged;
        /** local skin hash -> server-echoed network hash. */
        final Map<String, String> confirmedSkinUploads = new LinkedHashMap<>();
        /** local skin hash -> uploaded network hash awaiting any echo. */
        final Map<String, String> sentSkinUploads = new LinkedHashMap<>();

        private SyncState(NetworkSyncService service) throws ReflectiveOperationException {
            long sequence = ((java.util.concurrent.atomic.AtomicLong)
                    member(service, "syncSequence")).get();
            Object desired = member(service, "latestDesired");
            if (desired != null) {
                desiredCurrent = ((Long) member(desired, "token")) == sequence;
                desiredSkinId = (String) member(desired, "skinId");
                desiredCapeId = (String) member(desired, "capeId");
                desiredModel = (String) member(desired, "model");
            } else {
                desiredCurrent = false;
                desiredSkinId = null;
                desiredCapeId = null;
                desiredModel = null;
            }
            preparing = ((Long) member(service, "preparingToken")) != 0L;
            activeSync = member(service, "activeSync") != null;
            queuedSync = member(service, "queuedSync") != null;
            retryAtMillis = (Long) member(service, "retryAtMillis");
            Object awaiting = member(service, "awaitingAcknowledgement");
            if (awaiting != null) {
                awaitingSkinId = (String) member(awaiting, "serverSkinId");
                awaitingCapeId = (String) member(awaiting, "serverCapeId");
                awaitingModel = (String) member(awaiting, "model");
                awaitingAppearanceAcknowledged =
                        (Boolean) member(awaiting, "appearanceAcknowledged");
            } else {
                awaitingSkinId = null;
                awaitingCapeId = null;
                awaitingModel = null;
                awaitingAppearanceAcknowledged = false;
            }
            Map<?, ?> confirmed = (Map<?, ?>) member(service, "confirmedUploadHashes");
            for (Map.Entry<?, ?> entry : confirmed.entrySet()) {
                if ("skin".equals(member(entry.getKey(), "textureType"))) {
                    confirmedSkinUploads.put((String) member(entry.getKey(), "localHash"),
                            String.valueOf(entry.getValue()));
                }
            }
            Map<?, ?> sent = (Map<?, ?>) member(service, "sentUploadHashes");
            for (Map.Entry<?, ?> entry : sent.entrySet()) {
                if ("skin".equals(member(entry.getKey(), "textureType"))) {
                    sentSkinUploads.put((String) member(entry.getKey(), "localHash"),
                            (String) member(entry.getValue(), "networkHash"));
                }
            }
        }

        static SyncState read() {
            try {
                return new SyncState(NetworkSyncService.getInstance());
            } catch (ReflectiveOperationException | RuntimeException e) {
                E2ELog.warn("NetworkSyncService state unreadable: " + e);
                return null;
            }
        }

        private boolean desires(String skinId, String capeId, String model) {
            return desiredCurrent && skinId.equals(desiredSkinId)
                    && capeId.equals(desiredCapeId) && model.equals(desiredModel);
        }

        private boolean settled() {
            return !preparing && !activeSync && !queuedSync
                    && awaitingSkinId == null && retryAtMillis == 0L;
        }

        private static String localSkinHash(String skinId) {
            return skinId.startsWith("local_skin:")
                    ? skinId.substring("local_skin:".length()) : null;
        }

        /**
         * Evidence text when the exact local appearance is the current request, every pending /
         * awaiting / retry marker is clear (only the server's exact echo clears them), and a
         * local skin's upload moved into {@code confirmedUploadHashes}; {@code null} otherwise.
         */
        String acknowledged(String skinId, String capeId, String model) {
            if (!desires(skinId, capeId, model) || !settled()) return null;
            String localHash = localSkinHash(skinId);
            if (localHash == null) {
                return "server echoed appearance skin=" + skinId + " cape=" + capeId
                        + " model=" + model;
            }
            String networkHash = confirmedSkinUploads.get(localHash);
            if (networkHash == null) return null;
            return "server echoed appearance skin=" + skinId + " cape=" + capeId
                    + " model=" + model + " (confirmedUploadHashes[" + localHash
                    + ",skin]=" + networkHash + ", awaitingAcknowledgement cleared)";
        }

        /**
         * Evidence text when the exact local appearance is the current request but was never
         * echoed: still preparing/uploading, armed and unacknowledged, or between retries, with no
         * confirmed upload for its local skin; {@code null} otherwise.
         */
        String pending(String skinId, String capeId, String model) {
            if (!desires(skinId, capeId, model) || settled()) return null;
            String localHash = localSkinHash(skinId);
            if (localHash != null && confirmedSkinUploads.containsKey(localHash)) return null;
            StringBuilder text = new StringBuilder("server never echoed skin=")
                    .append(skinId).append(" cape=").append(capeId).append(" model=")
                    .append(model).append(": ");
            if (awaitingSkinId != null) {
                text.append("awaitingAcknowledgement{serverSkinId=").append(awaitingSkinId)
                        .append(", appearanceAcknowledged=")
                        .append(awaitingAppearanceAcknowledged).append('}');
            } else {
                text.append("preparing=").append(preparing).append(" activeSync=")
                        .append(activeSync).append(" queuedSync=").append(queuedSync)
                        .append(" retryScheduled=").append(retryAtMillis > 0L);
            }
            if (localHash != null) {
                String uploaded = sentSkinUploads.get(localHash);
                text.append("; bytes uploaded as ").append(uploaded == null
                        ? "(not in sentUploadHashes)" : uploaded)
                        .append(", absent from confirmedUploadHashes");
            }
            return text.toString();
        }

        String describe() {
            return "desired{current=" + desiredCurrent + " skin=" + desiredSkinId + " cape="
                    + desiredCapeId + " model=" + desiredModel + "} preparing=" + preparing
                    + " activeSync=" + activeSync + " queuedSync=" + queuedSync
                    + " retryAtMillis=" + retryAtMillis + " awaiting="
                    + (awaitingSkinId == null ? "none" : "{skin=" + awaitingSkinId + " cape="
                    + awaitingCapeId + " model=" + awaitingModel + " acked="
                    + awaitingAppearanceAcknowledged + "}")
                    + " confirmedSkins=" + confirmedSkinUploads
                    + " sentSkins=" + sentSkinUploads;
        }

        private static Object member(Object target, String name)
                throws ReflectiveOperationException {
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name + " on " + target.getClass().getName());
        }
    }
}
