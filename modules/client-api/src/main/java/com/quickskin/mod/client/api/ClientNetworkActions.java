package com.quickskin.mod.client.api;

import java.util.UUID;

/** Client feature operations; packet formats, negotiation, and session checks belong to networking. */
public interface ClientNetworkActions {
    void requestTexture(UUID playerId, String textureType, String contentId);

    void syncAppearance(UUID playerId, String skinId, String capeId, String model);

    void activateStoredTexture(String textureType, String contentId);

    void flushPendingTransparencyReload();

    void updateServerConfig(String key, boolean value);
}
