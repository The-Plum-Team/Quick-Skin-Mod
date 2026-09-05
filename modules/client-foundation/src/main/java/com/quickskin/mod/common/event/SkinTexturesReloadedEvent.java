package com.quickskin.mod.common.event;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Event emitted after skin texture registrations are rebuilt for a policy change. */
@Environment(EnvType.CLIENT)
public record SkinTexturesReloadedEvent(Reason reason) {
    public SkinTexturesReloadedEvent {
        if (reason == null) {
            throw new IllegalArgumentException("reason cannot be null");
        }
    }

    public enum Reason {
        TRANSPARENCY_POLICY
    }
}
