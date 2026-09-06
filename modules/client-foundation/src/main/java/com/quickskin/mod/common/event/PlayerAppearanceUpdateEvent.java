package com.quickskin.mod.common.event;

import com.quickskin.mod.common.data.PlayerAppearance;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.UUID;

/**
 * Event fired when a player's appearance is updated
 * Listeners can use this to refresh UI, renderers, etc.
 */
@Environment(EnvType.CLIENT)
public record PlayerAppearanceUpdateEvent(UUID playerId, PlayerAppearance appearance, UpdateType updateType) {
    public PlayerAppearanceUpdateEvent {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId cannot be null");
        }
        if (appearance == null) {
            throw new IllegalArgumentException("appearance cannot be null");
        }
        if (updateType == null) {
            updateType = UpdateType.FULL;
        }
    }

    /** Compatibility constructor for callers that do not need granular update information. */
    public PlayerAppearanceUpdateEvent(UUID playerId, PlayerAppearance appearance) {
        this(playerId, appearance, UpdateType.FULL);
    }

    public enum UpdateType {
        SKIN,
        CAPE,
        MODEL,
        FULL
    }

}
