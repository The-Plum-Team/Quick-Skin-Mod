package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.e2e.DefaultSkinEvidenceView;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import com.quickskin.mod.networking.NetworkSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 1 scenario: prove that a custom skin+cape applied by player A (subject) actually propagates
 * over the network and is <em>rendered</em> by player B (observer).
 *
 * <p>This is the most important property the whole suite exists to verify and the hardest to eyeball
 * by hand. One scenario id ({@code "propagation"}) drives both clients; the role is selected by
 * {@code -Dquickskin.e2e.role} ({@code client_a} vs {@code client_b}).</p>
 *
 * <h3>Subject (A)</h3>
 * Imports a local skin (real {@code SkinImporter}) and registers a local cape headlessly, then applies
 * both with {@code local_skin:}/{@code local_cape:} ids — the only ids whose texture <em>bytes</em>
 * flow over the wire. Applying to the local UUID makes {@code PlayerAppearanceService.applyLook} call
 * {@code NetworkSyncService.syncAppearance}, uploading the bytes (TEXTURE_CHUNK) and metadata
 * (UPDATE_APPEARANCE) to the server. A then idles, staying connected so B can observe it.
 *
 * <h3>Observer (B)</h3>
 * <ol>
 *   <li><b>confirm_self</b> — sends one C2S packet ({@code syncAppearance(B,"","","classic")}) so the
 *       server confirms the exact connection's negotiated or legacy protocol session; only then
 *       does it relay other players' appearances to B (and back-fill A's applied look).</li>
 *   <li><b>await_propagation</b> — waits (tick timeout, never wall-clock) until B has received A's
 *       appearance + texture bytes and the render path resolves to the network location.</li>
 *   <li><b>observe_a</b> — frames A in B's camera and screenshots it, re-asserting the full check.</li>
 * </ol>
 * The render-truthful assertion casts A's entity to {@link AbstractClientPlayer} and checks
 * {@code getSkinTextureLocation()}/{@code getCloakTextureLocation()} both equal
 * {@code quickskin:network/<hash>} (the location {@code NetworkTextureCache} registers received bytes
 * under). Compared via {@code ResourceLocation.toString()} so it stays version-agnostic.
 */
public final class PropagationScenario implements Scenario {

    /** Fixed subject pose used to make the remote cape checkpoint an unambiguous rear view. */
    private static final float SUBJECT_REAR_YAW = 180.0f;

    /** Set by A's apply action; read by A's ready/assert. */
    private volatile String skinHash;
    private volatile String capeHash;

    /** B's cached observation vantage (computed once from A's pose) + walk/settle bookkeeping. */
    private double tgtX, tgtY, tgtZ;
    private boolean vantageSet = false;
    private int settleTicks = 0;

    @Override
    public ScenarioId id() { return ScenarioId.PROPAGATION; }

    @Override
    public List<Step> build(Minecraft mc) {
        String role = System.getProperty("quickskin.e2e.role", "client_a");
        return "client_b".equals(role) ? buildObserver(mc) : buildSubject(mc);
    }

