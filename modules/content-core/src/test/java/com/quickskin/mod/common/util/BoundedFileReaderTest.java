package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedFileReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsAFileExactlyAtTheLimit() throws IOException {
        Path file = temporaryDirectory.resolve("exact.txt");
        byte[] content = "quickskin".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        assertArrayEquals(content, BoundedFileReader.readBytes(file, content.length));
        assertEquals("quickskin", BoundedFileReader.readUtf8(file, content.length));
    }

    @Test
    void rejectsAnOversizedFileBeforeReturningPartialContent() throws IOException {
        Path file = temporaryDirectory.resolve("large.bin");
        Files.write(file, new byte[33]);

        assertThrows(IOException.class, () -> BoundedFileReader.readBytes(file, 32));
    }

    @Test
    void rejectsDirectoriesAndMissingFiles() {
        assertThrows(IOException.class,
                () -> BoundedFileReader.readBytes(temporaryDirectory, 32));
        assertThrows(IOException.class,
                () -> BoundedFileReader.readBytes(temporaryDirectory.resolve("missing"), 32));
    }
}
