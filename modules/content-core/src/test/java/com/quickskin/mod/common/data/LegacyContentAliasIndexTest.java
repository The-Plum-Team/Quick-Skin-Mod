package com.quickskin.mod.common.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyContentAliasIndexTest {
    private static final String FIRST = ContentId.SHA256_PREFIX + "1".repeat(64);
    private static final String SECOND = ContentId.SHA256_PREFIX + "2".repeat(64);

    @Test
    void oneAssetCanRetainMultipleHistoricalNames() {
        LegacyContentAliasIndex index = new LegacyContentAliasIndex(2, 3);
        String raw = "a".repeat(40);
        String domainSeparated = "b".repeat(40);

        assertTrue(index.register(FIRST, List.of(raw, domainSeparated)));
        assertEquals(FIRST, index.resolve(FIRST));
        assertEquals(FIRST, index.resolve(raw));
        assertEquals(FIRST, index.resolve(domainSeparated));
        assertEquals(Map.of(raw, FIRST, domainSeparated, FIRST), index.uniqueAliases());
    }

    @Test
    void collisionDisablesOnlyTheAmbiguousWeakName() {
        LegacyContentAliasIndex index = new LegacyContentAliasIndex(2, 2);
        String collision = "c".repeat(40);
        String unique = "d".repeat(40);

        assertTrue(index.register(FIRST, List.of(collision, unique)));
        assertTrue(index.register(SECOND, List.of(collision)));

        assertNull(index.resolve(collision));
        assertTrue(index.isAmbiguous(collision));
        assertEquals(FIRST, index.resolve(unique));
        assertEquals(FIRST, index.resolve(FIRST));
        assertEquals(SECOND, index.resolve(SECOND));
        assertFalse(index.uniqueAliases().containsKey(collision));
    }

    @Test
    void invalidOrOverBoundRegistrationIsAtomic() {
        LegacyContentAliasIndex index = new LegacyContentAliasIndex(1, 1);
        String firstAlias = "e".repeat(40);

        assertFalse(index.register(FIRST, List.of("not-a-content-id")));
        assertEquals(0, index.size());
        assertTrue(index.register(FIRST, List.of(firstAlias)));
        assertFalse(index.register(SECOND, List.of("f".repeat(40))));
        assertNull(index.resolve(SECOND));
        assertEquals(FIRST, index.resolve(firstAlias));
    }
}
