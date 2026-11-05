package com.quickskin.mod.networking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiddenArmorTest {
    @Test
    void masksNameOnlyTheFourPieces() {
        assertTrue(HiddenArmor.isValid(HiddenArmor.NONE));
        assertTrue(HiddenArmor.isValid(HiddenArmor.ALL));
        assertFalse(HiddenArmor.isValid(1 << 4));
        assertFalse(HiddenArmor.isValid(-1));
        assertEquals(15, HiddenArmor.ALL);
    }

    @Test
    void togglingHidesAndShowsOnePiece() {
        int mask = HiddenArmor.toggle(HiddenArmor.NONE, HiddenArmor.HEAD);
        assertTrue(HiddenArmor.hides(mask, HiddenArmor.HEAD));
        assertFalse(HiddenArmor.hides(mask, HiddenArmor.CHEST));
        mask = HiddenArmor.toggle(mask, HiddenArmor.CHEST);
        assertEquals(HiddenArmor.HEAD | HiddenArmor.CHEST, mask);
        assertEquals(HiddenArmor.CHEST, HiddenArmor.toggle(mask, HiddenArmor.HEAD));
    }

    @Test
    void cosmeticPiecesSitFourBitsAboveTheirSlot() {
        assertEquals(HiddenArmor.HEAD << 4, HiddenArmor.cosmetic(HiddenArmor.HEAD));
        assertEquals(240, HiddenArmor.COSMETICS);
        assertEquals(HiddenArmor.HEAD, HiddenArmor.slot(HiddenArmor.cosmetic(HiddenArmor.HEAD)));
        assertEquals(HiddenArmor.FEET, HiddenArmor.slot(HiddenArmor.FEET));
        assertTrue(HiddenArmor.isCosmetic(HiddenArmor.cosmetic(HiddenArmor.LEGS)));
        assertFalse(HiddenArmor.isCosmetic(HiddenArmor.LEGS));
        assertFalse(HiddenArmor.isCosmetic(HiddenArmor.NONE));
    }

    @Test
    void cosmeticPiecesAreValidOnlyWhereTheyAreAllowed() {
        int mask = HiddenArmor.HEAD | HiddenArmor.cosmetic(HiddenArmor.CHEST);
        assertFalse(HiddenArmor.isValid(mask));
        assertTrue(HiddenArmor.isValidWithCosmetics(mask));
        assertTrue(HiddenArmor.isValidWithCosmetics(HiddenArmor.ALL | HiddenArmor.COSMETICS));
        assertFalse(HiddenArmor.isValidWithCosmetics(1 << 8));
        assertEquals(HiddenArmor.HEAD, HiddenArmor.toggle(mask, HiddenArmor.cosmetic(HiddenArmor.CHEST)));
    }
}
