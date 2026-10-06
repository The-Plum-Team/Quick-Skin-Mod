package com.quickskin.mod.runtime;

import com.mojang.authlib.EnvironmentParser;
import com.mojang.authlib.GameProfile;
import com.quickskin.mod.mixin.ChunkMapAccessor;
import com.quickskin.mod.mixin.PlayerGameProfileAccessor;
import com.quickskin.mod.mixin.TrackedEntityAccessor;
import com.quickskin.mod.networking.ProtocolNetwork;
import com.quickskin.mod.server.vanilla.AccountSkinShareService;
import com.quickskin.mod.server.vanilla.MojangAuthenticationEnvironment;
import com.quickskin.mod.server.vanilla.SignedTextures;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
//? if <1.20.2 {
import net.minecraft.server.players.GameProfileCache;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private final Optional<String> nonMojangReason;

    public AccountSkinProfileTarget(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.nonMojangReason = MojangAuthenticationEnvironment.nonMojangReason(
                MojangAuthenticationEnvironment.ofThisJvm(authlibEnvironmentOverridden()));
    }

    /**
     * Why this online-mode server does not authenticate players with Mojang's own session server
     * (empty when it does). Read once: the JVM's authentication setup is fixed at startup.
     */
    public Optional<String> nonMojangReason() {
        return nonMojangReason;
    }

    @Override
    public AccountSkinShareService.Authentication authentication() {
        if (!server.usesAuthentication()) return AccountSkinShareService.Authentication.OFFLINE;
        return nonMojangReason.isPresent()
                ? AccountSkinShareService.Authentication.OTHER
                : AccountSkinShareService.Authentication.MOJANG;
    }

    @Override
    public void execute(Runnable task) {
        server.execute(task);
    }

    @Override
    public AccountSkinShareService.CurrentProfile current(UUID playerId, Object session) {
        ServerPlayer player = activePlayer(playerId, session);
        if (player == null) return null;
        GameProfile profile = player.getGameProfile();
        return new AccountSkinShareService.CurrentProfile(
                GameProfileTextures.name(profile), GameProfileTextures.read(profile));
    }

    @Override
    public int apply(UUID playerId, Object session, SignedTextures textures) {
        ServerPlayer player = activePlayer(playerId, session);
        if (player == null) return -1;
        GameProfile previous = player.getGameProfile();
        // Always a new profile: a queued player-info packet may still be encoding the old one.
        GameProfile updated = GameProfileTextures.withTextures(previous, textures);
        ((PlayerGameProfileAccessor) player).quickskin$setGameProfile(updated);
        updateProfileCache(previous, updated);

        List<ServerPlayer> observers = AccountSkinObservers.withoutQuickSkin(
                server.getPlayerList().getPlayers(), player, ProtocolNetwork::canReceive);
        if (observers.isEmpty()) return 0;
        refreshFor(player, observers);
        return observers.size();
    }

    /**
     * Before 1.20.2 a skull resolves its owner from the profile cache entry, which is the login
     * profile instance itself, without asking the session server again; keep that entry current.
     * Later versions fetch skull profiles from the session server or cache only names and ids.
     */
    private void updateProfileCache(GameProfile previous, GameProfile updated) {
        //? if <1.20.2 {
        GameProfileCache cache = server.getProfileCache();
        if (cache != null && cache.get(previous.getId()).orElse(null) == previous) cache.add(updated);
        //?}
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
        List<ServerPlayer> viewers = new ArrayList<>();
        for (ServerPlayerConnection connection : List.copyOf(tracker.quickskin$seenBy())) {
            viewers.add(connection.getPlayer());
        }
        ServerEntity serverEntity = tracker.quickskin$serverEntity();
        for (ServerPlayer observer : AccountSkinObservers.pairedObservers(viewers, observers)) {
            // The client's player entity caches its info entry: only a new entity reads the new skin.
            serverEntity.removePairing(observer);
            serverEntity.addPairing(observer);
            if (observer.getCamera() == player) {
                // A spectator watching through the player's eyes would keep the removed entity
                // as its camera and freeze; point it at the new one.
                observer.connection.send(new ClientboundSetCameraPacket(player));
            }
        }
    }

    private ServerPlayer activePlayer(UUID playerId, Object session) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && player.connection == session ? player : null;
    }

    private static boolean authlibEnvironmentOverridden() {
        try {
            return EnvironmentParser.getEnvironmentFromProperties().isPresent();
        } catch (RuntimeException | LinkageError unavailable) {
            // The system property, agent and URL checks do not depend on this parser.
            return false;
        }
    }
}
