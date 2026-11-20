package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Another player's skin-change move plays as their client said, on this client's clock. */
class RemoteSkinSwapTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void theToldMovePlaysOnceAndEnds() {
        PreviewPlayerData remote = new PreviewPlayerData();
        remote.setShowcaseIdle(true);
        long start = 50 * SECOND;

        remote.playSkinSwap(SkinSwapAnimation.Variant.WAVE, start);
        assertEquals(SkinSwapAnimation.Variant.WAVE, remote.getSkinSwapVariant());
        assertEquals(start, remote.getSkinSwapStartNanos());
        assertFalse(remote.advanceSkinSwap(start + SECOND / 4).isRest());

        long after = start + (long) (SkinSwapAnimation.duration(SkinSwapAnimation.Variant.WAVE) * SECOND) + 1;
        assertTrue(remote.advanceSkinSwap(after).isRest());
        assertNull(remote.getSkinSwapVariant());
    }

    @Test
    void aMoveAlreadyPlayingFinishes() {
        PreviewPlayerData remote = new PreviewPlayerData();
        remote.playSkinSwap(SkinSwapAnimation.Variant.HOP, 0L);
        remote.playSkinSwap(SkinSwapAnimation.Variant.TWIRL, SECOND / 10);
        assertEquals(SkinSwapAnimation.Variant.HOP, remote.getSkinSwapVariant());
        assertEquals(0L, remote.getSkinSwapStartNanos());
        remote.playSkinSwap(null, 0L);
        assertEquals(SkinSwapAnimation.Variant.HOP, remote.getSkinSwapVariant());
    }

    @Test
    void everyMoveFitsThePoseOnTheWire() {
        // ImmersivePose carries the move's ordinal in 0..15
        assertTrue(SkinSwapAnimation.Variant.values().length <= 16);
    }
}
