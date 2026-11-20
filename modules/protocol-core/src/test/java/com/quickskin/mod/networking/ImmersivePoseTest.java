package com.quickskin.mod.networking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An immersive pose is a few bounded numbers; anything outside them is refused. */
class ImmersivePoseTest {
    @Test
    void aWellFormedPoseIsAccepted() {
        assertTrue(ImmersivePose.isValidFromClient(ImmersivePose.SIT, -179.5f, ImmersivePose.CLOSING, 255, 15));
        assertTrue(ImmersivePose.isValidFromClient(ImmersivePose.NONE, 0.0f, 0, 0, 0));
        assertTrue(ImmersivePose.isValidFromServer(ImmersivePose.IDLE, 180.0f,
                ImmersivePose.CLOSING | ImmersivePose.SETTLED, 3, 2));
    }

    @Test
    void numbersOutsideTheirRangeAreRefused() {
        assertFalse(ImmersivePose.isValidFromClient(-1, 0.0f, 0, 0, 0));
        assertFalse(ImmersivePose.isValidFromClient(ImmersivePose.MAX_POSE + 1, 0.0f, 0, 0, 0));
        assertFalse(ImmersivePose.isValidFromClient(ImmersivePose.IDLE, Float.NaN, 0, 0, 0));
        assertFalse(ImmersivePose.isValidFromClient(ImmersivePose.IDLE, Float.POSITIVE_INFINITY, 0, 0, 0));
        assertFalse(ImmersivePose.isValidFromClient(ImmersivePose.IDLE, 180.01f, 0, 0, 0));
        assertFalse(ImmersivePose.isValidFromClient(ImmersivePose.IDLE, 0.0f, 0, 256, 0));
        assertFalse(ImmersivePose.isValidFromClient(ImmersivePose.IDLE, 0.0f, 0, -1, 0));
        assertFalse(ImmersivePose.isValidFromClient(ImmersivePose.IDLE, 0.0f, 0, 0, 16));
        assertFalse(ImmersivePose.isValidFromClient(ImmersivePose.IDLE, 0.0f, 1 << 7, 0, 0));
    }

    @Test
    void onlyTheServerMaySayAPoseIsSettled() {
        assertFalse(ImmersivePose.isValidFromClient(ImmersivePose.IDLE, 0.0f, ImmersivePose.SETTLED, 0, 0));
        assertTrue(ImmersivePose.isValidFromServer(ImmersivePose.IDLE, 0.0f, ImmersivePose.SETTLED, 0, 0));
        assertFalse(ImmersivePose.isValidFromServer(ImmersivePose.IDLE, 0.0f, 4, 0, 0));
    }

    @Test
    void yawIsBroughtIntoOneTurn() {
        assertEquals(10.0f, ImmersivePose.wrapYaw(370.0f), 1e-4f);
        assertEquals(-170.0f, ImmersivePose.wrapYaw(190.0f), 1e-4f);
        assertEquals(-180.0f, ImmersivePose.wrapYaw(180.0f), 1e-4f);
        assertEquals(170.0f, ImmersivePose.wrapYaw(-190.0f), 1e-4f);
        assertEquals(-90.0f, ImmersivePose.wrapYaw(-720.0f - 90.0f), 1e-4f);
        assertEquals(0.0f, ImmersivePose.wrapYaw(Float.NaN));
        for (float yaw = -10_000.0f; yaw < 10_000.0f; yaw += 7.3f) {
            float wrapped = ImmersivePose.wrapYaw(yaw);
            assertTrue(ImmersivePose.isValidFromClient(ImmersivePose.IDLE, wrapped, 0, 0, 0), "yaw " + yaw);
        }
    }

    @Test
    void poseNamesMapBothWays() {
        assertEquals(ImmersivePose.WALK, ImmersivePose.poseOf("Walk"));
        assertEquals(ImmersivePose.SIT, ImmersivePose.poseOf("sit"));
        assertEquals(ImmersivePose.IDLE, ImmersivePose.poseOf("idle"));
        assertEquals(ImmersivePose.IDLE, ImmersivePose.poseOf(null), "an unknown pose plays the idle");
        for (int pose = ImmersivePose.IDLE; pose <= ImmersivePose.MAX_POSE; pose++) {
            assertEquals(pose, ImmersivePose.poseOf(ImmersivePose.animationOf(pose)));
        }
        assertNull(ImmersivePose.animationOf(ImmersivePose.NONE));
    }
}
