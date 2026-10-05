package com.quickskin.mod.runtime;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.mixin.ChunkMapAccessor;
import com.quickskin.mod.mixin.PlayerGameProfileAccessor;
import com.quickskin.mod.mixin.TrackedEntityAccessor;
import com.quickskin.mod.networking.ProtocolNetwork;
import com.quickskin.mod.server.vanilla.AccountSkinShareService;
import com.quickskin.mod.server.vanilla.SignedTextures;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Installs freshly signed account textures on a player and refreshes that player for observers
 * that do not run Quick Skin.
 *
 * <p>An unmodded client reads a player's skin from the player-info entry, and its player entity
 * keeps the entry it first resolved. Each such observer therefore receives a player-info removal
 * and re-addition carrying the new profile and, if it tracks the player, a fresh entity pairing.
 * Quick Skin observers and the player itself are left alone: Quick Skin draws their appearance
 * through its own protocol. Later joiners read the updated profile from the normal join packets.</p>
 */
public final class AccountSkinProfileTarget implements AccountSkinShareService.Target {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountSkinProfileTarget.class);

    private final MinecraftServer server;

    public AccountSkinProfileTarget(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public boolean usesAuthentication() {
        return server.usesAuthentication();
    }

    @Override
    public void execute(Runnable task) {
        server.execute(task);
    }

    @Override
    public AccountSkinShareService.CurrentProfile current(UUID playerId, Object session) {
        ServerPlayer player = activePlayer(playerId, session);
        return player == null
                ? null
                : new AccountSkinShareService.CurrentProfile(GameProfileTextures.read(player.getGameProfile()));
    }

    @Override
    public int apply(UUID playerId, Object session, SignedTextures textures) {
        ServerPlayer player = activePlayer(playerId, session);
        if (player == null) return -1;
        GameProfile current = player.getGameProfile();
        GameProfile updated = GameProfileTextures.withTextures(current, textures);
        if (updated != current) {
            ((PlayerGameProfileAccessor) player).quickskin$setGameProfile(updated);
        }

        List<ServerPlayer> observers = new ArrayList<>();
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            if (candidate != player && !ProtocolNetwork.canReceive(candidate)) observers.add(candidate);
        }
        if (observers.isEmpty()) return 0;
        refreshFor(player, observers);
        return observers.size();
    }

    /** Re-sends the player's info entry and, where tracked, its entity to {@code observers}. */
    static void refreshFor(ServerPlayer player, List<ServerPlayer> observers) {
        ClientboundPlayerInfoRemovePacket removal =
                new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID()));
        ClientboundPlayerInfoUpdatePacket addition =
                ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
        for (ServerPlayer observer : observers) {
            observer.connection.send(removal);
            observer.connection.send(addition);
        }

        Object tracked = ((ChunkMapAccessor) ((ServerLevel) player.level()).getChunkSource().chunkMap)
                .quickskin$entityMap().get(player.getId());
        if (!(tracked instanceof TrackedEntityAccessor tracker)) {
            LOGGER.debug("{} has no entity tracker; only the player list was refreshed", player.getUUID());
            return;
        }
        Set<ServerPlayer> refresh = Collections.newSetFromMap(new IdentityHashMap<>());
        refresh.addAll(observers);
        ServerEntity serverEntity = tracker.quickskin$serverEntity();
        for (ServerPlayerConnection connection : List.copyOf(tracker.quickskin$seenBy())) {
            ServerPlayer observer = connection.getPlayer();
            if (!refresh.contains(observer)) continue;
            // The client's player entity caches its info entry: only a new entity reads the new skin.
            serverEntity.removePairing(observer);
            serverEntity.addPairing(observer);
        }
    }

    private ServerPlayer activePlayer(UUID playerId, Object session) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && player.connection == session ? player : null;
    }
}
