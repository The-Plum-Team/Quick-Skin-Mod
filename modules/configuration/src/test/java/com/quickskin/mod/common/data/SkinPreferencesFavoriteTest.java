package com.quickskin.mod.common.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The skin list's favorites, kept beside each skin's arm width. */
class SkinPreferencesFavoriteTest {
    @TempDir
    Path temporaryDirectory;

    private static final String SKIN = "sha256-" + "e".repeat(64);

    @Test
    void aFavoriteSurvivesASaveAndKeepsTheArmWidth() {
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        SkinPreferences preferences = new SkinPreferences();
        preferences.setModelType(SKIN, "slim");
        preferences.setFavorite(SKIN, true);
        preferences.save(file);

        SkinPreferences reloaded = SkinPreferences.load(file);
        assertTrue(reloaded.isFavorite(SKIN));
        assertEquals("slim", reloaded.getModelType(SKIN));

        reloaded.setFavorite(SKIN, false);
        assertFalse(reloaded.isFavorite(SKIN));
        assertEquals("slim", reloaded.getModelType(SKIN));
    }

    @Test
    void aFileFromBeforeFavoritesHasNone() throws Exception {
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        Files.writeString(file, "{\"preferences\":{\"" + SKIN + "\":{\"modelType\":\"classic\"}}}");
        SkinPreferences preferences = SkinPreferences.load(file);
        assertFalse(preferences.isFavorite(SKIN));
        assertEquals("classic", preferences.getModelType(SKIN));
    }

    @Test
    void unmarkingASkinWithNoPreferenceStoresNothingAndBadIdsAreIgnored() {
        SkinPreferences preferences = new SkinPreferences();
        preferences.setFavorite(SKIN, false);
        assertEquals(0, preferences.size());
        preferences.setFavorite("not-a-hash", true);
        assertEquals(0, preferences.size());
        assertFalse(preferences.isFavorite("not-a-hash"));
    }

    @Test
    void deletingASkinForgetsItsFavorite() {
        SkinPreferences preferences = new SkinPreferences();
        preferences.setFavorite(SKIN, true);
        preferences.remove(SKIN);
        assertFalse(preferences.isFavorite(SKIN));
    }
}
