package com.quickskin.mod.common.util;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.platform.MinecraftCompat;
import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * GIF loader using LWJGL's STB Image library
 * Provides native, high-performance GIF decoding
 */
public class StbGifLoader {
    private static final int MAX_ENCODED_BYTES = 32 * 1024 * 1024;
    private static final int MAX_FRAME_DIMENSION = 2048;
    private static final int MAX_FRAMES = 256;
    private static final long MAX_DECODED_BYTES = 64L * 1024L * 1024L;

    /**
     * Result of GIF loading
     */
    public record GifLoadResult(
        NativeImage[] frames,         // Array of NativeImage frames
        AnimationMetadata metadata,   // Animation frame timing
        int frameWidth,               // Width of a single frame
        int frameHeight               // Height of a single frame
    ) implements AutoCloseable {
        /**
         * Clean up all frames when done
         */
        @Override
        public void close() {
            if (frames != null) {
                for (NativeImage frame : frames) {
                    if (frame != null) {
                        closeFrameQuietly(frame);
                    }
                }
            }
        }
    }

    /**
     * Load GIF file using STB Image
     * @param input GIF file input stream
     * @return Loading result with frames and metadata
     */
    public static GifLoadResult loadGif(InputStream input) throws IOException {
        ByteBuffer gifData = null;
        ByteBuffer imageData = null;
        PointerBuffer delaysBuffer;
        long delaysPointer = 0L;

        try {
            // Inspect the bounded container before STB reserves the full decoded animation.
            byte[] gifBytes = input.readNBytes(MAX_ENCODED_BYTES + 1);
            if (gifBytes.length == 0 || gifBytes.length > MAX_ENCODED_BYTES) {
                throw new IOException("Animated cape must be between 1 byte and 32 MB");
            }
            GifPreflight preflight = inspectGif(gifBytes);

            gifData = MemoryUtil.memAlloc(gifBytes.length);
            gifData.put(gifBytes);
            gifData.flip();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer widthBuffer = stack.mallocInt(1);
                IntBuffer heightBuffer = stack.mallocInt(1);
                IntBuffer framesBuffer = stack.mallocInt(1);
                IntBuffer channelsBuffer = stack.mallocInt(1);

                // Allocate buffer for frame delays pointer (will be filled by STB)
                delaysBuffer = stack.mallocPointer(1);
                delaysBuffer.put(0, 0L);

                if (!STBImage.stbi_info_from_memory(
                        gifData, widthBuffer, heightBuffer, channelsBuffer)) {
                    throw new IOException("Failed to inspect GIF: " + STBImage.stbi_failure_reason());
                }
                if (widthBuffer.get(0) != preflight.width()
                        || heightBuffer.get(0) != preflight.height()) {
                    throw new IOException("GIF canvas dimensions are inconsistent");
                }

                // Load GIF with STB Image
                // Request RGBA format (4 channels)
                imageData = STBImage.stbi_load_gif_from_memory(
                    gifData,
                    delaysBuffer,
                    widthBuffer,
                    heightBuffer,
                    framesBuffer,
                    channelsBuffer,
                    4  // Request RGBA
                );
                delaysPointer = delaysBuffer.get(0);

                if (imageData == null) {
                    String error = STBImage.stbi_failure_reason();
                    throw new IOException("Failed to load GIF: " + error);
                }
                int width = widthBuffer.get(0);
                int height = heightBuffer.get(0);
                int frameCount = framesBuffer.get(0);

                if (frameCount == 0) {
                    throw new IOException("GIF has no frames");
                }

                // Hard input caps — reject oversized GIFs before any pixel work
                if (width > 2048) {
                    throw new IOException("Animated cape too large: width " + width + " exceeds maximum 2048 pixels.");
                }
                if (frameCount > 256) {
                    throw new IOException("Animated cape too large: " + frameCount + " frames exceeds maximum 256.");
                }
                long decodedBytes = (long) frameCount * width * height * 4;
                long maxBytes = 64L * 1024 * 1024;
                if (decodedBytes > maxBytes) {
                    long decodedMb = decodedBytes / (1024 * 1024);
                    throw new IOException("Animated cape too large. Maximum 64 MB when decoded (frames x width x height x 4). Yours is " + decodedMb + " MB. Try reducing resolution or frame count.");
                }
                if (width != preflight.width() || height != preflight.height()
                        || frameCount != preflight.frameCount()) {
                    throw new IOException("GIF decoder output is inconsistent with its block structure");
                }



                // Get the delays IntBuffer from the pointer
                if (delaysPointer == 0L) {
                    throw new IOException("GIF decoder did not provide frame delays");
                }
                IntBuffer delays = MemoryUtil.memIntBuffer(delaysPointer, frameCount);

                // Extract frame delays and create metadata
                List<AnimationMetadata.FrameData> frameDataList = new ArrayList<>();
                for (int i = 0; i < frameCount; i++) {
                    int delay = delays.get(i);
                    // STB Image returns delays in milliseconds
                    // Ensure minimum delay to prevent too-fast animations
                    if (delay < 20) {
                        delay = 100;
                    } else if (delay > 60_000) {
                        delay = 60_000;
                    }
                    delay = Math.min(delay, 60_000);
                    frameDataList.add(new AnimationMetadata.FrameData(delay, i));
                }

                AnimationMetadata metadata = new AnimationMetadata(frameDataList, frameCount);

                // Convert frames to NativeImage array
                NativeImage[] frames = new NativeImage[frameCount];
                int frameSize = width * height * 4; // RGBA = 4 bytes per pixel

                for (int i = 0; i < frameCount; i++) {
                    NativeImage frame = null;
                    try {
                        // Create NativeImage for this frame
                        frame = new NativeImage(width, height, false);

                        // Copy pixel data from STB buffer to NativeImage
                        // STB Image returns frames stacked vertically in the buffer
                        int frameOffset = i * frameSize;

                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                int pixelOffset = frameOffset + (y * width + x) * 4;

                                // Read RGBA from STB buffer
                                int r = imageData.get(pixelOffset) & 0xFF;
                                int g = imageData.get(pixelOffset + 1) & 0xFF;
                                int b = imageData.get(pixelOffset + 2) & 0xFF;
                                int a = imageData.get(pixelOffset + 3) & 0xFF;

                                // NativeImage uses ABGR format
                                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                                MinecraftCompat.INSTANCE.setPixel(frame, x, y, abgr);
                            }
                        }

                        frames[i] = frame;
                        frame = null; // Ownership transferred to the result array.
                    } catch (RuntimeException | LinkageError e) {
                        if (frame != null) {
                            closeFrameQuietly(frame);
                        }
                        // Clean up already created frames on error
                        for (int j = 0; j < i; j++) {
                            if (frames[j] != null) {
                                closeFrameQuietly(frames[j]);
                            }
                        }
                        throw new IOException("Failed to create frame " + i, e);
                    }
                }

