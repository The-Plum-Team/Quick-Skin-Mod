package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.common.util.GifDecoder;
import com.quickskin.mod.common.util.ScaledGifDecoder;
import com.quickskin.mod.common.util.StbGifLoader;
import com.quickskin.mod.common.util.UserFacingIOException;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Converts the selected native-image API into the feature-owned ARGB decoding contract.
 *
 * <p>A GIF the native loader refuses only for its size (its canvas, its frames or its decoded
 * pixels) is decoded again by {@link ScaledGifDecoder}, frame by frame and shrunk, so the cape
 * editor can open it; every other GIF keeps the native loader's exact pixels.</p>
 */
public final class MinecraftGifDecoder implements GifDecoder {
    public static final MinecraftGifDecoder INSTANCE = new MinecraftGifDecoder();
    private static final long MAX_ATLAS_PIXELS = 64L * 1024L * 1024L / 4L;

    private MinecraftGifDecoder() {
    }

    /** The native loader's refusals that only mean "too big": the scaled decoder takes these. */
    private static final Set<String> SIZE_REFUSALS = Set.of(
            "quickskin.cape.error.gif_width",
            "quickskin.cape.error.gif_frames",
            "quickskin.cape.error.gif_decoded",
            "quickskin.cape.error.dimensions",
            "quickskin.cape.error.too_big");

    @Override
    public DecodedGif decode(InputStream input) throws IOException {
        byte[] data = input.readNBytes(ScaledGifDecoder.MAX_ENCODED_BYTES + 1);
        try {
            return decodeNative(new ByteArrayInputStream(data));
        } catch (UserFacingIOException refused) {
            if (!SIZE_REFUSALS.contains(refused.userMessage().translationKey())) {
                throw refused;
            }
            return ScaledGifDecoder.INSTANCE.decode(new ByteArrayInputStream(data));
        }
    }

    private DecodedGif decodeNative(InputStream input) throws IOException {
        StbGifLoader.GifLoadResult gif = StbGifLoader.loadGif(input);
        if (gif == null) throw new IOException("Invalid GIF image");
        try (gif) {
            if (gif.frames() == null || gif.frames().length == 0) {
                throw new IOException("Invalid GIF image");
            }
            int width = gif.frameWidth();
            int height = gif.frameHeight();
            int frameCount = gif.frames().length;
            if (width > 4096 || height > 4096) {
                throw new com.quickskin.mod.common.util.UserFacingIOException(
                        "Animated cape dimensions exceed 4096 pixels",
                        "quickskin.cape.error.dimensions", width + "×" + height, 4096);
            }
            if (width < 1 || height < 1 || (long) width * height * frameCount > MAX_ATLAS_PIXELS) {
                throw new com.quickskin.mod.common.util.UserFacingIOException(
                        "Animated cape dimensions exceed the decoded-image limit",
                        "quickskin.cape.error.too_big");
            }
            BufferedImage atlas = new BufferedImage(width, Math.multiplyExact(height, frameCount),
                    BufferedImage.TYPE_INT_ARGB);
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                NativeImage frame = gif.frames()[frameIndex];
                int[] row = new int[width];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int abgr = MinecraftCompat.INSTANCE.getPixel(frame, x, y);
                        int a = (abgr >>> 24) & 0xFF;
                        int b = (abgr >>> 16) & 0xFF;
                        int g = (abgr >>> 8) & 0xFF;
                        int r = abgr & 0xFF;
                        row[x] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                    atlas.setRGB(0, frameIndex * height + y, width, 1, row, 0, width);
                }
            }
            return new DecodedGif(atlas, frameCount, gif.metadata());
        } catch (ArithmeticException exception) {
            throw new IOException("Animated cape dimensions overflow", exception);
        }
    }
}
