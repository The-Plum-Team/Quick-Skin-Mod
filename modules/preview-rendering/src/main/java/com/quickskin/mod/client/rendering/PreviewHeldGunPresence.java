package com.quickskin.mod.client.rendering;

import java.lang.ref.WeakReference;
import java.util.function.LongSupplier;

/**
 * Remembers that a preview has just drawn a player with the gun it holds, for code that runs
 * between two frames and has to know whether such a preview is on screen.
 *
 * <p>Timeless and Classics Zero starts the third-person action animations of the local player -
 * reload, recoil, melee - when its gun events fire, not when the player is drawn. Whether the HUD
 * preview is showing that player with the gun is therefore asked at a key press or on a client
 * tick. Only a draw knows the answer: the preview is enabled, the HUD is not hidden, no screen
 * covers it, the subject is the live local player and the item is a gun. So every such draw leaves
 * a mark here, and the mark answers for as long as it is fresh.
 *
 * <p>Minecraft runs a frame's client ticks before its draw and key presses after it, so the mark of
 * a preview on screen is at most one frame gap old when an event fires, however many ticks a slow
 * frame catches up on. A preview switched off or hidden stops counting after {@link #WINDOW_NANOS},
 * as does one whose frames stall for longer, until its next draw. The mark holds its player weakly
 * and by identity: a respawned player counts from its own draws; a world left is not kept alive.
 *
 * <p>Free of Minecraft types, so the rule stays unit testable in the loader-independent test
 * source set.
 */
final class PreviewHeldGunPresence {

    /** How long one draw answers: the longest frame gap (2 fps) that still counts as on screen. */
    static final long WINDOW_NANOS = 500_000_000L;

    private final LongSupplier nanoClock;

    private volatile WeakReference<Object> subject = new WeakReference<>(null);
    private volatile long drawnAt;

    PreviewHeldGunPresence(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** A preview is drawing {@code player} with its held gun visible. */
    void drawn(Object player) {
        if (subject.get() != player) {
            subject = new WeakReference<>(player);
        }
        drawnAt = nanoClock.getAsLong();
    }

    /** Whether a preview drew {@code player} with its held gun visible a moment ago. */
    boolean showing(Object player) {
        if (player == null || subject.get() != player) {
            return false;
        }
        // A difference of two readings, as System.nanoTime asks: the readings themselves may wrap.
        return nanoClock.getAsLong() - drawnAt <= WINDOW_NANOS;
    }
}
