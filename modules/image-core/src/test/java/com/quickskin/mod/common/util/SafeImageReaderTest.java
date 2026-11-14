package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void decodesAJpegCape(@TempDir Path directory) throws IOException {
        Path source = directory.resolve("photo.jpg");
        Files.write(source, jpeg(quadrants()));

        BufferedImage decoded = SafeImageReader.readStillCape(source);

        assertEquals(64, decoded.getWidth());
        assertEquals(32, decoded.getHeight());
        assertColorNear(RED, decoded.getRGB(16, 8));
        assertColorNear(GREEN, decoded.getRGB(48, 8));
        assertColorNear(BLUE, decoded.getRGB(16, 24));
        assertColorNear(WHITE, decoded.getRGB(48, 24));
    }

    @Test
    void aJpegCapeIsTurnedTheWayItsExifOrientationShowsIt(@TempDir Path directory) throws IOException {
        // Stored on its side, as a phone held upright saves it: shown a quarter turn clockwise
        Path source = directory.resolve("photo.jpg");
        Files.write(source, withSegmentAfterStart(jpeg(quadrants()), JpegHeaderTest.exif(true, 6)));

        BufferedImage decoded = SafeImageReader.readStillCape(source);

        assertEquals(32, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
        assertColorNear(BLUE, decoded.getRGB(8, 16));
        assertColorNear(RED, decoded.getRGB(24, 16));
        assertColorNear(WHITE, decoded.getRGB(8, 48));
        assertColorNear(GREEN, decoded.getRGB(24, 48));
    }

    @Test
    void aSkinKeepsAJpegAsItIsStored() throws IOException {
        // A skin is a texture layout, never a photo: its pixels stay where the file has them
        byte[] encoded = withSegmentAfterStart(jpeg(quadrants()), JpegHeaderTest.exif(true, 6));

        BufferedImage decoded = SafeImageReader.readSkin(encoded);

        assertEquals(64, decoded.getWidth());
        assertEquals(32, decoded.getHeight());
        assertColorNear(RED, decoded.getRGB(16, 8));
        assertColorNear(WHITE, decoded.getRGB(48, 24));
    }

    @Test
    void aPngCapeWiderThanACapeCanBeIsShrunkAsItIsRead(@TempDir Path directory) throws IOException {
        BufferedImage wide = numbered(8200, 16);
        Path source = directory.resolve("wide.png");
        assertTrue(ImageIO.write(wide, "png", source.toFile()));

        BufferedImage decoded = SafeImageReader.readStillCape(source);

        // The smallest whole step that fits 8200 into 4096 is 3: one pixel in every three, each way
        assertEquals(2734, decoded.getWidth());
        assertEquals(6, decoded.getHeight());
        for (int y = 0; y < decoded.getHeight(); y++) {
            for (int x = 0; x < decoded.getWidth(); x += 97) {
                assertEquals(wide.getRGB(x * 3, y * 3), decoded.getRGB(x, y), x + "," + y);
            }
        }
    }

    @Test
    void aPngCapeTallerThanACapeCanBeIsShrunkToo(@TempDir Path directory) throws IOException {
        BufferedImage tall = numbered(16, 4097);
        Path source = directory.resolve("tall.png");
        assertTrue(ImageIO.write(tall, "png", source.toFile()));

        BufferedImage decoded = SafeImageReader.readStillCape(source);

        assertEquals(8, decoded.getWidth());
        assertEquals(2049, decoded.getHeight());
        assertEquals(tall.getRGB(14, 4096), decoded.getRGB(7, 2048));
    }

    @Test
    void aPictureOfExactlyACapesLimitIsNotShrunk(@TempDir Path directory) throws IOException {
        Path source = directory.resolve("limit.png");
        assertTrue(ImageIO.write(numbered(SafeImageReader.MAX_STILL_CAPE_SIDE, 2), "png", source.toFile()));

        BufferedImage decoded = SafeImageReader.readStillCape(source);

        assertEquals(SafeImageReader.MAX_STILL_CAPE_SIDE, decoded.getWidth());
        assertEquals(2, decoded.getHeight());
    }

    @Test
    void anOversizedJpegCapeIsShrunkAndStillTurned(@TempDir Path directory) throws IOException {
        // 9000x16 on its side: shrunk by three to 3000x6, then shown a quarter turn clockwise
        BufferedImage photo = new BufferedImage(9000, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 9000; x++) {
                photo.setRGB(x, y, x < 4500 ? RED : BLUE);
            }
        }
        Path source = directory.resolve("photo.jpg");
        Files.write(source, withSegmentAfterStart(jpeg(photo), JpegHeaderTest.exif(false, 6)));

        BufferedImage decoded = SafeImageReader.readStillCape(source);

        assertEquals(6, decoded.getWidth());
        assertEquals(3000, decoded.getHeight());
        assertColorNear(RED, decoded.getRGB(3, 700));
        assertColorNear(BLUE, decoded.getRGB(3, 2300));
    }

    @Test
    void refusesFromItsHeaderAPictureWithASideOverTheSourceLimit(@TempDir Path directory) throws IOException {
        byte[] encoded = png(1, 1);
        writeBigEndianInt(encoded, 16, SafeImageReader.MAX_CAPE_SOURCE_SIDE + 1);
        Path source = directory.resolve("endless.png");
        Files.write(source, encoded);

        OversizedImageException failure = assertThrows(
                OversizedImageException.class, () -> SafeImageReader.readStillCape(source));

        assertEquals(SafeImageReader.MAX_CAPE_SOURCE_SIDE + 1, failure.width());
        assertEquals(1, failure.height());
        assertEquals(SafeImageReader.MAX_CAPE_SOURCE_SIDE, failure.maxSide());
        assertTrue(failure.exceedsSide());
    }

    @Test
    void refusesFromItsHeaderAPictureWithTooManyPixelsInAll(@TempDir Path directory) throws IOException {
        byte[] encoded = png(1, 1);
        writeBigEndianInt(encoded, 16, 16384);
        writeBigEndianInt(encoded, 20, 4097);
        Path source = directory.resolve("vast.png");
        Files.write(source, encoded);

        OversizedImageException failure = assertThrows(
                OversizedImageException.class, () -> SafeImageReader.readStillCape(source));

        assertEquals(16384, failure.width());
        assertEquals(4097, failure.height());
        assertFalse(failure.exceedsSide());
    }

    @Test
    void aJpegItsDecoderHoldsWholeIsRefusedSoonerThanOneReadRowByRow(@TempDir Path directory) throws IOException {
        // The same 6000x6000 header on a progressive JPEG and on a sequential one
        BufferedImage small = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Path progressive = directory.resolve("progressive.jpg");
        Files.write(progressive, withFrameSize(JpegHeaderTest.encode(small, true), 6000, 6000));
        Path sequential = directory.resolve("sequential.jpg");
        Files.write(sequential, withFrameSize(JpegHeaderTest.encode(small, false), 6000, 6000));

        OversizedImageException failure = assertThrows(
                OversizedImageException.class, () -> SafeImageReader.readStillCape(progressive));
        assertFalse(failure.exceedsSide());

        // Its image data is 16x16, so the decode itself may fail; it is not refused for its size
        try {
            BufferedImage decoded = SafeImageReader.readStillCape(sequential);
            assertEquals(3000, decoded.getWidth());
            assertEquals(3000, decoded.getHeight());
        } catch (OversizedImageException refused) {
            throw new AssertionError("a sequential JPEG of 36 million pixels is within the source limit", refused);
        } catch (IOException damaged) {
            // expected for a header that promises more than the file holds
        }
    }

    @Test
    void aWebPCapeWiderThanACapeCanBeIsShrunkAsWell(@TempDir Path directory) throws IOException {
        // 4100x6, lossless: pixel (x, y) is (7x, 40y, x / 16), each channel modulo 256
        Path source = directory.resolve("wide.webp");
        Files.write(source, webp("wide-lossless.webp"));

        BufferedImage decoded = SafeImageReader.readStillCape(source);

        assertEquals(2050, decoded.getWidth());
        assertEquals(3, decoded.getHeight());
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 2050; x += 41) {
                int sourceX = x * 2;
                int sourceY = y * 2;
                int expected = 0xFF000000 | ((sourceX * 7) & 0xFF) << 16 | ((sourceY * 40) & 0xFF) << 8
                        | ((sourceX >> 4) & 0xFF);
                assertEquals(expected, decoded.getRGB(x, y), x + "," + y);
            }
        }
    }

    @Test
    void aWebPCapeMayNotHaveMorePixelsThanAStillCape(@TempDir Path directory) throws IOException {
        byte[] oversized = webp("skin-lossless.webp");
        // VP8L stores width - 1 and then height - 1, 14 bits each, after its signature byte at offset 20.
        int bits = (oversized[21] & 0xff) | (oversized[22] & 0xff) << 8
                | (oversized[23] & 0xff) << 16 | (oversized[24] & 0xff) << 24;
        bits = (bits & ~0xfffffff) | 8191 | 8191 << 14;
        for (int i = 0; i < 4; i++) {
            oversized[21 + i] = (byte) (bits >>> (8 * i));
        }
        Path source = directory.resolve("vast.webp");
        Files.write(source, oversized);

        OversizedImageException failure = assertThrows(
                OversizedImageException.class, () -> SafeImageReader.readStillCape(source));

        assertEquals(8192, failure.width());
        assertEquals(8192, failure.height());
        assertFalse(failure.exceedsSide());
    }

    @Test
    void acceptsWebPOnlyAsASkin() throws IOException {
        byte[] encoded = webp("skin-lossless.webp");

        assertThrows(IOException.class, () -> SafeImageReader.readPng(encoded));
    }

    private static final int RED = 0xFFD02020;
    private static final int GREEN = 0xFF20B040;
    private static final int BLUE = 0xFF2040D0;
    private static final int WHITE = 0xFFF0F0F0;

    /** 64x32, a flat colour in each quarter: red, green / blue, white. */
    private static BufferedImage quadrants() {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, y < 16 ? (x < 32 ? RED : GREEN) : (x < 32 ? BLUE : WHITE));
            }
        }
        return image;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", out));
        return out.toByteArray();
    }

    /** A picture whose every pixel holds its own position, so a shrunk copy can be checked exactly. */
    private static BufferedImage numbered(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (y * 31 + x) & 0xFFFFFF);
            }
        }
        return image;
    }

    /** Rewrites the height and width a JPEG's frame header declares. */
    private static byte[] withFrameSize(byte[] jpeg, int width, int height) {
        byte[] result = jpeg.clone();
        for (int offset = 2; offset + 9 < result.length; ) {
            int marker = result[offset + 1] & 0xff;
            int length = (result[offset + 2] & 0xff) << 8 | (result[offset + 3] & 0xff);
            if (marker == 0xC0 || marker == 0xC2) {
                result[offset + 5] = (byte) (height >>> 8);
                result[offset + 6] = (byte) height;
                result[offset + 7] = (byte) (width >>> 8);
                result[offset + 8] = (byte) width;
                return result;
            }
            offset += 2 + length;
        }
        throw new AssertionError("no frame header");
    }

    /** Puts {@code segment} right behind the two bytes that start a JPEG file. */
    private static byte[] withSegmentAfterStart(byte[] jpeg, byte[] segment) {
        byte[] result = new byte[jpeg.length + segment.length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(segment, 0, result, 2, segment.length);
        System.arraycopy(jpeg, 2, result, 2 + segment.length, jpeg.length - 2);
        return result;
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
