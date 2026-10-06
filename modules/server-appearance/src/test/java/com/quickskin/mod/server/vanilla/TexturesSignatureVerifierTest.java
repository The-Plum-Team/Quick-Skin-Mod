package com.quickskin.mod.server.vanilla;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TexturesSignatureVerifierTest {
    /**
     * Public textures of the profile "Notch" exactly as Mojang's session server returned them on
     * 2026-10-06 ({@code /session/minecraft/profile/069a79f444e94726a5befca90e38aaf5?unsigned=false}).
     */
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String NOTCH_VALUE =
            "ewogICJ0aW1lc3RhbXAiIDogMTc5MTI0NTkxNDExNywKICAicHJvZmlsZUlkIiA6ICIwNjlhNzlmNDQ0ZTk0NzI2"
            + "YTViZWZjYTkwZTM4YWFmNSIsCiAgInByb2ZpbGVOYW1lIiA6ICJOb3RjaCIsCiAgInNpZ25hdHVyZVJlcXVpcmVk"
            + "IiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3Rl"
            + "eHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yOTIwMDlhNDkyNWI1OGYwMmM3N2RhZGMzZWNlZjA3ZWE0Yzc0"
            + "NzJmNjRlMGZkYzMyY2U1NTIyNDg5MzYyNjgwIgogICAgfQogIH0KfQ==";
    private static final String NOTCH_SIGNATURE =
            "tvjKQhBerK77zM1LQUEFpfCHDmt/DDoQD/xEVLTt07Xp8k91Lhb3MXmeEJvpNn3NgBVvZyPTDBCKLvhe+vHIQQVR"
            + "au2aeUXs8JWXn1Wt9dkx3dp14F79KasyshezE3GthhX0ROtMvnoo3VNziJQvm5RpyXYRhVdxQ0ABXH5eikab2ly9"
            + "PAcZazHM+xk86JPREsV0MxRKQk2BWqC3N843oUm0JvhB6YLxpZhta30Cs2ZrDnALEnOWGAn2EKxRhWjVS1XTriDN"
            + "FEKPa5HbAF5/HKhWKQZPlNOQIYQRF1LPDCLialkiTXpg4+V0k0WEuAvOyc1u75fqOar1O9N4AhBu7v8zQjyzMBQv"
            + "D+5iPZkdKemb5ECee8HeMZiObSQ1mn6XAzKP7SVBq69k3XyqmSomiH/3iR99n6iNmoTlocfdRHlTKl/ymHHxmauY"
            + "LPh+cLSKVxFPs/tnI0lsyTu/QKNWz2Skp59nCl6X3fCkWjoJBX4SH3ERTEYqk3D8mAAG9hQERRcHaTDsozBt5w8H"
            + "lyCjMV4QQtG6zRct1+0dA4+I32ikj0VjLcc1ijIJLZ3mMJGbqNnCZKyaxZ0bIvj2Nxv5wXL2zqrkagdQ8fK5PxlK"
            + "ak02qziso9NZu7bu+9/kDIzMjfEc61+sZq71dS7JNCd4vozjwex8mknpWxvu5Z5l1zc=";

    @Test
    void acceptsTexturesThatMojangSigned() {
        SignedTextures textures = new SignedTextures(NOTCH_VALUE, NOTCH_SIGNATURE);

        assertTrue(TexturesSignatureVerifier.mojang().isTrusted(textures));
        assertEquals("Notch", SessionProfile.parse("{\"id\":\"069a79f444e94726a5befca90e38aaf5\","
                + "\"name\":\"Notch\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                + NOTCH_VALUE + "\",\"signature\":\"" + NOTCH_SIGNATURE + "\"}]}", NOTCH).name());
    }

    @Test
    void rejectsAChangedValueOrAnotherSignature() {
        String changed = NOTCH_VALUE.substring(0, 40) + (NOTCH_VALUE.charAt(40) == 'A' ? 'B' : 'A')
                + NOTCH_VALUE.substring(41);

        assertFalse(TexturesSignatureVerifier.mojang().isTrusted(new SignedTextures(changed, NOTCH_SIGNATURE)));
        assertFalse(TexturesSignatureVerifier.mojang().isTrusted(
                new SignedTextures(NOTCH_VALUE, SessionProfileFixtures.SIGNATURE)));
        assertFalse(TexturesSignatureVerifier.mojang().isTrusted(null));
    }

    @Test
    void rejectsTexturesThatAnotherAuthenticationServiceSigned() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair yggdrasil = generator.generateKeyPair();
        String value = SessionProfileFixtures.texturesValue(NOTCH, "http://textures.minecraft.net/texture/x", null, 1L);
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(yggdrasil.getPrivate());
        signer.update(value.getBytes(StandardCharsets.US_ASCII));
        SignedTextures foreign = new SignedTextures(value, Base64.getEncoder().encodeToString(signer.sign()));

        assertFalse(TexturesSignatureVerifier.mojang().isTrusted(foreign),
                "a custom Yggdrasil key is never Mojang's");
        assertTrue(TexturesSignatureVerifier.forKeys(List.of(
                Base64.getEncoder().encodeToString(yggdrasil.getPublic().getEncoded()))).isTrusted(foreign));
    }
}
