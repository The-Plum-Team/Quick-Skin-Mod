package com.quickskin.mod.client.compat;

//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Integration with the Ears mod using reflection.
 * Parses Ears features (ears, tails, wings, snouts, etc.) from skin images
 * loaded by QuickSkin, making them available to Ears' rendering pipeline.
 *
 * This allows the code to compile without Ears as a dependency,
 * and gracefully handles when the mod is not installed at runtime.
 */
public class EarsCompatIntegration {
    private static boolean MOD_AVAILABLE;
    //? if <1.21.11 {
    private static final ConcurrentHashMap<ResourceLocation, Object> featuresCache = new ConcurrentHashMap<>();
    //?} else {
    private static final ConcurrentHashMap<Identifier, Object> featuresCache = new ConcurrentHashMap<>();
    //?}

    // Cached reflection handles
    private static Constructor<?> rawEarsImageCtor;
    private static Method preprocessSkinMethod;
    private static Method detectMethod;
    private static Class<?> pngLoaderClass;
    private static Class<?> earsImageClass;
    private static Object earsFeaturesDisabled;
    private static Field earsFeaturesStorageInstance;
    private static Method storagePutMethod;

    static {
        try {
            // Detect Ears classes
            Class<?> rawEarsImageClass = Class.forName("com.unascribed.ears.common.RawEarsImage");
            Class<?> writableEarsImageClass = Class.forName("com.unascribed.ears.common.WritableEarsImage");
            earsImageClass = Class.forName("com.unascribed.ears.common.EarsImage");
            Class<?> earsCommonClass = Class.forName("com.unascribed.ears.common.EarsCommon");
            Class<?> earsFeaturesParserClass = Class.forName("com.unascribed.ears.common.EarsFeaturesParser");
            Class<?> earsFeaturesClass = Class.forName("com.unascribed.ears.api.features.EarsFeatures");
            Class<?> alfalfaDataClass = Class.forName("com.unascribed.ears.api.features.AlfalfaData");

            // RawEarsImage(int[], int, int, boolean)
            rawEarsImageCtor = rawEarsImageClass.getConstructor(int[].class, int.class, int.class, boolean.class);

            // EarsCommon.preprocessSkin(WritableEarsImage) -> AlfalfaData
            preprocessSkinMethod = earsCommonClass.getMethod("preprocessSkin", writableEarsImageClass);

            // EarsFeaturesParser.PNGLoader interface
            pngLoaderClass = Class.forName("com.unascribed.ears.common.EarsFeaturesParser$PNGLoader");

            // EarsFeaturesParser.detect(EarsImage, AlfalfaData, PNGLoader) -> EarsFeatures
            detectMethod = earsFeaturesParserClass.getMethod("detect", earsImageClass, alfalfaDataClass, pngLoaderClass);

            // EarsFeatures.DISABLED
            Field disabledField = earsFeaturesClass.getField("DISABLED");
            earsFeaturesDisabled = disabledField.get(null);

            // EarsFeaturesStorage.INSTANCE (SimpleEarsFeatureStorage)
            try {
                Class<?> storageClass = Class.forName("com.unascribed.ears.common.EarsFeaturesStorage");
                earsFeaturesStorageInstance = storageClass.getField("INSTANCE");

                // SimpleEarsFeatureStorage.put(String, UUID, EarsFeatures)
                Object storageObj = earsFeaturesStorageInstance.get(null);
                storagePutMethod = storageObj.getClass().getMethod("put", String.class, UUID.class, earsFeaturesClass);
            } catch (Exception e) {
                // Storage API may not be available in all Ears versions
                earsFeaturesStorageInstance = null;
                storagePutMethod = null;
            }

            MOD_AVAILABLE = true;
        } catch (Exception e) {
            MOD_AVAILABLE = false;
        }
    }

    public static boolean isAvailable() {
        return MOD_AVAILABLE;
    }

