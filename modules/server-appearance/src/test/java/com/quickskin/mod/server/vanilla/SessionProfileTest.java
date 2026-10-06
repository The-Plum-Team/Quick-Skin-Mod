package com.quickskin.mod.server.vanilla;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static com.quickskin.mod.server.vanilla.SessionProfileFixtures.SIGNATURE;
import static com.quickskin.mod.server.vanilla.SessionProfileFixtures.response;
import static com.quickskin.mod.server.vanilla.SessionProfileFixtures.texturesValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionProfileTest {
    private static final UUID PLAYER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String SKIN = "http://textures.minecraft.net/texture/abc123";

    @Test
    void parsesTheSignedTexturesOfTheRequestedProfile() {
        String value = texturesValue(PLAYER, SKIN, "slim", 1L);

        SessionProfile profile = SessionProfile.parse(response(PLAYER, value, SIGNATURE), PLAYER);

        assertEquals(value, profile.textures().value());
        assertEquals(SIGNATURE, profile.textures().signature());
        assertEquals(new AccountTextures(SKIN, "slim", null), profile.appearance());
    }

    @Test
    void acceptsMojangsUpperOrLowerCaseProfileId() {
        String body = response(PLAYER, SKIN, null, 1L)
                .replace(AccountTextures.undashed(PLAYER), AccountTextures.undashed(PLAYER).toUpperCase());

        assertEquals(AccountTextures.CLASSIC, SessionProfile.parse(body, PLAYER).appearance().skinModel());
    }

    @Test
    void rejectsAResponseForAnotherProfile() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse(response(OTHER, SKIN, null, 1L), PLAYER));
    }

    @Test
    void rejectsSignedTexturesThatDescribeAnotherProfile() {
        String foreignValue = texturesValue(OTHER, SKIN, null, 1L);

        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse(response(PLAYER, foreignValue, SIGNATURE), PLAYER));
    }

    @Test
    void rejectsUnsignedOrBadlySignedTextures() {
        String value = texturesValue(PLAYER, SKIN, null, 1L);

        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse(response(PLAYER, value, null), PLAYER));
        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse(response(PLAYER, value, ""), PLAYER));
        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse(response(PLAYER, value, "not base64!"), PLAYER));
    }

    @Test
    void readsThePlayerNameAndRequiresOne() {
        String valid = response(PLAYER, SKIN, null, 1L);

        assertEquals("Tester", SessionProfile.parse(valid, PLAYER).name());
        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse(valid.replace("\"name\":\"Tester\",", ""), PLAYER));
        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse(valid.replace("\"name\":\"Tester\"", "\"name\":\"\""), PLAYER));
        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse(valid.replace("\"name\":\"Tester\"", "\"name\":7"), PLAYER));
        assertThrows(IllegalArgumentException.class, () -> SessionProfile.parse(
                valid.replace("\"name\":\"Tester\"", "\"name\":\"" + "n".repeat(65) + "\""), PLAYER));
    }

    @Test
    void rejectsMissingOrDuplicateTextures() {
        String noTextures = "{\"id\":\"" + AccountTextures.undashed(PLAYER) + "\",\"properties\":[]}";
        String value = texturesValue(PLAYER, SKIN, null, 1L);
        String property = "{\"name\":\"textures\",\"value\":\"" + value
                + "\",\"signature\":\"" + SIGNATURE + "\"}";
        String twice = "{\"id\":\"" + AccountTextures.undashed(PLAYER) + "\",\"properties\":["
                + property + "," + property + "]}";

        assertThrows(IllegalArgumentException.class, () -> SessionProfile.parse(noTextures, PLAYER));
        assertThrows(IllegalArgumentException.class, () -> SessionProfile.parse(twice, PLAYER));
    }

    @Test
    void rejectsMalformedDocuments() {
        String valid = response(PLAYER, SKIN, null, 1L);

        assertThrows(IllegalArgumentException.class, () -> SessionProfile.parse("", PLAYER));
        assertThrows(IllegalArgumentException.class, () -> SessionProfile.parse("[]", PLAYER));
        assertThrows(IllegalArgumentException.class, () -> SessionProfile.parse(valid + "{}", PLAYER));
        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse(valid.replace("\"id\":", "\"id\":1,\"x\":"), PLAYER));
        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse("{\"id\":\"" + AccountTextures.undashed(PLAYER)
                        + "\",\"properties\":{}}", PLAYER));
        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse("x".repeat(SessionProfile.MAX_RESPONSE_BYTES + 1), PLAYER));
    }

    @Test
    void rejectsATexturesValueThatIsNotTheExpectedJson() {
        String garbage = Base64.getEncoder().encodeToString("not json".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class,
                () -> SessionProfile.parse(response(PLAYER, garbage, SIGNATURE), PLAYER));
    }

    @Test
    void appearanceIgnoresTheRequestTimestamp() {
        AccountTextures first = SessionProfile.parse(response(PLAYER, SKIN, "slim", 1L), PLAYER).appearance();
        AccountTextures second = SessionProfile.parse(response(PLAYER, SKIN, "slim", 2L), PLAYER).appearance();
        AccountTextures classic = SessionProfile.parse(response(PLAYER, SKIN, null, 2L), PLAYER).appearance();
        AccountTextures other = SessionProfile.parse(
                response(PLAYER, SKIN + "def", "slim", 2L), PLAYER).appearance();

        assertEquals(first, second);
        assertNotEquals(first, classic);
        assertNotEquals(first, other);
    }

    @Test
    void anAccountWithoutSkinHasNoSkinUrl() {
        assertNull(SessionProfile.parse(response(PLAYER, null, null, 1L), PLAYER).appearance().skinUrl());
    }

    @Test
    void signedTexturesAreBoundedAndNeverPrintTheirPayload() {
        String longValue = Base64.getEncoder().encodeToString(new byte[SignedTextures.MAX_VALUE_CHARS]);

        assertThrows(IllegalArgumentException.class, () -> new SignedTextures(longValue, SIGNATURE));
        assertThrows(IllegalArgumentException.class, () -> new SignedTextures(null, SIGNATURE));
        assertEquals(-1, new SignedTextures(texturesValue(PLAYER, SKIN, null, 1L), SIGNATURE)
                .toString().indexOf(SIGNATURE));
    }

    @Test
    void retryAfterHintIsBounded() {
        assertEquals(0L, MojangSessionProfileFetcher.retryAfterMillis(null));
        assertEquals(0L, MojangSessionProfileFetcher.retryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT"));
        assertEquals(0L, MojangSessionProfileFetcher.retryAfterMillis("-3"));
        assertEquals(7_000L, MojangSessionProfileFetcher.retryAfterMillis(" 7 "));
        assertEquals(300_000L, MojangSessionProfileFetcher.retryAfterMillis("86400"));
    }
}
