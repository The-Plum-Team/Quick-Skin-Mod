package com.quickskin.mod.e2e;

import com.quickskin.mod.e2e.scenario.CpmFirstPersonScenario;
import com.quickskin.mod.e2e.scenario.FullScenario;
import com.quickskin.mod.e2e.scenario.ModCompatibilityLateJoinScenario;
import com.quickskin.mod.e2e.scenario.ModCompatibilityScenario;
import com.quickskin.mod.e2e.scenario.ModCompatibilityRemoteScenario;
import com.quickskin.mod.e2e.scenario.Phase0Smoke;
import com.quickskin.mod.e2e.scenario.PropagationLiveScenario;
import com.quickskin.mod.e2e.scenario.PropagationScenario;
import com.quickskin.mod.e2e.generated.ScenarioContract;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.io.File;
import java.util.List;

/**
 * Tick-driven E2E state machine. Activated by {@code -Dquickskin.e2e.enabled=true} from a dev-only
 * Loom run config; this class (and the whole {@code e2e} source set) never enters the release jar.
 *
 * <p>Flow: wait until the client has joined a world -> build the selected scenario's steps -> run
 * each step (action / wait-condition / screenshot / assertion) -> write report.json + done.marker.
 * Timing is by ticks and real conditions, never wall-clock sleeps.</p>
 */
public final class E2EHarness {

    private enum State { WAIT_WORLD, RUN_STEPS, FLUSH, DONE }

    private final String version;
    private final String role;
    private final String scenarioId;
    private final E2EReport report;

    private State state = State.WAIT_WORLD;
    private int tick = 0;
    private int worldWaitDeadline = 0;
    private String lastScreen = "";
    private int lastConnectionDiagnosticTick = 0;
    private int missingConnectionReadRepairs = 0;
    private static final int MAX_MISSING_CONNECTION_READ_REPAIRS = 5;

    private List<Step> steps;
    private int stepIndex = 0;
    private boolean actionRun = false;
    private int actionTick = 0;
    private int readyTick = -1; // first tick the current step's ready predicate held; -1 = not yet
    private int flushDeadline = 0;
    private long renderedFrame = 0;
    private boolean captureArmed = false;
    private long captureFrame = -1;
    private boolean captureDispatched = false;
    private boolean captureSucceeded = false;
    private static final int CAPTURE_SETTLE_FRAMES = 2;

    // Track the last dispatched screenshot so FLUSH can confirm it landed at full size and re-grab a
    // transient undersized capture (a macOS window-resize race, observed as a tiny e.g. 90x110 PNG on
    // the LAST step, whose async write has the least time to flush before done.marker).
    private String lastShot;
    private int lastShotRegrabTick = 0;
    private int lastShotRetries = 0;
    private static final int MAX_SHOT_RETRIES = 5;

    private static boolean started = false;

    private E2EHarness(String version, String role, String scenarioId) {
        this.version = version;
        this.role = role;
        this.scenarioId = scenarioId;
        this.report = new E2EReport(version, role, scenarioId);
    }

    /** Idempotent entry point, called from each loader's dev-only client initializer. */
    public static synchronized void start() {
        if (started) return;
        if (!Boolean.getBoolean("quickskin.e2e.enabled")) return;
        started = true;
        String version = System.getProperty("quickskin.e2e.version", "v1_20_1");
        String role = System.getProperty("quickskin.e2e.role", "client_a");
        String scenarioId = System.getProperty("quickskin.e2e.scenario", "phase0-smoke");
        E2ELog.info("activating: version=" + version + " role=" + role + " scenario=" + scenarioId);
        E2EHarness h = new E2EHarness(version, role, scenarioId);
        ClientTickEvent.CLIENT_POST.register(h::onTick);
        ClientGuiEvent.RENDER_HUD.register((graphics, delta) -> h.onHudRendered());
        ClientGuiEvent.RENDER_POST.register(
                (screen, graphics, mouseX, mouseY, delta) -> h.onScreenRendered(screen)
        );
    }

    private void onHudRendered() {
        Minecraft mc = Minecraft.getInstance();
        if (VanillaShim.currentScreen(mc) == null) {
            onRenderedFrame();
        }
    }

    private void onScreenRendered(Screen screen) {
        if (screen != null) {
            onRenderedFrame();
        }
    }

    /** Count HUD/screen passes; the next client tick observes them only after composition completes. */
    private void onRenderedFrame() {
        renderedFrame++;
    }

