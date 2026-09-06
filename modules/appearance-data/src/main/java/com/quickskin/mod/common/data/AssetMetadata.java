package com.quickskin.mod.common.data;

import java.nio.file.Path;

/**
 * Metadata for a local asset (skin or cape)
 * Stored in memory cache for fast lookup
 */
public record AssetMetadata(
    String hash,              // Canonical SHA-256 content ID (sha256-...)
    String friendlyName,      // Display name (filename without extension)
    String type,              // "skin" or "cape"
    Path path,                // Original file path
    SkinResolution resolution, // Texture resolution
    boolean isAnimated,       // True if GIF cape
    int frameCount,           // Number of animation frames (1 if not animated)
    long fileSize,            // File size in bytes
    String skinModel,         // "classic" or "slim" (null for capes)
    long lastModifiedTime     // File modification timestamp in milliseconds
) {
    public AssetMetadata {
        ContentId contentId = ContentId.parse(hash);
        if (contentId == null || contentId.algorithm() != ContentId.Algorithm.SHA256) {
            throw new IllegalArgumentException("Local asset metadata requires a SHA-256 primary");
        }
    }

    /**
     * Create metadata for a static skin
     */
    public static AssetMetadata forSkin(
            String hash,
            String friendlyName,
            Path path,
            SkinResolution resolution,
            long fileSize,
            String skinModel,
            long lastModifiedTime
    ) {
        return new AssetMetadata(
                hash,
                friendlyName,
                "skin",
                path,
                resolution,
                false,
                1,
                fileSize,
                skinModel,
                lastModifiedTime
        );
    }

    /**
     * Create metadata for a static cape
     */
    public static AssetMetadata forCape(
            String hash,
            String friendlyName,
            Path path,
            SkinResolution resolution,
            long fileSize,
            long lastModifiedTime
    ) {
        return new AssetMetadata(
                hash,
                friendlyName,
                "cape",
                path,
                resolution,
                false,
                1,
                fileSize,
                null,
                lastModifiedTime
        );
    }

    /**
     * Create metadata for an animated cape
     */
    public static AssetMetadata forAnimatedCape(
            String hash,
            String friendlyName,
            Path path,
            SkinResolution resolution,
            long fileSize,
            int frameCount,
            long lastModifiedTime
    ) {
        return new AssetMetadata(
                hash,
                friendlyName,
                "cape",
                path,
                resolution,
                true,
                frameCount,
                fileSize,
                null,
                lastModifiedTime
        );
    }

    /**
     * Check if this is a skin
     */
    public boolean isSkin() {
        return "skin".equals(type);
    }

    /**
     * Check if this is a cape
     */
    public boolean isCape() {
        return "cape".equals(type);
    }

    public boolean isCpmModel() {
        return "cpmmodel".equals(type);
    }

    public static AssetMetadata forCpmModel(
            String hash,
            String friendlyName,
            Path path,
            long fileSize,
            long lastModifiedTime
    ) {
        return new AssetMetadata(
                hash,
                friendlyName,
                "cpmmodel",
                path,
                SkinResolution.STANDARD,
                false,
                1,
                fileSize,
                null,
                lastModifiedTime
        );
    }
}
