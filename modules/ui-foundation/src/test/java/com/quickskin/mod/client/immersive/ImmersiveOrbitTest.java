package com.quickskin.mod.client.immersive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ImmersiveOrbitTest {
    @Test
    void aDragTurnsTheCameraFromWhereItWasAsTheMenuModelTurns() {
        assertEquals(30.0f + 0.8f * 50.0f, ImmersiveOrbit.turned(30.0f, 100.0, 150.0), 1.0e-4f);
        assertEquals(30.0f - 0.8f * 25.0f, ImmersiveOrbit.turned(30.0f, 100.0, 75.0), 1.0e-4f);
    }

    @Test
    void aMouseBackWhereTheDragStartedPutsTheCameraBack() {
        assertEquals(-12.5f, ImmersiveOrbit.turned(-12.5f, 640.0, 640.0));
    }

    @Test
    void noDragStartedHasNothingToTurnOrRelease() {
        ImmersiveOrbit orbit = new ImmersiveOrbit();
        assertFalse(orbit.drag());
        assertFalse(orbit.release());
    }
}
