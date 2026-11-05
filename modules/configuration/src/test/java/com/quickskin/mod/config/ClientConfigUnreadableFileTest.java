package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigUnreadableFileTest {
    @TempDir Path directory;

    @Test
    void aFileThatCannotBeParsedIsNeverSavedOver() throws Exception {
        Path file = directory.resolve("quickskin-client.json");
        String broken = "{\"activeSkinHash\": \"" + "a".repeat(40) + "\", \"previewScale\": 30.5,}";
        Files.writeString(file, broken, StandardCharsets.UTF_8);

        ClientConfig config = ClientConfig.load(file);
        assertEquals("", config.activeSkinHash);
        assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8));

        config.activeSkinHash = "b".repeat(40);
        assertFalse(config.save(file));
        assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void aJsonNullFileIsLeftAsItIs() throws Exception {
        Path file = directory.resolve("quickskin-client.json");
        Files.writeString(file, "null", StandardCharsets.UTF_8);

        assertFalse(ClientConfig.load(file).save(file));
        assertEquals("null", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void aMissingFileIsWrittenWithTheDefaultsAndStaysWritable() throws Exception {
        Path file = directory.resolve("quickskin-client.json");

        ClientConfig config = ClientConfig.load(file);
        assertTrue(Files.isRegularFile(file));
        config.activeSkinHash = "c".repeat(40);
        assertTrue(config.save(file));
        assertEquals("c".repeat(40), ClientConfig.load(file).activeSkinHash);
    }

    @Test
    void anEmptyOrBlankFileIsReplacedWithTheDefaultsAndStaysWritable() throws Exception {
        for (String blank : new String[] {"", "  \n\t"}) {
            Path file = directory.resolve("blank.json");
            Files.writeString(file, blank, StandardCharsets.UTF_8);

            ClientConfig config = ClientConfig.load(file);
            assertTrue(Files.size(file) > blank.length());
            config.activeSkinHash = "d".repeat(40);
            assertTrue(config.save(file));
            assertEquals("d".repeat(40), ClientConfig.load(file).activeSkinHash);
            Files.delete(file);
        }
    }
}
