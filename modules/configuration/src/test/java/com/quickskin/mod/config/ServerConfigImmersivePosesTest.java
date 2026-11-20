package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigImmersivePosesTest {
    @Test
    void immersivePosesAreSharedByDefaultAndInOlderFiles() {
        assertTrue(ServerConfig.fromJson("{}").allowImmersivePoses);
        assertTrue(ServerConfig.fromJson("{\"allowHiddenArmor\":false}").allowImmersivePoses);
        assertTrue(ServerConfig.fromJson("not json").allowImmersivePoses);
    }

    @Test
    void anOperatorCanTurnThemOffAndClientsLearnIt() {
        ServerConfig disabled = ServerConfig.fromJson("{\"allowImmersivePoses\":false}");

        assertFalse(disabled.allowImmersivePoses);
        assertFalse(ServerConfig.fromJson(disabled.toJson()).allowImmersivePoses);
        assertFalse(ServerConfig.fromJson(disabled.toJson(null)).allowImmersivePoses);
    }
}
