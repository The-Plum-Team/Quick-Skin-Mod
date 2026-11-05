package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAlphaDetectorPixelScanTest {

    private static BufferedImage opaque(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF336699);
            }
        }
        return image;
    }

    @Test
    void anOpaqueImageHasNoTransparentPixels() {
        assertFalse(TextureAlphaDetector.hasTransparentPixels(opaque(128, 128)));
    }

    @Test
    void aSingleTranslucentPixelOffAnyGridIsFound() {
        // A 128x128 skin was sampled every 4th texel; (3, 5) lay between the sampled points.
        BufferedImage image = opaque(128, 128);
        image.setRGB(3, 5, 0x80336699);
        assertTrue(TextureAlphaDetector.hasTransparentPixels(image));

        BufferedImage last = opaque(64, 64);
        last.setRGB(63, 63, 0xFE336699);
        assertTrue(TextureAlphaDetector.hasTransparentPixels(last));
    }
}
