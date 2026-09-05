package com.quickskin.mod.client.storage;

import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.TextureTransferLimits;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side cache for animation metadata received from server
 * Stores animation data in memory for playback
 */
@Environment(EnvType.CLIENT)
public class ClientAnimationMetadataCache {
    private static ClientAnimationMetadataCache instance;

    private final Map<String, AnimationMetadata> metadataCache =
            new LinkedHashMap<>(16, 0.75f, true);

    private ClientAnimationMetadataCache() {}

    public static ClientAnimationMetadataCache getInstance() {
        if (instance == null) {
            instance = new ClientAnimationMetadataCache();
        }
        return instance;
    }

    /**
     * Store animation metadata for a texture
     * @param hash The texture hash
     * @param metadata The animation metadata
     */
    public synchronized void storeMetadata(String hash, AnimationMetadata metadata) {
        if (!NetworkSecurity.isValidContentId(hash) || !isValidMetadata(metadata)) {
            return;
        }

        AnimationMetadata copy = copyOf(metadata);
        metadataCache.put(hash, copy);
        while (metadataCache.size() > TextureTransferLimits.MAX_CLIENT_CACHE_ENTRIES) {
            String eldest = metadataCache.keySet().iterator().next();
            metadataCache.remove(eldest);
        }
    }

    /**
     * Get animation metadata for a texture
     * @param hash The texture hash
     * @return The metadata, or null if not found
     */
    @Nullable
    public synchronized AnimationMetadata getMetadata(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        AnimationMetadata metadata = metadataCache.get(hash);
        return metadata == null ? null : copyOf(metadata);
    }

    /**
     * Clear all cached metadata
     */
    public synchronized void clear() {
        metadataCache.clear();
    }

    /**
     * Remove metadata for a specific texture
     * @param hash The texture hash
     */
    public synchronized void remove(String hash) {
        metadataCache.remove(hash);
    }

    /** Fast replay guard used before parsing an already-known metadata payload. */
    public synchronized boolean hasMetadata(String hash) {
        return NetworkSecurity.isValidContentId(hash) && metadataCache.containsKey(hash);
    }

    /** Returns whether the cached value is the same validated metadata version. */
    public synchronized boolean matchesMetadata(String hash, AnimationMetadata metadata) {
        if (!NetworkSecurity.isValidContentId(hash) || !isValidMetadata(metadata)) return false;
        return metadata.equals(metadataCache.get(hash));
    }

    private boolean isValidMetadata(AnimationMetadata metadata) {
        if (metadata == null || metadata.frames() == null || metadata.frameCount() < 1
                || metadata.frameCount() > 256 || metadata.frames().size() != metadata.frameCount()) {
            return false;
        }
        for (AnimationMetadata.FrameData frame : metadata.frames()) {
            if (frame == null || frame.delay() < 20 || frame.delay() > 60_000
                    || frame.index() < 0 || frame.index() >= metadata.frameCount()) return false;
        }
        return true;
    }

    private AnimationMetadata copyOf(AnimationMetadata metadata) {
        return new AnimationMetadata(new ArrayList<>(metadata.frames()), metadata.frameCount());
    }
}
