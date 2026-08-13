package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.screen.CapeAdjustScreen;
import com.quickskin.mod.client.gui.screen.DeletionConfirmScreen;
import com.quickskin.mod.client.gui.screen.PlayerCapeMenuScreen;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.screen.RenameScreen;
import com.quickskin.mod.client.gui.screen.SettingsScreen;
import com.quickskin.mod.client.gui.util.CapeImportProcessor;
import com.quickskin.mod.client.gui.util.GuiScaleManager;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.CapeService;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.ModelService;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.CapeElytraSilhouette;
import com.quickskin.mod.common.util.CapeZoomRange;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.common.util.TextureAlphaDetector;
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
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

/**
 * Phase 2 scenario ({@code -Dquickskin.e2e.scenario=full}): a single-client sweep of <em>every</em>
 * Quick Skin feature, each step a real action driven through the mod's own services plus a per-step
 * screenshot and a programmatic assertion that reads the mod's state (the source of truth).
 *
 * <p>This is a <b>client-A-only</b> scenario; the A&rarr;B render-truthful propagation check is its
 * own {@code "propagation"} scenario (Phase 1) and is intentionally NOT duplicated here. The plan's
 * eleven feature areas map to these steps:</p>
 * <ol>
 *   <li>baseline &mdash; clean state, player present (singletons reset, 3rd-person view).</li>
 *   <li>local skin upload &mdash; {@code SkinImporter.importSkin} &rarr; {@code applySkin}; menu shot.</li>
 *   <li>model slim/classic &mdash; {@code applySkin(...,"slim"/"classic")}; both the requested
 *       appearance and Minecraft's renderer-facing model flip in a close, stable rear view.</li>
 *   <li>known cape &mdash; cape menu shot; {@code applyCape("known:test")}; cape id + location.</li>
 *   <li>CapeAdjustScreen &mdash; opened with a test image + harness-owned {@code onApply} consumer.</li>
 *   <li>BMO editor parity &mdash; crop a black-padded 128&times;64 import and compare its cape and
 *       elytra atlas/render paths with the bundled 64&times;32 original, then remove it through the
 *       cape menu and prove the still-equipped wings return to vanilla's elytra texture.</li>
 *   <li>animated cape &mdash; apply a valid bundled GIF cape atlas, pin two distinct frames, and
 *       prove both the active animation state and the rendered cape change.</li>
 *   <li>HD cape no-downscale &mdash; import a 256&times;128 cape; metadata resolution == source dims.</li>
 *   <li>elytra hides cape &mdash; equip {@code Items.ELYTRA} in CHEST; assert the inputs that make
 *       {@code CapeLayerMixin} cancel and the alpha cutout that tapers the rendered wings.</li>
 *   <li><i>(propagation A&rarr;B &mdash; separate scenario, not here)</i></li>
 *   <li>settings / rename / delete &mdash; {@code SettingsScreen} round-trips a flag through
 *       {@code onClose}&rarr;{@code ClientConfig}; Rename/Delete dialogs feed a harness-owned callback.</li>
 *   <li>HUD preview &mdash; toggle {@code showSkinPreviewOverlay}; the production {@code RENDER_HUD}
 *       hook draws {@code SkinPreviewOverlay} and the screenshot captures it.</li>
 * </ol>
 *
 * <p>Screens are opened via {@link VanillaShim#setScreen} on the client/tick thread; one or more
 * render frames are pumped (via {@code minTicks}) so {@code init()} builds widgets before a screenshot
 * or a reflective button press. Private playback/widget state is read by reflection.</p>
 */
public final class FullScenario implements Scenario {

    /** A close but unclipped view makes the one-texture-pixel arm-width delta inspectable. */
    private static final int MODEL_EVIDENCE_FOV = 50;
    private volatile Integer modelEvidenceOriginalFov;

    private volatile String skinHash;        // set by step 2, reused by model + HUD steps
    private volatile String externalSkinHash; // set by the external-drop step (no import call)
    private volatile String hdCapeHash;      // set by step "hd_cape"
    private final AtomicReference<BufferedImage> hdCapeSource = new AtomicReference<>();
    private final AtomicReference<BufferedImage> hdCapePresentation = new AtomicReference<>();
    private volatile String gifCapeHash;     // set by the mandatory bundled-GIF checkpoint
    private volatile int animStartFrame = Integer.MIN_VALUE; // snapshot for the frame-advance check
    /** Frame held still for screenshot A, then advanced deterministically to screenshot B. */
    private static final int ANIMATED_EVIDENCE_FRAME_A = 0;
    private static final int ANIMATED_EVIDENCE_FRAME_B = 1;
    /** Previous-poll layout stamp of the open skin menu; {@code Long.MIN_VALUE} = not held yet. */
    private long skinMenuLayoutStamp = Long.MIN_VALUE;
    private volatile String previewCapeHashA;  // set by the cape-preview steps (never applied)
    private volatile String previewCapeHashB;
    /** Rendered ticks a pushed preview cape must survive before its screenshot is captured. */
    private static final int PREVIEW_HOLD_TICKS = 15;
    private final AtomicInteger previewHoldA = new AtomicInteger();
    private final AtomicInteger previewHoldB = new AtomicInteger();
    /** Loud enough that the filled window cannot be confused with the cape's own colours. */
    private static final int OPAQUE_FILL_RGB = 0xFF00FF;
    private final AtomicInteger opaqueHoldOff = new AtomicInteger();
    private final AtomicInteger opaqueHoldOn = new AtomicInteger();
    /** Two slider positions far enough apart that the framing cannot look the same at both. */
    private static final double ZOOM_OUT_POSITION = 0.10;
    private static final double ZOOM_IN_POSITION = 0.80;
    /** Enough events that each one asks for a sub-pixel offset change, as a real drag does. */
    private static final int ZOOM_DRAG_STEPS = 64;
    /** The second target resolution, reachable because the zoom source is larger than 128x64. */
    private static final String RESOLUTION_2X_LABEL = "128x64 (2x)";
    private static final int RESOLUTION_2X_W = 128;
    private final AtomicInteger zoomHoldOut = new AtomicInteger();
    private final AtomicInteger zoomHoldIn = new AtomicInteger();
    private final AtomicReference<BufferedImage> zoomedOutAtlas = new AtomicReference<>();

    /** The non-standard BMO import and the exact production atlas it must reproduce. */
    private final AtomicReference<CapeImportProcessor.PreparedCape> bmoPreparedCape =
            new AtomicReference<>();
    private final AtomicReference<BufferedImage> bundledBmoAtlas = new AtomicReference<>();
    private final AtomicReference<BufferedImage> adjustedBmoAtlas = new AtomicReference<>();
    private final AtomicInteger bmoAdjustHold = new AtomicInteger();
    private volatile String adjustedBmoCapeHash;

    /** Screen-space tolerance for the redundant renderer-level BMO parity check. */
    private static final double BMO_RENDER_REGION_LEFT = 0.44;
    private static final double BMO_RENDER_REGION_TOP = 0.40;
    private static final double BMO_RENDER_REGION_RIGHT = 0.56;
    private static final double BMO_RENDER_REGION_BOTTOM = 0.84;
    private static final int BMO_RENDER_MAX_ALIGNMENT = 3;
    private static final int BMO_RENDER_CHANNEL_TOLERANCE = 12;
    private static final double BMO_RENDER_MAX_CHANGED_FRACTION = 0.10;

    private final AtomicReference<BufferedImage> capeAdjustResult = new AtomicReference<>();
    private volatile String renameResult;
    private volatile Boolean deleteResult;

    @Override
    public ScenarioId id() { return ScenarioId.FULL; }

