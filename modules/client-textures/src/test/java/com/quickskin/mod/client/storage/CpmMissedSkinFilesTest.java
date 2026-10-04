package com.quickskin.mod.client.storage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpmMissedSkinFilesTest {
    private static final String HASH = "sha256-" + "a".repeat(64);
    private static final byte[] BYTES = {1, 2, 3};

    @Test
    void presentBytesRecordNoMiss() {
        CpmMissedSkinFiles misses = new CpmMissedSkinFiles();

        assertArrayEquals(BYTES, misses.lookupOrRecordMiss(HASH, () -> BYTES));
        assertFalse(misses.takeMiss(HASH));
    }

    @Test
    void aMissIsAnsweredExactlyOnce() {
        CpmMissedSkinFiles misses = new CpmMissedSkinFiles();

        assertNull(misses.lookupOrRecordMiss(HASH, () -> null));
        // CPM asks again every frame while the bytes are missing; it stays one pending miss.
        assertNull(misses.lookupOrRecordMiss(HASH, () -> null));
        assertEquals(1, misses.size());

        assertTrue(misses.takeMiss(HASH));
        assertFalse(misses.takeMiss(HASH));
        assertFalse(misses.takeMiss("sha256-" + "b".repeat(64)));
    }

    @Test
    void bytesStoredWhileTheMissIsRecordedAreReturnedAndForgetTheMiss() {
        CpmMissedSkinFiles misses = new CpmMissedSkinFiles();
        AtomicInteger lookups = new AtomicInteger();

        // The first lookup misses; the bytes are committed before the second one.
        byte[] found = misses.lookupOrRecordMiss(
                HASH, () -> lookups.getAndIncrement() == 0 ? null : BYTES);

        assertArrayEquals(BYTES, found);
        assertEquals(2, lookups.get());
        assertFalse(misses.takeMiss(HASH));
    }

    @Test
    void aStoreBetweenBothLookupsSeesTheMiss() {
        CpmMissedSkinFiles misses = new CpmMissedSkinFiles();
        AtomicReference<Boolean> storeSawMiss = new AtomicReference<>();

        // The store commits after the miss is recorded but its bytes are not yet visible to the
        // second lookup's reader: the store must then be the one that answers the miss.
        assertNull(misses.lookupOrRecordMiss(HASH, () -> {
            if (misses.size() == 1 && storeSawMiss.get() == null) {
                storeSawMiss.set(misses.takeMiss(HASH));
            }
            return null;
        }));

        assertTrue(storeSawMiss.get());
    }

    @Test
    void aFullSetDropsOnlyTheMissAskedForLeastRecently() {
        CpmMissedSkinFiles misses = new CpmMissedSkinFiles();
        for (int i = 0; i < CpmMissedSkinFiles.MAX_ENTRIES; i++) {
            misses.lookupOrRecordMiss("sha256-" + i, () -> null);
        }
        // Skin 0 is still rendered every frame while its bytes are missing; skin 1 is not.
        misses.lookupOrRecordMiss("sha256-0", () -> null);
        misses.lookupOrRecordMiss("sha256-new", () -> null);

        assertEquals(CpmMissedSkinFiles.MAX_ENTRIES, misses.size());
        assertTrue(misses.takeMiss("sha256-0"));
        assertFalse(misses.takeMiss("sha256-1"));
        assertTrue(misses.takeMiss("sha256-2"));
        assertTrue(misses.takeMiss("sha256-new"));
    }

    @Test
    void onlyAStoredSkinAnswersItsMiss() {
        CpmMissedSkinFiles misses = new CpmMissedSkinFiles();
        misses.lookupOrRecordMiss(HASH, () -> null);

        // A cape or elytra with the same hash, or a skin that was not kept, leaves the miss pending.
        assertFalse(misses.takeStoredSkinMiss("cape", HASH, true));
        assertFalse(misses.takeStoredSkinMiss("elytra", HASH, true));
        assertFalse(misses.takeStoredSkinMiss("skin", HASH, false));
        assertEquals(1, misses.size());

        assertTrue(misses.takeStoredSkinMiss("skin", HASH, true));
        // Storing the same skin again (or a skin nobody missed) does not reload CPM.
        assertFalse(misses.takeStoredSkinMiss("skin", HASH, true));
        assertFalse(misses.takeStoredSkinMiss("skin", "sha256-" + "c".repeat(64), true));
    }

    @Test
    void theSetIsClearedWithTheSession() {
        CpmMissedSkinFiles misses = new CpmMissedSkinFiles();
        misses.lookupOrRecordMiss(HASH, () -> null);

        misses.clear();
        assertEquals(0, misses.size());
        assertFalse(misses.takeMiss(HASH));
        assertNull(misses.lookupOrRecordMiss(null, () -> null));
        assertEquals(0, misses.size());
        assertFalse(misses.takeMiss(null));
    }
}
