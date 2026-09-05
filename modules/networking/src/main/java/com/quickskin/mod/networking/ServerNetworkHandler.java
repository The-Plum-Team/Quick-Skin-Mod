package com.quickskin.mod.networking;

import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.payloads.*;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
import com.quickskin.mod.server.concurrent.ServerTextureIngressExecutor;
import com.quickskin.mod.server.data.ServerCooldownManager;
import com.quickskin.mod.server.data.ServerAppearanceSyncCoordinator;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import com.quickskin.mod.server.data.ServerTextureResponseCoordinator;
import com.quickskin.mod.server.data.ServerUploadCoordinator;
import com.quickskin.mod.server.storage.ServerAnimationCache;
import com.quickskin.mod.server.storage.ServerTextureCache;
import com.quickskin.mod.server.storage.TextureChunkAssembler;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Server-side network packet handlers (Architectury 13.x for MC 1.21.1)
 * Handles all C2S (Client to Server) packets using CustomPacketPayload
 */
public class ServerNetworkHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerNetworkHandler.class);
    private static final ServerUploadCoordinator UPLOAD_COORDINATOR =
            ServerUploadCoordinator.getInstance();
    private static final ServerTextureResponseCoordinator RESPONSE_COORDINATOR =
            ServerTextureResponseCoordinator.getInstance();
    private static final ServerAppearanceSyncCoordinator APPEARANCE_COORDINATOR =
            ServerAppearanceSyncCoordinator.getInstance();
    private static final Deque<PacedTextureResponse> PACED_TEXTURE_RESPONSES =
            new ConcurrentLinkedDeque<>();
    private static final Deque<PreparedTextureUpload> PREPARED_TEXTURE_UPLOADS =
            new ConcurrentLinkedDeque<>();
    private static final int MAX_UPLOAD_COMMITS_PER_TICK = 4;
    private static final int MAX_RESPONSE_PACKETS_PER_TICK = 64;
    private static final int MAX_RESPONSE_BYTES_PER_TICK = 2 * 1024 * 1024;

    /**
     * Checks if a player's client has QuickSkin installed and can receive our packets.
     * Used to skip sending S2C packets to vanilla clients that don't have the mod.
     */
    private static boolean canReceiveQuickSkin(ServerPlayer player) {
        return ProtocolNetwork.canReceive(player);
    }

    /** Completes the initial server bootstrap only after a schema is explicitly selected. */
    private static void onProtocolReady(ServerPlayer player) {
        sendAllAppearancesToPlayer(player);
        sendServerConfigToPlayer(player);
        int cooldownSeconds = ServerConfig.getInstance().skinChangeCooldownSeconds;
        if (cooldownSeconds > 0
                && ServerCooldownManager.getInstance().isPlayerOnCooldown(player.getUUID())
                && ProtocolNetwork.canReceiveCooldown(player)) {
            NetworkTransport.INSTANCE.sendToPlayer(
                    player,
                    new CooldownUpdatePayload(
                            ServerCooldownManager.getInstance()
                                    .getCooldownEndTime(player.getUUID())));
        }
        sendAppearanceToAllPlayers(player);
    }

    private static boolean acceptLegacyPacket(
            ServerPlayer player, NetworkManager.PacketContext context) {
        ProtocolSessions.LegacyAdmission admission = ProtocolSessions.getInstance()
                .acceptLegacyClient(player.getUUID(), player.connection);
        if (admission.accepted() && admission.becameReady()) {
            MinecraftServer server = player.level().getServer();
            UUID playerId = player.getUUID();
            Object connection = player.connection;
            context.queue(() -> {
                ServerPlayer active = activePlayer(server, playerId, connection);
                if (active != null) onProtocolReady(active);
            });
        }
        return admission.accepted();
    }

    private static boolean acceptSharedPacket(
            ServerPlayer player, NetworkManager.PacketContext context) {
        ProtocolProfile profile = ProtocolNetwork.profile(player);
        return profile.negotiated() || acceptLegacyPacket(player, context);
    }

    /** Negotiates v2 before any v2 data packet is accepted. */
    public static void handleProtocolHello(
            ProtocolHelloPayload payload, NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || payload.nonce() <= 0L) return;
        MinecraftServer server = sender.level().getServer();
        UUID playerId = sender.getUUID();
        Object connection = sender.connection;
        // The authenticated hello is the ACK-channel evidence here: Forge/Architectury 1.20.1
        // can falsely report canPlayerReceive. Bound it before queueing instead of dropping v2.
        if (!TextureTransferRateLimiter.getInstance()
                .allowProtocolHello(playerId, connection)) return;
        context.queue(() -> {
            ServerPlayer player = activePlayer(server, playerId, connection);
            if (player == null) return;
            ProtocolSessions.ServerHelloResult result = ProtocolSessions.getInstance()
                    .acceptServerHello(playerId, connection, payload.nonce(), payload.offer());
            if (result.shouldAcknowledge()) {
                NetworkTransport.INSTANCE.sendToPlayer(
                        player,
                        new ProtocolAckPayload(result.nonce(), result.acknowledgement()));
            }
            if (result.becameReady()) onProtocolReady(player);
        });
    }

    public static void handleUpdateAppearanceV2(
            UpdateAppearanceV2Payload payload, NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !ProtocolNetwork.acceptsV2(sender)
                || !sender.getUUID().equals(payload.playerId())
                || !NetworkSecurity.isValidV2AppearanceId(payload.skinId(), "skin")
                || !NetworkSecurity.isValidV2AppearanceId(payload.capeId(), "cape")
                || !NetworkSecurity.isValidModel(payload.model())
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.level().getServer();
        Object connection = sender.connection;
        context.queue(() -> {
            ServerPlayer player = activePlayer(server, payload.playerId(), connection);
            if (player == null || !ProtocolNetwork.acceptsV2(player)) return;
            acceptOrDeferAppearance(
                    player,
                    new ServerUploadCoordinator.PendingAppearance(
                            payload.skinId(), payload.capeId(), payload.model()),
                    true);
        });
    }

    public static void handleRequestTextureV2(
            RequestTextureV2Payload payload, NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !ProtocolNetwork.acceptsV2(sender)
                || !sender.getUUID().equals(payload.playerId())
                || !NetworkSecurity.isValidTextureType(payload.textureType())
                || !NetworkSecurity.isValidStrongContentId(payload.contentId())
                || !TextureTransferRateLimiter.getInstance().allowTextureRequest(
                        sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.level().getServer();
        Object connection = sender.connection;
        context.queue(() -> {
            ServerPlayer player = activePlayer(server, payload.playerId(), connection);
            if (player == null || !ProtocolNetwork.acceptsV2(player)
                    || !ServerTextureCache.getInstance().isRequestable(
                            payload.contentId(), payload.textureType())) return;
            sendCachedTextureToClient(
                    player, payload.textureType(), payload.contentId());
        });
    }

    public static void handleTextureChunkV2(
            TextureChunkV2Payload payload, NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !ProtocolNetwork.acceptsV2(sender)
                || !NetworkSecurity.isValidTextureType(payload.textureType())
                || !NetworkSecurity.isValidStrongContentId(payload.contentId())) return;
        ProtocolProfile profile = ProtocolNetwork.profile(sender);
        byte[] chunkData = payload.chunkData();
        if (chunkData == null || chunkData.length > profile.maximumChunkBytes()
                || !TextureTransferRateLimiter.getInstance().allowUploadBytes(
                        sender.getUUID(), sender.connection, chunkData.length)) return;
        byte[] completeTexture = TextureChunkAssembler.getInstance().addChunk(
                sender.getUUID(), sender.connection, payload.textureType(), payload.contentId(),
                payload.chunkIndex(), payload.totalChunks(), chunkData,
                profile.maximumTextureBytes(), profile.maximumChunkBytes());
        if (completeTexture == null
                || !reserveDecodedPixels(sender, payload.textureType(), completeTexture)
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        submitTextureUpload(
                sender.level().getServer(), sender.getUUID(), sender.connection,
                payload.contentId(), payload.textureType(), completeTexture);
    }

    public static void handleUploadAnimationMetadataV2(
            UploadAnimationMetadataV2Payload payload,
            NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !ProtocolNetwork.acceptsV2(sender)
                || !ProtocolNetwork.profile(sender)
                        .supports(com.quickskin.mod.networking.protocol.ProtocolCapability.ANIMATION_METADATA)
                || !NetworkSecurity.isValidStrongContentId(payload.contentId())
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.level().getServer();
        UUID playerId = sender.getUUID();
        Object connection = sender.connection;
        context.queue(() -> {
            ServerPlayer player = activePlayer(server, playerId, connection);
            if (player == null || !ProtocolNetwork.acceptsV2(player)) return;
            acceptOrDeferAnimationMetadata(
                    player,
                    new ServerUploadCoordinator.PendingMetadata(
                            payload.contentId(), payload.metadataJson()),
                    true);
        });
    }

    /**
     * Handles skin/cape upload from client
     */
    public static void handleUploadTexture(UploadTexturePayload payload, NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        byte[] imageData = payload.imageData();
        if (sender == null || !acceptLegacyPacket(sender, context)
                || imageData == null || !sender.getUUID().equals(payload.playerId())
                || !NetworkSecurity.isValidTextureType(payload.textureType())
                || !TextureTransferRateLimiter.getInstance().allowUploadBytes(
                        sender.getUUID(), sender.connection, imageData.length)
                || !reserveDecodedPixels(sender, payload.textureType(), imageData)
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        submitTextureUpload(
                sender.level().getServer(),
                sender.getUUID(),
                sender.connection,
                null,
                payload.textureType(),
                imageData);
    }

    /**
     * Handles appearance update from client
     */
    public static void handleUpdateAppearance(UpdateAppearancePayload payload, NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !acceptLegacyPacket(sender, context)
                || !sender.getUUID().equals(payload.playerId())
                || !NetworkSecurity.isValidLegacyAppearanceId(payload.skinId(), "skin")
                || !NetworkSecurity.isValidLegacyAppearanceId(payload.capeId(), "cape")
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.level().getServer();
        Object connection = sender.connection;
        context.queue(() -> {
            ServerPlayer player = activePlayer(server, payload.playerId(), connection);

            if (player == null || !player.getUUID().equals(payload.playerId())) {
                return;
            }

            acceptOrDeferAppearance(
                    player,
                    new ServerUploadCoordinator.PendingAppearance(
                            payload.skinId(), payload.capeId(), payload.model()),
                    true);
        });
    }

    /**
     * Handles texture request from client
     */
    public static void handleRequestTexture(RequestTexturePayload payload, NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !acceptLegacyPacket(sender, context)
                || !sender.getUUID().equals(payload.playerId())
                || !TextureTransferRateLimiter.getInstance().allowTextureRequest(
                        sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.level().getServer();
        Object connection = sender.connection;
        context.queue(() -> {
            ServerPlayer player = activePlayer(server, payload.playerId(), connection);

            if (player == null || !player.getUUID().equals(payload.playerId())) {
                return;
            }

            if (!NetworkSecurity.isValidTextureType(payload.textureType())
                    || !NetworkSecurity.isValidLegacyContentId(payload.hash())
                    || !ServerTextureCache.getInstance().isRequestable(payload.hash(), payload.textureType())) {
                return;
            }

            // Phase 5: Load texture from server storage and send to client
            sendCachedTextureToClient(player, payload.textureType(), payload.hash());
        });
    }

    /**
     * Handles chunked texture upload from client
     */
    public static void handleTextureChunk(TextureChunkPayload payload, NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        byte[] chunkData = payload.chunkData();
        if (sender == null || !acceptLegacyPacket(sender, context) || chunkData == null
                || !NetworkSecurity.isValidTextureType(payload.textureType())
                || !NetworkSecurity.isValidLegacyContentId(payload.hash())
                || !TextureTransferRateLimiter.getInstance().allowUploadBytes(
                        sender.getUUID(), sender.connection, chunkData.length)) return;

        // The assembler is synchronized and bounded. Keeping its final large array copy off the
        // server thread prevents a completed maximum-size upload from stalling a tick.
        byte[] completeTexture = TextureChunkAssembler.getInstance().addChunk(
                sender.getUUID(), sender.connection, payload.textureType(), payload.hash(),
                payload.chunkIndex(), payload.totalChunks(), chunkData);
        if (completeTexture == null
                || !reserveDecodedPixels(sender, payload.textureType(), completeTexture)
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        submitTextureUpload(
                sender.level().getServer(),
                sender.getUUID(),
                sender.connection,
                payload.hash(),
                payload.textureType(),
                completeTexture);
    }

    /** Handles a retryable request for a complete, paced appearance roster. */
    public static void handleRequestAppearanceSnapshot(
            RequestAppearanceSnapshotPayload payload,
            NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !acceptSharedPacket(sender, context)
                || !ProtocolNetwork.canReceiveAppearanceSnapshotComplete(sender)
                || payload.requestId() <= 0L
                || !sender.getUUID().equals(payload.playerId())
                || !TextureTransferRateLimiter.getInstance()
                        .allowAppearanceSnapshotRequest(
                                sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.level().getServer();
        Object connection = sender.connection;
        context.queue(() -> {
            ServerPlayer player = activePlayer(server, payload.playerId(), connection);
            if (player == null || !canReceiveQuickSkin(player)) return;
            APPEARANCE_COORDINATOR.requestSnapshot(
                    player.getUUID(), connection, payload.requestId());
        });
    }

    private static boolean reserveDecodedPixels(
            ServerPlayer player, String textureType, byte[] textureData) {
        long pixels = NetworkSecurity.getTexturePixelCount(textureData, textureType);
        return pixels > 0 && TextureTransferRateLimiter.getInstance().allowDecodedPixels(
                player.getUUID(), player.connection, pixels);
    }

    /** Runs hash/decode/staging off-thread, then commits only for the original live session. */
    private static void submitTextureUpload(
            MinecraftServer server,
            UUID playerId,
            Object connection,
            String expectedHash,
            String textureType,
            byte[] textureData
    ) {
        ServerUploadCoordinator.UploadTicket ticket =
                UPLOAD_COORDINATOR.beginUpload(
                        playerId, connection, textureType, expectedHash, textureData.length);
        if (ticket == null) {
            LOGGER.debug("Rejected texture upload because the per-session ingress bound was reached");
            return;
        }
        boolean accepted = ServerTextureIngressExecutor.getInstance().submit(
                textureData.length,
                () -> {
                    ServerTextureCache.PreparedTexture prepared = null;
                    boolean completionScheduled = false;
                    try {
                        prepared = ServerTextureCache.getInstance().prepareTexture(
                                expectedHash, playerId, textureType, textureData);
                        if (prepared != null) {
                            UPLOAD_COORDINATOR.identifyUpload(ticket, prepared.hash());
                        }
                        if (UPLOAD_COORDINATOR.isCanceled(ticket)) {
                            if (prepared != null) prepared.close();
                            prepared = null;
                            UPLOAD_COORDINATOR.finishUpload(ticket);
                            completionScheduled = true;
                            return;
                        }
                        scheduleTextureUploadCompletion(
                                server, playerId, connection, ticket, prepared);
                        prepared = null;
                        completionScheduled = true;
                    } catch (RuntimeException | LinkageError error) {
                        LOGGER.warn("Texture ingress preparation failed", error);
                    } finally {
                        if (!completionScheduled) {
                            if (prepared != null) prepared.close();
                            scheduleTextureUploadCompletion(
                                    server, playerId, connection, ticket, null);
                        }
                    }
                }, () -> {
                    UPLOAD_COORDINATOR.removeSession(playerId, connection);
                    UPLOAD_COORDINATOR.finishUpload(ticket);
                });
        if (!accepted) {
            LOGGER.debug("Rejected texture upload because the bounded ingress queue is full");
            scheduleTextureUploadCompletion(
                    server, playerId, connection, ticket, null);
        }
    }

    private static void scheduleTextureUploadCompletion(
            MinecraftServer server,
            UUID playerId,
            Object connection,
            ServerUploadCoordinator.UploadTicket ticket,
            ServerTextureCache.PreparedTexture prepared
    ) {
        PreparedTextureUpload handoff = new PreparedTextureUpload(
                server, playerId, connection, ticket, prepared);
        PREPARED_TEXTURE_UPLOADS.addLast(handoff);
        if (UPLOAD_COORDINATOR.isCanceled(ticket)
                && PREPARED_TEXTURE_UPLOADS.remove(handoff)) {
            discardPreparedTextureUpload(handoff);
        }
    }

    private static void completeTextureUpload(
            MinecraftServer server,
            UUID playerId,
            Object connection,
            ServerUploadCoordinator.UploadTicket ticket,
            ServerTextureCache.PreparedTexture prepared
    ) {
        ServerPlayer player = activePlayer(server, playerId, connection);
        if (player == null || UPLOAD_COORDINATOR.isCanceled(ticket)) {
            if (prepared != null) prepared.close();
            UPLOAD_COORDINATOR.removeSession(playerId, connection);
            UPLOAD_COORDINATOR.finishUpload(ticket);
            return;
        }

        try {
            if (prepared != null) {
                try (prepared) {
                    ServerTextureCache.getInstance().storePreparedTexture(prepared);
                }
            }
        } catch (RuntimeException | LinkageError error) {
            LOGGER.warn("Unable to commit a prepared texture upload", error);
        } finally {
            retryDeferredPackets(player, UPLOAD_COORDINATOR.finishUpload(ticket));
        }
    }

    private static ServerPlayer activePlayer(
            MinecraftServer server, UUID playerId, Object connection) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && player.connection == connection ? player : null;
    }

    /**
     * Handles animation metadata upload from client
     */
    public static void handleUploadAnimationMetadata(UploadAnimationMetadataPayload payload, NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !acceptLegacyPacket(sender, context)
                || !NetworkSecurity.isValidLegacyContentId(payload.hash())
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.level().getServer();
        UUID playerId = sender.getUUID();
        Object connection = sender.connection;
        context.queue(() -> {
            ServerPlayer player = activePlayer(server, playerId, connection);

            if (player == null) {
                return;
            }

            acceptOrDeferAnimationMetadata(
                    player,
                    new ServerUploadCoordinator.PendingMetadata(
                            payload.hash(), payload.metadataJson()),
                    true);
        });
    }

    /**
     * Handles server config update from admin client
     */
    public static void handleUpdateServerConfig(UpdateServerConfigPayload payload, NetworkManager.PacketContext context) {
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !acceptSharedPacket(sender, context)
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.level().getServer();
        UUID playerId = sender.getUUID();
        Object connection = sender.connection;
        context.queue(() -> {
            ServerPlayer player = activePlayer(server, playerId, connection);

            if (player == null) {
                return;
            }

            // Check if player has admin permissions (operator level 2+)
            //? if <1.21.11 {
            if (!player.hasPermissions(2)) {
            //?} else {
            if (!player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
            //?}
                return;
            }

            // Update server config based on key
            com.quickskin.mod.config.ServerConfig serverConfig =
                com.quickskin.mod.config.ServerConfig.getInstance();

            switch (payload.key()) {
                case "disableSkinTransparency":
                    serverConfig.disableSkinTransparency = payload.value();
                    break;
                default:
                    return;
            }

            // Save config to disk
            serverConfig.save();

            // Broadcast config change to ALL clients (including the admin who made the change)
            broadcastServerConfigToAllPlayers(player.level().getServer());
        });
    }

    /**
     * Broadcasts a player's appearance and echoes the accepted state to its sender.
     */
    private static void broadcastAppearanceToPlayers(ServerPlayer player, String skinId, String capeId, String model) {
        // The coordinator coalesces per-target broadcasts and preserves an exact sender echo as
        // the upload acknowledgement without allowing a mass update to burst control packets.
        APPEARANCE_COORDINATOR.notifyAppearance(player.getUUID());

    }

    /**
     * Sends a player's appearance to a specific client
     * Used when players join or respawn
     */
    public static void sendAppearanceToPlayer(ServerPlayer recipient, UUID targetPlayerId) {
        if (!canReceiveQuickSkin(recipient)) {
            return;
        }

        PlayerAppearance appearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(targetPlayerId);

        if (appearance != null) {
            if (!NetworkSecurity.isValidLocalAppearanceId(appearance.getSkinId(), "skin")
                    || !NetworkSecurity.isValidLocalAppearanceId(appearance.getCapeId(), "cape")
                    || !NetworkSecurity.isValidModel(appearance.getModel())) {
                LOGGER.warn("Skipping invalid stored appearance for {}", targetPlayerId);
                return;
            }
            if (!ProtocolNetwork.sendAppearance(
                    recipient,
                    targetPlayerId,
                    appearance.getSkinId(),
                    appearance.getCapeId(),
                    appearance.getModel())) return;

            // Texture bytes are demand-driven: the appearance hash lets a cache-missing client
            // request only what it needs. Animation metadata is small and can accompany the hash.
            String capeId = appearance.getCapeId();
            if (capeId != null && capeId.startsWith("local_cape:")) {
                String hash = capeId.substring("local_cape:".length());
                String primary = ServerTextureCache.getInstance().resolveContentId(hash);
                String metadata = primary == null ? null
                        : ServerAnimationCache.getInstance().getMetadata(primary);
                if (metadata != null) {
                    ProtocolNetwork.sendAnimationMetadata(recipient, hash, metadata);
                }
            }

        }
    }

    /**
     * Sends all player appearances to a newly joined player
     */
    public static void sendAllAppearancesToPlayer(ServerPlayer player) {
        if (player == null || !canReceiveQuickSkin(player)) return;
        APPEARANCE_COORDINATOR.requestSnapshot(
                player.getUUID(), player.connection,
                ServerAppearanceSyncCoordinator.SERVER_INITIATED_REQUEST_ID);
    }

    /**
     * Sends a specific player's appearance to all OTHER players on the server
     * Used when a player joins to notify existing players of the new player's appearance
     */
    public static void sendAppearanceToAllPlayers(ServerPlayer player) {
        if (player != null
                && ServerPlayerAppearanceRepository.getInstance()
                        .hasAppearance(player.getUUID())) {
            APPEARANCE_COORDINATOR.notifyAppearance(player.getUUID());
        }
    }

    private static boolean sendCachedTextureToClient(
            ServerPlayer player, String textureType, String hash) {
        int size = ServerTextureCache.getInstance().getTextureSize(hash);
        ProtocolProfile profile = ProtocolNetwork.profile(player);
        if (!canReceiveQuickSkin(player) || !NetworkSecurity.isValidTextureType(textureType)
                || size <= 0 || size > profile.maximumTextureBytes()
                || size > TextureTransferLimits.MAX_TEXTURE_BYTES) return false;
        TextureTransferRateLimiter.DownloadReservation downloadReservation =
                TextureTransferRateLimiter.getInstance().reserveDownloadBytes(
                        player.getUUID(), player.connection, size);
        if (downloadReservation == null) return false;
        ServerTextureResponseCoordinator.ResponseTicket ticket =
                RESPONSE_COORDINATOR.reserve(player.getUUID(), player.connection, size);
        if (ticket == null) {
            TextureTransferRateLimiter.getInstance()
                    .refundDownloadBytes(downloadReservation);
            return false;
        }

        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        Object connection = player.connection;
        boolean accepted = ServerTextureIngressExecutor.getInstance().submit(size, () -> {
            PreparedTextureResponse response = null;
            try {
                byte[] textureData = ServerTextureCache.getInstance().getTexture(hash);
                if (textureData != null && textureData.length == size) {
                    response = prepareTextureResponse(
                            textureData, profile.maximumChunkBytes());
                }
            } catch (RuntimeException | LinkageError error) {
                LOGGER.warn("Unable to prepare a requested texture response", error);
            } finally {
                scheduleTextureResponse(
                        server, playerId, connection, textureType, hash,
                        ticket, downloadReservation, response);
            }
        }, () -> releaseTextureResponse(ticket, downloadReservation, false));
        if (!accepted) releaseTextureResponse(ticket, downloadReservation, false);
        return accepted;
    }

    private static PreparedTextureResponse prepareTextureResponse(
            byte[] textureData, int maximumChunkBytes) {
        int chunkBytes = Math.min(TextureTransferLimits.CHUNK_BYTES, maximumChunkBytes);
        if (chunkBytes < 1) return null;
        if (textureData.length <= TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES
                && textureData.length <= maximumChunkBytes) {
            return new PreparedTextureResponse(textureData, null);
        }
        int totalChunks = (textureData.length + chunkBytes - 1) / chunkBytes;
        if (totalChunks < 1 || totalChunks > TextureTransferLimits.MAX_CHUNKS) return null;
        byte[][] chunks = new byte[totalChunks][];
        for (int index = 0; index < totalChunks; index++) {
            int from = index * chunkBytes;
            int to = Math.min(textureData.length, from + chunkBytes);
            chunks[index] = Arrays.copyOfRange(textureData, from, to);
        }
        return new PreparedTextureResponse(null, chunks);
    }

    private static void scheduleTextureResponse(
            MinecraftServer server,
            UUID playerId,
            Object connection,
            String textureType,
            String hash,
            ServerTextureResponseCoordinator.ResponseTicket ticket,
            TextureTransferRateLimiter.DownloadReservation downloadReservation,
            PreparedTextureResponse response
    ) {
        if (response == null || RESPONSE_COORDINATOR.isCanceled(ticket)) {
            releaseTextureResponse(ticket, downloadReservation, false);
            return;
        }
        PacedTextureResponse handoff = new PacedTextureResponse(
                server, playerId, connection, textureType, hash,
                ticket, downloadReservation, response);
        PACED_TEXTURE_RESPONSES.addLast(handoff);
        if (RESPONSE_COORDINATOR.isCanceled(ticket)
                && PACED_TEXTURE_RESPONSES.remove(handoff)) {
            releaseTextureResponse(ticket, downloadReservation, false);
        }
    }

    /** Emits a bounded, round-robin slice of requested texture packets after each server tick. */
    public static void tickTextureResponses(MinecraftServer server) {
        tickAppearanceControls(server);
        drainPreparedTextureUploads(server);
        int packetsRemaining = MAX_RESPONSE_PACKETS_PER_TICK;
        int bytesRemaining = MAX_RESPONSE_BYTES_PER_TICK;
        while (packetsRemaining > 0 && bytesRemaining > 0
                && !PACED_TEXTURE_RESPONSES.isEmpty()) {
            PacedTextureResponse pending = PACED_TEXTURE_RESPONSES.removeFirst();
            ServerPlayer player = activePlayer(
                    server, pending.playerId, pending.connection);
            if (pending.server != server || player == null || !canReceiveQuickSkin(player)
                    || RESPONSE_COORDINATOR.isCanceled(pending.ticket)
                    || !ServerTextureCache.getInstance().isRequestable(
                            pending.hash, pending.textureType)) {
                releaseTextureResponse(
                        pending.ticket, pending.downloadReservation, pending.nextPacket > 0);
                continue;
            }

            byte[] packet = pending.packet();
            if (packet.length > bytesRemaining) {
                PACED_TEXTURE_RESPONSES.addFirst(pending);
                break;
            }
            try {
                sendPreparedTexturePacket(player, pending, packet);
            } catch (RuntimeException | LinkageError error) {
                releaseTextureResponse(
                        pending.ticket, pending.downloadReservation, pending.nextPacket > 0);
                LOGGER.warn("Unable to enqueue a paced texture response packet", error);
                continue;
            }
            pending.nextPacket++;
            packetsRemaining--;
            bytesRemaining -= packet.length;
            if (pending.isComplete()) {
                releaseTextureResponse(pending.ticket, pending.downloadReservation, true);
            } else {
                PACED_TEXTURE_RESPONSES.addLast(pending);
            }
        }
    }

    private static void tickAppearanceControls(MinecraftServer server) {
        ServerPlayerAppearanceRepository.AppearanceRoster roster =
                ServerPlayerAppearanceRepository.getInstance().snapshotRoster();
        var actions = APPEARANCE_COORDINATOR.tick(
                new ServerAppearanceSyncCoordinator.RosterView(
                        roster.revision(), roster.playerIds()));
        for (ServerAppearanceSyncCoordinator.Action action : actions) {
            ServerPlayer recipient = activePlayer(
                    server, action.playerId(), action.connection());
            if (recipient == null) {
                APPEARANCE_COORDINATOR.cancelSession(
                        action.playerId(), action.connection());
                continue;
            }
            try {
                if (action.type()
                        == ServerAppearanceSyncCoordinator.ActionType.APPEARANCE) {
                    if (!canReceiveQuickSkin(recipient)) {
                        APPEARANCE_COORDINATOR.cancelSession(
                                action.playerId(), action.connection());
                        continue;
                    }
                    sendAppearanceToPlayer(recipient, action.targetPlayerId());
                } else {
                    if (!ProtocolNetwork.canReceiveAppearanceSnapshotComplete(recipient)) {
                        continue;
                    }
                    NetworkTransport.INSTANCE.sendToPlayer(
                            recipient,
                            new AppearanceSnapshotCompletePayload(action.requestId()));
                }
            } catch (RuntimeException | LinkageError error) {
                if (action.type()
                        == ServerAppearanceSyncCoordinator.ActionType.APPEARANCE) {
                    APPEARANCE_COORDINATOR.retryAppearance(
                            action.playerId(), action.connection(),
                            action.targetPlayerId());
                }
                LOGGER.warn("Unable to enqueue a paced appearance control packet", error);
            }
        }
    }

    private static void drainPreparedTextureUploads(MinecraftServer server) {
        for (int count = 0; count < MAX_UPLOAD_COMMITS_PER_TICK; count++) {
            PreparedTextureUpload pending = PREPARED_TEXTURE_UPLOADS.pollFirst();
            if (pending == null) return;
            if (pending.server != server) {
                discardPreparedTextureUpload(pending);
                continue;
            }
            completeTextureUpload(
                    server, pending.playerId, pending.connection,
                    pending.ticket, pending.prepared);
        }
    }

    private static void discardPreparedTextureUploads(UUID playerId, Object connection) {
        for (PreparedTextureUpload pending : PREPARED_TEXTURE_UPLOADS) {
            if (pending.playerId.equals(playerId) && pending.connection == connection
                    && PREPARED_TEXTURE_UPLOADS.remove(pending)) {
                discardPreparedTextureUpload(pending);
            }
        }
    }

    private static void discardAllPreparedTextureUploads() {
        PreparedTextureUpload pending;
        while ((pending = PREPARED_TEXTURE_UPLOADS.pollFirst()) != null) {
            discardPreparedTextureUpload(pending);
        }
    }

    private static void discardPreparedTextureUpload(PreparedTextureUpload pending) {
        if (pending.prepared != null) pending.prepared.close();
        UPLOAD_COORDINATOR.finishUpload(pending.ticket);
    }

    private static void sendPreparedTexturePacket(
            ServerPlayer player, PacedTextureResponse pending, byte[] packet) {
        if (pending.response.direct != null) {
            if (!ProtocolNetwork.sendTexture(
                    player, pending.textureType, pending.hash, packet)) {
                throw new IllegalStateException("Protocol profile cannot receive texture");
            }
            return;
        }
        if (!ProtocolNetwork.sendTextureChunk(
                player, pending.hash, pending.textureType, pending.nextPacket,
                pending.response.chunks.length, packet)) {
            throw new IllegalStateException("Protocol profile cannot receive texture chunk");
        }
    }

    private static void releaseTextureResponse(
            ServerTextureResponseCoordinator.ResponseTicket ticket,
            TextureTransferRateLimiter.DownloadReservation downloadReservation,
            boolean emittedAnyPacket
    ) {
        RESPONSE_COORDINATOR.release(ticket);
        if (emittedAnyPacket) {
            TextureTransferRateLimiter.getInstance()
                    .commitDownloadBytes(downloadReservation);
        } else {
            TextureTransferRateLimiter.getInstance()
                    .refundDownloadBytes(downloadReservation);
        }
    }

    private static void discardPacedTextureResponses(UUID playerId, Object connection) {
        for (PacedTextureResponse pending : PACED_TEXTURE_RESPONSES) {
            if (pending.playerId.equals(playerId) && pending.connection == connection
                    && PACED_TEXTURE_RESPONSES.remove(pending)) {
                releaseTextureResponse(
                        pending.ticket, pending.downloadReservation, pending.nextPacket > 0);
            }
        }
    }

    private static void discardAllPacedTextureResponses() {
        while (!PACED_TEXTURE_RESPONSES.isEmpty()) {
            PacedTextureResponse pending = PACED_TEXTURE_RESPONSES.removeFirst();
            releaseTextureResponse(
                    pending.ticket, pending.downloadReservation, pending.nextPacket > 0);
        }
    }

    private record PreparedTextureResponse(byte[] direct, byte[][] chunks) {
    }

    private static final class PacedTextureResponse {
        private final MinecraftServer server;
        private final UUID playerId;
        private final Object connection;
        private final String textureType;
        private final String hash;
        private final ServerTextureResponseCoordinator.ResponseTicket ticket;
        private final TextureTransferRateLimiter.DownloadReservation downloadReservation;
        private final PreparedTextureResponse response;
        private int nextPacket;

        private PacedTextureResponse(
                MinecraftServer server,
                UUID playerId,
                Object connection,
                String textureType,
                String hash,
                ServerTextureResponseCoordinator.ResponseTicket ticket,
                TextureTransferRateLimiter.DownloadReservation downloadReservation,
                PreparedTextureResponse response
        ) {
            this.server = server;
            this.playerId = playerId;
            this.connection = connection;
            this.textureType = textureType;
            this.hash = hash;
            this.ticket = ticket;
            this.downloadReservation = downloadReservation;
            this.response = response;
        }

        private byte[] packet() {
            return response.direct != null ? response.direct : response.chunks[nextPacket];
        }

        private boolean isComplete() {
            return response.direct != null || nextPacket >= response.chunks.length;
        }
    }

    private record PreparedTextureUpload(
            MinecraftServer server,
            UUID playerId,
            Object connection,
            ServerUploadCoordinator.UploadTicket ticket,
            ServerTextureCache.PreparedTexture prepared) {
    }

    private static void acceptOrDeferAppearance(
            ServerPlayer player,
            ServerUploadCoordinator.PendingAppearance appearance,
            boolean supersedeOlder
    ) {
        if (!NetworkSecurity.isValidModel(appearance.model())
                || !NetworkSecurity.isValidLocalAppearanceId(appearance.skinId(), "skin")
                || !NetworkSecurity.isValidLocalAppearanceId(appearance.capeId(), "cape")) {
            LOGGER.warn("Rejected invalid appearance update from {}", player.getUUID());
            return;
        }
        if (supersedeOlder) {
            UPLOAD_COORDINATOR.supersedeAppearance(player.getUUID(), player.connection);
        }

        String missingSkinHash = missingOwnedTextureHash(
                player, appearance.skinId(), "local_skin:", "skin");
        String missingCapeHash = missingOwnedTextureHash(
                player, appearance.capeId(), "local_cape:", "cape");
        if (missingSkinHash != null || missingCapeHash != null) {
            if (UPLOAD_COORDINATOR.deferAppearance(
                    player.getUUID(), player.connection, appearance,
                    missingSkinHash, missingCapeHash)) {
                return;
            }
            LOGGER.warn("Rejected unauthorized appearance update from {}", player.getUUID());
            return;
        }

        applyAppearance(player, appearance);
    }

    private static void applyAppearance(
            ServerPlayer player, ServerUploadCoordinator.PendingAppearance appearance) {
        UUID playerId = player.getUUID();
        int cooldownSeconds = ServerConfig.getInstance().skinChangeCooldownSeconds;
        PlayerAppearance currentAppearance =
                ServerPlayerAppearanceRepository.getInstance().getAppearance(playerId);
        boolean isSkinChanging = appearance.skinId() != null
                && !appearance.skinId().isEmpty()
                && (currentAppearance == null
                        || !appearance.skinId().equals(currentAppearance.getSkinId()));

        if (isSkinChanging && cooldownSeconds > 0
                && ServerCooldownManager.getInstance().isPlayerOnCooldown(playerId)) return;

        if (!ServerPlayerAppearanceRepository.getInstance().tryUpdateAppearance(
                playerId, appearance.skinId(), appearance.capeId(), appearance.model())) {
            LOGGER.warn("Refused appearance update from {} because the global active-texture byte cap was reached",
                    playerId);
            sendAppearanceToPlayer(player, playerId);
            return;
        }

        if (isSkinChanging && cooldownSeconds > 0) {
            ServerCooldownManager.getInstance().recordSkinChange(playerId);
            long cooldownEndTime =
                    ServerCooldownManager.getInstance().getCooldownEndTime(playerId);
            if (ProtocolNetwork.canReceiveCooldown(player)) {
                NetworkTransport.INSTANCE.sendToPlayer(
                        player, new CooldownUpdatePayload(cooldownEndTime));
            }
        }
        // Peers receive only content hashes; cache misses use RequestTexturePayload.
        broadcastAppearanceToPlayers(
                player, appearance.skinId(), appearance.capeId(), appearance.model());
    }

    private static String missingOwnedTextureHash(
            ServerPlayer player, String appearanceId, String prefix, String textureType) {
        if (appearanceId == null || !appearanceId.startsWith(prefix)) return null;
        String hash = appearanceId.substring(prefix.length());
        return ServerTextureCache.getInstance().isOwnedBy(
                hash, player.getUUID(), textureType) ? null : hash;
    }

    private static void acceptOrDeferAnimationMetadata(
            ServerPlayer player,
            ServerUploadCoordinator.PendingMetadata metadata,
            boolean supersedeOlder
    ) {
        if (!NetworkSecurity.isValidContentId(metadata.hash())
                || !NetworkSecurity.isValidAnimationMetadata(metadata.metadataJson())) {
            LOGGER.warn("Rejected invalid animation metadata from {}", player.getUUID());
            return;
        }
        if (supersedeOlder) {
            UPLOAD_COORDINATOR.supersedeMetadata(
                    player.getUUID(), player.connection, metadata.hash());
        }

        String primary = ServerTextureCache.getInstance()
                .resolveContentId(metadata.hash());
        if (primary == null || !ServerTextureCache.getInstance().isOwnedBy(
                primary, player.getUUID(), "cape")) {
            if (UPLOAD_COORDINATOR.deferMetadata(
                    player.getUUID(), player.connection, metadata)) return;
            LOGGER.warn("Rejected unauthorized animation metadata from {}", player.getUUID());
            return;
        }
        if (!ServerTextureCache.getInstance().isAnimationMetadataCompatible(
                primary, metadata.metadataJson())) {
            LOGGER.warn("Rejected animation metadata that does not match its PNG identity from {}",
                    player.getUUID());
            return;
        }
        if (!ServerAnimationCache.getInstance().storeMetadata(
                primary, metadata.metadataJson(), player.getUUID())) {
            LOGGER.warn("Rejected animation metadata storage from {}", player.getUUID());
            return;
        }
        broadcastAnimationMetadataToOtherPlayers(
                player, primary, metadata.metadataJson());
    }

    private static void retryDeferredPackets(
            ServerPlayer player, ServerUploadCoordinator.RetryBatch batch) {
        // Metadata is committed/echoed first so its acknowledgement is causally ordered before
        // an appearance starts referencing the newly committed animated cape.
        for (ServerUploadCoordinator.PendingMetadata metadata : batch.metadata()) {
            acceptOrDeferAnimationMetadata(player, metadata, false);
        }
        if (batch.appearance() != null) {
            acceptOrDeferAppearance(player, batch.appearance(), false);
        }
    }

    public static void onPlayerDisconnected(UUID playerId, Object connection) {
        ProtocolSessions.getInstance().removeServerSession(playerId, connection);
        APPEARANCE_COORDINATOR.cancelSession(playerId, connection);
        RESPONSE_COORDINATOR.cancelSession(playerId, connection);
        discardPacedTextureResponses(playerId, connection);
        UPLOAD_COORDINATOR.removeSession(playerId, connection);
        discardPreparedTextureUploads(playerId, connection);
        TextureTransferRateLimiter.getInstance().removeSession(playerId, connection);
        TextureChunkAssembler.getInstance().discardSession(playerId, connection);
    }

    public static void clearTransientNetworkState() {
        ProtocolSessions.getInstance().clearServerSessions();
        APPEARANCE_COORDINATOR.cancelAll();
        RESPONSE_COORDINATOR.cancelAll();
        discardAllPacedTextureResponses();
        UPLOAD_COORDINATOR.clear();
        discardAllPreparedTextureUploads();
        TextureTransferRateLimiter.getInstance().clear();
        TextureChunkAssembler.getInstance().clear();
    }

    /**
     * Broadcasts animation metadata to all other players on the server
     */
    private static void broadcastAnimationMetadataToOtherPlayers(ServerPlayer player, String hash, String metadataJson) {
        // Echo the exact hash+JSON to acknowledge storage, and notify peers with the same packet.
        for (ServerPlayer recipient : player.level().getServer().getPlayerList().getPlayers()) {
            if (canReceiveQuickSkin(recipient)) {
                ProtocolNetwork.sendAnimationMetadata(recipient, hash, metadataJson);
            }
        }

    }

    /**
     * Sends server config to a specific player (called on player join)
     */
    public static void sendServerConfigToPlayer(ServerPlayer player) {
        if (!ProtocolNetwork.canReceiveServerConfig(player)) {
            return;
        }
        com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
        String configJson = serverConfig.toJson();

        SyncServerConfigPayload payload = new SyncServerConfigPayload(configJson);
        NetworkTransport.INSTANCE.sendToPlayer(player, payload);

    }

    /**
     * Broadcasts server config to ALL players on the server
     * Called when an admin changes a server setting
     */
    private static void broadcastServerConfigToAllPlayers(net.minecraft.server.MinecraftServer server) {
        com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
        String configJson = serverConfig.toJson();

        SyncServerConfigPayload payload = new SyncServerConfigPayload(configJson);

        // Send to all players that have QuickSkin (including the admin who made the change)
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ProtocolNetwork.canReceiveServerConfig(player)) {
                NetworkTransport.INSTANCE.sendToPlayer(player, payload);
            }
        }

    }
}
