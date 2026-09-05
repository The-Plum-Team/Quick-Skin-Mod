package com.quickskin.mod.client.services;

import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.data.AssetMetadata;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.DefaultPlayerSkin;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Service for managing player skins
 * Handles loading skins from Mojang API and local storage
 */
@Environment(EnvType.CLIENT)
public class SkinService implements ISkinService {
    private static SkinService instance;

    private SkinService() {}

    public static SkinService getInstance() {
        if (instance == null) {
            instance = new SkinService();
        }
        return instance;
    }

    public static void init() {
        getInstance();
    }

    @Override
    @Nullable
    //? if <1.21.11 {
    public ResourceLocation getSkinLocation(UUID playerId, String skinId) {
    //?} else {
    public Identifier getSkinLocation(UUID playerId, String skinId) {
    //?}
        if (skinId == null || skinId.isEmpty()) {
            return null;
        }

        // Check if it's a local skin
        if (skinId.startsWith("local_skin:")) {
            String hash = skinId.substring("local_skin:".length());
            return loadLocalSkin(hash);
        }

        // Otherwise, it's a Mojang username
        return loadMojangSkin(skinId);
    }

    @Override
    @Nullable
    //? if <1.21.11 {
    public ResourceLocation loadMojangSkin(String username) {
    //?} else {
    public Identifier loadMojangSkin(String username) {
    //?}
        try {
            // Generate a UUID from the username for offline mode
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));

            // Return the default skin based on UUID
            // In a full implementation, this would fetch from Mojang's API
            // using the PlayerInfo or SkinManager to get the actual player skin
            //? if <1.21 {
            ResourceLocation defaultSkin = DefaultPlayerSkin.getDefaultSkin(uuid);
            //?} else if <1.21.11 {
            ResourceLocation defaultSkin = DefaultPlayerSkin.get(uuid).texture();
            //?} else {
            Identifier defaultSkin = DefaultPlayerSkin.get(uuid).body().texturePath();
            //?}
            return defaultSkin;

        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @Nullable
    //? if <1.21.11 {
    public ResourceLocation loadLocalSkin(String hash) {
    //?} else {
    public Identifier loadLocalSkin(String hash) {
    //?}
        // Check network cache first (for textures received from server)
        if (com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                .hasTexture(hash, "skin")) {
            //? if <1.21.11 {
            ResourceLocation networkLocation = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
            //?} else {
            Identifier networkLocation = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
            //?}
                    .getTextureLocation(hash, "skin");
            if (networkLocation != null) {
                return networkLocation;
            }
        }

        // Try local assets (for user's own skins)
        AssetMetadata localMetadata = LocalAssetManager.getInstance().getMetadata(hash);
        //? if <1.21.11 {
        ResourceLocation localLocation = localMetadata != null && localMetadata.isSkin()
                ? LocalAssetManager.getInstance().getTextureLocation(hash, TextureQuality.FULL)
                : null;
        //?} else {
        Identifier localLocation = localMetadata != null && localMetadata.isSkin()
                ? LocalAssetManager.getInstance().getTextureLocation(hash, TextureQuality.FULL)
                : null;
        //?}

        // If not found locally and we're connected to a server, request it
        if (localLocation == null) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && mc.getConnection() != null) {
                com.quickskin.mod.networking.TextureRequestCoordinator.getInstance().requestIfNeeded(
                    "skin", hash,
                    () -> com.quickskin.mod.client.api.ClientNetworkApi.actions()
                        .requestTexture(mc.player.getUUID(), "skin", hash));
            }
        }

        return localLocation;
    }

}
