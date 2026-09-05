package com.quickskin.mod.common.data;

/**
 * Enum defining valid skin and cape resolutions
 * Supports standard (64x64) and HD skins up to 2048x2048
 */
public enum SkinResolution {
    LEGACY(64, 32, 1),      // Old skin format (pre-1.8)
    STANDARD(64, 64, 1),    // Modern standard skin
    HD_128(128, 128, 2),    // 2x HD
    HD_256(256, 256, 4),    // 4x HD
    HD_512(512, 512, 8),    // 8x HD
    HD_1024(1024, 1024, 16), // 16x HD
    HD_2048(2048, 2048, 32), // 32x HD (max)

    // Cape dimensions (2:1 aspect ratio)
    CAPE_128(128, 64, 2),      // 2x HD cape
    CAPE_256(256, 128, 4),     // 4x HD cape
    CAPE_512(512, 256, 8),     // 8x HD cape
    CAPE_1024(1024, 512, 16),  // 16x HD cape
    CAPE_2048(2048, 1024, 32); // 32x HD cape (max)

    private final int width;
    private final int height;
    private final int scale;

    SkinResolution(int width, int height, int scale) {
        this.width = width;
        this.height = height;
        this.scale = scale;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getScale() {
        return scale;
    }

    public boolean isHD() {
        return scale > 1;
    }

    /**
     * Get resolution from dimensions
     * @return Resolution enum, or null if invalid
     */
    public static SkinResolution fromDimensions(int width, int height) {
        for (SkinResolution res : values()) {
            if (res.width == width && res.height == height) {
                return res;
            }
        }
        return null;
    }

    /**
     * Find the nearest valid resolution for arbitrary dimensions.
     * Determines skin vs cape based on aspect ratio, then picks the closest valid size.
     * @return Nearest resolution, or null if dimensions are too small or invalid
     */
    public static SkinResolution findNearest(int width, int height) {
        SkinResolution exact = fromDimensions(width, height);
        if (exact != null) return exact;

        if (width <= 0 || height <= 0) return null;

        double ratio = (double) height / width;

        if (ratio < 0.7) {
            // 2:1 aspect — cape or legacy
            int nearestWidth = findNearestValidSize(width);
            SkinResolution res = fromDimensions(nearestWidth, nearestWidth / 2);
            return res != null ? res : LEGACY;
        } else {
            // ~1:1 aspect — skin
            int size = Math.max(width, height);
            int nearestSize = findNearestValidSize(size);
            SkinResolution res = fromDimensions(nearestSize, nearestSize);
            return res != null ? res : STANDARD;
        }
    }

    private static int findNearestValidSize(int size) {
        int[] validSizes = {64, 128, 256, 512, 1024, 2048};
        int nearest = validSizes[0];
        int minDist = Math.abs(size - nearest);
        for (int valid : validSizes) {
            int dist = Math.abs(size - valid);
            if (dist < minDist) {
                minDist = dist;
                nearest = valid;
            }
        }
        return nearest;
    }

    @Override
    public String toString() {
        return width + "x" + height + (isHD() ? " (HD " + scale + "x)" : "");
    }
}
