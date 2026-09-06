package com.quickskin.mod.common.util;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import net.minecraft.client.Minecraft;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.server.packs.resources.Resource;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for detecting if textures contain transparency (alpha channel)
 */
@Environment(EnvType.CLIENT)
public class TextureAlphaDetector {
    private static final int MAX_CACHE_ENTRIES = 4096;

    // Cache to avoid repeatedly checking the same textures
    //? if <1.21.11 {
    private static final Map<ResourceLocation, Boolean> transparencyCache = new ConcurrentHashMap<>();
    //?} else {
    private static final Map<Identifier, Boolean> transparencyCache = new ConcurrentHashMap<>();
    //?}

    // Track textures currently being analyzed to avoid duplicate work
    //? if <1.21.11 {
    private static final Set<ResourceLocation> pendingAnalysis = ConcurrentHashMap.newKeySet();
    //?} else {
    private static final Set<Identifier> pendingAnalysis = ConcurrentHashMap.newKeySet();
    //?}

    /**
     * Check if a texture contains any transparent pixels
     * This method returns immediately and does NOT perform I/O on the calling thread.
     *
     * @param textureLocation The resource location of the texture
     * @return true if the texture has any pixels with alpha < 255, OR if the analysis is not yet complete (defaults to TRANSLUCENT for safety)
     */
    //? if <1.21.11 {
    public static boolean hasTransparency(ResourceLocation textureLocation) {
    //?} else {
    public static boolean hasTransparency(Identifier textureLocation) {
    //?}
        if (textureLocation == null) {
            return false;
        }

        // Check cache first
        Boolean cached = transparencyCache.get(textureLocation);
        if (cached != null) {
            return cached;
        }

        // Default to TRANSLUCENT (true) if not yet analyzed
        // This is safer - it may be slightly less performant but won't cause visual glitches
        return true;
    }

    /**
     * Asynchronously analyze a texture for transparency.
     * This should be called when a texture is first loaded/applied.
     * The analysis happens on a background thread, and the result is cached for future use.
     *
     * @param textureLocation The resource location of the texture to analyze
     */
    //? if <1.21.11 {
    public static void analyzeTextureAsync(ResourceLocation textureLocation) {
    //?} else {
    public static void analyzeTextureAsync(Identifier textureLocation) {
    //?}
        if (textureLocation == null) {
            return;
        }

        // Skip if already cached
        if (transparencyCache.containsKey(textureLocation)) {
            return;
        }

        // Skip if already being analyzed
        if (pendingAnalysis.size() >= MAX_CACHE_ENTRIES
                || !pendingAnalysis.add(textureLocation)) {
            return;
        }

        // Perform the analysis on a background thread
        ClientIoExecutor.runAsync(() -> {
            try {
                boolean hasAlpha = detectTransparency(textureLocation);

                // Update cache on the main thread to ensure thread safety with rendering
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    putBounded(textureLocation, hasAlpha);
                });
            } catch (Exception e) {
                // On error, cache as transparent (safe default)
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> putBounded(textureLocation, true));
            } finally {
                // Remove from pending set
                pendingAnalysis.remove(textureLocation);
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                pendingAnalysis.remove(textureLocation);
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null) {
                    minecraft.execute(() -> putBounded(textureLocation, true));
                }
                QuickSkinInfo.LOGGER.debug("Unable to schedule texture alpha analysis for {}",
                        textureLocation, error);
            }
        });
    }

    /**
     * Actually detect if the texture has transparency by loading and examining it
     */
    //? if <1.21.11 {
    private static boolean detectTransparency(ResourceLocation textureLocation) {
    //?} else {
    private static boolean detectTransparency(Identifier textureLocation) {
    //?}
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getResourceManager() == null) {
                return false;
            }

            // Try to get the resource
            Resource resource = mc.getResourceManager().getResource(textureLocation).orElse(null);
            if (resource == null) {
                return false;
            }

            // Load the image
            try (InputStream inputStream = resource.open()) {
                byte[] encoded = inputStream.readNBytes((int) SafeImageReader.MAX_ENCODED_BYTES + 1);
                if (encoded.length > SafeImageReader.MAX_ENCODED_BYTES) return false;
                BufferedImage image = SafeImageReader.readPng(encoded);

                return hasTransparentPixels(image);

            }
        } catch (IOException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if a BufferedImage contains any transparent pixels
     */
    public static boolean hasTransparentPixels(BufferedImage image) {
        if (image == null) return false;
        // If the image doesn't have an alpha channel, it's not transparent
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Sample pixels to check for transparency for performance
        int sampleRate = Math.max(1, Math.min(width, height) / 32); // Sample every Nth pixel

        for (int y = 0; y < height; y += sampleRate) {
            for (int x = 0; x < width; x += sampleRate) {
                int pixel = image.getRGB(x, y);
                int alpha = (pixel >> 24) & 0xFF;

                if (alpha < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Directly cache a transparency result for a texture.
     * Used by LocalAssetManager when registering DynamicTextures that aren't
     * available through the resource manager.
     */
    //? if <1.21.11 {
    public static void cacheTransparencyResult(ResourceLocation textureLocation, boolean hasTransparency) {
    //?} else {
    public static void cacheTransparencyResult(Identifier textureLocation, boolean hasTransparency) {
    //?}
        if (textureLocation != null) {
            putBounded(textureLocation, hasTransparency);
        }
    }

    //? if <1.21.11 {
    private static synchronized void putBounded(
            ResourceLocation textureLocation, boolean hasTransparency) {
    //?} else {
    private static synchronized void putBounded(
            Identifier textureLocation, boolean hasTransparency) {
    //?}
        if (!transparencyCache.containsKey(textureLocation)
                && transparencyCache.size() >= MAX_CACHE_ENTRIES) {
            var eldest = transparencyCache.keySet().iterator();
            if (eldest.hasNext()) transparencyCache.remove(eldest.next());
        }
        transparencyCache.put(textureLocation, hasTransparency);
    }

    /**
     * Clear the transparency cache (useful for resource pack reloads)
     */
    public static void clearCache() {
        transparencyCache.clear();
        pendingAnalysis.clear();
    }
}
