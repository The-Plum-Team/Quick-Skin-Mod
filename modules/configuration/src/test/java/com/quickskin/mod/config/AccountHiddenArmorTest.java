package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each launcher account keeps the armour pieces it hides. */
class AccountHiddenArmorTest {
    private static final String STEVE =
            AccountSkinPreferences.key(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Steve");
    private static final String ALEX =
            AccountSkinPreferences.key(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Alex");

    @Test
    void eachAccountKeepsItsOwnPieces() {
        AccountHiddenArmor hidden = new AccountHiddenArmor();

        assertTrue(hidden.set(STEVE, 8));
        assertTrue(hidden.set(ALEX, 1 | 4));

        assertEquals(8, hidden.hidden(STEVE));
        assertEquals(5, hidden.hidden(ALEX));
        assertEquals(0, hidden.hidden(null));
        assertFalse(hidden.set(STEVE, 8), "the same pieces are no change");
    }

    @Test
    void showingEverythingForgetsTheAccountAndUnknownBitsAreDropped() {
        AccountHiddenArmor hidden = new AccountHiddenArmor();
        hidden.set(STEVE, 8);

        assertTrue(hidden.set(STEVE, 0));
        assertEquals(0, hidden.hidden(STEVE));
        assertFalse(hidden.set(STEVE, 0), "there was nothing left to forget");
        assertTrue(hidden.set(STEVE, 0xF0 | 2));
        assertEquals(2, hidden.hidden(STEVE));
        assertFalse(hidden.set("not a key", 1));
    }
}
