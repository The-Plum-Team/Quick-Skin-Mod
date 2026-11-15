package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class JpegOrientationTest {

    @Test
    void turnsTheStoredPixelsTheWayEachOrientationIsShown() {
        // Stored as   a b c
        //             d e f
        assertShown(1, "abc", "def");
        assertShown(2, "cba", "fed");
        assertShown(3, "fed", "cba");
        assertShown(4, "def", "abc");
        assertShown(5, "ad", "be", "cf");
        assertShown(6, "da", "eb", "fc");
        assertShown(7, "fc", "eb", "da");
        assertShown(8, "cf", "be", "ad");
    }

    @Test
    void anUprightImageIsReturnedAsItIs() {
        BufferedImage stored = stored();

        assertSame(stored, JpegOrientation.upright(stored, 1));
        assertSame(stored, JpegOrientation.upright(stored, 0));
        assertSame(stored, JpegOrientation.upright(stored, 9));
    }

    @Test
    void aGreyscaleImageKeepsItsRawSamplesWhenItIsTurned() {
        BufferedImage grey = new BufferedImage(2, 1, BufferedImage.TYPE_BYTE_GRAY);
        grey.getRaster().setSample(0, 0, 0, 128);
        grey.getRaster().setSample(1, 0, 0, 20);

        BufferedImage shown = JpegOrientation.upright(grey, 6);

        // getRGB would read grey 128 as 188
        assertEquals(1, shown.getWidth());
        assertEquals(2, shown.getHeight());
        assertEquals(0xFF808080, shown.getRGB(0, 0));
        assertEquals(0xFF141414, shown.getRGB(0, 1));
    }

    private static void assertShown(int orientation, String... rows) {
        BufferedImage shown = JpegOrientation.upright(stored(), orientation);

        assertEquals(rows[0].length(), shown.getWidth(), "width of orientation " + orientation);
        assertEquals(rows.length, shown.getHeight(), "height of orientation " + orientation);
        for (int y = 0; y < rows.length; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < shown.getWidth(); x++) {
                row.append((char) ('a' + (shown.getRGB(x, y) & 0xFF)));
            }
            assertEquals(rows[y], row.toString(), "row " + y + " of orientation " + orientation);
        }
    }

    /** A 3x2 image whose pixels, in reading order, hold 0 to 5 in their blue channel. */
    private static BufferedImage stored() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        for (int index = 0; index < 6; index++) {
            image.setRGB(index % 3, index / 3, index);
        }
        return image;
    }
}
