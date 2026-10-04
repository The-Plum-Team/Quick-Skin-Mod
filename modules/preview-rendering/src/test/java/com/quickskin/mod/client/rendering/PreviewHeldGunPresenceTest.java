package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewHeldGunPresenceTest {

    private static final long FRAME = 16_000_000L;
    private static final long TICK = 50_000_000L;
    /** The gap between two frames at 2 fps. */
    private static final long HALF_A_SECOND = 500_000_000L;

    private final AtomicLong now = new AtomicLong(1_000L);
    private final PreviewHeldGunPresence presence = new PreviewHeldGunPresence(now::get);
    private final Object player = new Object();

    @Test
    void nothingIsShownBeforeAPreviewHasDrawnTheGun() {
        // Preview off, HUD hidden, a menu preview, no gun in the hand, no TaCZ: no draw ever marks,
        // so TaCZ keeps its own first-person answer.
        assertFalse(presence.showing(player));
        assertFalse(presence.showing(null));
    }

    @Test
    void aPreviewDrawnEveryFrameIsShownWhenAnEventFiresBetweenFrames() {
        presence.drawn(player);

        // A key press is handled right after the draw. A shot or a gun switch comes on the client
        // ticks that open the next frame, before its draw: one frame gap later, 16 ms at 60 fps or
        // about three ticks at 6 fps.
        assertTrue(presence.showing(player));
        now.addAndGet(FRAME);
        assertTrue(presence.showing(player));
        now.addAndGet(3 * TICK);
        assertTrue(presence.showing(player));
    }

    @Test
    void everyDrawRenewsTheAnswer() {
        for (int frame = 0; frame < 200; frame++) {
            presence.drawn(player);
            now.addAndGet(FRAME);
        }

        assertTrue(presence.showing(player), "a preview drawn for seconds must still count");
    }

    @Test
    void aPreviewThatStoppedBeingDrawnStopsCounting() {
        presence.drawn(player);

        now.addAndGet(PreviewHeldGunPresence.WINDOW_NANOS);
        assertTrue(presence.showing(player));
        now.addAndGet(1);
        // Switched off, hidden with F1 or covered by a screen: TaCZ's own behaviour is back. So it
        // is on the catch-up ticks after frames that stalled for longer, until the next draw.
        assertFalse(presence.showing(player));

        presence.drawn(player);
        assertTrue(presence.showing(player), "switching the preview back on counts again");
    }

    @Test
    void onlyThePlayerThatWasDrawnIsShown() {
        Object respawned = new Object();
        presence.drawn(player);

        // A respawn or a new world replaces the local player; the old draw says nothing about it.
        assertFalse(presence.showing(respawned));
        assertFalse(presence.showing(null));

        presence.drawn(respawned);
        assertTrue(presence.showing(respawned));
        assertFalse(presence.showing(player));
    }

    @Test
    void theClockMayWrapBetweenADrawAndTheQuestion() {
        // System.nanoTime promises differences, not values: its readings can be negative and wrap.
        now.set(Long.MAX_VALUE - FRAME / 2);
        presence.drawn(player);

        now.addAndGet(FRAME);
        assertTrue(now.get() < 0, "the reading wrapped");
        assertTrue(presence.showing(player));

        now.addAndGet(PreviewHeldGunPresence.WINDOW_NANOS);
        assertFalse(presence.showing(player));
    }

    @Test
    void theWindowCoversFrameGapsUpToHalfASecondAndIsShortForAPlayer() {
        // Minecraft runs the client ticks a frame owes together, before that frame's draw, so an
        // event on any of them sees the previous frame's mark aged by the gap between the two
        // frames, whatever the number of ticks. The window covers gaps up to 2 fps; after a longer
        // stall such an event gets TaCZ's own first-person answer, and the preview misses that
        // action. Half a second after the preview is gone nobody is watching it any more.
        assertTrue(PreviewHeldGunPresence.WINDOW_NANOS >= HALF_A_SECOND);
        assertTrue(PreviewHeldGunPresence.WINDOW_NANOS <= 1_000_000_000L);
    }
}
