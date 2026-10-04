package com.quickskin.mod.client.storage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Network skins whose file the CPM bridge asked for before their bytes arrived. CPM loads a
 * player once and keeps "no model" while the skin file is missing, so the later arrival of such a
 * skin must make CPM load the player again. Each miss is answered at most once, the set is
 * bounded, and it belongs to one connection (it is cleared with the network texture cache).
 */
final class CpmMissedSkinFiles {
    static final int MAX_ENTRIES = 256;

    private final Set<String> missed = ConcurrentHashMap.newKeySet();

    /**
     * Returns the bytes for {@code hash}, or records a miss when there are none. The lookup runs
     * again after the miss is recorded, so bytes stored at the same time are never lost: either
     * this call sees them, or the store sees the miss.
     */
    <T> T lookupOrRecordMiss(String hash, Supplier<T> lookup) {
        T value = lookup.get();
        if (value != null || hash == null) {
            return value;
        }
        if (missed.size() >= MAX_ENTRIES && !missed.contains(hash)) {
            missed.clear();
        }
        missed.add(hash);
        value = lookup.get();
        if (value != null) {
            missed.remove(hash);
        }
        return value;
    }

    /** True once for a skin whose file was missed since its last answer. */
    boolean takeMiss(String hash) {
        return hash != null && missed.remove(hash);
    }

    void clear() {
        missed.clear();
    }

    int size() {
        return missed.size();
    }
}
