package com.quickskin.mod.common.data;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContentAliasIndexTest {
    @Test
    void translatesOnlyRegisteredAliasesAndHonorsTheHardBound() {
        ContentAliasIndex index = new ContentAliasIndex(1);
        ContentAliases first = ContentAliases.forBytes(
                "first".getBytes(StandardCharsets.UTF_8));
        ContentAliases second = ContentAliases.forBytes(
                "second".getBytes(StandardCharsets.UTF_8));

        assertEquals(first.sha256(), index.register(first.sha256(), first));
        assertEquals(first.sha256(), index.resolve(first.sha1()));
        assertEquals(first.sha1(), index.alias(first.sha256(), ContentId.Algorithm.SHA1));
        assertNull(index.register(second.sha256(), second));
        assertNull(index.resolve(second.sha1()));
    }

    @Test
    void alwaysUsesSha256AsTheStablePrimaryForTheSameBytes() {
        ContentAliasIndex index = new ContentAliasIndex(2);
        ContentAliases aliases = ContentAliases.forBytes(
                "same bytes".getBytes(StandardCharsets.UTF_8));

        assertEquals(aliases.sha256(), index.register(aliases.sha1(), aliases));
        assertEquals(aliases.sha256(), index.register(aliases.sha256(), aliases));
        assertEquals(aliases.sha256(), index.resolve(aliases.sha1()));
        assertEquals(aliases.sha256(), index.resolve(aliases.sha256()));
        assertEquals(1, index.size());

        index.removePrimary(aliases.sha256());
        assertNull(index.resolve(aliases.sha1()));
        assertNull(index.resolve(aliases.sha256()));
    }

    @Test
    void preservesBothStrongPrimariesAndDisablesACollidingLegacyAlias() {
        ContentAliasIndex index = new ContentAliasIndex(2);
        String sharedSha1 = "a".repeat(40);
        ContentAliases first = new ContentAliases(
                sharedSha1, ContentId.SHA256_PREFIX + "b".repeat(64));
        ContentAliases second = new ContentAliases(
                sharedSha1, ContentId.SHA256_PREFIX + "c".repeat(64));

        assertEquals(first.sha256(), index.register(first.sha1(), first));
        assertEquals(second.sha256(), index.register(second.sha256(), second));

        assertEquals(first.sha256(), index.resolve(first.sha256()));
        assertEquals(second.sha256(), index.resolve(second.sha256()));
        assertNull(index.resolve(sharedSha1));
        assertNull(index.alias(first.sha256(), ContentId.Algorithm.SHA1));
        assertNull(index.alias(second.sha256(), ContentId.Algorithm.SHA1));
        assertEquals(first.sha256(),
                index.alias(first.sha256(), ContentId.Algorithm.SHA256));
        assertEquals(second.sha256(),
                index.alias(second.sha256(), ContentId.Algorithm.SHA256));
        assertEquals(2, index.size());
    }

    @Test
    void rejectsCrossMappedStrongIdentityWithoutDamagingEitherEntry() {
        ContentAliasIndex index = new ContentAliasIndex(2);
        ContentAliases first = new ContentAliases(
                "1".repeat(40), ContentId.SHA256_PREFIX + "a".repeat(64));
        ContentAliases second = new ContentAliases(
                "2".repeat(40), ContentId.SHA256_PREFIX + "b".repeat(64));

        assertEquals(first.sha256(), index.register(first.sha256(), first));
        assertEquals(second.sha256(), index.register(second.sha256(), second));
        assertNull(index.register(
                first.sha1(), new ContentAliases(first.sha1(), second.sha256())));
        assertNull(index.register(
                second.sha1(), new ContentAliases(second.sha1(), first.sha256())));

        assertEquals(first.sha256(), index.resolve(first.sha1()));
        assertEquals(second.sha256(), index.resolve(second.sha1()));
        assertEquals(first.sha256(), index.resolve(first.sha256()));
        assertEquals(second.sha256(), index.resolve(second.sha256()));
        assertEquals(2, index.size());
    }

    @Test
    void removalRestoresTheRemainingLegacyAliasWithoutRemovingItsStrongPrimary() {
        ContentAliasIndex index = new ContentAliasIndex(2);
        String sharedSha1 = "d".repeat(40);
        ContentAliases first = new ContentAliases(
                sharedSha1, ContentId.SHA256_PREFIX + "e".repeat(64));
        ContentAliases second = new ContentAliases(
                sharedSha1, ContentId.SHA256_PREFIX + "f".repeat(64));
        index.register(first.sha256(), first);
        index.register(second.sha256(), second);

        index.removePrimary(first.sha256());

        assertNull(index.resolve(first.sha256()));
        assertEquals(second.sha256(), index.resolve(second.sha256()));
        assertEquals(second.sha256(), index.resolve(sharedSha1));
        assertEquals(sharedSha1,
                index.alias(second.sha256(), ContentId.Algorithm.SHA1));
        assertEquals(1, index.size());
    }
}
