package com.quickskin.mod.common.util;

import java.awt.image.BufferedImage;

/**
 * Structural alpha mask for the outer Elytra face stored in a Minecraft cape atlas.
 *
 * <p>The Elytra model is a rectangular cuboid. Its familiar tapered outline comes from transparent
 * pixels in the outer 10x20 UV face, not from the model geometry. Consequently, flattening or
 * importing an otherwise fully opaque cape atlas without restoring this cutout renders each wing
 * as a rectangle. This class owns the one scale-independent mask used by import, preview, local
 * presentation, network presentation, and animation paths.</p>
 *
 * <p>Methods that normalize an atlas preserve the input object when no pixel needs changing. The
 * content-addressed source bytes can therefore remain immutable while presentation receives a
 * masked copy only when necessary.</p>
 */
public final class CapeElytraSilhouette {

    /** Visible columns for each row of the 10x20 outer wing face, end-exclusive. */
    private static final int[][] WING_ROWS = {
            {0, 6}, {0, 7}, {0, 8}, {0, 8}, {0, 8},
            {0, 9}, {0, 9}, {0, 9}, {0, 9},
            {0, 10}, {0, 10}, {0, 10}, {0, 10}, {0, 10},
            {1, 10}, {1, 10}, {1, 10},
            {2, 10}, {2, 10},
            {3, 10}
    };

    private CapeElytraSilhouette() {
    }

    public static int wingStartColumn(int row) {
        requireWingRow(row);
        return WING_ROWS[row][0];
    }

    public static int wingEndColumn(int row) {
        requireWingRow(row);
        return WING_ROWS[row][1];
    }

    /**
     * Return the original atlas when its required cutout is already transparent, otherwise an
     * exact ARGB copy with only the out-of-silhouette outer-wing pixels cleared.
     */
    public static BufferedImage maskedCopy(BufferedImage atlas, int frameCount) {
        Layout layout = layout(atlas, frameCount);
        if (layout == null || hasRequiredCutout(atlas, layout)) {
            return atlas;
        }
        BufferedImage copy = copyOf(atlas);
        apply(copy, layout);
        return copy;
    }

    /** Clear the required cutout in one frame embedded at {@code yOffset}. */
    public static boolean applyToFrame(BufferedImage image, int yOffset, int frameHeight) {
        if (image == null || image.getWidth() < 64 || image.getWidth() % 64 != 0
                || frameHeight * 2 != image.getWidth() || yOffset < 0
                || yOffset + frameHeight > image.getHeight()) {
            return false;
        }
        int scale = image.getWidth() / 64;
        boolean changed = false;
        for (int row = 0; row < WING_ROWS.length; row++) {
            int rowY = yOffset + (2 + row) * scale;
            int start = WING_ROWS[row][0];
            int end = WING_ROWS[row][1];
            changed |= clearBlock(image, 36 * scale, rowY, 0,
                    start * scale, scale);
            changed |= clearBlock(image, 36 * scale, rowY, end * scale,
                    (10 - end) * scale, scale);
        }
        return changed;
    }

    /** Whether every pixel outside the tapered outer-wing outline is fully transparent. */
    public static boolean hasRequiredCutout(BufferedImage atlas, int frameCount) {
        Layout layout = layout(atlas, frameCount);
        return layout != null && hasRequiredCutout(atlas, layout);
    }

