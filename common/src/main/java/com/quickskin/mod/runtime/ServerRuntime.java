package com.quickskin.mod.runtime;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.ServerNetworkHandler;
import com.quickskin.mod.server.concurrent.ServerTextureIngressExecutor;
import com.quickskin.mod.server.concurrent.ServerCacheIoExecutor;
import com.quickskin.mod.server.data.ServerCooldownManager;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import com.quickskin.mod.server.storage.ServerAnimationCache;
import com.quickskin.mod.server.storage.ServerAppearanceStorage;
import com.quickskin.mod.server.storage.ServerTextureCache;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.UUID;

/**
 * Owns state whose lifetime is one running Minecraft server.
 *
 * <p>The backing stores remain compatible singletons for existing callers, but lifecycle events
 * now reset them through one composition root instead of relying on scattered cleanup.</p>
 */
public final class ServerRuntime implements AutoCloseable {
    private static final ServerRuntime INSTANCE = new ServerRuntime(
            ServerTextureCache.getInstance(),
            ServerAnimationCache.getInstance(),
            ServerAppearanceStorage.getInstance(),
            ServerPlayerAppearanceRepository.getInstance(),
            ServerCooldownManager.getInstance()
    );

    private final ServerTextureCache textureCache;
    private final ServerAnimationCache animationCache;
    private final ServerAppearanceStorage appearanceStorage;
    private final ServerPlayerAppearanceRepository appearanceRepository;
    private final ServerCooldownManager cooldownManager;

    private MinecraftServer activeServer;

    public ServerRuntime(
            ServerTextureCache textureCache,
            ServerAnimationCache animationCache,
            ServerAppearanceStorage appearanceStorage,
            ServerPlayerAppearanceRepository appearanceRepository,
            ServerCooldownManager cooldownManager
    ) {
        this.textureCache = Objects.requireNonNull(textureCache, "textureCache");
        this.animationCache = Objects.requireNonNull(animationCache, "animationCache");
        this.appearanceStorage = Objects.requireNonNull(appearanceStorage, "appearanceStorage");
        this.appearanceRepository = Objects.requireNonNull(appearanceRepository, "appearanceRepository");
        this.cooldownManager = Objects.requireNonNull(cooldownManager, "cooldownManager");
    }

    public static ServerRuntime getInstance() {
        return INSTANCE;
    }

    /** Initializes all world-scoped stores after first discarding state from an earlier server. */
    public synchronized void start(MinecraftServer server) {
        Objects.requireNonNull(server, "server");

        if (activeServer == server) {
            return;
        }
        if (activeServer != null && activeServer != server) {
            QuickSkinInfo.LOGGER.warn("Starting a new QuickSkin server runtime before the previous one stopped; resetting stale state");
        }

        resetTransientState();
        ServerConfig.reload();
        ServerCacheIoExecutor.getInstance().start();
        java.nio.file.Path worldPath = server.getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT);
        textureCache.init(worldPath);
        animationCache.init(worldPath);
        appearanceStorage.init(worldPath);
        ServerTextureIngressExecutor.getInstance().start();
        activeServer = server;
    }

    /** Persists state while the server and its player list are still available. */
    public synchronized void prepareStop(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (activeServer == null || activeServer != server) {
            QuickSkinInfo.LOGGER.warn("Ignoring a stale QuickSkin server-stopping callback");
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            appearanceStorage.savePlayerAppearance(player.getUUID());
        }
        textureCache.saveAll();
        ServerConfig.getInstance().save();
    }

    /** Saves and evicts state owned by a player session. */
    public synchronized boolean playerDisconnected(
            MinecraftServer server, UUID playerId, Object connection) {
        if (server == null || server != activeServer || playerId == null || connection == null) {
            if (server != null && activeServer != null && server != activeServer) {
                QuickSkinInfo.LOGGER.warn("Ignoring a stale QuickSkin player-disconnect callback");
            }
            return false;
        }
        ServerPlayer activePlayer = server.getPlayerList().getPlayer(playerId);
        if (activePlayer != null && activePlayer.connection != connection) {
            QuickSkinInfo.LOGGER.debug(
                    "Ignoring an old QuickSkin session cleanup after {} reconnected", playerId);
            // Exact-session network state is safe to release even though UUID-scoped gameplay
            // state now belongs to the replacement connection.
            ServerNetworkHandler.onPlayerDisconnected(playerId, connection);
            return false;
        }
        appearanceStorage.savePlayerAppearance(playerId);
        appearanceRepository.removeAppearance(playerId);
        cooldownManager.removePlayer(playerId);
        ServerNetworkHandler.onPlayerDisconnected(playerId, connection);
        return true;
    }

    /** Completes shutdown and guarantees no state can leak into the next integrated server. */
    public synchronized void stop(MinecraftServer server) {
        if (activeServer != null && activeServer != server) {
            QuickSkinInfo.LOGGER.warn("Ignoring a stale QuickSkin server-stopped callback");
            return;
        }
        resetTransientState();
        activeServer = null;
    }

    private void resetTransientState() {
        runCleanup("stop server texture ingress", ServerTextureIngressExecutor.getInstance()::close);
        runCleanup("clear server textures", textureCache::clear);
        runCleanup("drain server cache cleanup", ServerCacheIoExecutor.getInstance()::close);
        runCleanup("clear server animation metadata", animationCache::clear);
        runCleanup("clear server appearances", appearanceRepository::clear);
        runCleanup("release server appearance storage", appearanceStorage::clear);
        runCleanup("clear server cooldowns", cooldownManager::clear);
        runCleanup("clear transient network transfers", ServerNetworkHandler::clearTransientNetworkState);
    }

    private static void runCleanup(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError error) {
            QuickSkinInfo.LOGGER.warn("Failed to {} while resetting the QuickSkin server runtime", operation, error);
        }
    }

    @Override
    public synchronized void close() {
        if (activeServer != null) {
            try {
                prepareStop(activeServer);
            } catch (RuntimeException | LinkageError error) {
                QuickSkinInfo.LOGGER.error("Failed to persist QuickSkin state during explicit server shutdown", error);
            }
        }
        resetTransientState();
        activeServer = null;
    }
}
