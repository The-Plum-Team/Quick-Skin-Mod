package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The previews' light: whatever faces the screen keeps all its colour, also on a model turned as
 * the title screen and the pause menu show it; a face seen edge-on and the top keep most of
 * theirs, and what faces the floor is the only dark part.
 */
class PreviewLightingTest {

    /** What vanilla's inventory light leaves a model's front and sides, which read as dark. */
    private static final float VANILLA_FRONT = 0.82f;
    private static final float VANILLA_SIDE = 0.52f;

    @Test
    void theLampsAreDirections() {
        double length = Math.sqrt(PreviewLighting.SIDE * PreviewLighting.SIDE
                + PreviewLighting.ABOVE * PreviewLighting.ABOVE
                + PreviewLighting.FRONT * PreviewLighting.FRONT);
        assertEquals(1.0, length, 1.0e-3);
    }

    @Test
    void whatFacesTheScreenKeepsAllItsColour() {
        assertEquals(1.0f, PreviewLighting.shade(0.0f, 0.0f, 1.0f), 1.0e-3f);
        assertEquals(1.0f, PreviewLighting.shade(0.0f, 0.0f, 4.0f), 1.0e-3f);
    }

    @Test
    void aModelTurnedAsTheMenusShowItKeepsAllItsColourToo() {
        // The title screen and the pause menu show the model turned by a quarter of a right
        // angle or so; the skin menu's turntable starts it at up to half of one
        for (int degrees = -45; degrees <= 45; degrees++) {
            double turn = Math.toRadians(degrees);
            float shade = PreviewLighting.shade((float) Math.sin(turn), 0.0f, (float) Math.cos(turn));
            assertTrue(shade > 0.99f, degrees + " degrees: " + shade);
        }
    }

    @Test
    void theSidesAndTheTopKeepMostOfTheirs() {
        float left = PreviewLighting.shade(-1.0f, 0.0f, 0.0f);
        float right = PreviewLighting.shade(1.0f, 0.0f, 0.0f);
        float top = PreviewLighting.shade(0.0f, -1.0f, 0.0f);
        assertEquals(left, right, 1.0e-6f);
        assertTrue(left > 0.74f && left < 0.8f, "side: " + left);
        assertTrue(top > 0.85f && top < 0.92f, "top: " + top);
        assertTrue(left > VANILLA_SIDE + 0.2f, "the sides are far lighter than in the inventory's light");
    }

    @Test
    void aFaceDimsOnlyAsItTurnsAwayAndNeverBelowItsEdgeOnShare() {
        // An upright face seen from any angle it can be seen from, as the turntable turns the model
        float edgeOn = PreviewLighting.shade(1.0f, 0.0f, 0.0f);
        float previous = 1.0f;
        for (int degrees = 0; degrees <= 90; degrees++) {
            double turn = Math.toRadians(degrees);
            float shade = PreviewLighting.shade((float) Math.sin(turn), 0.0f, (float) Math.cos(turn));
            assertTrue(shade <= previous + 1.0e-4f, degrees + " degrees: " + shade);
            assertTrue(shade >= edgeOn - 1.0e-4f, degrees + " degrees: " + shade);
            previous = shade;
        }
        // Up to two thirds of a right angle it still beats the inventory's light on a front
        float atSixty = PreviewLighting.shade((float) Math.sin(Math.toRadians(60)), 0.0f,
                (float) Math.cos(Math.toRadians(60)));
        assertTrue(atSixty > VANILLA_FRONT + 0.05f, "60 degrees: " + atSixty);
    }

    @Test
    void onlyWhatFacesTheFloorStaysDark() {
        assertEquals(0.4f, PreviewLighting.shade(0.0f, 1.0f, 0.0f), 1.0e-6f);
        assertEquals(0.4f, PreviewLighting.shade(0.0f, 0.0f, 0.0f), 1.0e-6f);
    }
}
