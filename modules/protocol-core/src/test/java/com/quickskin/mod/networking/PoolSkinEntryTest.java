package com.quickskin.mod.networking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pool skin's name is one command word; any other text is made into one. */
class PoolSkinEntryTest {
    @Test
    void aNameIsShortAsciiWithoutSpaces() {
        assertTrue(PoolSkinEntry.isValidName("Guard_2.v-1"));
        assertFalse(PoolSkinEntry.isValidName(""));
        assertFalse(PoolSkinEntry.isValidName("two words"));
        assertFalse(PoolSkinEntry.isValidName("Señor"));
        assertFalse(PoolSkinEntry.isValidName("x".repeat(TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS + 1)));
        assertEquals("guard", PoolSkinEntry.key("GuArD"));
    }

    @Test
    void otherTextBecomesAName() {
        assertEquals("Town_crier", PoolSkinEntry.nameFrom("Town crier", "skin"));
        assertEquals("Se_or_Pez", PoolSkinEntry.nameFrom("Señor  Pez!", "skin"));
        assertEquals("skin", PoolSkinEntry.nameFrom("¡¿!", "skin"));
        assertEquals(TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS,
                PoolSkinEntry.nameFrom("a".repeat(100), "skin").length());
        assertTrue(PoolSkinEntry.isValidName(PoolSkinEntry.nameFrom("  Guard (old) ", "skin")));
    }
}
