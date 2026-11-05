package com.quickskin.mod.client.gui.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleSplashClearanceTest {

    // The title preview's default size (30 %) and the smallest configurable one (1 %).
    private static final float DEFAULT_SCALE = 20.0f + 29.0f / 99.0f * 180.0f;
    private static final float MIN_SCALE = 20.0f;

    /** Where 1.20.1 stands the preview by default: beside Quit Game, feet 4 above it, centred on the button. */
    private static int centerX(int width) {
        return width / 2 + 128 - 23 + 72;
    }

    private static int feetY(int height) {
        return height / 4 + 48 + 84 - 86 + 82;
    }

    @Test
    void aShortGuiShrinksThePreviewUnderTheSplash() {
        // 1920x1080 at GUI scale 4.
        float scale = TitleSplashClearance.clearScale(480, centerX(480), feetY(270), DEFAULT_SCALE, MIN_SCALE, true);

        assertTrue(scale < DEFAULT_SCALE, "scale " + scale);
        assertClearOfEverySplash(480, centerX(480), feetY(270), scale);
    }

    @Test
    void aTallGuiKeepsTheConfiguredSize() {
        // 1920x1080 at GUI scale 2.
        assertEquals(DEFAULT_SCALE,
                TitleSplashClearance.clearScale(960, centerX(960), feetY(540), DEFAULT_SCALE, MIN_SCALE, true));
    }

    @Test
    void hiddenSplashTextsLeaveOnlyTheLogo() {
        // Right of the logo, so nothing above the preview is left to avoid.
        assertEquals(DEFAULT_SCALE,
                TitleSplashClearance.clearScale(480, centerX(480), feetY(270), DEFAULT_SCALE, MIN_SCALE, false));
    }

    @Test
    void aWindowTooShortForAnySizeKeepsTheSmallest() {
        assertEquals(MIN_SCALE,
                TitleSplashClearance.clearScale(480, centerX(480), 80, DEFAULT_SCALE, MIN_SCALE, true));
    }

    @Test
    void theLogoCountsUnderItsWholeWidth() {
        assertEquals(TitleSplashClearance.LOGO_BOTTOM,
                TitleSplashClearance.obstacleBottom(480, 240 + 100, 240 + 110, false));
        assertEquals(Integer.MIN_VALUE,
                TitleSplashClearance.obstacleBottom(480, 240 + 128, 240 + 140, false));
    }

    /** Checks the box against the four corners of the splash as drawn for a range of text widths. */
    private static void assertClearOfEverySplash(int width, int centerX, int feetY, float scale) {
        int height = (int) (scale * 2.0f);
        int boxWidth = (int) (height * 0.6f);
        int left = centerX - boxWidth / 2;
        int top = feetY - height;
        double cos = Math.cos(Math.toRadians(-20));
        double sin = Math.sin(Math.toRadians(-20));
        for (int textWidth = 4; textWidth <= 300; textWidth++) {
            double s = 180.0 / (textWidth + 32);
            for (double lx = -textWidth / 2.0 * s; lx <= textWidth / 2.0 * s; lx += 0.5) {
                for (double ly : new double[] {-8 * s, s}) {
                    double x = width / 2.0 + 123 + lx * cos - ly * sin;
                    double y = 69 + lx * sin + ly * cos;
                    if (x >= left && x < left + boxWidth) {
                        assertTrue(y < top, "splash of width " + textWidth + " reaches " + y + " over a box at " + top);
                    }
                }
            }
        }
    }
}