    /**
     * Grab the last completed render pass from the client-post tick.
     *
     * <p>The product and harness both listen to Architectury's HUD/screen render events. Listener
     * order differs between loaders, so grabbing inside our listener can capture before a later
     * product listener has drawn. Once control reaches the next client tick, every listener from
     * the counted pass has returned and the main target contains the complete composed frame.</p>
     */
    private void dispatchReadyCapture(Minecraft mc) {
        if (state != State.RUN_STEPS
                || !captureArmed
                || captureDispatched
                || renderedFrame < captureFrame
                || stepIndex >= steps.size()) {
            return;
        }
        Step step = steps.get(stepIndex);
        if (step.screenshot == null) {
            captureArmed = false;
            return;
        }
        captureSucceeded = VanillaShim.screenshot(mc, step.screenshot);
        captureDispatched = true;
        captureArmed = false;
        if (captureSucceeded) {
            lastShot = step.screenshot;
            E2ELog.info("step[" + stepIndex + "] " + step.name
                    + " : captured completed rendered frame " + renderedFrame);
        }
    }

    private Scenario resolveScenario() {
        ScenarioId selected = ScenarioId.fromExternal(scenarioId);
        Scenario scenario = switch (selected) {
            case PROPAGATION -> new PropagationScenario();
            case PROPAGATION_LIVE -> new PropagationLiveScenario();
            case FULL -> new FullScenario();
            case MOD_COMPATIBILITY_CPM_FIRST_PERSON -> new CpmFirstPersonScenario();
            case MOD_COMPATIBILITY -> new ModCompatibilityScenario();
            case MOD_COMPATIBILITY_LATE_JOIN -> new ModCompatibilityLateJoinScenario();
            case MOD_COMPATIBILITY_REMOTE -> new ModCompatibilityRemoteScenario();
            case PHASE0_SMOKE -> new Phase0Smoke();
        };
        if (scenario.id() != selected) {
            throw new IllegalStateException("scenario implementation id drift: requested "
                    + selected.externalId() + ", implementation returned "
                    + scenario.id().externalId());
        }
        return scenario;
    }

    private void onTick(Minecraft mc) {
        tick++;
        try {
            switch (state) {
                case WAIT_WORLD -> tickWaitWorld(mc);
                case RUN_STEPS -> tickRunSteps(mc);
                case FLUSH -> tickFlush(mc);
                case DONE -> { /* idle until the orchestrator tears the JVM down */ }
            }
            dispatchReadyCapture(mc);
        } catch (Throwable t) {
            E2ELog.error("harness tick crashed", t);
            report.record("harness_crash", "fail", t.toString(), null);
            finish(mc);
        }
    }

    private void tickWaitWorld(Minecraft mc) {
        if (worldWaitDeadline == 0) {
            worldWaitDeadline = tick + 20 * 90; // 90s budget (heavier loaders + a possible warnings screen)
            E2ELog.info("waiting for world join (up to 90s)...");
        }
        // ReplayMod starts recording on the Netty login path and may run its abandoned-file scan
        // before Minecraft publishes player/level. The compatibility wave installs the selected
        // mod for its clean comparison scenarios too, so give every scenario a cheap pre-world
        // hook. It is a no-op unless that process was launched with ReplayMod selected.
        ModCompatibilityScenario.prepareBeforeWorldJoin();
        // Diagnostic: log each screen transition so a stuck client (title vs loading vs error) is visible.
        Screen sc = VanillaShim.currentScreen(mc);
        String screen = VanillaShim.screenDiagnostic(sc);
        if (!screen.equals(lastScreen)) {
            E2ELog.info("screen -> " + screen);
            lastScreen = screen;
            // A release gate must never click through a loader compatibility/error screen. Doing so
            // can turn an invalid dependency range or a recoverable mod-loading failure into a false
            // pass. Record the screen immediately; the runner will retain the client log and frame.
            if (VanillaShim.isWarningOrErrorScreen(sc)) {
                String shot = version + "_00_startup_warning_" + role + ".png";
                boolean captured = VanillaShim.screenshot(mc, shot);
                report.record("startup_warning_screen", "fail",
                        "unexpected startup compatibility/error screen: " + screen,
                        captured ? shot : null);
                finish(mc);
                return;
            }
            // A disconnected screen cannot recover without user input. Finish immediately and
            // expose its exact translated reason instead of waiting out the remaining 90s budget.
            // Only the machine category below is eligible for one bounded orchestrator retry.
            if (VanillaShim.isDisconnectedScreen(sc)) {
                String shot = version + "_00_join_disconnect_" + role + ".png";
                boolean captured = VanillaShim.screenshot(mc, shot);
                String category = VanillaShim.isConnectionTimeoutScreen(sc)
                        ? "connection_timeout"
                        : "connection_rejected";
                report.record("join_world", "fail",
                        "category=" + category + "; disconnected before world join; " + screen,
                        captured ? shot : null);
                finish(mc);
                return;
            }
        }
        if (VanillaShim.isConnectScreen(sc)) {
            if (missingConnectionReadRepairs < MAX_MISSING_CONNECTION_READ_REPAIRS
                    && Boolean.getBoolean("quickskin.e2e.repairMissingConnectionRead")
                    && VanillaShim.repairMissingConnectionRead(sc)) {
                missingConnectionReadRepairs++;
                E2ELog.info("connection -> repaired missing OP_READ interest ("
                        + missingConnectionReadRepairs + "/"
                        + MAX_MISSING_CONNECTION_READ_REPAIRS + ")");
            }
            if (tick - lastConnectionDiagnosticTick >= 20 * 10) {
                E2ELog.info("connection -> " + VanillaShim.connectionDiagnostic(sc));
                lastConnectionDiagnosticTick = tick;
            }
        }
        if (mc.player != null && mc.level != null) {
            Scenario scenario = resolveScenario();
            steps = scenario.build(mc);
            E2EContractValidator.validate(scenario, role, steps);
            E2ELog.info("joined world; running " + steps.size() + " steps");
            state = State.RUN_STEPS;
            return;
        }
        if (tick > worldWaitDeadline) {
            E2ELog.warn("timed out waiting for world join (lastScreen=" + lastScreen + ")");
            String shot = version + "_00_join_timeout_" + role + ".png";
            boolean captured = VanillaShim.screenshot(mc, shot);
            report.record("join_world", "timeout",
                    "player/level null after 90s; lastScreen=" + lastScreen,
                    captured ? shot : null);
            finish(mc);
        }
    }

