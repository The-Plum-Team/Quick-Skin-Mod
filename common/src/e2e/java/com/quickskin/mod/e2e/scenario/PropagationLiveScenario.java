package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.storage.ClientAnimationMetadataCache;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.common.util.CapeElytraSilhouette;
import com.quickskin.mod.common.util.SafeImageReader;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 1+ scenario ({@code -Dquickskin.e2e.scenario=propagation-live}): the strict <b>live</b>
 * propagation test - both players already in-world, the observer watching, and the subject changes
 * its appearance and equipment <em>while being watched</em>, with the observer asserting it witnesses
 * every <b>transition</b> (default &rarr; animated custom look &rarr; second animation frame &rarr;
 * remote elytra textured by that cape &rarr; HD cape &rarr; cape removed), not merely a back-fill on
 * join.
 *
 * <p>The two clients run in separate JVMs with no IPC; they self-coordinate through <b>in-world
 * entity presence</b> and the server's appearance relay - timing is by ticks/conditions, never
 * wall-clock sleeps.</p>
 *
 * <h3>Acknowledgement protocol</h3>
 * The observer B never renders its own model (it stays in first person and is never captured), so
 * its own {@code (capeId, model)} appearance pair is a free channel that the server already relays
 * to A. B emits each acknowledgement through {@code NetworkSyncService.syncAppearance(B, "", capeId,
 * model)} from the <em>assertion</em> of the step that captured the evidence (the harness captures
 * before it asserts, so a screenshot is always on disk before A is released). A's {@code await_*}
 * steps poll {@code PlayerAppearanceRepository.getAppearance(B)} for the exact pair:
 * <table>
 *   <tr><th>ack</th><th>B sends after</th><th>(capeId, model)</th><th>releases A step</th></tr>
 *   <tr><td>1</td><td>observe_before</td><td>("", "slim")</td><td>await_observer_settled</td></tr>
 *   <tr><td>2</td><td>observe_animation_frame</td><td>("known:test", "slim")</td><td>await_frames_observed</td></tr>
 *   <tr><td>3</td><td>observe_remote_elytra</td><td>("known:test", "classic")</td><td>await_elytra_observed</td></tr>
 *   <tr><td>4</td><td>observe_hd_cape</td><td>("known:bmo", "classic")</td><td>await_hd_observed</td></tr>
 *   <tr><td>5</td><td>observe_cape_removed</td><td>("known:bmo", "slim")</td><td>remove_cape_live</td></tr>
 * </table>
 * Every pair is distinct from its predecessor, so A can never be released by a stale relay.
 *
 * <h3>Subject (A)</h3>
 * <ol>
 *   <li><b>baseline</b> - joined, default skin.</li>
 *   <li><b>await_observer_settled</b> - wait for ack 1.</li>
 *   <li><b>apply_live</b> - import the plaid classic skin, register the bundled animated GIF cape
 *       (its atlas bytes <em>and</em> animation metadata travel over the network) and
 *       {@code applyLook} both. Applying to the local UUID triggers
 *       {@code NetworkSyncService.syncAppearance}; the server broadcasts to confirmed observers.</li>
 *   <li><b>await_frames_observed</b> - wait for ack 2.</li>
 *   <li><b>equip_elytra_live</b> - put an elytra in the chest slot through the creative-mode slot
 *       packet so the <em>server</em> owns the equipment change and re-broadcasts it, mirror it
 *       locally, and crouch so the wings render separated.</li>
 *   <li><b>await_elytra_observed</b> - wait for ack 3 while holding the elytra pose.</li>
 *   <li><b>apply_hd_cape_live</b> - clear the chest slot through the same packet path, stand up, and
 *       {@code applyCape} a freshly registered 256x128 HD cape.</li>
 *   <li><b>await_hd_observed</b> - wait for ack 4.</li>
 *   <li><b>remove_cape_live</b> - {@code applyCape(uuid, "")} and wait for ack 5, so A never finishes
 *       before B captured the removal.</li>
 * </ol>
 *
 * <h3>Observer (B)</h3>
 * <ol>
 *   <li><b>baseline</b>.</li>
 *   <li><b>confirm_self</b> - one C2S ({@code syncAppearance(B,"","","classic")}) so the server marks
 *       B confirmed and will relay A's <em>live</em> updates to it.</li>
 *   <li><b>observe_before</b> - A present and rendering a <b>non-custom</b> skin (no
 *       {@code quickskin:network/} location yet). Screenshot "before", then ack 1.</li>
 *   <li><b>await_live_change</b> - walk to a fixed rear vantage while polling until A's
 *       {@link AbstractClientPlayer} skin resolves to {@code quickskin:network/skin/<hash>} with the
 *       bytes cached, and its cape resolves through the registered network animation
 *       {@code cape_<hash>} whose frame is pinned to 0 every poll. Screenshot "after".</li>
 *   <li><b>observe_animation_frame</b> - pin frame 1 of the same network animation, capture it, then
 *       ack 2.</li>
 *   <li><b>observe_remote_elytra</b> - wait until the remote entity's chest slot holds an elytra, its
 *       synced pose is crouching, and both {@code getElytraTextureLocation()} and
 *       {@code getCloakTextureLocation()} resolve to the network animation frame; capture, ack 3.</li>
 *   <li><b>observe_hd_cape</b> - wait until the chest is empty again, the cape id is a different
 *       network hash whose cached bytes decode to 256x128 with the complete Elytra cutout, and the
 *       cloak resolves to {@code quickskin:network/cape/<hdHash>}; capture, ack 4.</li>
 *   <li><b>observe_cape_removed</b> - wait until the cape id is empty and the cloak is {@code null}
 *       while the skin stays custom; capture, ack 5.</li>
 * </ol>
 * Every observer capture keeps the same fixed rear vantage and passes the rear-composition check.
 */
public final class PropagationLiveScenario implements Scenario {

    private static final double VANTAGE_DISTANCE = 5.0;
    private static final double VANTAGE_SIDE = 1.5;
    /** Fixed subject pose used to make every observer checkpoint an unambiguous rear view. */
    private static final float SUBJECT_REAR_YAW = 180.0f;

    /**
     * The {@code InventoryMenu} slot index carried by {@code ServerboundSetCreativeModeSlotPacket}.
     *
     * <p>The 1.20.1 server handler accepts slots 1..45 and resolves them through
     * {@code player.inventoryMenu.getSlot(slot)}: 0 is the crafting result, 1..4 the crafting grid,
     * 5..8 the armour slots in {@code SLOT_IDS} order {HEAD, CHEST, LEGS, FEET} (each backed by
     * {@code Inventory} index {@code 39 - i}, so CHEST is Inventory index 38), 9..35 the main
     * inventory, 36..44 the hotbar and 45 the off-hand. The chest armour slot is therefore menu
     * slot {@code ARMOR_SLOT_START + 1 == 6}; the raw Inventory index 38 would address hotbar slot
     * 2 in this packet.</p>
     */
    private static final int CREATIVE_CHEST_MENU_SLOT = InventoryMenu.ARMOR_SLOT_START + 1;

