package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arms' change of width: it starts on the old width and ends on the new one exactly, passes the
 * new one like rubber in both directions, and moves smoothly between frames.
 */
class ArmWidthMorphTest {

    private static final float FRAME = 1.0f / 60.0f;

    @Test
    void startsOnTheOldWidthAndEndsOnTheNewOne() {
        assertEquals(4.0f, ArmWidthMorph.width(4.0f, 3.0f, 0.0f));
        assertEquals(3.0f, ArmWidthMorph.width(4.0f, 3.0f, ArmWidthMorph.DURATION));
        assertEquals(3.0f, ArmWidthMorph.width(4.0f, 3.0f, 5.0f));
        assertEquals(3.0f, ArmWidthMorph.width(3.0f, 4.0f, -1.0f));
    }

    @Test
    void passesTheNewWidthAndSpringsBack() {
        float narrowest = Float.MAX_VALUE;
        float widest = -Float.MAX_VALUE;
        for (float t = 0.0f; t < ArmWidthMorph.DURATION; t += FRAME) {
            narrowest = Math.min(narrowest, ArmWidthMorph.width(4.0f, 3.0f, t));
            widest = Math.max(widest, ArmWidthMorph.width(3.0f, 4.0f, t));
        }
        assertTrue(narrowest < 2.8f && narrowest > 2.5f, "slim arms pass 3 by a fifth of a pixel or more: " + narrowest);
        assertTrue(widest > 4.2f && widest < 4.5f, "classic arms pass 4 by a fifth of a pixel or more: " + widest);
    }

    @Test
    void movesSmoothlyFromFrameToFrame() {
        float previous = ArmWidthMorph.width(4.0f, 3.0f, 0.0f);
        for (float t = FRAME; t <= ArmWidthMorph.DURATION + FRAME; t += FRAME) {
            float width = ArmWidthMorph.width(4.0f, 3.0f, t);
            assertTrue(Math.abs(width - previous) <= 0.2f, "a jump of " + (width - previous) + " at " + t);
            previous = width;
        }
        assertEquals(3.0f, previous);
    }
}
