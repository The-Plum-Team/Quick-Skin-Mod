package com.quickskin.mod.client.immersive;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmersiveFrameTest {
    private static final float WIDTH = 640.0f;
    private static final float HEIGHT = 360.0f;
    private static final float FOV = 50.0f;
    private static final float DEPTH = 4.0f;
    /** One GUI unit at the sheet, so that the sheet fills the view at rest. */
    private static final float UNIT = (float) (2.0 * DEPTH * Math.tan(Math.toRadians(FOV) / 2.0) / HEIGHT);

    private static final ImmersiveLayout.Panel COLUMN =
            new ImmersiveLayout.Panel(0.0f, 0.0f, 160.0f, HEIGHT, ImmersiveLayout.Hinge.LEFT, 30.0f);

    private static final ImmersiveLayout.Panel POPUP =
            new ImmersiveLayout.Panel(100, 60, 210, 120, ImmersiveLayout.Hinge.LEFT, 0, 30);

    private static ImmersiveFrame atRest(float tilt) {
        return new ImmersiveFrame(WIDTH, HEIGHT, FOV, DEPTH, UNIT, new Matrix4f(), List.of(COLUMN), tilt);
    }

    @Test
    void atRestTheFlatSheetIsDrawnWhereTheScreenWouldBe() {
        ImmersiveFrame frame = atRest(1.0f);
        float[] seen = new float[2];
        assertTrue(frame.project(ImmersiveFrame.BASE, 400.0f, 90.0f, seen));
        assertEquals(400.0f, seen[0], 0.01f);
        assertEquals(90.0f, seen[1], 0.01f);
    }

    @Test
    void aPanelKeepsItsHingeAndItsFreeEdgeRecedes() {
        ImmersiveFrame frame = atRest(1.0f);
        float[] hinge = new float[2];
        float[] free = new float[2];
        assertTrue(frame.project(0, 0.0f, 0.0f, hinge));
        assertTrue(frame.project(0, 160.0f, 0.0f, free));
        assertEquals(0.0f, hinge[0], 0.01f);
        assertEquals(0.0f, hinge[1], 0.01f);
        // Farther away, its top corner is nearer the middle of the view than it was flat
        assertTrue(free[1] > 1.0f, "the free edge is drawn shorter");
        assertTrue(free[0] > 100.0f && free[0] < WIDTH / 2.0f);
    }

    @Test
    void whatIsOnASwungPanelKeepsItsShape() {
        // A square of the panel is drawn about as wide as tall wherever it is on the panel: the
        // panel grows smaller away from its hinge, and nothing on it is stretched. Turned as a
        // stiff card, its squares came out a quarter wider than tall beside the hinge.
        ImmersiveFrame frame = atRest(1.0f);
        float widest = 0.0f;
        float narrowest = Float.MAX_VALUE;
        for (float x = 4.0f; x <= 150.0f; x += 8.0f) {
            float[] corner = new float[2];
            float[] right = new float[2];
            float[] below = new float[2];
            assertTrue(frame.project(0, x, 176.0f, corner));
            assertTrue(frame.project(0, x + 6.0f, 176.0f, right));
            assertTrue(frame.project(0, x, 182.0f, below));
            float shape = (right[0] - corner[0]) / (below[1] - corner[1]);
            widest = Math.max(widest, shape);
            narrowest = Math.min(narrowest, shape);
        }
        assertTrue(widest < 1.13f, "widest " + widest);
        assertTrue(narrowest > 0.87f, "narrowest " + narrowest);
        // And both ways of swinging do the same: a panel hinged on the right edge mirrors it
        ImmersiveLayout.Panel mirrored =
                new ImmersiveLayout.Panel(WIDTH - 160.0f, 0.0f, WIDTH, HEIGHT, ImmersiveLayout.Hinge.RIGHT, 30.0f);
        ImmersiveFrame other = new ImmersiveFrame(WIDTH, HEIGHT, FOV, DEPTH, UNIT, new Matrix4f(), List.of(mirrored), 1.0f);
        float[] left = new float[2];
        float[] here = new float[2];
        assertTrue(frame.project(0, 40.0f, 100.0f, left));
        assertTrue(other.project(0, WIDTH - 40.0f, 100.0f, here));
        assertEquals(WIDTH - left[0], here[0], 0.01f);
        assertEquals(left[1], here[1], 0.01f);
    }

    @Test
    void theMouseFindsThePointItPointsAtOnAPanel() {
        ImmersiveFrame frame = atRest(1.0f);
        float[] seen = new float[2];
        float[] found = new float[2];
        assertTrue(frame.project(0, 120.0f, 200.0f, seen));
        assertEquals(0, frame.unproject(seen[0], seen[1], ImmersiveFrame.NONE, found));
        assertEquals(120.0f, found[0], 0.05f);
        assertEquals(200.0f, found[1], 0.05f);
    }

    @Test
    void theFlatSheetAnswersOutsideThePanels() {
        ImmersiveFrame frame = atRest(1.0f);
        float[] found = new float[2];
        assertEquals(ImmersiveFrame.BASE, frame.unproject(500.0f, 100.0f, ImmersiveFrame.NONE, found));
        assertEquals(500.0f, found[0], 0.05f);
        assertEquals(100.0f, found[1], 0.05f);
    }

    @Test
    void whatSwungAwayIsNoLongerOnTheFlatSheet() {
        ImmersiveFrame frame = atRest(1.0f);
        float[] corner = new float[2];
        assertTrue(frame.project(0, 160.0f, 0.0f, corner));
        // Above the panel's shortened free edge, where the flat column used to be: neither the
        // panel, whose top edge now slopes down to that corner, nor the sheet
        float x = corner[0] - 10.0f;
        float slopeAtX = corner[1] * x / corner[0];
        float[] found = new float[2];
        assertEquals(ImmersiveFrame.NONE, frame.unproject(x, slopeAtX - 3.0f, ImmersiveFrame.NONE, found));
        assertEquals(0, frame.unproject(x, slopeAtX + 3.0f, ImmersiveFrame.NONE, found));
    }

    @Test
    void aHeldButtonKeepsItsPanelBeyondItsEdge() {
        ImmersiveFrame frame = atRest(1.0f);
        float[] found = new float[2];
        assertEquals(0, frame.unproject(500.0f, 180.0f, 0, found));
        assertTrue(found[0] > 160.0f, "the point lies past the panel's free edge");
    }

    @Test
    void closingTheContextMenuKeepsADragOnTheSkinColumn() {
        ImmersiveLayout.Panel column = new ImmersiveLayout.Panel(0, 0, 160, HEIGHT, ImmersiveLayout.Hinge.LEFT, 30);
        ImmersiveFrame open = new ImmersiveFrame(WIDTH, HEIGHT, FOV, DEPTH, UNIT, new Matrix4f(),
                List.of(POPUP, column), 0.9f);
        ImmersiveFrame closed = atRest(1.0f);
        int columnBefore = open.partAt(80, 200);
        assertEquals(1, columnBefore);
        int columnAfter = closed.partFrom(open, columnBefore);
        assertEquals(0, columnAfter);
        float[] found = new float[2];
        assertEquals(columnAfter, closed.unproject(500, 180, columnAfter, found));
        assertTrue(found[0] > COLUMN.right());
        assertEquals(columnBefore, open.partFrom(closed, columnAfter));
        assertEquals(ImmersiveFrame.BASE, closed.partFrom(open, ImmersiveFrame.BASE));
    }

    @Test
    void removingAPanelDropsItsCaptureEvenWhenAnotherPanelTakesItsIndex() {
        ImmersiveFrame open = new ImmersiveFrame(WIDTH, HEIGHT, FOV, DEPTH, UNIT, new Matrix4f(),
                List.of(POPUP, COLUMN), 1.0f);
        ImmersiveFrame closed = atRest(1.0f);
        int capture = closed.partFrom(open, open.partAt(110, 90));
        assertEquals(ImmersiveFrame.NONE, capture);
        assertEquals(ImmersiveFrame.NONE, open.partFrom(closed, capture));
        assertEquals(ImmersiveFrame.NONE, closed.unproject(110, 90, 1, new float[2]));
    }

    @Test
    void aFlatPanelIsPartOfTheScreenAgain() {
        ImmersiveFrame frame = atRest(0.0f);
        float[] seen = new float[2];
        assertTrue(frame.project(0, 80.0f, 300.0f, seen));
        assertEquals(80.0f, seen[0], 0.01f);
        assertEquals(300.0f, seen[1], 0.01f);
    }

    @Test
    void nothingBehindTheCameraIsDrawn() {
        // The camera stands past the sheet, looking away from it
        Matrix4f pastTheSheet = new Matrix4f().translate(0.0f, 0.0f, DEPTH + 1.0f);
        ImmersiveFrame frame = new ImmersiveFrame(WIDTH, HEIGHT, FOV, DEPTH, UNIT, pastTheSheet, List.of(), 0.0f);
        assertFalse(frame.project(ImmersiveFrame.BASE, 320.0f, 180.0f, new float[2]));
    }
}
