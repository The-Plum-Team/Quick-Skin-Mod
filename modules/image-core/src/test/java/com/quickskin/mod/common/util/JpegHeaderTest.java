package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpegHeaderTest {

    @Test
    void readsTheOrientationInEitherByteOrder() {
        for (int orientation = 1; orientation <= 8; orientation++) {
            assertEquals(orientation, orientation(jpeg(exif(true, orientation))), "little-endian");
            assertEquals(orientation, orientation(jpeg(exif(false, orientation))), "big-endian");
        }
    }

    @Test
    void findsTheExifSegmentBehindOtherSegments() {
        byte[] jfif = segment(0xE0, new byte[] {'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0});
        byte[] xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\0<x/>".getBytes());

        assertEquals(6, orientation(jpeg(jfif, xmp, exif(true, 6))));
    }

    @Test
    void aJpegWithoutAnOrientationIsUpright() {
        byte[] jfif = segment(0xE0, new byte[] {'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0});
        // An EXIF block whose only entry is the image width (tag 0x0100)
        byte[] otherTag = exif(true, 6);
        otherTag[4 + 6 + 10] = 0x00;
        otherTag[4 + 6 + 11] = 0x01;

        assertEquals(1, orientation(jpeg(jfif)));
        assertEquals(1, orientation(jpeg(otherTag)));
        assertEquals(1, orientation(jpeg(exif(true, 0))));
        assertEquals(1, orientation(jpeg(exif(false, 9))));
    }

    @Test
    void anOrientationBehindTheImageDataIsNotRead() {
        byte[] scan = segment(0xDA, new byte[] {1, 1, 0, 0, 63, 0});

        assertEquals(1, orientation(jpeg(scan, exif(true, 6))));
    }

    @Test
    void bytesThatAreNotAJpegAreUpright() {
        assertEquals(1, orientation(null));
        assertEquals(1, orientation(new byte[0]));
        assertEquals(1, orientation("this is not a JPEG at all".getBytes()));
        assertEquals(1, orientation(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}));
    }

    @Test
    void everyTruncationAndEveryDamagedByteIsReadWithoutFailing() {
        byte[] whole = jpeg(exif(false, 8), exif(true, 3), frame(0xC0, 3), scan(3));
        for (int length = 0; length <= whole.length; length++) {
            byte[] cut = Arrays.copyOf(whole, length);
            int orientation = orientation(cut);
            assertFalse(length < whole.length && JpegHeader.read(cut).decodedRowByRow(), "cut at " + length);
            assertTrue(orientation >= 1 && orientation <= 8, "cut at " + length);
        }
        for (int index = 0; index < whole.length; index++) {
            for (int value : new int[] {0x00, 0x7F, 0xFF}) {
                byte[] damaged = whole.clone();
                damaged[index] = (byte) value;
                int orientation = orientation(damaged);
                assertTrue(orientation >= 1 && orientation <= 8, "byte " + index + " = " + value);
            }
        }
    }

    @Test
    void aSequentialJpegWithOneScanOfEveryComponentIsReadRowByRow() {
        assertTrue(JpegHeader.read(jpeg(frame(0xC0, 3), scan(3))).decodedRowByRow());
        assertTrue(JpegHeader.read(jpeg(frame(0xC1, 1), scan(1))).decodedRowByRow());
        assertTrue(JpegHeader.read(jpeg(exif(true, 6), frame(0xC0, 3), scan(3))).decodedRowByRow());
    }

    @Test
    void aJpegItsDecoderHoldsWholeIsNotReadRowByRow() {
        // Progressive
        assertFalse(JpegHeader.read(jpeg(frame(0xC2, 3), scan(3))).decodedRowByRow());
        // Sequential, but each component comes in a scan of its own
        assertFalse(JpegHeader.read(jpeg(frame(0xC0, 3), scan(1))).decodedRowByRow());
        // No frame, no scan, or a kind of frame this reader does not know
        assertFalse(JpegHeader.read(jpeg(scan(3))).decodedRowByRow());
        assertFalse(JpegHeader.read(jpeg(frame(0xC0, 3))).decodedRowByRow());
        assertFalse(JpegHeader.read(jpeg(frame(0xC9, 3), scan(3))).decodedRowByRow());
        assertFalse(JpegHeader.read("not a JPEG".getBytes()).decodedRowByRow());
        assertFalse(JpegHeader.read("not a JPEG".getBytes()).isJpeg());
    }

    @Test
    void tellsAJpegTheRuntimeWroteSequentialFromOneItWroteProgressive() throws IOException {
        BufferedImage image = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);

        JpegHeader sequential = JpegHeader.read(encode(image, false));
        JpegHeader progressive = JpegHeader.read(encode(image, true));

        assertTrue(sequential.isJpeg());
        assertTrue(sequential.decodedRowByRow());
        assertTrue(progressive.isJpeg());
        assertFalse(progressive.decodedRowByRow());
    }

    private static int orientation(byte[] encoded) {
        return JpegHeader.read(encoded).orientation();
    }

    static byte[] encode(BufferedImage image, boolean progressive) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setProgressiveMode(progressive ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** A start-of-frame segment: 8-bit samples, a 16x16 picture, this many components. */
    private static byte[] frame(int marker, int components) {
        byte[] payload = new byte[6 + components * 3];
        payload[0] = 8;
        payload[2] = 16;
        payload[4] = 16;
        payload[5] = (byte) components;
        return segment(marker, payload);
    }

    /** A start-of-scan segment carrying this many components. */
    private static byte[] scan(int components) {
        byte[] payload = new byte[1 + components * 2 + 3];
        payload[0] = (byte) components;
        return segment(0xDA, payload);
    }

    /** An APP1 segment holding an EXIF block whose one entry is the orientation. */
    static byte[] exif(boolean littleEndian, int orientation) {
        byte[] tiff = littleEndian
                ? new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0,
                        1, 0,
                        0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0,
                        0, 0, 0, 0}
                : new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8,
                        0, 1,
                        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,
                        0, 0, 0, 0};
        byte[] payload = new byte[6 + tiff.length];
        System.arraycopy(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, payload, 0, 6);
        System.arraycopy(tiff, 0, payload, 6, tiff.length);
        return segment(0xE1, payload);
    }

    static byte[] segment(int marker, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(marker);
        out.write((payload.length + 2) >>> 8);
        out.write((payload.length + 2) & 0xFF);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** The start of a JPEG file: its marker and then these segments. */
    private static byte[] jpeg(byte[]... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        for (byte[] segment : segments) {
            out.writeBytes(segment);
        }
        return out.toByteArray();
    }
}