    /** Frames the observer pins the received network animation to for its two captures. */
    private static final int NETWORK_FRAME_A = 0;
    private static final int NETWORK_FRAME_B = 1;

    /** Acknowledgements cross the network twice (B -> server -> A); be generous. */
    private static final int ACK_TIMEOUT_TICKS = 20 * 90;

    /** Model half of every acknowledgement pair (see the class javadoc table). */
    private static final String ACK_MODEL_SLIM = "slim";
    private static final String ACK_MODEL_CLASSIC = "classic";
    /** Cape-id half of every acknowledgement pair; bundled ids so no bytes are uploaded. */
    private static final String ACK_CAPE_NONE = "";
    private static final String ACK_CAPE_FRAMES = "known:test";
    private static final String ACK_CAPE_HD = "known:bmo";

    /** Set by A's apply actions; read by A's ready/assert. */
    private volatile String skinHash;
    private volatile String capeHash;
    private volatile String hdCapeHash;

    /** B's cached observation vantage (computed once from A's pose) + walk/settle bookkeeping. */
    private double tgtX, tgtY, tgtZ;
    private boolean vantageSet = false;
    private int settleTicks = 0;

    /** B latches that it observed a clean pre-change ("before") state, so "after" proves a transition. */
    private volatile boolean sawBefore = false;
    /** B records the network hash of the animated cape it certified, so the HD cape must differ. */
    private volatile String observedAnimatedCapeHash;
    /** B caches the decoded HD cape bytes per network hash so the poll does not re-decode a PNG. */
    private volatile String decodedHdHash;
    private volatile BufferedImage decodedHdCape;

    @Override
    public ScenarioId id() { return ScenarioId.PROPAGATION_LIVE; }

    @Override
    public List<Step> build(Minecraft mc) {
        String role = System.getProperty("quickskin.e2e.role", "client_a");
        return "client_b".equals(role) ? buildObserver(mc) : buildSubject(mc);
    }

