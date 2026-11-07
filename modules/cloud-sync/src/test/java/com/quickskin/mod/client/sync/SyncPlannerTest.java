package com.quickskin.mod.client.sync;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncPlannerTest {
    private static final String A = "sha256-" + "a".repeat(64);
    private static final String B = "sha256-" + "b".repeat(64);

    private static LibraryEntry skin(String id, String name, long rev) {
        return new LibraryEntry(LibraryEntry.SKIN, id, name, "auto", false, false, rev);
    }

    private static Map<String, LibraryEntry> map(LibraryEntry... entries) {
        Map<String, LibraryEntry> map = new HashMap<>();
        for (LibraryEntry entry : entries) map.put(entry.key(), entry);
        return map;
    }

    private static SyncPlanner.Plan plan(Map<String, LibraryEntry> local, Map<String, LibraryEntry> known,
                                         List<LibraryEntry> remote) {
        return SyncPlanner.plan(local, known, remote, Set.of());
    }

    @Test
    void aFirstRoundMergesBothLibrariesWithoutDeletingAnything() {
        SyncPlanner.Plan plan = plan(map(skin(A, "Mine", 0)), Map.of(), List.of(skin(B, "Cloud", 5)));
        assertEquals(List.of(skin(B, "Cloud", 5)), plan.downloads());
        assertEquals(List.of(skin(A, "Mine", 0)), plan.uploads());
        assertTrue(plan.localDeletes().isEmpty());
        assertTrue(plan.cloudDeletes().isEmpty());
    }

    @Test
    void aSkinDeletedHereIsDeletedInTheCloud() {
        Map<String, LibraryEntry> known = map(skin(A, "Mine", 3));
        SyncPlanner.Plan plan = plan(Map.of(), known, List.of());
        assertEquals(1, plan.cloudDeletes().size());
        assertTrue(plan.cloudDeletes().get(0).deleted());
        assertTrue(plan.downloads().isEmpty());
    }

    @Test
    void aSkinDeletedElsewhereIsDeletedHere() {
        Map<String, LibraryEntry> known = map(skin(A, "Mine", 3));
        SyncPlanner.Plan plan = plan(map(skin(A, "Mine", 0)), known,
                List.of(LibraryEntry.tombstone(LibraryEntry.SKIN, A, 4)));
        assertEquals(1, plan.localDeletes().size());
        assertTrue(plan.uploads().isEmpty());
        assertTrue(plan.known().get(LibraryEntry.key(LibraryEntry.SKIN, A)).deleted());
    }

    @Test
    void aSkinImportedAgainAfterItsDeletionIsUploadedAgain() {
        Map<String, LibraryEntry> known = map(LibraryEntry.tombstone(LibraryEntry.SKIN, A, 4));
        SyncPlanner.Plan plan = plan(map(skin(A, "Back", 0)), known,
                List.of(LibraryEntry.tombstone(LibraryEntry.SKIN, A, 4)));
        assertTrue(plan.localDeletes().isEmpty());
        assertEquals(List.of(skin(A, "Back", 0)), plan.uploads());
    }

    @Test
    void aRenameElsewhereRenamesHereAndOneHereIsSent() {
        Map<String, LibraryEntry> known = map(skin(A, "Old", 3), skin(B, "Same", 3));
        SyncPlanner.Plan plan = plan(map(skin(A, "Old", 0), skin(B, "Renamed here", 0)), known,
                List.of(skin(A, "Renamed there", 6)));
        assertEquals(List.of(skin(A, "Renamed there", 6)), plan.localUpdates());
        assertEquals(List.of(skin(B, "Renamed here", 0)), plan.uploads());
    }

    @Test
    void aChangeMadeOnBothSidesKeepsTheOneMadeHere() {
        Map<String, LibraryEntry> known = map(skin(A, "Old", 3));
        SyncPlanner.Plan plan = plan(map(skin(A, "Here", 0)), known, List.of(skin(A, "There", 6)));
        assertTrue(plan.localUpdates().isEmpty());
        assertEquals(List.of(skin(A, "Here", 0)), plan.uploads());
    }

    @Test
    void aDownloadStillPendingIsNotTakenForADeletionHere() {
        SyncPlanner.Plan plan = plan(Map.of(), Map.of(), List.of(skin(B, "Cloud", 5)));
        assertTrue(plan.cloudDeletes().isEmpty());
        assertTrue(!plan.known().containsKey(LibraryEntry.key(LibraryEntry.SKIN, B)));
    }

    @Test
    void anIgnoredEntryIsNeitherUploadedNorDeleted() {
        String key = LibraryEntry.key(LibraryEntry.SKIN, A);
        SyncPlanner.Plan upload = SyncPlanner.plan(map(skin(A, "Mine", 0)), Map.of(), List.of(), Set.of(key));
        assertTrue(upload.uploads().isEmpty());
        SyncPlanner.Plan delete = SyncPlanner.plan(Map.of(), map(skin(A, "Mine", 3)), List.of(), Set.of(key));
        assertTrue(delete.cloudDeletes().isEmpty());
    }

    @Test
    void namesAreFittedToWhatTheCloudAccepts() {
        assertEquals("a_b", LibraryEntry.fitName("a/b", "skin"));
        assertEquals("skin", LibraryEntry.fitName("  ", "skin"));
        assertEquals(64, LibraryEntry.fitName("x".repeat(100), "skin").length());
        assertTrue(LibraryEntry.isValidName("Steve (2)"));
        assertTrue(!LibraryEntry.isValidName(" padded"));
    }
}
