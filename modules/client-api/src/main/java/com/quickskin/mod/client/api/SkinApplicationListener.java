package com.quickskin.mod.client.api;

import com.quickskin.mod.platform.TextureReference;

import java.util.UUID;

/** Runs on the client thread after a skin texture is resolved. It does not own the texture. */
@FunctionalInterface
public interface SkinApplicationListener {
    void onSkinApplied(UUID playerId, TextureReference texture);
}