    // ===== A: subject =====================================================================
    private List<Step> buildSubject(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final UUID uuid = mc.player.getUUID();
        final PlayerAppearanceService appearance = PlayerAppearanceService.getInstance();

        List<Step> steps = new ArrayList<>();
        steps.add(baseline(mc, v, role));

        steps.add(Step.of("apply_local_look")
                .action(() -> {
                    DefaultSkinEvidenceView.enterFirstPerson(mc);
                    try {
                        Path skinFile = TestAssets.makeClassicSkin();
                        AssetMetadata skinMeta = SkinImporter.importSkin(skinFile);
                        if (skinMeta == null) {
                            E2ELog.warn("SkinImporter.importSkin returned null");
                            return;
                        }
                        skinHash = skinMeta.hash();

                        Path capeFile = TestAssets.makeClassicCape();
                        String ch = TestAssets.registerLocalCape(capeFile);
                        if (ch == null) {
                            E2ELog.warn("registerLocalCape returned null");
                            return;
                        }
                        capeHash = ch;

                        // One call applies BOTH and (local player) uploads both textures to the server.
                        appearance.applyLook(uuid, "local_skin:" + skinHash, "local_cape:" + capeHash, "auto");
                        E2ELog.info("A applied+synced local_skin:" + skinHash + " local_cape:" + capeHash);
                    } catch (Exception e) {
                        E2ELog.error("apply_local_look action failed", e);
                    }
                })
                .minTicks(40)
                .ready(() -> skinHash != null && capeHash != null
                        && appearance.getAppearance(uuid) != null
                        && appearance.getSkinLocation(uuid) != null
                        && appearance.getCapeLocation(uuid) != null)
                .timeoutTicks(400)
                .screenshot(v + "_03_propagation_applied_" + role + ".png")
                .assertion(() -> {
                    if (skinHash == null) return Step.Result.fail("skin import failed (no hash)");
                    if (capeHash == null) return Step.Result.fail("cape register failed (no hash)");
                    PlayerAppearance app = appearance.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no local appearance");
                    String es = "local_skin:" + skinHash;
                    String ec = "local_cape:" + capeHash;
                    if (!es.equals(app.getSkinId()))
                        return Step.Result.fail("skinId=" + app.getSkinId() + " expected " + es);
                    if (!ec.equals(app.getCapeId()))
                        return Step.Result.fail("capeId=" + app.getCapeId() + " expected " + ec);
                    if (appearance.getSkinLocation(uuid) == null)
                        return Step.Result.fail("skin ResourceLocation did not resolve");
                    if (appearance.getCapeLocation(uuid) == null)
                        return Step.Result.fail("cape ResourceLocation did not resolve");
                    return Step.Result.pass("A applied+synced skin=" + es + " cape=" + ec);
                }));

        // After this the harness idles in DONE, keeping A connected so B can observe it.
        return steps;
    }

    // ===== B: observer ====================================================================
    private List<Step> buildObserver(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_b");
        final UUID me = mc.player.getUUID();

        List<Step> steps = new ArrayList<>();
        steps.add(baseline(mc, v, role));

        // 1. Speak first so the exact session is ready and the server relays/back-fills A to B.
        steps.add(Step.of("confirm_self")
                .action(() -> {
                    try {
                        NetworkSyncService.getInstance().syncAppearance(me, "", "", "classic");
                        E2ELog.info("B sent confirm C2S (empty appearance)");
                    } catch (Throwable t) {
                        E2ELog.error("confirm_self failed", t);
                    }
                })
                .minTicks(10)
                .ready(() -> Minecraft.getInstance().getConnection() != null)
                .timeoutTicks(200)
                .assertion(() -> mc.getConnection() != null
                        ? Step.Result.pass("connected; sent confirm C2S")
                        : Step.Result.fail("no server connection")));

        // 2. Wait until A's appearance + bytes arrived and the render path resolves to network/<hash>.
        //    This IS the propagation assertion (recorded with full detail).
        steps.add(Step.of("await_propagation")
                .minTicks(5)
                .ready(() -> checkPropagation(mc).pass())
                .timeoutTicks(20 * 60) // up to 60s for A -> server -> B over localhost
                .assertion(() -> checkPropagation(mc)));

        // 3. Walk B to a 3/4-rear vantage of A and capture the gallery screenshot; re-assert.
        //    B steps toward the vantage at walking speed (small per-tick deltas the server accepts as
        //    normal movement) rather than one big teleport that would be position-corrected mid-frame.
        steps.add(Step.of("observe_a")
                .action(() -> stepTowardVantage(mc))
                .minTicks(2)
                .ready(() -> {
                    stepTowardVantage(mc);
                    if (!vantageSet || mc.player == null) return false;
                    boolean atVantage = Math.hypot(mc.player.getX() - tgtX, mc.player.getZ() - tgtZ) < 0.4;
                    return atVantage && ++settleTicks >= 12; // settle so the frame is stable
                })
                .timeoutTicks(600)
                .screenshot(v + "_09_propagation_observe_" + role + ".png")
                .assertion(() -> {
                    logObserveGeometry(mc);
                    Step.Result propagated = checkPropagation(mc);
                    if (!propagated.pass()) return propagated;
                    Step.Result rearView = checkRearComposition(mc);
                    if (!rearView.pass()) return rearView;
                    return Step.Result.pass(propagated.message() + "; " + rearView.message());
                }));

        return steps;
    }

