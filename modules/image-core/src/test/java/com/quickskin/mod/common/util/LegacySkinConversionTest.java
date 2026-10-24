package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A 64x32 skin gains its left arm and leg exactly as vanilla's {@code HttpTexture.processLegacySkin}
 * builds them: every face of the right limb, mirrored, the soles and the ends of the hands too.
 */
class LegacySkinConversionTest {
    /**
     * Vanilla's twelve {@code copyRect(x, y, dx, dy, width, height, mirrorX=true, mirrorY=false)}
     * calls, in 64x32 texels: the right leg's and the right arm's six faces onto the left limbs.
     */
    private static final int[][] VANILLA_COPIES = {
            {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4},
            {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12}, {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12},
            {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
            {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12},
    };

    private static BufferedImage randomLegacySkin(int scale, long seed) {
        Random random = new Random(seed);
        BufferedImage skin = new BufferedImage(64 * scale, 32 * scale, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < skin.getHeight(); y++) {
            for (int x = 0; x < skin.getWidth(); x++) {
                skin.setRGB(x, y, 0xFF000000 | random.nextInt(0x1000000));
            }
        }
        return skin;
    }

    /** What vanilla shows on a left limb's face, scaled for an HD skin. */
    private static void assertVanillaLeftLimbs(BufferedImage legacy, BufferedImage modern, int scale) {
        for (int[] copy : VANILLA_COPIES) {
            int x0 = copy[0] * scale;
            int y0 = copy[1] * scale;
            int w = copy[4] * scale;
            int h = copy[5] * scale;
            int dx = copy[2] * scale;
            int dy = copy[3] * scale;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int expected = legacy.getRGB(x0 + x, y0 + y);
                    int actual = modern.getRGB(x0 + dx + (w - 1 - x), y0 + dy + y);
                    assertEquals(expected, actual, String.format("face copied from (%d,%d) at texel (%d,%d), scale %d",
                            copy[0], copy[1], x, y, scale));
                }
            }
        }
    }

    @Test
    void theLeftLimbsHaveEveryFaceVanillaGivesThem() {
        BufferedImage legacy = randomLegacySkin(1, 42);
        BufferedImage modern = HDTextureProcessor.convertLegacyToModern(legacy);

        assertEquals(64, modern.getWidth());
        assertEquals(64, modern.getHeight());
        assertVanillaLeftLimbs(legacy, modern, 1);
    }

    @Test
    void anHdLegacySkinIsConvertedTheSameWayAtItsScale() {
        BufferedImage legacy = randomLegacySkin(2, 7);
        BufferedImage modern = HDTextureProcessor.convertLegacyToModern(legacy);

        assertEquals(128, modern.getWidth());
        assertVanillaLeftLimbs(legacy, modern, 2);
    }

    @Test
    void theSolesAndHandEndsOfTheLeftLimbsAreOpaque() {
        // Notch's skin and every other opaque 64x32 skin: no hole under the left foot or hand
        BufferedImage modern = HDTextureProcessor.convertLegacyToModern(randomLegacySkin(1, 3));
        for (int[] bottom : new int[][] {{24, 48}, {40, 48}}) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(0xFF, modern.getRGB(bottom[0] + x, bottom[1] + y) >>> 24,
                            "bottom face texel (" + (bottom[0] + x) + "," + (bottom[1] + y) + ")");
                }
            }
        }
    }
}
