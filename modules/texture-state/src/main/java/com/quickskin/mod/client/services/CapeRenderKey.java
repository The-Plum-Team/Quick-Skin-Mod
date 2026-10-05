package com.quickskin.mod.client.services;

import org.jetbrains.annotations.Nullable;

/**
 * What the cape renderer needs to know about one cape ID, derived from it once.
 *
 * <p>The cape layer asks the same questions about every caped player on every frame: which
 * animation the cape ID names, and whether its {@code local_cape:} content ID is a valid network
 * content ID that may be looked up in the texture and metadata caches. The answers depend only on
 * the cape ID string, so {@link CapeRenderKeys} derives them once per ID and the renderer reuses
 * them instead of splitting and re-parsing the ID on every call.</p>
 *
 * <p>{@link #networkHash()} is non-null only when {@code NetworkSecurity.isValidContentId}
 * accepted it, and the constructor is private to {@link CapeRenderKeys}, so holding a key is proof
 * that its hash was validated. Cache methods that take a key rely on that proof; an invalid ID
 * never reaches them.</p>
 *
 * <p>A key also remembers the visibility epoch in which the renderer last finished marking it
 * visible. See {@link CapeRenderKeys#visibilityEpoch()}.</p>
 */
public final class CapeRenderKey {

    private static final long NEVER_MARKED = -1L;

    private final String capeId;
    private final String animationId;
    @Nullable
    private final String networkHash;
    private volatile long markedEpoch = NEVER_MARKED;

    CapeRenderKey(String capeId, String animationId, @Nullable String networkHash) {
        this.capeId = capeId;
        this.animationId = animationId;
        this.networkHash = networkHash;
    }

    /** The cape ID this key was derived from. */
    public String capeId() {
        return capeId;
    }

    /** The animation ID {@link CapeAnimationIds#deriveAnimationId} derives from the cape ID. */
    public String animationId() {
        return animationId;
    }

    /**
     * The validated content ID inside a {@code local_cape:} cape ID, or {@code null} for any other
     * cape ID and for a content ID that is not valid.
     */
    @Nullable
    public String networkHash() {
        return networkHash;
    }

    /** Whether the renderer already finished marking this cape visible in {@code epoch}. */
    public boolean isVisibilityMarkedIn(long epoch) {
        return markedEpoch == epoch;
    }

    /**
     * Records that the renderer finished marking this cape visible in {@code epoch}. Callers read
     * the epoch before they start marking, so an invalidation that lands while they mark leaves
     * the key stale and the next frame marks it again.
     */
    public void recordVisibilityMark(long epoch) {
        markedEpoch = epoch;
    }
}
