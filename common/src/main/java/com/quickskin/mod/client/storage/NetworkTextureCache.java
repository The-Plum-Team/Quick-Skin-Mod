package com.quickskin.mod.client.storage;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.ContentId;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.common.util.CapeElytraSilhouette;
import com.quickskin.mod.common.util.PngAnimationIdentity;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.TextureRequestCoordinator;
import com.quickskin.mod.networking.TextureTransferLimits;
//? if >=1.21 {
import com.quickskin.mod.platform.PlatformHelper;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if <26.2 {
import com.quickskin.mod.platform.PlatformHelper;
//?}

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
//? if <1.21.11 {
import java.nio.file.Files;
import java.nio.file.Path;
//?}
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for textures received from the server over the network
 * Stores textures in memory only - they won't appear in the local skin list
 */
@Environment(EnvType.CLIENT)
public class NetworkTextureCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkTextureCache.class);
    private static NetworkTextureCache instance;

    // Store raw texture bytes in memory (ORIGINAL unprocessed data)
    private final Map<TextureKey, byte[]> originalTextureData = new ConcurrentHashMap<>();

    // Store presentation bytes (skin transparency policy and the structural cape/Elytra cutout).
    private final Map<TextureKey, byte[]> textureDataCache = new ConcurrentHashMap<>();

    // Fully decoded on a bounded worker; ownership transfers to DynamicTexture on first use.
    private final Map<TextureKey, NativeImage> preparedNativeImages = new ConcurrentHashMap<>();
    private final Map<TextureKey, BufferedImage> preparedEarsImages = new ConcurrentHashMap<>();
    private final Map<TextureKey, Boolean> preparedTransparency = new ConcurrentHashMap<>();

    // Store registered ResourceLocations
    //? if <1.21.11 {
    private final Map<TextureKey, ResourceLocation> textureRegistry = new ConcurrentHashMap<>();
    //?} else {
    private final Map<TextureKey, Identifier> textureRegistry = new ConcurrentHashMap<>();
    //?}

    private final LinkedHashMap<TextureKey, Boolean> accessOrder =
            new LinkedHashMap<>(16, 0.75f, true);
    private final ClientTextureWorkingSet<TextureKey> workingSet =
            new ClientTextureWorkingSet<>(
                    TextureTransferLimits.MAX_CLIENT_CACHE_ENTRIES, 40L);
    private long cachedBytes;
    private long cachedPixels;
    private long pendingPreparedBytes;
    private long pendingPreparedPixels;
    private long generation;
    //? if <1.21.11 {
    private final Map<TextureKey, Path> tempFileCache = new ConcurrentHashMap<>();
    //?}

    private NetworkTextureCache() {}

    public static NetworkTextureCache getInstance() {
        if (instance == null) {
            instance = new NetworkTextureCache();
        }
        return instance;
    }

    private PreparedTexture prepareTexture(
            String hash, @Nullable String textureType, byte[] textureData,
            long reservedPixels, int reservedBytes) {
        if (!NetworkSecurity.isValidContentId(hash)
                || !NetworkSecurity.isValidTextureType(textureType)
                || !ContentId.matches(hash, textureData)
                || NetworkSecurity.getTexturePixelCount(textureData, textureType) < 1) {
            LOGGER.warn("Rejected invalid network texture (id={}, type={}, bytes={})",
                    hash, textureType, textureData == null ? -1 : textureData.length);
            return null;
        }

        byte[] originalCopy = Arrays.copyOf(textureData, textureData.length);
        BufferedImage decoded;
        try {
            decoded = SafeImageReader.readPng(originalCopy);
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Unable to decode network texture {}", hash, error);
            return null;
        }

        BufferedImage originalDecoded = decoded;
        BufferedImage earsSource = "skin".equals(textureType) ? decoded : null;
        boolean shouldRemoveTransparency = "skin".equals(textureType)
                && com.quickskin.mod.config.ClientConfig.getInstance()
                        .shouldDisableSkinTransparency();
        byte[] processedData = originalCopy;
        if (shouldRemoveTransparency || "cape".equals(textureType)) {
            try {
                if (shouldRemoveTransparency) {
                    decoded = com.quickskin.mod.common.util.HDTextureProcessor
                            .removeTransparency(decoded);
                } else {
                    int frameHeight = decoded.getWidth() / 2;
                    int frameCount = frameHeight > 0 && decoded.getHeight() % frameHeight == 0
                            ? decoded.getHeight() / frameHeight : 0;
                    decoded = CapeElytraSilhouette.maskedCopy(decoded, frameCount);
                }
                if (decoded != originalDecoded) {
                    processedData = com.quickskin.mod.common.util.HDTextureProcessor.imageToPng(decoded);
                    if (processedData != null && "cape".equals(textureType)) {
                        String animationIdentity = PngAnimationIdentity.extract(originalCopy);
                        if (animationIdentity != null) {
                            processedData = PngAnimationIdentity.attach(
                                    processedData, animationIdentity);
                        }
                    }
                }
                if (processedData == null) {
                    LOGGER.warn("Presentation processing produced invalid PNG data for network texture {}", hash);
                    return null;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Unable to process network texture presentation for {}", hash, e);
                return null;
            }
        }

        byte[] processedCopy = processedData == originalCopy
                ? Arrays.copyOf(originalCopy, originalCopy.length)
                : processedData;
        NativeImage preparedNativeImage;
        try {
            preparedNativeImage = NativeImage.read(new ByteArrayInputStream(processedCopy));
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Unable to prepare native network texture {}", hash, error);
            return null;
        }
        BufferedImage earsImage = "skin".equals(textureType)
                && com.quickskin.mod.client.compat.EarsCompatIntegration.isAvailable()
                ? earsSource : null;
        boolean hasTransparency =
                com.quickskin.mod.common.util.TextureAlphaDetector.hasTransparentPixels(decoded);
        return new PreparedTexture(
                originalCopy, processedCopy,
                NetworkSecurity.getTexturePixelCount(originalCopy, textureType),
                preparedNativeImage, earsImage, hasTransparency,
                this, reservedPixels, reservedBytes);
    }

    private boolean commitPreparedTexture(
            String hash, String textureType, PreparedTexture prepared) {
        TextureKey key = new TextureKey(hash, textureType);
        if (textureDataCache.containsKey(key)) {
            prepared.discard();
            return true;
        }

        byte[] existingOriginal = originalTextureData.putIfAbsent(key, prepared.original());
        if (existingOriginal != null && !Arrays.equals(existingOriginal, prepared.original())) {
            prepared.discard();
            return false;
        }
        NativeImage nativeImage = prepared.takeNativeImage();
        if (nativeImage == null) {
            prepared.releaseLease();
            return false;
        }
        if (existingOriginal == null) {
            cachedBytes += prepared.original().length;
            cachedPixels += prepared.pixelCount();
        }
        textureDataCache.put(key, prepared.processed());
        NativeImage oldPreparedImage = preparedNativeImages.put(key, nativeImage);
        if (oldPreparedImage != null) oldPreparedImage.close();
        BufferedImage earsImage = prepared.takeEarsImage();
        if (earsImage == null) preparedEarsImages.remove(key);
        else preparedEarsImages.put(key, earsImage);
        preparedTransparency.put(key, prepared.hasTransparency());
        cachedBytes += prepared.processed().length;
        accessOrder.put(key, Boolean.TRUE);
        evictToLimits();
        prepared.releaseLease();
        return textureDataCache.containsKey(key);
    }

    /** Opaque result of bounded image work. Its arrays never escape this cache. */
    public static final class PreparedTexture {
        private final byte[] original;
        private final byte[] processed;
        private final long pixelCount;
        private NativeImage nativeImage;
        private BufferedImage earsImage;
        private final boolean hasTransparency;
        private final NetworkTextureCache owner;
        private final long reservedPixels;
        private final int reservedBytes;
        private boolean leaseReleased;

        private PreparedTexture(
                byte[] original, byte[] processed, long pixelCount,
                NativeImage nativeImage, @Nullable BufferedImage earsImage,
                boolean hasTransparency,
                NetworkTextureCache owner, long reservedPixels, int reservedBytes) {
            this.original = original;
            this.processed = processed;
            this.pixelCount = pixelCount;
            this.nativeImage = nativeImage;
            this.earsImage = earsImage;
            this.hasTransparency = hasTransparency;
            this.owner = owner;
            this.reservedPixels = reservedPixels;
            this.reservedBytes = reservedBytes;
        }

        private byte[] original() {
            return original;
        }

        private byte[] processed() {
            return processed;
        }

        private long pixelCount() {
            return pixelCount;
        }

        private boolean hasTransparency() {
            return hasTransparency;
        }

        @Nullable
        private synchronized NativeImage takeNativeImage() {
            NativeImage result = nativeImage;
            nativeImage = null;
            return result;
        }

        @Nullable
        private synchronized BufferedImage takeEarsImage() {
            BufferedImage result = earsImage;
            earsImage = null;
            return result;
        }

        private void discard() {
            synchronized (this) {
                if (nativeImage != null) {
                    nativeImage.close();
                    nativeImage = null;
                }
                earsImage = null;
            }
            releaseLease();
        }

        private void releaseLease() {
            synchronized (this) {
                if (leaseReleased) return;
                leaseReleased = true;
            }
            owner.releasePreparedLease(reservedPixels, reservedBytes);
        }
    }

    /**
     * Get texture data for a hash
     * @param hash The texture hash
     * @return The texture bytes, or null if not found
     */
    public byte @Nullable [] getTextureData(String hash, String textureType) {
        if (!NetworkSecurity.isValidContentId(hash)
                || !NetworkSecurity.isValidTextureType(textureType)) return null;
        TextureKey key = new TextureKey(hash, textureType);
        byte[] data = textureDataCache.get(key);
        if (data != null) {
            synchronized (this) {
                if (textureDataCache.get(key) == data) accessOrder.put(key, Boolean.TRUE);
            }
        }
        return data == null ? null : Arrays.copyOf(data, data.length);
    }

    /**
     * Get or create a Identifier for a network texture
     * Registers the texture with Minecraft's texture manager if not already registered
     * @param hash The texture hash
     * @return The Identifier, or null if texture not found
     */
    @Nullable
    //? if <1.21.11 {
    public synchronized ResourceLocation getTextureLocation(String hash, String textureType) {
    //?} else {
    public synchronized Identifier getTextureLocation(String hash, String textureType) {
    //?}
        if (!NetworkSecurity.isValidContentId(hash)
                || !NetworkSecurity.isValidTextureType(textureType)) return null;
        TextureKey key = new TextureKey(hash, textureType);
        if (textureDataCache.containsKey(key)) accessOrder.put(key, Boolean.TRUE);
        // Check if already registered
        if (textureRegistry.containsKey(key)) {
            return textureRegistry.get(key);
        }

        // Get texture data
        byte[] textureData = textureDataCache.get(key);
        if (textureData == null) {
            return null;
        }

        // Load and register texture
        NativeImage nativeImage = null;
        BufferedImage earsImage = null;
        Boolean hasTransparency = null;
        DynamicTexture dynamicTexture = null;
        //? if <1.21.11 {
        ResourceLocation location = null;
        //?} else {
        Identifier location = null;
        //?}
        boolean registered = false;
        boolean committed = false;
        try {
            nativeImage = preparedNativeImages.remove(key);
            earsImage = preparedEarsImages.remove(key);
            hasTransparency = preparedTransparency.remove(key);
            if (nativeImage == null) {
                LOGGER.warn("Network texture {} had no prepared native image; requesting it again", hash);
                removeTexture(key);
                return null;
            }

            // Create dynamic texture
            //? if <1.21.6 {
            dynamicTexture = new DynamicTexture(nativeImage);
            //?} else {
            dynamicTexture = new DynamicTexture(() -> "quickskin_network_" + hash, nativeImage);
            //?}

            // Register with texture manager
            //? if <1.21.11 {
                //? if <1.21 {
            location = new ResourceLocation(
                //?} else {
            location = ResourceLocation.fromNamespaceAndPath(
                //?}
            //?} else {
            location = Identifier.fromNamespaceAndPath(
            //?}
                    QuickSkin.MOD_ID,
                    "network/" + textureType + "/" + hash
            );

            Minecraft.getInstance().getTextureManager().register(location, dynamicTexture);
            registered = true;

            // Parse Ears features from original unprocessed data (preserving alpha for Alfalfa)
            if ("skin".equals(textureType) && com.quickskin.mod.client.compat.EarsCompatIntegration.isAvailable()) {
                if (earsImage != null) {
                    try {
                        com.quickskin.mod.client.compat.EarsCompatIntegration
                                .parseAndStoreFeatures(location, earsImage);
                    } catch (RuntimeException e) {
                        LOGGER.debug("Unable to parse Ears data for network texture {}", hash, e);
                    }
                }
            }

            // Cache the location
            textureRegistry.put(key, location);
            if (hasTransparency != null) {
                com.quickskin.mod.common.util.TextureAlphaDetector
                        .cacheTransparencyResult(location, hasTransparency);
            }
            committed = true;
            return location;

        } catch (Exception e) {
            LOGGER.warn("Unable to register network texture {}", hash, e);
            removeTexture(key);
            return null;
        } finally {
            if (!committed) {
                if (registered && location != null) {
                    try {
                        Minecraft.getInstance().getTextureManager().release(location);
                    } catch (RuntimeException cleanupError) {
                        LOGGER.debug("Unable to release failed network texture {}", location, cleanupError);
                    }
                } else if (dynamicTexture != null) {
                    try {
                        dynamicTexture.close();
                    } catch (RuntimeException cleanupError) {
                        LOGGER.debug("Unable to close failed network texture {}", hash, cleanupError);
                    }
                } else if (nativeImage != null) {
                    nativeImage.close();
                }
            }
        }
    }

    /**
     * Check if a texture is cached
     * @param hash The texture hash
     * @return true if the texture is in the cache
     */
    public synchronized boolean hasTexture(String hash, String textureType) {
        if (!NetworkSecurity.isValidContentId(hash)
                || !NetworkSecurity.isValidTextureType(textureType)) return false;
        TextureKey key = new TextureKey(hash, textureType);
        boolean present = textureDataCache.containsKey(key);
        if (present) accessOrder.put(key, Boolean.TRUE);
        return present;
    }

    /**
     * Marks a cached texture as part of the current render working set.
     *
     * <p>This is intentionally separate from duplicate/cache probes: only a renderer-facing
     * lookup should protect an entry from ordinary LRU eviction.</p>
     */
    public synchronized boolean markTextureInUse(String hash, String textureType) {
        if (!NetworkSecurity.isValidContentId(hash)
                || !NetworkSecurity.isValidTextureType(textureType)) return false;
        TextureKey key = new TextureKey(hash, textureType);
        if (!textureDataCache.containsKey(key)) return false;
        accessOrder.put(key, Boolean.TRUE);
        workingSet.markInUse(key);
        return true;
    }

    /** Advances the short render-use lease; called once per client tick. */
    public synchronized void tickWorkingSet() {
        workingSet.advanceTick();
    }

    public synchronized long generation() {
        return generation;
    }

    /** Performs only bounded CPU/image work; callers must commit the result on the client thread. */
    @Nullable
    public PreparedTexture prepareTextureIfCurrent(
            long expectedGeneration, String hash, @Nullable String textureType, byte[] textureData) {
        long pixels = NetworkSecurity.getTexturePixelCount(textureData, textureType);
        int encodedBytes = textureData == null ? 0 : textureData.length;
        if (pixels < 1 || encodedBytes < 1) return null;
        synchronized (this) {
            if (generation != expectedGeneration
                    || pendingPreparedPixels > TextureTransferLimits.MAX_CLIENT_CACHE_PIXELS - pixels
                    || pendingPreparedBytes > TextureTransferLimits.MAX_CLIENT_CACHE_BYTES - encodedBytes) {
                return null;
            }
            pendingPreparedPixels += pixels;
            pendingPreparedBytes += encodedBytes;
        }
        PreparedTexture prepared = null;
        try {
            prepared = prepareTexture(
                    hash, textureType, textureData, pixels, encodedBytes);
            return prepared;
        } finally {
            if (prepared == null) releasePreparedLease(pixels, encodedBytes);
        }
    }

    /** Commits prepared data and performs any Minecraft resource eviction on the client thread. */
    public synchronized boolean commitPreparedTextureIfCurrent(
            long expectedGeneration, String hash, String textureType,
            @Nullable PreparedTexture prepared) {
        if (prepared == null) return false;
        if (generation != expectedGeneration) {
            prepared.discard();
            return false;
        }
        return commitPreparedTexture(hash, textureType, prepared);
    }

    /** Releases a prepared but uncommitted native image. */
    public void discardPreparedTexture(@Nullable PreparedTexture prepared) {
        if (prepared != null) prepared.discard();
    }

    private synchronized void releasePreparedLease(long pixels, int bytes) {
        pendingPreparedPixels = Math.max(0L, pendingPreparedPixels - pixels);
        pendingPreparedBytes = Math.max(0L, pendingPreparedBytes - bytes);
    }

    /** Fast duplicate check used before spending another full image decode budget. */
    public synchronized boolean containsTexture(String hash, String textureType) {
        TextureKey key = new TextureKey(hash, textureType);
        boolean present = NetworkSecurity.isValidContentId(hash)
                && NetworkSecurity.isValidTextureType(textureType)
                && textureDataCache.containsKey(key);
        if (present) accessOrder.put(key, Boolean.TRUE);
        return present;
    }

    //? if <1.21.11 {
    @Nullable
    public Path getOrCreateTempFile(String hash, String textureType) {
        if (!NetworkSecurity.isValidContentId(hash) || !"skin".equals(textureType)) return null;
        TextureKey key = new TextureKey(hash, textureType);
        Path existing = tempFileCache.get(key);
        if (existing != null && Files.exists(existing)) {
            return existing;
        }
        byte[] original = originalTextureData.get(key);
        if (original == null) return null;
        try {
            Path cpmCacheDir = PlatformHelper.getGameDirectory().resolve("quickskin").resolve("cpm-cache");
            Files.createDirectories(cpmCacheDir);
            Path tempFile = NetworkSecurity.resolveContained(cpmCacheDir, hash, ".png");
            if (tempFile == null || Files.isSymbolicLink(tempFile)) return null;
            com.quickskin.mod.client.compat.CPMCompatIntegration.evictHttpTextureCache(hash);
            Files.write(tempFile, original);
            tempFileCache.put(key, tempFile);
            return tempFile;
        } catch (IOException e) {
            LOGGER.warn("Unable to create CPM network texture cache file for {}", hash, e);
            return null;
        }
    }
    //?}
    /**
     * Clear all cached network textures
     */
    public synchronized void clear() {
        generation++;
        // Release all registered textures
        //? if <1.21.11 {
        for (ResourceLocation location : textureRegistry.values()) {
        //?} else {
        for (Identifier location : textureRegistry.values()) {
        //?}
            try {
                Minecraft.getInstance().getTextureManager().release(location);
            } catch (Exception e) {
                LOGGER.debug("Unable to release network texture {}", location, e);
            }
        }
        //? if <1.21.11 {
        for (Map.Entry<TextureKey, Path> entry : tempFileCache.entrySet()) {
            try {
                com.quickskin.mod.client.compat.CPMCompatIntegration
                        .evictHttpTextureCache(entry.getKey().hash());
                Files.deleteIfExists(entry.getValue());
            } catch (IOException e) {
                LOGGER.warn("Unable to delete CPM network texture cache file {}", entry.getValue(), e);
            }
        }
        tempFileCache.clear();
        //?}

        originalTextureData.clear();
        textureDataCache.clear();
        for (NativeImage image : preparedNativeImages.values()) image.close();
        preparedNativeImages.clear();
        preparedEarsImages.clear();
        preparedTransparency.clear();
        textureRegistry.clear();
        accessOrder.clear();
        workingSet.clear();
        cachedBytes = 0;
        cachedPixels = 0;
        TextureRequestCoordinator.getInstance().clear();
    }

    /**
     * Clear only skin texture registrations (not the raw data or capes)
     * Used when skin transparency setting changes - this forces skins to re-register
     * with the new transparency setting applied
     */
    public void clearSkins() {
        reprocessSkins();
    }

    /**
     * Reprocess and re-register all skin textures with current transparency settings
     * Called when server transparency setting changes
     */
    public void reprocessSkins() {
        java.util.List<ReprocessCandidate> candidates = new java.util.ArrayList<>();
        final long reprocessGeneration;
        synchronized (this) {
            reprocessGeneration = ++generation;
            for (java.util.Map.Entry<TextureKey, byte[]> entry : originalTextureData.entrySet()) {
                TextureKey key = entry.getKey();
                if (!"skin".equals(key.textureType())) continue;
                byte[] original = entry.getValue();
                if (original == null) continue;
                candidates.add(new ReprocessCandidate(key, original));
            }
        }

        reprocessNext(candidates, 0, reprocessGeneration);
    }

    private void reprocessNext(
            java.util.List<ReprocessCandidate> candidates, int index,
            long reprocessGeneration) {
        synchronized (this) {
            if (generation != reprocessGeneration || index >= candidates.size()) return;
        }
        ReprocessCandidate candidate = candidates.get(index);
        ClientIoExecutor.supplyAsync(() -> prepareTextureIfCurrent(
                reprocessGeneration, candidate.key().hash(), "skin", candidate.original()))
                .whenComplete((prepared, error) -> {
                    if (error != null) {
                        LOGGER.warn("Unable to reprocess network skin {}", candidate.key().hash(), error);
                    }
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft == null) {
                        discardPreparedTexture(prepared);
                        return;
                    }
                    minecraft.execute(() -> {
                        if (prepared != null) {
                            commitReprocessedSkinIfCurrent(
                                    reprocessGeneration, candidate, prepared);
                        }
                        synchronized (this) {
                            if (generation != reprocessGeneration) return;
                        }
                        reprocessNext(candidates, index + 1, reprocessGeneration);
                    });
                });
    }

    /** Atomically swaps one processed skin while retaining the old entry on preparation failure. */
    private boolean commitReprocessedSkinIfCurrent(
            long expectedGeneration, ReprocessCandidate candidate, PreparedTexture prepared) {
        //? if <1.21.11 {
        ResourceLocation oldLocation;
        //?} else {
        Identifier oldLocation;
        //?}
        synchronized (this) {
            if (generation != expectedGeneration
                    || originalTextureData.get(candidate.key()) != candidate.original()) {
                prepared.discard();
                return false;
            }
            NativeImage nativeImage = prepared.takeNativeImage();
            if (nativeImage == null) {
                prepared.releaseLease();
                return false;
            }
            byte[] previous = textureDataCache.put(candidate.key(), prepared.processed());
            if (previous != null) cachedBytes -= previous.length;
            cachedBytes += prepared.processed().length;
            accessOrder.put(candidate.key(), Boolean.TRUE);
            oldLocation = textureRegistry.remove(candidate.key());
            NativeImage oldPreparedImage = preparedNativeImages.put(candidate.key(), nativeImage);
            if (oldPreparedImage != null) oldPreparedImage.close();
            BufferedImage earsImage = prepared.takeEarsImage();
            if (earsImage == null) preparedEarsImages.remove(candidate.key());
            else preparedEarsImages.put(candidate.key(), earsImage);
            preparedTransparency.put(candidate.key(), prepared.hasTransparency());
            evictToLimits();
        }
        prepared.releaseLease();
        if (oldLocation != null) {
            com.quickskin.mod.client.compat.EarsCompatIntegration.clearFeatures(oldLocation);
            try {
                Minecraft.getInstance().getTextureManager().release(oldLocation);
            } catch (RuntimeException error) {
                LOGGER.debug("Unable to release reprocessed skin texture {}", oldLocation, error);
            }
        }
        com.quickskin.mod.common.data.PlayerAppearanceRepository.getInstance()
                .invalidateNetworkTexture(candidate.key().hash(), "skin");
        return true;
    }

    private record ReprocessCandidate(TextureKey key, byte[] original) {
    }

    /**
     * Get the number of cached textures
     * @return The cache size
     */
    public synchronized int size() {
        return textureDataCache.size();
    }

    private void evictToLimits() {
        while ((textureDataCache.size() > TextureTransferLimits.MAX_CLIENT_CACHE_ENTRIES
                || cachedBytes > TextureTransferLimits.MAX_CLIENT_CACHE_BYTES
                || cachedPixels > TextureTransferLimits.MAX_CLIENT_CACHE_PIXELS)) {
            TextureKey key = workingSet.selectEviction(accessOrder.keySet());
            if (key == null && !textureDataCache.isEmpty()) {
                key = textureDataCache.keySet().iterator().next();
            }
            if (key == null) break;
            removeTexture(key);
        }
    }

    private void removeTexture(TextureKey key) {
        String hash = key.hash();
        String removedTextureType = key.textureType();
        accessOrder.remove(key);
        workingSet.forget(key);
        byte[] original = originalTextureData.remove(key);
        byte[] processed = textureDataCache.remove(key);
        NativeImage preparedImage = preparedNativeImages.remove(key);
        if (preparedImage != null) preparedImage.close();
        preparedEarsImages.remove(key);
        preparedTransparency.remove(key);
        if (original != null) cachedBytes -= original.length;
        if (processed != null) cachedBytes -= processed.length;
        if (original != null) {
            long pixels = NetworkSecurity.getTexturePixelCount(original, removedTextureType);
            if (pixels > 0) cachedPixels -= pixels;
        }

        //? if <1.21.11 {
        ResourceLocation location = textureRegistry.remove(key);
        //?} else {
        Identifier location = textureRegistry.remove(key);
        //?}
        if (location != null) {
            com.quickskin.mod.client.compat.EarsCompatIntegration.clearFeatures(location);
            try {
                Minecraft.getInstance().getTextureManager().release(location);
            } catch (Exception e) {
                LOGGER.debug("Unable to release evicted network texture {}", location, e);
            }
        }
        //? if <1.21.11 {
        Path tempFile = tempFileCache.remove(key);
        if (tempFile != null && !Files.isSymbolicLink(tempFile)) {
            try {
                com.quickskin.mod.client.compat.CPMCompatIntegration.evictHttpTextureCache(hash);
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                LOGGER.warn("Unable to delete evicted CPM texture cache file {}", tempFile, e);
            }
        }
        //?}
        if (NetworkSecurity.isValidTextureType(removedTextureType)) {
            com.quickskin.mod.common.data.PlayerAppearanceRepository.getInstance()
                    .invalidateNetworkTexture(hash, removedTextureType);
            if ("cape".equals(removedTextureType)) {
                com.quickskin.mod.client.services.AnimatedTextureManager.getInstance()
                        .unregisterAnimation("cape_" + hash);
            }
        }
    }

    /** Texture content hashes are scoped by their rendering role. */
    private record TextureKey(String hash, String textureType) {
    }

}
