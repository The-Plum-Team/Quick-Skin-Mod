package com.quickskin.mod.client.importing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An own skin the player deleted is not downloaded again until the account's skin changes. */
class PlayerOwnSkinBootstrapTest {
    private static final String DELETED = "https://textures.minecraft.net/texture/deleted";
    private static final String CHANGED = "https://textures.minecraft.net/texture/changed";

    @Test
    void theSameTextureStaysDeleted() {
        assertTrue(PlayerOwnSkinBootstrap.staysDeleted(true, false, DELETED, DELETED, true));
    }

    @Test
    void aDefaultSkinNeverTakesTheDeletedOnesPlace() {
        assertTrue(PlayerOwnSkinBootstrap.staysDeleted(true, true, CHANGED, DELETED, true));
    }

    @Test
    void aChangedAccountSkinIsListed() {
        assertFalse(PlayerOwnSkinBootstrap.staysDeleted(true, false, CHANGED, DELETED, true));
    }

    @Test
    void itIsListedAgainWhenNoOtherSkinIsLeft() {
        assertFalse(PlayerOwnSkinBootstrap.staysDeleted(true, false, DELETED, DELETED, false));
        assertFalse(PlayerOwnSkinBootstrap.staysDeleted(true, true, CHANGED, DELETED, false));
    }

    @Test
    void anOwnSkinThePlayerKeptIsNotHeldBack() {
        assertFalse(PlayerOwnSkinBootstrap.staysDeleted(false, false, DELETED, DELETED, true));
    }
}
