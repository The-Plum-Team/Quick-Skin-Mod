package com.quickskin.mod.common.data;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bounded alias index for exact byte identities.
 *
 * <p>SHA-256 is always the authoritative primary. A SHA-1 alias resolves only while it names
 * exactly one registered primary. Registering a second, independently authenticated SHA-256
 * primary with the same SHA-1 therefore preserves both strong identities while disabling the
 * ambiguous legacy alias.</p>
 */
public final class ContentAliasIndex {
    private final int maximumContents;
    private final Map<String, ContentAliases> aliasesByPrimary = new LinkedHashMap<>();
    private final Map<String, Set<String>> primariesByLegacyAlias = new LinkedHashMap<>();

    public ContentAliasIndex(int maximumContents) {
        if (maximumContents < 1) {
            throw new IllegalArgumentException("maximumContents must be positive");
        }
        this.maximumContents = maximumContents;
    }

    /**
     * Registers aliases and returns their SHA-256 primary, or {@code null} when the proposed key
     * is not one of the supplied aliases, the strong identity is cross-mapped to a different
     * verified pair, or the hard entry bound is full.
     */
    public synchronized String register(String proposedPrimary, ContentAliases aliases) {
        if (ContentId.parse(proposedPrimary) == null || aliases == null
                || !aliases.contains(proposedPrimary)) return null;

        String primary = aliases.sha256();
        ContentAliases existing = aliasesByPrimary.get(primary);
        if (existing != null) return existing.equals(aliases) ? primary : null;
        if (aliasesByPrimary.size() >= maximumContents) return null;

        aliasesByPrimary.put(primary, aliases);
        primariesByLegacyAlias.computeIfAbsent(
                aliases.sha1(), ignored -> new LinkedHashSet<>()).add(primary);
        return primary;
    }

    public synchronized String resolve(String contentId) {
        ContentId parsed = ContentId.parse(contentId);
        if (parsed == null) return null;
        if (parsed.algorithm() == ContentId.Algorithm.SHA256) {
            return aliasesByPrimary.containsKey(contentId) ? contentId : null;
        }
        Set<String> primaries = primariesByLegacyAlias.get(contentId);
        return primaries != null && primaries.size() == 1
                ? primaries.iterator().next()
                : null;
    }

    public synchronized String alias(String contentId, ContentId.Algorithm algorithm) {
        if (algorithm == null) return null;
        String primary = resolve(contentId);
        ContentAliases aliases = primary == null ? null : aliasesByPrimary.get(primary);
        if (aliases == null) return null;
        String alias = aliases.forAlgorithm(algorithm);
        // Never emit a legacy identifier that no longer identifies one exact stored blob.
        return algorithm == ContentId.Algorithm.SHA1 && resolve(alias) == null
                ? null
                : alias;
    }

    public synchronized void removePrimary(String primary) {
        ContentAliases aliases = aliasesByPrimary.remove(primary);
        if (aliases == null) return;
        Set<String> primaries = primariesByLegacyAlias.get(aliases.sha1());
        if (primaries == null) return;
        primaries.remove(primary);
        if (primaries.isEmpty()) primariesByLegacyAlias.remove(aliases.sha1());
    }

    public synchronized int size() {
        return aliasesByPrimary.size();
    }

    public synchronized void clear() {
        aliasesByPrimary.clear();
        primariesByLegacyAlias.clear();
    }
}
