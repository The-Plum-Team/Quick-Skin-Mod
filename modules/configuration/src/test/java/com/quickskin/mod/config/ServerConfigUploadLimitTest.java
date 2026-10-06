package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigUploadLimitTest {
    @TempDir
    Path directory;
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

    @Test
    void aLimitOutsideTheIntRangeIsClampedInsteadOfDiscardingTheFile() throws IOException {
        Path file = write("{\"disableSkinTransparency\": true, \"skinChangeCooldownSeconds\": 30,"
                + " \"maxTextureUploadKilobytes\": 99999999999}");

        ServerConfig config = ServerConfig.load(file);

        assertTrue(config.disableSkinTransparency, "the other settings survive");
        assertEquals(30, config.skinChangeCooldownSeconds);
        assertEquals(16 * 1024, config.maxTextureUploadKilobytes);
        assertEquals(64, ServerConfig.load(write("{\"maxTextureUploadKilobytes\": -99999999999}"))
                .maxTextureUploadKilobytes);
        assertEquals(4096, ServerConfig.load(write("{\"maxTextureUploadKilobytes\": 4096.7}"))
                .maxTextureUploadKilobytes);
        // 2^32 + 1000 must clamp to the maximum, never wrap around to 1000.
        assertEquals(16 * 1024, ServerConfig.load(write(
                "{\"maxTextureUploadKilobytes\": 4294968296}")).maxTextureUploadKilobytes);
        assertEquals(86_400, ServerConfig.load(write(
                "{\"skinChangeCooldownSeconds\": 1e30}")).skinChangeCooldownSeconds);
    }

    @Test
    void aLimitThatIsNotANumberIsIgnoredAndTheRestIsKept() throws IOException {
        ServerConfig config = ServerConfig.load(write(
                "{\"skinChangeCooldownSeconds\": 45, \"maxTextureUploadKilobytes\": \"lots\"}"));

        assertEquals(45, config.skinChangeCooldownSeconds);
        assertEquals(16 * 1024, config.maxTextureUploadKilobytes);
        assertEquals(2048, ServerConfig.load(write("{\"maxTextureUploadKilobytes\": \"2048\"}"))
                .maxTextureUploadKilobytes);
        assertEquals(16 * 1024, ServerConfig.load(write("{\"maxTextureUploadKilobytes\": [1]}"))
                .maxTextureUploadKilobytes);
    }

    @Test
    void aFileThatCannotBeReadIsNeverOverwritten() throws IOException {
        for (String unreadable : new String[] {"{\"maxTextureUploadKilobytes\": 4096,", "null", "[1, 2]",
                "{\"disableSkinTransparency\": {\"nested\": true}}"}) {
            Path file = write(unreadable);
            byte[] before = Files.readAllBytes(file);

            ServerConfig config = ServerConfig.load(file);
            assertEquals(16 * 1024, config.maxTextureUploadKilobytes, unreadable);
            // ServerRuntime.prepareStop saves the configuration when the server stops.
            config.save(file);

            assertArrayEquals(before, Files.readAllBytes(file), unreadable);
        }
    }

    @Test
    void aReadableFileIsSavedBackWithTheEffectiveValues() throws IOException {
        Path file = write("{\"maxTextureUploadKilobytes\": 99999999999}");

        ServerConfig.load(file).save(file);

        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"maxTextureUploadKilobytes\": 16384"), saved);
        assertFalse(saved.contains("persistable"), saved);
    }

    @Test
    void aMissingFileIsCreatedWithTheDefaults() {
        Path file = directory.resolve("missing").resolve("quickskin-server.json");

        ServerConfig config = ServerConfig.load(file);

        assertEquals(16 * 1024, config.maxTextureUploadKilobytes);
        assertTrue(Files.isRegularFile(file));
    }

    private Path write(String json) throws IOException {
        Path file = Files.createTempFile(directory, "quickskin-server", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }
}
