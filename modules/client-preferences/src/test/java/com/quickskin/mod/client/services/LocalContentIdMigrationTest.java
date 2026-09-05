package com.quickskin.mod.client.services;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalContentIdMigrationTest {
    private static final String LEGACY = "a".repeat(40);
    private static final String STRONG = "sha256-" + "b".repeat(64);

    @Test
    void migratesBareAndPrefixedLocalReferences() {
        Map<String, String> aliases = Map.of(LEGACY, STRONG);

        assertEquals(STRONG, LocalContentIdMigration.migrateBareId(LEGACY, aliases));
        assertEquals("local_cape:" + STRONG,
                LocalContentIdMigration.migrateCapeReference(
                        "local_cape:" + LEGACY, aliases));
        assertEquals("known:unchanged",
                LocalContentIdMigration.migrateCapeReference("known:unchanged", aliases));
    }

    @Test
    void rejectsInvalidDirectionsAtTheMigrationBoundary() {
        Map<String, String> aliases = LocalContentIdMigration.validatedAliases(Map.of(
                LEGACY, STRONG,
                "sha256-" + "c".repeat(64), "d".repeat(40),
                "invalid", STRONG));

        assertEquals(Map.of(LEGACY, STRONG), aliases);
        assertFalse(aliases.containsKey("invalid"));
    }
}
