package com.quickskin.mod.client.storage;

import java.util.LinkedHashMap;

/**
 * Tracks textures used by recent render passes without weakening the cache's hard limits.
 *
 * <p>Protected entries are skipped before ordinary LRU entries. If the complete cache is part of
 * the recent working set, the eldest protected entry is still returned so callers can enforce
 * their byte, pixel, and entry caps unconditionally.</p>
 */
final class ClientTextureWorkingSet<K> {
    private final int maxTrackedEntries;
    private final long protectionTicks;
    private final LinkedHashMap<K, Long> lastUse =
            new LinkedHashMap<>(16, 0.75f, true);
    private long tick;

    ClientTextureWorkingSet(int maxTrackedEntries, long protectionTicks) {
        if (maxTrackedEntries < 1 || protectionTicks < 0L) {
            throw new IllegalArgumentException("working-set limits must be positive");
        }
        this.maxTrackedEntries = maxTrackedEntries;
        this.protectionTicks = protectionTicks;
    }

    void markInUse(K key) {
        if (key == null) return;
        lastUse.put(key, tick);
        while (lastUse.size() > maxTrackedEntries) {
            lastUse.remove(lastUse.keySet().iterator().next());
        }
    }

    void advanceTick() {
        if (tick < Long.MAX_VALUE) tick++;
        lastUse.entrySet().removeIf(entry -> !isProtectedAt(entry.getValue()));
    }

    K selectEviction(Iterable<K> leastToMostRecentlyUsed) {
        K eldest = null;
        for (K key : leastToMostRecentlyUsed) {
            if (eldest == null) eldest = key;
            Long usedAt = lastUse.get(key);
            if (usedAt == null || !isProtectedAt(usedAt)) return key;
        }
        return eldest;
    }

    void forget(K key) {
        lastUse.remove(key);
    }

    void clear() {
        lastUse.clear();
        tick = 0L;
    }

    int trackedEntries() {
        return lastUse.size();
    }

    private boolean isProtectedAt(long usedAt) {
        return tick >= usedAt && tick - usedAt <= protectionTicks;
    }
}