    // ===== A: subject (changes LIVE once B is present and has captured "before") ===============
    private List<Step> buildSubject(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final UUID uuid = mc.player.getUUID();
        final PlayerAppearanceService appearance = PlayerAppearanceService.getInstance();

        List<Step> steps = new ArrayList<>();
        steps.add(baseline(mc, v, role));

        // 1. Wait for B's explicit post-capture acknowledgement (ack 1). Position alone cannot prove
        //    that B's harness is ready: its entity is already visible at the observation coordinates
        //    while the client is still loading. B sends the otherwise-unused slim model only after
        //    its clean "before" screenshot and assertion have completed.
        steps.add(Step.of("await_observer_settled")
                .action(() -> {
                    // The normal client startup may asynchronously import the Mojang skin for the
                    // offline E2E username ("Alice"). That is valid production behavior, but this
                    // scenario needs an intentionally clean baseline. Disable further imports for
                    // this disposable profile and clear any import that already completed.
                    disableAutomaticOwnSkin();
                    enforceSubjectDefault(uuid, appearance);
                    E2ELog.info("A established clean default appearance for live-transition baseline");
                })
                .minTicks(40)
                .ready(() -> {
                    // A fetch already in flight when the harness disabled auto-import can complete
                    // once. Clear that late result before accepting B's post-capture acknowledgement.
                    enforceSubjectDefault(uuid, appearance);
                    return observerAcked(mc, ACK_CAPE_NONE, ACK_MODEL_SLIM);
                })
                .timeoutTicks(20 * 150) // up to 150s for B to launch, capture, and acknowledge
                .assertion(() -> assertAck(mc, ACK_CAPE_NONE, ACK_MODEL_SLIM,
                        "observer acknowledged clean BEFORE")));

        // 2. THE LIVE CHANGE - both players in-world, B watching from its vantage; A swaps skin+cape
        //    now. The cape is the bundled animated GIF: its atlas bytes AND its animation metadata
        //    travel to B, which must register the network animation "cape_<hash>" to render it.
        steps.add(Step.of("apply_live")
                .action(() -> {
                    holdSubjectView(mc);
                    try {
                        Path skinFile = TestAssets.makeClassicSkin();
                        AssetMetadata skinMeta = SkinImporter.importSkin(skinFile);
                        if (skinMeta == null) { E2ELog.warn("SkinImporter.importSkin returned null"); return; }
                        skinHash = skinMeta.hash();

                        String gif = TestAssets.registerBundledGifCape();
                        if (gif == null) {
                            E2ELog.error("bundled animated GIF cape is missing; live propagation cannot proceed",
                                    new IllegalStateException("no bundled GIF cape"));
                            return;
                        }
                        capeHash = gif;

                        appearance.applyLook(uuid, "local_skin:" + skinHash, "local_cape:" + capeHash, "classic");
                        E2ELog.info("A applied LIVE (B watching) local_skin:" + skinHash
                                + " animated local_cape:" + capeHash + " model=classic");
                    } catch (Exception e) {
                        E2ELog.error("apply_live action failed", e);
                    }
                })
                .minTicks(40)
                .ready(() -> {
                    holdSubjectView(mc);
                    return skinHash != null && capeHash != null
                            && appearance.getSkinLocation(uuid) != null
                            && appearance.getCapeLocation(uuid) != null
                            && AnimatedTextureManager.getInstance().isAnimated("cape_" + capeHash);
                })
                .timeoutTicks(400)
                .screenshot(v + "_live_03_applied_" + role + ".png")
                .assertion(() -> {
                    if (skinHash == null) return Step.Result.fail("skin import failed (no hash)");
                    if (capeHash == null) return Step.Result.fail("bundled GIF cape registration failed (no hash)");
                    PlayerAppearance app = appearance.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no local appearance");
                    String es = "local_skin:" + skinHash, ec = "local_cape:" + capeHash;
                    if (!es.equals(app.getSkinId()))
                        return Step.Result.fail("skinId=" + app.getSkinId() + " expected " + es);
                    if (!ec.equals(app.getCapeId()))
                        return Step.Result.fail("capeId=" + app.getCapeId() + " expected " + ec);
                    if (!"classic".equals(app.getModel()))
                        return Step.Result.fail("model=" + app.getModel() + " expected classic");
                    if (!AnimatedTextureManager.getInstance().isAnimated("cape_" + capeHash))
                        return Step.Result.fail("local animated cape did not register animation cape_" + capeHash);
                    return Step.Result.pass("A applied LIVE skin=" + es + " animated cape=" + ec
                            + " model=classic; local animation cape_" + capeHash + " registered");
                }));

        // 3. Hold still until B captured BOTH pinned frames of the animated cape (ack 2).
        steps.add(Step.of("await_frames_observed")
                .action(() -> holdSubjectView(mc))
                .minTicks(5)
                .ready(() -> {
                    holdSubjectView(mc);
                    return observerAcked(mc, ACK_CAPE_FRAMES, ACK_MODEL_SLIM);
                })
                .timeoutTicks(ACK_TIMEOUT_TICKS)
                .assertion(() -> assertAck(mc, ACK_CAPE_FRAMES, ACK_MODEL_SLIM,
                        "observer acknowledged animated frames A and B")));

        // 4. Equip an elytra so the SERVER learns about it. The packaged server runs in forced
        //    creative mode, so the creative slot packet is the real player path: the server writes
        //    the armour slot, echoes it to A, and broadcasts SetEquipment to B. Crouch so both wings
        //    render separated (a standing pose overlaps them into a cape-like panel).
        steps.add(Step.of("equip_elytra_live")
                .action(() -> {
                    holdSubjectView(mc);
                    try {
                        if (mc.gameMode == null) {
                            E2ELog.warn("equip_elytra_live: no MultiPlayerGameMode");
                            return;
                        }
                        E2ELog.info("equip_elytra_live: local game mode=" + mc.gameMode.getPlayerMode()
                                + " creative=" + mc.gameMode.getPlayerMode().isCreative()
                                + " menuSlot=" + CREATIVE_CHEST_MENU_SLOT);
                        mc.gameMode.handleCreativeModeItemAdd(
                                new ItemStack(Items.ELYTRA), CREATIVE_CHEST_MENU_SLOT);
                        enforceElytraPose(mc);
                    } catch (Throwable t) {
                        E2ELog.error("equip_elytra_live action failed", t);
                    }
                })
                .minTicks(10)
                .ready(() -> {
                    enforceElytraPose(mc);
                    return hasElytraChest(mc.player) && mc.player.isCrouching();
                })
                .timeoutTicks(400)
                .assertion(() -> {
                    if (mc.player == null) return Step.Result.fail("player null");
                    if (mc.gameMode == null || !mc.gameMode.getPlayerMode().isCreative())
                        return Step.Result.fail("local game mode is not creative; the creative slot packet was not sent");
                    if (!hasElytraChest(mc.player))
                        return Step.Result.fail("chest slot is not an elytra: " + mc.player.getItemBySlot(EquipmentSlot.CHEST));
                    if (!mc.player.isCrouching())
                        return Step.Result.fail("subject is not crouching (pose=" + mc.player.getPose() + ")");
                    return Step.Result.pass("elytra sent through creative menu slot "
                            + CREATIVE_CHEST_MENU_SLOT + " (Inventory index 38) in game mode "
                            + mc.gameMode.getPlayerMode() + "; local chest=ELYTRA, pose="
                            + mc.player.getPose() + ", shiftKeyDown=" + mc.player.isShiftKeyDown());
                }));

        // 5. Hold the crouching elytra pose until B captured the remote wings (ack 3).
        steps.add(Step.of("await_elytra_observed")
                .action(() -> enforceElytraPose(mc))
                .minTicks(5)
                .ready(() -> {
                    enforceElytraPose(mc);
                    return observerAcked(mc, ACK_CAPE_FRAMES, ACK_MODEL_CLASSIC);
                })
                .timeoutTicks(ACK_TIMEOUT_TICKS)
                .assertion(() -> {
                    if (!hasElytraChest(mc.player) || !mc.player.isCrouching())
                        return Step.Result.fail("elytra pose was lost while waiting for the observer");
                    return assertAck(mc, ACK_CAPE_FRAMES, ACK_MODEL_CLASSIC,
                            "observer acknowledged the remote elytra");
                }));

        // 6. Unequip through the same server path, stand up, and switch to a 256x128 HD cape.
        steps.add(Step.of("apply_hd_cape_live")
                .action(() -> {
                    try {
                        if (mc.gameMode != null) {
                            mc.gameMode.handleCreativeModeItemAdd(ItemStack.EMPTY, CREATIVE_CHEST_MENU_SLOT);
                        }
                        releaseElytraPose(mc);
                        Path hd = TestAssets.makeHdCape(); // 256x128 == CAPE_256, kept verbatim on import
                        String hash = TestAssets.registerLocalCapeAs(hd, "qs_e2e_cape_hd.png");
                        if (hash == null) { E2ELog.warn("registerLocalCapeAs(HD) returned null"); return; }
                        hdCapeHash = hash;
                        appearance.applyCape(uuid, "local_cape:" + hdCapeHash);
                        E2ELog.info("A applied LIVE HD cape local_cape:" + hdCapeHash + " with an empty chest slot");
                    } catch (Exception e) {
                        E2ELog.error("apply_hd_cape_live action failed", e);
                    }
                })
                .minTicks(20)
                .ready(() -> {
                    releaseElytraPose(mc);
                    return hdCapeHash != null
                            && hasEmptyChest(mc.player)
                            && !mc.player.isCrouching()
                            && hasCapeId(appearance, uuid, "local_cape:" + hdCapeHash)
                            && appearance.getCapeLocation(uuid) != null;
                })
                .timeoutTicks(400)
                .assertion(() -> {
                    if (hdCapeHash == null) return Step.Result.fail("HD cape registration failed");
                    if (!hasEmptyChest(mc.player))
                        return Step.Result.fail("chest slot still holds " + mc.player.getItemBySlot(EquipmentSlot.CHEST));
                    if (mc.player.isCrouching()) return Step.Result.fail("subject is still crouching");
                    if (!hasCapeId(appearance, uuid, "local_cape:" + hdCapeHash))
                        return Step.Result.fail("HD cape route is not active");
                    return Step.Result.pass("A cleared the chest slot through creative menu slot "
                            + CREATIVE_CHEST_MENU_SLOT + " and applied LIVE HD cape local_cape:" + hdCapeHash);
                }));

        // 7. Hold until B captured the HD cape (ack 4).
        steps.add(Step.of("await_hd_observed")
                .action(() -> holdSubjectView(mc))
                .minTicks(5)
                .ready(() -> {
                    holdSubjectView(mc);
                    return observerAcked(mc, ACK_CAPE_HD, ACK_MODEL_CLASSIC);
                })
                .timeoutTicks(ACK_TIMEOUT_TICKS)
                .assertion(() -> assertAck(mc, ACK_CAPE_HD, ACK_MODEL_CLASSIC,
                        "observer acknowledged the HD cape")));

        // 8. Remove the cape live and wait for B's final capture (ack 5) before finishing.
        steps.add(Step.of("remove_cape_live")
                .action(() -> {
                    holdSubjectView(mc);
                    try {
                        appearance.applyCape(uuid, "");
                        E2ELog.info("A removed its cape LIVE (skin retained)");
                    } catch (Exception e) {
                        E2ELog.error("remove_cape_live action failed", e);
                    }
                })
                .minTicks(5)
                .ready(() -> {
                    holdSubjectView(mc);
                    return hasCapeId(appearance, uuid, "")
                            && observerAcked(mc, ACK_CAPE_HD, ACK_MODEL_SLIM);
                })
                .timeoutTicks(ACK_TIMEOUT_TICKS)
                .assertion(() -> {
                    PlayerAppearance app = appearance.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no local appearance");
                    if (!app.getCapeId().isEmpty())
                        return Step.Result.fail("capeId=" + app.getCapeId() + " expected empty");
                    if (skinHash == null || !("local_skin:" + skinHash).equals(app.getSkinId()))
                        return Step.Result.fail("skin changed during cape removal: " + app.getSkinId());
                    if (appearance.getCapeLocation(uuid) != null)
                        return Step.Result.fail("cape location still resolves after removal");
                    Step.Result ack = assertAck(mc, ACK_CAPE_HD, ACK_MODEL_SLIM,
                            "observer acknowledged the cape removal");
                    if (!ack.pass()) return ack;
                    return Step.Result.pass("A removed cape LIVE (capeId empty, skin=local_skin:"
                            + skinHash + " retained); " + ack.message());
                }));

        // A idles in DONE (harness never quits the client), staying connected so B finishes observing.
        return steps;
    }