    /**
     * Parse Ears features from a skin image and store for later retrieval.
     * Must be called with the ORIGINAL unprocessed image (preserving alpha channel for Alfalfa data).
     *
     * @param skinLocation The Identifier where the skin texture is registered
     * @param originalImage The original unprocessed skin image
     */
    //? if <1.21.11 {
    public static void parseAndStoreFeatures(ResourceLocation skinLocation, BufferedImage originalImage) {
    //?} else {
    public static void parseAndStoreFeatures(Identifier skinLocation, BufferedImage originalImage) {
    //?}
        if (!MOD_AVAILABLE || skinLocation == null || originalImage == null) {
            return;
        }

        // Ears only works with 64x64 skins
        if (originalImage.getWidth() != 64 || originalImage.getHeight() != 64) {
            return;
        }

        try {
            // Extract ARGB pixel data from BufferedImage
            int width = originalImage.getWidth();
            int height = originalImage.getHeight();
            int[] pixels = originalImage.getRGB(0, 0, width, height, null, 0, width);

            // Create RawEarsImage via reflection
            // swapRedBlue=false because BufferedImage.getRGB() returns ARGB which is what Ears expects
            Object rawImage = rawEarsImageCtor.newInstance(pixels, width, height, false);

            // Call EarsCommon.preprocessSkin(rawImage) to get AlfalfaData
            Object alfalfaData = preprocessSkinMethod.invoke(null, rawImage);

            // Create a PNGLoader proxy that converts PNG byte[] to EarsImage via RawEarsImage
            Object pngLoader = Proxy.newProxyInstance(
                    pngLoaderClass.getClassLoader(),
                    new Class<?>[]{pngLoaderClass},
                    (InvocationHandler) (proxy, method, args) -> {
                        if ("load".equals(method.getName()) && args != null && args.length == 1) {
                            byte[] pngData = (byte[]) args[0];
                            BufferedImage pngImage = com.quickskin.mod.common.util.SafeImageReader
                                    .readPng(pngData);
                            if (pngImage == null) {
                                throw new java.io.IOException("Failed to decode PNG data");
                            }
                            int w = pngImage.getWidth();
                            int h = pngImage.getHeight();
                            int[] px = pngImage.getRGB(0, 0, w, h, null, 0, w);
                            return rawEarsImageCtor.newInstance(px, w, h, false);
                        }
                        return null;
                    }
            );

            // Call EarsFeaturesParser.detect(rawImage, alfalfa, pngLoader)
            Object features = detectMethod.invoke(null, rawImage, alfalfaData, pngLoader);

            // Only store if features are not DISABLED
            if (features != null && !features.equals(earsFeaturesDisabled)) {
                featuresCache.put(skinLocation, features);
            } else {
                // Remove any stale entry
                featuresCache.remove(skinLocation);
            }
        } catch (Exception e) {
        }
    }

    /**
     * Get stored Ears features for a skin Identifier.
     *
     * @param skinLocation The Identifier of the skin texture
     * @return The EarsFeatures object, or null if none stored
     */
    //? if <1.21.11 {
    public static Object getFeatures(ResourceLocation skinLocation) {
    //?} else {
    public static Object getFeatures(Identifier skinLocation) {
    //?}
        if (!MOD_AVAILABLE || skinLocation == null) {
            return null;
        }
        return featuresCache.get(skinLocation);
    }

    /**
     * Check if a return value from Ears' getEarsFeatures() is the DISABLED constant.
     */
    public static boolean isDisabledResult(Object result) {
        if (!MOD_AVAILABLE) {
            return false;
        }
        return result == null || result.equals(earsFeaturesDisabled);
    }

    /**
     * Associate stored features with a player in Ears' feature storage.
     * This populates Ears' public API so other mods can query features.
     */
    //? if <1.21.11 {
    public static void associateWithPlayer(ResourceLocation skinLocation, UUID playerId, String username) {
    //?} else {
    public static void associateWithPlayer(Identifier skinLocation, UUID playerId, String username) {
    //?}
        if (!MOD_AVAILABLE || earsFeaturesStorageInstance == null || storagePutMethod == null) {
            return;
        }

        Object features = featuresCache.get(skinLocation);
        if (features == null) {
            return;
        }

        try {
            Object storage = earsFeaturesStorageInstance.get(null);
            storagePutMethod.invoke(storage, username != null ? username : "", playerId, features);
        } catch (Exception e) {
        }
    }

    /**
     * Clear features for a specific skin location.
     */
    //? if <1.21.11 {
    public static void clearFeatures(ResourceLocation skinLocation) {
    //?} else {
    public static void clearFeatures(Identifier skinLocation) {
    //?}
        if (skinLocation != null) {
            featuresCache.remove(skinLocation);
        }
    }

    /**
     * Clear all stored features.
     */
    public static void clearAllFeatures() {
        featuresCache.clear();
    }

    /**
     * Get the skin Identifier from a player object using reflection.
     * Used by the mixin to handle different mapping schemes.
     * In 1.21.1, uses peer.getSkin().texture().
     */
    //? if <1.21.11 {
    public static ResourceLocation getSkinLocationFromPlayer(Object player) {
    //?} else {
    public static Identifier getSkinLocationFromPlayer(Object player) {
    //?}
        if (player == null) {
            return null;
        }
        try {
            //? if <1.21 {
            Method getSkinTexture = player.getClass().getMethod("getSkinTexture");
            return (ResourceLocation) getSkinTexture.invoke(player);
            //?} else if <1.21.11 {
            Method getSkin = player.getClass().getMethod("getSkin");
            Object playerSkin = getSkin.invoke(player);
            if (playerSkin != null) {
                Method texture = playerSkin.getClass().getMethod("texture");
                return (ResourceLocation) texture.invoke(playerSkin);
            }
            //?} else {
            // 1.21.1 API: getSkin() returns PlayerSkin, then .texture() returns Identifier
            Method getSkin = player.getClass().getMethod("getSkin");
            Object playerSkin = getSkin.invoke(player);
            if (playerSkin != null) {
                Method texture = playerSkin.getClass().getMethod("texture");
                return (Identifier) texture.invoke(playerSkin);
            }
            //?}
        } catch (Exception e) {
            // Ignore
        }
        try {
            //? if <1.21 {
            Method getSkinTextureLocation = player.getClass().getMethod("getSkinTextureLocation");
            return (ResourceLocation) getSkinTextureLocation.invoke(player);
            //?} else if <1.21.11 {
            Method getSkinTexture = player.getClass().getMethod("getSkinTexture");
            return (ResourceLocation) getSkinTexture.invoke(player);
            //?} else {
            // Fallback: try Fabric/Yarn mapped name
            Method getSkinTexture = player.getClass().getMethod("getSkinTexture");
            return (Identifier) getSkinTexture.invoke(player);
            //?}
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
}
