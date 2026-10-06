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
    public AccountSkinVisibility notifyAccountSkinChanged() {
        Minecraft minecraft = Minecraft.getInstance();
        Object connection = minecraft.getConnection();
        if (connection == null || minecraft.player == null) return AccountSkinVisibility.UNKNOWN;
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(connection);
        if (profile.negotiated() && profile.supports(ProtocolCapability.ACCOUNT_SKIN_REFRESH)) {
            UUID playerId = minecraft.player.getUUID();
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendAccountSkinChangedToServer(playerId);
            //?} else {
            if (NetworkTransport.INSTANCE.canServerReceive(
                    com.quickskin.mod.networking.payloads.AccountSkinChangedPayload.TYPE)) {
                NetworkTransport.INSTANCE.sendToServer(
                        new com.quickskin.mod.networking.payloads.AccountSkinChangedPayload(playerId));
            }
            //?}
        }
        return visibility(ClientConfig.getInstance().getServerOverride());
    }

    /** Reads what the connected server announced in its synchronized configuration. */
    static AccountSkinVisibility visibility(ServerConfig serverConfig) {
        String announced = serverConfig == null ? null : serverConfig.accountSkinVisibility;
        if (ServerConfig.ACCOUNT_SKIN_SHARED.equals(announced)) return AccountSkinVisibility.SHARED;
        if (ServerConfig.ACCOUNT_SKIN_AFTER_REJOIN.equals(announced)) return AccountSkinVisibility.AFTER_REJOIN;
        if (ServerConfig.ACCOUNT_SKIN_UNAVAILABLE.equals(announced)) return AccountSkinVisibility.UNAVAILABLE;
        return AccountSkinVisibility.UNKNOWN;
    }
}
