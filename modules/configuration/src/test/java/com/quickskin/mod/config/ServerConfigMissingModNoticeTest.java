package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The message for players without Quick Skin is on by default and never leaves the server. */
class ServerConfigMissingModNoticeTest {
    @Test
    void theMessageIsOnByDefaultAndLinksToModrinth() {
        ServerConfig config = ServerConfig.fromJson("{}");

        assertTrue(config.notifyPlayersWithoutQuickSkin);
        assertEquals("", config.playersWithoutQuickSkinMessage);
        assertEquals("https://modrinth.com/mod/quick-skin", config.playersWithoutQuickSkinLink);
    }

    @Test
    void anOperatorCanTurnItOffOrRewordItAndTheFileKeepsThat() {
        ServerConfig config = ServerConfig.fromJson("{\"notifyPlayersWithoutQuickSkin\":false,"
                + "\"playersWithoutQuickSkinMessage\":\"Get our pack\","
                + "\"playersWithoutQuickSkinLink\":\" https://example.com/pack \"}");

        ServerConfig reloaded = ServerConfig.fromJson(config.toJson());
        assertFalse(reloaded.notifyPlayersWithoutQuickSkin);
        assertEquals("Get our pack", reloaded.playersWithoutQuickSkinMessage);
        assertEquals("https://example.com/pack", reloaded.playersWithoutQuickSkinLink);
    }

    @Test
    void clientsNeverReceiveTheseSettings() {
        ServerConfig config = ServerConfig.fromJson("{\"playersWithoutQuickSkinMessage\":\"Hi\"}");

        String sent = config.toJson(ServerConfig.ACCOUNT_SKIN_SHARED);
        assertFalse(sent.contains("notifyPlayersWithoutQuickSkin"), sent);
        assertFalse(sent.contains("playersWithoutQuickSkinMessage"), sent);
        assertFalse(sent.contains("playersWithoutQuickSkinLink"), sent);
        assertTrue(config.toJson().contains("playersWithoutQuickSkinMessage"), "the file keeps them");
    }

    @Test
    void longOrMissingTextIsBounded() {
        String longText = "x".repeat(ServerConfig.MAX_NOTICE_TEXT_LENGTH + 100);
        ServerConfig config = ServerConfig.fromJson("{\"playersWithoutQuickSkinMessage\":\"" + longText
                + "\",\"playersWithoutQuickSkinLink\":null}");

        assertEquals(ServerConfig.MAX_NOTICE_TEXT_LENGTH, config.playersWithoutQuickSkinMessage.length());
        assertEquals(ServerConfig.DEFAULT_NOTICE_LINK, config.playersWithoutQuickSkinLink);
        assertEquals("", ServerConfig.fromJson("{\"playersWithoutQuickSkinMessage\":null}")
                .playersWithoutQuickSkinMessage);
    }
}
