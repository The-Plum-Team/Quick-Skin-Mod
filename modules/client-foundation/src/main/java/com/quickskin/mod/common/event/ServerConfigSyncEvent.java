package com.quickskin.mod.common.event;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Event fired when server config is synced to the client
 * Listeners can use this to update client behavior based on server settings
 */
@Environment(EnvType.CLIENT)
public class ServerConfigSyncEvent {
    private final boolean allowTransparentSkins;

    public ServerConfigSyncEvent(boolean allowTransparentSkins) {
        this.allowTransparentSkins = allowTransparentSkins;
    }

    public boolean isAllowTransparentSkins() {
        return allowTransparentSkins;
    }
}
