package com.quickskin.mod.client.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Network skins whose file the CPM bridge asked for before their bytes arrived. CPM loads a
 * player once and keeps "no model" while the skin file is missing, so the later arrival of such a
 * skin must make CPM load its players again. The bridge also serves vanilla rendering and the tab
 * list while CPM is installed, so a miss means "this skin was looked up through the bridge too
 * early", not necessarily "CPM itself looked". Each miss is answered at most once and belongs to
 * one connection (it is cleared with the network texture cache).
 *
 * <p>The set is bounded. When it is full the miss asked for least recently is dropped: a skin
 * still being looked up (every rendered frame while its bytes are missing) stays recent, so only
 * skins nobody asks for any more are forgotten.
 */
final class CpmMissedSkinFiles {
    static final int MAX_ENTRIES = 256;

    private final Map<String, Boolean> missed = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

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
        synchronized (missed) {
            missed.put(hash, Boolean.TRUE);
        }
        value = lookup.get();
        if (value != null) {
            synchronized (missed) {
                missed.remove(hash);
            }
        }
        return value;
    }

    /** True once for a skin whose file was missed since its last answer. */
    boolean takeMiss(String hash) {
        if (hash == null) {
            return false;
        }
        synchronized (missed) {
            return missed.remove(hash) != null;
        }
    }

    /**
     * True once when a skin texture whose file was missed is now stored. Capes, elytras and a
     * texture that was not kept (rejected or evicted at once) leave the miss pending.
     */
    boolean takeStoredSkinMiss(String textureType, String hash, boolean stored) {
        return stored && "skin".equals(textureType) && takeMiss(hash);
    }

    void clear() {
        synchronized (missed) {
            missed.clear();
        }
    }

    int size() {
        synchronized (missed) {
            return missed.size();
        }
    }
}
