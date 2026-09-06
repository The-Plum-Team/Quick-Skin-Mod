package com.quickskin.mod.server.data;

import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.server.storage.ServerTextureCache;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side repository for tracking player appearances
 * This stores appearance data for all connected players on the server
 */
public class ServerPlayerAppearanceRepository {
    private static final ServerPlayerAppearanceRepository INSTANCE = new ServerPlayerAppearanceRepository();

    private final Map<UUID, PlayerAppearance> appearances = new LinkedHashMap<>();
    private long revision;
    private AppearanceRoster cachedRoster = new AppearanceRoster(0L, List.of());

    private ServerPlayerAppearanceRepository() {}

    public static ServerPlayerAppearanceRepository getInstance() {
        return INSTANCE;
    }

    /**
     * Gets a player's appearance data
     * @param playerId The player's UUID
     * @return The appearance data, or null if not set
     */
    @Nullable
    public synchronized PlayerAppearance getAppearance(UUID playerId) {
        return appearances.get(playerId);
    }

    /**
     * Sets a player's appearance data
     * @param appearance The appearance data to set
     */
    public void setAppearance(PlayerAppearance appearance) {
        trySetAppearance(appearance);
    }

    /** Returns false without changing the old appearance when its blob pins exceed the cap. */
    public synchronized boolean trySetAppearance(PlayerAppearance appearance) {
        if (appearance == null || appearance.getPlayerId() == null) {
            return false;
        }
        PlayerAppearance accepted = copy(appearance);
        if (!ServerTextureCache.getInstance().tryReplaceAppearancePins(
                accepted.getPlayerId(), accepted.getSkinId(), accepted.getCapeId())) return false;
        PlayerAppearance previous = appearances.put(accepted.getPlayerId(), accepted);
        if (!sameAppearance(previous, accepted)) revision++;
        return true;
    }

    /**
     * Updates an existing player's appearance, or creates new if doesn't exist
     * @param playerId The player's UUID
     * @param skinId The skin ID (can be null to keep existing)
     * @param capeId The cape ID (can be null to keep existing)
     * @param model The model type (can be null to keep existing)
     */
    public void updateAppearance(UUID playerId, @Nullable String skinId, @Nullable String capeId, @Nullable String model) {
        tryUpdateAppearance(playerId, skinId, capeId, model);
    }

    /** Transactional update used by the network acceptance path. */
    public synchronized boolean tryUpdateAppearance(
            UUID playerId,
            @Nullable String skinId,
            @Nullable String capeId,
            @Nullable String model
    ) {
        if (playerId == null) return false;
        PlayerAppearance current = appearances.get(playerId);
        String nextSkin = skinId != null
                ? skinId : current != null ? current.getSkinId() : "";
        String nextCape = capeId != null
                ? capeId : current != null ? current.getCapeId() : "";
        String nextModel = model != null
                ? model : current != null ? current.getModel() : "classic";
        PlayerAppearance candidate = new PlayerAppearance(
                playerId, nextSkin, nextCape, nextModel);
        if (!ServerTextureCache.getInstance().tryReplaceAppearancePins(
                playerId, nextSkin, nextCape)) return false;
        appearances.put(playerId, candidate);
        if (!sameAppearance(current, candidate)) revision++;
        return true;
    }

    /**
     * Removes a player's appearance data (e.g., when they disconnect)
     * @param playerId The player's UUID
     */
    public synchronized void removeAppearance(UUID playerId) {
        if (playerId == null) return;
        PlayerAppearance removed = appearances.remove(playerId);
        ServerTextureCache.getInstance().releaseAppearancePins(playerId);
        if (removed != null) revision++;
    }

    /**
     * Clears all appearance data
     */
    public synchronized void clear() {
        if (!appearances.isEmpty()) revision++;
        for (UUID playerId : appearances.keySet()) {
            ServerTextureCache.getInstance().releaseAppearancePins(playerId);
        }
        appearances.clear();
    }

    /**
     * Checks if a player has custom appearance data
     * @param playerId The player's UUID
     * @return true if the player has custom data
     */
    public synchronized boolean hasAppearance(UUID playerId) {
        return appearances.containsKey(playerId);
    }

    /** Prevents eviction of a blob referenced by a currently connected appearance. */
    public boolean isTextureReferenced(String hash) {
        return ServerTextureCache.getInstance().isPinned(hash);
    }

    /** One immutable roster view shared by every paced snapshot during the same revision. */
    public synchronized AppearanceRoster snapshotRoster() {
        if (cachedRoster.revision() != revision) {
            cachedRoster = new AppearanceRoster(
                    revision, new ArrayList<>(appearances.keySet()));
        }
        return cachedRoster;
    }

    private static PlayerAppearance copy(PlayerAppearance appearance) {
        return new PlayerAppearance(
                appearance.getPlayerId(), appearance.getSkinId(),
                appearance.getCapeId(), appearance.getModel());
    }

    private static boolean sameAppearance(
            @Nullable PlayerAppearance first, PlayerAppearance second) {
        return first != null
                && first.getSkinId().equals(second.getSkinId())
                && first.getCapeId().equals(second.getCapeId())
                && first.getModel().equals(second.getModel());
    }

    public record AppearanceRoster(long revision, List<UUID> playerIds) {
        public AppearanceRoster {
            playerIds = List.copyOf(playerIds);
        }
    }
}
