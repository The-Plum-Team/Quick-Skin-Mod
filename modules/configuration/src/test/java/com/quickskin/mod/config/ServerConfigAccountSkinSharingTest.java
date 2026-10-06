package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void theRuntimeVisibilityTravelsOnlyWithTheNetworkCopy() {
        ServerConfig config = ServerConfig.fromJson("{\"shareAccountSkinWithVanillaClients\":true}");

        assertFalse(config.toJson().contains("accountSkinVisibility"),
                "the file form never carries the runtime fact");
        for (String visibility : new String[] {ServerConfig.ACCOUNT_SKIN_SHARED,
                ServerConfig.ACCOUNT_SKIN_AFTER_REJOIN, ServerConfig.ACCOUNT_SKIN_UNAVAILABLE}) {
            ServerConfig received = ServerConfig.fromJson(config.toJson(visibility));
            assertEquals(visibility, received.accountSkinVisibility);
            assertTrue(received.shareAccountSkinWithVanillaClients);
        }
        assertNull(ServerConfig.fromJson(config.toJson(null)).accountSkinVisibility);
        assertNull(config.accountSkinVisibility, "sending never changes the server's own config");
    }

    @Test
    void anUnknownVisibilityReadsAsUnknown() {
        assertNull(ServerConfig.fromJson("{\"accountSkinVisibility\":\"everyone\"}").accountSkinVisibility);
        assertNull(ServerConfig.fromJson("{}").accountSkinVisibility);
        assertNull(ServerConfig.fromJson(ServerConfig.fromJson("{}").toJson("bogus")).accountSkinVisibility);
    }
}
