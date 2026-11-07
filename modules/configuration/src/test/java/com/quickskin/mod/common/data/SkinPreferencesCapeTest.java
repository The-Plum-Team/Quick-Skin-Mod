package com.quickskin.mod.common.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A skin's own cape, kept beside its arm width and favourite mark. */
class SkinPreferencesCapeTest {
    @TempDir
    Path temporaryDirectory;

    private static final String SKIN = "sha256-" + "e".repeat(64);
    private static final String LOCAL_CAPE = "local_cape:sha256-" + "c".repeat(64);
    private static final String KNOWN_CAPE = "known:nether_portal";

    @Test
    void aSkinsCapeSurvivesASaveAndKeepsItsOtherChoices() {
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        SkinPreferences preferences = new SkinPreferences();
        preferences.setModelType(SKIN, "slim");
        preferences.setFavorite(SKIN, true);
        preferences.setCape(SKIN, LOCAL_CAPE);
        preferences.save(file);

        SkinPreferences reloaded = SkinPreferences.load(file);
        assertEquals(LOCAL_CAPE, reloaded.getCape(SKIN));
        assertEquals("slim", reloaded.getModelType(SKIN));
        assertTrue(reloaded.isFavorite(SKIN));

        reloaded.setCape(SKIN, KNOWN_CAPE);
        assertEquals(KNOWN_CAPE, reloaded.getCape(SKIN));
        reloaded.setCape(SKIN, null);
        assertNull(reloaded.getCape(SKIN));
        assertEquals("slim", reloaded.getModelType(SKIN));
        assertTrue(reloaded.isFavorite(SKIN));
    }

    @Test
    void onlyCapesTheCapeMenuCanPickAreKept() {
        SkinPreferences preferences = new SkinPreferences();
        for (String invalid : new String[] {"", "Notch", "local_cape:not-a-hash", "known:", "known:Nether Portal",
                "known:" + "a".repeat(65), "local_skin:sha256-" + "c".repeat(64)}) {
            preferences.setCape(SKIN, invalid);
            assertNull(preferences.getCape(SKIN), invalid);
        }
        assertEquals(0, preferences.size());
    }

    @Test
    void anInvalidCapeInTheFileIsDroppedAloneAndAFileFromBeforeHasNone() throws Exception {
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        Files.writeString(file, "{\"preferences\":{\"" + SKIN
                + "\":{\"modelType\":\"classic\",\"favorite\":true,\"cape\":\"local_cape:../../evil\"}}}");
        SkinPreferences preferences = SkinPreferences.load(file);
        assertNull(preferences.getCape(SKIN));
        assertEquals("classic", preferences.getModelType(SKIN));
        assertTrue(preferences.isFavorite(SKIN));

        Files.writeString(file, "{\"preferences\":{\"" + SKIN + "\":{\"modelType\":\"slim\"}}}");
        assertNull(SkinPreferences.load(file).getCape(SKIN));
    }

    @Test
    void takingAwayTheCapeOfASkinWithNoPreferenceStoresNothingAndDeletingASkinForgetsIt() {
        SkinPreferences preferences = new SkinPreferences();
        preferences.setCape(SKIN, null);
        assertEquals(0, preferences.size());
        preferences.setCape(SKIN, LOCAL_CAPE);
        preferences.remove(SKIN);
        assertNull(preferences.getCape(SKIN));
    }

    @Test
    void aSkinsElytraChoiceSurvivesASaveBesideItsCape() {
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        SkinPreferences preferences = new SkinPreferences();
        preferences.setCape(SKIN, LOCAL_CAPE);
        preferences.setElytra(SKIN, KNOWN_CAPE);
        preferences.save(file);
        SkinPreferences reloaded = SkinPreferences.load(file);
        assertEquals(KNOWN_CAPE, reloaded.getElytra(SKIN));
        assertEquals(LOCAL_CAPE, reloaded.getCape(SKIN));

        reloaded.setElytra(SKIN, "");
        assertEquals("", reloaded.getElytra(SKIN));
        reloaded.setElytra(SKIN, SkinPreferences.VANILLA_ELYTRA);
        assertEquals(SkinPreferences.VANILLA_ELYTRA, reloaded.getElytra(SKIN));
        reloaded.setElytra(SKIN, "Notch");
        assertEquals(SkinPreferences.VANILLA_ELYTRA, reloaded.getElytra(SKIN));
        reloaded.setElytra(SKIN, null);
        assertNull(reloaded.getElytra(SKIN));
        assertEquals(LOCAL_CAPE, reloaded.getCape(SKIN));
    }

    @Test
    void anInvalidElytraChoiceInTheFileIsDroppedAlone() throws Exception {
        Path file = temporaryDirectory.resolve("skin-preferences.json");
        Files.writeString(file, "{\"preferences\":{\"" + SKIN
                + "\":{\"modelType\":\"slim\",\"cape\":\"" + KNOWN_CAPE + "\",\"elytra\":\"local_cape:../x\"}}}");
        SkinPreferences preferences = SkinPreferences.load(file);
        assertNull(preferences.getElytra(SKIN));
        assertEquals(KNOWN_CAPE, preferences.getCape(SKIN));
        assertEquals("slim", preferences.getModelType(SKIN));
    }
}
