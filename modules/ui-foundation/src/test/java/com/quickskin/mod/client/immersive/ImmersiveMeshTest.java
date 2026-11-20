package com.quickskin.mod.client.immersive;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmersiveMeshTest {
    private static final float WIDTH = 640.0f;
    private static final float HEIGHT = 360.0f;
    private static final float FOV = 50.0f;
    private static final float DEPTH = 4.0f;
    /** One GUI unit at the sheet, so that the sheet fills the view at rest. */
    private static final float UNIT = (float) (2.0 * DEPTH * Math.tan(Math.toRadians(FOV) / 2.0) / HEIGHT);
    private static final float QUAD_FLOATS = ImmersiveMesh.QUAD * ImmersiveMesh.STRIDE;

    private static final ImmersiveLayout.Panel COLUMN =
            new ImmersiveLayout.Panel(0.0f, 0.0f, 150.0f, HEIGHT, ImmersiveLayout.Hinge.LEFT, 30.0f);

    private static ImmersiveFrame frame(List<ImmersiveLayout.Panel> panels, float tilt, Matrix4f rigToCamera) {
        return new ImmersiveFrame(WIDTH, HEIGHT, FOV, DEPTH, UNIT, rigToCamera, panels, tilt);
    }

    @Test
    void atRestTheFlatSheetCoversTheWholeGuiWithThePictureUpright() {
        float[] mesh = ImmersiveMesh.sheet(frame(List.of(), 1.0f, new Matrix4f()), WIDTH * 2.0f, HEIGHT * 2.0f);
        assertEquals(0, mesh.length % (int) QUAD_FLOATS);
        // 640 / 16 cells across and 360 / 16 rounded up down
        assertEquals(40 * 23, mesh.length / (int) QUAD_FLOATS);
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < mesh.length; i += ImmersiveMesh.STRIDE) {
            float x = mesh[i];
            float y = mesh[i + 1];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            // Drawn where the screen would be, in a GUI twice as large; the picture's first row is its bottom
            assertEquals(x / (WIDTH * 2.0f), mesh[i + 2], 1.0e-3f);
            assertEquals(1.0f - y / (HEIGHT * 2.0f), mesh[i + 3], 1.0e-3f);
        }
        assertEquals(0.0f, minX, 0.05f);
        assertEquals(WIDTH * 2.0f, maxX, 0.05f);
        assertEquals(0.0f, minY, 0.05f);
        assertEquals(HEIGHT * 2.0f, maxY, 0.05f);
    }

    @Test
    void noCellLiesAcrossAPanelEdge() {
        float[] mesh = ImmersiveMesh.sheet(frame(List.of(COLUMN), 1.0f, new Matrix4f()), WIDTH, HEIGHT);
        float edge = COLUMN.right() / WIDTH;
        boolean beside = false;
        for (int quad = 0; quad < mesh.length; quad += (int) QUAD_FLOATS) {
            float lowest = Float.MAX_VALUE;
            float highest = -Float.MAX_VALUE;
            for (int vertex = 0; vertex < ImmersiveMesh.QUAD; vertex++) {
                float u = mesh[quad + vertex * ImmersiveMesh.STRIDE + 2];
                lowest = Math.min(lowest, u);
                highest = Math.max(highest, u);
            }
            assertTrue(highest <= edge + 1.0e-5f || lowest >= edge - 1.0e-5f, "a cell straddles the panel's edge");
            beside |= Math.abs(highest - edge) < 1.0e-5f;
        }
        assertTrue(beside);
    }

    @Test
    void aSwungPanelsFreeEdgeRecedesWhileTheSheetBesideItStaysFlat() {
        float[] mesh = ImmersiveMesh.sheet(frame(List.of(COLUMN), 1.0f, new Matrix4f()), WIDTH, HEIGHT);
        float edge = COLUMN.right() / WIDTH;
        float sheetEdge = Float.MAX_VALUE;
        float freeEdge = -Float.MAX_VALUE;
        for (int i = 0; i < mesh.length; i += ImmersiveMesh.STRIDE) {
            if (Math.abs(mesh[i + 2] - edge) < 1.0e-5f) {
                sheetEdge = Math.min(sheetEdge, mesh[i]);
                freeEdge = Math.max(freeEdge, mesh[i]);
            }
        }
        // The flat sheet's edge is drawn where the screen has it; the column swings away from the
        // camera about its left hinge, so its free edge is seen nearer the middle of the view
        assertEquals(COLUMN.right(), sheetEdge, 0.05f);
        assertTrue(freeEdge > COLUMN.right() + 1.0f, "free edge at " + freeEdge);
    }

    @Test
    void cellsTheCameraCannotSeeAreLeftOut() {
        // The camera stands beyond the sheet: all of it is behind the camera
        float[] mesh = ImmersiveMesh.sheet(frame(List.of(COLUMN), 1.0f,
                new Matrix4f().translate(0.0f, 0.0f, DEPTH + 1.0f)), WIDTH, HEIGHT);
        assertEquals(0, mesh.length);
    }

    @Test
    void whatLiesAboveIsOneFlatQuadMovedToTheMouse() {
        float[] quad = ImmersiveMesh.above(frame(List.of(), 1.0f, new Matrix4f()), WIDTH * 2.0f, HEIGHT * 2.0f,
                10.0f, -5.0f);
        assertArrayEquals(new float[]{
                20.0f, -10.0f, 0.0f, 1.0f,
                20.0f, HEIGHT * 2.0f - 10.0f, 0.0f, 0.0f,
                WIDTH * 2.0f + 20.0f, HEIGHT * 2.0f - 10.0f, 1.0f, 0.0f,
                WIDTH * 2.0f + 20.0f, -10.0f, 1.0f, 1.0f,
        }, quad, 1.0e-4f);
    }
}
