package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Easing into the walk and out of it: a restored pose switches at once, a picked one eases over
 * its time without a jump from frame to frame, and a change of mind turns back from where the
 * blend is.
 */
class GaitBlendTest {

    private static final long FRAME = 1_000_000_000L / 60;

    private static long nanos(float seconds) {
        return (long) (seconds * 1.0e9);
    }

    @Test
    void setSwitchesAtOnce() {
        GaitBlend gait = new GaitBlend();
        gait.set(true);
        assertEquals(1.0f, gait.amount(0));
        gait.set(false);
        assertEquals(0.0f, gait.amount(0));
    }

    @Test
    void easesIntoTheWalkAndOutOfIt() {
        GaitBlend gait = new GaitBlend();
        gait.set(false);
        gait.ease(true, 0);
        assertEquals(0.0f, gait.amount(0));
        float half = gait.amount(nanos(GaitBlend.START / 2));
        assertTrue(half > 0.4f && half < 0.6f, "halfway in: " + half);
        assertEquals(1.0f, gait.amount(nanos(GaitBlend.START)));

        long stop = nanos(1.0f);
        gait.ease(false, stop);
        assertEquals(1.0f, gait.amount(stop));
        assertEquals(0.0f, gait.amount(stop + nanos(GaitBlend.STOP)));
    }

    @Test
    void movesSmoothlyFromFrameToFrame() {
        GaitBlend gait = new GaitBlend();
        gait.set(false);
        gait.ease(true, 0);
        float previous = gait.amount(0);
        for (long t = FRAME; t < nanos(2.0f); t += FRAME) {
            if (t == 30 * FRAME) {
                gait.ease(false, t);
            }
            float amount = gait.amount(t);
            assertEquals(previous, amount, 0.07f, "at frame " + t / FRAME);
            previous = amount;
        }
        assertEquals(0.0f, previous);
    }

    @Test
    void aChangeOfMindTurnsBackFromWhereTheBlendIs() {
        GaitBlend gait = new GaitBlend();
        gait.set(false);
        gait.ease(true, 0);
        long back = nanos(GaitBlend.START / 2);
        float reached = gait.amount(back);
        gait.ease(false, back);
        assertEquals(reached, gait.amount(back), 1.0e-6f);
        // Part of the way back takes its share of the time
        assertEquals(0.0f, gait.amount(back + nanos(reached * GaitBlend.STOP) + 1));
    }

    @Test
    void easingToWhereItIsChangesNothing() {
        GaitBlend gait = new GaitBlend();
        gait.set(true);
        gait.ease(true, 0);
        assertEquals(1.0f, gait.amount(0));
    }
}
