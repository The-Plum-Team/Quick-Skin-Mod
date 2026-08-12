package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeElytraSilhouetteTest {

    @Test
    void oneXMaskMatchesTheVanillaOuterWingRows() {
        BufferedImage frame = opaque(64, 32);

        assertTrue(CapeElytraSilhouette.applyToFrame(frame, 0, 32));

        for (int row = 0; row < 20; row++) {
            int start = CapeElytraSilhouette.wingStartColumn(row);
            int end = CapeElytraSilhouette.wingEndColumn(row);
            for (int column = 0; column < 10; column++) {
                int pixel = frame.getRGB(36 + column, 2 + row);
                assertEquals(column >= start && column < end ? 0xff117788 : 0x00000000,
                        pixel, "outer wing row=" + row + " column=" + column);
            }
        }
        assertEquals(0xff117788, frame.getRGB(35, 2), "adjacent right side changed");
        assertEquals(0xff117788, frame.getRGB(1, 1), "cape body changed");
        assertTrue(CapeElytraSilhouette.hasRequiredCutout(frame, 1));
    }

    @Test
    void maskScalesByPixelBlocksWithoutTouchingVisiblePixels() {
        BufferedImage frame = opaque(256, 128);

        CapeElytraSilhouette.applyToFrame(frame, 0, 128);

        for (int y = 8; y < 12; y++) {
            for (int x = 180; x < 184; x++) {
                assertEquals(0x00000000, frame.getRGB(x, y));
            }
            for (int x = 144; x < 148; x++) {
                assertEquals(0xff117788, frame.getRGB(x, y));
            }
        }
        assertTrue(CapeElytraSilhouette.hasRequiredCutout(frame, 1));
    }

    @Test
    void validAtlasKeepsObjectIdentity() {
        BufferedImage frame = opaque(64, 32);
        CapeElytraSilhouette.applyToFrame(frame, 0, 32);

        assertSame(frame, CapeElytraSilhouette.maskedCopy(frame, 1));
    }

    @Test
    void presentationMayChangeOnlyTheRequiredCutout() {
        BufferedImage source = opaque(64, 32);
        BufferedImage presented = CapeElytraSilhouette.maskedCopy(source, 1);

        assertTrue(CapeElytraSilhouette.isExactMaskedPresentation(source, presented, 1));

        presented.setRGB(1, 1, 0xff000000);
        assertFalse(CapeElytraSilhouette.isExactMaskedPresentation(source, presented, 1));
    }

    @Test
    void opaqueFillContractAllowsOnlyTheStructuralCutout() {
        BufferedImage atlas = opaque(64, 64);
        CapeElytraSilhouette.applyToFrame(atlas, 0, 32);
        CapeElytraSilhouette.applyToFrame(atlas, 32, 32);

        assertTrue(CapeElytraSilhouette.isOpaqueExceptWingCutout(atlas, 2));

        atlas.setRGB(2, 3, 0x00117788);
        assertFalse(CapeElytraSilhouette.isOpaqueExceptWingCutout(atlas, 2));
    }

    @Test
    void malformedLayoutsFailClosedWithoutMutation() {
        BufferedImage malformed = opaque(65, 32);

        assertFalse(CapeElytraSilhouette.hasRequiredCutout(malformed, 1));
        assertSame(malformed, CapeElytraSilhouette.maskedCopy(malformed, 1));
        assertFalse(CapeElytraSilhouette.applyToFrame(malformed, 0, 32));
    }

    @Test
    void publicWingRowAccessRejectsInvalidIndexes() {
        assertThrows(IllegalArgumentException.class,
                () -> CapeElytraSilhouette.wingStartColumn(-1));
        assertThrows(IllegalArgumentException.class,
                () -> CapeElytraSilhouette.wingEndColumn(20));
    }

    private static BufferedImage opaque(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xff117788);
            }
        }
        return image;
    }
}
