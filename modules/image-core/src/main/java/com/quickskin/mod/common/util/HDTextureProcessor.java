package com.quickskin.mod.common.util;

import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.common.data.TextureQuality;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Handles HD skin processing, legacy skin conversion, and texture scaling
 * Supports skins up to 2048x2048 (32x scale factor)
 */
public class HDTextureProcessor {

    /**
     * Process HD skin with transparency handling
     * Converts legacy skins to modern format if needed
     *
     * @param input Input stream of skin image
     * @param allowTransparency Whether to preserve transparency
     * @return Processed image bytes, or null on error
     */
    public static byte[] processHDSkin(InputStream input, boolean allowTransparency) {
        try {
            if (input == null) {
                return null;
            }
            byte[] imageBytes = input.readNBytes((int) SafeImageReader.MAX_ENCODED_BYTES + 1);
            if (imageBytes.length == 0 || imageBytes.length > SafeImageReader.MAX_ENCODED_BYTES) {
                return null;
            }
            return processHDSkin(SafeImageReader.readSkin(imageBytes), allowTransparency);
        } catch (Exception e) {
            return null;
        }
    }

    /** Processes an already bounded and decoded skin image. */
    public static byte[] processHDSkin(BufferedImage image, boolean allowTransparency) {
        try {
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                    || image.getWidth() > 2048 || image.getHeight() > 2048
                    || (long) image.getWidth() * image.getHeight() > 2048L * 2048L) {
                return null;
            }

            // Ensure image has alpha channel (TYPE_INT_ARGB = 2). Use hardware-accelerated
            // blit via Graphics2D instead of per-pixel loop.
            if (image.getType() != BufferedImage.TYPE_INT_ARGB) {
                BufferedImage argbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = argbImage.createGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
                image = argbImage;
            }

            int width = image.getWidth();
            int height = image.getHeight();

            // Check if valid resolution, resize to nearest valid if not
            SkinResolution resolution = SkinResolution.fromDimensions(width, height);
            if (resolution == null) {
                resolution = SkinResolution.findNearest(width, height);
                if (resolution == null) {
                    return null;
                }
                image = resizeToResolution(image, resolution);
            }

            // Convert legacy (64x32) to modern (64x64)
            if (resolution == SkinResolution.LEGACY) {
                image = convertLegacyToModern(image);
            }

            // Handle transparency
            if (!allowTransparency) {
                image = removeTransparency(image);
            }

            // Convert to PNG bytes
            return imageToPng(image);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resize an image to match the given SkinResolution dimensions.
     * Uses nearest-neighbor interpolation to preserve pixel art sharpness.
     */
    public static BufferedImage resizeToResolution(BufferedImage image, SkinResolution resolution) {
        int targetWidth = resolution.getWidth();
        int targetHeight = resolution.getHeight();

        if (image.getWidth() == targetWidth && image.getHeight() == targetHeight) {
            return image;
        }

        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return resized;
    }

    /**
     * Convert legacy 64x32 skin to modern 64x64 format
     * Properly copies and mirrors limbs for HD compatibility
     * Each limb texture has 6 faces laid out in a specific pattern:
     * - Top/Bottom (4x4 each at top)
     * - Right side, Front, Left side, Back (4x12 each below)
     * When converting right limbs to left limbs, we must rearrange the faces,
     * not just flip the entire texture horizontally.
     */
    public static BufferedImage convertLegacyToModern(BufferedImage legacy) {
        int width = legacy.getWidth();
        int scale = width / 64;

        // Create new 64x64 (scaled) image
        BufferedImage modern = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = modern.createGraphics();

        // Copy entire top half (64x32 scaled)
        g.drawImage(legacy, 0, 0, null);

        // Convert right leg to left leg with proper face rearrangement
        convertLimbToMirror(legacy, modern, 0, 16 * scale, 16, 48 * scale, scale);

        // Convert right arm to left arm with proper face rearrangement
        convertLimbToMirror(legacy, modern, 40, 16 * scale, 32, 48 * scale, scale);

        // Clear overlay layers if they're all black (artifact prevention)
        clearBlackOverlays(modern, scale);

        g.dispose();
        return modern;
    }

    /**
     * Detect and clear overlay layers that are entirely black
     * Some legacy skins have black pixels in overlay areas which should be transparent
     *
     * @param image The converted modern format image
     * @param scale The skin scale factor
     */
    private static void clearBlackOverlays(BufferedImage image, int scale) {
        // Check head overlay (hat layer) - (32-63, 0-15)
        if (isOverlayAllBlack(image, 32 * scale, 0, 32 * scale, 16 * scale)) {
            clearArea(image, 32 * scale, 0, 32 * scale, 16 * scale, 0x00000000);
        }
    }

    /**
     * Check if a rectangular area contains only black pixels (RGB 0,0,0)
     * Ignores alpha channel - only checks if RGB values are all zero
     *
     * @return true if all pixels in the area are black
     */
    private static boolean isOverlayAllBlack(BufferedImage image, int x, int y, int width, int height) {
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                if (x + dx < image.getWidth() && y + dy < image.getHeight()) {
                    int pixel = image.getRGB(x + dx, y + dy);

                    // Extract RGB components (ignore alpha)
                    int red = (pixel >> 16) & 0xFF;
                    int green = (pixel >> 8) & 0xFF;
                    int blue = pixel & 0xFF;

                    // If any pixel is not black, return false
                    if (red != 0 || green != 0 || blue != 0) {
                        return false;
                    }
                }
            }
        }

