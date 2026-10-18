package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decoder for GIFs too large for the native loader: composing, shrinking and its limits. */
class ScaledGifDecoderTest {
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    /** Palette: 0 red, 1 green, 2 blue, 3 black (used as the transparent index). */
    private static final int[] PALETTE = {0xFF0000, 0x00FF00, 0x0000FF, 0x000000};

    @Test
    void decodesFramesAndTheirDelays() throws IOException {
        byte[] gif = new Gif(4, 2)
                .frame(new FrameSpec(0, 0, 4, 2, fill(8, 0)).delay(5))
                .frame(new FrameSpec(0, 0, 4, 2, fill(8, 2)).delay(0))
                .bytes();
        GifDecoder.DecodedGif decoded = ScaledGifDecoder.INSTANCE.decode(gif);
        assertEquals(2, decoded.frameCount());
        assertFalse(decoded.resampled());
        assertEquals(4, decoded.atlas().getWidth());
        assertEquals(4, decoded.atlas().getHeight());
        assertEquals(RED, decoded.atlas().getRGB(3, 1));
        assertEquals(BLUE, decoded.atlas().getRGB(0, 2));
        // 5 hundredths is 50 ms; no delay gets the default, as the native loader does
        assertEquals(50, decoded.metadata().frames().get(0).delay());
        assertEquals(100, decoded.metadata().frames().get(1).delay());
    }

    @Test
    void composesTransparencyAndRestoresTheBackground() throws IOException {
        byte[] gif = new Gif(4, 2)
                .frame(new FrameSpec(0, 0, 4, 2, fill(8, 0)))
                // A green pair over the red, then cleared to transparent once shown
                .frame(new FrameSpec(1, 0, 2, 1, fill(2, 1)).disposal(2))
                // One blue pixel; its transparent pixel keeps what is under it
                .frame(new FrameSpec(0, 1, 2, 1, new byte[] {2, 3}).transparent(3))
                .bytes();
        BufferedImage atlas = ScaledGifDecoder.INSTANCE.decode(gif).atlas();
        assertEquals(GREEN, atlas.getRGB(1, 2));
        assertEquals(RED, atlas.getRGB(0, 2));
        assertEquals(0, atlas.getRGB(1, 4) >>> 24, "frame 2 shows frame 1's area cleared");
        assertEquals(RED, atlas.getRGB(3, 4));
        assertEquals(BLUE, atlas.getRGB(0, 5));
        assertEquals(RED, atlas.getRGB(1, 5));
    }

    @Test
    void restoresThePreviousFrame() throws IOException {
        byte[] gif = new Gif(2, 1)
                .frame(new FrameSpec(0, 0, 2, 1, fill(2, 0)))
                .frame(new FrameSpec(0, 0, 1, 1, new byte[] {2}).disposal(3))
                .frame(new FrameSpec(1, 0, 1, 1, new byte[] {1}))
                .bytes();
        BufferedImage atlas = ScaledGifDecoder.INSTANCE.decode(gif).atlas();
        assertEquals(BLUE, atlas.getRGB(0, 1));
        assertEquals(RED, atlas.getRGB(0, 2), "frame 1 is undone before frame 2");
        assertEquals(GREEN, atlas.getRGB(1, 2));
    }

    @Test
    void readsInterlacedRowsInPictureOrder() throws IOException {
        // Rows are stored 0 8 | 4 | 2 6 | 1 3 5 7 9; each row is coloured by its picture row
        int[] stored = {0, 8, 4, 2, 6, 1, 3, 5, 7, 9};
        byte[] pixels = new byte[10];
        for (int i = 0; i < stored.length; i++) {
            pixels[i] = (byte) (stored[i] % 3);
        }
        byte[] gif = new Gif(1, 10).frame(new FrameSpec(0, 0, 1, 10, pixels).interlaced()).bytes();
        BufferedImage atlas = ScaledGifDecoder.INSTANCE.decode(gif).atlas();
        int[] colours = {RED, GREEN, BLUE};
        for (int y = 0; y < 10; y++) {
            assertEquals(colours[y % 3], atlas.getRGB(0, y), "row " + y);
        }
    }