    // ===== B: observer (witnesses every transition) ==========================================
    private List<Step> buildObserver(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_b");
        final UUID me = mc.player.getUUID();

        List<Step> steps = new ArrayList<>();
        steps.add(baseline(mc, v, role));

        // 1. Speak first so the exact session is ready and the server relays A's live update to B.
        steps.add(Step.of("confirm_self")
                .action(() -> {
                    try {
                        disableAutomaticOwnSkin();
                        enforceSubjectDefault(me, PlayerAppearanceService.getInstance());
                        NetworkSyncService.getInstance().syncAppearance(me, "", "", "classic");
                        E2ELog.info("B sent confirm C2S (empty appearance)");
                    } catch (Throwable t) {
                        E2ELog.error("confirm_self failed", t);
                    }
                })
                .minTicks(10)
                .ready(() -> mc.getConnection() != null)
                .timeoutTicks(200)
                .assertion(() -> mc.getConnection() != null
                        ? Step.Result.pass("connected; sent confirm C2S")
                        : Step.Result.fail("no server connection")));

        // 2. Walk to the rear vantage of A and capture a clean BEFORE: A framed, still rendering a
        //    NON-custom skin (no network loc). B then holds position and sends ack 1.
        steps.add(Step.of("observe_before")
                .action(() -> stepTowardVantage(mc))
                .minTicks(2) // walk continuously (poll every tick) so A never sees a false "still" mid-approach
                .ready(() -> {
                    // Keep B's own startup auto-import from superseding the explicit acknowledgement
                    // that this step sends after its clean capture. This is the LAST time B's own
                    // appearance is reset: every later ack rides on that same appearance.
                    enforceSubjectDefault(me, PlayerAppearanceService.getInstance());
                    stepTowardVantage(mc);
                    AbstractClientPlayer a = findOther(mc);
                    if (!vantageSet || mc.player == null || a == null) {
                        settleTicks = 0;
                        return false;
                    }
                    boolean atVantage = Math.hypot(mc.player.getX() - tgtX, mc.player.getZ() - tgtZ) < 0.4;
                    String cloak = VanillaShim.cloakTexture(a);
                    boolean clean = VanillaShim.isExpectedDefaultSkinResolved(a)
                            && (cloak == null || !cloak.startsWith("quickskin:network/"));
                    if (atVantage && clean) {
                        settleTicks++;
                    } else {
                        settleTicks = 0;
                    }
                    return settleTicks >= 12; // clean + framed for 12 consecutive rendered ticks
                })
                .timeoutTicks(20 * 60)
                .screenshot(v + "_live_01_before_" + role + ".png")
                .assertion(() -> {
                    AbstractClientPlayer a = findOther(mc);
                    if (a == null) return Step.Result.fail("A's entity not present yet");
                    String skin = VanillaShim.skinTexture(a);
                    String cloak = VanillaShim.cloakTexture(a);
                    String expected = VanillaShim.expectedDefaultSkinTexture(a);
                    boolean customSkin = skin != null && skin.startsWith("quickskin:network/");
                    boolean customCape = cloak != null && cloak.startsWith("quickskin:network/");
                    if (customSkin || customCape)
                        return Step.Result.fail("A already custom BEFORE the live change (skin=" + skin
                                + " cape=" + cloak + ") - ordering race");
                    if (expected == null || !expected.equals(skin)) {
                        return Step.Result.fail("A's default skin did not stabilize BEFORE: expected="
                                + expected + " actual=" + skin);
                    }
                    Step.Result rearView = checkRearComposition(mc);
                    if (!rearView.pass()) return rearView;
                    Step.Result ack = sendAck(me, ACK_CAPE_NONE, ACK_MODEL_SLIM, "clean BEFORE");
                    if (!ack.pass()) return ack;
                    sawBefore = true;
                    return Step.Result.pass("BEFORE: A(" + VanillaShim.playerName(a)
                            + ") framed at vantage, non-custom skin=" + skin + " cape=" + cloak
                            + "; " + ack.message() + "; " + rearView.message());
                }));

        // 3. Hold the vantage and await the LIVE change; capture AFTER from the SAME camera with the
        //    received network animation frozen on frame 0 and assert the witnessed transition.
        steps.add(Step.of("await_live_change")
                .action(() -> stepTowardVantage(mc))
                .minTicks(5)
                .ready(() -> {
                    stepTowardVantage(mc); // keep position + aim steady on A
                    pinNetworkAnimation(mc, NETWORK_FRAME_A);
                    return checkPropagation(mc, NETWORK_FRAME_A).pass(); // the live change must have landed
                })
                // The change lands mid-tick, from the network: the tick that first sees it is one
                // frame ahead of the framebuffer. Hold the resolved state for a second of ticks so
                // the captured frame is the rendered transition, not the frame before it.
                .settleTicks(20)
                .timeoutTicks(ACK_TIMEOUT_TICKS) // B watching, waiting for A to apply + relay + decode
                .screenshot(v + "_live_02_after_" + role + ".png")
                .assertion(() -> {
                    if (!sawBefore)
                        return Step.Result.fail("no clean 'before' state was captured - cannot prove a live transition");
                    logObserveGeometry(mc);
                    Step.Result r = checkPropagation(mc, NETWORK_FRAME_A);
                    if (!r.pass()) return r;
                    Step.Result rearView = checkRearComposition(mc);
                    if (!rearView.pass()) return rearView;
                    observedAnimatedCapeHash = networkCapeHash(mc);
                    return Step.Result.pass("LIVE transition witnessed (before: non-custom -> after: "
                            + r.message() + "; " + rearView.message() + ")");
                }));

        // 4. Pin the second frame of the SAME network animation, capture it, then send ack 2.
        steps.add(Step.of("observe_animation_frame")
                .action(() -> {
                    stepTowardVantage(mc);
                    pinNetworkAnimation(mc, NETWORK_FRAME_B);
                })
                .minTicks(5)
                .ready(() -> {
                    stepTowardVantage(mc);
                    pinNetworkAnimation(mc, NETWORK_FRAME_B);
                    return checkPropagation(mc, NETWORK_FRAME_B).pass();
                })
                .settleTicks(20)
                .timeoutTicks(20 * 30)
                .screenshot(v + "_live_04_animation_frame_b_" + role + ".png")
                .assertion(() -> {
                    logObserveGeometry(mc);
                    Step.Result r = checkPropagation(mc, NETWORK_FRAME_B);
                    if (!r.pass()) return r;
                    String hash = networkCapeHash(mc);
                    if (hash == null || !hash.equals(observedAnimatedCapeHash))
                        return Step.Result.fail("animated cape hash changed between frames: "
                                + observedAnimatedCapeHash + " -> " + hash);
                    if (!ClientAnimationMetadataCache.getInstance().hasMetadata(hash))
                        return Step.Result.fail("no network animation metadata cached for " + hash);
                    Step.Result rearView = checkRearComposition(mc);
                    if (!rearView.pass()) return rearView;
                    Step.Result ack = sendAck(me, ACK_CAPE_FRAMES, ACK_MODEL_SLIM, "animated frames A+B");
                    if (!ack.pass()) return ack;
                    return Step.Result.pass("network animation frame " + NETWORK_FRAME_A + " -> "
                            + NETWORK_FRAME_B + " witnessed (" + r.message() + "; metadata cached for "
                            + hash + "); " + ack.message() + "; " + rearView.message());
                }));

        // 5. A equips an elytra server-side; B must see the synced equipment, the synced crouch pose
        //    and BOTH renderer inputs (elytra + cloak) resolving to the network cape frame.
        steps.add(Step.of("observe_remote_elytra")
                .action(() -> stepTowardVantage(mc))
                .minTicks(5)
                .ready(() -> {
                    stepTowardVantage(mc);
                    pinNetworkAnimation(mc, NETWORK_FRAME_B);
                    return checkRemoteElytra(mc).pass();
                })
                .settleTicks(20)
                .timeoutTicks(ACK_TIMEOUT_TICKS)
                .screenshot(v + "_live_05_remote_elytra_" + role + ".png")
                .assertion(() -> {
                    logObserveGeometry(mc);
                    Step.Result r = checkRemoteElytra(mc);
                    if (!r.pass()) return r;
                    Step.Result rearView = checkRearComposition(mc);
                    if (!rearView.pass()) return rearView;
                    Step.Result ack = sendAck(me, ACK_CAPE_FRAMES, ACK_MODEL_CLASSIC, "remote elytra");
                    if (!ack.pass()) return ack;
                    return Step.Result.pass(r.message() + "; " + ack.message() + "; " + rearView.message());
                }));

        // 6. A clears the chest slot and switches to the 256x128 HD cape; B must see the empty
        //    chest, a different network cape hash whose bytes decode to 256x128, and the cloak on it.
        steps.add(Step.of("observe_hd_cape")
                .action(() -> stepTowardVantage(mc))
                .minTicks(5)
                .ready(() -> {
                    stepTowardVantage(mc);
                    return checkHdCape(mc).pass();
                })
                .settleTicks(20)
                .timeoutTicks(ACK_TIMEOUT_TICKS)
                .screenshot(v + "_live_06_hd_cape_" + role + ".png")
                .assertion(() -> {
                    logObserveGeometry(mc);
                    Step.Result r = checkHdCape(mc);
                    if (!r.pass()) return r;
                    Step.Result rearView = checkRearComposition(mc);
                    if (!rearView.pass()) return rearView;
                    Step.Result ack = sendAck(me, ACK_CAPE_HD, ACK_MODEL_CLASSIC, "HD cape");
                    if (!ack.pass()) return ack;
                    return Step.Result.pass(r.message() + "; " + ack.message() + "; " + rearView.message());
                }));

        // 7. A removes its cape; B must see an empty cape id, a null cloak and the retained skin.
        steps.add(Step.of("observe_cape_removed")
                .action(() -> stepTowardVantage(mc))
                .minTicks(5)
                .ready(() -> {
                    stepTowardVantage(mc);
                    return checkCapeRemoved(mc).pass();
                })
                .settleTicks(20)
                .timeoutTicks(ACK_TIMEOUT_TICKS)
                .screenshot(v + "_live_07_cape_removed_" + role + ".png")
                .assertion(() -> {
                    logObserveGeometry(mc);
                    Step.Result r = checkCapeRemoved(mc);
                    if (!r.pass()) return r;
                    Step.Result rearView = checkRearComposition(mc);
                    if (!rearView.pass()) return rearView;
                    Step.Result ack = sendAck(me, ACK_CAPE_HD, ACK_MODEL_SLIM, "cape removed");
                    if (!ack.pass()) return ack;
                    return Step.Result.pass(r.message() + "; " + ack.message() + "; " + rearView.message());
                }));

        return steps;
    }

