package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeImageReaderTest {
    @Test
    void decodesAValidBoundedPng() throws IOException {
        BufferedImage decoded = SafeImageReader.readPng(png(2, 3));

        assertEquals(2, decoded.getWidth());
        assertEquals(3, decoded.getHeight());
    }

    @Test
    void decodesAValidBoundedResourceStream() throws IOException {
        BufferedImage decoded = SafeImageReader.readPng(
                new ByteArrayInputStream(png(4, 5)));

        assertEquals(4, decoded.getWidth());
        assertEquals(5, decoded.getHeight());
    }

    @Test
    void rejectsAnOversizedIhdrBeforeFullDecode() throws IOException {
        byte[] oversizedHeader = png(1, 1);
        writeBigEndianInt(oversizedHeader, 16, SafeImageReader.MAX_WIDTH + 1);

        assertThrows(IOException.class, () -> SafeImageReader.readPng(oversizedHeader));
    }

    @Test
    void decodesALosslessWebPSkinExactly() throws IOException {
        BufferedImage decoded = SafeImageReader.readSkin(webp("skin-lossless.webp"));

        assertEquals(64, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
        assertEquals(0xff336699, decoded.getRGB(5, 5));
        assertEquals(0x80cc2211, decoded.getRGB(5, 20));
        assertEquals(0xff11aa44, decoded.getRGB(0, 32));
        assertEquals(0xffeeee22, decoded.getRGB(4, 32));
        assertEquals(0, decoded.getRGB(5, 60) >>> 24);
    }

    @Test
    void decodesALossyWebPSkinWithItsAlpha() throws IOException {
        BufferedImage decoded = SafeImageReader.readSkin(webp("skin-lossy.webp"));

        assertEquals(64, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
        assertColorNear(0xff336699, decoded.getRGB(5, 5));
        assertEquals(0x80, decoded.getRGB(5, 20) >>> 24);
        assertEquals(0, decoded.getRGB(5, 60) >>> 24);
    }

    @Test
    void decodesAnOpaqueLossyWebPSkin() throws IOException {
        BufferedImage decoded = SafeImageReader.readSkin(webp("skin-lossy-opaque.webp"));

        assertEquals(64, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
        assertColorNear(0xff336699, decoded.getRGB(5, 5));
        assertEquals(0xff, decoded.getRGB(5, 60) >>> 24);
    }

    @Test
    void rejectsAnOversizedWebPHeaderBeforeFullDecode() throws IOException {
        byte[] oversized = webp("skin-lossless.webp");
        // VP8L stores width - 1 in the 14 low bits after its signature byte at offset 20.
        int bits = (oversized[21] & 0xff) | (oversized[22] & 0xff) << 8
                | (oversized[23] & 0xff) << 16 | (oversized[24] & 0xff) << 24;
        bits = (bits & ~0x3fff) | 4095;
        for (int i = 0; i < 4; i++) {
            oversized[21 + i] = (byte) (bits >>> (8 * i));
        }

        IOException failure = assertThrows(IOException.class, () -> SafeImageReader.readSkin(oversized));
        assertEquals("Image dimensions exceed the configured limits", failure.getMessage());
    }

    @Test
    void acceptsWebPOnlyAsASkin() throws IOException {
        byte[] encoded = webp("skin-lossless.webp");

        assertThrows(IOException.class, () -> SafeImageReader.readPng(encoded));
    }

    private static byte[] webp(String name) throws IOException {
        try (InputStream in = SafeImageReaderTest.class.getResourceAsStream("/webp/" + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    private static void assertColorNear(int expected, int actual) {
        for (int shift = 0; shift <= 24; shift += 8) {
            int difference = Math.abs((expected >>> shift & 0xff) - (actual >>> shift & 0xff));
            assertTrue(difference <= 12, () -> String.format("expected ~%08x, got %08x", expected, actual));
        }
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