    /** Real LZW, with its codes growing to 12 bits, from Java's own GIF writer. */
    @Test
    void decodesCompressedData() throws IOException {
        byte[] r = new byte[16], g = new byte[16], b = new byte[16];
        for (int i = 0; i < 16; i++) {
            r[i] = (byte) (i * 16);
            g[i] = (byte) (255 - i * 16);
            b[i] = (byte) (i * 7);
        }
        IndexColorModel model = new IndexColorModel(4, 16, r, g, b);
        BufferedImage source = new BufferedImage(97, 61, BufferedImage.TYPE_BYTE_INDEXED, model);
        for (int y = 0; y < 61; y++) {
            for (int x = 0; x < 97; x++) {
                source.getRaster().setSample(x, y, 0, (x * x + 3 * y + x * y / 5) % 16);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "gif", out));
        BufferedImage atlas = ScaledGifDecoder.INSTANCE.decode(out.toByteArray()).atlas();
        for (int y = 0; y < 61; y++) {
            for (int x = 0; x < 97; x++) {
                assertEquals(source.getRGB(x, y), atlas.getRGB(x, y), x + "," + y);
            }
        }
    }

    @Test
    void shrinksACanvasTooWideForTheNativeLoader() throws IOException {
        byte[] left = new byte[3000 * 10];
        for (int y = 0; y < 10; y++) {
            for (int x = 1500; x < 3000; x++) {
                left[y * 3000 + x] = 2;
            }
        }
        byte[] gif = new Gif(3000, 10)
                .frame(new FrameSpec(0, 0, 3000, 10, left))
                .frame(new FrameSpec(0, 0, 3000, 10, fill(30000, 1)))
                .bytes();
        GifDecoder.DecodedGif decoded = ScaledGifDecoder.INSTANCE.decode(gif);
        assertTrue(decoded.resampled());
        BufferedImage atlas = decoded.atlas();
        assertEquals(ScaledGifDecoder.MAX_FRAME_SIDE, atlas.getWidth());
        int frameH = atlas.getHeight() / 2;
        assertEquals(3, frameH);
        assertEquals(RED, atlas.getRGB(10, 1));
        assertEquals(BLUE, atlas.getRGB(1000, 1));
        assertEquals(GREEN, atlas.getRGB(500, frameH + 1));
    }

    @Test
    void manyFramesShrinkToFitTheAtlas() throws IOException {
        Gif gif = new Gif(640, 360);
        for (int i = 0; i < 300; i++) {
            gif.frame(new FrameSpec(0, 0, 1, 1, new byte[] {(byte) (i % 3)}));
        }
        GifDecoder.DecodedGif decoded = ScaledGifDecoder.INSTANCE.decode(gif.bytes());
        assertEquals(300, decoded.frameCount());
        long pixels = (long) decoded.atlas().getWidth() * decoded.atlas().getHeight();
        assertTrue(pixels <= ScaledGifDecoder.MAX_ATLAS_PIXELS, "atlas has " + pixels + " pixels");
        assertTrue(decoded.atlas().getWidth() > 300);
    }

    @Test
    void refusesWhatItCannotBound() {
        assertRefused("quickskin.cape.error.dimensions", new Gif(5000, 10)
                .frame(new FrameSpec(0, 0, 1, 1, new byte[] {0})).bytes());
        Gif many = new Gif(1, 1);
        for (int i = 0; i <= ScaledGifDecoder.MAX_FRAMES; i++) {
            many.frame(new FrameSpec(0, 0, 1, 1, new byte[] {0}));
        }
        assertRefused("quickskin.cape.error.gif_editor_frames", many.bytes());
        Gif work = new Gif(4096, 4096);
        for (int i = 0; i < 40; i++) {
            work.frame(new FrameSpec(0, 0, 1, 1, new byte[] {0}));
        }
        assertRefused("quickskin.cape.error.gif_decoded", work.bytes());
        assertRefused("quickskin.cape.error.invalid_image", new Gif(2, 2)
                .frame(new FrameSpec(1, 1, 2, 2, fill(4, 0))).bytes());
        byte[] whole = new Gif(2, 2).frame(new FrameSpec(0, 0, 2, 2, fill(4, 0))).bytes();
        byte[] truncated = java.util.Arrays.copyOf(whole, whole.length - 6);
        assertRefused("quickskin.cape.error.invalid_image", truncated);
        assertRefused("quickskin.cape.error.invalid_image", "not a gif at all".getBytes());
    }

