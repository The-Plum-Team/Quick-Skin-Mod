package com.quickskin.mod.networking;

import com.quickskin.mod.client.api.ClientNetworkActions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

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
}
