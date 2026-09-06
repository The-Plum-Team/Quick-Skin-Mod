package com.quickskin.mod.client.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the trigger that lets a skin copied into the uploads folder from outside the game show up
 * in an open menu, and the throttle that keeps that check off the per-frame path.
 */
class LocalAssetFolderWatchTest {

    private static final long INTERVAL = LocalAssetFolderWatch.POLL_INTERVAL_MILLIS;

    @TempDir
    Path root;

    private Path skins;
    private Path capes;

    @BeforeEach
    void createUploadFolders() throws IOException {
        skins = Files.createDirectories(root.resolve("uploads/skins"));
        capes = Files.createDirectories(root.resolve("uploads/capes"));
    }

    // --- throttle -------------------------------------------------------------------------------

    @Test
    void firstPollRunsImmediatelyWhenAScreenOpens() {
        LocalAssetFolderWatch watch = new LocalAssetFolderWatch();

        assertTrue(watch.beginPoll(0L), "a freshly opened screen must check the folder at once");
    }

    @Test
    void ticksInsideTheIntervalDoNotStartAnotherPoll() {
        LocalAssetFolderWatch watch = new LocalAssetFolderWatch();
        long now = 10_000L;
        assertTrue(watch.beginPoll(now));
        watch.finishPoll();

        assertFalse(watch.beginPoll(now + 1L));
        assertFalse(watch.beginPoll(now + INTERVAL - 1L));
        assertTrue(watch.beginPoll(now + INTERVAL));
    }

    /**
     * Performance regression guard: the folder must not be walked once per client tick. A second of
     * ticking at 20 TPS may grant only the single opening poll.
     */
    @Test
    void oneSecondOfTicksGrantsOnlyTheOpeningPoll() {
        LocalAssetFolderWatch watch = new LocalAssetFolderWatch();
        int granted = 0;

        for (int tick = 0; tick < 20; tick++) {
            if (watch.beginPoll(tick * 50L)) {
                granted++;
                watch.finishPoll();
            }
        }

        assertEquals(1, granted, "expected exactly the on-open poll within the first second");
    }

    @Test
    void aSlowPollIsNeverOverlappedByTheNextTick() {
        LocalAssetFolderWatch watch = new LocalAssetFolderWatch();
        assertTrue(watch.beginPoll(0L));

        // The walk has not reported back yet, so no later tick may start a second one.
        assertFalse(watch.beginPoll(INTERVAL * 100L));
        assertTrue(watch.isPollInFlight());

        watch.finishPoll();
        assertTrue(watch.beginPoll(INTERVAL * 100L));
    }

    // --- change detection -----------------------------------------------------------------------

    @Test
    void anUnchangedFolderKeepsTheSameFingerprint() throws IOException {
        write(skins.resolve("steve.png"), 64);

        long first = LocalAssetFolderWatch.fingerprint(directories());
        long second = LocalAssetFolderWatch.fingerprint(directories());

        assertEquals(first, second);
    }

    @Test
    void aSkinCopiedInFromOutsideTheGameChangesTheFingerprint() throws IOException {
        write(skins.resolve("steve.png"), 64);
        long before = LocalAssetFolderWatch.fingerprint(directories());

        write(skins.resolve("dropped-by-hand.png"), 96);

        assertNotEquals(before, LocalAssetFolderWatch.fingerprint(directories()));
    }

    @Test
    void aCapeCopiedInFromOutsideTheGameChangesTheFingerprint() throws IOException {
        write(skins.resolve("steve.png"), 64);
        long before = LocalAssetFolderWatch.fingerprint(directories());

        write(capes.resolve("dropped-by-hand.png"), 128);

        assertNotEquals(before, LocalAssetFolderWatch.fingerprint(directories()));
    }

    @Test
    void deletingAFileChangesTheFingerprint() throws IOException {
        Path doomed = write(skins.resolve("steve.png"), 64);
        write(skins.resolve("alex.png"), 72);
        long before = LocalAssetFolderWatch.fingerprint(directories());

        Files.delete(doomed);

        assertNotEquals(before, LocalAssetFolderWatch.fingerprint(directories()));
    }

    @Test
    void rewritingAFileInPlaceChangesTheFingerprint() throws IOException {
        Path skin = write(skins.resolve("steve.png"), 64);
        long before = LocalAssetFolderWatch.fingerprint(directories());

        write(skin, 128);

        assertNotEquals(before, LocalAssetFolderWatch.fingerprint(directories()));
    }

    @Test
    void filesInNestedFoldersAreCovered() throws IOException {
        long before = LocalAssetFolderWatch.fingerprint(directories());

        Files.createDirectories(skins.resolve("packs/winter"));
        write(skins.resolve("packs/winter/santa.png"), 64);

        assertNotEquals(before, LocalAssetFolderWatch.fingerprint(directories()));
    }

    @Test
    void renamingAFileChangesTheFingerprint() throws IOException {
        Path skin = write(skins.resolve("steve.png"), 64);
        long before = LocalAssetFolderWatch.fingerprint(directories());

        Files.move(skin, skins.resolve("steve-renamed.png"));

        assertNotEquals(before, LocalAssetFolderWatch.fingerprint(directories()));
    }

    @Test
    void emptyAndMissingFoldersLookUnchangedInsteadOfThrowing() {
        assertEquals(0L, LocalAssetFolderWatch.fingerprint(List.of()));
        assertEquals(0L, LocalAssetFolderWatch.fingerprint(directories()));
        assertEquals(0L, LocalAssetFolderWatch.fingerprint(List.of(root.resolve("absent"))));
    }

    @Test
    void aNullDirectoryEntryIsSkipped() throws IOException {
        write(skins.resolve("steve.png"), 64);

        long withNull = LocalAssetFolderWatch.fingerprint(java.util.Arrays.asList(skins, null));

        assertEquals(LocalAssetFolderWatch.fingerprint(List.of(skins)), withNull);
    }

    @Test
    void filesOutsideTheConfiguredFoldersAreIgnored() throws IOException {
        write(skins.resolve("steve.png"), 64);
        long before = LocalAssetFolderWatch.fingerprint(directories());

        Files.createDirectories(root.resolve("elsewhere"));
        write(root.resolve("elsewhere/unrelated.png"), 64);

        assertEquals(before, LocalAssetFolderWatch.fingerprint(directories()));
    }

    private List<Path> directories() {
        return List.of(skins, capes);
    }

    private static Path write(Path path, int bytes) throws IOException {
        return Files.write(path, new byte[bytes]);
    }
}
