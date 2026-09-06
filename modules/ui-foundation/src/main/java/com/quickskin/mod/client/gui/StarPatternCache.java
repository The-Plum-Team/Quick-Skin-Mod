package com.quickskin.mod.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.platform.MinecraftCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.quickskin.mod.platform.MinecraftTextureUploads;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads a pre-generated tiled star pattern for optimal rendering performance.
 * Instead of rendering 700+ tiles per frame, we render from one large pre-tiled texture.
 * The texture was pre-generated externally to eliminate runtime generation overhead.
 */
public class StarPatternCache {
    //? if <1.21.11 {
        //? if <1.21 {
    private static final ResourceLocation STAR_PATTERN_CACHE = new ResourceLocation(QuickSkinInfo.MOD_ID, "textures/gui/background/star_pattern_cache_generated.png");
        //?} else {
    private static final ResourceLocation STAR_PATTERN_CACHE = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/gui/background/star_pattern_cache_generated.png");
        //?}
    //?} else {
    private static final Identifier STAR_PATTERN_CACHE = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/gui/background/star_pattern_cache_generated.png");
    //?}
    private static final int TILE_SIZE = 55; // Match the original tile size
    private static final int CACHE_TILES_WIDTH = 64; // Pre-generated texture has 64 tiles width
    private static final int CACHE_TILES_HEIGHT = 32; // Pre-generated texture has 32 tiles height

    private static DynamicTexture cachedTexture = null;
    //? if <1.21.11 {
    private static ResourceLocation cachedTextureLocation = null;
    //?} else {
    private static Identifier cachedTextureLocation = null;
    //?}
    private static int cachedTextureWidth = 0;
    private static int cachedTextureHeight = 0;

    /**
     * Initialize by loading the pre-generated cached texture
     */
    public static void initialize() {
        if (cachedTexture != null) {
            return; // Already initialized
        }

        try {
            Minecraft mc = Minecraft.getInstance();

            //? if <1.21 {
            Resource resource = mc.getResourceManager().getResource(STAR_PATTERN_CACHE).orElseThrow();
            //?} else {
            // Try to load the pre-generated star pattern cache texture
            var resourceOptional = mc.getResourceManager().getResource(STAR_PATTERN_CACHE);
            if (resourceOptional.isEmpty()) {
                createFallbackTexture();
                return;
            }

            Resource resource = resourceOptional.get();
            //?}
            NativeImage cachedImage;
            try (InputStream stream = resource.open()) {
                cachedImage = NativeImage.read(stream);
            }

            // Store dimensions
            cachedTextureWidth = cachedImage.getWidth();
            cachedTextureHeight = cachedImage.getHeight();

            // Upload to GPU
            cachedTexture = MinecraftTextureUploads.create(() -> "quickskin_star_cache", cachedImage);
            //? if <1.21.4 {
            cachedTextureLocation = mc.getTextureManager().register("quickskin_star_cache", cachedTexture);
            //?} else if <1.21.11 {
            cachedTextureLocation = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "star_cache");
            mc.getTextureManager().register(cachedTextureLocation, cachedTexture);
            //?} else {
            cachedTextureLocation = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "star_cache");
            mc.getTextureManager().register(cachedTextureLocation, cachedTexture);
            //?}
            //? if <1.21.11 {
            cachedTexture.setFilter(true, false);
            //?}

        } catch (IOException e) {
            //? if >=1.21 {
            createFallbackTexture();
            //?}
        }
    }

    /**
     * Get the cached texture location
     */
    //? if <1.21.11 {
    public static ResourceLocation getTextureLocation() {
    //?} else {
    public static Identifier getTextureLocation() {
    //?}
        if (cachedTextureLocation == null) {
            initialize();
        }
        return cachedTextureLocation;
    }

    /**
     * Get the width of the cached texture
     */
    public static int getTextureWidth() {
        if (cachedTexture == null) {
            initialize();
        }
        return cachedTextureWidth;
    }

    /**
     * Get the height of the cached texture
     */
    public static int getTextureHeight() {
        if (cachedTexture == null) {
            initialize();
        }
        return cachedTextureHeight;
    }

    /**
     * Get the tile size used for the pattern
     */
    public static int getTileSize() {
        return TILE_SIZE;
    }

    //? if >=1.21 {
    /**
     * Create a simple fallback texture when the cached version is not available
     */
    private static void createFallbackTexture() {
        try {
            Minecraft mc = Minecraft.getInstance();

            // Create a simple 64x64 transparent texture as fallback
            int size = 64;
            NativeImage fallbackImage = new NativeImage(size, size, false);

            // Fill with transparent pixels
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    MinecraftCompat.INSTANCE.setPixel(fallbackImage, x, y, 0x00000000);
                }
            }

            cachedTextureWidth = size;
            cachedTextureHeight = size;
            cachedTexture = MinecraftTextureUploads.create(() -> "quickskin_star_cache_fallback", fallbackImage);
            //? if <1.21.4 {
            cachedTextureLocation = mc.getTextureManager().register("quickskin_star_cache_fallback", cachedTexture);
            //?} else if <1.21.11 {
            cachedTextureLocation = ResourceLocation.fromNamespaceAndPath(
                    QuickSkinInfo.MOD_ID, "star_cache_fallback");
            mc.getTextureManager().register(cachedTextureLocation, cachedTexture);
            //?} else {
            cachedTextureLocation = Identifier.fromNamespaceAndPath(
                    QuickSkinInfo.MOD_ID, "star_cache_fallback");
            mc.getTextureManager().register(cachedTextureLocation, cachedTexture);
            //?}
            //? if <1.21.11 {
            cachedTexture.setFilter(true, false);
            //?}

        } catch (Exception e) {
        }
    }

    /**
     * Re-apply linear filtering before rendering.
     * RenderType may reset texture parameters, so this ensures smooth sub-pixel scrolling.
     */
    public static void ensureLinearFiltering() {
        //? if <1.21.11 {
        if (cachedTexture != null) {
            cachedTexture.setFilter(true, false);
        }
        //?}
    }

    //?}
    /**
     * Clean up resources
     */
    public static void cleanup() {
        if (cachedTexture != null) {
            cachedTexture.close();
            cachedTexture = null;
            cachedTextureLocation = null;
        }
    }
}
