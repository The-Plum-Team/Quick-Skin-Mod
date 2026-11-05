package com.quickskin.mod.common.util;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.Raster;

/**
 * Copies a decoded image into a TYPE_INT_ARGB image holding the samples vanilla shows.
 *
 * <p>Minecraft loads a greyscale PNG by repeating each raw grey sample into red, green and blue.
 * {@link BufferedImage#getRGB} instead converts a {@code CS_GRAY} image from linear grey to sRGB,
 * which brightens it (grey 128 reads as 188), so greyscale images, with or without alpha, are read
 * from their raster. Every other image keeps its exact non-premultiplied ARGB values; nothing is
 * composited, so the colour behind a translucent or transparent pixel survives.</p>
 */
public final class RawArgbImages {
    private RawArgbImages() {
    }

    /** The image itself when it already is TYPE_INT_ARGB, otherwise an exact copy. */
    public static BufferedImage toIntArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        return isRawGray(source) ? copyGray(source) : copyArgb(source);
    }

    /** Whether {@link BufferedImage#getRGB} would gamma-convert this image's grey samples. */
    public static boolean isRawGray(BufferedImage image) {
        ColorModel model = image.getColorModel();
        return model instanceof ComponentColorModel
                && model.getColorSpace().getType() == ColorSpace.TYPE_GRAY
                && !model.isAlphaPremultiplied()
                && model.getNumComponents() == (model.hasAlpha() ? 2 : 1)
                && image.getRaster().getNumBands() == model.getNumComponents();
    }

    private static BufferedImage copyGray(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        ColorModel model = source.getColorModel();
        boolean alpha = model.hasAlpha();
        int grayBits = model.getComponentSize(0);
        int alphaBits = alpha ? model.getComponentSize(1) : 0;
        Raster raster = source.getRaster();
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] gray = new int[width];
        int[] alphas = new int[width];
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            raster.getSamples(0, y, width, 1, 0, gray);
            if (alpha) raster.getSamples(0, y, width, 1, 1, alphas);
            for (int x = 0; x < width; x++) {
                int g = to8Bits(gray[x], grayBits);
                int a = alpha ? to8Bits(alphas[x], alphaBits) : 0xFF;
                row[x] = (a << 24) | (g << 16) | (g << 8) | g;
            }
            copy.setRGB(0, y, width, 1, row, 0, width);
        }
        return copy;
    }

    private static int to8Bits(int sample, int bits) {
        if (bits == 8) return sample & 0xFF;
        if (bits > 8) return (sample >>> (bits - 8)) & 0xFF;
        int maximum = (1 << bits) - 1;
        return maximum <= 0 ? 0 : (sample * 255 + maximum / 2) / maximum;
    }

    private static BufferedImage copyArgb(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width]; // row by row: O(width) extra memory, not 16 MB at 2048x2048
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            copy.setRGB(0, y, width, 1, row, 0, width);
        }
        return copy;
    }
}