    // ===== shared =============================================================================
    private Step baseline(Minecraft mc, String v, String role) {
        boolean observer = "client_b".equals(role);
        return Step.of("baseline")
                .action(() -> DefaultSkinEvidenceView.hold(mc, observer))
                .minTicks(40) // ~2s render warmup so the first frame is real
                .ready(() -> VanillaShim.isExpectedDefaultSkinResolved(mc.player)
                        && DefaultSkinEvidenceView.hold(mc, observer))
                .settleTicks(20) // reject a one-frame generic fallback before the UUID skin lands
                .timeoutTicks(400)
                .screenshot(v + "_live_00_baseline_" + role + ".png")
                .assertion(() -> {
                    if (mc.player == null) return Step.Result.fail("player is null");
                    String expected = VanillaShim.expectedDefaultSkinTexture(mc.player);
                    String actual = VanillaShim.skinTexture(mc.player);
                    if (expected == null || !expected.equals(actual)) {
                        return Step.Result.fail("default skin did not stabilize: expected="
                                + expected + " actual=" + actual);
                    }
                    return Step.Result.pass("player present: " + VanillaShim.playerName(mc.player)
                            + " defaultSkin=" + actual + "; full-body evidence held"
                            + (observer ? "; remote subject present behind third-person camera" : ""));
                });
    }

    // ----- acknowledgement protocol ------------------------------------------------------------

