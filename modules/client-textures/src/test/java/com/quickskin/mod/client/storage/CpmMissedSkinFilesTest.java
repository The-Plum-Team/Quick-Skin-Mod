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
    void theSetIsBoundedAndClearedWithTheSession() {
        CpmMissedSkinFiles misses = new CpmMissedSkinFiles();
        for (int i = 0; i < CpmMissedSkinFiles.MAX_ENTRIES + 10; i++) {
            misses.lookupOrRecordMiss("sha256-" + i, () -> null);
            assertTrue(misses.size() <= CpmMissedSkinFiles.MAX_ENTRIES);
        }
        assertTrue(misses.takeMiss("sha256-" + (CpmMissedSkinFiles.MAX_ENTRIES + 9)));

        misses.clear();
        assertEquals(0, misses.size());
        assertNull(misses.lookupOrRecordMiss(null, () -> null));
        assertEquals(0, misses.size());
        assertFalse(misses.takeMiss(null));
    }
}
