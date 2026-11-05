package com.quickskin.mod.client.storage;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAppearanceStorageTest {
    @TempDir Path directory;

    @Test
    void savesSuppliedSelectionAndMigratesOnlyAuthenticatedSkinIds() throws Exception {
        LocalAppearanceStorage storage = LocalAppearanceStorage.getInstance();
        storage.init(directory);
        UUID playerId = UUID.randomUUID();
        String legacy = "a".repeat(40);
        String strong = "sha256-" + "b".repeat(64);
        storage.savePlayerPreferences(playerId, legacy, "slim");
        assertTrue(storage.migrateContentIds(Map.of(legacy, strong)));

        LocalAppearanceStorage.PreferencesData data = new Gson().fromJson(
                Files.readString(directory.resolve("quickskin_preferences.json")),
                LocalAppearanceStorage.PreferencesData.class);
        assertEquals(strong, data.players.get(playerId.toString()).lastSkinId);
        assertEquals("slim", data.players.get(playerId.toString()).lastModelType);
        assertTrue(data.players.get(playerId.toString()).favorites.isEmpty());
    }

    @Test
    void anUnreadablePreferencesFileIsNeverSavedOver() throws Exception {
        LocalAppearanceStorage storage = LocalAppearanceStorage.getInstance();
        storage.init(directory);
        Path file = directory.resolve("quickskin_preferences.json");
        String broken = "{\"players\": {\"" + UUID.randomUUID() + "\": {\"lastSkinId\": [}}}";
        Files.writeString(file, broken);

        storage.savePlayerPreferences(UUID.randomUUID(), "a".repeat(40), "slim");
        assertFalse(storage.migrateContentIds(Map.of("a".repeat(40), "sha256-" + "b".repeat(64))));

        assertEquals(broken, Files.readString(file));
    }
}
