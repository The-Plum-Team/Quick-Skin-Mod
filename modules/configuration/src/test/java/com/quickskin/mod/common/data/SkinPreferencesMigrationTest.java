package com.quickskin.mod.common.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinPreferencesMigrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsStrongPreferenceBeforeRetiringLegacyKey() {
        String legacy = "a".repeat(40);
        String strong = "sha256-" + "b".repeat(64);
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        SkinPreferences preferences = new SkinPreferences();
        preferences.setModelType(legacy, "slim");
        preferences.save(file);

        assertTrue(preferences.migrateAliases(Map.of(legacy, strong), file));
        SkinPreferences reloaded = SkinPreferences.load(file);
        assertEquals("slim", reloaded.getModelType(strong));
        assertEquals("auto", reloaded.getModelType(legacy));
    }

    @Test
    void existingStrongPreferenceWinsOnMigration() {
        String legacy = "c".repeat(40);
        String strong = "sha256-" + "d".repeat(64);
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        SkinPreferences preferences = new SkinPreferences();
        preferences.setModelType(legacy, "slim");
        preferences.setModelType(strong, "classic");
        preferences.save(file);

        assertTrue(preferences.migrateAliases(Map.of(legacy, strong), file));
        assertEquals("classic", SkinPreferences.load(file).getModelType(strong));
    }

    @Test
    void failedDiskMigrationKeepsCanonicalRuntimeLookupAndLegacyRetryState() throws Exception {
        String legacy = "e".repeat(40);
        String strong = "sha256-" + "f".repeat(64);
        SkinPreferences preferences = new SkinPreferences();
        preferences.setModelType(legacy, "slim");
        Path nonDirectory = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(nonDirectory, "block writes below this path");

        assertFalse(preferences.migrateAliases(
                Map.of(legacy, strong), nonDirectory.resolve("skin-preferences.json")));
        assertEquals("slim", preferences.getModelType(strong));
        assertEquals("slim", preferences.getModelType(legacy));
    }
}