    private void tickRunSteps(Minecraft mc) {
        if (stepIndex >= steps.size()) {
            // Don't finish() immediately: Screenshot.grab is async (GPU readback -> worker-thread PNG
            // write), so the LAST step's screenshot may not have flushed to disk yet. Writing
            // done.marker now lets the orchestrator tear the JVM down mid-write -> a 0-byte PNG
            // (observed in packaged loader runs for the final frame). Wait a few seconds first.
            state = State.FLUSH;
            flushDeadline = tick + 60; // ~3s for pending screenshot writes to flush
            lastShotRegrabTick = tick; // give the last grab ~1s to land before FLUSH re-grab checks
            E2ELog.info("all steps done; flushing pending screenshot writes (~3s) before finish");
            return;
        }
        Step s = steps.get(stepIndex);

        if (captureDispatched) {
            completeStep(s, captureSucceeded ? s.screenshot : null, !captureSucceeded);
            return;
        }

        if (!actionRun) {
            actionRun = true;
            actionTick = tick;
            E2ELog.info("step[" + stepIndex + "] " + s.name + " : action");
            if (s.action != null) {
                try {
                    s.action.run();
                } catch (Throwable t) {
                    E2ELog.error("step action threw: " + s.name, t);
                    report.record(s.name, "fail", "action threw: " + t, null);
                    advance();
                    return;
                }
            }
        }

        int waited = tick - actionTick;
        boolean ready = waited >= s.minTicks && (s.ready == null || safe(s.ready));

        if (waited > s.timeoutTicks) {
            E2ELog.warn("step[" + stepIndex + "] " + s.name + " : TIMEOUT");
            report.record(s.name, "timeout",
                    "ready condition or rendered capture frame not reached within "
                            + s.timeoutTicks + " ticks",
                    null);
            advance();
            return;
        }

        if (!ready) {
            readyTick = -1; // the state flickered; the settle window restarts on the next hold
            captureArmed = false;
            captureFrame = -1;
        } else {
            if (s.screenshot != null
                    && VanillaShim.currentScreen(mc) == null
                    && mc.player != null) {
                DefaultSkinEvidenceView.pinStandingMotion(mc.player);
            }
            if (readyTick < 0) readyTick = tick;
            // Screenshot.grab reads the last PRESENTED frame, so capturing on the tick the predicate
            // first held would record the frame drawn BEFORE the awaited change. Let the state hold
            // (and keep rendering) so the captured frame actually shows it.
            if (tick - readyTick < s.settleTicks) return;

            if (s.screenshot != null) {
                if (!captureArmed) {
                    String overlayFailure = VanillaShim.clearTransientOverlays(mc);
                    if (overlayFailure != null) {
                        report.record(s.name, "fail", overlayFailure, null);
                        advance();
                        return;
                    }
                    captureArmed = true;
                    captureFrame = renderedFrame + CAPTURE_SETTLE_FRAMES;
                    E2ELog.info("step[" + stepIndex + "] " + s.name
                            + " : armed for rendered frame " + captureFrame);
                }
                return;
            }
            completeStep(s, null, false);
            return;
        }

    }

