package com.quickskin.mod.common.util;

import java.awt.image.BufferedImage;

/**
 * Turns a decoded JPEG the way its EXIF orientation ({@link JpegHeader#orientation()}) says it
 * is shown: a phone held upright stores its photo on its side.
 */
final class JpegOrientation {
    private JpegOrientation() {
    }

    /**
     * The decoded pixels turned the way {@code orientation} says they are shown; the image itself
     * when they already are.
     */
    static BufferedImage upright(BufferedImage decoded, int orientation) {
        if (decoded == null || orientation <= JpegHeader.UPRIGHT || orientation > 8) {
            return decoded;
        }
        // getRGB would brighten a greyscale JPEG's samples
        BufferedImage source = RawArgbImages.isRawGray(decoded) ? RawArgbImages.toIntArgb(decoded) : decoded;
        int width = source.getWidth();
        int height = source.getHeight();
        // From 5 on, the stored rows are the shown columns
        boolean turned = orientation >= 5;
        BufferedImage shown = new BufferedImage(
                turned ? height : width, turned ? width : height, BufferedImage.TYPE_INT_ARGB);
        // Whether a stored row runs right to left (or bottom to top) and where it lands
        boolean reversed = orientation == 2 || orientation == 3 || orientation == 7 || orientation == 8;
        boolean fromFarEdge = orientation == 3 || orientation == 4 || orientation == 6 || orientation == 7;
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            if (reversed) {
                for (int left = 0, right = width - 1; left < right; left++, right--) {
                    int swapped = row[left];
                    row[left] = row[right];
                    row[right] = swapped;
                }
            }
            int target = fromFarEdge ? height - 1 - y : y;
            if (turned) {
                shown.setRGB(target, 0, 1, width, row, 0, 1);
            } else {
                shown.setRGB(0, target, width, 1, row, 0, width);
            }
        }
        return shown;
    }
}
