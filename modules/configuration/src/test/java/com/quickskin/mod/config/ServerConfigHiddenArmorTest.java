package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigHiddenArmorTest {
    @Test
    void hiddenArmorIsAllowedByDefaultAndInOlderFiles() {
        assertTrue(ServerConfig.fromJson("{}").allowHiddenArmor);
        assertTrue(ServerConfig.fromJson("{\"allowNicknames\":false}").allowHiddenArmor);
        assertTrue(ServerConfig.fromJson("not json").allowHiddenArmor);
    }

    @Test
    void anOperatorCanTurnItOffAndClientsLearnIt() {
        ServerConfig disabled = ServerConfig.fromJson("{\"allowHiddenArmor\":false}");

        assertFalse(disabled.allowHiddenArmor);
        assertFalse(ServerConfig.fromJson(disabled.toJson()).allowHiddenArmor);
        assertFalse(ServerConfig.fromJson(disabled.toJson(null)).allowHiddenArmor);
    }
}