    private void completeStep(Step step, String screenshot, boolean screenshotFailed) {
        Step.Result result;
        try {
            result = step.assertion == null
                    ? Step.Result.pass("no assertion")
                    : step.assertion.run();
        } catch (Throwable failure) {
            result = Step.Result.fail("assertion threw: " + failure);
        }
        if (screenshotFailed) {
            result = Step.Result.fail("screenshot dispatch failed: " + step.screenshot
                    + "; assertion=" + result.message());
        }
        E2ELog.info("step[" + stepIndex + "] " + step.name + " : "
                + (result.pass() ? "PASS" : "FAIL") + " - " + result.message());
        report.record(
                step.name,
                result.pass() ? "pass" : "fail",
                result.message(),
                screenshot
        );
        advance();
    }

    /**
     * Let pending async screenshot writes flush to disk before writing the done.marker sentinel, and
     * guard the LAST step's grab specifically: it has the least time to flush and has been observed to
     * occasionally capture a transient undersized framebuffer (a macOS window resize mid-grab -> e.g. a
     * 90x110 PNG) even though the propagation/render state is correct. Because the last step's scene is
     * still on screen while we idle here, re-grab it (bounded retries) until the written PNG is full
     * size. This also covers a missing/0-byte last frame.
     */
    private void tickFlush(Minecraft mc) {
        if (lastShot != null && lastShotRetries < MAX_SHOT_RETRIES && tick - lastShotRegrabTick >= 20) {
            int[] dimensions = pngDimensions(screenshotFile(lastShot));
            if (!expectedDimensions(dimensions)) {
                lastShotRetries++;
                E2ELog.warn("last screenshot " + lastShot + " is not "
                        + ScenarioContract.SCREENSHOT_WIDTH + "x"
                        + ScenarioContract.SCREENSHOT_HEIGHT + " yet (got "
                        + dimensions[0] + "x" + dimensions[1]
                        + "); re-grabbing (attempt " + lastShotRetries + "/"
                        + MAX_SHOT_RETRIES + ")");
                VanillaShim.screenshot(mc, lastShot);
                lastShotRegrabTick = tick;
                flushDeadline = Math.max(flushDeadline, tick + 40); // let the re-grab flush
                return;
            }
        }
        if (tick >= flushDeadline) {
            if (lastShot != null) {
                int[] dimensions = pngDimensions(screenshotFile(lastShot));
                if (!expectedDimensions(dimensions)) {
                    report.record("screenshot_flush", "fail",
                            "last screenshot never reached "
                                    + ScenarioContract.SCREENSHOT_WIDTH + "x"
                                    + ScenarioContract.SCREENSHOT_HEIGHT + " after "
                                    + lastShotRetries + " retries: " + lastShot + " (got "
                                    + dimensions[0] + "x" + dimensions[1] + ")",
                            lastShot);
                }
            }
            finish(mc);
        }
    }

    /** The on-disk path a dispatched screenshot is written to: {@code <runDir>/screenshots/<name>}. */
    private static File screenshotFile(String name) {
        return new File(new File(System.getProperty("user.dir"), "screenshots"), name);
    }

    /**
     * Width and height from a PNG's IHDR, or {@code {-1, -1}} while it is not fully available.
     * Reading only the fixed header stays robust while the async writer appends pixel data.
     */
    private static int[] pngDimensions(File f) {
        if (f == null || !f.isFile() || f.length() < 33) return new int[] {-1, -1};
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f))) {
            in.skipBytes(16);
            return new int[] {in.readInt(), in.readInt()};
        } catch (Throwable t) {
            return new int[] {-1, -1};
        }
    }

    private static boolean expectedDimensions(int[] dimensions) {
        return dimensions[0] == ScenarioContract.SCREENSHOT_WIDTH
                && dimensions[1] == ScenarioContract.SCREENSHOT_HEIGHT;
    }

    private static boolean safe(java.util.function.BooleanSupplier ready) {
        try { return ready.getAsBoolean(); } catch (Throwable t) { return false; }
    }

    private void advance() {
        stepIndex++;
        actionRun = false;
        readyTick = -1;
        captureArmed = false;
        captureFrame = -1;
        captureDispatched = false;
        captureSucceeded = false;
    }

    private void finish(Minecraft mc) {
        if (state == State.DONE) return;
        state = State.DONE;
        File f = report.write();
        E2ELog.info("FINISHED status=" + (report.allPassed() ? "pass" : "fail")
                + " report=" + (f == null ? "<write failed>" : f.getAbsolutePath()));
    }
}
