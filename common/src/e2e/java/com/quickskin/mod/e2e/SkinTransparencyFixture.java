package com.quickskin.mod.e2e;

import java.awt.image.BufferedImage;

/** Alpha-only edits to the bundled classic skin, shared by first- and third-person evidence. */
public final class SkinTransparencyFixture {

    private SkinTransparencyFixture() {}

    public static void apply(BufferedImage image) {
        if (image.getWidth() != 64 || image.getHeight() != 64) {
            throw new IllegalArgumentException("the transparency fixture requires a 64x64 classic skin");
        }
        setAlpha(image, 34, 23, 4, 6, 0); // torso back window
        setAlpha(image, 40, 20, 16, 11, 128); // all four right sleeve faces, above the hand row
        setAlpha(image, 44, 16, 4, 4, 128); // right shoulder
        setAlpha(image, 32, 52, 16, 11, 128); // all four left sleeve faces, above the hand row
        setAlpha(image, 36, 48, 4, 4, 128); // left shoulder
        // An opaque outer sleeve would conceal the base layer this fixture is testing.
        setAlpha(image, 40, 32, 16, 16, 0);
        setAlpha(image, 48, 48, 16, 16, 0);
    }

    private static void setAlpha(BufferedImage image, int x, int y, int width, int height, int alpha) {
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                image.setRGB(column, row, (alpha << 24) | (image.getRGB(column, row) & 0xFFFFFF));
            }
        }
    }
}