    /** True when the other player's relayed appearance carries exactly this (capeId, model) pair. */
    private static boolean observerAcked(Minecraft mc, String capeId, String model) {
        AbstractClientPlayer other = findOther(mc);
        if (other == null) return false;
        PlayerAppearance ack = PlayerAppearanceRepository.getInstance().getAppearance(other.getUUID());
        return ack != null
                && capeId.equals(ack.getCapeId() == null ? "" : ack.getCapeId())
                && model.equals(ack.getModel());
    }

    private static Step.Result assertAck(Minecraft mc, String capeId, String model, String label) {
        AbstractClientPlayer other = findOther(mc);
        if (other == null) return Step.Result.fail("observer B never appeared");
        PlayerAppearance ack = PlayerAppearanceRepository.getInstance().getAppearance(other.getUUID());
        if (!observerAcked(mc, capeId, model)) {
            return Step.Result.fail("observer B never sent ack (cape=" + capeId + ", model=" + model
                    + "); current relayed appearance=" + describe(ack));
        }
        return Step.Result.pass(label + ": " + VanillaShim.playerName(other)
                + " relayed ack (cape=" + capeId + ", model=" + model + ")");
    }

    /** B publishes one acknowledgement pair through its own (otherwise unused) appearance. */
    private static Step.Result sendAck(UUID me, String capeId, String model, String label) {
        try {
            NetworkSyncService.getInstance().syncAppearance(me, "", capeId, model);
            E2ELog.info("B acknowledged " + label + " via appearance (cape=" + capeId + ", model=" + model + ")");
            return Step.Result.pass("ack(cape=" + capeId + ", model=" + model + ") sent");
        } catch (Throwable t) {
            E2ELog.error("failed to acknowledge " + label, t);
            return Step.Result.fail(label + " captured, but acknowledgement failed: " + t);
        }
    }

    private static String describe(PlayerAppearance appearance) {
        return appearance == null ? "null" : "(skin=" + appearance.getSkinId() + ", cape="
                + appearance.getCapeId() + ", model=" + appearance.getModel() + ")";
    }

    // ----- observer checks ----------------------------------------------------------------------

    /**
     * The full A->B render-truthful check (also used as B's live-change ready predicate): A's entity
     * present, its appearance received with network ids, its skin and cape bytes cached on B, A's
     * {@link AbstractClientPlayer} skin location resolving to {@code quickskin:network/skin/<hash>},
     * the network animation {@code cape_<hash>} registered from the received metadata with at least
     * two frames and pinned to {@code expectedFrame}, and the cloak resolving to that animation's
     * frame texture.
     */
    private Step.Result checkPropagation(Minecraft mc, int expectedFrame) {
        if (mc.player == null || mc.level == null) return Step.Result.fail("not in world");
        AbstractClientPlayer a = findOther(mc);
        if (a == null) return Step.Result.fail("A's entity not present in B's world");
        UUID aUuid = a.getUUID();

        PlayerAppearance app = PlayerAppearanceRepository.getInstance().getAppearance(aUuid);
        if (app == null) return Step.Result.fail("no appearance received for A yet (" + aUuid + ")");

        String skinId = app.getSkinId();
        String capeId = app.getCapeId();
        if (skinId == null || !skinId.startsWith("local_skin:"))
            return Step.Result.fail("A skinId not a network skin yet: " + skinId);
        if (capeId == null || !capeId.startsWith("local_cape:"))
            return Step.Result.fail("A capeId not a network cape yet: " + capeId);
        String skinHash = skinId.substring("local_skin:".length());
        String capeHash = capeId.substring("local_cape:".length());

        NetworkTextureCache cache = NetworkTextureCache.getInstance();
        if (!cache.hasTexture(skinHash, "skin")) return Step.Result.fail("skin bytes not cached on B yet: " + skinHash);
        if (!cache.hasTexture(capeHash, "cape")) return Step.Result.fail("cape bytes not cached on B yet: " + capeHash);

        String skinLoc = VanillaShim.skinTexture(a);
        String expectedSkin = "quickskin:network/skin/" + skinHash;
        if (skinLoc == null || !expectedSkin.equals(skinLoc))
            return Step.Result.fail("render skin=" + skinLoc + " expected " + expectedSkin);

        String animationId = "cape_" + capeHash;
        AnimatedTextureManager manager = AnimatedTextureManager.getInstance();
        if (!ClientAnimationMetadataCache.getInstance().hasMetadata(capeHash))
            return Step.Result.fail("animation metadata not received yet for " + capeHash);
        if (!manager.isAnimated(animationId))
            return Step.Result.fail("network animation " + animationId + " not registered yet");
        Object state = animationState(animationId);
        if (state == null) return Step.Result.fail("network animation " + animationId + " still pending");
        AnimationMetadata meta = metaOf(state);
        int frameCount = meta == null ? -1 : meta.frameCount();
        if (frameCount < 2) return Step.Result.fail("network animation frameCount=" + frameCount + " (not animated)");
        int frame = frameOf(state);
        if (frame != expectedFrame)
            return Step.Result.fail("network animation frame=" + frame + " expected pinned " + expectedFrame);
        Object frameTexture = manager.getCurrentFrameTexture(animationId);
        if (frameTexture == null) return Step.Result.fail("network animation has no frame texture");
        String expectedCape = frameTexture.toString();
        String cloakLoc = VanillaShim.cloakTexture(a);
        if (cloakLoc == null || !expectedCape.equals(cloakLoc))
            return Step.Result.fail("render cape=" + cloakLoc + " expected animation frame " + expectedCape);

        return Step.Result.pass("A(" + VanillaShim.playerName(a) + ") observed: skin=" + expectedSkin
                + " cape=" + capeId + " animated " + animationId + " frame=" + frame + "/" + frameCount
                + " rendered as " + expectedCape + "; bytes+metadata cached + render-truthful");
    }

