package com.quickskin.mod.common.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinPreferencesUnreadableFileTest {
    @TempDir
    Path directory;

    @Test
    void aFileThatCannotBeParsedIsNeverSavedOver() throws Exception {
        Path file = directory.resolve("skin-preferences.json");
        String broken = "{\"preferences\": {\"" + "a".repeat(40) + "\": {\"favorite\": true},}";
        Files.writeString(file, broken, StandardCharsets.UTF_8);

        SkinPreferences preferences = SkinPreferences.load(file);
        assertFalse(preferences.isPersistable());
        preferences.setFavorite("b".repeat(40), true);
        preferences.save(file);
        assertFalse(preferences.migrateAliases(Map.of("b".repeat(40), "sha256-" + "c".repeat(64)), file));
        assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void aJsonNullFileIsLeftAsItIs() throws Exception {
        Path file = directory.resolve("skin-preferences.json");
        Files.writeString(file, "null", StandardCharsets.UTF_8);

        SkinPreferences preferences = SkinPreferences.load(file);
        preferences.setModelType("d".repeat(40), "slim");
        preferences.save(file);
        assertEquals("null", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void anEmptyFileStaysWritable() throws Exception {
        Path file = directory.resolve("skin-preferences.json");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        SkinPreferences preferences = SkinPreferences.load(file);
        assertTrue(preferences.isPersistable());
        preferences.setModelType("e".repeat(40), "slim");
        preferences.save(file);
        assertEquals("slim", SkinPreferences.load(file).getModelType("e".repeat(40)));
    }
}
