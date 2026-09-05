package com.quickskin.mod.common.event;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Objects;
import java.util.UUID;

/** An authoritative remote look has finished applying on the Minecraft thread. */
@Environment(EnvType.CLIENT)
public record NetworkAppearanceAppliedEvent(UUID playerId) {
    public NetworkAppearanceAppliedEvent {
        Objects.requireNonNull(playerId, "playerId");
    }
}
