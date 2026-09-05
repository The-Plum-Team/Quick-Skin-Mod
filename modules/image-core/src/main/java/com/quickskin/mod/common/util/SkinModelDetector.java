package com.quickskin.mod.common.util;

import com.quickskin.mod.common.data.SkinResolution;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;

/**
 * Utility for detecting skin model type (slim/Alex vs classic/Steve)
 * by analyzing arm pixel transparency
 */
public class SkinModelDetector {

    /**
     * Detect skin model from BufferedImage
     * @param image The skin image
     * @return "slim" or "classic"
     */
    public static String detectSkinModel(BufferedImage image) {
        if (image == null) {
            return "classic";
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Determine resolution and scale factor
        SkinResolution resolution = SkinResolution.fromDimensions(width, height);
        int scale = resolution != null ? resolution.getScale() : 1;

        // For legacy skins (64x32), default to classic
        if (resolution == SkinResolution.LEGACY) {
            return "classic";
        }

        // Check right arm outer layer (x=54-55 at 1x scale, y=20-32)
        // In slim skins, this column should be mostly transparent
        int rightArmX = 54 * scale;
        int rightArmEndX = 56 * scale;
        int rightArmStartY = 20 * scale;
        int rightArmEndY = 32 * scale;

        // Also check left arm outer layer (x=46-47 at 1x scale, y=52-64)
        int leftArmX = 46 * scale;
        int leftArmEndX = 48 * scale;
        int leftArmStartY = 52 * scale;
        int leftArmEndY = 64 * scale;

        int totalPixels = 0;
        int transparentPixels = 0;

        // Check right arm column
        for (int y = rightArmStartY; y < rightArmEndY && y < height; y++) {
            for (int x = rightArmX; x < rightArmEndX && x < width; x++) {
                totalPixels++;
                int argb = image.getRGB(x, y);

                // Extract color components
                int alpha = (argb >> 24) & 0xFF;
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;

                // Consider pixel as "empty" for slim detection if:
                // 1. Alpha is very low (transparent), OR
                // 2. It's pure black (RGB 0,0,0) - often used as a mask in slim skins
                if (alpha < 10 || (red == 0 && green == 0 && blue == 0)) {
                    transparentPixels++;
                }
            }
        }

        // Check left arm column
        for (int y = leftArmStartY; y < leftArmEndY && y < height; y++) {
            for (int x = leftArmX; x < leftArmEndX && x < width; x++) {
                totalPixels++;
                int argb = image.getRGB(x, y);

                // Extract color components
                int alpha = (argb >> 24) & 0xFF;
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;

                // Consider pixel as "empty" for slim detection if:
                // 1. Alpha is very low (transparent), OR
                // 2. It's pure black (RGB 0,0,0) - often used as a mask in slim skins
                if (alpha < 10 || (red == 0 && green == 0 && blue == 0)) {
                    transparentPixels++;
                }
            }
        }

        // If more than 50% of arm pixels are transparent, it's a slim model
        if (totalPixels > 0) {
            float transparentRatio = (float) transparentPixels / totalPixels;
            boolean isSlim = transparentRatio > 0.5f;

            return isSlim ? "slim" : "classic";
        }

        // Default to classic if detection fails
        return "classic";
    }

    /**
     * Detect skin model from file
     */
    public static String detectSkinModel(File file) {
        try {
            BufferedImage image = file == null ? null : SafeImageReader.readSkin(file.toPath());
            return detectSkinModel(image);
        } catch (Exception e) {
            return "classic";
        }
    }

    /**
     * Detect skin model from byte array
     */
    public static String detectSkinModel(byte[] data) {
        try {
            BufferedImage image = SafeImageReader.readSkin(data);
            return detectSkinModel(image);
        } catch (Exception e) {
            return "classic";
        }
    }

    /**
     * Detect skin model from InputStream
     */
    public static String detectSkinModel(InputStream input) {
        try {
            byte[] encoded = BoundedFileReader.readBytes(
                    input, (int) SafeImageReader.MAX_ENCODED_BYTES);
            BufferedImage image = SafeImageReader.readSkin(encoded);
            return detectSkinModel(image);
        } catch (Exception e) {
            return "classic";
        }
    }
}
