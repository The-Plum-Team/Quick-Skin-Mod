package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
