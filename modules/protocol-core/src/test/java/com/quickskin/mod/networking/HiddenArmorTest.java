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
}
