package com.quickskin.mod.server.vanilla;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.UUID;

/**
 * A validated answer of Mojang's session server for one profile.
 *
 * <p>The response must name exactly the requested profile, carry its non-empty player name and
 * exactly one {@code textures} property with a signature, and the signed payload must describe
 * that same profile. Anything else is rejected before it can reach a player's profile. Whether
 * Mojang made the signature is checked separately by {@link TexturesSignatureVerifier}.</p>
 */
public record SessionProfile(String name, SignedTextures textures, AccountTextures appearance) {
    /** Mojang's profile responses are well below a kilobyte; the cap only bounds a hostile one. */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_PROPERTIES = 16;
    /** Mojang names have at most 16 characters; the cap only bounds a hostile response. */
    private static final int MAX_NAME_CHARS = 64;
    private static final String TEXTURES = "textures";

    public SessionProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(textures, "textures");
        Objects.requireNonNull(appearance, "appearance");
    }

    /**
     * Parses a {@code /session/minecraft/profile/<id>?unsigned=false} response body.
     *
     * @throws IllegalArgumentException when the response is malformed, unsigned, ambiguous or
     *                                  describes another profile
     */
    public static SessionProfile parse(String body, UUID expectedProfileId) {
        Objects.requireNonNull(expectedProfileId, "expectedProfileId");
        if (body == null || body.length() > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("The session profile response is missing or too long");
        }
        JsonObject root = StrictJson.parseObject(body);
        String id = StrictJson.string(root, "id");
        if (id == null || !id.equalsIgnoreCase(AccountTextures.undashed(expectedProfileId))) {
            throw new IllegalArgumentException("The session profile response names another profile");
        }
        String name = StrictJson.string(root, "name");
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("The session profile response has no usable name");
        }
        JsonElement propertiesElement = root.get("properties");
        if (propertiesElement == null || !propertiesElement.isJsonArray()) {
            throw new IllegalArgumentException("The session profile response has no properties");
        }
        JsonArray properties = propertiesElement.getAsJsonArray();
        if (properties.size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException("The session profile response has too many properties");
        }
        SignedTextures textures = null;
        for (JsonElement element : properties) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("A session profile property is not an object");
            }
            JsonObject property = element.getAsJsonObject();
            if (!TEXTURES.equals(StrictJson.string(property, "name"))) continue;
            if (textures != null) {
                throw new IllegalArgumentException("The session profile response has two textures");
            }
            String signature = StrictJson.string(property, "signature");
            if (signature == null || signature.isEmpty()) {
                throw new IllegalArgumentException("The session profile textures are not signed");
            }
            textures = new SignedTextures(StrictJson.string(property, "value"), signature);
        }
        if (textures == null) {
            throw new IllegalArgumentException("The session profile response has no textures");
        }
        return new SessionProfile(name, textures, AccountTextures.decode(textures.value(), expectedProfileId));
    }
}