    // ===== shared =========================================================================
    private Step baseline(Minecraft mc, String v, String role) {
        boolean observer = "client_b".equals(role);
        return Step.of("baseline")
                .action(() -> DefaultSkinEvidenceView.hold(mc, observer))
                .minTicks(40) // ~2s render warmup so the first frame is real
                .ready(() -> VanillaShim.isExpectedDefaultSkinResolved(mc.player)
                        && DefaultSkinEvidenceView.hold(mc, observer))
                .settleTicks(20) // reject a one-frame generic fallback before the UUID skin lands
                .timeoutTicks(400)
                .screenshot(v + "_01_baseline_" + role + ".png")
                .assertion(() -> {
                    if (mc.player == null) return Step.Result.fail("player is null");
                    String expected = VanillaShim.expectedDefaultSkinTexture(mc.player);
                    String actual = VanillaShim.skinTexture(mc.player);
                    if (expected == null || !expected.equals(actual)) {
                        return Step.Result.fail("default skin did not stabilize: expected="
                                + expected + " actual=" + actual);
                    }
                    if (!DefaultSkinEvidenceView.hold(mc, observer)) {
                        return Step.Result.fail(
                                "observer baseline did not keep the remote subject behind the camera");
                    }
                    return Step.Result.pass("player present: " + VanillaShim.playerName(mc.player)
                            + " defaultSkin=" + actual + "; full-body evidence held"
                            + (observer ? "; remote subject present behind third-person camera" : ""));
                });
    }

    /**
     * The full A->B check, reused as both the ready predicate and the recorded assertion: A's entity
     * present, its appearance received with network ids, its texture bytes cached on B, and — the
     * render-truthful part — A's {@link AbstractClientPlayer} skin/cape locations resolving to
     * {@code quickskin:network/<hash>}.
     */
    private Step.Result checkPropagation(Minecraft mc) {
        if (mc.player == null || mc.level == null) return Step.Result.fail("not in world");
        AbstractClientPlayer a = findOther(mc);
        if (a == null) return Step.Result.fail("A's entity not present in B's world yet");
        UUID aUuid = a.getUUID();

        PlayerAppearance app = PlayerAppearanceRepository.getInstance().getAppearance(aUuid);
        if (app == null) return Step.Result.fail("no appearance received for A (" + aUuid + ")");

        String skinId = app.getSkinId();
        String capeId = app.getCapeId();
        if (skinId == null || !skinId.startsWith("local_skin:"))
            return Step.Result.fail("A skinId not a network skin: " + skinId);
        if (capeId == null || !capeId.startsWith("local_cape:"))
            return Step.Result.fail("A capeId not a network cape: " + capeId);
        String skinHash = skinId.substring("local_skin:".length());
        String capeHash = capeId.substring("local_cape:".length());

        NetworkTextureCache cache = NetworkTextureCache.getInstance();
        if (!cache.hasTexture(skinHash, "skin")) return Step.Result.fail("skin bytes not cached on B: " + skinHash);
        if (!cache.hasTexture(capeHash, "cape")) return Step.Result.fail("cape bytes not cached on B: " + capeHash);

        String skinLoc = VanillaShim.skinTexture(a);
        String cloakLoc = VanillaShim.cloakTexture(a);
        String expectedSkin = "quickskin:network/skin/" + skinHash;
        String expectedCape = "quickskin:network/cape/" + capeHash;
        if (skinLoc == null || !expectedSkin.equals(skinLoc))
            return Step.Result.fail("render skin=" + skinLoc + " expected " + expectedSkin);
        if (cloakLoc == null || !expectedCape.equals(cloakLoc))
            return Step.Result.fail("render cape=" + cloakLoc + " expected " + expectedCape);

        return Step.Result.pass("A(" + VanillaShim.playerName(a) + ") observed: skin=" + expectedSkin
                + " cape=" + expectedCape + "; bytes cached + render-truthful");
    }

    /** The single other player in B's world (the subject A); null if not yet loaded. */
    private static AbstractClientPlayer findOther(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        UUID me = mc.player.getUUID();
        for (Player p : mc.level.players()) {
            if (p instanceof AbstractClientPlayer acp && !acp.getUUID().equals(me)) {
                return acp;
            }
        }
        return null;
    }

