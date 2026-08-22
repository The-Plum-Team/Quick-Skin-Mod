package com.quickskin.mod.e2e;

import java.util.function.BooleanSupplier;

/**
 * One step of an E2E scenario, driven by the tick state machine.
 *
 * <p>Lifecycle per step: run {@link #action} once -> poll {@link #ready} every tick (but at least
 * {@link #minTicks} ticks after the action so a frame renders with the new state) -> hold the ready
 * state for {@link #settleTicks} ticks -> hold through two complete render callbacks -> capture
 * {@link #screenshot} (if set) -> run
 * {@link #assertion} -> advance. If {@code ready} never becomes true within {@link #timeoutTicks},
 * the step fails with a timeout.</p>
 *
 * <p>Actions and assertions run on the client tick; screenshots dispatch from the matching final
 * HUD or screen render callback. Both event surfaces execute on the client/render thread.</p>
 */
public final class Step {

    /** Result of a step assertion. */
    public record Result(boolean pass, String message) {
        public static Result pass(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }

    /** Assertion callback; may throw (a thrown exception is recorded as a failure). */
    @FunctionalInterface
    public interface Check { Result run() throws Exception; }

    final String name;
    Runnable action;                 // nullable: nothing to do
    BooleanSupplier ready;           // nullable: ready immediately (after minTicks)
    int minTicks = 5;                // wait at least this many ticks after the action
    int settleTicks = 0;             // hold `ready` this many ticks before capturing (see below)
    int timeoutTicks = 200;          // ~10s at 20 tps
    String screenshot;               // nullable: no capture
    Check assertion;                 // nullable: no assertion (records pass)

    private Step(String name) { this.name = name; }

    public static Step of(String name) { return new Step(name); }

    public Step action(Runnable action) { this.action = action; return this; }
    public Step ready(BooleanSupplier ready) { this.ready = ready; return this; }
    public Step minTicks(int t) { this.minTicks = t; return this; }

    /**
     * Ticks the {@link #ready} state must hold <em>before</em> the screenshot is taken.
     *
     * <p>The harness runs on the client tick and grabs the main render target, which holds the last
     * <em>presented</em> frame — the one drawn before this tick. A step whose {@code ready} predicate
     * flips on the very tick its state changes therefore captures a frame from before that change.
     * Steps that set their state in {@link #action} and then wait out {@link #minTicks} are immune
     * (many frames have been drawn by then), so the default is 0 and existing timing is unchanged.
     *
     * <p>Set this on any step whose predicate waits for an <em>asynchronous</em> change — a packet
     * landing, a texture committing — so the capture is render-truthful rather than one frame stale.
     * Ticks are not frames: under software rendering (CI uses llvmpipe) several ticks can share one
     * frame, so prefer a generous value. The window restarts if {@code ready} goes false again, so a
     * state that only flickers never gets captured.
     */
    public Step settleTicks(int t) { this.settleTicks = t; return this; }
    public Step timeoutTicks(int t) { this.timeoutTicks = t; return this; }
    public Step screenshot(String name) { this.screenshot = name; return this; }
    public Step assertion(Check check) { this.assertion = check; return this; }
}
