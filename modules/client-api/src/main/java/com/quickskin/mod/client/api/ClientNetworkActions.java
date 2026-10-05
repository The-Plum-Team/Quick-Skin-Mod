package com.quickskin.mod.client.api;

import java.util.UUID;

/** Client feature operations; packet formats, negotiation, and session checks belong to networking. */
public interface ClientNetworkActions {
    void requestTexture(UUID playerId, String textureType, String contentId);

    void syncAppearance(UUID playerId, String skinId, String capeId, String model);

    void activateStoredTexture(String textureType, String contentId);

    void flushPendingTransparencyReload();

    void updateServerConfig(String key, boolean value);

    /**
     * Reports a successful, user-initiated upload to the player's own Mojang account to the
     * connected server, when it negotiated {@code account-skin-refresh}.
     *
     * @return {@code true} when the server announced that it shares account skins with players
     *         who do not run Quick Skin, so they will see the new skin without a rejoin
     */
    boolean notifyAccountSkinChanged();
}
