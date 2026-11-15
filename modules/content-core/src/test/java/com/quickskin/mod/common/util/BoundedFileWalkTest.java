package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BoundedFileWalkTest {
    @TempDir
    Path folder;

    @Test
    void listsTheRegularFilesOfEveryFolder() throws IOException {
        Path nested = Files.createDirectories(folder.resolve("a").resolve("b"));
        Path top = Files.write(folder.resolve("top.png"), new byte[1]);
        Path deep = Files.write(nested.resolve("deep.png"), new byte[1]);

        BoundedFileWalk.Result walked = BoundedFileWalk.regularFiles(folder, 32, 4096);

        assertEquals(Set.of(top, deep), new HashSet<>(walked.files()));
        assertFalse(walked.capped());
        assertEquals(0, walked.skipped());
    }

    @Test
    void leavesOutAFolderItCannotReadAndKeepsTheRest() throws IOException {
        Path readable = Files.write(folder.resolve("readable.png"), new byte[1]);
        Path locked = Files.createDirectory(folder.resolve("locked"));
        Files.write(locked.resolve("hidden.png"), new byte[1]);
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            // An administrator reads the folder anyway; nothing to leave out then
            assumeFalse(Files.isReadable(locked));

            // The stream this walk replaces fails as a whole on the same folder
            assertThrows(java.io.UncheckedIOException.class, () -> {
                try (var paths = Files.walk(folder, 32)) {
                    paths.toList();
                }
            });

            BoundedFileWalk.Result walked = BoundedFileWalk.regularFiles(folder, 32, 4096);

            assertEquals(List.of(readable), walked.files());
            assertEquals(1, walked.skipped());
            assertFalse(walked.capped());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void stopsAtTheEntryCapAndSaysSo() throws IOException {
        for (int i = 0; i < 6; i++) {
            Files.write(folder.resolve("skin" + i + ".png"), new byte[1]);
        }

        // The folder itself is the first entry
        BoundedFileWalk.Result capped = BoundedFileWalk.regularFiles(folder, 32, 4);
        assertEquals(3, capped.files().size());
        assertTrue(capped.capped());

        BoundedFileWalk.Result exact = BoundedFileWalk.regularFiles(folder, 32, 7);
        assertEquals(6, exact.files().size());
        assertFalse(exact.capped());
    }

    @Test
    void goesNoDeeperThanAsked() throws IOException {
        Path nested = Files.createDirectory(folder.resolve("nested"));
        Path top = Files.write(folder.resolve("top.png"), new byte[1]);
        Files.write(nested.resolve("deep.png"), new byte[1]);

        assertEquals(List.of(top), BoundedFileWalk.regularFiles(folder, 1, 4096).files());
    }

    @Test
    void countsALinkToAFileAndStaysOutOfALinkedFolder() throws IOException {
        Path outside = Files.createDirectory(folder.resolve("outside"));
        Path target = Files.write(outside.resolve("target.png"), new byte[1]);
        Path scanned = Files.createDirectory(folder.resolve("scanned"));
        Path fileLink = scanned.resolve("link.png");
        try {
            Files.createSymbolicLink(fileLink, target);
            Files.createSymbolicLink(scanned.resolve("folder-link"), outside);
        } catch (IOException | UnsupportedOperationException noLinks) {
            assumeTrue(false, "this file system has no symbolic links");
        }

        assertEquals(List.of(fileLink), BoundedFileWalk.regularFiles(scanned, 32, 4096).files());
    }

    @Test
    void failsOnlyWhenTheFolderItselfCannotBeRead() {
        assertThrows(IOException.class, () -> BoundedFileWalk.regularFiles(folder.resolve("missing"), 32, 4096));
        assertThrows(IOException.class, () -> BoundedFileWalk.regularFiles(null, 32, 4096));
        assertThrows(IOException.class, () -> BoundedFileWalk.regularFiles(folder, 32, 0));
    }
}
