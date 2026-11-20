package com.quickskin.mod.client.rendering;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In the immersive view the armour outlines are traced on the real player, seen through the
 * camera: a camera-space point lands on the GUI where the world's projection puts it.
 */
class PreviewEquipmentProjectionTest {
    private static final float WIDTH = 640;
    private static final float HEIGHT = 360;

    /** The world's projection and the clip-space to GUI step, as {@code WorldArmor} composes them. */
    private static Matrix4f cameraToGui(float fovDegrees) {
        Matrix4f toGui = new Matrix4f(
                WIDTH / 2, 0, 0, 0,
                0, -HEIGHT / 2, 0, 0,
                0, 0, 1, 0,
                WIDTH / 2, HEIGHT / 2, 0, 1);
        return toGui.mul(new Matrix4f().perspective((float) Math.toRadians(fovDegrees), WIDTH / HEIGHT, 0.05F, 100F));
    }

    @Test
    void aFlatPreviewsPointIsAlreadyOnTheGui() {
        Vector3f point = new Vector3f(12, 34, 5);
        assertSame(point, PreviewEquipment.toGui(null, point));
        assertEquals(new Vector3f(12, 34, 5), point);
    }

    @Test
    void thePointAheadLandsInTheMiddleAndOneAboveItHigherUp() {
        Matrix4f projection = cameraToGui(50);
        Vector3f ahead = PreviewEquipment.toGui(projection, new Vector3f(0, 0, -3));
        assertEquals(WIDTH / 2, ahead.x, 1e-3);
        assertEquals(HEIGHT / 2, ahead.y, 1e-3);
        // Half the view's height above the middle, at the edge of a 50 degree view
        float edge = (float) (3 * Math.tan(Math.toRadians(25)));
        Vector3f top = PreviewEquipment.toGui(projection, new Vector3f(0, edge, -3));
        assertEquals(0, top.y, 1e-2);
        Vector3f right = PreviewEquipment.toGui(projection, new Vector3f(edge * WIDTH / HEIGHT, 0, -3));
        assertEquals(WIDTH, right.x, 1e-2);
    }

    @Test
    void fartherIsSmallerAndNearerIsDeeper() {
        Matrix4f projection = cameraToGui(50);
        Vector3f near = PreviewEquipment.toGui(projection, new Vector3f(1, 0, -2));
        Vector3f far = PreviewEquipment.toGui(projection, new Vector3f(1, 0, -4));
        assertEquals(near.x - WIDTH / 2, 2 * (far.x - WIDTH / 2), 1e-2, "twice as far, half as far from the middle");
        assertTrue(near.z > far.z, "larger is nearer the viewer");
        assertEquals(-2 * 16, near.z, 1e-4, "in model pixels");
    }
}
