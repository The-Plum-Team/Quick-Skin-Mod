package com.quickskin.mod.client.services;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side service to manage skin change cooldown status.
 */
@Environment(EnvType.CLIENT)
public class CooldownService {
    private static CooldownService instance;
    private long skinChangeCooldownEnd = 0L;

    private CooldownService() {}

    public static CooldownService getInstance() {
        if (instance == null) {
            instance = new CooldownService();
        }
        return instance;
    }

    /**
     * Sets the timestamp when the cooldown will end.
     * @param time The epoch millisecond timestamp.
     */
    public void setCooldownEndTime(long time) {
        this.skinChangeCooldownEnd = time;
    }

    /**
     * Gets the remaining cooldown time in seconds.
     * @return The number of seconds remaining, or 0 if not on cooldown.
     */
    public long getRemainingCooldownSeconds() {
        if (skinChangeCooldownEnd == 0) return 0;
        long remainingMillis = skinChangeCooldownEnd - System.currentTimeMillis();
        return remainingMillis > 0 ? (remainingMillis / 1000) + 1 : 0;
    }

    /**
     * Checks if the player is currently on cooldown.
     * @return true if the cooldown is active, false otherwise.
     */
    public boolean isCooldownActive() {
        return getRemainingCooldownSeconds() > 0;
    }

    /**
     * Resets the cooldown timer.
     */
    public void clearCooldown() {
        this.skinChangeCooldownEnd = 0L;
    }
}
