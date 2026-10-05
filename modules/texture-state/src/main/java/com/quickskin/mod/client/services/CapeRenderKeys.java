package com.quickskin.mod.client.services;

import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.TextureTransferLimits;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded memo of {@link CapeRenderKey}s, plus the epoch that limits how often the renderer marks a
 * cape visible.
 *
 * <p><b>Keys.</b> {@link #of} derives a key once per cape ID and returns the same key afterwards.
 * Only recognised cape IDs get a key, so unrecognised strings cannot fill the memo, and the memo
 * is cleared outright when it reaches its bound. Clearing only costs a re-derivation and a fresh
 * visibility mark, never a missed one.</p>
 *
 * <p><b>Visibility epoch.</b> Marking a cape visible stamps its texture-cache working-set entry
 * and its animation slot with the current client tick, so repeating it within the same tick
 * changes nothing. The epoch therefore advances on every client tick, and also whenever the state
 * a mark depends on is removed or replaced mid-tick: a texture leaving the network cache, an
 * animation being unregistered, either structure being cleared, or animation metadata changing.
 * A cape whose key is already marked in the current epoch needs no further marking until the
 * epoch moves on.</p>
 */
public final class CapeRenderKeys {

    /** One key per texture the client cache can hold is plenty for every cape in view. */
    public static final int DEFAULT_MAX_KEYS = TextureTransferLimits.MAX_CLIENT_CACHE_ENTRIES;

    private static final CapeRenderKeys SHARED = new CapeRenderKeys(DEFAULT_MAX_KEYS);

    private final int maxKeys;
    private final ConcurrentHashMap<String, CapeRenderKey> keys = new ConcurrentHashMap<>();
    private final AtomicLong visibilityEpoch = new AtomicLong();

    public CapeRenderKeys(int maxKeys) {
        if (maxKeys < 1) {
            throw new IllegalArgumentException("maxKeys must be positive");
        }
        this.maxKeys = maxKeys;
    }

    /** The process-wide memo the renderer and the client caches share. */
    public static CapeRenderKeys shared() {
        return SHARED;
    }

    /**
     * The key for {@code capeId}, or {@code null} when the cape ID has no animation ID (null,
     * empty or unrecognised), exactly when {@link CapeAnimationIds#deriveAnimationId} is null.
     */
    @Nullable
    public CapeRenderKey of(@Nullable String capeId) {
        if (capeId == null) {
            return null;
        }
        CapeRenderKey key = keys.get(capeId);
        if (key != null) {
            return key;
        }
        String animationId = CapeAnimationIds.deriveAnimationId(capeId);
        if (animationId == null) {
            return null;
        }
        String localHash = CapeAnimationIds.localHash(capeId);
        String networkHash = localHash != null && NetworkSecurity.isValidContentId(localHash)
                ? localHash
                : null;
        key = new CapeRenderKey(capeId, animationId, networkHash);
        if (keys.size() >= maxKeys) {
            keys.clear();
        }
        CapeRenderKey raced = keys.putIfAbsent(capeId, key);
        return raced != null ? raced : key;
    }

    /** The current visibility epoch; read it before marking, then record it on the key. */
    public long visibilityEpoch() {
        return visibilityEpoch.get();
    }

    /** Makes every key need a fresh visibility mark. */
    public void invalidateVisibilityMarks() {
        visibilityEpoch.incrementAndGet();
    }

    /** Number of memoised keys, for bounding assertions. */
    public int size() {
        return keys.size();
    }
}
