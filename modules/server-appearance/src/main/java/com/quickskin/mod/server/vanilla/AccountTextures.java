package com.quickskin.mod.server.vanilla;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * What a signed {@code textures} value shows: the skin URL, its arm model and the cape URL.
 *
 * <p>Mojang stamps every response with the time of the request, so two values of the same skin
 * differ byte for byte. Comparing the decoded appearance tells a fresh upload apart from a
 * session server that still answers with the previous skin.</p>
 */
public record AccountTextures(String skinUrl, String skinModel, String capeUrl) {
    public static final String CLASSIC = "classic";
    public static final String SLIM = "slim";
    private static final int MAX_URL_CHARS = 512;

    public AccountTextures {
        skinModel = SLIM.equals(skinModel) ? SLIM : CLASSIC;
    }

    /**
     * Decodes a textures value and requires it to describe {@code expectedProfileId}.
     *
     * @throws IllegalArgumentException when the value is malformed or names another profile
     */
    public static AccountTextures decode(String value, UUID expectedProfileId) {
        Objects.requireNonNull(expectedProfileId, "expectedProfileId");
        if (value == null || value.isEmpty() || value.length() > SignedTextures.MAX_VALUE_CHARS) {
            throw new IllegalArgumentException("The textures value is missing or too long");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("The textures value is not base64", error);
        }
        JsonObject root = StrictJson.parseObject(new String(decoded, StandardCharsets.UTF_8));
        String profileId = StrictJson.string(root, "profileId");
        if (profileId == null || !profileId.equalsIgnoreCase(undashed(expectedProfileId))) {
            throw new IllegalArgumentException("The textures value belongs to another profile");
        }
        JsonElement texturesElement = root.get("textures");
        if (texturesElement == null || !texturesElement.isJsonObject()) {
            throw new IllegalArgumentException("The textures value has no textures object");
        }
        JsonObject textures = texturesElement.getAsJsonObject();
        JsonObject skin = optionalObject(textures, "SKIN");
        JsonObject cape = optionalObject(textures, "CAPE");
        String skinUrl = skin == null ? null : url(skin);
        String model = CLASSIC;
        if (skin != null) {
            JsonObject metadata = optionalObject(skin, "metadata");
            String declared = metadata == null ? null : StrictJson.string(metadata, "model");
            if (declared != null && SLIM.equals(declared.toLowerCase(Locale.ROOT))) {
                model = SLIM;
            }
        }
        return new AccountTextures(skinUrl, model, cape == null ? null : url(cape));
    }

    /** Mojang's profile id format: 32 lowercase hexadecimal digits without dashes. */
    public static String undashed(UUID id) {
        return id.toString().replace("-", "");
    }

    private static JsonObject optionalObject(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("The textures field " + key + " is not an object");
        }
        return element.getAsJsonObject();
    }

    private static String url(JsonObject texture) {
        String url = StrictJson.string(texture, "url");
        if (url == null || url.isEmpty() || url.length() > MAX_URL_CHARS) {
            throw new IllegalArgumentException("A texture has no usable URL");
        }
        return url;
    }
}
