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

        // The strong entry differs, so the legacy one is kept rather than discarded.
        assertFalse(preferences.migrateAliases(Map.of(legacy, strong), file));
        SkinPreferences reloaded = SkinPreferences.load(file);
        assertEquals("classic", reloaded.getModelType(strong));
        assertEquals("slim", reloaded.getModelType(legacy));
    }

    @Test
    void anEqualStrongPreferenceRetiresTheLegacyKey() {
        String legacy = "1".repeat(40);
        String strong = "sha256-" + "2".repeat(64);
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        SkinPreferences preferences = new SkinPreferences();
        preferences.setModelType(legacy, "slim");
        preferences.setModelType(strong, "slim");
        preferences.save(file);

        assertTrue(preferences.migrateAliases(Map.of(legacy, strong), file));
        SkinPreferences reloaded = SkinPreferences.load(file);
        assertEquals(1, reloaded.size());
        assertEquals("slim", reloaded.getModelType(strong));
    }

    @Test
    void aChangeBeforeTheMigrationIsWrittenKeepsTheLegacySettingsItDoesNotTouch() throws Exception {
        String legacy = "3".repeat(40);
        String strong = "sha256-" + "4".repeat(64);
        SkinPreferences preferences = new SkinPreferences();
        preferences.setModelType(legacy, "slim");
        preferences.setFavorite(legacy, true);
        Path nonDirectory = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(nonDirectory, "block writes below this path");
        assertFalse(preferences.migrateAliases(
                Map.of(legacy, strong), nonDirectory.resolve("skin-preferences.json")));

        // Unmarking the favourite through the strong id unmarks it and keeps the slim model.
        preferences.setFavorite(strong, false);
        assertFalse(preferences.isFavorite(strong));
        assertEquals("slim", preferences.getModelType(strong));

        // A later migration keeps the player's change and retires the legacy entry.
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        assertTrue(preferences.migrateAliases(Map.of(legacy, strong), file));
        SkinPreferences reloaded = SkinPreferences.load(file);
        assertEquals(1, reloaded.size());
        assertFalse(reloaded.isFavorite(strong));
        assertEquals("slim", reloaded.getModelType(strong));
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

    @Test
    void deletingTheSkinAlsoDropsALegacyEntryTheMigrationKept() {
        String legacy = "7".repeat(40);
        String strong = "sha256-" + "8".repeat(64);
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        SkinPreferences preferences = new SkinPreferences();
        preferences.setModelType(legacy, "slim");
        preferences.setModelType(strong, "classic");
        preferences.save(file);

        assertFalse(preferences.migrateAliases(Map.of(legacy, strong), file));
        preferences.remove(strong);
        preferences.save(file);
        assertEquals(0, SkinPreferences.load(file).size());
    }
}
