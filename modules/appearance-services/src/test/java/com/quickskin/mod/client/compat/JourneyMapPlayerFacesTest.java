package com.quickskin.mod.client.compat;

import org.junit.jupiter.api.Test;

import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The JourneyMap face is the skin's head front with its hat layer on top, scaled to the 24x24
 * image JourneyMap draws.
 */
class JourneyMapPlayerFacesTest {

    private static final int HEAD = 0xFF0000FF;
    private static final int HAT = 0xFF00FF00;
    private static final int ELSEWHERE = 0xFFFF0000;

    @Test
    void anOpaqueHatCoversTheHead() {
        int[] face = JourneyMapPlayerFaces.composeFace(64, 64, skin(64, HAT));

        assertEquals(24 * 24, face.length);
        for (int pixel : face) {
            assertEquals(HAT, pixel);
        }
    }

    @Test
    void aTransparentHatShowsTheHead() {
        int[] face = JourneyMapPlayerFaces.composeFace(64, 64, skin(64, 0x00000000));

        for (int pixel : face) {
            assertEquals(HEAD, pixel);
        }
    }

    @Test
    void aLegacySkinStillHasItsFace() {
        int[] face = JourneyMapPlayerFaces.composeFace(64, 32, skin(64, 0x00000000));

        assertEquals(24 * 24, face.length);
        assertEquals(HEAD, face[0]);
    }

    @Test
    void anHdSkinIsReadFromItsScaledHead() {
        int[] face = JourneyMapPlayerFaces.composeFace(128, 128, skin(128, 0x00000000));

        assertEquals(24 * 24, face.length);
        for (int pixel : face) {
            assertEquals(HEAD, pixel);
        }
    }

    @Test
    void theFaceIsReadFromTheFrontOfTheHeadOnly() {
        // Mark two opposite corners of the head front; each fills its 3x3 corner of the face.
        IntBinaryOperator skin = (x, y) -> {
            if (x == 8 && y == 8) return 0xFF111111;
            if (x == 15 && y == 15) return 0xFF222222;
            return skin(64, 0x00000000).applyAsInt(x, y);
        };

        int[] face = JourneyMapPlayerFaces.composeFace(64, 64, skin);

        assertEquals(0xFF111111, face[0]);
        assertEquals(0xFF111111, face[2 * 24 + 2]);
        assertEquals(HEAD, face[3]);
        assertEquals(0xFF222222, face[24 * 24 - 1]);
        assertEquals(0xFF222222, face[21 * 24 + 21]);
    }

    @Test
    void anImageTooSmallForASkinHasNoFace() {
        assertNull(JourneyMapPlayerFaces.composeFace(32, 32, (x, y) -> HEAD));
        assertNull(JourneyMapPlayerFaces.composeFace(64, 16, (x, y) -> HEAD));
    }

    @Test
    void aHalfTransparentHatBlendsOverTheHead() {
        int halfWhite = 0x80FFFFFF;
        int black = 0xFF000000;

        int blended = JourneyMapPlayerFaces.over(halfWhite, black);

        assertEquals(0xFF, blended >>> 24);
        int channel = blended & 0xFF;
        assertEquals(channel, (blended >>> 8) & 0xFF);
        assertEquals(channel, (blended >>> 16) & 0xFF);
        assertEquals(128, channel, 1);
    }

    @Test
    void transparentPixelsBlendToTransparent() {
        assertEquals(0, JourneyMapPlayerFaces.over(0, 0));
        assertEquals(HEAD, JourneyMapPlayerFaces.over(0, HEAD));
    }

    /** A skin whose head front is {@link #HEAD}, hat front {@code hat}, and anything else red. */
    private static IntBinaryOperator skin(int width, int hat) {
        int scale = width / 64;
        return (x, y) -> {
            boolean faceRow = y >= 8 * scale && y < 16 * scale;
            if (faceRow && x >= 8 * scale && x < 16 * scale) return HEAD;
            if (faceRow && x >= 40 * scale && x < 48 * scale) return hat;
            return ELSEWHERE;
        };
    }
}
