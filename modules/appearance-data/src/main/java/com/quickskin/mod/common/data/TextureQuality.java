package com.quickskin.mod.common.data;

/**
 * Quality levels for texture caching
 * Different qualities are used for different purposes to optimize memory
 */
public enum TextureQuality {
    /**
     * Full resolution - used for in-world rendering
     * Can be HD (up to 2048x1024)
     */
    FULL,

    /**
     * Preview size - used for GUI skin selection screen
     * Fixed 256x256 regardless of original size
     */
    PREVIEW,

    /**
     * Thumbnail size - used for GUI list items
     * Fixed 64x64 regardless of original size
     */
    THUMBNAIL,

    /**
     * Normalized for vanilla rendering - downscaled to 64x64
     * Used when HD skins need to be rendered without HD support
     * (Phase 6 will remove this when we drop GeckoLib)
     */
    NORMALIZED;

    /**
     * Get the target dimensions for this quality level
     */
    public int getTargetSize() {
        return switch (this) {
            case PREVIEW -> 256;
            case THUMBNAIL, NORMALIZED -> 64;
            case FULL -> -1; // Original size
        };
    }

}
