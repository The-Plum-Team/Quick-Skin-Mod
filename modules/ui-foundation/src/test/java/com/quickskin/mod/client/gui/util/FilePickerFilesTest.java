package com.quickskin.mod.client.gui.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FilePickerFilesTest {
    @TempDir Path directory;

    @Test
    void showsDirectoriesFirstAndOnlyTheRequestedImportTypes() throws Exception {
        Files.createDirectory(directory.resolve("models"));
        Files.createFile(directory.resolve("Z skin.PNG"));
        Files.createFile(directory.resolve("a.cpmmodel"));
        Files.createFile(directory.resolve("cape.gif"));
        Files.createFile(directory.resolve("fake.png.exe"));
        Files.createFile(directory.resolve("notes.txt"));

        var files = FilePickerFiles.list(directory, Set.of("png", "cpmmodel"));
        assertEquals(java.util.List.of("models", "a.cpmmodel", "Z skin.PNG"),
                files.stream().map(entry -> entry.path().getFileName().toString()).toList());
        assertTrue(files.get(0).directory());
        assertFalse(files.get(1).directory());
        assertEquals(java.util.List.of("models", "cape.gif", "Z skin.PNG"),
                FilePickerFiles.list(directory, Set.of("png", "gif")).stream()
                        .map(entry -> entry.path().getFileName().toString()).toList());
    }

    @Test
    void treatsAnEmptyFolderAsAnEmptyResultButReportsAnInvalidDirectory() throws Exception {
        assertTrue(FilePickerFiles.list(directory, Set.of("png")).isEmpty());
        assertThrows(IOException.class,
                () -> FilePickerFiles.list(directory.resolve("missing"), Set.of("png")));
        Path file = Files.createFile(directory.resolve("skin.png"));
        assertThrows(IOException.class, () -> FilePickerFiles.list(file, Set.of("png")));
    }

    @Test
    void boundsScansEvenWhenEveryFileHasAnUnsupportedExtension() throws Exception {
        for (int i = 0; i <= FilePickerFiles.MAX_DIRECTORY_ENTRIES; i++) {
            Files.createFile(directory.resolve(i + ".txt"));
        }
        assertThrows(IOException.class, () -> FilePickerFiles.list(directory, Set.of("png")));
    }
}
