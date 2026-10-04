package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalLookPreviewTest {
    private static final String SAVED = "sha256-saved";
    private static final String OWN = "sha256-own";

    @Test
    void quickSkinLookShowsTheSavedSkinInWorldAndOnTheTitleScreen() {
        assertEquals(SAVED, LocalLookPreview.skinHash(SAVED, OWN, false, true));
        assertEquals(SAVED, LocalLookPreview.skinHash(SAVED, OWN, false, false));
    }

    @Test
    void quickSkinLookWithoutASavedSkinShowsTheVanillaSkin() {
        assertEquals("", LocalLookPreview.skinHash("", OWN, false, true));
        assertEquals("", LocalLookPreview.skinHash(null, OWN, false, false));
    }

    @Test
    void cpmModelLookInWorldShowsTheLivePlayersOwnSkinNotTheSavedOne() {
        assertEquals("", LocalLookPreview.skinHash(SAVED, OWN, true, true));
        assertEquals("", LocalLookPreview.skinHash(SAVED, "", true, true));
    }

    @Test
    void cpmModelLookOnTheTitleScreenShowsTheImportedOwnSkin() {
        assertEquals(OWN, LocalLookPreview.skinHash(SAVED, OWN, true, false));
    }

    @Test
    void cpmModelLookOnTheTitleScreenWithoutAnOwnSkinFallsBackToTheDefault() {
        assertEquals("", LocalLookPreview.skinHash(SAVED, "", true, false));
        assertEquals("", LocalLookPreview.skinHash(SAVED, null, true, false));
    }
}