        return true; // All pixels are black
    }

    /**
     * Clear a rectangular area to a specific color (usually transparent)
     */
    private static void clearArea(BufferedImage image, int x, int y, int width, int height, int color) {
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                if (x + dx < image.getWidth() && y + dy < image.getHeight()) {
                    image.setRGB(x + dx, y + dy, color);
                }
            }
        }
    }

    /**
     * Convert a right limb to a left limb with proper face rearrangement
     *
     * Limb texture layout (each limb is 16x16 pixels at 1x scale):
     * - Columns 0-3:   Top (rows 0-3) and Right side (rows 4-15)
     * - Columns 4-7:   Bottom (rows 0-3) and Front (rows 4-15)
     * - Columns 8-11:  Left side (rows 4-15)
     * - Columns 12-15: Back (rows 4-15)
     *
     * When mirroring right limb to left limb:
     * - Right side ↔ Left side (swap and mirror)
     * - Front and Back stay in relative positions but are mirrored
     * - Top and Bottom are mirrored
     *
     * @param src Source image (legacy skin)
     * @param dst Destination image (modern skin)
     * @param srcX Source limb X position (at 1x scale)
     * @param srcY Source limb Y position (scaled)
     * @param dstX Destination limb X position (at 1x scale)
     * @param dstY Destination limb Y position (scaled)
     * @param scale Skin scale factor (1 for 64x64, 2 for 128x128, etc.)
     */
    private static void convertLimbToMirror(
            BufferedImage src,
            BufferedImage dst,
            int srcX, int srcY,
            int dstX, int dstY,
            int scale
    ) {
        // Scale the source and destination X coordinates
        srcX *= scale;
        dstX *= scale;

        // Face dimensions at current scale
        int faceWidth = 4 * scale;    // Each face is 4 pixels wide
        int topHeight = 4 * scale;     // Top/bottom faces are 4 pixels tall
        int sideHeight = 12 * scale;   // Side faces are 12 pixels tall

        // Copy and mirror Top face (top-left 4x4)
        // Right limb top (srcX, srcY) → Left limb top (dstX, dstY), mirrored horizontally
        copyAreaRGBA(src, dst,
                srcX, srcY, faceWidth, topHeight,
                dstX, dstY, true, false);

        // Copy and mirror Bottom face (top, columns 4-7)
        // Right limb bottom (srcX+4, srcY) → Left limb bottom (dstX+4, dstY), mirrored horizontally
        copyAreaRGBA(src, dst,
                srcX + faceWidth, srcY, faceWidth, topHeight,
                dstX + faceWidth, dstY, true, false);

        // IMPORTANT: For the side faces, we need to swap left and right

        // Right limb's RIGHT side (columns 0-3, rows 4-15) → Left limb's LEFT side (columns 8-11, rows 4-15)
        copyAreaRGBA(src, dst,
                srcX, srcY + topHeight, faceWidth, sideHeight,
                dstX + (faceWidth * 2), dstY + topHeight, true, false);

        // Right limb's FRONT (columns 4-7, rows 4-15) → Left limb's FRONT (columns 4-7, rows 4-15)
        copyAreaRGBA(src, dst,
                srcX + faceWidth, srcY + topHeight, faceWidth, sideHeight,
                dstX + faceWidth, dstY + topHeight, true, false);

        // Right limb's LEFT side (columns 8-11, rows 4-15) → Left limb's RIGHT side (columns 0-3, rows 4-15)
        copyAreaRGBA(src, dst,
                srcX + (faceWidth * 2), srcY + topHeight, faceWidth, sideHeight,
                dstX, dstY + topHeight, true, false);

        // Right limb's BACK (columns 12-15, rows 4-15) → Left limb's BACK (columns 12-15, rows 4-15)
        copyAreaRGBA(src, dst,
                srcX + (faceWidth * 3), srcY + topHeight, faceWidth, sideHeight,
                dstX + (faceWidth * 3), dstY + topHeight, true, false);
    }

    /**
     * Copy and optionally mirror an area of the image
     * Supports HD scale factors
     */
    private static void copyAreaRGBA(
            BufferedImage src,
            BufferedImage dst,
            int srcX, int srcY,
            int width, int height,
            int dstX, int dstY,
            boolean mirrorX,
            boolean mirrorY
    ) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (srcX + x < src.getWidth() && srcY + y < src.getHeight()) {
                    int pixel = src.getRGB(srcX + x, srcY + y);

                    int targetX = mirrorX ? dstX + (width - 1 - x) : dstX + x;
                    int targetY = mirrorY ? dstY + (height - 1 - y) : dstY + y;

                    if (targetX < dst.getWidth() && targetY < dst.getHeight()) {
                        dst.setRGB(targetX, targetY, pixel);
                    }
                }
            }
        }
    }

    /**
     * Remove transparency from image, replace with white
     * Preserves 3D overlay layers (they should always be transparent)
     */
    public static BufferedImage removeTransparency(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int scale = width / 64;

        BufferedImage opaque = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;

                // Check if this pixel is part of a 3D overlay layer
                // Overlay layers should always preserve transparency
                if (isOverlayLayerPixel(x / scale, y / scale)) {
                    opaque.setRGB(x, y, argb); // Keep original (with transparency)
                } else if (alpha == 0) {
                    // Make fully transparent pixels black
                    opaque.setRGB(x, y, 0xFF000000);
                } else {
                    // Make semi-transparent pixels fully opaque
                    opaque.setRGB(x, y, argb | 0xFF000000);
                }
            }
        }

        return opaque;
    }

    /**
     * Check if a pixel coordinate (at 1x scale) is part of a 3D overlay layer
     * These areas should always preserve transparency
     */
    private static boolean isOverlayLayerPixel(int x, int y) {
        // Head overlay (32-63, 0-15)
        if (x >= 32 && x < 64 && y >= 0 && y < 16) {
            return true;
        }

        // Body overlay (16-39, 32-47)
        if (x >= 16 && x < 40 && y >= 32 && y < 48) {
            return true;
        }

        // Right arm overlay (40-55, 32-47)
        if (x >= 40 && x < 56 && y >= 32 && y < 48) {
            return true;
        }

        // Left arm overlay (48-63, 48-63)
        if (x >= 48 && x < 64 && y >= 48 && y < 64) {
            return true;
        }

        // Right leg overlay (0-15, 32-47)
        if (x >= 0 && x < 16 && y >= 32 && y < 48) {
            return true;
        }

        // Left leg overlay (0-15, 48-63)
        if (x >= 0 && x < 16 && y >= 48 && y < 64) {
            return true;
        }

        return false;
    }

    /**
     * Downsample texture to target size with high quality
     */
    public static BufferedImage downsample(BufferedImage source, int targetSize) {
        if (source.getWidth() <= targetSize && source.getHeight() <= targetSize) {
            return source; // Already small enough
        }

        // Calculate target dimensions maintaining aspect ratio
        int targetWidth, targetHeight;
        if (source.getWidth() > source.getHeight()) {
            targetWidth = targetSize;
            targetHeight = (targetSize * source.getHeight()) / source.getWidth();
        } else {
            targetHeight = targetSize;
            targetWidth = (targetSize * source.getWidth()) / source.getHeight();
        }

        BufferedImage downsampled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = downsampled.createGraphics();

        // High quality downsampling
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        return downsampled;
    }

    // ### START NEW METHOD ###
    /**
     * Resizes an animation strip (vertical frames) to a new width, preserving the strip's aspect ratio.
     * This version correctly handles frame-by-frame resizing to prevent content shrinking.
     * @param source The original animation strip.
     * @param targetWidth The desired width for each frame.
     * @return A new, resized animation strip.
     */
    public static BufferedImage resizeAnimationStrip(BufferedImage source, int targetWidth) {
        int originalWidth = source.getWidth();
        if (originalWidth <= 0) {
            return source; // Return original if invalid
        }
        if (originalWidth == targetWidth) {
            return source; // No resize needed
        }

        // A single cape frame has a 2:1 aspect ratio.
        int originalFrameHeight = originalWidth / 2;
        if (originalFrameHeight <= 0 || source.getHeight() % originalFrameHeight != 0) {
            return source; // Return original if dimensions are not a valid strip
        }

        int frameCount = source.getHeight() / originalFrameHeight;
        int targetFrameHeight = targetWidth / 2;
        int targetHeight = targetFrameHeight * frameCount;

        BufferedImage resizedStrip = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resizedStrip.createGraphics();

        // Use high-quality rendering hints for downscaling
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Iterate through each frame and draw it scaled
        for (int i = 0; i < frameCount; i++) {
            // Source coordinates for the current frame
            int sx1 = 0;
            int sy1 = i * originalFrameHeight;
            int sx2 = originalWidth;
            int sy2 = sy1 + originalFrameHeight;

            // Destination coordinates for the current frame
            int dx1 = 0;
            int dy1 = i * targetFrameHeight;
            int dx2 = targetWidth;
            int dy2 = dy1 + targetFrameHeight;

            // Draw the source frame into the destination frame, scaling it in the process
            g.drawImage(source, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, null);
        }

        g.dispose();
        return resizedStrip;
    }
    // ### END NEW METHOD ###

    /**
     * Create thumbnail (64x64) for GUI lists
     */
    public static byte[] createThumbnail(BufferedImage source) {
        BufferedImage thumbnail = downsample(source, TextureQuality.THUMBNAIL.getTargetSize());
        return imageToPng(thumbnail);
    }

    /**
     * Create preview (256x256) for GUI selection screen
     */
    public static byte[] createPreview(BufferedImage source) {
        BufferedImage preview = downsample(source, TextureQuality.PREVIEW.getTargetSize());
        return imageToPng(preview);
    }

    /**
     * Normalize texture to 64x64 for vanilla rendering
     * (Phase 6 will remove this when we drop GeckoLib)
     */
    public static byte[] normalizeForVanilla(BufferedImage source) {
        BufferedImage normalized = downsample(source, TextureQuality.NORMALIZED.getTargetSize());
        return imageToPng(normalized);
    }

    /**
     * Convert BufferedImage to PNG byte array
     */
    public static byte[] imageToPng(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convert byte array back to BufferedImage
     */
    public static BufferedImage pngToImage(byte[] data) {
        try {
            return SafeImageReader.readPng(data);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Normalize cape to standard 64x32 if HD
     */
    public static BufferedImage normalizeCape(BufferedImage cape) {
        if (cape.getWidth() == 64 && cape.getHeight() == 32) {
            return cape; // Already normalized
        }

        // Downsample to 64x32
        BufferedImage normalized = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = normalized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(cape, 0, 0, 64, 32, null);
        g.dispose();

        return normalized;
    }
    /**
     * Detect image format from file header bytes
     */
    private static String detectImageFormat(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return "unknown (insufficient data)";
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.length >= 8 &&
            (bytes[0] & 0xFF) == 0x89 &&
            (bytes[1] & 0xFF) == 0x50 &&
            (bytes[2] & 0xFF) == 0x4E &&
            (bytes[3] & 0xFF) == 0x47) {
            return "PNG";
        }

        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "JPEG";
        }

        // GIF: "GIF87a" or "GIF89a"
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "GIF";
        }

        // BMP: "BM"
        if (bytes[0] == 'B' && bytes[1] == 'M') {
            return "BMP";
        }

        // WebP: "RIFF" ... "WEBP"
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' &&
            bytes.length >= 12 && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "WebP";
        }

        // TIFF: "II" (little-endian) or "MM" (big-endian)
        if ((bytes[0] == 'I' && bytes[1] == 'I') || (bytes[0] == 'M' && bytes[1] == 'M')) {
            return "TIFF";
        }

        // Return hex of first 4 bytes for unknown formats
        return String.format("unknown (header: %02X %02X %02X %02X)",
            bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF, bytes[3] & 0xFF);
    }
}