    private static void assertRefused(String key, byte[] gif) {
        UserFacingIOException error = assertThrows(UserFacingIOException.class,
                () -> ScaledGifDecoder.INSTANCE.decode(gif));
        assertEquals(key, error.userMessage().translationKey());
    }

    private static byte[] fill(int count, int index) {
        byte[] pixels = new byte[count];
        java.util.Arrays.fill(pixels, (byte) index);
        return pixels;
    }

    // --- a minimal GIF writer: uncompressed LZW, so every field is the test's own ---

    private static final class FrameSpec {
        final int left, top, width, height;
        final byte[] pixels;
        int delay, disposal, transparent = -1;
        boolean interlaced;

        FrameSpec(int left, int top, int width, int height, byte[] pixels) {
            assertEquals(width * height, pixels.length);
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        FrameSpec delay(int hundredths) { delay = hundredths; return this; }
        FrameSpec disposal(int method) { disposal = method; return this; }
        FrameSpec transparent(int index) { transparent = index; return this; }
        FrameSpec interlaced() { interlaced = true; return this; }
    }

    private static final class Gif {
        private final int width, height;
        private final List<FrameSpec> frames = new ArrayList<>();

        Gif(int width, int height) {
            this.width = width;
            this.height = height;
        }

        Gif frame(FrameSpec frame) {
            frames.add(frame);
            return this;
        }

        byte[] bytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes("GIF89a".getBytes());
            u16(out, width);
            u16(out, height);
            out.write(0x80 | 7); // a global table of 256 colours
            out.write(0);
            out.write(0);
            for (int i = 0; i < 256; i++) {
                int rgb = i < PALETTE.length ? PALETTE[i] : 0;
                out.write(rgb >> 16 & 0xFF);
                out.write(rgb >> 8 & 0xFF);
                out.write(rgb & 0xFF);
            }
            for (FrameSpec frame : frames) {
                out.write(0x21);
                out.write(0xF9);
                out.write(4);
                out.write(frame.disposal << 2 | (frame.transparent >= 0 ? 1 : 0));
                u16(out, frame.delay);
                out.write(Math.max(0, frame.transparent));
                out.write(0);
                out.write(0x2C);
                u16(out, frame.left);
                u16(out, frame.top);
                u16(out, frame.width);
                u16(out, frame.height);
                out.write(frame.interlaced ? 0x40 : 0);
                out.write(8);
                writeBlocks(out, uncompressed(frame.pixels));
            }
            out.write(0x3B);
            return out.toByteArray();
        }

        /** 9-bit codes with a clear code often enough that the code table never grows. */
        private static byte[] uncompressed(byte[] pixels) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int[] buffer = {0, 0};
            for (int i = 0; i < pixels.length; i++) {
                if (i % 250 == 0) {
                    code(out, buffer, 256);
                }
                code(out, buffer, pixels[i] & 0xFF);
            }
            code(out, buffer, 257);
            if (buffer[1] > 0) {
                out.write(buffer[0] & 0xFF);
            }
            return out.toByteArray();
        }

        private static void code(ByteArrayOutputStream out, int[] buffer, int code) {
            buffer[0] |= code << buffer[1];
            buffer[1] += 9;
            while (buffer[1] >= 8) {
                out.write(buffer[0] & 0xFF);
                buffer[0] >>>= 8;
                buffer[1] -= 8;
            }
        }

        private static void writeBlocks(ByteArrayOutputStream out, byte[] data) {
            for (int i = 0; i < data.length; i += 255) {
                int size = Math.min(255, data.length - i);
                out.write(size);
                out.write(data, i, size);
            }
            out.write(0);
        }

        private static void u16(ByteArrayOutputStream out, int value) {
            out.write(value & 0xFF);
            out.write(value >> 8 & 0xFF);
        }
    }
}