    /**
     * Best-effort gallery framing: walk B toward a 3/4-rear vantage of A (behind + to the side, using
     * A's facing) so A's full body — custom skin and the propagated cape — is in frame, aiming B's
     * camera at A's torso each tick. Movement is capped to a small per-tick step so the server treats it
     * as normal walking (a single big teleport gets position-corrected mid-frame, which framed A point
     * blank). The programmatic assertion does not depend on any of this.
     */
    private void stepTowardVantage(Minecraft mc) {
        try {
            DefaultSkinEvidenceView.enterFirstPerson(mc);
            AbstractClientPlayer a = findOther(mc);
            if (a == null || mc.player == null) return;

            // Pose the disposable remote entity locally on B. This removes head/body interpolation
            // ambiguity from the screenshot without changing the appearance or cape render paths.
            DefaultSkinEvidenceView.pinStandingPose(a, SUBJECT_REAR_YAW);

            if (!vantageSet) {
                // A's forward look vector (MC convention: x=-sin(yaw), z=cos(yaw)).
                double rad = Math.toRadians(a.getYRot());
                double fx = -Math.sin(rad), fz = Math.cos(rad);
                final double dist = 5.0, side = 1.5;
                tgtX = a.getX() - fx * dist + fz * side; // behind A + perpendicular side offset
                tgtY = a.getY();
                tgtZ = a.getZ() - fz * dist - fx * side;
                vantageSet = true;
            }

            double cx = mc.player.getX(), cz = mc.player.getZ();
            double dx = tgtX - cx, dz = tgtZ - cz;
            double d = Math.sqrt(dx * dx + dz * dz);
            final double step = 0.25; // ~walking speed -> accepted by the server, no correction
            double nx = (d > step) ? cx + dx / d * step : tgtX;
            double nz = (d > step) ? cz + dz / d * step : tgtZ;

            mc.player.setDeltaMovement(0, 0, 0);
            mc.player.setPos(nx, tgtY, nz);

            // Aim at A's torso (~1 block above feet).
            double ax = a.getX() - nx, az = a.getZ() - nz;
            double ah = Math.sqrt(ax * ax + az * az);
            double ayTorso = (a.getY() + 1.0) - (tgtY + mc.player.getEyeHeight());
            float yaw = (float) Math.toDegrees(Math.atan2(-ax, az));
            float pitch = (float) (-Math.toDegrees(Math.atan2(ayTorso, ah < 0.01 ? 0.01 : ah)));
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
            mc.player.setXRot(pitch);
            mc.player.xRotO = pitch;
            mc.player.setYHeadRot(yaw);
            mc.player.yHeadRotO = yaw;
            mc.player.setYBodyRot(yaw);
            mc.player.yBodyRotO = yaw;
            DefaultSkinEvidenceView.pinStandingMotion(mc.player);
        } catch (Throwable ignored) {
        }
    }

    private Step.Result checkRearComposition(Minecraft mc) {
        if (mc.player == null) return Step.Result.fail("rear-view observer is unavailable");
        AbstractClientPlayer a = findOther(mc);
        if (a == null) return Step.Result.fail("rear-view subject is unavailable");
        return DefaultSkinEvidenceView.checkRearView(a, mc.player, SUBJECT_REAR_YAW);
    }

    /** Rich one-line diagnostic of the observation geometry + resolved textures (greppable). */
    private void logObserveGeometry(Minecraft mc) {
        try {
            AbstractClientPlayer a = findOther(mc);
            if (a == null || mc.player == null) return;
            double rad = Math.toRadians(mc.player.getYRot());
            double lx = -Math.sin(rad), lz = Math.cos(rad);
            double ax = a.getX() - mc.player.getX(), az = a.getZ() - mc.player.getZ();
            double ah = Math.sqrt(ax * ax + az * az);
            double faceCos = ah < 1e-6 ? 0 : (lx * ax + lz * az) / ah;
            E2ELog.info(String.format(
                    "observe: Bpos=(%.1f,%.1f,%.1f) Apos=(%.1f,%.1f,%.1f) dist=%.2f yaw=%.0f pitch=%.0f faceCos=%.2f aAlive=%b aInvis=%b Bskin=%s Askin=%s Acloak=%s",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    a.getX(), a.getY(), a.getZ(), mc.player.distanceTo(a),
                    mc.player.getYRot(), mc.player.getXRot(), faceCos, a.isAlive(), a.isInvisible(),
                    VanillaShim.skinTextureStr(mc.player),
                    VanillaShim.skinTextureStr(a),
                    VanillaShim.cloakTextureStr(a)));
        } catch (Throwable ignored) {
        }
    }
}
