package com.quickskin.mod.client.api;

import com.quickskin.mod.common.data.PlayerAppearance;

import java.util.UUID;

/** Main-thread effects of accepted network state; the network layer does not own the UI. */
public interface RemoteAppearanceTarget {
    void applyLookFromNetwork(UUID playerId, String skinId, String capeId, String model);

    void reloadSkinsForTransparencyChange();

    /**
     * The server shows the local player a skin an admin gave them ({@code ""}: taken back). It is
     * drawn on the player instead of their own, whose choices meanwhile are kept, synced to the
     * server and shown again once it is taken back.
     */
    void applyAssignedSkin(UUID localPlayerId, String skinId, String model);

    /**
     * The local player's own appearance, which is what syncs: while a skin is assigned it holds
     * their own skin and model, not the one drawn on them. Null when they have none.
     */
    PlayerAppearance ownAppearance(UUID localPlayerId);
}
