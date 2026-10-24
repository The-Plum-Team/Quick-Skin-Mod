package com.quickskin.mod.event;

//? if >=1.21 {
import com.quickskin.mod.networking.ServerNetworkHandler;
//?}
import com.quickskin.mod.networking.MissingModNotice;
import com.quickskin.mod.networking.NetworkTransport;
import com.quickskin.mod.networking.ProtocolNetwork;
//? if <1.21 {
import com.quickskin.mod.networking.ServerNetworkHandler;
//?} else {
import com.quickskin.mod.networking.payloads.CooldownUpdatePayload;
//?}
import com.quickskin.mod.server.data.ServerCooldownManager;
import com.quickskin.mod.server.storage.ServerAppearanceStorage;
import com.quickskin.mod.runtime.ServerRuntime;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
//? if >=1.21 {
import com.quickskin.mod.networking.payloads.SyncAppearancePayload;
//?}
import net.minecraft.server.level.ServerPlayer;

/**
 * Common event handlers (runs on both client and server)
 * Uses Architectury's event system for cross-platform compatibility
 */
public class CommonEvents {
    private static boolean initialized;

    /**
     * Initializes common event listeners
     * Called from QuickSkin.init()
     */
    public static void init() {
        init(ServerRuntime.getInstance());
    }

    /** Registers callbacks against the runtime that owns server-scoped state. */
    public static synchronized void init(ServerRuntime serverRuntime) {
        if (initialized) {
            return;
        }
        if (serverRuntime == null) {
            throw new IllegalArgumentException("serverRuntime cannot be null");
        }

        // Player joins server
        PlayerEvent.PLAYER_JOIN.register(player -> {
            // Phase 5: Load player's saved appearance from server storage
            com.quickskin.mod.common.data.PlayerAppearance savedAppearance =
                ServerAppearanceStorage.getInstance().loadPlayerAppearance(player.getUUID());

            // If no saved appearance exists, create a default entry in the repository
            // This ensures all connected players have an entry that can be synced to joining players
            com.quickskin.mod.server.data.ServerPlayerAppearanceRepository repository =
                    com.quickskin.mod.server.data.ServerPlayerAppearanceRepository.getInstance();
            if (savedAppearance == null && !repository.hasAppearance(player.getUUID())) {
                repository.updateAppearance(player.getUUID(), "", "", "classic");
            }

            // Send QuickSkin data only to players that have the mod installed
            ServerPlayer serverPlayer = (ServerPlayer) player;
            // A skin an admin gave this player is pinned again before anyone is sent it
            com.quickskin.mod.networking.ServerSkinAssignments.playerJoined(serverPlayer);
            ProtocolNetwork.classifyServerPeer(serverPlayer);
            boolean hasQuickSkin = ProtocolNetwork.canReceive(serverPlayer);

            if (hasQuickSkin) {
                // Phase 3: Send all other players' appearances to the joining player
                ServerNetworkHandler.sendAllAppearancesToPlayer(serverPlayer);

                // Phase 9: Sync server config to client
                ServerNetworkHandler.sendServerConfigToPlayer(serverPlayer);

                // Send current cooldown status to joining player
                int cooldownSeconds = com.quickskin.mod.config.ServerConfig.getInstance().skinChangeCooldownSeconds;
                if (cooldownSeconds > 0
                        && ProtocolNetwork.canReceiveCooldown(serverPlayer)
                        && ServerCooldownManager.getInstance()
                                .isPlayerOnCooldown(player.getUUID())) {
                    long cooldownEndTime = ServerCooldownManager.getInstance().getCooldownEndTime(player.getUUID());
                    //? if <1.21 {
                    NetworkTransport.INSTANCE.sendCooldownToPlayer(serverPlayer, cooldownEndTime);
                    //?} else {
                    CooldownUpdatePayload payload = new CooldownUpdatePayload(cooldownEndTime);
                    NetworkTransport.INSTANCE.sendToPlayer(serverPlayer, payload);
                    //?}
                }
            }

            if (!hasQuickSkin) {
                // Tell them the server uses Quick Skin if their client still shows none later
                MissingModNotice.playerJoined(serverPlayer);
            }

            // CRITICAL FIX: Send THIS player's appearance to all OTHER players that have QuickSkin
            // This ensures that existing players (like the host) see the joining player's appearance
            // (sendAppearanceToAllPlayers internally checks canReceiveQuickSkin for each recipient)
            ServerNetworkHandler.sendAppearanceToAllPlayers(serverPlayer);

            // Vanilla's join packets named the player in the tab list; operators also see the
            // account name of whoever uses a nickname
            com.quickskin.mod.networking.ServerNicknames.playerJoined(serverPlayer);
        });

        // Player quits server
        PlayerEvent.PLAYER_QUIT.register(player -> {
            ServerPlayer serverPlayer = (ServerPlayer) player;
            serverRuntime.playerDisconnected(
                    serverPlayer.level().getServer(),
                    serverPlayer.getUUID(),
                    serverPlayer.connection);
        });

        // Player respawns (after death)
        //? if <1.21 {
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                if (ProtocolNetwork.canReceive(serverPlayer)) {
                    ServerNetworkHandler.sendAllAppearancesToPlayer(serverPlayer);
                }
            }
        });
        //?}

        // Player changes dimension
        PlayerEvent.CHANGE_DIMENSION.register((player, oldLevel, newLevel) -> {
            // Re-sync appearance if needed (sometimes skins don't transfer across dimensions)
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                if (ProtocolNetwork.canReceive(serverPlayer)) {
                    // Phase 3: Re-send all appearances to this player
                    ServerNetworkHandler.sendAllAppearancesToPlayer(serverPlayer);
                }
            }
        });

        TickEvent.SERVER_POST.register(ServerNetworkHandler::tickTextureResponses);
        TickEvent.SERVER_POST.register(MissingModNotice::tick);

        // Admin commands: /quickskin nickname ...
        CommandRegistrationEvent.EVENT.register(com.quickskin.mod.networking.QuickSkinCommands::register);

        // Server starting
        LifecycleEvent.SERVER_STARTING.register(server -> {
            serverRuntime.start(server);
        });

        // Server stopping
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            serverRuntime.prepareStop(server);
        });

        // Server stopped
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            serverRuntime.stop(server);
        });

        initialized = true;
    }
}
