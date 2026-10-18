package com.quickskin.mod.common.util;

import com.quickskin.mod.common.data.AnimationMetadata;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bounded animated-image decoding without native Minecraft image types.
 * The caller owns the input stream. Implementations release native allocations before returning
 * an owned ARGB atlas and its unchanged frame timings, under the 64 MB decoded-image limit.
 */
@FunctionalInterface
public interface GifDecoder {
    DecodedGif decode(InputStream input) throws IOException;

    /**
     * @param resampled whether the frames are smaller than the GIF's canvas, so the atlas is not
     *                  the file's own pixels
     */
    record DecodedGif(BufferedImage atlas, int frameCount, AnimationMetadata metadata, boolean resampled) {
        public DecodedGif(BufferedImage atlas, int frameCount, AnimationMetadata metadata) {
            this(atlas, frameCount, metadata, false);
        }
    }
}
