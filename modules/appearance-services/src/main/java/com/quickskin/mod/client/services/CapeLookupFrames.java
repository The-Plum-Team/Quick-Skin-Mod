package com.quickskin.mod.client.services;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The frame {@link CapeAnimationHelper} last resolved since the cape layer began its texture
 * lookup, so that {@link CapeAnimationHelper#resolveCurrentFrameOnce} can tell a texture that is
 * already a resolved frame from one that still needs resolving.
 *
 * <p>A texture counts as resolved only when it is the very object resolved last (frames are
 * compared by identity), for the same cape ID, on the calling thread, and no lookup began since.
 * Anything else, including an equal texture from another source, is resolved again.</p>
 */
final class CapeLookupFrames {

    private record Resolved(Thread thread, @Nullable String capeId, Object frame) {
    }

    private volatile Resolved last;

    /** Forgets the previous resolution; the cape layer calls it right before its lookup. */
    void beginLookup() {
        last = null;
    }

    /** Records that the calling thread resolved {@code frame} for {@code capeId}. */
    void recordResolved(@Nullable String capeId, Object frame) {
        last = new Resolved(Thread.currentThread(), capeId, Objects.requireNonNull(frame, "frame"));
    }

    /**
     * Whether {@code texture} is the frame this thread resolved for {@code capeId} since the
     * lookup began.
     */
    boolean wasResolved(@Nullable Object texture, @Nullable String capeId) {
        Resolved resolved = last;
        return texture != null && resolved != null && resolved.frame() == texture
                && resolved.thread() == Thread.currentThread()
                && Objects.equals(resolved.capeId(), capeId);
    }
}
