package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.KnownCapes;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing player capes
 * Handles loading capes from Mojang API, local storage, and known capes
 */
@Environment(EnvType.CLIENT)
public class CapeService implements ICapeService {
    private static CapeService instance;

    private CapeService() {}

    public static CapeService getInstance() {
        if (instance == null) {
            instance = new CapeService();
        }
        return instance;
    }

    public static void init() {
        getInstance();
    }

    @Override
    @Nullable
    //? if <1.21.11 {
    public ResourceLocation getCapeLocation(UUID playerId, String capeId) {
    //?} else {
    public Identifier getCapeLocation(UUID playerId, String capeId) {
    //?}

        if (capeId == null || capeId.isEmpty()) {
            return null;
        }

        // Check if it's a local cape
        if (capeId.startsWith("local_cape:")) {
            String hash = capeId.substring("local_cape:".length());
            return loadLocalCape(hash);
        }

        // Check if it's a known cape
        if (capeId.startsWith("known:")) {
            String knownId = capeId.substring("known:".length());
            return loadKnownCape(knownId);
        }

        // Otherwise, it's a Mojang username
        return loadMojangCape(capeId);
    }

    @Override
    @Nullable
    //? if <1.21.11 {
    public ResourceLocation loadMojangCape(String username) {
    //?} else {
    public Identifier loadMojangCape(String username) {
    //?}
        // Mojang cape loading requires online API access - not implemented yet
        return null;
    }

    @Override
    @Nullable
    //? if <1.21.11 {
    public ResourceLocation loadLocalCape(String hash) {
    //?} else {
    public Identifier loadLocalCape(String hash) {
    //?}
        // Check network cache first (for capes received from server)
        //? if <1.21.11 {
        ResourceLocation capeLocation;
        //?} else {
        Identifier capeLocation;
        //?}
        if (com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                .hasTexture(hash, "cape")) {
            capeLocation = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                    .getTextureLocation(hash, "cape");
            if (capeLocation != null) {
                // Check if this network cape has animation metadata
                com.quickskin.mod.common.data.AnimationMetadata animMeta =
                    com.quickskin.mod.client.storage.ClientAnimationMetadataCache.getInstance().getMetadata(hash);

                if (animMeta != null) {
                    // Route all untrusted network animation work through the bounded async path.
                    com.quickskin.mod.networking.ClientNetworkHandler
                            .onTextureStored("cape", hash);
                }

                return capeLocation;
            }
        }

        // Fall back to local assets (for user's own capes)
        AssetMetadata localMetadata = LocalAssetManager.getInstance().getMetadata(hash);
        capeLocation = localMetadata != null && localMetadata.isCape()
                ? LocalAssetManager.getInstance().getTextureLocation(hash, TextureQuality.FULL)
                : null;

        if (capeLocation != null) {
            // Check if this cape is animated and register it if not already running.
            AssetMetadata assetMeta = localMetadata;
            if (assetMeta != null && assetMeta.isAnimated()) {
                // Register under the content ID we were handed, never a resolved primary: the
                // renderer derives its animation ID from the same cape ID the caller holds, so
                // rewriting it here would leave a second animation running beside the one the
                // renderer looks up. Aliases are folded onto their primary where the cape ID is
                // chosen, not where it is loaded.
                String capeId = CapeAnimationIds.LOCAL_PREFIX + hash;
                String animationId = CapeAnimationIds.deriveAnimationId(capeId);
                AnimatedTextureManager animManager = AnimatedTextureManager.getInstance();

                if (!animManager.isAnimated(animationId)) {
                    animManager.registerAnimationAsync(
                            animationId, capeId, capeLocation, hash);
                }
            }
        } else {
            // If not found locally and we're connected to a server, request it
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && mc.getConnection() != null) {
                com.quickskin.mod.networking.TextureRequestCoordinator.getInstance().requestIfNeeded(
                    "cape", hash,
                    () -> com.quickskin.mod.networking.NetworkSyncService.getInstance()
                        .requestTexture(mc.player.getUUID(), "cape", hash));
            }
        }

        return capeLocation;
    }

    @Override
    @Nullable
    //? if <1.21.11 {
    public ResourceLocation loadKnownCape(String capeId) {
    //?} else {
    public Identifier loadKnownCape(String capeId) {
    //?}
        // Look up the cape in the KnownCapes enum
        KnownCapes cape = KnownCapes.getById(capeId);

        if (cape != null && !cape.isNoCape()) {
            if (cape.isAnimated()) {
                String animationId = CapeAnimationIds.deriveAnimationId(
                        CapeAnimationIds.KNOWN_PREFIX + capeId);
                AnimatedTextureManager animManager = AnimatedTextureManager.getInstance();

                // Only register the animation if it's not already running
                if (!animManager.isAnimated(animationId)) {
                    try {
                        //? if <1.21.11 {
                        ResourceLocation capeTexture = cape.getTextureLocation();
                        //?} else {
                        Identifier capeTexture = cape.getTextureLocation();
                        //?}

                        BufferedImage atlasImage;
                        try (InputStream stream = Minecraft.getInstance().getResourceManager()
                                .getResource(capeTexture).orElseThrow().open()) {
                            byte[] encoded = com.quickskin.mod.common.util.BoundedFileReader.readBytes(
                                    stream,
                                    (int) com.quickskin.mod.common.util.SafeImageReader.MAX_ENCODED_BYTES);
                            atlasImage = com.quickskin.mod.common.util.SafeImageReader.readPng(encoded);
                        }

                        if (atlasImage != null) {
                            int width = atlasImage.getWidth();
                            int height = atlasImage.getHeight();
                            int frameHeight = width / 2; // Cape frames are 2:1 ratio
                            int frameCount = height / frameHeight;

                            if (frameCount > 1) {
                                // Create default frame metadata (50ms per frame)
                                List<AnimationMetadata.FrameData> frames = new ArrayList<>();
                                for (int i = 0; i < frameCount; i++) {
                                    frames.add(new AnimationMetadata.FrameData(50, i));
                                }
                                AnimationMetadata metadata = new AnimationMetadata(frames, frameCount);

                                String fullCapeId = "known:" + capeId;
                                animManager.registerAnimationAsync(
                                        animationId, fullCapeId, capeTexture, atlasImage, metadata);
                            }
                        }
                    } catch (Exception e) {
                        QuickSkin.LOGGER.warn("Unable to load known animated cape {}", capeId, e);
                    }
                }
            }

            //? if <1.21.11 {
            ResourceLocation location = cape.getTextureLocation();
            //?} else {
            Identifier location = cape.getTextureLocation();
            //?}
            return location;
        }

        return null;
    }

    @Override
    public boolean isAnimated(String capeId) {
        if (capeId == null || capeId.isEmpty()) {
            return false;
        }

        // Check if it's a local cape
        if (capeId.startsWith("local_cape:")) {
            String hash = capeId.substring("local_cape:".length());

            // Check if the asset has animation metadata
            AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(hash);
            if (metadata != null && metadata.isAnimated()) {
                return true;
            }
        }

        // Check if it's a known cape
        if (capeId.startsWith("known:")) {
            String knownId = capeId.substring("known:".length());
            com.quickskin.mod.common.data.KnownCapes cape = com.quickskin.mod.common.data.KnownCapes.getById(knownId);
            if (cape != null) {
                return cape.isAnimated();
            }
        }

        // Mojang capes are not animated
        return false;
    }

}
