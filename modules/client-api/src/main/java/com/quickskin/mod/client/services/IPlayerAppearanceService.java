package com.quickskin.mod.client.services;

import com.quickskin.mod.common.data.PlayerAppearance;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Main coordinator service for player appearance
 * This is the primary API for applying and managing player looks
 */
@Environment(EnvType.CLIENT)
public interface IPlayerAppearanceService {

    /**
     * Applies a complete look to a player (skin + cape + model)
     * This is the main entry point for changing a player's appearance
     *
     * @param playerId The player's UUID
     * @param skinId The skin ID (e.g., "local_skin:hash" or "username")
     * @param capeId The cape ID (e.g., "local_cape:hash", "known:minecon2016", or "username")
     * @param model The model type ("classic", "slim", or "auto")
     */
    void applyLook(UUID playerId, @Nullable String skinId, @Nullable String capeId, @Nullable String model);

    /**
     * Applies only a skin to a player
     * @param playerId The player's UUID
     * @param skinId The skin ID
     * @param model The model type (can be "auto")
     */
    void applySkin(UUID playerId, String skinId, @Nullable String model);

    /**
     * Applies only a cape to a player
     * @param playerId The player's UUID
     * @param capeId The cape ID
     */
    void applyCape(UUID playerId, String capeId);

    /**
     * Gets a player's current appearance
     * @param playerId The player's UUID
     * @return The appearance data, or null if not set
     */
    @Nullable
    PlayerAppearance getAppearance(UUID playerId);

    /**
     * Refreshes a player's renderer (forces re-render)
     * @param playerId The player's UUID
     */
    void refreshPlayerRenderer(UUID playerId);
}
