package com.quickskin.mod.e2e;

import com.quickskin.mod.e2e.scenario.FullScenario;
import com.quickskin.mod.e2e.scenario.ModCompatibilityScenario;
import com.quickskin.mod.e2e.scenario.Phase0Smoke;
import com.quickskin.mod.e2e.scenario.PropagationLiveScenario;
import com.quickskin.mod.e2e.scenario.PropagationScenario;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
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
    private boolean stalledConnectionReadRearmed = false;

    private List<Step> steps;
    private int stepIndex = 0;
    private boolean actionRun = false;
    private int actionTick = 0;
    private int readyTick = -1; // first tick the current step's ready predicate held; -1 = not yet
    private int flushDeadline = 0;

    // Track the last dispatched screenshot so FLUSH can confirm it landed at full size and re-grab a
    // transient undersized capture (a macOS window-resize race, observed as a tiny e.g. 90x110 PNG on
    // the LAST step, whose async write has the least time to flush before done.marker).
    private String lastShot;
    private int lastShotRegrabTick = 0;
    private int lastShotRetries = 0;
    private static final int MIN_SHOT_WIDTH = 640; // anything narrower is a broken/transient grab, not a real frame
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
    }

    private Scenario resolveScenario() {
        ScenarioId selected = ScenarioId.fromExternal(scenarioId);
        Scenario scenario = switch (selected) {
            case PROPAGATION -> new PropagationScenario();
            case PROPAGATION_LIVE -> new PropagationLiveScenario();
            case FULL -> new FullScenario();
            case MOD_COMPATIBILITY -> new ModCompatibilityScenario();
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
        if (VanillaShim.isConnectScreen(sc)
                && tick - lastConnectionDiagnosticTick >= 20 * 10) {
            E2ELog.info("connection -> " + VanillaShim.connectionDiagnostic(sc));
            lastConnectionDiagnosticTick = tick;
            if (!stalledConnectionReadRearmed
                    && Boolean.getBoolean("quickskin.e2e.rearmStalledConnectionRead")) {
                stalledConnectionReadRearmed = true;
                if (!VanillaShim.rearmConnectionRead(sc)) {
                    throw new IllegalStateException("could not rearm stalled connection read");
                }
                E2ELog.info("connection -> rearmed the live channel read once");
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

        if (!ready) {
            readyTick = -1; // the state flickered; the settle window restarts on the next hold
        } else {
            if (readyTick < 0) readyTick = tick;
            // Screenshot.grab reads the last PRESENTED frame, so capturing on the tick the predicate
            // first held would record the frame drawn BEFORE the awaited change. Let the state hold
            // (and keep rendering) so the captured frame actually shows it.
            if (tick - readyTick < s.settleTicks) return;

            String shot = null;
            boolean screenshotFailed = false;
            if (s.screenshot != null) {
                if (VanillaShim.screenshot(mc, s.screenshot)) {
                    shot = s.screenshot;
                    lastShot = s.screenshot; // remember the most recent grab for FLUSH validation
                } else {
                    screenshotFailed = true;
                }
            }
            Step.Result r;
            try {
                r = (s.assertion == null) ? Step.Result.pass("no assertion") : s.assertion.run();
            } catch (Throwable t) {
                r = Step.Result.fail("assertion threw: " + t);
            }
            if (screenshotFailed) {
                r = Step.Result.fail("screenshot dispatch failed: " + s.screenshot
                        + "; assertion=" + r.message());
            }
            E2ELog.info("step[" + stepIndex + "] " + s.name + " : "
                    + (r.pass() ? "PASS" : "FAIL") + " - " + r.message());
            report.record(s.name, r.pass() ? "pass" : "fail", r.message(), shot);
            advance();
            return;
        }

        if (waited > s.timeoutTicks) {
            E2ELog.warn("step[" + stepIndex + "] " + s.name + " : TIMEOUT");
            report.record(s.name, "timeout",
                    "ready condition not met within " + s.timeoutTicks + " ticks", null);
            advance();
        }
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
            int w = pngWidth(screenshotFile(lastShot));
            if (w < MIN_SHOT_WIDTH) { // -1 (missing/header not written yet) or an undersized transient grab
                lastShotRetries++;
                E2ELog.warn("last screenshot " + lastShot + " not full size yet (width=" + w
                        + "px); re-grabbing (attempt " + lastShotRetries + "/" + MAX_SHOT_RETRIES + ")");
                VanillaShim.screenshot(mc, lastShot);
                lastShotRegrabTick = tick;
                flushDeadline = Math.max(flushDeadline, tick + 40); // let the re-grab flush
                return;
            }
        }
        if (tick >= flushDeadline) {
            if (lastShot != null) {
                int width = pngWidth(screenshotFile(lastShot));
                if (width < MIN_SHOT_WIDTH) {
                    report.record("screenshot_flush", "fail",
                            "last screenshot never reached " + MIN_SHOT_WIDTH
                                    + "px width after " + lastShotRetries + " retries: "
                                    + lastShot + " (width=" + width + ")",
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
     * Width (px) from a PNG's IHDR, or -1 if the file is absent or too short to hold a header yet
     * (treated as "not written"). PNG layout: 8-byte signature, 4-byte IHDR length, the "IHDR" tag,
     * then a 4-byte big-endian width -- i.e. the width starts at byte offset 16. Reading only the
     * header is robust even while the async writer is still appending pixel data.
     */
    private static int pngWidth(File f) {
        if (f == null || !f.isFile() || f.length() < 33) return -1;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f))) {
            in.skipBytes(16);
            return in.readInt();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean safe(java.util.function.BooleanSupplier ready) {
        try { return ready.getAsBoolean(); } catch (Throwable t) { return false; }
    }

    private void advance() {
        stepIndex++;
        actionRun = false;
        readyTick = -1;
    }

    private void finish(Minecraft mc) {
        if (state == State.DONE) return;
        state = State.DONE;
        File f = report.write();
        E2ELog.info("FINISHED status=" + (report.allPassed() ? "pass" : "fail")
                + " report=" + (f == null ? "<write failed>" : f.getAbsolutePath()));
    }
}
