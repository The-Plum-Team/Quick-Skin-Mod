package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 3D head-and-shoulders picture of the skin menu's list rows. */
class SkinBustRasterizerTest {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;

    /** A 64x64 skin: grey everywhere the base layer has texels, the head's front red, its top green. */
    private static int[] skin(int scale) {
        int size = 64 * scale;
        int[] pixels = new int[size * size];
        fill(pixels, size, scale, 0, 0, 32, 16, BLUE);       // head, every side
        fill(pixels, size, scale, 8, 8, 8, 8, RED);          // head front
        fill(pixels, size, scale, 8, 0, 8, 8, GREEN);        // head top
        fill(pixels, size, scale, 16, 16, 24, 16, 0xFF808080); // body
        fill(pixels, size, scale, 40, 16, 16, 16, 0xFF808080); // right arm
        fill(pixels, size, scale, 32, 48, 16, 16, 0xFF808080); // left arm
        return pixels;
    }

    private static void fill(int[] pixels, int size, int scale, int u, int v, int w, int h, int color) {
        for (int y = v * scale; y < (v + h) * scale; y++) {
            for (int x = u * scale; x < (u + w) * scale; x++) {
                pixels[y * size + x] = color;
            }
        }
    }

    private static boolean nearlyRed(int argb) {
        return (argb & 0xFF00FFFF) == 0xFF000000 && ((argb >> 16) & 0xFF) > 0xE0;
    }

    @Test
    void drawsTheLitFrontAndShadedSidesOfTheHead() {
        int[] bust = SkinBustRasterizer.render(skin(1), 64, 64, false, 120, 99);
        assertEquals(120 * 99, bust.length);
        // The front faces the light: its texels keep nearly all their colour
        assertTrue(Arrays.stream(bust).anyMatch(SkinBustRasterizerTest::nearlyRed), "the head's front");
        // The top and the side are seen too, each in its own shade: the bust is drawn in 3D
        assertTrue(Arrays.stream(bust).anyMatch(p -> (p & 0xFFFF00FF) == 0xFF000000 && ((p >> 8) & 0xFF) > 0x40),
                "the head's top");
        assertTrue(Arrays.stream(bust).anyMatch(p -> (p & 0xFFFFFF00) == 0xFF000000 && (p & 0xFF) > 0x40
                && (p & 0xFF) < 0xFF), "a shaded side of the head");
    }

    @Test
    void drawsTheWholePlayerStandingOnTheBottomEdge() {
        int[] skin = skin(1);
        fill(skin, 64, 1, 0, 16, 16, 16, GREEN);  // right leg
        fill(skin, 64, 1, 16, 48, 16, 16, GREEN); // left leg
        int width = 64, height = 128;
        int[] body = SkinBustRasterizer.renderBody(skin, 64, 64, false, width, height);
        assertEquals(width * height, body.length);
        assertTrue(Arrays.stream(body).anyMatch(SkinBustRasterizerTest::nearlyRed), "the head's front");
        // The legs stand on the bottom rows and the head reaches near the top
        boolean feet = false, head = false;
        for (int x = 0; x < width; x++) {
            int low = body[(height - 4) * width + x];
            feet |= (low >>> 24) != 0 && ((low >> 8) & 0xFF) > 0x40 && (low & 0xFF0000) == 0;
            head |= (body[6 * width + x] >>> 24) != 0;
        }
        assertTrue(feet, "the legs at the bottom");
        assertTrue(head, "the head at the top");
        assertEquals(0, body[0] >>> 24, "a transparent corner");
    }

    @Test
    void turnsTheStandingPlayerAboutItsUprightAxis() {
        int[] skin = skin(1);
        int width = 64, height = 128;
        assertArrayEquals(SkinBustRasterizer.renderBody(skin, 64, 64, false, width, height),
                SkinBustRasterizer.renderBody(skin, 64, 64, false, width, height, SkinBustRasterizer.DEFAULT_YAW));
        // Turned from the light the face is darker, but still red
        int[] turned = SkinBustRasterizer.renderBody(skin, 64, 64, false, width, height, 60);
        assertTrue(Arrays.stream(turned).anyMatch(SkinBustRasterizerTest::red), "the face, turned further");
        int[] back = SkinBustRasterizer.renderBody(skin, 64, 64, false, width, height, 180);
        assertFalse(Arrays.stream(back).anyMatch(SkinBustRasterizerTest::red), "the face, turned away");
    }

    private static boolean red(int argb) {
        return (argb & 0xFF00FFFF) == 0xFF000000 && ((argb >> 16) & 0xFF) > 0x30;
    }

    @Test
    void leavesTheCornersAroundTheBustTransparent() {
        int[] bust = SkinBustRasterizer.render(skin(1), 64, 64, false, 120, 99);
        assertEquals(0, bust[0] >>> 24, "top-left corner");
        assertEquals(0, bust[119] >>> 24, "top-right corner");
        // ...and fills the bottom edge with the chest
        assertTrue(bust[98 * 120 + 60] >>> 24 == 255, "middle of the bottom edge");
    }

    @Test
    void anHdSkinDrawsLikeTheSameSkinAtSixtyFourPixels() {
        int[] small = SkinBustRasterizer.render(skin(1), 64, 64, true, 120, 99);
        int[] hd = SkinBustRasterizer.render(skin(2), 128, 128, true, 120, 99);
        assertArrayEquals(small, hd);
    }

    @Test
    void slimArmsChangeTheSilhouette() {
        int[] wide = SkinBustRasterizer.render(skin(1), 64, 64, false, 120, 99);
        int[] slim = SkinBustRasterizer.render(skin(1), 64, 64, true, 120, 99);
        assertFalse(Arrays.equals(wide, slim));
    }

    @Test
    void acceptsTheLegacyLayout() {
        int[] legacy = Arrays.copyOf(skin(1), 64 * 32);
        int[] bust = SkinBustRasterizer.render(legacy, 64, 32, false, 60, 50);
        assertTrue(Arrays.stream(bust).anyMatch(SkinBustRasterizerTest::nearlyRed));
    }

    @Test
    void rejectsWhatIsNotASkinOrAnUnreasonableSize() {
        assertThrows(IllegalArgumentException.class, () -> SkinBustRasterizer.render(new int[48 * 48], 48, 48, false, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> SkinBustRasterizer.render(new int[64 * 64], 64, 64, false, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> SkinBustRasterizer.render(new int[64 * 64], 64, 64, false, SkinBustRasterizer.MAX_SIZE + 1, 10));
        assertThrows(IllegalArgumentException.class, () -> SkinBustRasterizer.render(new int[10], 64, 64, false, 10, 10));
    }

    @Test
    void compositesHalfTransparentTexelsOverWhatIsBehind() {
        assertEquals(0xFF000000 | 0x80 << 16 | 0x7F, SkinBustRasterizer.over(0x80FF0000, 0xFF0000FF));
        assertEquals(0xFF123456, SkinBustRasterizer.over(0xFF123456, 0xFFABCDEF));
        assertEquals(0x80FF0000, SkinBustRasterizer.over(0x80FF0000, 0));
    }
}
