package com.quickskin.mod.client.gui.widget;

/**
 * Maps a preview between its model placement (centre X, feet Y, scale) and its layout rectangle.
 *
 * <p>The layout rectangle is the drawn model box: {@code 2 * scale} high and 60 % of that wide, with
 * the feet on its bottom edge. A layout tool such as FancyMenu may move or resize that rectangle;
 * {@link #place} turns whatever rectangle it reports back into a placement. An unchanged rectangle
 * gives back exactly the placement it was built from, so a preview nobody moved draws as before.
 *
 * <p>Pure Java with no Minecraft types, so it is unit-tested directly.
 */
final class PreviewLayoutBox {
    /** The configured preview size range: 1 % maps to {@code MIN_SCALE}, 100 % to {@code MAX_SCALE}. */
    static final float MIN_SCALE = 20.0f;
    static final float MAX_SCALE = 200.0f;
    /** Bounds for a scale derived from a rectangle set by a layout tool. */
    static final float MIN_LAYOUT_SCALE = 4.0f;
    static final float MAX_LAYOUT_SCALE = 600.0f;

    record Box(int x, int y, int width, int height) {
    }

    record Placement(int centerX, int feetY, float scale) {
    }

    private PreviewLayoutBox() {
    }

    /** Converts the configured size percentage (1..100) to the model scale. */
    static float scaleForPercentage(int percentage) {
        float percentageAsFloat = (percentage - 1) / 99.0f; // Convert 1-100 to 0.0-1.0
        return MIN_SCALE + percentageAsFloat * (MAX_SCALE - MIN_SCALE);
    }

    /** The drawn model box; the same arithmetic as the preview's debug border and hit area. */
    static Box natural(int centerX, int feetY, float scale) {
        int height = (int) (scale * 2.0f);
        int width = (int) (height * 0.6f);
        return new Box(centerX - width / 2, feetY - height, width, height);
    }

    /**
     * Where to draw the model inside the rectangle a layout tool reports.
     *
     * <p>The model stands on the rectangle's bottom edge, centred horizontally, as large as fits.
     * A rectangle with the natural size keeps the natural scale, so moving the box never rescales
     * the model. A non-positive size means the tool reports no size, and the natural one is used.
     */
    static Placement place(Box natural, float naturalScale, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            width = natural.width();
            height = natural.height();
        }
        float scale = width == natural.width() && height == natural.height()
                ? naturalScale
                : clamp(Math.min(height / 2.0f, width / 1.2f));
        return new Placement(x + width / 2, y + height, scale);
    }

    private static float clamp(float scale) {
        return Math.max(MIN_LAYOUT_SCALE, Math.min(MAX_LAYOUT_SCALE, scale));
    }
}
