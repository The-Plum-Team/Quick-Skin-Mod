package com.quickskin.mod.client.services;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CapeAnimationIdsTest {

    private static final String ALIAS = "a".repeat(40);
    private static final String PRIMARY = "sha256-" + "b".repeat(64);

    /** Stands in for the catalogue: it resolves exactly the one alias it was given. */
    private static final UnaryOperator<String> CATALOG =
            Map.of(ALIAS, PRIMARY, PRIMARY, PRIMARY)::get;

    /** Stands in for a catalogue that holds nothing, like a client with no local capes. */
    private static final UnaryOperator<String> EMPTY_CATALOG = hash -> null;

    @Test
    void derivesOneAnimationIdPerCapeIdFamily() {
        assertEquals("cape_" + PRIMARY,
                CapeAnimationIds.deriveAnimationId("local_cape:" + PRIMARY));
        assertEquals("cape_known_bmo", CapeAnimationIds.deriveAnimationId("known:bmo"));
        assertNull(CapeAnimationIds.deriveAnimationId("SomeMojangUsername"));
        assertNull(CapeAnimationIds.deriveAnimationId(""));
        assertNull(CapeAnimationIds.deriveAnimationId(null));
    }

    @Test
    void aLegacyAliasAndItsPrimaryNameTheSameAnimationOnceCanonicalised() {
        String fromAlias = CapeAnimationIds.canonicalCapeId("local_cape:" + ALIAS, CATALOG);
        String fromPrimary = CapeAnimationIds.canonicalCapeId("local_cape:" + PRIMARY, CATALOG);

        assertEquals(fromPrimary, fromAlias);
        assertEquals("cape_" + PRIMARY, CapeAnimationIds.deriveAnimationId(fromAlias));
    }

    @Test
    void anUnresolvableContentIdKeepsTheIdItArrivedWith() {
        // A network cape, a cape whose file is gone, and an ambiguous alias the catalogue
        // deliberately refuses to resolve all reach this path: none may be rewritten, because
        // the animation they render under is the one their own content ID registered.
        assertEquals("local_cape:" + ALIAS,
                CapeAnimationIds.canonicalCapeId("local_cape:" + ALIAS, EMPTY_CATALOG));
        assertEquals("cape_" + ALIAS,
                CapeAnimationIds.deriveAnimationId(
                        CapeAnimationIds.canonicalCapeId("local_cape:" + ALIAS, EMPTY_CATALOG)));
    }

    @Test
    void canonicalisationLeavesEveryNonLocalCapeIdAlone() {
        assertEquals("known:bmo", CapeAnimationIds.canonicalCapeId("known:bmo", CATALOG));
        assertEquals("SomeMojangUsername",
                CapeAnimationIds.canonicalCapeId("SomeMojangUsername", CATALOG));
        assertEquals("local_cape:", CapeAnimationIds.canonicalCapeId("local_cape:", CATALOG));
        assertEquals("", CapeAnimationIds.canonicalCapeId("", CATALOG));
        assertNull(CapeAnimationIds.canonicalCapeId(null, CATALOG));
    }

    @Test
    void splitsExposeTheSamePartsEveryCallerUsedToRecomputeInline() {
        assertEquals(PRIMARY, CapeAnimationIds.localHash("local_cape:" + PRIMARY));
        assertNull(CapeAnimationIds.localHash("known:bmo"));
        assertEquals("bmo", CapeAnimationIds.knownId("known:bmo"));
        assertNull(CapeAnimationIds.knownId("local_cape:" + PRIMARY));
    }
}
