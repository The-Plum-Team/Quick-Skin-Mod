package com.quickskin.mod.common.data;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for player appearance data (client-side only)
 * This is the central repository that all services and mixins query
 */
@Environment(EnvType.CLIENT)
public class PlayerAppearanceRepository {
    private static final PlayerAppearanceRepository INSTANCE = new PlayerAppearanceRepository();
    private static final int MAX_APPEARANCES = 4096;

    private final Map<UUID, PlayerAppearance> appearances = new ConcurrentHashMap<>();

    private PlayerAppearanceRepository() {}

    public static PlayerAppearanceRepository getInstance() {
        return INSTANCE;
    }

    /**
     * Gets a player's appearance data
     * @param playerId The player's UUID, or null for a name-only profile
     * @return The appearance data, or null if not set or the id is null
     */
    @Nullable
    public PlayerAppearance getAppearance(@Nullable UUID playerId) {
        // A name-only GameProfile (a player_head whose SkullOwner is a bare name, or an
        // unresolved skull lookup) has no UUID. Appearances are keyed by UUID, so there is
        // nothing to find, and ConcurrentHashMap rejects a null key.
        if (playerId == null) return null;
        return appearances.get(playerId);
    }

    /**
     * Sets a player's appearance data
     * @param appearance The appearance data to set
     */
    public synchronized void setAppearance(PlayerAppearance appearance) {
        if (appearance == null || appearance.getPlayerId() == null) {
            return;
        }
        UUID playerId = appearance.getPlayerId();
        if (!appearances.containsKey(playerId) && appearances.size() >= MAX_APPEARANCES) {
            var eldest = appearances.keySet().iterator();
            if (eldest.hasNext()) appearances.remove(eldest.next());
        }
        appearances.put(playerId, appearance);
    }

    /**
     * Clears all appearance data
     */
    public void clear() {
        appearances.clear();
    }

    public void removeAppearance(UUID playerId) {
        if (playerId != null) appearances.remove(playerId);
    }

    /** Invalidates cached Minecraft handles when a network texture is replaced or evicted. */
    public synchronized void invalidateNetworkTexture(String hash, String textureType) {
        if (hash == null || textureType == null) return;
        String expectedId = ("skin".equals(textureType) ? "local_skin:" : "local_cape:") + hash;
        for (PlayerAppearance appearance : appearances.values()) {
            if ("skin".equals(textureType) && expectedId.equals(appearance.getSkinId())) {
                appearance.setSkinLocation(null);
            } else if ("cape".equals(textureType) && expectedId.equals(appearance.getCapeId())) {
                appearance.setCapeLocation(null);
            }
        }
    }

}
