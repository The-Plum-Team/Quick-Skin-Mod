package com.quickskin.mod.client.importing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void anUploadedSkinNamedOverHttpIsTheSameTexture() {
        // The upload reply's spelling, which earlier versions also stored
        String uploaded = "http://textures.minecraft.net/texture/deleted";
        assertTrue(PlayerOwnSkinBootstrap.staysDeleted(true, false, DELETED, uploaded, true));
        assertEquals(DELETED, PlayerOwnSkinBootstrap.canonicalTextureUrl(uploaded));
        assertTrue(PlayerOwnSkinBootstrap.sameTexture(uploaded, DELETED));
    }

    @Test
    void otherAddressesAreNeverTakenForMojangsTexture() {
        assertFalse(PlayerOwnSkinBootstrap.sameTexture("http://example.com/texture/deleted", DELETED));
        assertFalse(PlayerOwnSkinBootstrap.sameTexture("https://textures.minecraft.net:8443/texture/deleted", DELETED));
        assertFalse(PlayerOwnSkinBootstrap.sameTexture("", ""));
        assertEquals("not a uri", PlayerOwnSkinBootstrap.canonicalTextureUrl("not a uri"));
        assertEquals("", PlayerOwnSkinBootstrap.canonicalTextureUrl(null));
    }
}
