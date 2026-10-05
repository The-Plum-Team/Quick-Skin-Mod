package com.quickskin.mod.client.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeRenderKeysTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA256 = "sha256-" + "c".repeat(64);

    @Test
    void aKeyCarriesTheAnimationIdAndTheValidatedNetworkHash() {
        CapeRenderKeys keys = new CapeRenderKeys(8);

        CapeRenderKey strong = keys.of("local_cape:" + SHA256);
        CapeRenderKey legacy = keys.of("local_cape:" + SHA1);
        CapeRenderKey known = keys.of("known:bmo");

        assertNotNull(strong);
        assertEquals("local_cape:" + SHA256, strong.capeId());
        assertEquals("cape_" + SHA256, strong.animationId());
        assertEquals(SHA256, strong.networkHash());
        assertNotNull(legacy);
        assertEquals(SHA1, legacy.networkHash());
        assertNotNull(known);
        assertEquals("cape_known_bmo", known.animationId());
        assertNull(known.networkHash(), "a bundled cape is never a network texture");
    }

    @Test
    void anInvalidContentIdKeepsItsAnimationIdButNeverGetsANetworkHash() {
        CapeRenderKeys keys = new CapeRenderKeys(8);
        String[] invalid = {
                "local_cape:",
                "local_cape:" + SHA1.toUpperCase(),
                "local_cape:" + SHA1 + "0",
                "local_cape:sha256-" + "c".repeat(63),
                "local_cape:sha1-" + SHA1,
                "local_cape:../" + SHA1,
                "local_cape:" + SHA256 + "/../x",
        };

        for (String capeId : invalid) {
            CapeRenderKey key = keys.of(capeId);
            assertNotNull(key, capeId);
            assertEquals(CapeAnimationIds.deriveAnimationId(capeId), key.animationId(), capeId);
            assertNull(key.networkHash(), capeId);
        }
    }

    @Test
    void capeIdsWithoutAnAnimationIdGetNoKeyAndAreNotRemembered() {
        CapeRenderKeys keys = new CapeRenderKeys(8);

        assertNull(keys.of(null));
        assertNull(keys.of(""));
        assertNull(keys.of("SomeMojangUsername"));
        assertNull(keys.of("__NONE__"));
        assertEquals(0, keys.size());
    }

    @Test
    void theSameCapeIdReusesOneKey() {
        CapeRenderKeys keys = new CapeRenderKeys(8);

        CapeRenderKey first = keys.of("local_cape:" + SHA256);
        CapeRenderKey again = keys.of(new String("local_cape:" + SHA256));

        assertSame(first, again);
        assertEquals(1, keys.size());
    }

    @Test
    void theMemoStaysWithinItsBound() {
        CapeRenderKeys keys = new CapeRenderKeys(4);

        for (int index = 0; index < 50; index++) {
            assertNotNull(keys.of("known:cape" + index));
            assertTrue(keys.size() <= 4, "size " + keys.size() + " after " + index);
        }
        CapeRenderKey rederived = keys.of("known:cape0");
        assertNotNull(rederived);
        assertEquals("cape_known_cape0", rederived.animationId());
    }

    @Test
    void aMarkHoldsOnlyForTheEpochItWasRecordedIn() {
        CapeRenderKeys keys = new CapeRenderKeys(8);
        CapeRenderKey key = keys.of("local_cape:" + SHA256);
        assertNotNull(key);

        long epoch = keys.visibilityEpoch();
        assertFalse(key.isVisibilityMarkedIn(epoch), "a new key has never been marked");
        key.recordVisibilityMark(epoch);
        assertTrue(key.isVisibilityMarkedIn(keys.visibilityEpoch()));

        keys.invalidateVisibilityMarks();
        assertFalse(key.isVisibilityMarkedIn(keys.visibilityEpoch()));
    }

    @Test
    void aMarkRecordedForAnEpochThatMovedOnWhileMarkingIsStale() {
        CapeRenderKeys keys = new CapeRenderKeys(8);
        CapeRenderKey key = keys.of("known:bmo");
        assertNotNull(key);

        long epochReadBeforeMarking = keys.visibilityEpoch();
        keys.invalidateVisibilityMarks(); // e.g. the client ticked while the cape was being marked
        key.recordVisibilityMark(epochReadBeforeMarking);

        assertFalse(key.isVisibilityMarkedIn(keys.visibilityEpoch()));
    }

    @Test
    void clearingTheMemoForgetsMarksSoTheNextFrameMarksAgain() {
        CapeRenderKeys keys = new CapeRenderKeys(1);
        CapeRenderKey first = keys.of("known:a");
        assertNotNull(first);
        first.recordVisibilityMark(keys.visibilityEpoch());

        keys.of("known:b"); // evicts "known:a" from the one-entry memo
        CapeRenderKey again = keys.of("known:a");

        assertNotNull(again);
        assertNotSame(first, again);
        assertFalse(again.isVisibilityMarkedIn(keys.visibilityEpoch()));
    }

    @Test
    void sharedMemoIsASingleProcessWideInstance() {
        assertSame(CapeRenderKeys.shared(), CapeRenderKeys.shared());
    }

    @Test
    void aNonPositiveBoundIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CapeRenderKeys(0));
    }
}
