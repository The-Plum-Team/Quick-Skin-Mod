package com.quickskin.mod.client.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CapeAnimationHelper#resolveCurrentFrameOnce} skips resolving only a texture that
 * {@link CapeLookupFrames} reports as already resolved; every other texture is resolved again.
 */
class CapeLookupFramesTest {

    private static final String CAPE = "local_cape:sha256-" + "a".repeat(64);

    @Test
    void theFrameResolvedSinceTheLookupBeganIsNotResolvedAgain() {
        CapeLookupFrames frames = new CapeLookupFrames();
        Object frame = new Object();

        frames.beginLookup();
        frames.recordResolved(CAPE, frame);

        assertTrue(frames.wasResolved(frame, CAPE));
    }

    @Test
    void nothingIsResolvedBeforeAFrameIsRecorded() {
        CapeLookupFrames frames = new CapeLookupFrames();

        frames.beginLookup();

        assertFalse(frames.wasResolved(new Object(), CAPE));
        assertFalse(frames.wasResolved(null, CAPE));
    }

    @Test
    void beginningALookupForgetsTheEarlierFrame() {
        CapeLookupFrames frames = new CapeLookupFrames();
        Object frame = new Object();
        frames.recordResolved(CAPE, frame);

        frames.beginLookup();

        assertFalse(frames.wasResolved(frame, CAPE),
                "a texture from the next lookup was not resolved by it");
    }

    @Test
    void anotherTextureOrCapeIsResolvedAgain() {
        CapeLookupFrames frames = new CapeLookupFrames();
        Object frame = new Object();
        frames.beginLookup();
        frames.recordResolved(CAPE, frame);

        assertFalse(frames.wasResolved(new Object(), CAPE),
                "a preview binding or another mod's texture still needs resolving");
        assertFalse(frames.wasResolved(frame, "known:minecon2016"));
        assertFalse(frames.wasResolved(frame, null));
    }

    @Test
    void framesAreComparedByIdentity() {
        CapeLookupFrames frames = new CapeLookupFrames();
        String frame = new String("quickskin:frame");
        frames.beginLookup();
        frames.recordResolved(CAPE, frame);

        assertFalse(frames.wasResolved(new String("quickskin:frame"), CAPE),
                "an equal atlas location is not proof that it was resolved");
    }

    @Test
    void aFrameAnotherThreadResolvedIsResolvedAgain() throws InterruptedException {
        CapeLookupFrames frames = new CapeLookupFrames();
        Object frame = new Object();
        frames.beginLookup();
        frames.recordResolved(CAPE, frame);

        AtomicBoolean resolvedElsewhere = new AtomicBoolean(true);
        Thread other = new Thread(() -> resolvedElsewhere.set(frames.wasResolved(frame, CAPE)));
        other.start();
        other.join();

        assertFalse(resolvedElsewhere.get());
        assertTrue(frames.wasResolved(frame, CAPE));
    }
}
