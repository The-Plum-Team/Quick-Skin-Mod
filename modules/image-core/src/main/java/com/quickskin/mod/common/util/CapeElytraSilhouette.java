package com.quickskin.mod.common.util;

import java.awt.image.BufferedImage;

/**
 * Structural alpha mask for the complete Elytra UV footprint stored in a Minecraft cape atlas.
 *
 * <p>The Elytra model is a rectangular cuboid. Its familiar tapered outline comes from the alpha
 * envelope across the model's complete 24x22 UV footprint, not from the model geometry. In
 * particular, Minecraft's 10x20 inner face is transparent; leaving that face opaque draws the
 * source canvas as a large rectangular panel even when the outer face itself is tapered. This
 * class owns the one scale-independent vanilla envelope used by import, preview, local
 * presentation, network presentation, and animation paths.</p>
 *
 * <p>Methods that normalize an atlas preserve the input object when no pixel needs changing. The
 * content-addressed source bytes can therefore remain immutable while presentation receives a
 * masked copy only when necessary.</p>
 */
public final class CapeElytraSilhouette {

    private static final int ELYTRA_MIN_X = 22;
    private static final int ELYTRA_MAX_X = 46;
    private static final int ELYTRA_HEIGHT = 22;

    /**
     * Visible x spans, end-exclusive, for each row of Minecraft's 24x22 Elytra UV footprint.
     *
     * <p>These binary alpha rows are shared by every supported vanilla Elytra texture. Applying
     * them only removes pixels the model must not sample; custom colour and transparency inside
     * the envelope remain untouched.</p>
     */
    private static final int[][] ELYTRA_VISIBLE_SPANS = {
            {31, 40},
            {32, 34},
            {34, 42},
            {34, 43},
            {35, 44},
            {35, 44},
            {35, 44},
            {35, 45},
            {35, 45},
            {35, 45},
            {35, 45},
            {22, 23, 36, 46},
            {22, 23, 36, 46},
            {22, 23, 36, 46},
            {22, 23, 36, 46},
            {22, 23, 36, 46},
            {22, 23, 37, 46},
            {22, 23, 37, 46},
            {22, 23, 37, 46},
            {22, 23, 38, 46},
            {22, 23, 38, 46},
            {22, 23, 39, 46}
    };

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
     * exact ARGB copy with only pixels outside the vanilla Elytra alpha envelope cleared.
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
        for (int row = 0; row < ELYTRA_VISIBLE_SPANS.length; row++) {
            int rowY = yOffset + row * scale;
            int cursor = ELYTRA_MIN_X;
            int[] spans = ELYTRA_VISIBLE_SPANS[row];
            for (int index = 0; index < spans.length; index += 2) {
                int start = spans[index];
                int end = spans[index + 1];
                changed |= clearBlock(image, cursor * scale, rowY, 0,
                        (start - cursor) * scale, scale);
                cursor = end;
            }
            changed |= clearBlock(image, cursor * scale, rowY, 0,
                    (ELYTRA_MAX_X - cursor) * scale, scale);
        }
        return changed;
    }

    /** Whether every pixel outside the vanilla Elytra UV envelope is fully transparent. */
    public static boolean hasRequiredCutout(BufferedImage atlas, int frameCount) {
        Layout layout = layout(atlas, frameCount);
        return layout != null && hasRequiredCutout(atlas, layout);
    }

    /**
     * Predicate used by the editor's opaque-fill regression checks: every non-structural pixel is
     * opaque and every pixel outside the required Elytra envelope remains fully transparent.
     */
    public static boolean isOpaqueExceptElytraEnvelope(BufferedImage atlas, int frameCount) {
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
        for (int y = 0; y < ELYTRA_HEIGHT * scale; y++) {
            for (int x = ELYTRA_MIN_X * scale; x < ELYTRA_MAX_X * scale; x++) {
                if (!isCutoutPixel(x, y, scale)
                        && ((frame.getRGB(x, y) >>> 24) & 0xFF) > 10) {
                    return false;
                }
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
            for (int x = ELYTRA_MIN_X * layout.scale();
                    x < ELYTRA_MAX_X * layout.scale(); x++) {
                if (isCutoutPixel(x, frameY, layout.scale())
                        && ((atlas.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isCutoutPixel(int x, int frameY, int scale) {
        int firstY = 0;
        int lastY = ELYTRA_HEIGHT * scale;
        int firstX = ELYTRA_MIN_X * scale;
        int lastX = ELYTRA_MAX_X * scale;
        if (x < firstX || x >= lastX || frameY < firstY || frameY >= lastY) {
            return false;
        }
        int row = frameY / scale;
        int column = x / scale;
        int[] spans = ELYTRA_VISIBLE_SPANS[row];
        for (int index = 0; index < spans.length; index += 2) {
            if (column >= spans[index] && column < spans[index + 1]) {
                return false;
            }
        }
        return true;
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
