package com.quickskin.mod.client.gui.screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hidden pieces lie round the ring, drop there when hidden and fly back when put on. */
class PedestalDropsTest {
    private static final float CX = 200;
    private static final float CY = 300;
    private static final float RX = 45;
    private static final float RY = 9;
    private static final float SIZE = 21;
    private static final float REACH = 150;
    private static final float[] WORN = {190, 180};

    private static float[][] places(int count) {
        float[][] places = new float[count][];
        for (int i = 0; i < count; i++) {
            places[i] = PedestalDrops.place(i, count, CX, CY, RX, RY, SIZE, REACH);
        }
        return places;
    }

    /** The places by number, as the screen gives them. */
    private static IntFunction<float[]> slots(float[][] places) {
        return slot -> places[slot];
    }

    private static int[] pieces(int... pieces) {
        return pieces;
    }

    @Test
    void theFirstTwoLieOnTheRingsFrontAndTheRestBeyondItSideBySide() {
        float[][] places = places(6);
        assertTrue(places[0][0] < CX && places[1][0] > CX, "left, then right");
        for (int i = 0; i < 2; i++) {
            float dx = (places[i][0] - CX) / RX;
            float dy = (places[i][1] - CY) / RY;
            assertEquals(1.0, dx * dx + dy * dy, 1e-3, "on the band");
            assertTrue(places[i][1] > CY, "in front of the feet");
            assertTrue(Math.abs(places[i][0] - CX) > SIZE, "clear of the feet");
        }
        for (int i = 2; i < 6; i++) {
            assertTrue(Math.abs(places[i][0] - CX) > RX, "beyond the ring: " + i);
            assertEquals(Math.signum(places[i - 2][0] - CX), Math.signum(places[i][0] - CX), "same side as two before");
            assertTrue(Math.abs(places[i][0] - places[i - 2][0]) >= SIZE, "no overlap with its neighbour");
        }
    }

    @Test
    void manyItemsMoveCloserTogetherToStayWithinReach() {
        int count = 24;
        float[][] places = places(count);
        for (float[] place : places) {
            assertTrue(Math.abs(place[0] - CX) <= REACH + 0.01f, "within reach");
        }
        for (int i = 4; i < count; i++) {
            assertTrue(Math.abs(places[i][0] - CX) > Math.abs(places[i - 2][0] - CX), "still in order outward: " + i);
        }
    }

    @Test
    void theFirstUpdateLaysEveryPieceInItsPlace() {
        PedestalDrops drops = new PedestalDrops();
        float[][] places = places(2);
        drops.update(pieces(4, 8), slots(places), SIZE, piece -> WORN, 1000);
        List<PedestalDrops.Item> items = new ArrayList<>(drops.items());
        assertEquals(2, items.size());
        for (int i = 0; i < 2; i++) {
            assertFalse(items.get(i).dropping());
            assertEquals(places[i][0], items.get(i).x, 1e-4);
            assertEquals(places[i][1], items.get(i).y, 1e-4);
        }
    }

    @Test
    void aPieceJustHiddenHopsUpOffTheModelFallsOntoItsPlaceAndBounces() {
        PedestalDrops drops = new PedestalDrops();
        drops.update(pieces(), slots(new float[0][]), SIZE, piece -> WORN, 1000);
        float[][] places = places(1);
        drops.update(pieces(8), slots(places), SIZE, piece -> WORN, 2000);
        PedestalDrops.Item item = drops.items().iterator().next();
        assertTrue(item.dropping());
        assertTrue(item.inAir());
        assertEquals(WORN[0], item.x, 1e-4);
        assertEquals(WORN[1], item.y, 1e-4);
        assertEquals(0, drops.pieceAt(item.x, item.y, SIZE), "not clickable while it falls");

        // Up first, about its own size above the model, then down
        float highest = Float.MAX_VALUE;
        for (long t = 2000; t < 2000 + PedestalDrops.DROP_MILLIS; t += 10) {
            drops.update(pieces(8), slots(places), SIZE, piece -> WORN, t);
            highest = Math.min(highest, item.y);
        }
        assertEquals(WORN[1] - SIZE * PedestalDrops.DROP_APEX, highest, 0.5, "it hops up off the model");

        drops.update(pieces(8), slots(places), SIZE, piece -> WORN, 2000 + PedestalDrops.DROP_MILLIS);
        assertFalse(item.inAir(), "on the floor");
        assertTrue(item.dropping(), "still bouncing");
        assertEquals(places[0][0], item.x, 1e-4);
        assertEquals(places[0][1], item.y, 1e-4);
        drops.update(pieces(8), slots(places), SIZE, piece -> WORN,
                2000 + PedestalDrops.DROP_MILLIS + PedestalDrops.BOUNCE_MILLIS / 2);
        assertEquals(places[0][1] - SIZE * PedestalDrops.BOUNCE_HEIGHT, item.y, 1e-3, "the first bounce");
        drops.update(pieces(8), slots(places), SIZE, piece -> WORN,
                2000 + PedestalDrops.DROP_MILLIS + PedestalDrops.BOUNCE_MILLIS + PedestalDrops.SECOND_BOUNCE_MILLIS / 2);
        assertEquals(places[0][1] - SIZE * PedestalDrops.SECOND_BOUNCE_HEIGHT, item.y, 1e-3, "a smaller second one");

        drops.update(pieces(8), slots(places), SIZE, piece -> WORN,
                2000 + PedestalDrops.DROP_MILLIS + PedestalDrops.BOUNCE_MILLIS + PedestalDrops.SECOND_BOUNCE_MILLIS);
        assertFalse(item.dropping());
        assertEquals(places[0][0], item.x, 1e-4);
        assertEquals(places[0][1], item.y, 1e-4);
        assertEquals(8, drops.pieceAt(places[0][0], places[0][1] - SIZE * PedestalDrops.LIFT, SIZE));
        assertEquals(item, drops.item(8));
        assertEquals(null, drops.item(4), "not on the floor");
    }