    @Override
    public List<Step> build(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final UUID uuid = mc.player.getUUID();
        final PlayerAppearanceService svc = PlayerAppearanceService.getInstance();
        final String prefix = v + "_";
        final String suffix = "_" + role + ".png";
        final String bundledBmoCapeShot = prefix + "full_05h_bmo_bundled_cape" + suffix;
        final String bundledBmoElytraShot = prefix + "full_05i_bmo_bundled_elytra" + suffix;
        final String adjustedBmoCapeShot = prefix + "full_05l_bmo_adjusted_cape" + suffix;
        final String adjustedBmoElytraShot = prefix + "full_05m_bmo_adjusted_elytra" + suffix;
        final String removedBmoElytraShot = prefix + "full_05n_bmo_removed_vanilla_elytra" + suffix;

        List<Step> steps = new ArrayList<>();

        // 1. baseline -----------------------------------------------------------------------------
        steps.add(Step.of("baseline")
                .action(() -> {
                    resetState();
                    DefaultSkinEvidenceView.hold(mc, false);
                })
                .minTicks(40) // ~2s render warmup so the first frame is real
                .ready(() -> VanillaShim.isExpectedDefaultSkinResolved(mc.player)
                        && DefaultSkinEvidenceView.hold(mc, false))
                .settleTicks(20) // reject a one-frame generic fallback before the UUID skin lands
                .timeoutTicks(400)
                .screenshot(prefix + "full_01_baseline" + suffix)
                .assertion(() -> {
                    if (mc.player == null) return Step.Result.fail("player is null");
                    String expected = VanillaShim.expectedDefaultSkinTexture(mc.player);
                    String actual = VanillaShim.skinTexture(mc.player);
                    if (expected == null || !expected.equals(actual)) {
                        return Step.Result.fail("default skin did not stabilize: expected="
                                + expected + " actual=" + actual);
                    }
                    return Step.Result.pass("player present: " + VanillaShim.playerName(mc.player)
                            + " defaultSkin=" + actual + " activeSkin=" + svc.hasActiveSkin(uuid)
                            + " activeCape=" + svc.hasActiveCape(uuid)
                            + "; full-body evidence held");
                }));

        // 2. local skin upload --------------------------------------------------------------------
        steps.add(Step.of("local_skin_apply")
                .action(() -> {
                    enterWorldView(mc);
                    try {
                        Path file = TestAssets.makeClassicSkin();
                        AssetMetadata meta = SkinImporter.importSkin(file);
                        if (meta == null) { E2ELog.warn("importSkin returned null"); return; }
                        skinHash = meta.hash();
                        svc.applySkin(uuid, "local_skin:" + skinHash, "auto");
                        E2ELog.info("applied local_skin:" + skinHash);
                    } catch (Exception e) {
                        E2ELog.error("local_skin_apply failed", e);
                    }
                })
                .minTicks(40)
                .ready(() -> skinHash != null && svc.getAppearance(uuid) != null
                        && svc.getSkinLocation(uuid) != null)
                .timeoutTicks(400)
                .screenshot(prefix + "full_02a_local_skin_body" + suffix)
                .assertion(() -> {
                    if (skinHash == null) return Step.Result.fail("skin import failed (no hash)");
                    PlayerAppearance app = svc.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no appearance");
                    String expected = "local_skin:" + skinHash;
                    if (!expected.equals(app.getSkinId()))
                        return Step.Result.fail("skinId=" + app.getSkinId() + " expected " + expected);
                    if (svc.getSkinLocation(uuid) == null)
                        return Step.Result.fail("skin location did not resolve");
                    return Step.Result.pass("skinId=" + expected + " location=" + svc.getSkinLocation(uuid));
                }));

        // 2b. skin menu screenshot ----------------------------------------------------------------
        // The skin screen's first init() may early-return while GuiScaleManager forces the menu
        // scale and the display resize re-enters init(); a frame presented before that re-init lays
        // the drop-zone copy a text row higher than the settled layout, dropping its glyphs out of
        // the contract's required-gui-text box. So readiness is the SETTLED layout (forced scale
        // applied + tick-over-tick stable layout stamp), not the bare instanceof, and settleTicks
        // holds it so the captured last-presented frame really shows it (ticks are not frames:
        // under software rendering several ticks can share one frame).
        steps.add(Step.of("skin_menu_screen")
                .action(() -> VanillaShim.setScreen(mc, new PlayerSkinMenuScreen(null)))
                .minTicks(30)
                .ready(() -> skinMenuLayoutSettled(mc))
                .settleTicks(20)
                .timeoutTicks(400)
                .screenshot(prefix + "full_02b_skin_menu" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen))
                        return Step.Result.fail("skin menu not open: " + screenName(mc));
                    int scale = VanillaShim.guiScale(mc);
                    if (scale != GuiScaleManager.getOptimalMenuScale())
                        return Step.Result.fail("menu GUI scale never settled: window=" + scale
                                + " expected=" + GuiScaleManager.getOptimalMenuScale());
                    return Step.Result.pass(
                            "PlayerSkinMenuScreen open, layout settled at scale " + scale);
                }));

        // 2c. external drop -----------------------------------------------------------------------
        // Copy a PNG straight into uploads/skins with the menu already open and WITHOUT calling
        // reload(): the open screen has to notice the file on its own, which is the whole point.
        steps.add(Step.of("external_skin_drop")
                .action(() -> {
                    try {
                        LocalAssetManager assets = LocalAssetManager.getInstance();
                        Path skinsDirectory = assets.getSkinsDirectory();
                        if (skinsDirectory == null) {
                            E2ELog.warn("LocalAssetManager.getSkinsDirectory() is null");
                            return;
                        }
                        java.nio.file.Files.createDirectories(skinsDirectory);
                        Path target = skinsDirectory.resolve("qs_e2e_external_drop.png");
                        java.nio.file.Files.copy(
                                TestAssets.makeDistinctSkin(),
                                target,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                        // Skin IDs are the plain content hash (only capes are domain-separated).
                        String hash = com.quickskin.mod.common.util.HashUtil.computeFileHash(target);
                        if (hash == null) {
                            E2ELog.warn("could not hash the externally dropped skin");
                            return;
                        }
                        if (assets.getMetadata(hash) != null) {
                            E2ELog.warn("dropped skin was already catalogued; check would be vacuous");
                            return;
                        }
                        externalSkinHash = hash;
                        E2ELog.info("dropped " + target + " without reload(), hash=" + hash);
                    } catch (Exception e) {
                        E2ELog.error("external_skin_drop action failed", e);
                    }
                })
                // Comfortably longer than LocalAssetFolderWatch.POLL_INTERVAL_MILLIS at 20 TPS.
                .minTicks(80)
                .ready(() -> externalSkinHash != null
                        && LocalAssetManager.getInstance().getMetadata(externalSkinHash) != null)
                .timeoutTicks(300)
                .screenshot(prefix + "full_02c_external_drop" + suffix)
                .assertion(() -> {
                    if (externalSkinHash == null) {
                        return Step.Result.fail("external skin was never staged on disk");
                    }
                    if (LocalAssetManager.getInstance().getMetadata(externalSkinHash) == null) {
                        return Step.Result.fail(
                                "open skin menu did not pick up externally dropped skin "
                                        + externalSkinHash);
                    }
                    if (!(VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen)) {
                        return Step.Result.fail("skin menu closed during the poll: " + screenName(mc));
                    }
                    return Step.Result.pass("externally dropped skin catalogued: " + externalSkinHash);
                }));

        // 3. model slim / classic -----------------------------------------------------------------
        steps.add(Step.of("model_slim")
                .action(() -> {
                    prepareModelEvidenceView(mc);
                    if (skinHash != null) svc.applySkin(uuid, "local_skin:" + skinHash, "slim");
                })
                .minTicks(30)
                .ready(() -> holdModelEvidenceView(mc, "slim"))
                .settleTicks(12)
                .timeoutTicks(240)
                .screenshot(prefix + "full_03a_model_slim" + suffix)
                .assertion(() -> assertModelEvidence(mc, svc, uuid, "slim")));

        steps.add(Step.of("model_classic")
                .action(() -> {
                    prepareModelEvidenceView(mc);
                    if (skinHash != null) svc.applySkin(uuid, "local_skin:" + skinHash, "classic");
                })
                .minTicks(30)
                .ready(() -> holdModelEvidenceView(mc, "classic"))
                .settleTicks(12)
                .timeoutTicks(240)
                .screenshot(prefix + "full_03b_model_classic" + suffix)
                .assertion(() -> {
                    try {
                        return assertModelEvidence(mc, svc, uuid, "classic");
                    } finally {
                        restoreModelEvidenceView(mc);
                    }
                }));

        // 4. known cape ---------------------------------------------------------------------------
        steps.add(Step.of("cape_menu_screen")
                .action(() -> {
                    restoreModelEvidenceView(mc);
                    VanillaShim.setScreen(mc, new PlayerCapeMenuScreen(null));
                })
                .minTicks(20)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_04a_cape_menu" + suffix)
                .assertion(() -> VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen
                        ? Step.Result.pass("PlayerCapeMenuScreen open")
                        : Step.Result.fail("cape menu not open: " + screenName(mc))));

        steps.add(Step.of("known_cape_apply")
                .action(() -> {
                    enterWorldView(mc);
                    svc.applyCape(uuid, "known:test");
                })
                .minTicks(30)
                .ready(() -> svc.getAppearance(uuid) != null && svc.getCapeLocation(uuid) != null)
                .timeoutTicks(200)
                .screenshot(prefix + "full_04b_known_cape_body" + suffix)
                .assertion(() -> {
                    PlayerAppearance app = svc.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no appearance");
                    if (!"known:test".equals(app.getCapeId()))
                        return Step.Result.fail("capeId=" + app.getCapeId() + " expected known:test");
                    if (svc.getCapeLocation(uuid) == null)
                        return Step.Result.fail("cape location did not resolve");
                    return Step.Result.pass("capeId=known:test location=" + svc.getCapeLocation(uuid));
                }));

        // 5. CapeAdjustScreen ---------------------------------------------------------------------
        steps.add(Step.of("cape_adjust_screen")
                .action(() -> {
                    capeAdjustResult.set(null);
                    Consumer<BufferedImage> onApply = capeAdjustResult::set;
                    BufferedImage src = TestAssets.makeClassicCapeImage();
                    VanillaShim.setScreen(mc, new CapeAdjustScreen(null, src, onApply));
                })
                .minTicks(25)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_05_cape_adjust" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s))
                        return Step.Result.fail("cape adjust not open: " + screenName(mc));
                    // Trigger the (private) apply path so our owned onApply consumer receives the result.
                    try {
                        Method m = CapeAdjustScreen.class.getDeclaredMethod("applyAndClose");
                        m.setAccessible(true);
                        m.invoke(s);
                    } catch (Throwable t) {
                        return Step.Result.fail("applyAndClose reflection failed: " + t);
                    }
                    BufferedImage out = capeAdjustResult.get();
                    if (out == null) return Step.Result.fail("onApply did not receive an image");
                    int w = out.getWidth(), h = out.getHeight();
                    if (w <= 0 || h <= 0) return Step.Result.fail("composed cape has bad dims " + w + "x" + h);
                    if (w != h * 2) return Step.Result.fail("composed cape not 2:1: " + w + "x" + h);
                    return Step.Result.pass("CapeAdjust onApply received " + w + "x" + h + " cape");
                }));

        // 5b. cape preview follows the SELECTION, not the worn cape -------------------------------
        // The player keeps wearing known:test throughout both steps; only the preview widget is
        // pushed a cape, exactly as selecting an entry does. In a world the preview renders the real
        // player entity, so before the preview-cape binding existed both shots showed the worn cape
        // and were identical. The DISTINCT_SCREENSHOT_PAIRS entry for this pair is the pixel-level
        // proof that the preview now tracks the selection.
        steps.add(Step.of("cape_preview_selected_a")
                .action(() -> {
                    enterWorldView(mc);
                    try {
                        previewCapeHashA = TestAssets.registerLocalCapeAs(
                                TestAssets.makeClassicCape(), "qs_e2e_cape_preview_a.png");
                        previewCapeHashB = TestAssets.registerLocalCapeAs(
                                TestAssets.makeContrastCape(), "qs_e2e_cape_preview_b.png");
                        E2ELog.info("preview capes a=" + previewCapeHashA + " b=" + previewCapeHashB);
                    } catch (Exception e) {
                        E2ELog.error("cape_preview_selected_a asset setup failed", e);
                    }
                    VanillaShim.setScreen(mc, new PlayerCapeMenuScreen(null));
                })
                .minTicks(25)
                // The screenshot is captured as soon as ready() first returns true, so hold the
                // selection for a stretch of rendered ticks first; otherwise the captured frame
                // predates the push and shows the previous cape.
                .ready(() -> previewCapeHashA != null
                        && pushPreviewCape(mc, "local_cape:" + previewCapeHashA)
                        && previewHoldA.incrementAndGet() >= PREVIEW_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_05b_cape_preview_a" + suffix)
                .assertion(() -> previewCapeAssertion(mc, svc, uuid, previewCapeHashA, "A")));

        steps.add(Step.of("cape_preview_selected_b")
                .action(() -> { /* same screen, only the selection changes */ })
                .minTicks(25)
                .ready(() -> previewCapeHashB != null
                        && pushPreviewCape(mc, "local_cape:" + previewCapeHashB)
                        && previewHoldB.incrementAndGet() >= PREVIEW_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_05c_cape_preview_b" + suffix)
                .assertion(() -> previewCapeAssertion(mc, svc, uuid, previewCapeHashB, "B")));

        // 5d/5e. opaque cape fill ------------------------------------------------------------------
        // One CapeAdjustScreen instance spans both steps: the OFF shot, then the same screen with
        // the toggle flipped to a loud magenta. The DISTINCT_SCREENSHOT_PAIRS entry for the pair is
        // the pixel-level proof that the fill reaches the live preview, and the two composeCapeImage
        // assertions are the programmatic proof that what the preview shows is what apply produces.
        steps.add(Step.of("cape_adjust_opaque_off")
                .action(() -> {
                    enterWorldView(mc);
                    capeAdjustResult.set(null);
                    Consumer<BufferedImage> onApply = capeAdjustResult::set;
                    BufferedImage src = TestAssets.makeTransparentCapeImage();
                    VanillaShim.setScreen(mc, new CapeAdjustScreen(null, src, onApply));
                })
                .minTicks(25)
                // Hold the opened screen for a stretch of rendered ticks so the composed preview
                // texture and the 3D player widget are both on screen when the frame is captured.
                .ready(() -> VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen
                        && opaqueHoldOff.incrementAndGet() >= PREVIEW_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_05d_cape_opaque_off" + suffix)
                .assertion(() -> {
                    BufferedImage composed = composeCapeNow(mc);
                    if (composed == null) return Step.Result.fail("composeCapeImage unavailable");
                    if (!TextureAlphaDetector.hasTransparentPixels(composed))
                        return Step.Result.fail("toggle off should leave the cape transparent");
                    int pixel = composed.getRGB(TestAssets.TRANSPARENT_WINDOW_X + 1,
                            TestAssets.TRANSPARENT_WINDOW_Y + 1);
                    if (((pixel >>> 24) & 0xFF) != 0)
                        return Step.Result.fail("window pixel should be transparent, was "
                                + Integer.toHexString(pixel));
                    BufferedImage preview = composePreviewFrameNow(mc);
                    if (preview == null) return Step.Result.fail("composeFrame unavailable");
                    if (!TextureAlphaDetector.hasTransparentPixels(preview))
                        return Step.Result.fail("preview frame should still be transparent");
                    String landmark = checkOpaqueLandmark(composed, 0, "composed");
                    if (landmark != null) return Step.Result.fail(landmark);
                    return Step.Result.pass("toggle off: preview and apply are both transparent");
                }));

        steps.add(Step.of("cape_adjust_opaque_on")
                .action(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s)) {
                        E2ELog.error("cape_adjust_opaque_on: adjust screen closed early", null);
                        return;
                    }
                    try {
                        Method m = CapeAdjustScreen.class.getDeclaredMethod(
                                "setOpaqueFill", boolean.class, int.class);
                        m.setAccessible(true);
                        m.invoke(s, true, OPAQUE_FILL_RGB);
                    } catch (Throwable t) {
                        E2ELog.error("cape_adjust_opaque_on: setOpaqueFill reflection failed", t);
                    }
                })
                .minTicks(25)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen
                        && opaqueHoldOn.incrementAndGet() >= PREVIEW_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_05e_cape_opaque_on" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s))
                        return Step.Result.fail("cape adjust not open: " + screenName(mc));
                    BufferedImage composed = composeCapeNow(mc);
                    if (composed == null) return Step.Result.fail("composeCapeImage unavailable");
                    if (!CapeElytraSilhouette.isOpaqueExceptWingCutout(composed, 1))
                        return Step.Result.fail(
                                "toggle on must fill the atlas except the Elytra silhouette cutout");
                    int pixel = composed.getRGB(TestAssets.TRANSPARENT_WINDOW_X + 1,
                            TestAssets.TRANSPARENT_WINDOW_Y + 1);
                    if (pixel != (0xFF000000 | OPAQUE_FILL_RGB))
                        return Step.Result.fail("window pixel should be the fill, was "
                                + Integer.toHexString(pixel));
                    // The frame the preview texture is built from must carry the fill too — that is
                    // what the 2D thumbnails blit and what PlayerWidget.setCape is handed.
                    BufferedImage preview = composePreviewFrameNow(mc);
                    if (preview == null) return Step.Result.fail("composeFrame unavailable");
                    if (!CapeElytraSilhouette.isOpaqueExceptWingCutout(preview, 1))
                        return Step.Result.fail(
                                "preview must keep only the Elytra silhouette transparent");
                    if (preview.getRGB(TestAssets.TRANSPARENT_WINDOW_X + 1,
                            TestAssets.TRANSPARENT_WINDOW_Y + 1) != (0xFF000000 | OPAQUE_FILL_RGB))
                        return Step.Result.fail("preview frame does not carry the fill colour");
                    // The applied atlas must carry the same fill the preview just showed; both come
                    // from the one pass that finishes every composed frame.
                    try {
                        Method m = CapeAdjustScreen.class.getDeclaredMethod("applyAndClose");
                        m.setAccessible(true);
                        m.invoke(s);
                    } catch (Throwable t) {
                        return Step.Result.fail("applyAndClose reflection failed: " + t);
                    }
                    BufferedImage applied = capeAdjustResult.get();
                    if (applied == null) return Step.Result.fail("onApply did not receive an image");
                    if (!CapeElytraSilhouette.isOpaqueExceptWingCutout(applied, 1))
                        return Step.Result.fail(
                                "applied cape must keep only the Elytra silhouette transparent");
                    if (applied.getRGB(TestAssets.TRANSPARENT_WINDOW_X + 1,
                            TestAssets.TRANSPARENT_WINDOW_Y + 1) != (0xFF000000 | OPAQUE_FILL_RGB))
                        return Step.Result.fail("applied cape does not carry the fill colour");
                    // The fill must touch ONLY transparent pixels: an opaque landmark inside the
                    // same face has to come through bit-for-bit on all three images.
                    for (BufferedImage img : new BufferedImage[] {composed, preview, applied}) {
                        String landmark = checkOpaqueLandmark(img, 0, "filled");
                        if (landmark != null) return Step.Result.fail(landmark);
                    }
                    String animated = assertAnimatedAtlasIsFilled();
                    if (animated != null) return Step.Result.fail(animated);
                    return Step.Result.pass(
                            "toggle on: visible cape pixels are opaque and every atlas preserves "
                                    + "the Elytra silhouette cutout");
                }));

        // 5f/5g. zoom slider ------------------------------------------------------------------------
        // One CapeAdjustScreen instance spans both steps and only the zoom moves between them. The
        // DISTINCT_SCREENSHOT_PAIRS entry is the frame-level proof that the change reached the
        // screen; exactly like the opaque pair it cannot on its own tell a rescaled preview from a
        // moved slider handle, because the handle and its "Zoom: n%" label are in frame too. The
        // pixel-level proof is here instead: the composed atlas is captured at both positions and
        // the two must differ. These steps also assert the two things that make the slider and the
        // wheel one control — that the widget's value always equals the position imgScale implies,
        // and that one wheel notch moves the handle by exactly the mapping's step.
        steps.add(Step.of("cape_adjust_zoom_out")
                .action(() -> {
                    enterWorldView(mc);
                    capeAdjustResult.set(null);
                    zoomedOutAtlas.set(null);
                    Consumer<BufferedImage> onApply = capeAdjustResult::set;
                    // A real 320x180 PNG, not a synthetic 64x32 one: 64x32 is the format
                    // CapeImportProcessor saves directly, so the adjust screen never sees it in
                    // production. This shape is the one that reaches the screen for real, it leaves
                    // three target resolutions selectable so the resolution check below has
                    // somewhere to go, and its 16:9 aspect separates the contain fit from the cover
                    // fit so the zoom range is the general case rather than the degenerate one.
                    BufferedImage src = TestAssets.makeZoomSourceImage();
                    VanillaShim.setScreen(mc, new CapeAdjustScreen(null, src, onApply));
                })
                .minTicks(25)
                // Driving the slider from ready() rather than action() means it is set after init()
                // has built the widgets, however the screen was opened; setting the same position
                // repeatedly is a no-op, so holding for a stretch of rendered ticks is safe.
                .ready(() -> setZoomOnAdjustScreen(mc, ZOOM_OUT_POSITION)
                        && zoomHoldOut.incrementAndGet() >= PREVIEW_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_05f_cape_zoom_out" + suffix)
                .assertion(() -> {
                    String desync = checkZoomSliderAgrees(mc, ZOOM_OUT_POSITION);
                    if (desync != null) return Step.Result.fail(desync);
                    BufferedImage composed = composeCapeNow(mc);
                    if (composed == null) return Step.Result.fail("composeCapeImage unavailable");
                    zoomedOutAtlas.set(composed);
                    return Step.Result.pass("zoomed out to position " + ZOOM_OUT_POSITION
                            + " (" + composed.getWidth() + "x" + composed.getHeight() + " atlas)");
                }));

        steps.add(Step.of("cape_adjust_zoom_in")
                .action(() -> { /* same screen, only the zoom changes */ })
                .minTicks(25)
                .ready(() -> setZoomOnAdjustScreen(mc, ZOOM_IN_POSITION)
                        && zoomHoldIn.incrementAndGet() >= PREVIEW_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_05g_cape_zoom_in" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s))
                        return Step.Result.fail("cape adjust not open: " + screenName(mc));
                    String desync = checkZoomSliderAgrees(mc, ZOOM_IN_POSITION);
                    if (desync != null) return Step.Result.fail(desync);

                    // The cape content itself must have rescaled, not merely the panel repainted.
                    BufferedImage zoomedOut = zoomedOutAtlas.get();
                    if (zoomedOut == null) return Step.Result.fail("no zoomed-out atlas captured");
                    BufferedImage zoomedIn = composeCapeNow(mc);
                    if (zoomedIn == null) return Step.Result.fail("composeCapeImage unavailable");
                    long differing = countDifferingPixels(zoomedOut, zoomedIn);
                    if (differing < 0) return Step.Result.fail("atlas geometry changed with the zoom");
                    // A tenth of the atlas is a very low bar for 0.10 -> 0.80 of the track; it is
                    // set low on purpose so this asserts "the pixels moved", not a golden framing.
                    long minimumDifferent = (long) zoomedIn.getWidth() * zoomedIn.getHeight() / 10;
                    if (differing < minimumDifferent)
                        return Step.Result.fail("composed atlas barely changed across the zoom: "
                                + differing + " of " + ((long) zoomedIn.getWidth()
                                * zoomedIn.getHeight()) + " pixels differ");

                    // A slider is a continuous control, so the framing must not depend on how many
                    // events the drag arrives in. Walking the same span in single steps has to land
                    // on the same atlas as jumping it. This is the integration-level guard for the
                    // offset chaining: each step asks for a sub-pixel offset change, so a screen
                    // that chained the anchor correction off the snapped offset would round every
                    // one of them to nothing and freeze the framing while the scale climbed.
                    setZoomOnAdjustScreen(mc, ZOOM_OUT_POSITION);
                    for (int i = 1; i <= ZOOM_DRAG_STEPS; i++) {
                        setZoomOnAdjustScreen(mc, ZOOM_OUT_POSITION
                                + (ZOOM_IN_POSITION - ZOOM_OUT_POSITION) * i / ZOOM_DRAG_STEPS);
                    }
                    String walked = checkZoomSliderAgrees(mc, ZOOM_IN_POSITION);
                    if (walked != null) return Step.Result.fail("after a stepped drag: " + walked);
                    BufferedImage dragged = composeCapeNow(mc);
                    if (dragged == null) return Step.Result.fail("composeCapeImage unavailable");
                    long crawl = countDifferingPixels(zoomedIn, dragged);
                    if (crawl != 0)
                        return Step.Result.fail("a " + ZOOM_DRAG_STEPS + "-step drag framed the cape "
                                + "differently from the same drag done in one jump: " + crawl
                                + " pixels differ");

                    // The wheel must still be the same control: one notch over the middle of the
                    // grid is one mapping step of the handle, and the two views must still agree.
                    double before = doubleOnAdjustScreen(mc, "zoomSliderValue");
                    if (!scrollOnGridCentre(mc, true))
                        return Step.Result.fail("could not deliver a scroll to the grid");
                    double after = doubleOnAdjustScreen(mc, "zoomSliderValue");
                    double expectedStep;
                    try {
                        // Derived from the live source rather than hardcoded, so the assertion
                        // follows the bundled image instead of assuming its size.
                        expectedStep = CapeZoomRange.wheelStepPosition(
                                RESOLUTION_2X_W,
                                ((BufferedImage) adjustScreenObject(s, "sourceImage")).getWidth(),
                                adjustScreenInt(s, "srcFrameHeight"));
                    } catch (Exception e) {
                        return Step.Result.fail("could not read the source dimensions: " + e);
                    }
                    if (Math.abs((after - before) - expectedStep) > 1.0e-6)
                        return Step.Result.fail("a wheel notch moved the slider by "
                                + (after - before) + ", expected " + expectedStep);
                    desync = checkZoomSliderAgrees(mc, after);
                    if (desync != null) return Step.Result.fail("after scrolling: " + desync);

                    // Switching target resolution rescales the whole transform
                    // (imgScale *= newCapeW / oldCapeW). Both ends of the zoom's legal range carry
                    // the same factor of capeW, so the handle must not move and the label must not
                    // change — the property the normalised mapping is built on, asserted here
                    // against the live screen and not only against the arithmetic. This is also the
                    // only step that exercises a resolution other than the default, which the old
                    // 64x32 source could not reach at all.
                    double positionBefore = doubleOnAdjustScreen(mc, "zoomPosition");
                    double percentBefore = doubleOnAdjustScreen(mc, "zoomPercent");
                    double scaleBefore;
                    try {
                        scaleBefore = adjustScreenDouble(s, "imgScale");
                    } catch (Exception e) {
                        return Step.Result.fail("could not read imgScale: " + e);
                    }
                    if (!pressResolutionButton(mc, RESOLUTION_2X_LABEL))
                        return Step.Result.fail("could not press the " + RESOLUTION_2X_LABEL
                                + " resolution button — is the source large enough for it?");
                    double scaleAfter;
                    try {
                        scaleAfter = adjustScreenDouble(s, "imgScale");
                    } catch (Exception e) {
                        return Step.Result.fail("could not read imgScale: " + e);
                    }
                    if (Math.abs(scaleAfter - scaleBefore * 2.0) > 1.0e-9)
                        return Step.Result.fail("switching to " + RESOLUTION_2X_LABEL
                                + " should have doubled imgScale: " + scaleBefore
                                + " -> " + scaleAfter);
                    String rode = checkZoomSliderAgrees(mc, positionBefore);
                    if (rode != null)
                        return Step.Result.fail("after switching resolution: " + rode);
                    if (Math.abs(doubleOnAdjustScreen(mc, "zoomPercent") - percentBefore) > 1.0e-9)
                        return Step.Result.fail("the zoom label changed across a resolution switch: "
                                + percentBefore + " -> " + doubleOnAdjustScreen(mc, "zoomPercent"));
                    BufferedImage resized = composeCapeNow(mc);
                    if (resized == null)
                        return Step.Result.fail("composeCapeImage unavailable");
                    if (resized.getWidth() != RESOLUTION_2X_W
                            || resized.getHeight() != RESOLUTION_2X_W / 2)
                        return Step.Result.fail("resolution switch did not resize the atlas: "
                                + resized.getWidth() + "x" + resized.getHeight());

                    // And what the preview is showing has to be what apply hands over, byte for
                    // byte: both come from the same transform through the same composer.
                    BufferedImage previewed = composeCapeNow(mc);
                    if (previewed == null) return Step.Result.fail("composeCapeImage unavailable");
                    try {
                        Method m = CapeAdjustScreen.class.getDeclaredMethod("applyAndClose");
                        m.setAccessible(true);
                        m.invoke(s);
                    } catch (Throwable t) {
                        return Step.Result.fail("applyAndClose reflection failed: " + t);
                    }
                    BufferedImage applied = capeAdjustResult.get();
                    if (applied == null) return Step.Result.fail("onApply did not receive an image");
                    long drift = countDifferingPixels(previewed, applied);
                    if (drift != 0)
                        return Step.Result.fail("applied cape differs from the previewed one in "
                                + drift + " pixels");
                    return Step.Result.pass("zoom " + ZOOM_OUT_POSITION + " -> " + ZOOM_IN_POSITION
                            + " changed " + differing + " atlas pixels; a " + ZOOM_DRAG_STEPS
                            + "-step drag matched the jump exactly; a wheel notch moved the slider "
                            + "by " + expectedStep + "; applied == previewed");
                }));

        // 5h-5n. bundled BMO versus the same atlas recovered through the editor ------------------
        // The imported source is a 128x64 black canvas with the production 64x32 BMO atlas centred
        // inside it. Reset shows the whole padded source at 50%; moving the real zoom slider to
        // scale 1.0 must re-anchor it to (-32,-16), crop away only the black padding and reproduce
        // every BMO cape + elytra UV pixel exactly. The paired world captures then redundantly check
        // that the bundled and adjusted ids render the same surface with and without worn elytra.
        final AtomicReference<String> bmoSetupFailure = new AtomicReference<>();
        steps.add(Step.of("bundled_bmo_cape")
                .action(() -> {
                    enterWorldView(mc);
                    setChestSlot(mc, ItemStack.EMPTY);
                    svc.applyCape(uuid, "known:bmo");
                })
                .minTicks(30)
                .ready(() -> hasExpectedCape(svc, uuid, "known:bmo")
                        && mc.player != null
                        && mc.player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                        && VanillaShim.cloakTexture(mc.player) != null)
                .timeoutTicks(300)
                .screenshot(bundledBmoCapeShot)
                .assertion(() -> assertCapeRoute(mc, svc, uuid, "known:bmo", false)));

        steps.add(Step.of("bundled_bmo_elytra")
                .action(() -> {
                    enterWorldView(mc);
                    poseElytraForEvidence(mc);
                })
                .minTicks(25)
                .ready(() -> {
                    poseElytraForEvidence(mc);
                    return hasExpectedCape(svc, uuid, "known:bmo")
                            && mc.player != null
                            && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                            && mc.player.isCrouching()
                            && VanillaShim.cloakTexture(mc.player) != null;
                })
                .settleTicks(12)
                .timeoutTicks(300)
                .screenshot(bundledBmoElytraShot)
                .assertion(() -> assertCapeRoute(mc, svc, uuid, "known:bmo", true)));

        steps.add(Step.of("bmo_padded_source_screen")
                .action(() -> {
                    enterWorldView(mc);
                    setChestSlot(mc, ItemStack.EMPTY);
                    capeAdjustResult.set(null);
                    bmoPreparedCape.set(null);
                    bundledBmoAtlas.set(null);
                    adjustedBmoAtlas.set(null);
                    adjustedBmoCapeHash = null;
                    bmoAdjustHold.set(0);
                    bmoSetupFailure.set(null);
                    try {
                        Path source = TestAssets.makePaddedBmoCapeSource();
                        CapeImportProcessor.PreparedCape prepared =
                                CapeImportProcessor.prepare(source);
                        bmoPreparedCape.set(prepared);
                        bundledBmoAtlas.set(TestAssets.makeBundledBmoCapeImage());
                        Consumer<BufferedImage> onApply = capeAdjustResult::set;
                        VanillaShim.setScreen(mc, new CapeAdjustScreen(
                                null, prepared.atlas(), prepared.frameCount(), onApply));
                    } catch (Throwable t) {
                        bmoSetupFailure.set("could not prepare padded BMO source: " + t);
                        E2ELog.error("bmo_padded_source_screen setup failed", t);
                    }
                })
                .minTicks(25)
                .ready(() -> {
                    if (bmoSetupFailure.get() != null) return true;
                    return VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen
                            && bmoAdjustHold.incrementAndGet() >= PREVIEW_HOLD_TICKS;
                })
                .timeoutTicks(400)
                .screenshot(prefix + "full_05j_bmo_padded_source" + suffix)
                .assertion(() -> {
                    String setupFailure = bmoSetupFailure.get();
                    if (setupFailure != null) return Step.Result.fail(setupFailure);
                    if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen screen))
                        return Step.Result.fail("BMO padded source screen not open: " + screenName(mc));
                    CapeImportProcessor.PreparedCape prepared = bmoPreparedCape.get();
                    BufferedImage expected = bundledBmoAtlas.get();
                    if (prepared == null || expected == null)
                        return Step.Result.fail("BMO source or expected atlas was not retained");
                    if (prepared.standardFormat())
                        return Step.Result.fail("128x64 padded BMO source bypassed the editor");
                    try {
                        if (adjustScreenInt(screen, "selectedResolution") != 0
                                || !"128x64".equals(stringOnAdjustScreen(mc,
                                        "sourceDimensions"))
                                || !"64x32".equals(stringOnAdjustScreen(mc,
                                        "outputDimensions"))) {
                            return Step.Result.fail("BMO evidence labels or output resolution "
                                    + "do not identify source=128x64 output=64x32");
                        }
                    } catch (Exception e) {
                        return Step.Result.fail("could not read BMO evidence dimensions: " + e);
                    }

                    String padding = validatePaddedBmoSource(prepared.atlas(), expected);
                    if (padding != null) return Step.Result.fail(padding);
                    double target = bmoPaddedZoomPosition();
                    String desync = checkZoomSliderAgrees(mc, target);
                    if (desync != null) return Step.Result.fail(desync);
                    double scale;
                    double offsetX;
                    double offsetY;
                    try {
                        scale = adjustScreenDouble(screen, "imgScale");
                        offsetX = adjustScreenDouble(screen, "imgOffsetX");
                        offsetY = adjustScreenDouble(screen, "imgOffsetY");
                    } catch (Exception e) {
                        return Step.Result.fail("could not read BMO transform: " + e);
                    }
                    double expectedScale = (double) TestAssets.BMO_CAPE_WIDTH
                            / TestAssets.BMO_PADDED_WIDTH;
                    if (Math.abs(scale - expectedScale) > 1.0e-9
                            || Math.abs(offsetX) > 1.0e-9
                            || Math.abs(offsetY) > 1.0e-9) {
                        return Step.Result.fail("BMO transform is scale=" + scale + " offset=("
                                + offsetX + "," + offsetY + "), expected " + expectedScale
                                + " offset=(0,0)");
                    }

                    return Step.Result.pass("complete 64x32 BMO atlas is centred inside exact "
                            + "opaque-black 128x64 padding at reset scale " + expectedScale);
                }));

        steps.add(Step.of("bmo_adjust_screen")
                .action(() -> bmoAdjustHold.set(0))
                .minTicks(5)
                .ready(() -> {
                    if (bmoSetupFailure.get() != null) return true;
                    double target = bmoTargetZoomPosition();
                    return setZoomOnAdjustScreen(mc, target)
                            && bmoAdjustHold.incrementAndGet() >= PREVIEW_HOLD_TICKS;
                })
                .timeoutTicks(400)
                .screenshot(prefix + "full_05k_bmo_adjusted_editor" + suffix)
                .assertion(() -> {
                    String setupFailure = bmoSetupFailure.get();
                    if (setupFailure != null) return Step.Result.fail(setupFailure);
                    if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen screen))
                        return Step.Result.fail("BMO cape adjust not open: " + screenName(mc));
                    CapeImportProcessor.PreparedCape prepared = bmoPreparedCape.get();
                    BufferedImage expected = bundledBmoAtlas.get();
                    if (prepared == null || expected == null)
                        return Step.Result.fail("BMO source or expected atlas was not retained");
                    if (prepared.standardFormat())
                        return Step.Result.fail("128x64 padded BMO source bypassed the editor");

                    String padding = validatePaddedBmoSource(prepared.atlas(), expected);
                    if (padding != null) return Step.Result.fail(padding);
                    double target = bmoTargetZoomPosition();
                    String desync = checkZoomSliderAgrees(mc, target);
                    if (desync != null) return Step.Result.fail(desync);
                    double scale;
                    double offsetX;
                    double offsetY;
                    try {
                        scale = adjustScreenDouble(screen, "imgScale");
                        offsetX = adjustScreenDouble(screen, "imgOffsetX");
                        offsetY = adjustScreenDouble(screen, "imgOffsetY");
                    } catch (Exception e) {
                        return Step.Result.fail("could not read BMO transform: " + e);
                    }
                    if (Math.abs(scale - 1.0) > 1.0e-9
                            || Math.abs(offsetX + TestAssets.BMO_PADDED_X) > 1.0e-9
                            || Math.abs(offsetY + TestAssets.BMO_PADDED_Y) > 1.0e-9) {
                        return Step.Result.fail("BMO transform is scale=" + scale + " offset=("
                                + offsetX + "," + offsetY + "), expected 1.0 offset=(-"
                                + TestAssets.BMO_PADDED_X + ",-" + TestAssets.BMO_PADDED_Y + ")");
                    }

                    BufferedImage composed = composeCapeNow(mc);
                    BufferedImage previewed = composePreviewFrameNow(mc);
                    if (composed == null || previewed == null)
                        return Step.Result.fail("BMO composed or preview atlas unavailable");
                    long composedDrift = countDifferingPixels(expected, composed);
                    long previewDrift = countDifferingPixels(expected, previewed);
                    if (composedDrift != 0 || previewDrift != 0)
                        return Step.Result.fail("BMO atlas drift before apply: composed="
                                + composedDrift + " preview=" + previewDrift + " pixels");
                    if (CapeImportProcessor.isElytraAreaTransparent(composed))
                        return Step.Result.fail("adjusted BMO atlas lost the bundled elytra UVs");

                    try {
                        Method apply = CapeAdjustScreen.class.getDeclaredMethod("applyAndClose");
                        apply.setAccessible(true);
                        apply.invoke(screen);
                    } catch (Throwable t) {
                        return Step.Result.fail("BMO applyAndClose failed: " + t);
                    }
                    BufferedImage applied = capeAdjustResult.get();
                    if (applied == null) return Step.Result.fail("BMO onApply returned no atlas");
                    long appliedDrift = countDifferingPixels(expected, applied);
                    if (appliedDrift != 0)
                        return Step.Result.fail("applied BMO differs from bundled atlas in "
                                + appliedDrift + " pixels");
                    adjustedBmoAtlas.set(applied);
                    adjustedBmoCapeHash = TestAssets.registerAdjustedCape(prepared, applied);
                    if (adjustedBmoCapeHash == null)
                        return Step.Result.fail("adjusted BMO was not catalogued as a local cape");
                    AssetMetadata metadata = LocalAssetManager.getInstance()
                            .getMetadata(adjustedBmoCapeHash);
                    if (metadata == null || !metadata.isCape()
                            || metadata.resolution().getWidth() != TestAssets.BMO_CAPE_WIDTH
                            || metadata.resolution().getHeight() != TestAssets.BMO_CAPE_HEIGHT) {
                        return Step.Result.fail("adjusted BMO metadata is missing or not 64x32: "
                                + metadata);
                    }
                    return Step.Result.pass("128x64 black-padded BMO aligned at scale 1.0 / offset "
                            + "(-32,-16); preview, applied and bundled 64x32 atlases are identical");
                }));

        steps.add(Step.of("adjusted_bmo_cape")
                .action(() -> {
                    enterWorldView(mc);
                    setChestSlot(mc, ItemStack.EMPTY);
                    if (adjustedBmoCapeHash != null) {
                        String capeId = "local_cape:" + adjustedBmoCapeHash;
                        ClientConfig config = ClientConfig.getInstance();
                        config.activeCapeHash = capeId;
                        config.save();
                        svc.applyCape(uuid, capeId);
                    }
                })
                .minTicks(30)
                .ready(() -> bmoSetupFailure.get() != null
                        || (adjustedBmoCapeHash != null
                        && hasExpectedCape(svc, uuid, "local_cape:" + adjustedBmoCapeHash)
                        && mc.player != null
                        && mc.player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                        && VanillaShim.cloakTexture(mc.player) != null))
                .timeoutTicks(300)
                .screenshot(adjustedBmoCapeShot)
                .assertion(() -> bmoSetupFailure.get() == null
                        ? assertAdjustedBmoRoute(mc, svc, uuid, false)
                        : Step.Result.fail("BMO setup failed before adjusted cape: "
                                + bmoSetupFailure.get())));

        steps.add(Step.of("adjusted_bmo_elytra")
                .action(() -> {
                    enterWorldView(mc);
                    poseElytraForEvidence(mc);
                })
                .minTicks(25)
                .ready(() -> {
                    poseElytraForEvidence(mc);
                    return bmoSetupFailure.get() != null
                            || (adjustedBmoCapeHash != null
                            && hasExpectedCape(svc, uuid, "local_cape:" + adjustedBmoCapeHash)
                            && mc.player != null
                            && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                            && mc.player.isCrouching()
                            && VanillaShim.cloakTexture(mc.player) != null);
                })
                .settleTicks(12)
                .timeoutTicks(300)
                .screenshot(adjustedBmoElytraShot)
                .assertion(() -> bmoSetupFailure.get() == null
                        ? assertAdjustedBmoRoute(mc, svc, uuid, true)
                        : Step.Result.fail("BMO setup failed before adjusted elytra: "
                                + bmoSetupFailure.get())));

        // 5n-5o. removing an active cape restores the vanilla elytra texture ---------------------
        // Start from the immediately preceding, render-truthful adjusted-BMO elytra checkpoint.
        // One step opens the real cape menu and presses Remove Cape; the next returns to the exact
        // same equipped, crouching rear view. Keeping UI action and world capture separate matters:
        // opening a paused screen releases movement keys, so it cannot itself certify the pose.
        steps.add(Step.of("remove_cape_with_elytra")
                .action(() -> {
                    enterWorldView(mc);
                    poseElytraForEvidence(mc);
                    VanillaShim.setScreen(mc, new PlayerCapeMenuScreen(null));
                })
                .minTicks(8)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen screen
                        && !screen.children().isEmpty())
                .timeoutTicks(300)
                .assertion(() -> {
                    Step.Result customRoute = assertAdjustedBmoRoute(
                            mc, svc, uuid, true, false);
                    if (!customRoute.pass()) {
                        return Step.Result.fail("custom elytra precondition failed: "
                                + customRoute.message());
                    }
                    String expectedCapeId = "local_cape:" + adjustedBmoCapeHash;
                    if (!expectedCapeId.equals(ClientConfig.getInstance().activeCapeHash)) {
                        return Step.Result.fail("persisted cape precondition failed: "
                                + ClientConfig.getInstance().activeCapeHash
                                + " expected " + expectedCapeId);
                    }
                    String removeLabel = Component.translatable(
                            "quickskin.button.remove_cape").getString();
                    if (!pressActiveButton(mc, removeLabel)) {
                        return Step.Result.fail(
                                "active Remove Cape button not found in cape menu");
                    }
                    if (svc.hasActiveCape(uuid)
                            || svc.getCapeLocation(uuid) != null
                            || !ClientConfig.getInstance().activeCapeHash.isEmpty()) {
                        return Step.Result.fail(
                                "Remove Cape did not synchronously clear config and service state");
                    }
                    return Step.Result.pass("real Remove Cape button cleared the active custom "
                            + "cape while the elytra remained equipped");
                }));

        // The wings must keep rendering, but both custom texture inputs must disappear so
        // vanilla's minecraft:textures/entity/elytra.png fallback owns the captured surface.
        final AtomicInteger vanillaElytraWaitTicks = new AtomicInteger(0);
        steps.add(Step.of("vanilla_elytra_after_cape_removal")
                .action(() -> {
                    vanillaElytraWaitTicks.set(0);
                    enterWorldView(mc);
                    poseElytraForEvidence(mc);
                })
                .minTicks(25)
                .ready(() -> {
                    poseElytraForEvidence(mc);
                    String problem = vanillaElytraFallbackProblem(mc, svc, uuid);
                    int waited = vanillaElytraWaitTicks.incrementAndGet();
                    if (problem != null && waited % 40 == 0) {
                        E2ELog.info("waiting for vanilla elytra fallback: " + problem);
                    }
                    return problem == null;
                })
                .settleTicks(12)
                .timeoutTicks(300)
                .screenshot(removedBmoElytraShot)
                .assertion(() -> assertVanillaElytraAfterCapeRemoval(mc, svc, uuid)));

        steps.add(Step.of("bmo_render_parity")
                .minTicks(15)
                .ready(() -> bmoSetupFailure.get() != null
                        || (readShot(bundledBmoCapeShot) != null
                        && readShot(bundledBmoElytraShot) != null
                        && readShot(adjustedBmoCapeShot) != null
                        && readShot(adjustedBmoElytraShot) != null))
                .timeoutTicks(400)
                .assertion(() -> bmoSetupFailure.get() == null
                        ? compareBmoRenderPairs(
                                bundledBmoCapeShot, adjustedBmoCapeShot,
                                bundledBmoElytraShot, adjustedBmoElytraShot)
                        : Step.Result.fail("BMO setup failed before render comparison: "
                                + bmoSetupFailure.get())));

        // 6. animated cape ------------------------------------------------------------------------
        steps.add(Step.of("animated_cape_apply")
                .action(() -> {
                    enterWorldView(mc);
                    // The preceding BMO parity probe deliberately equips an elytra. Every cape
                    // checkpoint owns its equipment state so a previous step cannot silently turn
                    // the cape texture into an elytra render.
                    setChestSlot(mc, ItemStack.EMPTY);
                    try {
                        // The mod decodes the valid bundled GIF cape into an animated local cape.
                        // Missing evidence is fatal: a different fallback could satisfy the logical
                        // animation checks without proving the contracted red/blue render change.
                        gifCapeHash = TestAssets.registerBundledGifCape();
                        if (gifCapeHash == null) {
                            throw new IllegalStateException("bundled animated cape is missing");
                        }
                        svc.applyCape(uuid, "local_cape:" + gifCapeHash);
                        // Hold the initial frame while its screenshot settles. The follow-up
                        // step advances to an exact different frame, then freezes it as well.
                        AnimatedTextureManager.getInstance().setAnimationSpeed(
                                "cape_" + gifCapeHash, 0.0f);
                        E2ELog.info("applied local animated GIF cape local_cape:" + gifCapeHash);
                    } catch (Exception e) {
                        E2ELog.error("animated_cape_apply failed", e);
                    }
                })
                .minTicks(20)
                .ready(() -> {
                    setChestSlot(mc, ItemStack.EMPTY);
                    String expectedCapeId = expectedAnimatedCapeId();
                    Object state = expectedAnimatedState();
                    if (state != null) {
                        AnimatedTextureManager.getInstance().setAnimationSpeed(
                                expectedAnimationId(), 0.0f);
                        AnimatedTextureManager.getInstance().setAnimationFrame(
                                expectedAnimationId(), ANIMATED_EVIDENCE_FRAME_A);
                    }
                    return state != null
                            && hasExpectedCape(svc, uuid, expectedCapeId)
                            && hasEmptyChest(mc)
                            && VanillaShim.cloakTexture(mc.player) != null
                            && frameOf(state) == ANIMATED_EVIDENCE_FRAME_A;
                })
                .settleTicks(12)
                .timeoutTicks(200)
                .screenshot(prefix + "full_06a_animated_cape_frameA" + suffix)
                .assertion(() -> {
                    if (!hasEmptyChest(mc)) {
                        return Step.Result.fail("animated cape rendered with a non-empty CHEST slot");
                    }
                    Object st = expectedAnimatedState();
                    if (st == null) return Step.Result.fail("no animated AnimationState registered");
                    AnimationMetadata meta = metaOf(st);
                    int fc = (meta == null) ? -1 : meta.frameCount();
                    animStartFrame = frameOf(st);
                    if (fc < 2) return Step.Result.fail("animation frameCount=" + fc + " (not animated)");
                    if (animStartFrame < 0 || animStartFrame >= fc)
                        return Step.Result.fail("currentFrame out of range: " + animStartFrame + "/" + fc);
                    if (animStartFrame != ANIMATED_EVIDENCE_FRAME_A) {
                        return Step.Result.fail("animated evidence frame A drifted to "
                                + animStartFrame);
                    }
                    PlayerAppearance app = svc.getAppearance(uuid);
                    String capeId = app == null ? null : app.getCapeId();
                    String expectedCapeId = expectedAnimatedCapeId();
                    if (!hasExpectedCape(svc, uuid, expectedCapeId)) {
                        return Step.Result.fail("animated cape route is not active: " + capeId);
                    }
                    return Step.Result.pass("local GIF cape registered capeId=" + capeId
                            + " frameCount=" + fc + " startFrame=" + animStartFrame);
                }));

        steps.add(Step.of("animated_cape_advance")
                .action(() -> {
                    setChestSlot(mc, ItemStack.EMPTY);
                    if (gifCapeHash != null) {
                        AnimatedTextureManager.getInstance().setAnimationFrame(
                                expectedAnimationId(), ANIMATED_EVIDENCE_FRAME_B);
                    }
                })
                .minTicks(5)
                // Reassert the exact target frame while the rendered image settles. This proves a
                // real frame change without racing a fast wall-clock animation past the screenshot.
                .ready(() -> {
                    setChestSlot(mc, ItemStack.EMPTY);
                    Object st = expectedAnimatedState();
                    if (st != null) {
                        AnimatedTextureManager.getInstance().setAnimationFrame(
                                expectedAnimationId(), ANIMATED_EVIDENCE_FRAME_B);
                    }
                    return hasEmptyChest(mc)
                            && hasExpectedCape(svc, uuid, expectedAnimatedCapeId())
                            && VanillaShim.cloakTexture(mc.player) != null
                            && st != null
                            && animStartFrame != Integer.MIN_VALUE
                            && frameOf(st) != animStartFrame
                            && frameOf(st) == ANIMATED_EVIDENCE_FRAME_B;
                })
                .settleTicks(12)
                .timeoutTicks(200)
                .screenshot(prefix + "full_06b_animated_cape_frameB" + suffix)
                .assertion(() -> {
                    if (!hasEmptyChest(mc)) {
                        return Step.Result.fail("advanced cape frame rendered with a non-empty CHEST slot");
                    }
                    if (!hasExpectedCape(svc, uuid, expectedAnimatedCapeId())) {
                        return Step.Result.fail("animated cape route disappeared before frame B");
                    }
                    Object st = expectedAnimatedState();
                    if (st == null) return Step.Result.fail("animation disappeared");
                    AnimationMetadata meta = metaOf(st);
                    if (meta == null) return Step.Result.fail("no metadata");
                    int fc = meta.frameCount();
                    int now = frameOf(st);
                    if (now == animStartFrame)
                        return Step.Result.fail("currentFrame did not advance (stuck at " + now + ")");
                    if (now != ANIMATED_EVIDENCE_FRAME_B) {
                        return Step.Result.fail("animated evidence frame B drifted to " + now);
                    }
                    if (now < 0 || now >= fc)
                        return Step.Result.fail("currentFrame out of range: " + now + "/" + fc);
                    return Step.Result.pass("frame advanced " + animStartFrame + "->" + now
                            + "/" + fc + " and remained pinned through capture settlement");
                }));

        // 7. HD cape import (no downscale) --------------------------------------------------------
        steps.add(Step.of("hd_cape_no_downscale")
                .action(() -> {
                    enterWorldView(mc);
                    setChestSlot(mc, ItemStack.EMPTY);
                    hdCapeHash = null;
                    hdCapeSource.set(null);
                    hdCapePresentation.set(null);
                    try {
                        Path hd = TestAssets.makeHdCape(); // 256x128 == CAPE_256, kept verbatim on import
                        hdCapeSource.set(SafeImageReader.readPng(hd));
                        hdCapeHash = TestAssets.registerLocalCapeAs(hd, "qs_e2e_cape_hd.png");
                        if (hdCapeHash != null) {
                            byte[] presented = LocalAssetManager.getInstance()
                                    .loadTexture(hdCapeHash, TextureQuality.FULL);
                            hdCapePresentation.set(SafeImageReader.readPng(presented));
                        }
                        if (hdCapeHash != null) svc.applyCape(uuid, "local_cape:" + hdCapeHash);
                        E2ELog.info("registered HD local cape hash=" + hdCapeHash);
                    } catch (Exception e) {
                        E2ELog.error("hd_cape_no_downscale failed", e);
                    }
                })
                .minTicks(30)
                .ready(() -> {
                    setChestSlot(mc, ItemStack.EMPTY);
                    return hdCapeHash != null
                            && LocalAssetManager.getInstance().getMetadata(hdCapeHash) != null
                            && hasExpectedCape(svc, uuid, "local_cape:" + hdCapeHash)
                            && hasEmptyChest(mc)
                            && VanillaShim.cloakTexture(mc.player) != null;
                })
                .timeoutTicks(200)
                .screenshot(prefix + "full_07_hd_cape_body" + suffix)
                .assertion(() -> {
                    if (hdCapeHash == null) return Step.Result.fail("HD cape registration failed");
                    if (!hasEmptyChest(mc)) {
                        return Step.Result.fail("HD cape rendered with a non-empty CHEST slot");
                    }
                    if (!hasExpectedCape(svc, uuid, "local_cape:" + hdCapeHash)) {
                        return Step.Result.fail("HD cape route is not active");
                    }
                    AssetMetadata meta = LocalAssetManager.getInstance().getMetadata(hdCapeHash);
                    if (meta == null) return Step.Result.fail("no metadata for HD cape");
                    int w = meta.resolution().getWidth(), h = meta.resolution().getHeight();
                    if (w != 256 || h != 128)
                        return Step.Result.fail("HD cape downscaled: resolution=" + w + "x" + h + " expected 256x128");
                    if (!meta.isCape())
                        return Step.Result.fail("metadata type=" + meta.type() + " expected cape");
                    BufferedImage hdAtlas = hdCapePresentation.get();
                    if (!CapeElytraSilhouette.hasRequiredCutout(hdAtlas, 1)) {
                        return Step.Result.fail(
                                "HD cape presentation did not restore the tapered Elytra cutout");
                    }
                    if (!CapeElytraSilhouette.isExactMaskedPresentation(
                            hdCapeSource.get(), hdAtlas, 1)) {
                        return Step.Result.fail(
                                "HD cape presentation changed pixels outside the Elytra cutout");
                    }
                    return Step.Result.pass("HD cape preserved at " + w + "x" + h
                            + " with tapered Elytra cutout (no downscale)");
                }));

        // 8. elytra hides cape --------------------------------------------------------------------
        steps.add(Step.of("elytra_hides_cape")
                .action(() -> {
                    enterWorldView(mc);
                    equipElytra(mc);
                })
                .minTicks(15)
                .ready(() -> {
                    equipElytra(mc); // re-assert each tick in case creative inventory sync clears it
                    return mc.player != null
                            && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                            && svc.hasActiveCape(uuid)
                            && VanillaShim.cloakTexture(mc.player) != null;
                })
                .timeoutTicks(200)
                .screenshot(prefix + "full_08_elytra_hides_cape" + suffix)
                .assertion(() -> {
                    if (mc.player == null) return Step.Result.fail("player null");
                    equipElytra(mc);
                    boolean elytra = mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
                    boolean activeCape = svc.hasActiveCape(uuid);
                    String cloak = VanillaShim.cloakTexture(mc.player);
                    if (!elytra) return Step.Result.fail("CHEST slot is not an elytra");
                    if (!activeCape) return Step.Result.fail("hasActiveCape(uuid) is false");
                    if (cloak == null) return Step.Result.fail("cloak location null (cancel via hide branch, not elytra)");
                    BufferedImage hdAtlas = hdCapePresentation.get();
                    if (!CapeElytraSilhouette.hasRequiredCutout(hdAtlas, 1)) {
                        return Step.Result.fail(
                                "active HD cape atlas has no tapered Elytra alpha cutout");
                    }
                    if (!CapeElytraSilhouette.isExactMaskedPresentation(
                            hdCapeSource.get(), hdAtlas, 1)) {
                        return Step.Result.fail(
                                "active HD cape presentation is not an exact structural mask");
                    }
                    // CapeLayerMixin cancels the flat cape iff these inputs hold. Minecraft then
                    // renders the custom atlas on the worn Elytra, whose outline is alpha-driven.
                    return Step.Result.pass("cape-cancel inputs satisfied and active HD atlas has "
                            + "a tapered Elytra cutout: elytra in CHEST + activeCape + cloak=" + cloak);
                }));

        // 8b. the cape editor previews the cape, never the worn elytra -----------------------------
        // The preview renders the real player entity, so every vanilla layer runs against live
        // equipment: the wings layer read the worn CHEST slot and drew the elytra over the cape
        // being composed, at the same depth, hiding the thing the screen exists to edit.
        //
        // Three frames, one screen instance, one camera, one cape. Two are taken with the CHEST slot
        // empty - the first to measure how much of the previewed cape reaches the screen and where,
        // the second to measure how much that count drifts on its own between grabs. The third is
        // taken after equipping an elytra and nothing else. A screenshot threshold could not tell
        // "the elytra vanished" from "the cape changed", so this counts the previewed cape's own
        // pixels inside a region measured from the first frame, and requires the elytra frame to
        // keep them.
        final String capeProbeEmpty = prefix + "full_08b_probe_no_elytra" + suffix;
        final String capeProbeStill = prefix + "full_08c_probe_still" + suffix;
        final String capeProbeElytra = prefix + "full_08d_probe_elytra" + suffix;
        final AtomicInteger capePhase = new AtomicInteger(0);
        final AtomicInteger capeHold = new AtomicInteger(0);
        final AtomicReference<int[]> capeRegion = new AtomicReference<>();
        final AtomicReference<int[]> capeCounts = new AtomicReference<>();
        final AtomicReference<String> capeFailure = new AtomicReference<>();
        final AtomicInteger capeOriginalGuiScale = new AtomicInteger(0);
        steps.add(Step.of("cape_editor_ignores_elytra")
                .action(() -> {
                    enterWorldView(mc);
                    setChestSlot(mc, ItemStack.EMPTY);
                    capeOriginalGuiScale.set(VanillaShim.guiScale(mc));
                    openCapeProbeEditor(mc);
                })
                .minTicks(CAPE_PROBE_HOLD_TICKS)
                .ready(() -> capeProbeReady(mc, capePhase, capeHold, capeRegion, capeCounts,
                        capeFailure, capeProbeEmpty, capeProbeStill, capeProbeElytra))
                .timeoutTicks(900)
                .screenshot(prefix + "full_08e_cape_editor_ignores_elytra" + suffix)
                .assertion(() -> {
                    try {
                        return capeProbeVerdict(mc, capeCounts, capeRegion, capeFailure);
                    } finally {
                        // The editor only builds its 3D preview when the GUI is tall enough, so the
                        // probe shrank the scale to bring it out; put back what the profile chose so
                        // every later step is framed the way the rest of the run is.
                        int original = capeOriginalGuiScale.get();
                        if (original > 0 && VanillaShim.guiScale(mc) != original) {
                            VanillaShim.setGuiScale(mc, original);
                        }
                    }
                }));

        // 10a. settings: round-trip a flag through SettingsScreen.onClose -> ClientConfig ----------
        steps.add(Step.of("settings_screen")
                .action(() -> {
                    ClientConfig c = ClientConfig.getInstance();
                    c.showSkinPreviewOverlay = false; // known starting value; the screen will flip it
                    c.save();
                    VanillaShim.setScreen(mc, new SettingsScreen(null));
                })
                .minTicks(25)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof SettingsScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_10a_settings" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen s))
                        return Step.Result.fail("settings not open: " + screenName(mc));
                    Object cbObj = screenField(s, "showOverlayCheckbox");
                    if (!(cbObj instanceof Checkbox cb))
                        return Step.Result.fail("showOverlayCheckbox not found/built");
                    if (!cb.selected()) VanillaShim.press(cb); // flip false -> true via the real widget
                    s.onClose();                       // persists checkbox states into ClientConfig + save()
                    boolean now = ClientConfig.getInstance().showSkinPreviewOverlay;
                    return now
                            ? Step.Result.pass("SettingsScreen.onClose wrote showSkinPreviewOverlay=true to ClientConfig")
                            : Step.Result.fail("ClientConfig.showSkinPreviewOverlay still false after onClose");
                }));

        // 10b. rename dialog (harness owns the result Consumer) -----------------------------------
        steps.add(Step.of("rename_dialog")
                .action(() -> {
                    renameResult = null;
                    Consumer<String> cb = v2 -> renameResult = v2;
                    VanillaShim.setScreen(mc, new RenameScreen(null,
                            Component.literal("Rename"), Component.literal("New name?"),
                            "qs_e2e_old", cb));
                })
                .minTicks(15)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof RenameScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_10b_rename" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof RenameScreen s))
                        return Step.Result.fail("rename not open: " + screenName(mc));
                    Object ebObj = screenField(s, "nameEditBox");
                    Object btnObj = screenField(s, "confirmButton");
                    if (!(ebObj instanceof EditBox eb)) return Step.Result.fail("nameEditBox not built");
                    if (!(btnObj instanceof Button confirm)) return Step.Result.fail("confirmButton not built");
                    eb.setValue("qs_e2e_renamed");
                    confirm.active = true;   // bypass the blank-name disable just in case
                    VanillaShim.press(confirm); // -> callback.accept(getValue()) + onClose()
                    return "qs_e2e_renamed".equals(renameResult)
                            ? Step.Result.pass("Rename callback received 'qs_e2e_renamed'")
                            : Step.Result.fail("Rename callback got: " + renameResult);
                }));

        // 10c. delete-confirm dialog --------------------------------------------------------------
        steps.add(Step.of("delete_dialog")
                .action(() -> {
                    deleteResult = null;
                    Consumer<Boolean> cb = b -> deleteResult = b;
                    VanillaShim.setScreen(mc, new DeletionConfirmScreen(null,
                            Component.literal("Delete?"), Component.literal("Delete this skin?"),
                            cb, false));
                })
                .minTicks(15)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof DeletionConfirmScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_10c_delete" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof DeletionConfirmScreen))
                        return Step.Result.fail("delete dialog not open: " + screenName(mc));
                    // Buttons are local vars in init() (no fields): press the confirm/Delete button,
                    // which is added last (after Cancel) -> accept(true).
                    if (!pressLastButton(mc)) return Step.Result.fail("no button to press");
                    return Boolean.TRUE.equals(deleteResult)
                            ? Step.Result.pass("Delete callback received true")
                            : Step.Result.fail("Delete callback got: " + deleteResult);
                }));

        // 11. HUD preview overlay -----------------------------------------------------------------
        steps.add(Step.of("hud_preview_overlay")
                .action(() -> {
                    enterWorldView(mc); // closes any leftover dialog; 3rd-person world frame
                    ClientConfig c = ClientConfig.getInstance();
                    c.showSkinPreviewOverlay = true;
                    c.enablePlayerPreviewCustomization = false;
                    // Small thumbnail pushed to the lower-left so it reads as a distinct HUD preview
                    // beside the centered 3rd-person world player rather than overlapping it.
                    c.sizeModelPreviewPercentageHudOverlay = 15;
                    c.positionOffsetXHudOverlay = -150;
                    c.positionOffsetYHudOverlay = -10;
                    c.hudOverlayRotation = 20.0f;
                    if (skinHash != null) setActiveSkinHash(c, skinHash); // show the custom skin in the overlay
                    c.save();
                    overlayForceResolve(); // null lastCheckedSkinHash so render() re-resolves the skin
                })
                .minTicks(30) // several RENDER_HUD frames so the overlay draws + caches its state
                .ready(this::overlayRendered)
                .timeoutTicks(200)
                .screenshot(prefix + "full_11_hud_overlay" + suffix)
                .assertion(() -> {
                    if (!ClientConfig.getInstance().showSkinPreviewOverlay)
                        return Step.Result.fail("showSkinPreviewOverlay not set");
                    if (!overlayRendered())
                        return Step.Result.fail("SkinPreviewOverlay.render did not run (cachedScale==0)");
                    Object loc = overlayCachedSkinLocation();
                    return Step.Result.pass("HUD overlay rendered; cachedSkinLocation=" + loc);
                }));

        // 12. Title screen: the preview stays in front of the vanilla splash ----------------------
        //
        // The z-order regression this guards is invisible to a screenshot threshold: "the splash
        // moved from behind the model to in front of it" and "the splash string changed" are the
        // same handful of differing pixels. So this step reads pixels, and reads them twice.
        //
        // Frame 1 is the title screen with the preview left where the mod puts it, away from a
        // harness-pinned yellow splash. Its pixels are located in that frame - no vanilla layout
        // constant is assumed - and become the control: they prove a splash is being drawn, and
        // where. Frame 2 is the same screen with the preview moved onto those exact pixels. The
        // model must have taken the region over completely.
        //
        // The pair is what makes it a z-order assertion rather than a "something is drawn" one. A
        // build that never draws the model would fail frame 2's control (the splash would still be
        // there); a build that draws it behind the splash fails frame 2; and a build with no splash
        // at all fails frame 1 instead of passing vacuously.
        final String probeBefore = prefix + "full_12a_title_probe_splash" + suffix;
        final String probeAfter = prefix + "full_12b_title_probe_covered" + suffix;
        final AtomicInteger titlePhase = new AtomicInteger(0);
        final AtomicInteger titleHold = new AtomicInteger(0);
        final AtomicReference<int[]> splashRegion = new AtomicReference<>();
        final AtomicReference<int[]> splashPixels = new AtomicReference<>();
        final AtomicReference<String> titleFailure = new AtomicReference<>();
        steps.add(Step.of("title_screen_splash_order")
                .action(() -> {
                    ClientConfig c = ClientConfig.getInstance();
                    // Keep the frame to just the panorama, the vanilla chrome and the preview: the
                    // HUD overlay would put a second model on screen and the debug border would put
                    // mod text over the one being measured.
                    c.showSkinPreviewOverlay = false;
                    c.enablePlayerPreviewCustomization = false;
                    c.positionOffsetXTitleScreen = 0;
                    c.positionOffsetYTitleScreen = 0;
                    c.sizeModelPreviewPercentageTitleScreen = TITLE_PROBE_SIZE_PERCENT;
                    c.save();
                    TitleScreen probeScreen = new TitleScreen();
                    String splashFailure = VanillaShim.installDeterministicSplash(
                            probeScreen, TITLE_PROBE_SPLASH
                    );
                    if (splashFailure != null) titleFailure.set(splashFailure);
                    VanillaShim.setScreen(mc, probeScreen);
                })
                .minTicks(TITLE_HOLD_TICKS)
                .ready(() -> titleProbeReady(mc, titlePhase, titleHold, splashRegion, splashPixels,
                        titleFailure, probeBefore, probeAfter))
                .timeoutTicks(600)
                .screenshot(prefix + "full_12_title_splash_order" + suffix)
                .assertion(() -> {
                    String failure = titleFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    if (!(VanillaShim.currentScreen(mc) instanceof TitleScreen))
                        return Step.Result.fail("title screen not open: " + screenName(mc));
                    int[] counts = splashPixels.get();
                    int[] region = splashRegion.get();
                    if (counts == null || region == null)
                        return Step.Result.fail("title screen probe never completed");
                    if (counts[0] < MIN_SPLASH_PIXELS)
                        return Step.Result.fail("control frame found only " + counts[0]
                                + " splash pixels in " + java.util.Arrays.toString(region)
                                + "; expected at least " + MIN_SPLASH_PIXELS
                                + " (no splash means the covered frame proves nothing)");
                    if (counts[1] != 0)
                        return Step.Result.fail("splash still draws over the player model: "
                                + counts[1] + " of " + counts[0] + " splash pixels survived in "
                                + java.util.Arrays.toString(region)
                                + " with the model on top of them");
                    return Step.Result.pass("model covers the splash: " + counts[0]
                            + " splash pixels in the control frame, 0 left once the model is over "
                            + "them (region " + java.util.Arrays.toString(region) + ")");
                }));

        return steps;
    }

    // ===== skin-menu layout settle =============================================================

    /**
     * True once the skin menu is open at the forced GUI scale with a tick-over-tick stable layout.
     *
     * <p>The scale check proves the resizeDisplay()-&gt;second-init() re-entrancy completed; the
     * stamp must then repeat on the next poll, the same consecutive-tick stability the propagation
     * observers hold before their captures. The stamp is built only from signals this source set
     * already uses on every version (scaled window dimensions, widget count): the list widget's own
     * geometry accessors are era-preprocessed and would not compile against every lane. Losing the
     * precondition resets the stamp, so a flickering layout is never reported settled.
     */
    private boolean skinMenuLayoutSettled(Minecraft mc) {
        Screen sc = VanillaShim.currentScreen(mc);
        if (!(sc instanceof PlayerSkinMenuScreen)
                || VanillaShim.guiScale(mc) != GuiScaleManager.getOptimalMenuScale()) {
            skinMenuLayoutStamp = Long.MIN_VALUE; // precondition lost; demand a fresh hold
            return false;
        }
        long stamp = ((long) mc.getWindow().getGuiScaledWidth() * 31L
                + mc.getWindow().getGuiScaledHeight()) * 31L + sc.children().size();
        if (stamp != skinMenuLayoutStamp) {
            skinMenuLayoutStamp = stamp; // changed (or first sight); require the same layout again
            return false;
        }
        return true;
    }

    // ===== title-screen splash z-order probe ===================================================

    /** Preview size (config percentage) used for the probe, so the model reliably covers the splash. */
    private static final int TITLE_PROBE_SIZE_PERCENT = 70;

    /** Rendered ticks the title screen must hold before each probe grab, so the fade-in has finished. */
    private static final int TITLE_HOLD_TICKS = 25;

    /**
     * How far below the measured splash the model's feet are placed, as a multiple of its scale.
     *
     * <p>The preview grows upward from its centre point, which is the feet, so the target has to sit
     * above it. Just under one scale unit puts the splash around the model's chest.
     */
    private static final float TITLE_PROBE_BODY_DROP = 0.9f;

    /** Splash pixels the control frame must find, below which the covered frame proves nothing. */
    private static final int MIN_SPLASH_PIXELS = 40;

    /** Plain text deliberately chosen to avoid vanilla's rare formatted or seasonal renderers. */
    private static final String TITLE_PROBE_SPLASH = "Quick Skin E2E splash probe";

    /**
     * The harness-pinned vanilla splash's colour, and nothing else on the title screen.
     *
     * <p>{@code SplashRenderer} draws at {@code 0xFFFF00} with a drop shadow at a quarter of that, so
     * only the glyph cores match; the panorama, the logo and the button chrome are all far away from
     * saturated yellow with no blue at all.
     */
    private static boolean isSplashYellow(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r >= 200 && g >= 200 && b <= 90;
    }

    /**
     * Drives the two-frame probe, one phase per poll, and reports done to the step's {@code ready}.
     *
     * <p>Phases: hold the untouched title screen and grab the control frame; locate the splash in it
     * and shrink to the middle of its bounding box; move the preview onto that point; let the widget
     * republish its layout; hold again and grab the covered frame; count splash pixels in both. Any
     * step that cannot proceed records a message and reports ready so the assertion fails loudly
     * rather than the step timing out with no explanation.
     */
    private boolean titleProbeReady(Minecraft mc, AtomicInteger phase, AtomicInteger hold,
                                    AtomicReference<int[]> region, AtomicReference<int[]> counts,
                                    AtomicReference<String> failure,
                                    String probeBefore, String probeAfter) {
        if (failure.get() != null) return true;
        if (!(VanillaShim.currentScreen(mc) instanceof TitleScreen)) {
            return false;
        }
        switch (phase.get()) {
            case 0: // hold the untouched screen, then grab the control frame
                if (hold.incrementAndGet() < TITLE_HOLD_TICKS) return false;
                if (!VanillaShim.screenshot(mc, probeBefore)) {
                    failure.set("could not grab the control frame " + probeBefore);
                    return true;
                }
                phase.set(1);
                return false;
            case 1: { // locate the splash in the control frame
                BufferedImage shot = readShot(probeBefore);
                if (shot == null) return false; // async write still in flight
                int[] gui = locateSplash(mc, shot);
                if (gui == null) {
                    failure.set("no splash pixels in the control frame " + probeBefore
                            + " (" + shot.getWidth() + "x" + shot.getHeight() + ")");
                    return true;
                }
                region.set(gui);
                if (!placePreviewOver(mc, gui)) {
                    failure.set("the title screen has no Quick Skin preview widget to move");
                    return true;
                }
                hold.set(0);
                phase.set(2);
                return false;
            }
            case 2: // hold the moved model, then grab the covered frame
                if (hold.incrementAndGet() < TITLE_HOLD_TICKS) return false;
                if (!VanillaShim.screenshot(mc, probeAfter)) {
                    failure.set("could not grab the covered frame " + probeAfter);
                    return true;
                }
                phase.set(3);
                return false;
            default: { // count splash pixels in both frames over the same region
                BufferedImage before = readShot(probeBefore);
                BufferedImage after = readShot(probeAfter);
                if (before == null || after == null) return false;
                if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
                    failure.set("probe frames differ in size: " + before.getWidth() + "x"
                            + before.getHeight() + " vs " + after.getWidth() + "x" + after.getHeight());
                    return true;
                }
                int[] gui = region.get();
                counts.set(new int[] {
                        countSplashPixels(mc, before, gui),
                        countSplashPixels(mc, after, gui)});
                return true;
            }
        }
    }

    /** The PNG a dispatched grab wrote, or {@code null} while the async write is still in flight. */
    private static BufferedImage readShot(String name) {
        File file = new File(new File(System.getProperty("user.dir"), "screenshots"), name);
        if (!file.isFile() || file.length() < 1024L) return null;
        try {
            BufferedImage image = ImageIO.read(file);
            return image != null && image.getWidth() >= 320 ? image : null;
        } catch (Throwable t) {
            return null; // a half-written file; the next poll will find it complete
        }
    }

    /** Blank scanlines/columns tolerated inside one cluster; wider than any gap between glyphs. */
    private static final int CLUSTER_GAP = 10;

    /**
     * The middle of the splash, in GUI coordinates, as {@code {x0, y0, x1, y1}}.
     *
     * <p>Measured rather than assumed: the splash's anchor, rotation and pulsing scale are vanilla
     * internals that have already been renamed once across the supported eras. The harness pins
     * the string and colour, but deliberately keeps measuring vanilla's placement and pulse.
     *
     * <p>The splash is not the only saturated yellow on a title screen, though. The panorama can
     * contain bees and thousands of yellow flower pixels - 1.21.10 exposed exactly that false
     * positive. Vanilla's splash is title chrome in the upper-right quadrant on every supported
     * lane, so only that deliberately broad area is eligible. Its pixels are then clustered into
     * bands, by row and then by column, and the densest eligible cluster wins.
     */
    private int[] locateSplash(Minecraft mc, BufferedImage shot) {
        int height = shot.getHeight();
        int width = shot.getWidth();
        int scanTop = 0;
        int scanBottom = Math.max(1, height / 2);
        int scanLeft = width / 2;
        int scanRight = width;
        int[] perRow = new int[height];
        int total = 0;
        for (int y = scanTop; y < scanBottom; y++) {
            for (int x = scanLeft; x < scanRight; x++) {
                if (isSplashYellow(shot.getRGB(x, y))) {
                    perRow[y]++;
                    total++;
                }
            }
        }
        if (total < MIN_SPLASH_PIXELS) return null;
        int[] rows = densestCluster(perRow);
        if (rows == null) return null;

        int[] perColumn = new int[width];
        for (int y = rows[0]; y <= rows[1]; y++) {
            for (int x = scanLeft; x < scanRight; x++) {
                if (isSplashYellow(shot.getRGB(x, y))) perColumn[x]++;
            }
        }
        int[] columns = densestCluster(perColumn);
        if (columns == null) return null;

        double scaleX = (double) width / Math.max(1, mc.getWindow().getGuiScaledWidth());
        double scaleY = (double) height / Math.max(1, mc.getWindow().getGuiScaledHeight());
        // Keep the middle half of the box: the tails of a long splash reach past what a single
        // model silhouette can cover, and covering the middle is what the ordering rule decides.
        int qx = (columns[1] - columns[0]) / 4;
        int qy = (rows[1] - rows[0]) / 4;
        return new int[] {
                (int) ((columns[0] + qx) / scaleX), (int) ((rows[0] + qy) / scaleY),
                (int) ((columns[1] - qx) / scaleX), (int) ((rows[1] - qy) / scaleY)};
    }

    /**
     * First and last index of the run of populated buckets holding the most pixels.
     *
     * <p>Buckets are joined into one run while the blank stretch between them stays under
     * {@link #CLUSTER_GAP}, so glyph spacing does not split a splash while a bee several dozen
     * pixels away stays its own, much smaller, run. {@code null} when the winning run is too small
     * to be a splash.
     */
    private static int[] densestCluster(int[] buckets) {
        int bestStart = -1;
        int bestEnd = -1;
        int bestWeight = 0;
        int start = -1;
        int end = -1;
        int weight = 0;
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] > 0) {
                if (start < 0 || i - end > CLUSTER_GAP) {
                    if (weight > bestWeight) {
                        bestWeight = weight;
                        bestStart = start;
                        bestEnd = end;
                    }
                    start = i;
                    weight = 0;
                }
                end = i;
                weight += buckets[i];
            }
        }
        if (weight > bestWeight) {
            bestWeight = weight;
            bestStart = start;
            bestEnd = end;
        }
        return bestWeight >= MIN_SPLASH_PIXELS ? new int[] {bestStart, bestEnd} : null;
    }

    /** Splash-coloured pixels inside a GUI-coordinate region of a captured frame. */
    private int countSplashPixels(Minecraft mc, BufferedImage shot, int[] guiRegion) {
        double scaleX = (double) shot.getWidth() / Math.max(1, mc.getWindow().getGuiScaledWidth());
        double scaleY = (double) shot.getHeight() / Math.max(1, mc.getWindow().getGuiScaledHeight());
        int px0 = Math.max(0, (int) Math.floor(guiRegion[0] * scaleX));
        int py0 = Math.max(0, (int) Math.floor(guiRegion[1] * scaleY));
        int px1 = Math.min(shot.getWidth() - 1, (int) Math.ceil(guiRegion[2] * scaleX));
        int py1 = Math.min(shot.getHeight() - 1, (int) Math.ceil(guiRegion[3] * scaleY));
        int count = 0;
        for (int y = py0; y <= py1; y++) {
            for (int x = px0; x <= px1; x++) {
                if (isSplashYellow(shot.getRGB(x, y))) count++;
            }
        }
        return count;
    }

    /**
     * Move the title screen's preview so its body covers a GUI-coordinate region.
     *
     * <p>Drives the same config offsets the user's own reposition writes, and measures the current
     * placement from the widget's cached layout - both mod-owned names, so both survive remapping -
     * rather than reimplementing the widget's anchor rules here.
     */
    private boolean placePreviewOver(Minecraft mc, int[] guiRegion) {
        PlayerWidget widget = titlePreviewWidget(mc);
        if (widget == null) return false;
        Integer centerX = (Integer) screenField(widget, "cachedModelCenterX");
        Integer centerY = (Integer) screenField(widget, "cachedModelCenterY");
        Float modelScale = (Float) screenField(widget, "cachedScale");
        if (centerX == null || centerY == null || modelScale == null) return false;
        int targetX = (guiRegion[0] + guiRegion[2]) / 2;
        int targetY = (guiRegion[1] + guiRegion[3]) / 2;
        int feetY = targetY + (int) (modelScale * TITLE_PROBE_BODY_DROP);
        ClientConfig c = ClientConfig.getInstance();
        c.positionOffsetXTitleScreen += targetX - centerX;
        c.positionOffsetYTitleScreen += feetY - centerY;
        c.save();
        return true;
    }

    /** The Quick Skin preview the mod injected into the open title screen, or {@code null}. */
    private PlayerWidget titlePreviewWidget(Minecraft mc) {
        Screen screen = VanillaShim.currentScreen(mc);
        if (screen == null) return null;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof PlayerWidget widget) return widget;
        }
        return null;
    }

    // ===== cape-editor elytra probe ============================================================

    /** Rendered ticks the cape editor must hold before each probe grab, so the frame has settled. */
    private static final int CAPE_PROBE_HOLD_TICKS = 25;

    /** Previewed-cape pixels the empty-chest frame must find, below which the probe proves nothing. */
    private static final int MIN_CAPE_PROBE_PIXELS = 200;

    /**
     * Share of the empty-chest frame's cape pixels a later frame has to keep.
     *
     * <p>Loose enough to ride out a pixel or two of drift between grabs - which the still frame
     * measures rather than assumes - and nowhere near loose enough to pass a drawn elytra: folded
     * wings sit flat on the back over the whole cape, so the count collapses when they render.
     */
    private static final double CAPE_PROBE_RETENTION = 0.85;

    /**
     * The probe cape's colour, and nothing else the cape editor can put on the model.
     *
     * <p>{@code TestAssets.makeProbeCapeImage()} paints the whole atlas one saturated magenta. The
     * skin is a vanilla default, the vanilla elytra is grey, the screen chrome is near-monochrome
     * and the world behind is blurred and darkened, so no other source reaches high red and high
     * blue with almost no green. The band is wide enough to survive the model's directional GUI
     * lighting, which dims the cape without shifting its hue.
     */
    private static boolean isProbeCapeMagenta(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r >= 120 && b >= 120 && g <= 90;
    }

    /** Open the cape editor on the probe cape; the composed result is not what this step reads. */
    private void openCapeProbeEditor(Minecraft mc) {
        VanillaShim.setScreen(mc, new CapeAdjustScreen(
                null, TestAssets.makeProbeCapeImage(), composed -> { }));
    }

    /**
     * Bring the editor's 3D preview into existence, shrinking the GUI scale until it fits.
     *
     * <p>The editor only builds the preview when what is left under its 2D thumbnails is at least
     * 120 GUI units tall, so whether it exists at all depends on the window size and the GUI scale.
     * The harness profile runs at scale 3, which on both this machine's framebuffer and CI's leaves
     * too little room; scale 1 leaves plenty on either. Rather than hard-code that, step the scale
     * down until the widget appears - the same thing a player with a small window does by hand.
     *
     * @return {@code null} once the preview exists, or a message saying it never did.
     */
    private String revealCapeEditorPreview(Minecraft mc) {
        for (int attempt = 0; attempt < 4; attempt++) {
            if (capeEditorPreviewWidget(mc) != null) return null;
            int scale = VanillaShim.guiScale(mc);
            if (scale <= 1) {
                return "the cape editor built no 3D preview even at GUI scale 1 ("
                        + mc.getWindow().getGuiScaledWidth() + "x"
                        + mc.getWindow().getGuiScaledHeight() + " GUI units); nothing to probe";
            }
            if (!VanillaShim.setGuiScale(mc, scale - 1)) {
                return "could not change the GUI scale to bring out the cape editor's 3D preview";
            }
            openCapeProbeEditor(mc); // a screen reads the scaled dimensions once, when it opens
        }
        return capeEditorPreviewWidget(mc) != null ? null
                : "the cape editor built no 3D preview at any GUI scale";
    }

    /**
     * Drives the three-frame probe, one phase per poll, and reports done to the step's {@code ready}.
     *
     * <p>Phases: bring the preview out; hold the editor with an empty chest and grab the control
     * frame; find the previewed cape in it and shrink to the middle of its bounding box; hold again
     * with nothing changed and grab the still frame, which is what calibrates the tolerance; equip an
     * elytra; hold and grab the third frame; count the cape's pixels in all three over the same
     * region. Any phase that cannot proceed records a message and reports ready, so the assertion
     * fails with a reason instead of the step timing out silently.
     */
    private boolean capeProbeReady(Minecraft mc, AtomicInteger phase, AtomicInteger hold,
                                   AtomicReference<int[]> region, AtomicReference<int[]> counts,
                                   AtomicReference<String> failure,
                                   String emptyShot, String stillShot, String elytraShot) {
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen)) {
            return false;
        }
        switch (phase.get()) {
            case 0: { // make sure there is a 3D preview on this screen to probe at all
                String problem = revealCapeEditorPreview(mc);
                if (problem != null) {
                    failure.set(problem);
                    return true;
                }
                hold.set(0);
                phase.set(1);
                return false;
            }
            case 1: // hold the editor with an empty chest, then grab the control frame
                if (hold.incrementAndGet() < CAPE_PROBE_HOLD_TICKS) return false;
                if (!VanillaShim.screenshot(mc, emptyShot)) {
                    failure.set("could not grab the empty-chest frame " + emptyShot);
                    return true;
                }
                phase.set(2);
                return false;
            case 2: { // find the previewed cape on the model in the control frame
                BufferedImage shot = readShot(emptyShot);
                if (shot == null) return false; // async write still in flight
                int[] gui = locateProbeCape(mc, shot);
                if (gui == null) {
                    failure.set("no previewed-cape pixels on the model in " + emptyShot
                            + " (" + shot.getWidth() + "x" + shot.getHeight()
                            + "); the editor is not showing the cape it was handed");
                    return true;
                }
                region.set(gui);
                hold.set(0);
                phase.set(3);
                return false;
            }
            case 3: // hold with nothing changed, then grab the still frame
                if (hold.incrementAndGet() < CAPE_PROBE_HOLD_TICKS) return false;
                if (!VanillaShim.screenshot(mc, stillShot)) {
                    failure.set("could not grab the still frame " + stillShot);
                    return true;
                }
                phase.set(4);
                return false;
            case 4: // the one thing that changes between the still frame and the last one
                equipElytra(mc);
                hold.set(0);
                phase.set(5);
                return false;
            case 5: // hold the elytra frame, re-asserting the slot, then grab it
                equipElytra(mc); // creative inventory sync can clear it back out
                if (hold.incrementAndGet() < CAPE_PROBE_HOLD_TICKS) return false;
                if (mc.player == null
                        || !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                    failure.set("the elytra would not stay in the CHEST slot");
                    return true;
                }
                if (!VanillaShim.screenshot(mc, elytraShot)) {
                    failure.set("could not grab the elytra frame " + elytraShot);
                    return true;
                }
                phase.set(6);
                return false;
            default: { // count the cape's pixels in all three frames over the same region
                BufferedImage empty = readShot(emptyShot);
                BufferedImage still = readShot(stillShot);
                BufferedImage elytra = readShot(elytraShot);
                if (empty == null || still == null || elytra == null) return false;
                if (empty.getWidth() != still.getWidth() || empty.getHeight() != still.getHeight()
                        || empty.getWidth() != elytra.getWidth()
                        || empty.getHeight() != elytra.getHeight()) {
                    failure.set("probe frames differ in size: " + empty.getWidth() + "x"
                            + empty.getHeight() + ", " + still.getWidth() + "x" + still.getHeight()
                            + ", " + elytra.getWidth() + "x" + elytra.getHeight());
                    return true;
                }
                int[] gui = region.get();
                counts.set(new int[] {
                        countProbeCapePixels(mc, empty, gui),
                        countProbeCapePixels(mc, still, gui),
                        countProbeCapePixels(mc, elytra, gui)});
                return true;
            }
        }
    }

    /** What the three frames say: the previewed cape has to survive the worn elytra. */
    private Step.Result capeProbeVerdict(Minecraft mc, AtomicReference<int[]> capeCounts,
                                         AtomicReference<int[]> capeRegion,
                                         AtomicReference<String> capeFailure) {
        String failure = capeFailure.get();
        if (failure != null) return Step.Result.fail(failure);
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen))
            return Step.Result.fail("cape adjust not open: " + screenName(mc));
        int[] counts = capeCounts.get();
        int[] region = capeRegion.get();
        if (counts == null || region == null)
            return Step.Result.fail("cape editor probe never completed");
        String where = java.util.Arrays.toString(region);
        if (counts[0] < MIN_CAPE_PROBE_PIXELS)
            return Step.Result.fail("only " + counts[0] + " previewed-cape pixels on the model in "
                    + where + " with an empty chest; expected at least " + MIN_CAPE_PROBE_PIXELS
                    + " (with no cape visible the elytra frame would prove nothing)");
        int floor = (int) (counts[0] * CAPE_PROBE_RETENTION);
        if (counts[1] < floor)
            return Step.Result.fail("the preview is not stable between grabs: " + counts[1] + " of "
                    + counts[0] + " previewed-cape pixels survived in " + where + " with nothing"
                    + " changed, so the elytra frame cannot be judged");
        if (mc.player == null || !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA))
            return Step.Result.fail("the probe never got an elytra into the CHEST slot");
        if (counts[2] < floor)
            return Step.Result.fail("the worn elytra is drawn over the previewed cape: " + counts[2]
                    + " of " + counts[0] + " previewed-cape pixels survived in " + where + " once an"
                    + " elytra was equipped (the still frame kept " + counts[1] + "); the editor must"
                    + " show the cape being edited, not worn equipment");
        return Step.Result.pass("the cape editor ignores the worn elytra: " + counts[0]
                + " previewed-cape pixels with an empty chest, " + counts[1] + " unchanged, "
                + counts[2] + " with an elytra equipped (region " + where + ", floor " + floor + ")");
    }

    /**
     * The middle of the previewed cape on the model, in GUI coordinates, as {@code {x0,y0,x1,y1}}.
     *
     * <p>Measured, not assumed, for the same reason the title-screen probe measures its splash: the
     * model's placement inside the widget is the mod's own layout arithmetic and its proportions are
     * vanilla's, and neither is worth restating here. The search is bounded to the model's own box,
     * derived from the widget's cached layout - mod-owned names, so they survive remapping - because
     * the editor also paints the very same source image in its cropping pane, and that must not be
     * mistaken for the cape on the model. Inside that box the cape pixels are clustered by row and
     * then by column exactly like the splash probe, and the middle half of the winning box is kept
     * so the count is taken well inside the cape rather than along its shaded edges.
     */
    private int[] locateProbeCape(Minecraft mc, BufferedImage shot) {
        PlayerWidget widget = capeEditorPreviewWidget(mc);
        if (widget == null) return null;
        Integer centerX = (Integer) screenField(widget, "cachedModelCenterX");
        Integer centerY = (Integer) screenField(widget, "cachedModelCenterY");
        Float modelScale = (Float) screenField(widget, "cachedScale");
        if (centerX == null || centerY == null || modelScale == null || modelScale <= 0f) return null;

        int width = shot.getWidth();
        int height = shot.getHeight();
        double scaleX = (double) width / Math.max(1, mc.getWindow().getGuiScaledWidth());
        double scaleY = (double) height / Math.max(1, mc.getWindow().getGuiScaledHeight());
        // The reference point is the model's feet and it grows upward; a player is a little under
        // two of these units tall and well under one wide, so this box holds the body and no more.
        int x0 = clamp((int) ((centerX - modelScale * 0.7f) * scaleX), 0, width - 1);
        int x1 = clamp((int) ((centerX + modelScale * 0.7f) * scaleX), 0, width - 1);
        int y0 = clamp((int) ((centerY - modelScale * 1.9f) * scaleY), 0, height - 1);
        int y1 = clamp((int) ((centerY - modelScale * 0.4f) * scaleY), 0, height - 1);
        if (x1 <= x0 || y1 <= y0) return null;

        int[] perRow = new int[height];
        int total = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (isProbeCapeMagenta(shot.getRGB(x, y))) {
                    perRow[y]++;
                    total++;
                }
            }
        }
        if (total < MIN_CAPE_PROBE_PIXELS) return null;
        int[] rows = densestCluster(perRow);
        if (rows == null) return null;

        int[] perColumn = new int[width];
        for (int y = rows[0]; y <= rows[1]; y++) {
            for (int x = x0; x <= x1; x++) {
                if (isProbeCapeMagenta(shot.getRGB(x, y))) perColumn[x]++;
            }
        }
        int[] columns = densestCluster(perColumn);
        if (columns == null) return null;

        int qx = (columns[1] - columns[0]) / 4;
        int qy = (rows[1] - rows[0]) / 4;
        return new int[] {
                (int) ((columns[0] + qx) / scaleX), (int) ((rows[0] + qy) / scaleY),
                (int) ((columns[1] - qx) / scaleX), (int) ((rows[1] - qy) / scaleY)};
    }

    /** Previewed-cape pixels inside a GUI-coordinate region of a captured frame. */
    private int countProbeCapePixels(Minecraft mc, BufferedImage shot, int[] guiRegion) {
        double scaleX = (double) shot.getWidth() / Math.max(1, mc.getWindow().getGuiScaledWidth());
        double scaleY = (double) shot.getHeight() / Math.max(1, mc.getWindow().getGuiScaledHeight());
        int px0 = Math.max(0, (int) Math.floor(guiRegion[0] * scaleX));
        int py0 = Math.max(0, (int) Math.floor(guiRegion[1] * scaleY));
        int px1 = Math.min(shot.getWidth() - 1, (int) Math.ceil(guiRegion[2] * scaleX));
        int py1 = Math.min(shot.getHeight() - 1, (int) Math.ceil(guiRegion[3] * scaleY));
        int count = 0;
        for (int y = py0; y <= py1; y++) {
            for (int x = px0; x <= px1; x++) {
                if (isProbeCapeMagenta(shot.getRGB(x, y))) count++;
            }
        }
        return count;
    }

    /** The Quick Skin preview inside the open cape editor, or {@code null}. */
    private PlayerWidget capeEditorPreviewWidget(Minecraft mc) {
        Screen screen = VanillaShim.currentScreen(mc);
        if (!(screen instanceof CapeAdjustScreen)) return null;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof PlayerWidget widget) return widget;
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    // ===== world / view helpers ===============================================================

    /** Reset the client singletons to a deterministic clean state between feature runs. */
    /** The cape menu's private preview widget, or {@code null} if that screen is not open. */
    private Object capeMenuWidget(Minecraft mc) {
        if (!(VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen screen)) return null;
        try {
            Field f = PlayerCapeMenuScreen.class.getDeclaredField("playerWidget");
            f.setAccessible(true);
            return f.get(screen);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The cape location the preview widget is currently holding, or {@code null}. */
    private Object previewCapeLocation(Minecraft mc) {
        Object widget = capeMenuWidget(mc);
        if (widget == null) return null;
        try {
            Field pd = widget.getClass().getDeclaredField("previewData");
            pd.setAccessible(true);
            Object previewData = pd.get(widget);
            if (previewData == null) return null;
            return previewData.getClass().getMethod("getCapeLocation").invoke(previewData);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Pushes a cape into the open cape menu's preview widget without applying it - the same call
     * selecting an entry makes - and reports whether the widget now holds it. Re-asserted every
     * poll so the screen's own selection refresh cannot win the last write before the screenshot.
     * The texture type is era-specific, so the setter is invoked reflectively.
     */
    private boolean pushPreviewCape(Minecraft mc, String capeId) {
        Object widget = capeMenuWidget(mc);
        if (widget == null) return false;
        Object location = CapeService.getInstance().getCapeLocation(null, capeId);
        if (location == null) return false;
        try {
            for (Method m : widget.getClass().getMethods()) {
                if ("setCape".equals(m.getName()) && m.getParameterCount() == 2) {
                    m.invoke(widget, location, capeId);
                    break;
                }
            }
        } catch (Throwable t) {
            E2ELog.error("setCape reflection failed", t);
            return false;
        }
        return location.equals(previewCapeLocation(mc));
    }

    /** Asserts the preview shows the selected cape while the worn cape is untouched. */
    private Step.Result previewCapeAssertion(
            Minecraft mc, PlayerAppearanceService svc, UUID uuid, String hash, String label) {
        if (hash == null) return Step.Result.fail("preview cape " + label + " was not catalogued");
        if (!(VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen))
            return Step.Result.fail("cape menu not open: " + screenName(mc));

        Object expected = CapeService.getInstance().getCapeLocation(null, "local_cape:" + hash);
        if (expected == null)
            return Step.Result.fail("preview cape " + label + " did not resolve to a texture");
        Object shown = previewCapeLocation(mc);
        if (!expected.equals(shown))
            return Step.Result.fail("preview holds " + shown + " expected " + expected);

        // The whole point: selecting must not apply. If this drifts, the screenshot pair would be
        // comparing two *worn* capes and would pass for the wrong reason.
        PlayerAppearance app = svc.getAppearance(uuid);
        String worn = app == null ? null : app.getCapeId();
        if (!"known:test".equals(worn))
            return Step.Result.fail("worn cape became " + worn + "; preview must not apply");
        return Step.Result.pass("preview=" + expected + " worn=" + worn);
    }

    private void resetState() {
        try {
            PlayerAppearanceRepository.getInstance().clear();
            ModelService.getInstance().clearAll();
            AnimatedTextureManager.getInstance().clearAnimations();
            NetworkTextureCache.getInstance().clear();
            CooldownService.getInstance().clearCooldown();
        } catch (Throwable t) {
            E2ELog.warn("resetState: " + t);
        }
    }

    /**
     * The atlas the open {@code CapeAdjustScreen} would apply right now, without applying it.
     * {@code composeCapeImage} is private and is what {@code applyAndClose} hands to {@code onApply},
     * so composing it directly asserts on the exact bytes the apply path would produce.
     */
    private static BufferedImage composeCapeNow(Minecraft mc) {
        return composeOnAdjustScreen(mc, "composeCapeImage");
    }

    /**
     * Every frame of a stacked animation atlas must be filled, not just the first. Composed off an
     * unshown screen instance: {@code composeCapeImage} needs only the transform fields, so this
     * checks the multi-frame path without disturbing the scenario's screen or the atlas geometry
     * (the composed strip must stay {@code capeW x capeH*frames}, which is what carries the
     * animation's frame count downstream).
     *
     * @return null when the atlas is correct, otherwise the failure description
     */
    private static String assertAnimatedAtlasIsFilled() {
        try {
            BufferedImage src = TestAssets.makeTransparentAnimatedCapeImage();
            CapeAdjustScreen screen = new CapeAdjustScreen(null, src, 2, img -> { });
            Method set = CapeAdjustScreen.class.getDeclaredMethod(
                    "setOpaqueFill", boolean.class, int.class);
            set.setAccessible(true);
            set.invoke(screen, true, OPAQUE_FILL_RGB);
            Method compose = CapeAdjustScreen.class.getDeclaredMethod("composeCapeImage");
            compose.setAccessible(true);
            BufferedImage atlas = (BufferedImage) compose.invoke(screen);
            if (atlas.getWidth() != 64 || atlas.getHeight() != 64) {
                return "animated atlas geometry changed: "
                        + atlas.getWidth() + "x" + atlas.getHeight() + " expected 64x64";
            }
            if (!CapeElytraSilhouette.isOpaqueExceptWingCutout(atlas, 2)) {
                return "animated atlas does not preserve its Elytra cutout as the only transparency";
            }
            for (int frame = 0; frame < 2; frame++) {
                int pixel = atlas.getRGB(TestAssets.TRANSPARENT_WINDOW_X + 1,
                        frame * 32 + TestAssets.TRANSPARENT_WINDOW_Y + 1);
                if (pixel != (0xFF000000 | OPAQUE_FILL_RGB)) {
                    return "animated frame " + frame + " missing the fill: "
                            + Integer.toHexString(pixel);
                }
                String landmark = checkOpaqueLandmark(atlas, frame * 32, "animated frame " + frame);
                if (landmark != null) {
                    return landmark;
                }
            }
            return null;
        } catch (Throwable t) {
            return "animated atlas check failed: " + t;
        }
    }

    /**
     * The frame the live preview is built from: {@code updatePreviewTexture} composes exactly this
     * and registers it as the texture both the 2D thumbnails and {@code PlayerWidget.setCape} use.
     * Asserting on it is what proves the fill reached the preview path and not only the apply path.
     */
    private static BufferedImage composePreviewFrameNow(Minecraft mc) {
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s)) {
            return null;
        }
        try {
            Method m = CapeAdjustScreen.class.getDeclaredMethod("composeFrame", int.class);
            m.setAccessible(true);
            return (BufferedImage) m.invoke(s, 0);
        } catch (Throwable t) {
            E2ELog.error("composeFrame reflection failed", t);
            return null;
        }
    }

    /**
     * Move the open adjust screen's zoom slider exactly as a drag would.
     *
     * <p>Idempotent, so a {@code ready()} hold can call it on every rendered tick: setting the
     * position it already holds re-derives the same scale and the same offsets.
     */
    private static boolean setZoomOnAdjustScreen(Minecraft mc, double position) {
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s)) {
            return false;
        }
        try {
            Method m = CapeAdjustScreen.class.getDeclaredMethod("setZoomPosition", double.class);
            m.setAccessible(true);
            m.invoke(s, position);
            return true;
        } catch (Throwable t) {
            E2ELog.error("setZoomPosition reflection failed", t);
            return false;
        }
    }

    /**
     * The screen holds one zoom, and the slider is a view of it. This reads both sides — what the
     * widget's own value is, and what {@code imgScale} says that value should be — and fails if
     * they have drifted apart or from what the harness asked for.
     *
     * @return null when the two agree, otherwise the failure description
     */
    private static String checkZoomSliderAgrees(Minecraft mc, double expectedPosition) {
        double widget = doubleOnAdjustScreen(mc, "zoomSliderValue");
        double fromScale = doubleOnAdjustScreen(mc, "zoomPosition");
        if (Double.isNaN(widget) || Double.isNaN(fromScale)) {
            return "zoom position unavailable (widget=" + widget + " scale=" + fromScale + ")";
        }
        if (widget < 0) {
            return "no zoom slider was built";
        }
        if (Math.abs(widget - fromScale) > 1.0e-9) {
            return "slider and imgScale disagree: slider=" + widget + " scale implies=" + fromScale;
        }
        if (Math.abs(widget - expectedPosition) > 1.0e-9) {
            return "slider sits at " + widget + ", expected " + expectedPosition;
        }
        return null;
    }

    private static double doubleOnAdjustScreen(Minecraft mc, String method) {
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s)) {
            return Double.NaN;
        }
        try {
            Method m = CapeAdjustScreen.class.getDeclaredMethod(method);
            m.setAccessible(true);
            return (Double) m.invoke(s);
        } catch (Throwable t) {
            E2ELog.error(method + " reflection failed", t);
            return Double.NaN;
        }
    }

    private static String stringOnAdjustScreen(Minecraft mc, String method) {
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s)) {
            return null;
        }
        try {
            Method m = CapeAdjustScreen.class.getDeclaredMethod(method);
            m.setAccessible(true);
            return (String) m.invoke(s);
        } catch (Throwable t) {
            E2ELog.error(method + " reflection failed", t);
            return null;
        }
    }

    /**
     * Deliver one wheel notch over the middle of the grid — the point the slider itself zooms
     * about, so the wheel and the slider are asked for the same thing.
     *
     * <p>Invokes {@code zoomAtCursor}, the mod-owned method that holds the whole body of the
     * wheel's zoom, rather than {@code mouseScrolled} itself: {@code mouseScrolled} is a Minecraft
     * override, so its name is remapped in the shipped jar and cannot be looked up by reflection at
     * runtime — and its signature is era-branched, which this unpreprocessed source set could not
     * express anyway.
     */
    private static boolean scrollOnGridCentre(Minecraft mc, boolean zoomIn) {
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s)) {
            return false;
        }
        try {
            double centreX = adjustScreenInt(s, "gridX") + adjustScreenInt(s, "gridW") / 2.0;
            double centreY = adjustScreenInt(s, "gridY") + adjustScreenInt(s, "gridH") / 2.0;
            Method zoom = CapeAdjustScreen.class.getDeclaredMethod(
                    "zoomAtCursor", double.class, double.class, boolean.class);
            zoom.setAccessible(true);
            return Boolean.TRUE.equals(zoom.invoke(s, centreX, centreY, zoomIn));
        } catch (Throwable t) {
            E2ELog.error("zoomAtCursor reflection failed", t);
            return false;
        }
    }

    private static int adjustScreenInt(CapeAdjustScreen screen, String name) throws Exception {
        return adjustScreenField(name).getInt(screen);
    }

    private static double adjustScreenDouble(CapeAdjustScreen screen, String name) throws Exception {
        return adjustScreenField(name).getDouble(screen);
    }

    private static Object adjustScreenObject(CapeAdjustScreen screen, String name) throws Exception {
        return adjustScreenField(name).get(screen);
    }

    private static Field adjustScreenField(String name) throws Exception {
        Field field = CapeAdjustScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * Press one of the adjust screen's target-resolution buttons by its label.
     *
     * <p>Goes through the real widget so the button's own handler runs — that handler is what
     * rescales the transform for the new resolution, which is the thing under test.
     *
     * @return false when no enabled button carries that label
     */
    private static boolean pressResolutionButton(Minecraft mc, String label) {
        return pressActiveButton(mc, label);
    }

    /** Press an enabled button on the current screen by its rendered, localized label. */
    private static boolean pressActiveButton(Minecraft mc, String label) {
        if (VanillaShim.currentScreen(mc) == null) return false;
        for (GuiEventListener child : VanillaShim.currentScreen(mc).children()) {
            if (child instanceof Button button
                    && label.equals(button.getMessage().getString())
                    && button.active) {
                return VanillaShim.press(button);
            }
        }
        return false;
    }

    private static boolean hasExpectedCape(
            PlayerAppearanceService service, UUID uuid, String capeId) {
        PlayerAppearance appearance = service.getAppearance(uuid);
        return appearance != null
                && capeId.equals(appearance.getCapeId())
                && service.hasActiveCape(uuid)
                && service.getCapeLocation(uuid) != null;
    }

    /** Assert the shared render inputs for either a bundled or adjusted BMO cape route. */
    private static Step.Result assertCapeRoute(
            Minecraft mc,
            PlayerAppearanceService service,
            UUID uuid,
            String capeId,
            boolean expectElytra
    ) {
        return assertCapeRoute(mc, service, uuid, capeId, expectElytra, true);
    }

    private static Step.Result assertCapeRoute(
            Minecraft mc,
            PlayerAppearanceService service,
            UUID uuid,
            String capeId,
            boolean expectElytra,
            boolean requireStablePose
    ) {
        if (!hasExpectedCape(service, uuid, capeId)) {
            PlayerAppearance appearance = service.getAppearance(uuid);
            return Step.Result.fail("active cape="
                    + (appearance == null ? null : appearance.getCapeId())
                    + " expected " + capeId);
        }
        if (mc.player == null) return Step.Result.fail("player null");
        boolean hasElytra = mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        if (hasElytra != expectElytra) {
            return Step.Result.fail("elytra equipped=" + hasElytra + " expected " + expectElytra);
        }
        if (expectElytra && requireStablePose && !mc.player.isCrouching()) {
            return Step.Result.fail("elytra evidence pose is not crouching");
        }
        if (expectElytra && requireStablePose
                && (!sameRotation(mc.player.getYRot(), mc.player.yRotO)
                || !sameRotation(mc.player.getYRot(), mc.player.yHeadRot)
                || !sameRotation(mc.player.getYRot(), mc.player.yHeadRotO)
                || !sameRotation(mc.player.getYRot(), mc.player.yBodyRot)
                || !sameRotation(mc.player.getYRot(), mc.player.yBodyRotO))) {
            return Step.Result.fail("elytra evidence camera/body yaw is not stably aligned");
        }
        Object expected = CapeService.getInstance().getCapeLocation(null, capeId);
        Object resolved = service.getCapeLocation(uuid);
        if (expected == null || !expected.equals(resolved)) {
            return Step.Result.fail("cape location=" + resolved + " expected " + expected);
        }
        String cloak = VanillaShim.cloakTexture(mc.player);
        if (cloak == null) return Step.Result.fail("cloak texture is null for " + capeId);
        if (!String.valueOf(resolved).equals(cloak)) {
            return Step.Result.fail("renderer cloak=" + cloak + " expected " + resolved);
        }
        return Step.Result.pass(capeId + " resolved to " + resolved
                + (expectElytra ? " with elytra equipped" : " with an empty chest slot"));
    }

    private static String vanillaElytraFallbackProblem(
            Minecraft mc, PlayerAppearanceService service, UUID uuid) {
        if (mc.player == null) return "player is null";
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return "CHEST slot is not elytra";
        }
        if (!mc.player.isCrouching()) return "player is not crouching";
        if (service.hasActiveCape(uuid)) return "cape service remains active";
        Object serviceCape = service.getCapeLocation(uuid);
        if (serviceCape != null) return "cape service location=" + serviceCape;
        String configuredCape = ClientConfig.getInstance().activeCapeHash;
        if (!configuredCape.isEmpty()) return "persisted cape=" + configuredCape;
        PlayerAppearance appearance = service.getAppearance(uuid);
        if (appearance == null) return "appearance is null";
        String capeId = appearance.getCapeId();
        if (capeId != null && !capeId.isEmpty()) return "appearance cape=" + capeId;
        String cloak = VanillaShim.cloakTexture(mc.player);
        if (cloak != null) return "renderer cloak=" + cloak;
        String profileElytra = VanillaShim.elytraTexture(mc.player);
        if (profileElytra != null) return "profile elytra=" + profileElytra;
        return null;
    }

    private static Step.Result assertVanillaElytraAfterCapeRemoval(
            Minecraft mc, PlayerAppearanceService service, UUID uuid) {
        if (mc.player == null) return Step.Result.fail("player null");
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return Step.Result.fail("Remove Cape also removed the equipped elytra");
        }
        if (!mc.player.isCrouching()) {
            return Step.Result.fail("vanilla elytra evidence pose is not crouching");
        }
        if (!sameRotation(mc.player.getYRot(), mc.player.yRotO)
                || !sameRotation(mc.player.getYRot(), mc.player.yHeadRot)
                || !sameRotation(mc.player.getYRot(), mc.player.yHeadRotO)
                || !sameRotation(mc.player.getYRot(), mc.player.yBodyRot)
                || !sameRotation(mc.player.getYRot(), mc.player.yBodyRotO)) {
            return Step.Result.fail("vanilla elytra camera/body yaw is not stably aligned");
        }
        PlayerAppearance appearance = service.getAppearance(uuid);
        String capeId = appearance == null ? null : appearance.getCapeId();
        if (capeId != null && !capeId.isEmpty()) {
            return Step.Result.fail("cape id survived Remove Cape: " + capeId);
        }
        if (service.hasActiveCape(uuid) || service.getCapeLocation(uuid) != null) {
            return Step.Result.fail("cape service still exposes an active cape after removal");
        }
        if (!ClientConfig.getInstance().activeCapeHash.isEmpty()) {
            return Step.Result.fail("persisted active cape survived Remove Cape: "
                    + ClientConfig.getInstance().activeCapeHash);
        }
        String cloak = VanillaShim.cloakTexture(mc.player);
        if (cloak != null) {
            return Step.Result.fail("renderer cloak survived Remove Cape: " + cloak);
        }
        String profileElytra = VanillaShim.elytraTexture(mc.player);
        if (profileElytra != null) {
            return Step.Result.fail("test profile unexpectedly supplies an elytra texture: "
                    + profileElytra);
        }
        return Step.Result.pass("Remove Cape cleared persisted, service and renderer cape state; "
                + "elytra remains equipped and renderer inputs select "
                + "minecraft:textures/entity/elytra.png");
    }

    private Step.Result assertAdjustedBmoRoute(
            Minecraft mc, PlayerAppearanceService service, UUID uuid, boolean expectElytra) {
        return assertAdjustedBmoRoute(mc, service, uuid, expectElytra, true);
    }

    private Step.Result assertAdjustedBmoRoute(
            Minecraft mc,
            PlayerAppearanceService service,
            UUID uuid,
            boolean expectElytra,
            boolean requireStablePose) {
        if (adjustedBmoCapeHash == null) {
            return Step.Result.fail("adjusted BMO cape was not catalogued");
        }
        String capeId = "local_cape:" + adjustedBmoCapeHash;
        Step.Result route = assertCapeRoute(
                mc, service, uuid, capeId, expectElytra, requireStablePose);
        if (!route.pass()) return route;
        BufferedImage expected = bundledBmoAtlas.get();
        BufferedImage adjusted = adjustedBmoAtlas.get();
        if (expected == null || adjusted == null) {
            return Step.Result.fail("bundled or adjusted BMO atlas was not retained");
        }
        long drift = countDifferingPixels(expected, adjusted);
        if (drift != 0) {
            return Step.Result.fail("render route uses an adjusted BMO atlas with " + drift
                    + " pixels of drift");
        }
        return Step.Result.pass(route.message() + "; local atlas remains pixel-identical to bundled BMO");
    }

    /** Slider position that turns the centred 128x64 source into a 1:1 64x32 crop. */
    private static double bmoTargetZoomPosition() {
        return CapeZoomRange.position(1.0, TestAssets.BMO_CAPE_WIDTH,
                TestAssets.BMO_PADDED_WIDTH, TestAssets.BMO_PADDED_HEIGHT);
    }

    /** Slider position at reset, where the complete padded source fits inside the 64x32 grid. */
    private static double bmoPaddedZoomPosition() {
        double resetScale = (double) TestAssets.BMO_CAPE_WIDTH / TestAssets.BMO_PADDED_WIDTH;
        return CapeZoomRange.position(resetScale, TestAssets.BMO_CAPE_WIDTH,
                TestAssets.BMO_PADDED_WIDTH, TestAssets.BMO_PADDED_HEIGHT);
    }

    /**
     * Prove that the fixture really is BMO unchanged inside opaque-black padding. A generated or
     * stale lookalike would make the downstream equality assertion meaningless, so this checks all
     * 8192 source pixels rather than a few landmarks.
     */
    private static String validatePaddedBmoSource(
            BufferedImage padded, BufferedImage expected) {
        if (padded.getWidth() != TestAssets.BMO_PADDED_WIDTH
                || padded.getHeight() != TestAssets.BMO_PADDED_HEIGHT) {
            return "padded BMO source is " + padded.getWidth() + "x" + padded.getHeight()
                    + ", expected " + TestAssets.BMO_PADDED_WIDTH + "x"
                    + TestAssets.BMO_PADDED_HEIGHT;
        }
        if (expected.getWidth() != TestAssets.BMO_CAPE_WIDTH
                || expected.getHeight() != TestAssets.BMO_CAPE_HEIGHT) {
            return "bundled BMO atlas has unexpected dimensions";
        }
        for (int y = 0; y < padded.getHeight(); y++) {
            for (int x = 0; x < padded.getWidth(); x++) {
                boolean inside = x >= TestAssets.BMO_PADDED_X
                        && x < TestAssets.BMO_PADDED_X + TestAssets.BMO_CAPE_WIDTH
                        && y >= TestAssets.BMO_PADDED_Y
                        && y < TestAssets.BMO_PADDED_Y + TestAssets.BMO_CAPE_HEIGHT;
                int wanted = inside
                        ? expected.getRGB(x - TestAssets.BMO_PADDED_X,
                                y - TestAssets.BMO_PADDED_Y)
                        : 0xFF000000;
                int actual = padded.getRGB(x, y);
                if (actual != wanted) {
                    return "padded BMO pixel (" + x + "," + y + ")="
                            + Integer.toHexString(actual) + " expected "
                            + Integer.toHexString(wanted)
                            + (inside ? " from bundled BMO" : " opaque black padding");
                }
            }
        }
        return null;
    }

    /** Result of aligning and comparing the fixed central player region in two world captures. */
    private record RenderedDifference(
            double changedFraction, double rmsDifference, int shiftX, int shiftY) {
    }

    private static Step.Result compareBmoRenderPairs(
            String bundledCape,
            String adjustedCape,
            String bundledElytra,
            String adjustedElytra
    ) {
        try {
            RenderedDifference cape = measureRenderedDifference(
                    readShot(bundledCape), readShot(adjustedCape));
            RenderedDifference elytra = measureRenderedDifference(
                    readShot(bundledElytra), readShot(adjustedElytra));
            if (cape == null || elytra == null) {
                return Step.Result.fail("one or more BMO parity screenshots are unavailable");
            }
            if (cape.changedFraction() > BMO_RENDER_MAX_CHANGED_FRACTION) {
                return Step.Result.fail("edited BMO cape render drifted from bundled BMO: changed="
                        + cape.changedFraction() + " rms=" + cape.rmsDifference()
                        + " shift=(" + cape.shiftX() + "," + cape.shiftY() + ")");
            }
            if (elytra.changedFraction() > BMO_RENDER_MAX_CHANGED_FRACTION) {
                return Step.Result.fail("edited BMO elytra render drifted from bundled BMO: changed="
                        + elytra.changedFraction() + " rms=" + elytra.rmsDifference()
                        + " shift=(" + elytra.shiftX() + "," + elytra.shiftY() + ")");
            }
            return Step.Result.pass("bundled vs adjusted BMO render parity: cape changed="
                    + cape.changedFraction() + " rms=" + cape.rmsDifference()
                    + ", elytra changed=" + elytra.changedFraction()
                    + " rms=" + elytra.rmsDifference());
        } catch (RuntimeException error) {
            return Step.Result.fail("could not compare BMO render pairs: " + error);
        }
    }

    /**
     * Compare only the fixed third-person player area and absorb at most three pixels of ordinary
     * entity/cape interpolation drift. A channel delta up to 12 is ignored for lighting noise; a
     * broken UV crop changes the BMO surface by far more and over far more than the allowed tenth
     * of this tightly bounded region. Exact atlas equality remains the primary, non-flaky oracle.
     */
    private static RenderedDifference measureRenderedDifference(
            BufferedImage first, BufferedImage second) {
        if (first == null || second == null
                || first.getWidth() != second.getWidth()
                || first.getHeight() != second.getHeight()) {
            return null;
        }
        int left = (int) Math.floor(first.getWidth() * BMO_RENDER_REGION_LEFT);
        int top = (int) Math.floor(first.getHeight() * BMO_RENDER_REGION_TOP);
        int right = (int) Math.ceil(first.getWidth() * BMO_RENDER_REGION_RIGHT);
        int bottom = (int) Math.ceil(first.getHeight() * BMO_RENDER_REGION_BOTTOM);
        RenderedDifference best = null;
        for (int shiftY = -BMO_RENDER_MAX_ALIGNMENT;
             shiftY <= BMO_RENDER_MAX_ALIGNMENT; shiftY++) {
            for (int shiftX = -BMO_RENDER_MAX_ALIGNMENT;
                 shiftX <= BMO_RENDER_MAX_ALIGNMENT; shiftX++) {
                long changed = 0;
                long squared = 0;
                long pixels = 0;
                for (int y = top + BMO_RENDER_MAX_ALIGNMENT;
                     y < bottom - BMO_RENDER_MAX_ALIGNMENT; y++) {
                    for (int x = left + BMO_RENDER_MAX_ALIGNMENT;
                         x < right - BMO_RENDER_MAX_ALIGNMENT; x++) {
                        int a = first.getRGB(x, y);
                        int b = second.getRGB(x + shiftX, y + shiftY);
                        int dr = Math.abs(((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF));
                        int dg = Math.abs(((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF));
                        int db = Math.abs((a & 0xFF) - (b & 0xFF));
                        int maximum = Math.max(dr, Math.max(dg, db));
                        if (maximum > BMO_RENDER_CHANNEL_TOLERANCE) changed++;
                        squared += (long) dr * dr + (long) dg * dg + (long) db * db;
                        pixels++;
                    }
                }
                double fraction = pixels == 0 ? 1.0 : (double) changed / pixels;
                double rms = pixels == 0 ? 255.0 : Math.sqrt((double) squared / (pixels * 3));
                RenderedDifference candidate = new RenderedDifference(
                        fraction, rms, shiftX, shiftY);
                if (best == null
                        || candidate.changedFraction() < best.changedFraction()
                        || (candidate.changedFraction() == best.changedFraction()
                        && candidate.rmsDifference() < best.rmsDifference())) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** @return how many pixels differ, or -1 when the two atlases are not the same size */
    private static long countDifferingPixels(BufferedImage first, BufferedImage second) {
        if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight()) {
            return -1;
        }
        long differing = 0;
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) {
                    differing++;
                }
            }
        }
        return differing;
    }

    private static BufferedImage composeOnAdjustScreen(Minecraft mc, String method) {
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s)) {
            return null;
        }
        try {
            Method m = CapeAdjustScreen.class.getDeclaredMethod(method);
            m.setAccessible(true);
            return (BufferedImage) m.invoke(s);
        } catch (Throwable t) {
            E2ELog.error(method + " reflection failed", t);
            return null;
        }
    }

    /**
     * The fill is only allowed to replace transparency. This probes an opaque pixel of the source
     * that sits inside the visible cape face, so a {@code finalizeCapeFrame} that painted the fill
     * over every pixel — destroying the cape while still reporting zero transparency — fails.
     *
     * @return null when the landmark survived, otherwise the failure description
     */
    private static String checkOpaqueLandmark(BufferedImage image, int yOffset, String label) {
        int pixel = image.getRGB(TestAssets.OPAQUE_LANDMARK_X,
                yOffset + TestAssets.OPAQUE_LANDMARK_Y);
        if (pixel != TestAssets.OPAQUE_LANDMARK_ARGB) {
            return label + " overwrote an opaque pixel: " + Integer.toHexString(pixel)
                    + " expected " + Integer.toHexString(TestAssets.OPAQUE_LANDMARK_ARGB);
        }
        return null;
    }

    /** Close any open screen, switch to a fixed 3rd-person-back view, and pin the player's facing. */
    private void enterWorldView(Minecraft mc) {
        try {
            VanillaShim.setScreen(mc, null);
            if (mc.options != null) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                mc.options.keyShift.setDown(false);
            }
            if (mc.player != null) {
                mc.player.setShiftKeyDown(false);
                pinRearEvidenceView(mc);
            }
        } catch (Throwable t) {
            E2ELog.warn("enterWorldView: " + t);
        }
    }

    /**
     * Zoom only the two model checkpoints so the 3-pixel Alex and 4-pixel Steve arms are visible.
     * The original FOV is captured once and restored before any later scenario evidence.
     */
    private void prepareModelEvidenceView(Minecraft mc) {
        enterWorldView(mc);
        Integer current = VanillaShim.fieldOfView(mc);
        if (modelEvidenceOriginalFov == null && current != null) {
            modelEvidenceOriginalFov = current;
        }
        VanillaShim.setFieldOfView(mc, MODEL_EVIDENCE_FOV);
        pinRearEvidenceView(mc);
    }

    /** Hold camera, pose, and renderer-facing geometry through the screenshot settle window. */
    private boolean holdModelEvidenceView(Minecraft mc, String expectedModel) {
        if (mc.player == null || mc.options == null) return false;
        VanillaShim.setScreen(mc, null);
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        mc.options.keyShift.setDown(false);
        mc.player.setShiftKeyDown(false);
        pinRearEvidenceView(mc);
        return VanillaShim.setFieldOfView(mc, MODEL_EVIDENCE_FOV)
                && Integer.valueOf(MODEL_EVIDENCE_FOV).equals(VanillaShim.fieldOfView(mc))
                && expectedModel.equals(VanillaShim.playerModel(mc.player));
    }

    private Step.Result assertModelEvidence(
            Minecraft mc, PlayerAppearanceService svc, UUID uuid, String expectedModel) {
        PlayerAppearance app = svc.getAppearance(uuid);
        if (app == null) return Step.Result.fail("no appearance");
        if (!expectedModel.equals(app.getModel())) {
            return Step.Result.fail(
                    "stored model=" + app.getModel() + " expected " + expectedModel);
        }
        String renderedModel = VanillaShim.playerModel(mc.player);
        if (!expectedModel.equals(renderedModel)) {
            return Step.Result.fail(
                    "renderer model=" + renderedModel + " expected " + expectedModel);
        }
        if (!Integer.valueOf(MODEL_EVIDENCE_FOV).equals(VanillaShim.fieldOfView(mc))) {
            return Step.Result.fail(
                    "model evidence FOV=" + VanillaShim.fieldOfView(mc)
                            + " expected " + MODEL_EVIDENCE_FOV);
        }
        return Step.Result.pass(
                "stored model=" + expectedModel + ", renderer model=" + renderedModel
                        + ", close rear FOV=" + MODEL_EVIDENCE_FOV);
    }

    private void restoreModelEvidenceView(Minecraft mc) {
        Integer original = modelEvidenceOriginalFov;
        if (original != null) {
            VanillaShim.setFieldOfView(mc, original);
            modelEvidenceOriginalFov = null;
        }
    }

    private void equipElytra(Minecraft mc) {
        setChestSlot(mc, new ItemStack(Items.ELYTRA));
    }

    /** Keep both elytra wings visually separated so semantic review cannot mistake them for a cape. */
    private void poseElytraForEvidence(Minecraft mc) {
        equipElytra(mc);
        if (mc.options != null) mc.options.keyShift.setDown(true);
        if (mc.player != null) {
            mc.player.setShiftKeyDown(true);
            pinRearEvidenceView(mc);
        }
    }

    /**
     * Align the camera, head and rendered body to the same rear-facing yaw.
     *
     * <p>Changing only {@code setYRot} leaves {@code yBodyRot} free to retain its previous
     * interpolated direction. That made the player look almost sideways in the BMO elytra evidence:
     * one wing was broad while the other was edge-on even though the logical crouch assertion
     * passed. Pin both current and previous rotations during the settle window so the captured
     * frame is a stable rear view rather than a transition between poses.
     */
    private void pinRearEvidenceView(Minecraft mc) {
        if (mc.player == null) return;
        float yaw = 180f;
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setYHeadRot(yaw);
        mc.player.yHeadRotO = yaw;
        mc.player.setYBodyRot(yaw);
        mc.player.yBodyRotO = yaw;
        mc.player.setXRot(0f);
        mc.player.xRotO = 0f;
    }

    private static boolean sameRotation(float left, float right) {
        return Math.abs(left - right) < 0.01f;
    }

    private static boolean hasEmptyChest(Minecraft mc) {
        return mc.player != null
                && mc.player.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
    }

    private String expectedAnimatedCapeId() {
        return gifCapeHash == null ? null : "local_cape:" + gifCapeHash;
    }

    private String expectedAnimationId() {
        return gifCapeHash == null ? null : "cape_" + gifCapeHash;
    }

    /**
     * Put {@code stack} in the player's chest slot.
     *
     * <p>The harness drives the real game, so it equips the way a player would; the production code
     * under test never writes here, which is the point of the probe that calls this.
     */
    private void setChestSlot(Minecraft mc, ItemStack stack) {
        try {
            if (mc.player != null) {
                mc.player.setItemSlot(EquipmentSlot.CHEST, stack);
            }
        } catch (Throwable t) {
            E2ELog.warn("setChestSlot: " + t);
        }
    }

    private static String screenName(Minecraft mc) {
        return VanillaShim.currentScreen(mc) == null ? "<none>" : VanillaShim.currentScreen(mc).getClass().getName();
    }

    private boolean pressLastButton(Minecraft mc) {
        if (VanillaShim.currentScreen(mc) == null) return false;
        Button last = null;
        for (GuiEventListener c : VanillaShim.currentScreen(mc).children()) {
            if (c instanceof Button b) last = b;
        }
        if (last != null) { VanillaShim.press(last); return true; }
        return false;
    }

    // ===== animation reflection ================================================================

    /** The registered state for the cape actually worn by this checkpoint, or {@code null}. */
    private Object expectedAnimatedState() {
        try {
            AnimatedTextureManager mgr = AnimatedTextureManager.getInstance();
            Field f = AnimatedTextureManager.class.getDeclaredField("animations");
            f.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) f.get(mgr);
            Object state = map.get(expectedAnimationId());
            AnimationMetadata metadata = metaOf(state);
            return metadata != null && metadata.frameCount() > 1 ? state : null;
        } catch (Throwable t) {
            E2ELog.warn("expectedAnimatedState: " + t);
        }
        return null;
    }

    private int frameOf(Object state) {
        Object v2 = stateField(state, "currentFrame");
        return (v2 instanceof Integer i) ? i : -1;
    }

    private AnimationMetadata metaOf(Object state) {
        Object v2 = stateField(state, "metadata");
        return (v2 instanceof AnimationMetadata m) ? m : null;
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

    // ===== screen / overlay / config reflection ================================================

    private static Object screenField(Object screen, String name) {
        try {
            Field f = screen.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(screen);
        } catch (Throwable t) {
            E2ELog.warn("field " + name + " on " + screen.getClass().getSimpleName() + ": " + t);
            return null;
        }
    }

    /** {@code ClientConfig.activeSkinHash} is a public field; set reflectively to stay robust. */
    private static void setActiveSkinHash(ClientConfig c, String hash) {
        try {
            Field f = ClientConfig.class.getField("activeSkinHash");
            f.set(c, hash);
        } catch (Throwable t) {
            E2ELog.warn("setActiveSkinHash: " + t);
        }
    }

    private void overlayForceResolve() {
        try {
            Field f = Class.forName("com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay")
                    .getDeclaredField("lastCheckedSkinHash");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Throwable t) {
            E2ELog.warn("overlayForceResolve: " + t);
        }
    }

    /** True once {@code SkinPreviewOverlay.render} has executed at least once (cachedScale set). */
    private boolean overlayRendered() {
        try {
            Field f = Class.forName("com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay")
                    .getDeclaredField("cachedScale");
            f.setAccessible(true);
            return f.getFloat(null) > 0f;
        } catch (Throwable t) {
            return false;
        }
    }

    private Object overlayCachedSkinLocation() {
        try {
            Field f = Class.forName("com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay")
                    .getDeclaredField("cachedSkinLocation");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
