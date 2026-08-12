package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pixel rule behind the cape adjust screen's opaque toggle. The screen itself needs a live
 * Minecraft client, so the rule lives in a Minecraft-free class and is pinned here instead.
 */
class CapeOpaqueFillTest {

    private static final int MAGENTA = 0xFF00FF;

    // --- the fill rule ---

    @Test
    void fullyTransparentPixelsBecomeTheFill() {
        assertEquals(0xFFFF00FF, CapeOpaqueFill.flatten(0x00000000, MAGENTA));
        // The RGB carried by a fully transparent pixel is discarded, not blended.
        assertEquals(0xFFFF00FF, CapeOpaqueFill.flatten(0x00123456, MAGENTA));
    }

    @Test
    void opaquePixelsAreReturnedUntouched() {
        assertEquals(0xFF123456, CapeOpaqueFill.flatten(0xFF123456, MAGENTA));
        assertEquals(0xFFFFFFFF, CapeOpaqueFill.flatten(0xFFFFFFFF, 0x000000));
    }

    /**
     * Partial alpha composites toward the fill rather than merely having its alpha byte forced to
     * 255. Bilinear resampling in the compose step routinely produces these, and hard-setting alpha
     * would leave a full-strength halo along every antialiased edge.
     */
    @Test
    void semiTransparentPixelsCompositeOverTheFill() {
        // Half-transparent white over black lands halfway.
        int halfway = CapeOpaqueFill.flatten(0x80FFFFFF, 0x000000);
        assertEquals(0xFF, (halfway >>> 24) & 0xFF);
        assertEquals(128, (halfway >>> 16) & 0xFF);
        assertEquals(128, (halfway >>> 8) & 0xFF);
        assertEquals(128, halfway & 0xFF);

        // Barely-there source is dominated by the fill, but is not exactly the fill.
        int faint = CapeOpaqueFill.flatten(0x01FFFFFF, 0x000000);
        assertEquals(0xFF010101, faint);
    }

    /**
     * A black fill zeroes the {@code fill * (255 - alpha)} half of the blend, so a black-only test
     * would pass against an implementation that ignored the fill entirely for partial alpha. These
     * cases carry a non-black fill in every channel.
     */
    @Test
    void semiTransparentPixelsBlendTowardsANonBlackFill() {
        // Half-transparent black over white: both terms contribute.
        assertEquals(0xFF7F7F7F, CapeOpaqueFill.flatten(0x80000000, 0xFFFFFF));

        // Channel-asymmetric: quarter-alpha red over blue keeps the channels independent.
        assertEquals(0xFF4000BF, CapeOpaqueFill.flatten(0x40FF0000, 0x0000FF));

        // A partial pixel over a non-black fill must not come back as the bare fill colour.
        int blended = CapeOpaqueFill.flatten(0x80FFFFFF, 0x336699);
        assertEquals(0xFF, (blended >>> 24) & 0xFF);
        assertEquals(0x99, (blended >>> 16) & 0xFF);
        assertEquals(0xB3, (blended >>> 8) & 0xFF);
        assertEquals(0xCC, blended & 0xFF);
    }

    @Test
    void everyAlphaProducesAFullyOpaqueResult() {
        for (int alpha = 0; alpha <= 0xFF; alpha++) {
            int argb = (alpha << 24) | 0x3399CC;
            int flattened = CapeOpaqueFill.flatten(argb, MAGENTA);
            assertEquals(0xFF, (flattened >>> 24) & 0xFF, "alpha " + alpha + " stayed translucent");
        }
    }

    @Test
    void compositingIsExactAtTheEndpointsForEveryChannelValue() {
        for (int channel = 0; channel <= 0xFF; channel++) {
            int source = (channel << 16) | (channel << 8) | channel;
            assertEquals(0xFF000000 | source, CapeOpaqueFill.flatten(0xFF000000 | source, MAGENTA));
            assertEquals(0xFF000000 | source, CapeOpaqueFill.flatten(0x00ABCDEF, source));
        }
    }

    @Test
    void opaqueBuildsAnOpaquePixelAndIgnoresStrayAlphaBits() {
        assertEquals(0xFF00FF00, CapeOpaqueFill.opaque(0x00FF00));
        assertEquals(0xFF00FF00, CapeOpaqueFill.opaque(0x7700FF00));
    }

    // --- channel helpers, which the RGB sliders drive ---

    @Test
    void channelsAreReadAndReplacedIndependently() {
        int color = 0x102030;
        assertEquals(0x10, CapeOpaqueFill.channel(color, 16));
        assertEquals(0x20, CapeOpaqueFill.channel(color, 8));
        assertEquals(0x30, CapeOpaqueFill.channel(color, 0));

        assertEquals(0xAA2030, CapeOpaqueFill.withChannel(color, 16, 0xAA));
        assertEquals(0x10AA30, CapeOpaqueFill.withChannel(color, 8, 0xAA));
        assertEquals(0x1020AA, CapeOpaqueFill.withChannel(color, 0, 0xAA));
    }