    /**
     * Predicate used by the editor's opaque-fill regression checks: every non-structural pixel is
     * opaque and every required wing-cutout pixel remains fully transparent.
     */
    public static boolean isOpaqueExceptWingCutout(BufferedImage atlas, int frameCount) {
        Layout layout = layout(atlas, frameCount);
        if (layout == null) {
            return false;
        }
        for (int y = 0; y < atlas.getHeight(); y++) {
            int frameY = y % layout.frameHeight();
            for (int x = 0; x < atlas.getWidth(); x++) {
                int alpha = (atlas.getRGB(x, y) >>> 24) & 0xFF;
                boolean cutout = isCutoutPixel(x, frameY, layout.scale());
                if (cutout ? alpha != 0 : alpha != 0xFF) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Verify that a presentation atlas differs from its immutable source only where the required
     * cutout clears alpha, with every other ARGB pixel preserved exactly.
     */
    public static boolean isExactMaskedPresentation(
            BufferedImage source, BufferedImage presented, int frameCount) {
        Layout layout = layout(source, frameCount);
        if (layout == null || presented == null
                || presented.getWidth() != source.getWidth()
                || presented.getHeight() != source.getHeight()) {
            return false;
        }
        for (int y = 0; y < source.getHeight(); y++) {
            int frameY = y % layout.frameHeight();
            for (int x = 0; x < source.getWidth(); x++) {
                int actual = presented.getRGB(x, y);
                boolean cutout = isCutoutPixel(x, frameY, layout.scale());
                if (cutout ? ((actual >>> 24) & 0xFF) != 0 : actual != source.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Check whether all actual Elytra UV faces are transparent. Padding outside those faces is
     * ignored, so unrelated pixels cannot suppress the vanilla Elytra fallback.
     */
    public static boolean isElytraAreaTransparent(BufferedImage frame) {
        Layout layout = layout(frame, 1);
        if (layout == null) {
            return true;
        }
        int scale = layout.scale();

        // Top and bottom faces: (24,0)-(44,2).
        if (hasVisiblePixel(frame, 24 * scale, 0, 20 * scale, 2 * scale)) {
            return false;
        }
        // Left side, inner face and right side: (22,2)-(36,22).
        if (hasVisiblePixel(frame, 22 * scale, 2 * scale, 14 * scale, 20 * scale)) {
            return false;
        }
        // Outer face: only pixels inside the tapered silhouette count as Elytra content.
        for (int row = 0; row < WING_ROWS.length; row++) {
            int start = WING_ROWS[row][0];
            int end = WING_ROWS[row][1];
            if (hasVisiblePixel(frame, (36 + start) * scale, (2 + row) * scale,
                    (end - start) * scale, scale)) {
                return false;
            }
        }
        return true;
    }

    private static void apply(BufferedImage atlas, Layout layout) {
        for (int frame = 0; frame < layout.frameCount(); frame++) {
            applyToFrame(atlas, frame * layout.frameHeight(), layout.frameHeight());
        }
    }

    private static boolean hasRequiredCutout(BufferedImage atlas, Layout layout) {
        for (int y = 0; y < atlas.getHeight(); y++) {
            int frameY = y % layout.frameHeight();
            for (int x = 36 * layout.scale(); x < 46 * layout.scale(); x++) {
                if (isCutoutPixel(x, frameY, layout.scale())
                        && ((atlas.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isCutoutPixel(int x, int frameY, int scale) {
        int firstY = 2 * scale;
        int lastY = 22 * scale;
        int firstX = 36 * scale;
        int lastX = 46 * scale;
        if (x < firstX || x >= lastX || frameY < firstY || frameY >= lastY) {
            return false;
        }
        int row = frameY / scale - 2;
        int column = x / scale - 36;
        return column < WING_ROWS[row][0] || column >= WING_ROWS[row][1];
    }

    private static boolean clearBlock(
            BufferedImage image, int faceX, int y, int xOffset, int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        boolean changed = false;
        int startX = faceX + xOffset;
        for (int py = y; py < y + height; py++) {
            for (int px = startX; px < startX + width; px++) {
                if (image.getRGB(px, py) != 0x00000000) {
                    image.setRGB(px, py, 0x00000000);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean hasVisiblePixel(
            BufferedImage image, int x, int y, int width, int height) {
        for (int py = y; py < y + height; py++) {
            for (int px = x; px < x + width; px++) {
                if (((image.getRGB(px, py) >>> 24) & 0xFF) > 10) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BufferedImage copyOf(BufferedImage image) {
        BufferedImage copy = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
            copy.setRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
        }
        return copy;
    }

    private static void requireWingRow(int row) {
        if (row < 0 || row >= WING_ROWS.length) {
            throw new IllegalArgumentException("Elytra wing row must be between 0 and 19");
        }
    }

    private static Layout layout(BufferedImage atlas, int frameCount) {
        if (atlas == null || frameCount < 1 || atlas.getWidth() < 64
                || atlas.getWidth() % 64 != 0 || atlas.getHeight() % frameCount != 0) {
            return null;
        }
        int frameHeight = atlas.getHeight() / frameCount;
        if (frameHeight * 2 != atlas.getWidth()) {
            return null;
        }
        return new Layout(atlas.getWidth() / 64, frameHeight, frameCount);
    }

    private record Layout(int scale, int frameHeight, int frameCount) {
    }
}
