package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigAccountSkinSharingTest {
    @Test
    void sharingAccountSkinsIsOffByDefault() {
        assertFalse(ServerConfig.fromJson("{}").shareAccountSkinWithVanillaClients);
        assertFalse(ServerConfig.fromJson("{\"disableSkinTransparency\":true}")
                .shareAccountSkinWithVanillaClients);
        assertFalse(ServerConfig.fromJson("not json").shareAccountSkinWithVanillaClients);
    }

    @Test
    void anOperatorCanEnableItAndItSurvivesTheConfigRoundTrip() {
        ServerConfig enabled = ServerConfig.fromJson("{\"shareAccountSkinWithVanillaClients\":true}");

        assertTrue(enabled.shareAccountSkinWithVanillaClients);
        assertTrue(ServerConfig.fromJson(enabled.toJson()).shareAccountSkinWithVanillaClients);
        assertTrue(enabled.toJson().contains("\"shareAccountSkinWithVanillaClients\": true"));
    }
}
