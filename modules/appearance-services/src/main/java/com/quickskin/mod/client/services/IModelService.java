package com.quickskin.mod.client.services;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.UUID;

/**
 * Service interface for managing player model types (classic/slim)
 */
@Environment(EnvType.CLIENT)
public interface IModelService {

    /**
     * Gets the model type for a player
     * @param playerId The player's UUID
     * @param skinId The skin ID for auto-detection
     * @param requestedModel The requested model ("classic", "slim", "auto")
     * @return The resolved model type ("classic" or "slim")
     */
    String getModelType(UUID playerId, String skinId, String requestedModel);

    /**
     * Auto-detects the model type from skin texture data
     * @param skinData The raw skin texture bytes
     * @return The detected model type ("classic" or "slim")
     */
    String detectModelType(byte[] skinData);

    /**
     * Sets a model override for a player
     * @param playerId The player's UUID
     * @param model The model type to set
     */
    void setModelOverride(UUID playerId, String model);

    /**
     * Gets a model override for a player
     * @param playerId The player's UUID
     * @return The model override, or null if not set
     */
    String getModelOverride(UUID playerId);

    /**
     * Checks if a player has a model override
     * @param playerId The player's UUID
     * @return True if the player has a model override
     */
    boolean hasModelOverride(UUID playerId);
}
