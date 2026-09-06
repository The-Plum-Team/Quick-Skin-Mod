package com.quickskin.mod.server.storage;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Transactional accounting for cache blobs retained by connected-player appearances.
 *
 * <p>Bytes are charged once per distinct content hash, even when several players (or both typed
 * slots of one player) reference the same immutable blob. A rejected replacement never releases
 * the caller's previous pins, so an adversarial over-budget update cannot evict an already
 * accepted active appearance.</p>
 */
final class PinnedTextureBudget {
    private final long maxBytes;
    private final Map<UUID, Set<String>> pinsByPlayer = new LinkedHashMap<>();
    private final Map<String, Integer> referencesByHash = new HashMap<>();
    private final Map<String, Integer> bytesByHash = new HashMap<>();
    private long pinnedBytes;

    PinnedTextureBudget(long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }

    /** Atomically replaces every blob pin owned by one connected player. */
    synchronized boolean tryReplace(UUID playerId, Map<String, Integer> requestedPins) {
        if (playerId == null || requestedPins == null) return false;
        LinkedHashMap<String, Integer> candidate = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : requestedPins.entrySet()) {
            String hash = entry.getKey();
            Integer bytes = entry.getValue();
            if (hash == null || bytes == null || bytes <= 0) return false;
            Integer previous = candidate.putIfAbsent(hash, bytes);
            if (previous != null && !previous.equals(bytes)) return false;
            Integer pinnedSize = bytesByHash.get(hash);
            if (pinnedSize != null && !pinnedSize.equals(bytes)) return false;
        }

        Set<String> existing = pinsByPlayer.getOrDefault(playerId, Collections.emptySet());
        long projectedBytes = pinnedBytes;
        for (String hash : existing) {
            if (!candidate.containsKey(hash)
                    && referencesByHash.getOrDefault(hash, 0) == 1) {
                projectedBytes -= bytesByHash.getOrDefault(hash, 0);
            }
        }
        for (Map.Entry<String, Integer> entry : candidate.entrySet()) {
            if (!existing.contains(entry.getKey())
                    && referencesByHash.getOrDefault(entry.getKey(), 0) == 0) {
                if (projectedBytes > maxBytes - entry.getValue()) return false;
                projectedBytes += entry.getValue();
            }
        }
        if (projectedBytes < 0 || projectedBytes > maxBytes) return false;

        for (String hash : existing) {
            if (!candidate.containsKey(hash)) releaseReference(hash);
        }
        for (Map.Entry<String, Integer> entry : candidate.entrySet()) {
            if (!existing.contains(entry.getKey())) addReference(entry.getKey(), entry.getValue());
        }
        if (candidate.isEmpty()) pinsByPlayer.remove(playerId);
        else pinsByPlayer.put(playerId, new LinkedHashSet<>(candidate.keySet()));
        pinnedBytes = projectedBytes;
        return true;
    }

    synchronized void releasePlayer(UUID playerId) {
        if (playerId == null) return;
        Set<String> removed = pinsByPlayer.remove(playerId);
        if (removed == null) return;
        for (String hash : removed) releaseReference(hash);
    }

    /** Defensive accounting cleanup for a backing blob that is being permanently deleted. */
    synchronized void removeTexture(String hash) {
        if (hash == null || !referencesByHash.containsKey(hash)) return;
        for (var iterator = pinsByPlayer.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, Set<String>> entry = iterator.next();
            entry.getValue().remove(hash);
            if (entry.getValue().isEmpty()) iterator.remove();
        }
        Integer bytes = bytesByHash.remove(hash);
        referencesByHash.remove(hash);
        if (bytes != null) pinnedBytes = Math.max(0, pinnedBytes - bytes);
    }

    synchronized boolean isPinned(String hash) {
        return hash != null && referencesByHash.getOrDefault(hash, 0) > 0;
    }

    synchronized long pinnedBytes() {
        return pinnedBytes;
    }

    synchronized int playerCount() {
        return pinsByPlayer.size();
    }

    synchronized int textureCount() {
        return referencesByHash.size();
    }

    synchronized void clear() {
        pinsByPlayer.clear();
        referencesByHash.clear();
        bytesByHash.clear();
        pinnedBytes = 0;
    }

    private void addReference(String hash, int bytes) {
        int references = referencesByHash.getOrDefault(hash, 0);
        referencesByHash.put(hash, references + 1);
        if (references == 0) bytesByHash.put(hash, bytes);
    }

    private void releaseReference(String hash) {
        int references = referencesByHash.getOrDefault(hash, 0);
        if (references <= 1) {
            referencesByHash.remove(hash);
            Integer bytes = bytesByHash.remove(hash);
            if (bytes != null) pinnedBytes = Math.max(0, pinnedBytes - bytes);
        } else {
            referencesByHash.put(hash, references - 1);
        }
    }
}
