package com.quickskin.mod.client.storage;

import com.quickskin.mod.client.services.CapeRenderKey;
import com.quickskin.mod.client.services.CapeRenderKeys;
import com.quickskin.mod.common.data.AnimationMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAnimationMetadataCacheTest {
    private static final String HASH =
            "0123456789abcdef0123456789abcdef01234567";
    private final ClientAnimationMetadataCache cache =
            ClientAnimationMetadataCache.getInstance();

    @AfterEach
    void clearCache() {
        cache.clear();
    }

    @Test
    void distinguishesTimingVersionsForTheSameAtlasHash() {
        AnimationMetadata initial = metadata(50);
        AnimationMetadata changed = metadata(125);

        cache.storeMetadata(HASH, initial);
        assertTrue(cache.matchesMetadata(HASH, initial));
        assertFalse(cache.matchesMetadata(HASH, changed));

        cache.storeMetadata(HASH, changed);
        assertFalse(cache.matchesMetadata(HASH, initial));
        assertTrue(cache.matchesMetadata(HASH, changed));
    }

    @Test
    void callersCannotMutateTheCachedFrameList() {
        cache.storeMetadata(HASH, metadata(50));
        AnimationMetadata returned = cache.getMetadata(HASH);
        assertNotNull(returned);
        returned.frames().clear();

        AnimationMetadata reread = cache.getMetadata(HASH);
        assertNotNull(reread);
        assertEquals(1, reread.frames().size());
    }

    @Test
    void aCapeKeyFindsMetadataOnlyThroughItsValidatedHash() {
        CapeRenderKeys keys = new CapeRenderKeys(8);
        CapeRenderKey network = keys.of("local_cape:" + HASH);
        CapeRenderKey invalid = keys.of("local_cape:" + HASH.toUpperCase());
        CapeRenderKey known = keys.of("known:bmo");
        assertNotNull(network);
        assertNotNull(invalid);
        assertNotNull(known);

        assertFalse(cache.hasMetadata(network));
        cache.storeMetadata(HASH, metadata(50));

        assertTrue(cache.hasMetadata(network));
        assertFalse(cache.hasMetadata(invalid));
        assertFalse(cache.hasMetadata(known));
        cache.remove(HASH);
        assertFalse(cache.hasMetadata(network));
    }

    @Test
    void metadataChangesMakeVisibleCapesMarkAgain() {
        CapeRenderKeys shared = CapeRenderKeys.shared();

        long beforeStore = shared.visibilityEpoch();
        cache.storeMetadata(HASH, metadata(50));
        long afterStore = shared.visibilityEpoch();
        assertNotEquals(beforeStore, afterStore);

        cache.remove(HASH);
        long afterRemove = shared.visibilityEpoch();
        assertNotEquals(afterStore, afterRemove);

        cache.clear();
        assertNotEquals(afterRemove, shared.visibilityEpoch());
    }

    private static AnimationMetadata metadata(int delay) {
        return new AnimationMetadata(
                new ArrayList<>(List.of(new AnimationMetadata.FrameData(delay, 0))), 1);
    }
}
