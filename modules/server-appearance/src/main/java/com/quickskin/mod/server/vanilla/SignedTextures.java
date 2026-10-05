package com.quickskin.mod.server.vanilla;

import java.util.Base64;

/**
 * The {@code textures} property of a Mojang profile together with Mojang's signature.
 *
 * <p>Unmodded clients show another player's skin only when this property carries a valid
 * Mojang signature, so a value without one is never constructed. Both strings are bounded and
 * must be canonical base64; the clients that render the value verify the signature itself.</p>
 */
public record SignedTextures(String value, String signature) {
    /** A Mojang textures value is a few hundred characters; this is a generous hard cap. */
    public static final int MAX_VALUE_CHARS = 8 * 1024;
    /** Mojang signs with a 4096-bit key: 512 bytes, 684 base64 characters. */
    public static final int MAX_SIGNATURE_CHARS = 1024;

    public SignedTextures {
        requireBase64(value, MAX_VALUE_CHARS, "value");
        requireBase64(signature, MAX_SIGNATURE_CHARS, "signature");
    }

    private static void requireBase64(String text, int maxChars, String field) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("The textures " + field + " is missing");
        }
        if (text.length() > maxChars) {
            throw new IllegalArgumentException("The textures " + field + " is too long");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("The textures " + field + " is not base64", error);
        }
        if (decoded.length == 0) {
            throw new IllegalArgumentException("The textures " + field + " is empty");
        }
    }

    @Override
    public String toString() {
        // Never log the full payload; its length is enough to tell values apart in diagnostics.
        return "SignedTextures[value=" + value.length() + " chars, signature="
                + signature.length() + " chars]";
    }
}