    @Test
    void aThrowLandsOnItsPointEvenWhenThatIsAboveWhereItStarted() {
        float[] landed = PedestalDrops.thrown(0, 100, 30, 60, 400, 400, 10);
        assertEquals(30, landed[0], 1e-3);
        assertEquals(60, landed[1], 1e-3);
        float[] down = PedestalDrops.thrown(0, 100, 30, 300, 400, 400, 10);
        assertEquals(300, down[1], 1e-3);
    }

    @Test
    void aPiecePutBackOnFliesToTheModelAndTheOthersStayWhereTheyLie() {
        PedestalDrops drops = new PedestalDrops();
        float[][] three = places(3);
        drops.update(pieces(4, 8, 2), slots(three), SIZE, piece -> WORN, 1000);
        drops.update(pieces(8, 2), slots(three), SIZE, piece -> WORN, 1016);
        assertEquals(1, drops.rises().size());
        PedestalDrops.Rise rise = drops.rises().get(0);
        assertEquals(4, rise.piece());
        float[] start = rise.at(1016);
        assertEquals(three[0][0], start[0], 1e-4);
        float[] end = rise.at(1016 + PedestalDrops.RISE_MILLIS);
        assertEquals(WORN[0], end[0], 1e-4);
        assertEquals(WORN[1], end[1], 1e-4);
        assertEquals(PedestalDrops.RISE_END_SCALE, rise.scale(1016 + PedestalDrops.RISE_MILLIS), 1e-4);
        drops.update(pieces(8, 2), slots(three), SIZE, piece -> WORN, 1016 + PedestalDrops.RISE_MILLIS);
        assertTrue(drops.rises().isEmpty(), "the rise ends at the model");
        // The pieces left on the floor have not moved: the first place is empty
        assertEquals(three[1][0], drops.item(8).x, 1e-4);
        assertEquals(three[2][0], drops.item(2).x, 1e-4);
    }

    @Test
    void aNewPieceTakesTheFirstEmptyPlaceAndMovesNoOther() {
        PedestalDrops drops = new PedestalDrops();
        float[][] three = places(3);
        drops.update(pieces(4, 8, 2), slots(three), SIZE, piece -> null, 1000);
        drops.update(pieces(4, 2), slots(three), SIZE, piece -> null, 1016);
        assertEquals(three[2][0], drops.item(2).x, 1e-4, "the third stays put");
        // A piece hidden now lies in the place the second left, before the others in the list
        drops.update(pieces(1, 4, 2), slots(three), SIZE, piece -> null, 1032);
        assertEquals(three[1][0], drops.item(1).x, 1e-4);
        assertEquals(three[0][0], drops.item(4).x, 1e-4);
        assertEquals(three[2][0], drops.item(2).x, 1e-4);
        // Another goes beyond the last
        drops.update(pieces(1, 4, 2, 8), slots(places(4)), SIZE, piece -> null, 1048);
        assertEquals(places(4)[3][0], drops.item(8).x, 1e-4);
    }

    @Test
    void aPieceHiddenAgainOnItsWayUpDropsAgain() {
        PedestalDrops drops = new PedestalDrops();
        drops.update(pieces(4), slots(places(1)), SIZE, piece -> WORN, 1000);
        drops.update(pieces(), slots(new float[0][]), SIZE, piece -> WORN, 1100);
        assertEquals(1, drops.rises().size());
        drops.update(pieces(4), slots(places(1)), SIZE, piece -> WORN, 1150);
        assertTrue(drops.rises().isEmpty());
        assertTrue(drops.items().iterator().next().dropping());
    }

    @Test
    void aLyingItemTurnsAndBobsWithinItsBounds() {
        for (long t = 0; t < PedestalDrops.TURN_MILLIS; t += 37) {
            float width = PedestalDrops.width(t, 3);
            assertTrue(width >= PedestalDrops.MIN_WIDTH - 1e-6 && width <= 1 + 1e-6, "width " + width);
            float bob = PedestalDrops.bob(t, 3);
            assertTrue(bob >= -1e-6 && bob <= 2 * PedestalDrops.BOB_HEIGHT + 1e-6, "bob " + bob);
        }
    }
}
