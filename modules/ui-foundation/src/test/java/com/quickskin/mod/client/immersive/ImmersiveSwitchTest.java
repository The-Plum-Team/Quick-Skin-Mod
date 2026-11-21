package com.quickskin.mod.client.immersive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmersiveSwitchTest {
    @Test
    void theOldScreenIsGoneAndTheNewOneWholeBeforeTheSwitchEnds() {
        assertEquals(1f, ImmersiveSwitch.leaving(0f));
        assertEquals(0f, ImmersiveSwitch.arriving(0f));
        assertEquals(0f, ImmersiveSwitch.travelled(0f));
        assertEquals(0f, ImmersiveSwitch.leaving(ImmersiveSwitch.LENGTH));
        assertEquals(1f, ImmersiveSwitch.arriving(ImmersiveSwitch.LENGTH));
        assertEquals(1f, ImmersiveSwitch.travelled(ImmersiveSwitch.LENGTH));
    }

    @Test
    void bothScreensFadeAndTheCameraTravelsWithoutEverGoingBack() {
        float leaving = 1f;
        float arriving = 0f;
        float travelled = 0f;
        boolean bothShown = false;
        for (float seconds = 0f; seconds <= ImmersiveSwitch.LENGTH; seconds += 1f / 240f) {
            assertTrue(ImmersiveSwitch.leaving(seconds) <= leaving);
            assertTrue(ImmersiveSwitch.arriving(seconds) >= arriving);
            assertTrue(ImmersiveSwitch.travelled(seconds) >= travelled);
            leaving = ImmersiveSwitch.leaving(seconds);
            arriving = ImmersiveSwitch.arriving(seconds);
            travelled = ImmersiveSwitch.travelled(seconds);
            bothShown |= leaving > 0.1f && arriving > 0.1f;
        }
        // A crossfade: for a moment both screens show, never a frame with neither
        assertTrue(bothShown);
    }

    @Test
    void theCameraStartsAndStopsGently() {
        float step = 1f / 60f;
        float first = ImmersiveSwitch.travelled(step);
        float middle = ImmersiveSwitch.travelled(0.31f + step) - ImmersiveSwitch.travelled(0.31f);
        float last = 1f - ImmersiveSwitch.travelled(ImmersiveSwitch.LENGTH - 0.03f - step);
        assertTrue(first < middle / 4f);
        assertTrue(last < middle / 4f);
    }
}
