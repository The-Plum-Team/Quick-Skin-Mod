package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigNicknamesTest {
    @Test
    void nicknamesAreAllowedByDefaultAndInOlderFiles() {
        assertTrue(ServerConfig.fromJson("{}").allowNicknames);
        assertTrue(ServerConfig.fromJson("{\"disableSkinTransparency\":true}").allowNicknames);
        assertTrue(ServerConfig.fromJson("not json").allowNicknames);
    }

    @Test
    void anOperatorCanTurnThemOffAndTheChoiceSurvivesTheRoundTrip() {
        ServerConfig disabled = ServerConfig.fromJson("{\"allowNicknames\":false}");

        assertFalse(disabled.allowNicknames);
        assertFalse(ServerConfig.fromJson(disabled.toJson()).allowNicknames);
        // Clients learn it from the synchronized copy
        assertFalse(ServerConfig.fromJson(disabled.toJson(null)).allowNicknames);
    }
}