                return new GifLoadResult(frames, metadata, width, height);

            } finally {
                if (delaysPointer != 0L) {
                    STBImage.nstbi_image_free(delaysPointer);
                }
                // Free STB Image buffer
                if (imageData != null) {
                    STBImage.stbi_image_free(imageData);
                }
            }

        } finally {
            // Free allocated buffer (delaysBuffer is stack-allocated and auto-freed)
            if (gifData != null) {
                MemoryUtil.memFree(gifData);
            }
        }
    }

    /**
     * Parses only the bounded GIF container structure. Counting image descriptors here lets us
     * reject decompression bombs before {@code stbi_load_gif_from_memory} reserves its output.
     */
    private static GifPreflight inspectGif(byte[] data) throws IOException {
        if (data.length < 13
                || data[0] != 'G' || data[1] != 'I' || data[2] != 'F'
                || data[3] != '8' || (data[4] != '7' && data[4] != '9') || data[5] != 'a') {
            throw new IOException("Invalid GIF header");
        }

        int canvasWidth = readUnsignedShort(data, 6);
        int canvasHeight = readUnsignedShort(data, 8);
        if (canvasWidth < 1 || canvasHeight < 1
                || canvasWidth > MAX_FRAME_DIMENSION || canvasHeight > MAX_FRAME_DIMENSION) {
            throw new IOException("Animated cape dimensions exceed 2048 pixels");
        }

        int packed = data[10] & 0xFF;
        int offset = 13;
        if ((packed & 0x80) != 0) {
            offset = skipColorTable(data, offset, packed);
        }

        int frameCount = 0;
        boolean trailerFound = false;
        while (offset < data.length) {
            int marker = data[offset++] & 0xFF;
            if (marker == 0x00) {
                // Tolerate padding emitted by a few otherwise valid encoders.
                continue;
            }
            if (marker == 0x3B) {
                trailerFound = true;
                break;
            }
            if (marker == 0x21) {
                requireAvailable(data, offset, 1);
                offset++; // Extension label.
                offset = skipSubBlocks(data, offset);
                continue;
            }
            if (marker != 0x2C) {
                throw new IOException("Invalid GIF block marker");
            }

            requireAvailable(data, offset, 9);
            int left = readUnsignedShort(data, offset);
            int top = readUnsignedShort(data, offset + 2);
            int width = readUnsignedShort(data, offset + 4);
            int height = readUnsignedShort(data, offset + 6);
            int imagePacked = data[offset + 8] & 0xFF;
            offset += 9;
            if (width < 1 || height < 1
                    || (long) left + width > canvasWidth || (long) top + height > canvasHeight) {
                throw new IOException("GIF frame lies outside its canvas");
            }
            if ((imagePacked & 0x80) != 0) {
                offset = skipColorTable(data, offset, imagePacked);
            }
            requireAvailable(data, offset, 1);
            offset++; // LZW minimum code size.
            offset = skipSubBlocks(data, offset);

            frameCount++;
            if (frameCount > MAX_FRAMES) {
                throw new IOException("Animated cape exceeds 256 frames");
            }
        }

        if (!trailerFound || frameCount == 0) {
            throw new IOException("GIF is truncated or has no frames");
        }
        long decodedBytes = (long) canvasWidth * canvasHeight * frameCount * 4L;
        if (decodedBytes > MAX_DECODED_BYTES) {
            throw new IOException("Animated cape exceeds 64 MB when decoded");
        }
        return new GifPreflight(canvasWidth, canvasHeight, frameCount);
    }

    private static int skipColorTable(byte[] data, int offset, int packed) throws IOException {
        int entries = 1 << ((packed & 0x07) + 1);
        int bytes = Math.multiplyExact(entries, 3);
        requireAvailable(data, offset, bytes);
        return offset + bytes;
    }

    private static int skipSubBlocks(byte[] data, int offset) throws IOException {
        while (true) {
            requireAvailable(data, offset, 1);
            int length = data[offset++] & 0xFF;
            if (length == 0) {
                return offset;
            }
            requireAvailable(data, offset, length);
            offset += length;
        }
    }

    private static int readUnsignedShort(byte[] data, int offset) throws IOException {
        requireAvailable(data, offset, 2);
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static void requireAvailable(byte[] data, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IOException("Truncated GIF data");
        }
    }

    private static void closeFrameQuietly(NativeImage frame) {
        try {
            frame.close();
        } catch (RuntimeException | LinkageError error) {
            QuickSkinInfo.LOGGER.warn("Unable to release a decoded GIF frame", error);
        }
    }

    private record GifPreflight(int width, int height, int frameCount) {
    }
}
