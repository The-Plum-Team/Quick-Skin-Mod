package com.quickskin.mod.client.services;

import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * The Minecraft-free vocabulary shared by every cape-animation caller: how a cape ID is split,
 * how its animation ID is derived, and how a legacy local alias is folded onto its catalog
 * primary.
 *
 * <p>An animated cape lives in {@link AnimatedTextureManager} under one string, so every caller
 * that registers, renders, retimes, or unregisters it has to derive the exact same string from
 * the exact same cape ID. Re-implementing the {@code substring} split per screen is how those
 * callers drift apart, so the split lives here once.</p>
 *
 * <p>Canonicalisation is deliberately <em>not</em> part of {@link #deriveAnimationId}. A
 * {@code local_cape:} ID addresses a network-delivered cape just as often as a catalogued one,
 * and a network cape owns the content ID the server sent as its animation identity. Rewriting
 * that on the render path would look up an animation nobody registered and fall back to the
 * stacked atlas; rewriting it only where an animation is registered would leave a second
 * animation running beside the one the renderer resolves.</p>
 *
 * <p>{@link #canonicalCapeId} therefore applies where a cape ID is <em>chosen</em>, and only to a
 * value that never leaves this client. A {@code PlayerAppearance} cape ID is not such a value:
 * it is advertised to the server, where a bare SHA-1 is the immutable alias a legacy peer
 * negotiated. The persisted preference is canonicalised once by
 * {@code LocalContentIdMigration}; this helper covers a read-only local lookup that has to agree
 * with the catalogue before that migration has committed.</p>
 */
public final class CapeAnimationIds {

    /** Cape-ID prefix for a content-addressed cape, local catalogue or network cache alike. */
    public static final String LOCAL_PREFIX = "local_cape:";
    /** Cape-ID prefix for a cape bundled with the mod. */
    public static final String KNOWN_PREFIX = "known:";

    private static final String LOCAL_ANIMATION_PREFIX = "cape_";
    private static final String KNOWN_ANIMATION_PREFIX = "cape_known_";

    private CapeAnimationIds() {
    }

    /**
     * Derives the animation ID from a cape ID.
     * <ul>
     *   <li>{@code "local_cape:hash"} &rarr; {@code "cape_hash"}</li>
     *   <li>{@code "known:id"} &rarr; {@code "cape_known_id"}</li>
     * </ul>
     *
     * @return the animation ID, or {@code null} when the cape ID has no recognised prefix
     */
    @Nullable
    public static String deriveAnimationId(@Nullable String capeId) {
        if (capeId == null || capeId.isEmpty()) return null;
        String localHash = localHash(capeId);
        if (localHash != null) return LOCAL_ANIMATION_PREFIX + localHash;
        String knownId = knownId(capeId);
        return knownId == null ? null : KNOWN_ANIMATION_PREFIX + knownId;
    }

    /** The content ID inside a {@code local_cape:} ID, or {@code null} for any other cape ID. */
    @Nullable
    public static String localHash(@Nullable String capeId) {
        return capeId != null && capeId.startsWith(LOCAL_PREFIX)
                ? capeId.substring(LOCAL_PREFIX.length())
                : null;
    }

    /** The bundled cape id inside a {@code known:} ID, or {@code null} for any other cape ID. */
    @Nullable
    public static String knownId(@Nullable String capeId) {
        return capeId != null && capeId.startsWith(KNOWN_PREFIX)
                ? capeId.substring(KNOWN_PREFIX.length())
                : null;
    }

    /**
     * Rewrites a {@code local_cape:} ID onto the catalog primary its content ID resolves to, so a
     * persisted SHA-1 alias and the catalogue's SHA-256 primary stop naming two different capes.
     *
     * <p>Only call this where the cape ID is already known to address the local catalogue. The
     * resolver must be the catalogue's own authoritative lookup: it returns {@code null} for a
     * content ID the catalogue does not hold, and for an ambiguous alias that must not resolve at
     * all. Either way the original ID is preserved untouched.</p>
     *
     * @param capeId         the cape ID to canonicalise; any non-local ID is returned unchanged
     * @param catalogPrimary maps a local content ID to its catalogue primary, or {@code null}
     */
    @Nullable
    public static String canonicalCapeId(
            @Nullable String capeId, UnaryOperator<String> catalogPrimary) {
        String localHash = localHash(capeId);
        if (localHash == null || localHash.isEmpty()) return capeId;
        String primary = catalogPrimary.apply(localHash);
        return primary == null || primary.equals(localHash) ? capeId : LOCAL_PREFIX + primary;
    }
}
