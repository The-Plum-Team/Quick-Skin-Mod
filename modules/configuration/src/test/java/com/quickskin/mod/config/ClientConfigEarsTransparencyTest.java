package com.quickskin.mod.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ears keeps extra data of a skin in the alpha of its body. With Ears installed skins are shown
 * without that transparency, and are still saved with it.
 */
class ClientConfigEarsTransparencyTest {

    @AfterEach
    void askTheLoaderAgain() {
        ClientConfig.assumeEarsInstalled(null);
    }

    @Test
    void withoutEarsOnlyThePlayerOrTheServerTurnsTransparencyOff() {
        ClientConfig.assumeEarsInstalled(false);
        ClientConfig config = ClientConfig.parse("{}");
        assertFalse(config.shouldDisableSkinTransparency());
        assertFalse(config.shouldFlattenImportedSkins());

        config.disableSkinTransparency = true;
        assertTrue(config.shouldDisableSkinTransparency());
        assertTrue(config.shouldFlattenImportedSkins());

        config.disableSkinTransparency = false;
        config.applyServerOverride(ServerConfig.fromJson("{\"disableSkinTransparency\": true}"));
        assertTrue(config.shouldDisableSkinTransparency());
        assertTrue(config.shouldFlattenImportedSkins());
    }

    @Test
    void withEarsSkinsAreShownOpaqueWhateverThePlayerChose() {
        ClientConfig.assumeEarsInstalled(true);
        ClientConfig config = ClientConfig.parse("{}");
        assertFalse(config.disableSkinTransparency, "the player's own choice is not changed");
        assertTrue(config.shouldDisableSkinTransparency());
    }

    @Test
    void withEarsAnImportedSkinKeepsTheAlphaEarsReads() {
        ClientConfig.assumeEarsInstalled(true);
        ClientConfig config = ClientConfig.parse("{\"disableSkinTransparency\": true}");
        assertFalse(config.shouldFlattenImportedSkins(), "not even when the player turned transparency off");

        config.applyServerOverride(ServerConfig.fromJson("{\"disableSkinTransparency\": true}"));
        assertFalse(config.shouldFlattenImportedSkins(), "nor when the server did");
        assertTrue(config.shouldDisableSkinTransparency());
    }

    @Test
    void outsideTheGameNoModIsInstalled() {
        ClientConfig.assumeEarsInstalled(null);
        assertFalse(ClientConfig.isEarsInstalled());
    }
}