    @Test
    void channelWritesAreClampedIntoRange() {
        assertEquals(0xFF2030, CapeOpaqueFill.withChannel(0x102030, 16, 999));
        assertEquals(0x002030, CapeOpaqueFill.withChannel(0x102030, 16, -5));
        assertEquals(0x1020FF, CapeOpaqueFill.withChannel(0x102030, 0, 255));
    }

    @Test
    void clampRgbDropsAnythingAboveTheColourBits() {
        assertEquals(0x123456, CapeOpaqueFill.clampRgb(0xFF123456));
        assertEquals(0xFFFFFF, CapeOpaqueFill.clampRgb(-1));
    }

    // --- hex parsing, which the picker's text field drives ---

    @Test
    void hexRoundTrips() {
        assertEquals("#000000", CapeOpaqueFill.toHex(0x000000));
        assertEquals("#FF00FF", CapeOpaqueFill.toHex(MAGENTA));
        assertEquals("#123456", CapeOpaqueFill.toHex(0xAB123456));
        assertEquals(MAGENTA, CapeOpaqueFill.parseHex(CapeOpaqueFill.toHex(MAGENTA)));
    }

    @Test
    void hexParsesWithOrWithoutTheHashAndInEitherCase() {
        assertEquals(0xAABBCC, CapeOpaqueFill.parseHex("#AABBCC"));
        assertEquals(0xAABBCC, CapeOpaqueFill.parseHex("AABBCC"));
        assertEquals(0xAABBCC, CapeOpaqueFill.parseHex("aabbcc"));
        assertEquals(0xAABBCC, CapeOpaqueFill.parseHex("  #aAbBcC  "));
    }

    /** {@code -1} is what lets a live text field ignore input the user has not finished typing. */
    @Test
    void incompleteOrInvalidHexIsRejected() {
        assertEquals(-1, CapeOpaqueFill.parseHex(null));
        assertEquals(-1, CapeOpaqueFill.parseHex(""));
        assertEquals(-1, CapeOpaqueFill.parseHex("#"));
        assertEquals(-1, CapeOpaqueFill.parseHex("AABBC"));
        assertEquals(-1, CapeOpaqueFill.parseHex("AABBCCD"));
        assertEquals(-1, CapeOpaqueFill.parseHex("GGHHII"));
        assertEquals(-1, CapeOpaqueFill.parseHex("#12345Z"));
        assertEquals(-1, CapeOpaqueFill.parseHex("0xAABBCC"));
    }

    @Test
    void hexInputIsSanitizedToWhatTheParserAccepts() {
        assertEquals("", CapeOpaqueFill.sanitizeHexInput(null));
        assertEquals("AABBCC", CapeOpaqueFill.sanitizeHexInput("#aabbcc"));
        assertEquals("AABBCC", CapeOpaqueFill.sanitizeHexInput("aa-bb cc"));
        assertEquals("AABBCC", CapeOpaqueFill.sanitizeHexInput("AABBCCDDEE"));
        assertEquals("", CapeOpaqueFill.sanitizeHexInput("zzz"));
    }

    // --- the two properties the screen depends on ---

    /**
     * The screen's toggle-off path never calls into this class, so "off is unchanged" is really a
     * statement about the fill being the only thing that touches a pixel. Applying the rule to an
     * already-opaque image must therefore be the identity.
     */
    @Test
    void flatteningAnAlreadyOpaqueImageChangesNothing() {
        BufferedImage image = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, 0xFF000000 | (x * 16) << 16 | (y * 32) << 8 | 0x40);
            }
        }
        BufferedImage before = copy(image);
        flattenAll(image, MAGENTA);
        assertPixelsEqual(before, image);
    }

    /**
     * The screen applies this rule only to non-structural pixels; its final pass then restores the
     * Elytra cutout. Pin the fill rule itself exhaustively here so every pixel it receives becomes
     * opaque before that separate structural mask is applied.
     */
    @Test
    void flatteningClearsEveryTransparentPixelTheDetectorWouldFind() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                // A spread of fully transparent, partly transparent and opaque pixels.
                int alpha = (x * 8 + y) % 256;
                image.setRGB(x, y, (alpha << 24) | 0x3399CC);
            }
        }
        assertTrue(hasAnyPixelBelowFullAlpha(image), "fixture should start with transparency");

        flattenAll(image, MAGENTA);

        assertFalse(hasAnyPixelBelowFullAlpha(image),
                "every pixel should be alpha 255 after the fill");
    }

    /** {@code TextureAlphaDetector.hasTransparentPixels}' predicate, scanned exhaustively. */
    private static boolean hasAnyPixelBelowFullAlpha(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void flattenAll(BufferedImage image, int fillRgb) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, CapeOpaqueFill.flatten(image.getRGB(x, y), fillRgb));
            }
        }
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                out.setRGB(x, y, image.getRGB(x, y));
            }
        }
        return out;
    }

    private static void assertPixelsEqual(BufferedImage expected, BufferedImage actual) {
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }
}
