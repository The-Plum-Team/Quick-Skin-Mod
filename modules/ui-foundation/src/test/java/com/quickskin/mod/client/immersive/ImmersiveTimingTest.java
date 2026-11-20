package com.quickskin.mod.client.immersive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmersiveTimingTest {
    @Test
    void aSlowShaderCanFinishTheOpeningTransition() {
        assertEquals(0f, ImmersiveMode.step(0f));
        float elapsed = 0f;
        for (int frame = 0; frame < 8; frame++) {
            float step = ImmersiveMode.step(1.5f);
            assertTrue(step > 0f && step <= 0.1f);
            elapsed += step;
        }
        assertTrue(elapsed >= 0.7f);
    }

    @Test
    void invalidClockSamplesClearTheHistoryAndOneSlowFramePreservesTheMedian() {
        assertEquals(0f, ImmersiveMode.step(Float.NaN));
        for (int frame = 0; frame < 5; frame++) ImmersiveMode.step(0.02f);
        assertEquals(0.02f, ImmersiveMode.step(2f));
        assertEquals(0f, ImmersiveMode.step(Float.POSITIVE_INFINITY));
        assertEquals(0f, ImmersiveMode.step(-0.1f));
        assertEquals(0.05f, ImmersiveMode.step(0.05f));
    }
}
