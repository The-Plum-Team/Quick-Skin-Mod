package com.quickskin.mod.server.storage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedTextureBudgetTest {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccc";

    @Test
    void overBudgetReplacementPreservesPreviouslyAcceptedPins() {
        PinnedTextureBudget budget = new PinnedTextureBudget(10);
        UUID player = UUID.randomUUID();

        assertTrue(budget.tryReplace(player, Map.of(HASH_A, 6)));
        assertFalse(budget.tryReplace(player, Map.of(HASH_B, 11)));

        assertTrue(budget.isPinned(HASH_A));
        assertFalse(budget.isPinned(HASH_B));
        assertEquals(6, budget.pinnedBytes());
        assertEquals(1, budget.playerCount());
    }

    @Test
    void replacementCreditsReleasedBytesBeforeTestingTheCap() {
        PinnedTextureBudget budget = new PinnedTextureBudget(10);
        UUID player = UUID.randomUUID();
        assertTrue(budget.tryReplace(player, Map.of(HASH_A, 8)));

        assertTrue(budget.tryReplace(player, Map.of(HASH_B, 10)));

        assertFalse(budget.isPinned(HASH_A));
        assertTrue(budget.isPinned(HASH_B));
        assertEquals(10, budget.pinnedBytes());
    }

    @Test
    void sharedContentIsChargedOnceAndReleasedOnTheLastReference() {
        PinnedTextureBudget budget = new PinnedTextureBudget(10);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(budget.tryReplace(first, Map.of(HASH_A, 10)));
        assertTrue(budget.tryReplace(second, Map.of(HASH_A, 10)));
        assertEquals(10, budget.pinnedBytes());

        budget.releasePlayer(first);
        assertTrue(budget.isPinned(HASH_A));
        assertEquals(10, budget.pinnedBytes());
        budget.releasePlayer(second);
        assertFalse(budget.isPinned(HASH_A));
        assertEquals(0, budget.pinnedBytes());
    }

    @Test
    void sameBlobInTwoTypedSlotsStillConsumesOneCacheBlob() {
        PinnedTextureBudget budget = new PinnedTextureBudget(10);
        UUID player = UUID.randomUUID();
        LinkedHashMap<String, Integer> deDuplicatedTypedPins = new LinkedHashMap<>();
        deDuplicatedTypedPins.put(HASH_A, 7);
        deDuplicatedTypedPins.put(HASH_A, 7);

        assertTrue(budget.tryReplace(player, deDuplicatedTypedPins));
        assertEquals(7, budget.pinnedBytes());
        assertEquals(1, budget.textureCount());
    }

    @Test
    void deleteAndClearReleaseEveryAccountingDimension() {
        PinnedTextureBudget budget = new PinnedTextureBudget(20);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(budget.tryReplace(first, Map.of(HASH_A, 7, HASH_B, 5)));
        assertTrue(budget.tryReplace(second, Map.of(HASH_A, 7, HASH_C, 4)));

        budget.removeTexture(HASH_A);
        assertFalse(budget.isPinned(HASH_A));
        assertEquals(9, budget.pinnedBytes());
        assertEquals(2, budget.playerCount());

        budget.clear();
        assertEquals(0, budget.pinnedBytes());
        assertEquals(0, budget.playerCount());
        assertEquals(0, budget.textureCount());
    }
}
