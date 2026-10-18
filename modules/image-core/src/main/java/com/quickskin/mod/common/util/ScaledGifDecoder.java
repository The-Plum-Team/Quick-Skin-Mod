package com.quickskin.mod.common.util;

import com.quickskin.mod.common.data.AnimationMetadata;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes a GIF too large for the native loader into an atlas the cape editor can use.
 *
 * <p>The native loader decodes every frame at full size at once, so it refuses a canvas over 2048
 * pixels, more than 256 frames, or more than 64 MB of pixels. This decoder composes one frame at a
 * time on a canvas of its own and shrinks it straight into the atlas, so the whole animation never
 * exists at full size. Every frame shrinks by the same factor: the longest side to at most
 * {@link #MAX_FRAME_SIDE}, and the atlas to at most {@link #MAX_ATLAS_PIXELS}. The editor then
 * picks the frames and the part of the picture the cape keeps.</p>
 *
 * <p>The input is bounded before any pixel work: {@link #MAX_ENCODED_BYTES} of file, a canvas of
 * at most {@link #MAX_CANVAS_SIDE} per side, at most {@link #MAX_FRAMES} frames, and at most
 * {@link #MAX_COMPOSED_PIXELS} canvas pixels composed over the whole animation.</p>
 */
public final class ScaledGifDecoder implements GifDecoder {
    public static final ScaledGifDecoder INSTANCE = new ScaledGifDecoder();

    public static final int MAX_ENCODED_BYTES = 32 * 1024 * 1024;
    public static final int MAX_CANVAS_SIDE = 4096;
    public static final int MAX_FRAMES = 1024;
    /** Canvas pixels composed over the whole animation: what bounds the decoding work. */
    public static final long MAX_COMPOSED_PIXELS = 512L * 1024L * 1024L;
    /** The longest side of a frame in the atlas; plenty for a cape of at most 256x128. */
    public static final int MAX_FRAME_SIDE = 1024;
    /** The atlas's pixels: the 64 MB decoded-image limit of every cape import. */
    public static final long MAX_ATLAS_PIXELS = 64L * 1024L * 1024L / 4L;

    private static final int MIN_DELAY_MS = 20;
    private static final int MAX_DELAY_MS = 60_000;
    private static final int DEFAULT_DELAY_MS = 100;
    private static final int MAX_LZW_CODES = 4096;

    private ScaledGifDecoder() {
    }

    @Override
    public DecodedGif decode(InputStream input) throws IOException {
        byte[] data = input.readNBytes(MAX_ENCODED_BYTES + 1);
        if (data.length == 0 || data.length > MAX_ENCODED_BYTES) {
            throw new UserFacingIOException("Animated cape must be between 1 byte and 32 MB",
                    "quickskin.cape.error.file_size",
                    (data.length + 1024L * 1024L - 1) / (1024L * 1024L), MAX_ENCODED_BYTES / (1024 * 1024));
        }
        return decode(data);
    }

    public DecodedGif decode(byte[] data) throws IOException {
        Structure gif = parse(data);
        int width = gif.width;
        int height = gif.height;
        int frameCount = gif.frames.size();

        double scale = Math.min(1.0, (double) MAX_FRAME_SIDE / Math.max(width, height));
        scale = Math.min(scale, Math.sqrt((double) MAX_ATLAS_PIXELS / ((double) width * height * frameCount)));
        // The epsilon keeps a side meant to land exactly on the cap from rounding a pixel short
        int outW = Math.max(1, (int) Math.floor(width * scale + 1e-9));
        int outH = Math.max(1, (int) Math.floor(height * scale + 1e-9));
        while ((long) outW * outH * frameCount > MAX_ATLAS_PIXELS && (outW > 1 || outH > 1)) {
            outW = Math.max(1, outW - 1);
            outH = Math.max(1, (int) Math.floor((double) outW * height / width));
        }
        if ((long) outW * outH * frameCount > MAX_ATLAS_PIXELS) {
            throw new UserFacingIOException("Animated cape requires more than 64 MB of decoded pixels",
                    "quickskin.cape.error.too_big");
        }

        BufferedImage atlas = new BufferedImage(outW, outH * frameCount, BufferedImage.TYPE_INT_ARGB);
        int[] canvas = new int[width * height];
        int[] saved = null;
        int[] scaled = new int[outW * outH];
        List<AnimationMetadata.FrameData> timing = new ArrayList<>(frameCount);
        Frame previous = null;
        for (int i = 0; i < frameCount; i++) {
            Frame frame = gif.frames.get(i);
            // What the previous frame asked for once it had been shown
            if (previous != null && previous.disposal == 2) {
                clearRect(canvas, width, previous);
            } else if (previous != null && previous.disposal == 3 && saved != null) {
                System.arraycopy(saved, 0, canvas, 0, canvas.length);
            }
            if (frame.disposal == 3) {
                if (saved == null) {
                    saved = new int[canvas.length];
                }
                System.arraycopy(canvas, 0, saved, 0, canvas.length);
            }
            draw(data, gif, frame, canvas);
            int[] out = canvas;
            if (outW != width || outH != height) {
                shrink(canvas, width, height, scaled, outW, outH);
                out = scaled;
            }
            atlas.setRGB(0, i * outH, outW, outH, out, 0, outW);
            timing.add(new AnimationMetadata.FrameData(frame.delayMs, i));
            previous = frame;
        }
        return new DecodedGif(atlas, frameCount, new AnimationMetadata(List.copyOf(timing), frameCount),
                outW != width || outH != height);
    }

    // --- container ---

    private static final class Frame {
        int left, top, width, height;
        boolean interlaced;
        int paletteOffset = -1, paletteSize;
        int transparent = -1;
        int disposal;
        int delayMs = DEFAULT_DELAY_MS;
        int minCodeSize;
        int dataOffset;
    }

    private static final class Structure {
        int width, height;
        int globalPaletteOffset = -1, globalPaletteSize;
        final List<Frame> frames = new ArrayList<>();
    }

    /** Reads the block structure and every limit before any pixel is decoded. */
    private static Structure parse(byte[] data) throws IOException {
        if (data.length < 13 || data[0] != 'G' || data[1] != 'I' || data[2] != 'F'
                || data[3] != '8' || (data[4] != '7' && data[4] != '9') || data[5] != 'a') {
            throw invalid("Invalid GIF header");
        }
        Structure gif = new Structure();
        gif.width = u16(data, 6);
        gif.height = u16(data, 8);
        if (gif.width < 1 || gif.height < 1) {
            throw invalid("GIF canvas is empty");
        }
        if (gif.width > MAX_CANVAS_SIDE || gif.height > MAX_CANVAS_SIDE) {
            throw new UserFacingIOException("Animated cape dimensions exceed 4096 pixels",
                    "quickskin.cape.error.dimensions", gif.width + "×" + gif.height, MAX_CANVAS_SIDE);
        }
        int packed = data[10] & 0xFF;
        int offset = 13;
        if ((packed & 0x80) != 0) {
            gif.globalPaletteOffset = offset;
            gif.globalPaletteSize = 1 << ((packed & 7) + 1);
            offset += 3 * gif.globalPaletteSize;
            require(data, offset, 0);
        }
        int disposal = 0;
        int delay = DEFAULT_DELAY_MS;
        int transparent = -1;
        while (true) {
            require(data, offset, 1);
            int marker = data[offset++] & 0xFF;
            if (marker == 0x3B) {
                break;
            }
            if (marker == 0x21) {
                require(data, offset, 1);
                int label = data[offset++] & 0xFF;
                if (label == 0xF9) {
                    require(data, offset, 6);
                    if ((data[offset] & 0xFF) >= 4) {
                        int flags = data[offset + 1] & 0xFF;
                        disposal = (flags >> 2) & 7;
                        int hundredths = u16(data, offset + 2);
                        delay = clampDelay(hundredths * 10);
                        transparent = (flags & 1) != 0 ? data[offset + 4] & 0xFF : -1;
                    }
                }
                offset = skipSubBlocks(data, offset);
            } else if (marker == 0x2C) {
                require(data, offset, 9);
                Frame frame = new Frame();
                frame.left = u16(data, offset);
                frame.top = u16(data, offset + 2);
                frame.width = u16(data, offset + 4);
                frame.height = u16(data, offset + 6);
                int flags = data[offset + 8] & 0xFF;
                offset += 9;
                frame.interlaced = (flags & 0x40) != 0;
                if (frame.left + frame.width > gif.width || frame.top + frame.height > gif.height) {
                    throw invalid("GIF frame lies outside its canvas");
                }
                if ((flags & 0x80) != 0) {
                    frame.paletteOffset = offset;
                    frame.paletteSize = 1 << ((flags & 7) + 1);
                    offset += 3 * frame.paletteSize;
                } else {
                    frame.paletteOffset = gif.globalPaletteOffset;
                    frame.paletteSize = gif.globalPaletteSize;
                }
                if (frame.paletteOffset < 0) {
                    throw invalid("GIF frame has no colour table");
                }
                require(data, offset, 1);
                frame.minCodeSize = data[offset++] & 0xFF;
                if (frame.minCodeSize < 2 || frame.minCodeSize > 11) {
                    throw invalid("GIF frame has an invalid code size");
                }
                frame.dataOffset = offset;
                offset = skipSubBlocks(data, offset);
                frame.disposal = disposal;
                frame.delayMs = delay;
                frame.transparent = transparent;
                disposal = 0;
                delay = DEFAULT_DELAY_MS;
                transparent = -1;
                gif.frames.add(frame);
                if (gif.frames.size() > MAX_FRAMES) {
                    throw new UserFacingIOException("Animated cape exceeds 1024 frames",
                            "quickskin.cape.error.gif_editor_frames", MAX_FRAMES);
                }
            } else {
                throw invalid("Invalid GIF block marker");
            }
        }
        int frameCount = gif.frames.size();
        if (frameCount == 0) {
            throw invalid("GIF has no frames");
        }
        long composed = (long) gif.width * gif.height * frameCount;
        if (composed > MAX_COMPOSED_PIXELS) {
            long framesAllowed = MAX_COMPOSED_PIXELS / ((long) gif.width * gif.height);
            throw new UserFacingIOException("Animated cape is too much work to decode",
                    "quickskin.cape.error.gif_decoded", frameCount, gif.width + "×" + gif.height,
                    framesAllowed);
        }
        return gif;
    }

    private static int clampDelay(int delayMs) {
        if (delayMs < MIN_DELAY_MS) {
            return DEFAULT_DELAY_MS;
        }
        return Math.min(delayMs, MAX_DELAY_MS);
    }

    // --- pixels ---

    /** Decodes one frame's LZW data and paints its opaque pixels onto the canvas. */
    private static void draw(byte[] data, Structure gif, Frame frame, int[] canvas) {
        int frameW = frame.width;
        int frameH = frame.height;
        if (frameW == 0 || frameH == 0) {
            return;
        }
        int[] palette = new int[256];
        for (int i = 0; i < frame.paletteSize && i < 256; i++) {
            int p = frame.paletteOffset + 3 * i;
            palette[i] = 0xFF000000 | (data[p] & 0xFF) << 16 | (data[p + 1] & 0xFF) << 8 | (data[p + 2] & 0xFF);
        }
        byte[] indices = decodeLzw(data, frame.dataOffset, frame.minCodeSize, frameW * frameH);
        for (int row = 0; row < frameH; row++) {
            int y = frame.interlaced ? interlacedRow(row, frameH) : row;
            int canvasY = frame.top + y;
            if (canvasY < 0 || canvasY >= gif.height) {
                continue;
            }
            int source = row * frameW;
            for (int x = 0; x < frameW; x++) {
                int canvasX = frame.left + x;
                if (canvasX >= gif.width) {
                    break;
                }
                int index = indices[source + x] & 0xFF;
                if (index != frame.transparent && index < frame.paletteSize) {
                    canvas[canvasY * gif.width + canvasX] = palette[index];
                }
            }
        }
    }

    /** Which picture row the {@code row}-th stored row of an interlaced frame is. */
    private static int interlacedRow(int row, int height) {
        int pass1 = (height + 7) / 8;
        if (row < pass1) {
            return row * 8;
        }
        row -= pass1;
        int pass2 = (height + 3) / 8;
        if (row < pass2) {
            return 4 + row * 8;
        }
        row -= pass2;
        int pass3 = (height + 1) / 4;
        if (row < pass3) {
            return 2 + row * 4;
        }
        row -= pass3;
        return 1 + row * 2;
    }

    /**
     * The frame's colour indices. Data that ends early leaves the rest at 0, which a frame with a
     * transparent index of 0 leaves untouched; the native loader tolerates the same.
     */
    private static byte[] decodeLzw(byte[] data, int offset, int minCodeSize, int pixels) {
        byte[] out = new byte[pixels];
        int clear = 1 << minCodeSize;
        int end = clear + 1;
        short[] prefix = new short[MAX_LZW_CODES];
        byte[] suffix = new byte[MAX_LZW_CODES];
        byte[] stack = new byte[MAX_LZW_CODES + 1];
        for (int i = 0; i < clear; i++) {
            suffix[i] = (byte) i;
        }
        int codeSize = minCodeSize + 1;
        int next = clear + 2;
        int old = -1;
        byte first = 0;
        int written = 0;

        int bits = 0;
        int bitCount = 0;
        int block = 0;
        int position = offset;
        while (written < pixels) {
            while (bitCount < codeSize) {
                if (block == 0) {
                    if (position >= data.length) {
                        return out;
                    }
                    block = data[position++] & 0xFF;
                    if (block == 0 || position + block > data.length) {
                        return out;
                    }
                }
                bits |= (data[position++] & 0xFF) << bitCount;
                bitCount += 8;
                block--;
            }
            int code = bits & ((1 << codeSize) - 1);
            bits >>>= codeSize;
            bitCount -= codeSize;

            if (code == clear) {
                codeSize = minCodeSize + 1;
                next = clear + 2;
                old = -1;
                continue;
            }
            if (code == end) {
                return out;
            }
            if (old == -1) {
                if (code >= clear) {
                    return out;
                }
                out[written++] = suffix[code];
                old = code;
                first = suffix[code];
                continue;
            }
            int current = code;
            int top = 0;
            if (code >= next) {
                if (code > next) {
                    return out;
                }
                stack[top++] = first;
                current = old;
            }
            while (current >= clear) {
                stack[top++] = suffix[current];
                current = prefix[current];
            }
            first = suffix[current];
            stack[top++] = first;
            while (top > 0 && written < pixels) {
                out[written++] = stack[--top];
            }
            if (next < MAX_LZW_CODES) {
                prefix[next] = (short) old;
                suffix[next] = first;
                next++;
                if (next == (1 << codeSize) && codeSize < 12) {
                    codeSize++;
                }
            }
            old = code;
        }
        return out;
    }

    private static void clearRect(int[] canvas, int width, Frame frame) {
        int height = canvas.length / width;
        int right = Math.min(width, frame.left + frame.width);
        int bottom = Math.min(height, frame.top + frame.height);
        for (int y = frame.top; y < bottom; y++) {
            if (frame.left < right) {
                Arrays.fill(canvas, y * width + frame.left, y * width + right, 0);
            }
        }
    }

    /**
     * Shrinks by averaging every source pixel an output pixel covers, weighting colour by alpha so
     * transparent pixels do not darken the edges they border.
     */
    private static void shrink(int[] source, int width, int height, int[] out, int outW, int outH) {
        for (int oy = 0; oy < outH; oy++) {
            int y0 = (int) ((long) oy * height / outH);
            int y1 = Math.max(y0 + 1, (int) ((long) (oy + 1) * height / outH));
            for (int ox = 0; ox < outW; ox++) {
                int x0 = (int) ((long) ox * width / outW);
                int x1 = Math.max(x0 + 1, (int) ((long) (ox + 1) * width / outW));
                long a = 0, r = 0, g = 0, b = 0;
                for (int y = y0; y < y1; y++) {
                    int row = y * width;
                    for (int x = x0; x < x1; x++) {
                        int argb = source[row + x];
                        int alpha = argb >>> 24;
                        a += alpha;
                        r += (long) ((argb >> 16) & 0xFF) * alpha;
                        g += (long) ((argb >> 8) & 0xFF) * alpha;
                        b += (long) (argb & 0xFF) * alpha;
                    }
                }
                int count = (y1 - y0) * (x1 - x0);
                int pixel = 0;
                if (a > 0) {
                    pixel = (int) ((a + count / 2) / count) << 24
                            | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
                }
                out[oy * outW + ox] = pixel;
            }
        }
    }

    // --- bounded reads ---

    private static int skipSubBlocks(byte[] data, int offset) throws IOException {
        while (true) {
            require(data, offset, 1);
            int size = data[offset++] & 0xFF;
            if (size == 0) {
                return offset;
            }
            require(data, offset, size);
            offset += size;
        }
    }

    private static int u16(byte[] data, int offset) throws IOException {
        require(data, offset, 2);
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    private static void require(byte[] data, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw invalid("GIF is truncated");
        }
    }

    private static UserFacingIOException invalid(String message) {
        return new UserFacingIOException(message, "quickskin.cape.error.invalid_image");
    }
}
