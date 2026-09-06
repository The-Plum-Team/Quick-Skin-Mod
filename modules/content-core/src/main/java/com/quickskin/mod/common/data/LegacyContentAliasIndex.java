package com.quickskin.mod.common.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bounded index from historical SHA-1 identifiers to canonical SHA-256 identifiers.
 *
 * <p>Unlike {@link ContentAliasIndex}, a local asset may have more than one historical name
 * (for example, both the old raw cape digest and the later domain-separated cape digest). An
 * alias resolves only while every registered claimant points at the same strong primary. A
 * collision therefore disables the weak alias without hiding either SHA-256 identity.</p>
 */
public final class LegacyContentAliasIndex {
    private final int maximumContents;
    private final int maximumAliasAssociations;
    private final Set<String> primaries = new LinkedHashSet<>();
    private final Map<String, Set<String>> primariesByAlias = new LinkedHashMap<>();
    private int aliasAssociations;

    public LegacyContentAliasIndex(int maximumContents, int maximumAliasesPerContent) {
        if (maximumContents < 1 || maximumAliasesPerContent < 1) {
            throw new IllegalArgumentException("Alias index bounds must be positive");
        }
        this.maximumContents = maximumContents;
        try {
            this.maximumAliasAssociations = Math.multiplyExact(
                    maximumContents, maximumAliasesPerContent);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Alias index bounds are too large", overflow);
        }
    }

    /** Registers one strong primary and any authenticated legacy names for it. */
    public synchronized boolean register(String primary, Collection<String> legacyAliases) {
        ContentId parsedPrimary = ContentId.parse(primary);
        if (parsedPrimary == null || parsedPrimary.algorithm() != ContentId.Algorithm.SHA256
                || legacyAliases == null) {
            return false;
        }

        LinkedHashSet<String> validatedAliases = new LinkedHashSet<>();
        for (String alias : legacyAliases) {
            ContentId parsedAlias = ContentId.parse(alias);
            if (parsedAlias == null || parsedAlias.algorithm() != ContentId.Algorithm.SHA1) {
                return false;
            }
            validatedAliases.add(alias);
        }

        boolean newPrimary = !primaries.contains(primary);
        if (newPrimary && primaries.size() >= maximumContents) {
            return false;
        }
        int additions = 0;
        for (String alias : validatedAliases) {
            Set<String> claimants = primariesByAlias.get(alias);
            if (claimants == null || !claimants.contains(primary)) additions++;
        }
        if (additions > maximumAliasAssociations - aliasAssociations) {
            return false;
        }

        primaries.add(primary);
        for (String alias : validatedAliases) {
            if (primariesByAlias.computeIfAbsent(alias, ignored -> new LinkedHashSet<>())
                    .add(primary)) {
                aliasAssociations++;
            }
        }
        return true;
    }

    /** Returns a primary only for a registered strong ID or an unambiguous legacy alias. */
    public synchronized String resolve(String contentId) {
        ContentId parsed = ContentId.parse(contentId);
        if (parsed == null) return null;
        if (parsed.algorithm() == ContentId.Algorithm.SHA256) {
            return primaries.contains(contentId) ? contentId : null;
        }
        Set<String> claimants = primariesByAlias.get(contentId);
        return claimants != null && claimants.size() == 1
                ? claimants.iterator().next()
                : null;
    }

    public synchronized boolean resolvesTo(String legacyAlias, String primary) {
        return primary != null && primary.equals(resolve(legacyAlias));
    }

    /** Immutable snapshot containing only aliases that still identify exactly one primary. */
    public synchronized Map<String, String> uniqueAliases() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : primariesByAlias.entrySet()) {
            if (entry.getValue().size() == 1) {
                result.put(entry.getKey(), entry.getValue().iterator().next());
            }
        }
        return Map.copyOf(result);
    }

    public synchronized boolean isAmbiguous(String legacyAlias) {
        Set<String> claimants = primariesByAlias.get(legacyAlias);
        return claimants != null && claimants.size() > 1;
    }

    public synchronized int size() {
        return primaries.size();
    }
}
