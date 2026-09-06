package com.quickskin.mod.client.storage;

import com.quickskin.mod.common.data.AnimationMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static AnimationMetadata metadata(int delay) {
        return new AnimationMetadata(
                new ArrayList<>(List.of(new AnimationMetadata.FrameData(delay, 0))), 1);
    }
}
