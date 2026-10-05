package com.quickskin.mod.networking;

import com.quickskin.mod.client.api.ClientNetworkActions;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/** Loader/version-selected transport behind the feature API. */
@Environment(EnvType.CLIENT)
public final class NetworkClientActions implements ClientNetworkActions {
    @Override
    public void requestTexture(UUID playerId, String textureType, String contentId) {
        NetworkSyncService.getInstance().requestTexture(playerId, textureType, contentId);
    }

    @Override
    public void syncAppearance(UUID playerId, String skinId, String capeId, String model) {
        NetworkSyncService.getInstance().syncAppearance(playerId, skinId, capeId, model);
    }

    @Override
    public void activateStoredTexture(String textureType, String contentId) {
        ClientNetworkHandler.onTextureStored(textureType, contentId);
    }

    @Override
    public void flushPendingTransparencyReload() {
        ClientNetworkHandler.executePendingTransparencyReload();
    }

    @Override
    public void updateServerConfig(String key, boolean value) {
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendServerConfigUpdateToServer(key, value);
        //?} else {
        NetworkTransport.INSTANCE.sendToServer(
                new com.quickskin.mod.networking.payloads.UpdateServerConfigPayload(key, value));
        //?}
    }

    @Override
    public boolean notifyAccountSkinChanged() {
        Minecraft minecraft = Minecraft.getInstance();
        Object connection = minecraft.getConnection();
        if (connection == null || minecraft.player == null) return false;
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(connection);
        if (!profile.negotiated() || !profile.supports(ProtocolCapability.ACCOUNT_SKIN_REFRESH)) {
            return false;
        }
        UUID playerId = minecraft.player.getUUID();
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendAccountSkinChangedToServer(playerId);
        //?} else {
        if (!NetworkTransport.INSTANCE.canServerReceive(
                com.quickskin.mod.networking.payloads.AccountSkinChangedPayload.TYPE)) return false;
        NetworkTransport.INSTANCE.sendToServer(
                new com.quickskin.mod.networking.payloads.AccountSkinChangedPayload(playerId));
        //?}
        ServerConfig serverConfig = ClientConfig.getInstance().getServerOverride();
        return serverConfig != null && serverConfig.shareAccountSkinWithVanillaClients;
    }
}
