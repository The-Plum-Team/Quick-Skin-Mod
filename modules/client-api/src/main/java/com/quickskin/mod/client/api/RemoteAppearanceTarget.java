package com.quickskin.mod.client.api;

import java.util.UUID;

/** Main-thread effects of accepted network state; the network layer does not own the UI. */
public interface RemoteAppearanceTarget {
    void applyLookFromNetwork(UUID playerId, String skinId, String capeId, String model);

    void reloadSkinsForTransparencyChange();
}
