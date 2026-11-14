package com.quickskin.mod.common.util;

import java.io.IOException;

/**
 * A picture too large to be opened at all, refused from its header before any of it is decoded.
 * It names the picture's size and the side limit it was held to, so a screen can say which of
 * the two limits it is over: its sides, or the pixels it has in all.
 */
public final class OversizedImageException extends IOException {
    private final int width;
    private final int height;
    private final int maxSide;

    OversizedImageException(int width, int height, int maxSide) {
        super("Image dimensions exceed the configured limits");
        this.width = width;
        this.height = height;
        this.maxSide = maxSide;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int maxSide() {
        return maxSide;
    }

    /** Whether a side is over the limit; otherwise the picture only has too many pixels in all. */
    public boolean exceedsSide() {
        return width > maxSide || height > maxSide;
    }
}
