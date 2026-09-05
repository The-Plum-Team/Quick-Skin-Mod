package com.quickskin.mod.runtime;

import com.quickskin.mod.client.api.CpmAssetAccess;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.compat.CustomNPCsIntegration;
import com.quickskin.mod.client.gui.screen.SettingsScreen;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.networking.ClientNetworkHandler;
import com.quickskin.mod.platform.MinecraftTextures;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** Process-owned feature wiring, installed before catalog scans, events, or packet receivers. */
@Environment(EnvType.CLIENT)
public final class ClientFeatureBindings {
    private static boolean installed;

    private ClientFeatureBindings() {
    }

    public static synchronized void install() {
        if (installed) return;
        com.quickskin.mod.networking.TextureRequestCoordinator.configureConnection(
                () -> Minecraft.getInstance().getConnection());
        com.quickskin.mod.client.api.ClientNetworkApi.bind(
                new com.quickskin.mod.networking.NetworkClientActions());
        CPMCompatIntegration.configureAssets(new CpmAssetAccess() {
            @Override
            public AssetMetadata metadata(String contentId) {
                return LocalAssetManager.getInstance().getMetadata(contentId);
            }

            @Override
            public Path localSource(String contentId) {
                return LocalAssetManager.getInstance().getSourcePath(contentId);
            }

            @Override
            public Path networkSkinFile(String contentId) {
                return NetworkTextureCache.getInstance().getOrCreateTempFile(contentId, "skin");
            }
        });
        PlayerAppearanceService appearance = PlayerAppearanceService.getInstance();
        appearance.configureSkinListener((playerId, texture) ->
                CustomNPCsIntegration.onSkinApplied(playerId, MinecraftTextures.location(texture)));
        ClientNetworkHandler.configureAppearance(appearance, ClientFeatureBindings::settingsScreenOpen);
        installed = true;
    }

    private static boolean settingsScreenOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return false;
        //? if <26.2 {
        return minecraft.screen instanceof SettingsScreen;
        //?} else {
        return minecraft.gui.screen() instanceof SettingsScreen;
        //?}
    }
}
