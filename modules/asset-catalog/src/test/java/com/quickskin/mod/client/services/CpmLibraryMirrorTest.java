package com.quickskin.mod.client.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpmLibraryMirrorTest {
    private static final int MAX = 1 << 20;

    @TempDir
    Path temp;

    @Test
    void modelsTravelBothWaysAndOnlyManagedCopiesAreRemoved() throws Exception {
        Path library = temp.resolve("library");
        Path playerModels = temp.resolve("player_models");
        Files.createDirectories(library);
        Files.createDirectories(playerModels.resolve("mine"));
        Files.write(playerModels.resolve("mine/Robot.cpmmodel"), new byte[] {1, 2, 3});
        Files.write(library.resolve("Knight.cpmmodel"), new byte[] {4, 5, 6});

        CpmLibraryMirror.Result first = CpmLibraryMirror.sync(library, playerModels, MAX);
        assertEquals(1, first.addedToLibrary());
        assertEquals(1, first.addedToInstance());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(library.resolve("Robot.cpmmodel")));
        Path mirrored = playerModels.resolve(CpmLibraryMirror.MIRROR_FOLDER).resolve("Knight.cpmmodel");
        assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(mirrored));

        // In step: a second pass changes nothing
        assertFalse(CpmLibraryMirror.sync(library, playerModels, MAX).changed());

        // The Knight was deleted in another instance: its managed copy goes, the player's own stays
        Files.delete(library.resolve("Knight.cpmmodel"));
        Files.delete(library.resolve("Robot.cpmmodel"));
        CpmLibraryMirror.Result after = CpmLibraryMirror.sync(library, playerModels, MAX);
        assertEquals(1, after.removedFromInstance());
        assertFalse(Files.exists(mirrored));
        assertTrue(Files.exists(playerModels.resolve("mine/Robot.cpmmodel")));
        // ...and the player's own model joins the library again
        assertEquals(1, after.addedToLibrary());
    }

    @Test
    void aModelWithTheSameContentUnderAnotherNameIsNotCopiedAgain() throws Exception {
        Path library = temp.resolve("library");
        Path playerModels = temp.resolve("player_models");
        Files.createDirectories(library);
        Files.createDirectories(playerModels);
        Files.write(library.resolve("A.cpmmodel"), new byte[] {9});
        Files.write(playerModels.resolve("B.cpmmodel"), new byte[] {9});
        assertFalse(CpmLibraryMirror.sync(library, playerModels, MAX).changed());
    }
}
