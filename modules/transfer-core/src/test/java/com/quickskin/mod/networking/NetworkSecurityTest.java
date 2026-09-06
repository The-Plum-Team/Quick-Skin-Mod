package com.quickskin.mod.networking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSecurityTest {
    private static final String CONTENT_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final String STRONG_CONTENT_ID =
            "sha256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesCanonicalBoundaryIdentifiers() {
        assertTrue(NetworkSecurity.isValidContentId(CONTENT_ID));
        assertTrue(NetworkSecurity.isValidContentId(STRONG_CONTENT_ID));
        assertFalse(NetworkSecurity.isValidContentId(null));
        assertFalse(NetworkSecurity.isValidContentId(CONTENT_ID.substring(1)));
        assertFalse(NetworkSecurity.isValidContentId(CONTENT_ID.toUpperCase()));
        assertFalse(NetworkSecurity.isValidContentId("../" + CONTENT_ID.substring(3)));
        assertFalse(NetworkSecurity.isValidContentId(STRONG_CONTENT_ID.toUpperCase()));
        assertFalse(NetworkSecurity.isValidContentId(STRONG_CONTENT_ID.replace('-', ':')));

        assertTrue(NetworkSecurity.isValidTextureType("skin"));
        assertTrue(NetworkSecurity.isValidTextureType("cape"));
        assertFalse(NetworkSecurity.isValidTextureType("elytra"));
        assertFalse(NetworkSecurity.isValidTextureType(null));

        assertTrue(NetworkSecurity.isValidLocalAppearanceId("local_skin:" + CONTENT_ID, "skin"));
        assertTrue(NetworkSecurity.isValidLocalAppearanceId("local_cape:" + CONTENT_ID, "cape"));
        assertTrue(NetworkSecurity.isValidLocalAppearanceId(
                "local_skin:" + STRONG_CONTENT_ID, "skin"));
        assertFalse(NetworkSecurity.isValidLocalAppearanceId("local_skin:not-a-hash", "skin"));
        assertFalse(NetworkSecurity.isValidLocalAppearanceId("local_cape:not-a-hash", "cape"));
        assertFalse(NetworkSecurity.isValidLocalAppearanceId("local_skin:" + CONTENT_ID, "cape"));
        assertTrue(NetworkSecurity.isValidLocalAppearanceId("", "skin"));
        assertFalse(NetworkSecurity.isValidLocalAppearanceId("", "elytra"));
        assertFalse(NetworkSecurity.isValidLocalAppearanceId(null, "elytra"));
        assertFalse(NetworkSecurity.isValidLocalAppearanceId("line\nbreak", "skin"));
        assertFalse(NetworkSecurity.isValidLocalAppearanceId(
                "x".repeat(TextureTransferLimits.MAX_APPEARANCE_ID_BYTES + 1), "skin"));
        assertTrue(NetworkSecurity.isValidLocalAppearanceId("mojang:profile", "skin"));
    }

    @Test
    void resolvesOnlyContainedContentAddressedPaths() {
        Path expected = temporaryDirectory.toAbsolutePath().normalize().resolve(CONTENT_ID + ".png");
        assertEquals(
                expected,
                NetworkSecurity.resolveContained(temporaryDirectory, CONTENT_ID, ".png"));
        assertEquals(
                temporaryDirectory.toAbsolutePath().normalize().resolve(STRONG_CONTENT_ID + ".png"),
                NetworkSecurity.resolveContained(temporaryDirectory, STRONG_CONTENT_ID, ".png"));
        assertNull(NetworkSecurity.resolveContained(temporaryDirectory, "../escape", ".png"));
        assertNull(NetworkSecurity.resolveContained(
                temporaryDirectory, CONTENT_ID, "/../../escape.png"));
        assertNull(NetworkSecurity.resolveContained(null, CONTENT_ID, ".png"));
    }

    @Test
    void validatesACompletePngBeforeAcceptingTextureBytes() throws IOException {
        byte[] png = png(64, 64);
        assertTrue(NetworkSecurity.isValidTextureData(png, "skin"));
        assertTrue(NetworkSecurity.isValidTextureData(png, "cape"));

        byte[] badSignature = png.clone();
        badSignature[0] = 0;
        assertFalse(NetworkSecurity.isValidTextureData(badSignature, "skin"));
        assertFalse(NetworkSecurity.isValidTextureData(Arrays.copyOf(png, 30), "skin"));
        assertFalse(NetworkSecurity.isValidTextureData(png, "unknown"));
        assertFalse(NetworkSecurity.isValidTextureData(png, ""));

        byte[] oversizedHeader = png.clone();
        writeBigEndianInt(oversizedHeader, 16, TextureTransferLimits.MAX_IMAGE_WIDTH + 1);
        assertFalse(NetworkSecurity.isValidTextureData(oversizedHeader, "cape"));
    }

    @Test
    void validatesAnimationFrameBoundsDelaysAndUniqueness() {
        assertTrue(NetworkSecurity.isValidAnimationMetadata(
                "{\"frames\":[{\"delay\":50,\"index\":0}],\"frameCount\":1}"));
        assertFalse(NetworkSecurity.isValidAnimationMetadata(null));
        assertFalse(NetworkSecurity.isValidAnimationMetadata("not-json"));
        assertFalse(NetworkSecurity.isValidAnimationMetadata(
                "{\"frames\":[{\"delay\":19,\"index\":0}],\"frameCount\":1}"));
        assertFalse(NetworkSecurity.isValidAnimationMetadata(
                "{\"frames\":[{\"delay\":50,\"index\":0},"
                        + "{\"delay\":50,\"index\":0}],\"frameCount\":2}"));
        assertFalse(NetworkSecurity.isValidAnimationMetadata(
                "{\"frames\":[{\"delay\":50,\"index\":1}],\"frameCount\":1}"));
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static void writeBigEndianInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