    /** Remote elytra: synced equipment, synced crouch pose, and both renderer inputs on the cape. */
    private Step.Result checkRemoteElytra(Minecraft mc) {
        Step.Result base = checkPropagation(mc, NETWORK_FRAME_B);
        if (!base.pass()) return base;
        AbstractClientPlayer a = findOther(mc);
        if (a == null) return Step.Result.fail("A's entity not present in B's world");
        ItemStack chest = a.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA))
            return Step.Result.fail("remote chest slot is not an elytra yet: " + chest);
        if (!a.isCrouching())
            return Step.Result.fail("remote subject pose is not crouching yet (pose=" + a.getPose()
                    + ", shiftKeyDown=" + a.isShiftKeyDown() + ")");
        String hash = networkCapeHash(mc);
        Object frameTexture = AnimatedTextureManager.getInstance().getCurrentFrameTexture("cape_" + hash);
        String expected = frameTexture == null ? null : frameTexture.toString();
        String elytra = VanillaShim.elytraTexture(a);
        String cloak = VanillaShim.cloakTexture(a);
        if (expected == null || !expected.equals(elytra))
            return Step.Result.fail("remote elytra texture=" + elytra + " expected network cape frame " + expected);
        if (!expected.equals(cloak))
            return Step.Result.fail("remote cloak texture=" + cloak + " expected network cape frame " + expected);
        return Step.Result.pass("remote elytra: chest=ELYTRA, pose=" + a.getPose() + " crouching, shiftKeyDown="
                + a.isShiftKeyDown() + ", elytra texture=" + elytra + " == cloak texture == network cape frame; "
                + base.message());
    }

    /** HD cape: empty chest, a different network hash, 256x128 masked bytes, cloak on the network location. */
    private Step.Result checkHdCape(Minecraft mc) {
        if (mc.player == null || mc.level == null) return Step.Result.fail("not in world");
        AbstractClientPlayer a = findOther(mc);
        if (a == null) return Step.Result.fail("A's entity not present in B's world");
        ItemStack chest = a.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.isEmpty()) return Step.Result.fail("remote chest slot not empty yet: " + chest);
        if (a.isCrouching()) return Step.Result.fail("remote subject still crouching");

        PlayerAppearance app = PlayerAppearanceRepository.getInstance().getAppearance(a.getUUID());
        if (app == null) return Step.Result.fail("no appearance for A");
        String skinId = app.getSkinId();
        if (skinId == null || !skinId.startsWith("local_skin:"))
            return Step.Result.fail("A skinId not a network skin: " + skinId);
        String skinHash = skinId.substring("local_skin:".length());
        String expectedSkin = "quickskin:network/skin/" + skinHash;
        String skinLoc = VanillaShim.skinTexture(a);
        if (skinLoc == null || !expectedSkin.equals(skinLoc))
            return Step.Result.fail("render skin=" + skinLoc + " expected " + expectedSkin);

        String capeId = app.getCapeId();
        if (capeId == null || !capeId.startsWith("local_cape:"))
            return Step.Result.fail("A capeId not a network cape: " + capeId);
        String hdHash = capeId.substring("local_cape:".length());
        if (hdHash.equals(observedAnimatedCapeHash))
            return Step.Result.fail("cape id still the animated cape " + hdHash + "; HD cape not received yet");
        NetworkTextureCache cache = NetworkTextureCache.getInstance();
        if (!cache.hasTexture(hdHash, "cape")) return Step.Result.fail("HD cape bytes not cached on B yet: " + hdHash);
        String expectedCape = "quickskin:network/cape/" + hdHash;
        String cloak = VanillaShim.cloakTexture(a);
        if (cloak == null || !expectedCape.equals(cloak))
            return Step.Result.fail("render cape=" + cloak + " expected " + expectedCape);
        if (AnimatedTextureManager.getInstance().isAnimated("cape_" + hdHash))
            return Step.Result.fail("HD cape unexpectedly registered an animation");

        BufferedImage decoded = decodedHdCape(cache, hdHash);
        if (decoded == null) return Step.Result.fail("cached HD cape bytes did not decode: " + hdHash);
        int w = decoded.getWidth(), h = decoded.getHeight();
        if (w != 256 || h != 128)
            return Step.Result.fail("HD cape received as " + w + "x" + h + " expected 256x128 (downscaled?)");
        // NetworkTextureCache stores the PRESENTED cape bytes (CapeElytraSilhouette.maskedCopy is
        // applied while preparing a network cape), so the decoded bytes are the exact pixels that
        // back the registered network texture.
        if (!CapeElytraSilhouette.hasRequiredCutout(decoded, 1))
            return Step.Result.fail("presented HD cape lacks the complete vanilla Elytra cutout");
        return Step.Result.pass("HD cape: chest empty, standing, capeId=" + capeId + " (differs from animated "
                + observedAnimatedCapeHash + "), bytes cached, decoded " + w + "x" + h
                + " with the complete Elytra cutout, cloak=" + expectedCape + ", skin=" + expectedSkin);
    }

    /** Cape removal: empty cape id, null cloak, skin retained, chest still empty. */
    private Step.Result checkCapeRemoved(Minecraft mc) {
        if (mc.player == null || mc.level == null) return Step.Result.fail("not in world");
        AbstractClientPlayer a = findOther(mc);
        if (a == null) return Step.Result.fail("A's entity not present in B's world");
        PlayerAppearance app = PlayerAppearanceRepository.getInstance().getAppearance(a.getUUID());
        if (app == null) return Step.Result.fail("no appearance for A");
        String capeId = app.getCapeId();
        if (capeId != null && !capeId.isEmpty())
            return Step.Result.fail("A capeId not removed yet: " + capeId);
        String skinId = app.getSkinId();
        if (skinId == null || !skinId.startsWith("local_skin:"))
            return Step.Result.fail("A skinId not a network skin: " + skinId);
        String skinHash = skinId.substring("local_skin:".length());
        String expectedSkin = "quickskin:network/skin/" + skinHash;
        String skinLoc = VanillaShim.skinTexture(a);
        if (skinLoc == null || !expectedSkin.equals(skinLoc))
            return Step.Result.fail("render skin=" + skinLoc + " expected " + expectedSkin);
        String cloak = VanillaShim.cloakTexture(a);
        if (cloak != null) return Step.Result.fail("render cape=" + cloak + " expected null after removal");
        ItemStack chest = a.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.isEmpty()) return Step.Result.fail("remote chest slot not empty: " + chest);
        return Step.Result.pass("cape removed: capeId empty, cloak=null, elytra texture="
                + VanillaShim.elytraTexture(a) + ", skin=" + expectedSkin + " retained, chest empty");
    }

    // ----- network animation helpers -------------------------------------------------------------

    /** The network hash of A's current {@code local_cape:} id, or {@code null}. */
    private static String networkCapeHash(Minecraft mc) {
        AbstractClientPlayer a = findOther(mc);
        if (a == null) return null;
        PlayerAppearance app = PlayerAppearanceRepository.getInstance().getAppearance(a.getUUID());
        String capeId = app == null ? null : app.getCapeId();
        return capeId != null && capeId.startsWith("local_cape:")
                ? capeId.substring("local_cape:".length()) : null;
    }

    /** Freeze the received network animation and hold one exact frame, as FullScenario does locally. */
    private static void pinNetworkAnimation(Minecraft mc, int frame) {
        String hash = networkCapeHash(mc);
        if (hash == null) return;
        String animationId = "cape_" + hash;
        if (animationState(animationId) == null) return;
        AnimatedTextureManager manager = AnimatedTextureManager.getInstance();
        manager.setAnimationSpeed(animationId, 0.0f);
        manager.setAnimationFrame(animationId, frame);
    }

    /** The registered {@code AnimationState} for this id (private mod-owned map), or {@code null}. */
    private static Object animationState(String animationId) {
        try {
            AnimatedTextureManager mgr = AnimatedTextureManager.getInstance();
            Field f = AnimatedTextureManager.class.getDeclaredField("animations");
            f.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) f.get(mgr);
            return map.get(animationId);
        } catch (Throwable t) {
            E2ELog.warn("animationState: " + t);
            return null;
        }
    }

    private static int frameOf(Object state) {
        Object v = stateField(state, "currentFrame");
        return (v instanceof Integer i) ? i : -1;
    }

    private static AnimationMetadata metaOf(Object state) {
        Object v = stateField(state, "metadata");
        return (v instanceof AnimationMetadata m) ? m : null;
    }

    private static Object stateField(Object state, String name) {
        try {
            Field f = state.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(state);
        } catch (Throwable t) {
            E2ELog.warn("AnimationState." + name + ": " + t);
            return null;
        }
    }

    private BufferedImage decodedHdCape(NetworkTextureCache cache, String hash) {
        if (hash.equals(decodedHdHash) && decodedHdCape != null) return decodedHdCape;
        try {
            byte[] data = cache.getTextureData(hash, "cape");
            if (data == null) return null;
            BufferedImage image = SafeImageReader.readPng(data);
            decodedHdHash = hash;
            decodedHdCape = image;
            return image;
        } catch (Exception e) {
            E2ELog.warn("HD cape decode failed for " + hash + ": " + e);
            return null;
        }
    }

    // ----- subject helpers -----------------------------------------------------------------------

    /** Keep A in first person with no screen and no drift while B captures it. */
    private static void holdSubjectView(Minecraft mc) {
        try {
            DefaultSkinEvidenceView.enterFirstPerson(mc);
            if (mc.player != null) mc.player.setDeltaMovement(0, 0, 0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Re-assert the crouching elytra pose every poll. The server owns the armour slot (creative
     * packet), so only the local mirror is re-applied here; the crouch flag is sent by
     * {@code LocalPlayer} whenever {@code isShiftKeyDown()} changes and syncs to B as the shared
     * sneaking flag plus the CROUCHING pose.
     */
    private static void enforceElytraPose(Minecraft mc) {
        try {
            holdSubjectView(mc);
            if (mc.options != null) mc.options.keyShift.setDown(true);
            if (mc.player != null) {
                if (!hasElytraChest(mc.player)) {
                    mc.player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
                }
                mc.player.setShiftKeyDown(true);
            }
        } catch (Throwable t) {
            E2ELog.warn("enforceElytraPose: " + t);
        }
    }

    /** Stand up and clear the local chest mirror after the creative packet cleared the server slot. */
    private static void releaseElytraPose(Minecraft mc) {
        try {
            holdSubjectView(mc);
            if (mc.options != null) mc.options.keyShift.setDown(false);
            if (mc.player != null) {
                if (!hasEmptyChest(mc.player)) {
                    mc.player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                }
                mc.player.setShiftKeyDown(false);
            }
        } catch (Throwable t) {
            E2ELog.warn("releaseElytraPose: " + t);
        }
    }

    private static boolean hasElytraChest(Player player) {
        return player != null && player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private static boolean hasEmptyChest(Player player) {
        return player != null && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
    }

    private static boolean hasCapeId(PlayerAppearanceService service, UUID uuid, String capeId) {
        PlayerAppearance app = service.getAppearance(uuid);
        return app != null && capeId.equals(app.getCapeId() == null ? "" : app.getCapeId());
    }

    /** Keep A on the explicit empty appearance until B has captured that baseline. */
    private static void enforceSubjectDefault(
            UUID playerId, PlayerAppearanceService appearanceService) {
        PlayerAppearance current = appearanceService.getAppearance(playerId);
        if (current == null
                || !current.getSkinId().isEmpty()
                || !current.getCapeId().isEmpty()
                || !"classic".equals(current.getModel())) {
            appearanceService.applyLook(playerId, "", "", "classic");
        }
    }

    /** Isolate this disposable E2E profile from the asynchronous Mojang own-skin importer. */
    private static void disableAutomaticOwnSkin() {
        com.quickskin.mod.config.ClientConfig config =
                com.quickskin.mod.config.ClientConfig.getInstance();
        config.enablePlayerOwnSkinSystem = false;
        config.activeSkinHash = "";
        config.playerOwnSkinHash = "";
        config.activeCapeHash = "";
    }

    /** The single other player in B's (or A's) world; null if not yet loaded. */
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
     * Walk B toward a fixed rear vantage of A at walking speed (small per-tick deltas the server
     * accepts), aiming the camera at A's torso each tick, so every observer frame shows A's full body
     * from the same camera. The programmatic assertions do not depend on framing.
     */
    private void stepTowardVantage(Minecraft mc) {
        try {
            DefaultSkinEvidenceView.enterFirstPerson(mc);
            AbstractClientPlayer a = findOther(mc);
            if (a == null || mc.player == null) return;

            // Pose the disposable remote entity locally on B. This removes head/body interpolation
            // ambiguity from the screenshots without changing the appearance or cape render paths.
            DefaultSkinEvidenceView.pinStandingPose(a, SUBJECT_REAR_YAW);

            if (!vantageSet) {
                double rad = Math.toRadians(a.getYRot());
                double fx = -Math.sin(rad), fz = Math.cos(rad);
                tgtX = a.getX() - fx * VANTAGE_DISTANCE + fz * VANTAGE_SIDE;
                tgtY = a.getY();
                tgtZ = a.getZ() - fz * VANTAGE_DISTANCE - fx * VANTAGE_SIDE;
                vantageSet = true;
            }

            double cx = mc.player.getX(), cz = mc.player.getZ();
            double dx = tgtX - cx, dz = tgtZ - cz;
            double d = Math.sqrt(dx * dx + dz * dz);
            final double step = 0.25;
            double nx = (d > step) ? cx + dx / d * step : tgtX;
            double nz = (d > step) ? cz + dz / d * step : tgtZ;

            mc.player.setDeltaMovement(0, 0, 0);
            mc.player.setPos(nx, tgtY, nz);

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
            E2ELog.info(String.format(Locale.ROOT,
                    "observe(live): Bpos=(%.1f,%.1f,%.1f) Apos=(%.1f,%.1f,%.1f) dist=%.2f faceCos=%.2f aAlive=%b aInvis=%b aPose=%s aShift=%b aChest=%s Bskin=%s Askin=%s Acloak=%s Aelytra=%s",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    a.getX(), a.getY(), a.getZ(), mc.player.distanceTo(a), faceCos,
                    a.isAlive(), a.isInvisible(), a.getPose(), a.isShiftKeyDown(),
                    a.getItemBySlot(EquipmentSlot.CHEST),
                    VanillaShim.skinTextureStr(mc.player),
                    VanillaShim.skinTextureStr(a),
                    VanillaShim.cloakTextureStr(a),
                    String.valueOf(VanillaShim.elytraTexture(a))));
        } catch (Throwable ignored) {
        }
    }
}
