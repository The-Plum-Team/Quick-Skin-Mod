package com.quickskin.mod.common.data;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentIdTest {
    private static final String LEGACY = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA256 =
            "sha256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void parsesOnlyCanonicalLegacyAndStrongForms() {
        ContentId legacy = ContentId.parse(LEGACY);
        ContentId strong = ContentId.parse(SHA256);

        assertEquals(ContentId.Algorithm.SHA1, legacy.algorithm());
        assertTrue(legacy.isLegacy());
        assertEquals(LEGACY, legacy.externalForm());
        assertEquals(ContentId.Algorithm.SHA256, strong.algorithm());
        assertFalse(strong.isLegacy());
        assertEquals(SHA256, strong.externalForm());

        assertNull(ContentId.parse(null));
        assertNull(ContentId.parse(""));
        assertNull(ContentId.parse(LEGACY.toUpperCase()));
        assertNull(ContentId.parse("sha256:" + SHA256.substring("sha256-".length())));
        assertNull(ContentId.parse("../" + LEGACY.substring(3)));
    }

    @Test
    void hashesWithTheRequestedAlgorithmWithoutDowngrade() {
        byte[] bytes = "quickskin".getBytes(StandardCharsets.UTF_8);

        ContentId legacy = ContentId.hash(bytes, ContentId.Algorithm.SHA1);
        ContentId strong = ContentId.hash(bytes, ContentId.Algorithm.SHA256);

        assertEquals("e37ebd9540762bf0c6ca8d8d7aff201d775a1a42", legacy.externalForm());
        assertEquals(
                "sha256-d443e697ed59e0db6f9072781f913dd7c7d3911597558116b2e8c412d50c1969",
                strong.externalForm());
        assertThrows(IllegalArgumentException.class,
                () -> new ContentId(ContentId.Algorithm.SHA256, legacy.digest()));
    }

    @Test
    void domainSeparationChangesIdentity() {
        byte[] bytes = "same-png".getBytes(StandardCharsets.UTF_8);
        ContentId plain = ContentId.hash(bytes, ContentId.Algorithm.SHA256);
        ContentId cape = ContentId.hashDomainSeparated(
                "quickskin:cape\0".getBytes(StandardCharsets.UTF_8),
                bytes,
                ContentId.Algorithm.SHA256);

        assertFalse(plain.equals(cape));
        assertEquals(ContentId.Algorithm.SHA256, cape.algorithm());
    }
}
