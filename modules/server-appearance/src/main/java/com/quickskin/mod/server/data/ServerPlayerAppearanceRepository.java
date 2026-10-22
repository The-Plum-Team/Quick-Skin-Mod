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
 * This stores appearance data for all connected players on the server, and the last appearance
 * of players who have left, so that players who join later still see it (on a figure or statue
 * of that player, for example).
 */
public class ServerPlayerAppearanceRepository {
    private static final ServerPlayerAppearanceRepository INSTANCE = new ServerPlayerAppearanceRepository();
    /** Players who have left whose appearance is kept; past it, whoever left first is dropped. */
    public static final int MAX_OFFLINE_APPEARANCES = 256;

    private final Map<UUID, PlayerAppearance> appearances = new LinkedHashMap<>();
    // Their textures are not pinned: they never take room from a connected player, and the cache
    // may evict them. Disjoint from the connected appearances, oldest departure first.
    private final Map<UUID, PlayerAppearance> offline = new LinkedHashMap<>();
    private long revision;
    private AppearanceRoster cachedRoster = new AppearanceRoster(0L, List.of());

    private ServerPlayerAppearanceRepository() {}

    public static ServerPlayerAppearanceRepository getInstance() {
        return INSTANCE;
    }

    /**
     * Gets a player's appearance data: a connected player's, or the last one of a player who left
     * @param playerId The player's UUID
     * @return The appearance data, or null if not set
     */
    @Nullable
    public synchronized PlayerAppearance getAppearance(UUID playerId) {
        PlayerAppearance connected = appearances.get(playerId);
        return connected != null ? connected : offline.get(playerId);
    }

    /**
     * The appearance to send to other players: a connected player's as it is, and that of a
     * player who left without each texture the cache no longer holds, since nothing pins it.
     */
    @Nullable
    public synchronized PlayerAppearance getAppearanceToShare(UUID playerId) {
        PlayerAppearance connected = appearances.get(playerId);
        if (connected != null) return connected;
        PlayerAppearance left = offline.get(playerId);
        if (left == null) return null;
        ServerTextureCache textures = ServerTextureCache.getInstance();
        return new PlayerAppearance(playerId,
                textures.heldTextureOrEmpty(left.getSkinId(), "local_skin:"),
                textures.heldTextureOrEmpty(left.getCapeId(), "local_cape:"),
                left.getModel());
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
        PlayerAppearance left = offline.remove(accepted.getPlayerId());
        if (!sameAppearance(previous != null ? previous : left, accepted)) revision++;
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
        PlayerAppearance left = offline.remove(playerId);
        if (!sameAppearance(current != null ? current : left, candidate)) revision++;
        return true;
    }

    /**
     * Removes a player's appearance data, the connected one and any kept after they left
     * @param playerId The player's UUID
     */
    public synchronized void removeAppearance(UUID playerId) {
        if (playerId == null) return;
        PlayerAppearance removed = appearances.remove(playerId);
        ServerTextureCache.getInstance().releaseAppearancePins(playerId);
        if (offline.remove(playerId) != null || removed != null) revision++;
    }

    /**
     * Releases the pins of a player who disconnected and keeps their appearance as one of a player
     * who left, unless it has neither a skin nor a cape to show.
     * @param playerId The player's UUID
     */
    public synchronized void playerLeft(UUID playerId) {
        if (playerId == null) return;
        PlayerAppearance left = appearances.remove(playerId);
        ServerTextureCache.getInstance().releaseAppearancePins(playerId);
        // Still in the roster when kept, so the roster changes only when it is not
        if (left != null && !keep(left)) revision++;
    }

    /**
     * Keeps the saved appearance of a player who is not connected, without pinning its textures.
     * The appearance of a connected player is left as it is.
     * @return whether it was kept
     */
    public synchronized boolean keepOfflineAppearance(PlayerAppearance appearance) {
        if (appearance == null || appearance.getPlayerId() == null
                || appearances.containsKey(appearance.getPlayerId())) {
            return false;
        }
        PlayerAppearance previous = offline.get(appearance.getPlayerId());
        if (!keep(copy(appearance))) {
            if (previous != null) revision++;
            return false;
        }
        if (!sameAppearance(previous, appearance)) revision++;
        return true;
    }

    // Puts a departed player's appearance last, dropping whoever left first beyond the bound.
    private boolean keep(PlayerAppearance appearance) {
        offline.remove(appearance.getPlayerId());
        if (appearance.getSkinId().isEmpty() && appearance.getCapeId().isEmpty()) return false;
        offline.put(appearance.getPlayerId(), appearance);
        while (offline.size() > MAX_OFFLINE_APPEARANCES) {
            offline.remove(offline.keySet().iterator().next());
            revision++;
        }
        return true;
    }

    /**
     * Clears all appearance data
     */
    public synchronized void clear() {
        if (!appearances.isEmpty() || !offline.isEmpty()) revision++;
        for (UUID playerId : appearances.keySet()) {
            ServerTextureCache.getInstance().releaseAppearancePins(playerId);
        }
        appearances.clear();
        offline.clear();
    }

    /**
     * Checks if a connected player has appearance data
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

    /**
     * One immutable roster view shared by every paced snapshot during the same revision: the
     * connected players, then the players who left.
     */
    public synchronized AppearanceRoster snapshotRoster() {
        if (cachedRoster.revision() != revision) {
            List<UUID> playerIds = new ArrayList<>(appearances.size() + offline.size());
            playerIds.addAll(appearances.keySet());
            playerIds.addAll(offline.keySet());
            cachedRoster = new AppearanceRoster(revision, playerIds);
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
