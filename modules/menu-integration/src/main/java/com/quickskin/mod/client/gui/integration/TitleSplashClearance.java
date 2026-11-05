package com.quickskin.mod.client.gui.integration;

/**
 * Sizes the title-screen preview so that, where it stands by default, it stays below the
 * Minecraft logo and the yellow splash text.
 *
 * <p>The preview stands beside the Quit Game row, so how close its head comes to the splash
 * depends on the window and the GUI scale: a short GUI height brings that row up under the logo.
 * The preview keeps its usual size wherever that fits and shrinks only as far as needed.
 *
 * <p>The geometry is vanilla's, the same in every supported version (1.20.1 to 26.3):
 * {@code SplashRenderer} draws the splash centred on {@code (width / 2 + 123, 69)}, turned by
 * -20 degrees and scaled by at most {@code 1.8 * 100 / (textWidth + 32)}, its text spanning
 * {@code -8..1} around that point; {@code LogoRenderer} draws the 256x44 logo at y 30 and the
 * 128x14 edition 7 pixels into it. The splash itself is unknown here, so every text width is
 * assumed: the result never depends on which splash happens to show.
 *
 * <p>Deliberately free of Minecraft types, so the rule is unit testable.
 */
final class TitleSplashClearance {
    static final int SPLASH_OFFSET_X = 123;
    static final int SPLASH_Y = 69;
    static final int LOGO_HALF_WIDTH = 128;
    static final int LOGO_BOTTOM = 30 + 44;
    static final int EDITION_HALF_WIDTH = 64;
    static final int EDITION_BOTTOM = 30 + 44 - 7 + 14;
    /** Space left between the obstacle and the top of the preview's box. */
    static final int MARGIN = 2;

    private static final double ANGLE = Math.toRadians(-20.0);
    private static final double COS = Math.cos(ANGLE);
    private static final double SIN = Math.sin(ANGLE);
    private static final int MAX_TEXT_WIDTH = 512;
    /** Columns relative to the splash centre that any splash can reach. */
    private static final int PROFILE_REACH = 100;
    /** For each column, the lowest y (relative to the splash centre) any splash reaches; MIN_VALUE where none does. */
    private static final int[] SPLASH_PROFILE = splashProfile();

    private TitleSplashClearance() {
    }

    /**
     * The lowest GUI y that the logo or, when shown, the splash reaches over the columns
     * {@code [left, right)}; {@link Integer#MIN_VALUE} when nothing is drawn there.
     */
    static int obstacleBottom(int screenWidth, int left, int right, boolean splashShown) {
        int centre = screenWidth / 2;
        int bottom = Integer.MIN_VALUE;
        if (left < centre + LOGO_HALF_WIDTH && right > centre - LOGO_HALF_WIDTH) {
            bottom = LOGO_BOTTOM;
        }
        if (left < centre + EDITION_HALF_WIDTH && right > centre - EDITION_HALF_WIDTH) {
            bottom = Math.max(bottom, EDITION_BOTTOM);
        }
        if (splashShown) {
            int splashX = centre + SPLASH_OFFSET_X;
            int from = Math.max(left - splashX, -PROFILE_REACH);
            int to = Math.min(right - splashX, PROFILE_REACH + 1);
            for (int column = from; column < to; column++) {
                int reach = SPLASH_PROFILE[column + PROFILE_REACH];
                if (reach != Integer.MIN_VALUE) {
                    bottom = Math.max(bottom, SPLASH_Y + reach);
                }
            }
        }
        return bottom;
    }

    /**
     * The largest scale, at most {@code preferred} and at least {@code minimum}, at which a preview
     * standing at {@code (centerX, feetY)} keeps its box below the logo and the splash.
     *
     * <p>The box is the preview's drawn model box: {@code 2 * scale} high and 60 % of that wide,
     * centred on {@code centerX} with the feet on its bottom edge. Narrowing the box as it shrinks
     * can move it out from under the splash, so each candidate is tested with its own width.
     */
    static float clearScale(int screenWidth, int centerX, int feetY, float preferred, float minimum,
                            boolean splashShown) {
        for (float scale = preferred; scale > minimum; scale -= 0.5f) {
            if (fits(screenWidth, centerX, feetY, scale, splashShown)) {
                return scale;
            }
        }
        return minimum;
    }

    private static boolean fits(int screenWidth, int centerX, int feetY, float scale, boolean splashShown) {
        int height = (int) (scale * 2.0f);
        int width = (int) (height * 0.6f);
        int left = centerX - width / 2;
        int bottom = obstacleBottom(screenWidth, left, left + width, splashShown);
        return bottom == Integer.MIN_VALUE || feetY - height >= bottom + MARGIN;
    }

    private static int[] splashProfile() {
        int[] profile = new int[2 * PROFILE_REACH + 1];
        java.util.Arrays.fill(profile, Integer.MIN_VALUE);
        for (int textWidth = 1; textWidth <= MAX_TEXT_WIDTH; textWidth++) {
            double scale = 1.8 * 100.0 / (textWidth + 32);
            double halfWidth = textWidth / 2.0 * scale;
            double top = -8.0 * scale;
            double bottom = 1.0 * scale;
            // The lowest point of a column of a convex shape lies on its outline.
            for (double x = -halfWidth; x <= halfWidth; x += 0.25) {
                mark(profile, x, top);
                mark(profile, x, bottom);
            }
            mark(profile, halfWidth, top);
            mark(profile, halfWidth, bottom);
            for (double y = top; y <= bottom; y += 0.25) {
                mark(profile, -halfWidth, y);
                mark(profile, halfWidth, y);
            }
        }
        return profile;
    }

    private static void mark(int[] profile, double localX, double localY) {
        double x = localX * COS - localY * SIN;
        double y = localX * SIN + localY * COS;
        int lowest = (int) Math.ceil(y);
        // A point can sit on a column boundary: count it in both neighbours.
        for (int column = (int) Math.floor(x) - 1; column <= (int) Math.floor(x) + 1; column++) {
            int index = column + PROFILE_REACH;
            if (index >= 0 && index < profile.length) {
                profile[index] = Math.max(profile[index], lowest);
            }
        }
    }
}
