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
     * @return what players without Quick Skin on the connected server will see, as that server
     *         announced it
     */
    AccountSkinVisibility notifyAccountSkinChanged();

    /** What players without Quick Skin see after an upload to Mojang, per the connected server. */
    enum AccountSkinVisibility {
        /** The server shares account skins: they see it within minutes, without a rejoin. */
        SHARED,
        /** An online-mode server that does not share them: they see it after a rejoin. */
        AFTER_REJOIN,
        /** Offline mode or another login service: they never see Mojang account skins there. */
        UNAVAILABLE,
        /** The server did not say (it runs no or an older Quick Skin). */
        UNKNOWN
    }
}
