package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigUploadLimitTest {
    @Test
    void aConfigWrittenByAnEarlierReleaseKeepsTheHardCap() {
        ServerConfig config = ServerConfig.fromJson(
                "{\"disableSkinTransparency\": false, \"skinChangeCooldownSeconds\": 0}");

        assertEquals(16 * 1024, config.maxTextureUploadKilobytes);
        assertEquals(16 * 1024 * 1024, config.maxTextureUploadBytes());
    }

    @Test
    void anAdministratorLimitIsKeptInBytes() {
        ServerConfig config = ServerConfig.fromJson("{\"maxTextureUploadKilobytes\": 2048}");

        assertEquals(2 * 1024 * 1024, config.maxTextureUploadBytes());
    }

    @Test
    void outOfRangeLimitsAreClampedToWhatEveryPeerCanCarry() {
        assertEquals(64 * 1024,
                ServerConfig.fromJson("{\"maxTextureUploadKilobytes\": 0}").maxTextureUploadBytes());
        assertEquals(64 * 1024,
                ServerConfig.fromJson("{\"maxTextureUploadKilobytes\": -5}").maxTextureUploadBytes());
        assertEquals(16 * 1024 * 1024, ServerConfig.fromJson(
                "{\"maxTextureUploadKilobytes\": 2147483647}").maxTextureUploadBytes());
    }

    @Test
    void theLimitTravelsWithTheSynchronizedConfiguration() {
        ServerConfig config = ServerConfig.fromJson("{\"maxTextureUploadKilobytes\": 512}");

        assertTrue(config.toJson().contains("\"maxTextureUploadKilobytes\": 512"));
    }
}
