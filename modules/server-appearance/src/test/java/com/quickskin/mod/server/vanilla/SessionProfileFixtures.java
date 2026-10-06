package com.quickskin.mod.server.vanilla;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Builds session-server documents shaped like Mojang's, without any real account data. */
final class SessionProfileFixtures {
    static final String SIGNATURE = Base64.getEncoder().encodeToString(new byte[512]);
    /** A signature the test verifier treats as another service's, not Mojang's. */
    static final String FOREIGN_SIGNATURE = Base64.getEncoder().encodeToString(new byte[256]);
    /** Trusts {@link #SIGNATURE} and nothing else, standing in for Mojang's keys. */
    static final TexturesSignatureVerifier VERIFIER = textures -> SIGNATURE.equals(textures.signature());

    private SessionProfileFixtures() {
    }

    static String texturesValue(UUID profileId, String skinUrl, String model, long timestamp) {
        String metadata = model == null ? "" : ",\"metadata\":{\"model\":\"" + model + "\"}";
        String skin = skinUrl == null ? "" : "\"SKIN\":{\"url\":\"" + skinUrl + "\"" + metadata + "}";
        String json = "{\"timestamp\":" + timestamp
                + ",\"profileId\":\"" + AccountTextures.undashed(profileId) + "\""
                + ",\"profileName\":\"Tester\",\"signatureRequired\":true"
                + ",\"textures\":{" + skin + "}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static String response(UUID profileId, String texturesValue, String signature) {
        String signatureField = signature == null ? "" : ",\"signature\":\"" + signature + "\"";
        return "{\"id\":\"" + AccountTextures.undashed(profileId) + "\",\"name\":\"Tester\","
                + "\"properties\":[{\"name\":\"textures\",\"value\":\"" + texturesValue + "\""
                + signatureField + "}]}";
    }

    static String response(UUID profileId, String skinUrl, String model, long timestamp) {
        return response(profileId, texturesValue(profileId, skinUrl, model, timestamp), SIGNATURE);
    }

    static SignedTextures signed(UUID profileId, String skinUrl, String model, long timestamp) {
        return new SignedTextures(texturesValue(profileId, skinUrl, model, timestamp), SIGNATURE);
    }
}
