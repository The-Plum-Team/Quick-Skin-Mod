package com.quickskin.mod.server.data;

import com.quickskin.mod.config.ServerConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages skin change cooldowns for players on the server.
 */
public class ServerCooldownManager {
    private static final ServerCooldownManager INSTANCE = new ServerCooldownManager();
    private final Map<UUID, Long> lastSkinChangeTimestamps = new ConcurrentHashMap<>();

    private ServerCooldownManager() {}

    public static ServerCooldownManager getInstance() {
        return INSTANCE;
    }

    /**
     * Checks if a player is currently on cooldown for changing their skin.
     * @param playerId The UUID of the player to check.
     * @return true if the player is on cooldown, false otherwise.
     */
    public boolean isPlayerOnCooldown(UUID playerId) {
        int cooldownSeconds = ServerConfig.getInstance().skinChangeCooldownSeconds;
        if (cooldownSeconds <= 0) {
            return false;
        }

        long lastChangeTime = lastSkinChangeTimestamps.getOrDefault(playerId, 0L);
        long cooldownMillis = cooldownSeconds * 1000L;

        return (System.currentTimeMillis() - lastChangeTime) < cooldownMillis;
    }

    /**
     * Records that a player has just changed their skin, updating their cooldown timestamp.
     * @param playerId The UUID of the player.
     */
    public void recordSkinChange(UUID playerId) {
        lastSkinChangeTimestamps.put(playerId, System.currentTimeMillis());
    }

    /**
     * Calculates the timestamp when a player's cooldown will end.
     * @param playerId The UUID of the player.
     * @return The epoch millisecond timestamp when the cooldown ends, or 0 if not on cooldown.
     */
    public long getCooldownEndTime(UUID playerId) {
        int cooldownSeconds = ServerConfig.getInstance().skinChangeCooldownSeconds;
        if (cooldownSeconds <= 0) {
            return 0L;
        }

        long lastChangeTime = lastSkinChangeTimestamps.getOrDefault(playerId, 0L);
        return lastChangeTime + (cooldownSeconds * 1000L);
    }

    /**
     * Gets the raw timestamp of the last skin change for a player.
     * @param playerId The UUID of the player.
     * @return The epoch millisecond timestamp of the last change.
     */
    public long getLastChangeTime(UUID playerId) {
        return lastSkinChangeTimestamps.getOrDefault(playerId, 0L);
    }

    /**
     * Removes a player's cooldown data, e.g., when they disconnect.
     * @param playerId The UUID of the player.
     */
    public void removePlayer(UUID playerId) {
        lastSkinChangeTimestamps.remove(playerId);
    }

    /** Clears every cooldown owned by the current server session. */
    public void clear() {
        lastSkinChangeTimestamps.clear();
    }
}
