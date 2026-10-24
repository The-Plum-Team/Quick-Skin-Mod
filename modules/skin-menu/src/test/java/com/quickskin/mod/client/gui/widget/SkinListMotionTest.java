package com.quickskin.mod.client.gui.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every animation of the skin column starts where it should and ends at rest. */
class SkinListMotionTest {
    private static final int TILE = 32;
    private static final int CARD_WIDTH = 140;
    private static final int CARD_HEIGHT = 42;

    @Test
    void anAddedTileDropsFromAboveAndLandsInItsPlace() {
        SkinListMotion.Pose start = SkinListMotion.arrival(false, 0, TILE, TILE);
        assertTrue(start.dy() <= -TILE, "starts a tile above its place: " + start);
        assertEquals(0, start.dx());
        assertTrue(SkinListMotion.arrival(false, SkinListMotion.ARRIVAL_MILLIS, TILE, TILE).isRest());
        // It bounces: on the way it touches its place and leaves it again
        boolean landed = false;
        boolean bouncedAfter = false;
        for (long t = 0; t <= SkinListMotion.ARRIVAL_MILLIS; t += 5) {
            float dy = SkinListMotion.arrival(false, t, TILE, TILE).dy();
            if (Math.abs(dy) < 0.5f) {
                landed = true;
            } else if (landed) {
                bouncedAfter = true;
            }
            assertTrue(dy <= 0, "never below its place");
        }
        assertTrue(bouncedAfter);
    }

    @Test
    void anAddedCardSlidesInFromBeyondTheLeftEdge() {
        SkinListMotion.Pose start = SkinListMotion.arrival(true, 0, CARD_WIDTH, CARD_HEIGHT);
        assertTrue(start.dx() <= -CARD_WIDTH, "starts outside the column: " + start);
        assertEquals(0, start.dy());
        float half = SkinListMotion.arrival(true, SkinListMotion.ARRIVAL_MILLIS / 2, CARD_WIDTH, CARD_HEIGHT).dx();
        assertTrue(half > start.dx() && half < 0);
        assertTrue(SkinListMotion.arrival(true, SkinListMotion.ARRIVAL_MILLIS, CARD_WIDTH, CARD_HEIGHT).isRest());
    }

    @Test
    void aDeletedSkinHopsThenFallsTippingOver() {
        assertTrue(SkinListMotion.departure(100).dy() < 0, "hops up first");
        SkinListMotion.Pose later = SkinListMotion.departure(600);
        assertTrue(later.dy() > TILE, "then falls well below its place: " + later);
        assertTrue(later.degrees() > 0);
        assertTrue(SkinListMotion.departure(SkinListMotion.DEPARTURE_MAX_MILLIS).dy() > 1000,
                "out of any column before it stops being drawn");
    }

    @Test
    void aNewFavoriteFlashesSwellsAndSettles() {
        assertTrue(SkinListMotion.favoriteFlash(0) > 0.5f);
        assertTrue(SkinListMotion.favorite(0).scale() > 1f);
        assertEquals(0f, SkinListMotion.favoriteFlash(SkinListMotion.FAVORITE_MILLIS));
        assertTrue(SkinListMotion.favorite(SkinListMotion.FAVORITE_MILLIS).isRest());
        assertEquals(0f, SkinListMotion.favoriteFrameAlpha(SkinListMotion.FAVORITE_FRAME_MILLIS));
        assertTrue(SkinListMotion.favoriteFrameGrowth(300) > SkinListMotion.favoriteFrameGrowth(0));
        // The frame and the swell of the widest entry, a list card, stay inside the room the list
        // leaves for them, so neither is cut by its edges
        for (long t = 0; t <= SkinListMotion.FAVORITE_FRAME_MILLIS; t += 5) {
            if (SkinListMotion.favoriteFrameAlpha(t) > 0) {
                assertTrue(SkinListMotion.favoriteFrameGrowth(t) <= SkinListMotion.FAVORITE_OVERFLOW, "frame at " + t);
            }
            float swell = (SkinListMotion.favorite(t).scale() - 1) * CARD_WIDTH / 2;
            assertTrue(swell + SkinListMotion.favoriteFrameGrowth(t) * SkinListMotion.favorite(t).scale()
                    <= SkinListMotion.FAVORITE_OVERFLOW + 1, "swell at " + t);
        }
        // The star pops past its size, then settles at it
        assertEquals(0f, SkinListMotion.starScale(0), 1e-6);
        float peak = 0;
        for (long t = 0; t <= SkinListMotion.FAVORITE_MILLIS; t += 5) {
            peak = Math.max(peak, SkinListMotion.starScale(t));
        }
        assertTrue(peak > 1.1f);
        assertEquals(1f, SkinListMotion.starScale(SkinListMotion.FAVORITE_MILLIS), 1e-6);
    }

    @Test
    void aGlideEndsInPlaceAndClosingAGapWaitsAndOvershoots() {
        assertEquals(36f, SkinListMotion.glide(36f, 0, false));
        assertEquals(0f, SkinListMotion.glide(36f, SkinListMotion.GLIDE_MILLIS, false), 1e-4);
        assertTrue(SkinListMotion.glideDone(SkinListMotion.GLIDE_MILLIS, false));

        // Behind a deleted skin: still for a moment, then past its place and back
        assertEquals(36f, SkinListMotion.glide(36f, SkinListMotion.GAP_DELAY_MILLIS, true));
        boolean overshot = false;
        for (long t = SkinListMotion.GAP_DELAY_MILLIS; t <= SkinListMotion.GAP_DELAY_MILLIS + SkinListMotion.GLIDE_MILLIS; t += 5) {
            overshot |= SkinListMotion.glide(36f, t, true) < -0.5f;
        }
        assertTrue(overshot);
        assertFalse(SkinListMotion.glideDone(SkinListMotion.GLIDE_MILLIS, true));
        long end = SkinListMotion.GAP_DELAY_MILLIS + SkinListMotion.GLIDE_MILLIS;
        assertEquals(0f, SkinListMotion.glide(36f, end, true), 1e-4);
        assertTrue(SkinListMotion.glideDone(end, true));
    }
}
