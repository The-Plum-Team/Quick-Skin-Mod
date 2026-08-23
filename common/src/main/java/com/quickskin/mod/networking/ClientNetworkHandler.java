package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.storage.ClientAnimationMetadataCache;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.common.event.InternalEventBus;
import com.quickskin.mod.common.event.ServerConfigSyncEvent;
import com.quickskin.mod.networking.protocol.ProtocolAcknowledgement;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
//? if <1.21 {
import com.quickskin.mod.networking.packets.PacketHelper;
//?} else {
import com.quickskin.mod.networking.payloads.*;
//?}
import dev.architectury.networking.NetworkManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if <1.21 {
import net.minecraft.network.FriendlyByteBuf;
//?}

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side network packet handlers (Architectury 14.x for MC 1.21.3)
 * Handles all S2C (Server to Client) packets using CustomPacketPayload
 */
@Environment(EnvType.CLIENT)
public class ClientNetworkHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientNetworkHandler.class);
    private static final ConcurrentHashMap<String, PendingAnimation> PENDING_NETWORK_ANIMATIONS =
            new ConcurrentHashMap<>();

    // Flag to track if a texture reload is pending when GUI closes
    private static boolean pendingTransparencyReload = false;
    private static boolean appearanceBootstrapSent;
    private static long pendingTransparencyReloadAtMillis;
    private static long lastTransparencyReloadMillis;
    private static final long TRANSPARENCY_RELOAD_INTERVAL_MILLIS = 30_000L;

    /** Accepts only an acknowledgement tied to this exact live connection and hello nonce. */
    //? if <1.21 {
    public static void handleProtocolAck(
            FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        long nonce = buf.readLong();
        ProtocolAcknowledgement acknowledgement = new ProtocolAcknowledgement(
                buf.readBoolean(), buf.readInt(), buf.readLong(), buf.readInt(), buf.readInt());
    //?} else {
    public static void handleProtocolAck(
            ProtocolAckPayload payload, NetworkManager.PacketContext context) {
        long nonce = payload.nonce();
        ProtocolAcknowledgement acknowledgement = payload.acknowledgement();
    //?}
        Object sourceConnection = packetConnectionIdentity(context);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !ClientTextureIngressLimiter.getInstance().allowControlBytes(29)) return;
        Object packetPlayer = context.getPlayer();
        if (!(packetPlayer instanceof net.minecraft.client.player.LocalPlayer localPlayer)) return;
        ProtocolProfile acceptedProfile = ProtocolSessions.getInstance().acceptClientAcknowledgement(
                localPlayer.getUUID(), sourceConnection, nonce, acknowledgement);
        if (acceptedProfile.negotiated()) {
            context.queue(() -> {
                if (!isCurrentConnection(sourceConnection)) return;
                NetworkSyncService.getInstance().onProtocolAcknowledged(sourceConnection);
            });
        } else if (!"stale-protocol-ack".equals(acceptedProfile.reason())) {
            LOGGER.warn("QuickSkin protocol negotiation failed: {}", acceptedProfile.reason());
        }
    }

    /**
     * Handles appearance sync from server
     */
    //? if <1.21 {
    public static void handleSyncAppearance(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UUID playerId = PacketHelper.readPlayerId(buf);
        String skinId = PacketHelper.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
        String capeId = PacketHelper.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
        String model = PacketHelper.readString(buf, TextureTransferLimits.MAX_MODEL_BYTES);
        org.slf4j.LoggerFactory.getLogger("QuickSkin-CPM").info(
                "handleSyncAppearance: player={} skinId={} model={}", playerId, skinId, model);
    //?} else {
    public static void handleSyncAppearance(SyncAppearancePayload payload, NetworkManager.PacketContext context) {
    //?}
        Object sourceConnection = packetConnectionIdentity(context);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsWireMode(sourceConnection, false)) return;
        //? if <1.21 {
        if (!NetworkSecurity.isValidLegacyAppearanceId(skinId, "skin")
                || !NetworkSecurity.isValidLegacyAppearanceId(capeId, "cape")
                || !NetworkSecurity.isValidModel(model)
                || !ClientTextureIngressLimiter.getInstance()
                        .allowControlBytes(controlBytes(skinId, capeId, model))) return;
        //?} else {
        if (!NetworkSecurity.isValidLegacyAppearanceId(payload.skinId(), "skin")
                || !NetworkSecurity.isValidLegacyAppearanceId(payload.capeId(), "cape")
                || !NetworkSecurity.isValidModel(payload.model())
                || !ClientTextureIngressLimiter.getInstance().allowControlBytes(
                        controlBytes(payload.skinId(), payload.capeId(), payload.model()))) return;
        //?}
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)) return;
            //? if <1.21 {
            if (!NetworkSecurity.isValidLegacyAppearanceId(skinId, "skin")
                    || !NetworkSecurity.isValidLegacyAppearanceId(capeId, "cape")
                    || !NetworkSecurity.isValidModel(model)) return;
            //?} else {
            if (!NetworkSecurity.isValidLegacyAppearanceId(payload.skinId(), "skin")
                    || !NetworkSecurity.isValidLegacyAppearanceId(payload.capeId(), "cape")
                    || !NetworkSecurity.isValidModel(payload.model())) return;
            //?}
            //? if <1.21 {
            org.slf4j.LoggerFactory.getLogger("QuickSkin-CPM").info(
                    "handleSyncAppearance EXECUTING on main thread for {}", playerId);
            //?}
            Minecraft minecraft = Minecraft.getInstance();
            //? if <1.21 {
            boolean ownPlayerUpdate = minecraft.player != null
                    && playerId.equals(minecraft.player.getUUID());
            if (ownPlayerUpdate) {
                NetworkSyncService.getInstance().confirmAppearance(skinId, capeId, model);
            }
            //?} else {
            boolean ownPlayerUpdate = minecraft.player != null
                    && payload.playerId().equals(minecraft.player.getUUID());
            if (ownPlayerUpdate) {
                NetworkSyncService.getInstance().confirmAppearance(
                        payload.skinId(), payload.capeId(), payload.model());
            }
            //?}
            if (ownPlayerUpdate) return;
            // Apply appearance through service
            //? if <1.21 {
            PlayerAppearanceService.getInstance().applyLookFromNetwork(
                    playerId, skinId, capeId, model);
            //?} else {
            PlayerAppearanceService.getInstance().applyLookFromNetwork(
                payload.playerId(), payload.skinId(), payload.capeId(), payload.model()
            );
            //?}
        });
    }

    //? if <1.21 {
    public static void handleSyncAppearanceV2(
            FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UUID receivedPlayerId = PacketHelper.readPlayerId(buf);
        String receivedSkinId = PacketHelper.readString(
                buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
        String receivedCapeId = PacketHelper.readString(
                buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
        String receivedModel = PacketHelper.readString(buf, TextureTransferLimits.MAX_MODEL_BYTES);
    //?} else {
    public static void handleSyncAppearanceV2(
            SyncAppearanceV2Payload payload, NetworkManager.PacketContext context) {
        UUID receivedPlayerId = payload.playerId();
        String receivedSkinId = payload.skinId();
        String receivedCapeId = payload.capeId();
        String receivedModel = payload.model();
    //?}
        Object sourceConnection = packetConnectionIdentity(context);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsWireMode(sourceConnection, true)
                || !NetworkSecurity.isValidV2AppearanceId(receivedSkinId, "skin")
                || !NetworkSecurity.isValidV2AppearanceId(receivedCapeId, "cape")
                || !NetworkSecurity.isValidModel(receivedModel)
                || !ClientTextureIngressLimiter.getInstance().allowControlBytes(
                        controlBytes(receivedSkinId, receivedCapeId, receivedModel))) return;
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)
                    || !acceptsWireMode(sourceConnection, true)) return;
            Minecraft minecraft = Minecraft.getInstance();
            boolean ownPlayerUpdate = minecraft.player != null
                    && receivedPlayerId.equals(minecraft.player.getUUID());
            if (ownPlayerUpdate) {
                NetworkSyncService.getInstance().confirmAppearance(
                        receivedSkinId, receivedCapeId, receivedModel);
                return;
            }
            PlayerAppearanceService.getInstance().applyLookFromNetwork(
                    receivedPlayerId, receivedSkinId, receivedCapeId, receivedModel);
        });
    }

    /**
     * Handles texture data from server
     */
    //? if <1.21 {
    public static void handleSendTexture(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String textureType = PacketHelper.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        String hash = PacketHelper.readString(buf, TextureTransferLimits.CONTENT_ID_LENGTH);
        byte[] imageData = PacketHelper.readByteArray(buf, TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES);
        org.slf4j.LoggerFactory.getLogger("QuickSkin-CPM").info(
                "handleSendTexture: type={} hash={} size={}", textureType, hash, imageData.length);
    //?} else {
    public static void handleSendTexture(SendTexturePayload payload, NetworkManager.PacketContext context) {
    //?}
        Object sourceConnection = packetConnectionIdentity(context);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsWireMode(sourceConnection, false)) return;
        //? if <1.21 {
        if (!NetworkSecurity.isValidTextureType(textureType)
                || !NetworkSecurity.isValidLegacyContentId(hash)
                || !ClientTextureIngressLimiter.getInstance().allowWireBytes(imageData.length)) return;
        String receivedTextureType = textureType;
        String receivedHash = hash;
        byte[] receivedImageData = imageData;
        //?} else {
        if (!NetworkSecurity.isValidTextureType(payload.textureType())
                || !NetworkSecurity.isValidLegacyContentId(payload.hash())
                || !ClientTextureIngressLimiter.getInstance()
                        .allowWireBytes(payload.imageData().length)) return;
        String receivedTextureType = payload.textureType();
        String receivedHash = payload.hash();
        byte[] receivedImageData = payload.imageData();
        //?}
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)) return;
            //? if <1.21 {
            org.slf4j.LoggerFactory.getLogger("QuickSkin-CPM").info(
                    "handleSendTexture EXECUTING on main thread hash={}", hash);
            //?}
            var cache = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance();
            if (cache.containsTexture(receivedHash, receivedTextureType)) {
                onTextureStored(receivedTextureType, receivedHash);
                return;
            }
            if (!ClientTextureIngressLimiter.getInstance()
                    .allowDecode(receivedImageData, receivedTextureType)) return;
            long generation = cache.generation();
            ClientIoExecutor.supplyAsync(() -> cache.prepareTextureIfCurrent(
                            generation, receivedHash, receivedTextureType, receivedImageData))
                    .whenComplete((prepared, error) -> {
                        if (error != null) {
                            LOGGER.warn("Unable to process network texture {}", receivedHash, error);
                        } else if (prepared != null) {
                            Minecraft minecraft = Minecraft.getInstance();
                            if (minecraft != null) {
                                minecraft.execute(() -> {
                                    if (!isCurrentConnection(sourceConnection)) {
                                        cache.discardPreparedTexture(prepared);
                                        return;
                                    }
                                    if (cache.commitPreparedTextureIfCurrent(
                                            generation, receivedHash,
                                            receivedTextureType, prepared)) {
                                        onTextureStored(receivedTextureType, receivedHash);
                                    }
                                });
                            } else {
                                cache.discardPreparedTexture(prepared);
                            }
                        }
                    });
        });
    }

    //? if <1.21 {
    public static void handleSendTextureV2(
            FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String receivedTextureType = PacketHelper.readString(
                buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        String receivedHash = PacketHelper.readString(
                buf, TextureTransferLimits.MAX_CONTENT_ID_BYTES);
        byte[] receivedImageData = PacketHelper.readByteArray(
                buf, TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES);
    //?} else {
    public static void handleSendTextureV2(
            SendTextureV2Payload payload, NetworkManager.PacketContext context) {
        String receivedTextureType = payload.textureType();
        String receivedHash = payload.contentId();
        byte[] receivedImageData = payload.imageData();
    //?}
        Object sourceConnection = packetConnectionIdentity(context);
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(sourceConnection);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsWireMode(sourceConnection, true)
                || !NetworkSecurity.isValidTextureType(receivedTextureType)
                || !NetworkSecurity.isValidStrongContentId(receivedHash)
                || receivedImageData.length > profile.maximumTextureBytes()
                || !ClientTextureIngressLimiter.getInstance()
                        .allowWireBytes(receivedImageData.length)) return;
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)
                    || !acceptsWireMode(sourceConnection, true)) return;
            var cache = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance();
            if (cache.containsTexture(receivedHash, receivedTextureType)) {
                onTextureStored(receivedTextureType, receivedHash);
                return;
            }
            if (!ClientTextureIngressLimiter.getInstance()
                    .allowDecode(receivedImageData, receivedTextureType)) return;
            long generation = cache.generation();
            ClientIoExecutor.supplyAsync(() -> cache.prepareTextureIfCurrent(
                            generation, receivedHash, receivedTextureType, receivedImageData))
                    .whenComplete((prepared, error) -> {
                        if (error != null) {
                            LOGGER.warn("Unable to process v2 network texture {}", receivedHash, error);
                        } else if (prepared != null) {
                            Minecraft minecraft = Minecraft.getInstance();
                            if (minecraft == null) {
                                cache.discardPreparedTexture(prepared);
                                return;
                            }
                            minecraft.execute(() -> {
                                if (!isCurrentConnection(sourceConnection)
                                        || !acceptsWireMode(sourceConnection, true)) {
                                    cache.discardPreparedTexture(prepared);
                                    return;
                                }
                                if (cache.commitPreparedTextureIfCurrent(
                                        generation, receivedHash,
                                        receivedTextureType, prepared)) {
                                    onTextureStored(receivedTextureType, receivedHash);
                                }
                            });
                        }
                    });
        });
    }

    /**
     * Handles animation metadata from server
     */
    //? if <1.21 {
    public static void handleSendAnimationMetadata(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String hash = PacketHelper.readString(buf, TextureTransferLimits.CONTENT_ID_LENGTH);
        String metadataJson = PacketHelper.readString(buf, TextureTransferLimits.MAX_JSON_BYTES);
    //?} else {
    public static void handleSendAnimationMetadata(SendAnimationMetadataPayload payload, NetworkManager.PacketContext context) {
    //?}
        //? if <1.21 {
        String receivedHash = hash;
        String receivedMetadataJson = metadataJson;
        //?} else {
        String receivedHash = payload.hash();
        String receivedMetadataJson = payload.metadataJson();
        //?}
        Object sourceConnection = packetConnectionIdentity(context);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsWireMode(sourceConnection, false)) return;
        ClientAnimationMetadataCache metadataCache = ClientAnimationMetadataCache.getInstance();
        NetworkSyncService syncService = NetworkSyncService.getInstance();
        boolean possibleUploadAck = syncService.hasPendingMetadata(
                receivedHash, receivedMetadataJson);
        if (!NetworkSecurity.isValidLegacyContentId(receivedHash)) return;
        if (!ClientTextureIngressLimiter.getInstance().allowControlBytes(
                receivedMetadataJson.getBytes(StandardCharsets.UTF_8).length)) return;
        AnimationMetadata receivedMetadata =
                NetworkSecurity.parseAnimationMetadata(receivedMetadataJson);
        if (receivedMetadata == null) return;
        boolean exactReplay = metadataCache.matchesMetadata(receivedHash, receivedMetadata);
        if (exactReplay && !possibleUploadAck) return;
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)) return;
            syncService.confirmMetadata(receivedHash, receivedMetadataJson);
            if (metadataCache.matchesMetadata(receivedHash, receivedMetadata)) return;
            try {
                String animationId = "cape_" + receivedHash;
                PENDING_NETWORK_ANIMATIONS.remove(animationId);
                AnimatedTextureManager.getInstance().unregisterAnimation(animationId);
                metadataCache.storeMetadata(receivedHash, receivedMetadata);
                registerNetworkCapeAnimation(receivedHash, receivedMetadata);
                refreshPlayersUsingTexture(receivedHash);
            } catch (Exception e) {
                LOGGER.warn("Unable to store network animation metadata", e);
            }
        });
    }

    //? if <1.21 {
    public static void handleSendAnimationMetadataV2(
            FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String receivedHash = PacketHelper.readString(
                buf, TextureTransferLimits.MAX_CONTENT_ID_BYTES);
        String receivedMetadataJson = PacketHelper.readString(
                buf, TextureTransferLimits.MAX_JSON_BYTES);
    //?} else {
    public static void handleSendAnimationMetadataV2(
            SendAnimationMetadataV2Payload payload, NetworkManager.PacketContext context) {
        String receivedHash = payload.contentId();
        String receivedMetadataJson = payload.metadataJson();
    //?}
        Object sourceConnection = packetConnectionIdentity(context);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsWireMode(sourceConnection, true)
                || !ProtocolSessions.getInstance().clientProfile(sourceConnection)
                        .supports(ProtocolCapability.ANIMATION_METADATA)
                || !NetworkSecurity.isValidStrongContentId(receivedHash)
                || !ClientTextureIngressLimiter.getInstance().allowControlBytes(
                        receivedMetadataJson.getBytes(StandardCharsets.UTF_8).length)) return;
        ClientAnimationMetadataCache metadataCache = ClientAnimationMetadataCache.getInstance();
        NetworkSyncService syncService = NetworkSyncService.getInstance();
        boolean possibleUploadAck = syncService.hasPendingMetadata(
                receivedHash, receivedMetadataJson);
        AnimationMetadata receivedMetadata =
                NetworkSecurity.parseAnimationMetadata(receivedMetadataJson);
        if (receivedMetadata == null) return;
        boolean exactReplay = metadataCache.matchesMetadata(receivedHash, receivedMetadata);
        if (exactReplay && !possibleUploadAck) return;
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)
                    || !acceptsWireMode(sourceConnection, true)) return;
            syncService.confirmMetadata(receivedHash, receivedMetadataJson);
            if (metadataCache.matchesMetadata(receivedHash, receivedMetadata)) return;
            try {
                String animationId = "cape_" + receivedHash;
                PENDING_NETWORK_ANIMATIONS.remove(animationId);
                AnimatedTextureManager.getInstance().unregisterAnimation(animationId);
                metadataCache.storeMetadata(receivedHash, receivedMetadata);
                registerNetworkCapeAnimation(receivedHash, receivedMetadata);
                refreshPlayersUsingTexture(receivedHash);
            } catch (RuntimeException error) {
                LOGGER.warn("Unable to store v2 network animation metadata", error);
            }
        });
    }

    /**
     * Registers animation for a network-received cape
     */
    private static void registerNetworkCapeAnimation(String hash, AnimationMetadata metadata) {
        if (metadata == null || !NetworkSecurity.isValidContentId(hash)) return;
        String animationId = "cape_" + hash;
        AnimatedTextureManager animManager = AnimatedTextureManager.getInstance();
        if (animManager.isAnimated(animationId)
                || PENDING_NETWORK_ANIMATIONS.containsKey(animationId)) return;
        var cache = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance();
        if (!cache.containsTexture(hash, "cape")
                || !NetworkSecurity.isValidAnimationMetadata(metadata.toJson())) return;
        Object sourceConnection = currentConnectionIdentity();
        if (sourceConnection == null || PENDING_NETWORK_ANIMATIONS.size() >= 32) return;
        long generation = cache.generation();
        PendingAnimation pending = new PendingAnimation(new Object(), sourceConnection, generation);
        if (PENDING_NETWORK_ANIMATIONS.putIfAbsent(animationId, pending) != null) return;
        ClientIoExecutor.supplyAsync(() -> {
            if (!isCurrentConnection(sourceConnection)
                    || cache.generation() != generation
                    || PENDING_NETWORK_ANIMATIONS.get(animationId) != pending) return null;
            byte[] textureData = cache.getTextureData(hash, "cape");
            if (textureData == null || !isCurrentConnection(sourceConnection)
                    || cache.generation() != generation
                    || PENDING_NETWORK_ANIMATIONS.get(animationId) != pending
                    || !ClientTextureIngressLimiter.getInstance()
                            .allowDecode(textureData, "cape")) return null;
            if (!isCurrentConnection(sourceConnection)
                    || cache.generation() != generation
                    || PENDING_NETWORK_ANIMATIONS.get(animationId) != pending) return null;
            try {
                return SafeImageReader.readPng(textureData);
            } catch (java.io.IOException error) {
                throw new IllegalArgumentException("Invalid network animation atlas", error);
            }
        }).whenComplete((atlasImage, error) -> {
            if (error != null) {
                PENDING_NETWORK_ANIMATIONS.remove(animationId, pending);
                LOGGER.warn("Unable to decode network animation {}", hash, error);
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                PENDING_NETWORK_ANIMATIONS.remove(animationId, pending);
                return;
            }
            minecraft.execute(() -> {
                try {
                    if (atlasImage == null || !isCurrentConnection(sourceConnection)
                            || PENDING_NETWORK_ANIMATIONS.get(animationId) != pending
                            || cache.generation() != generation
                            || !cache.containsTexture(hash, "cape")
                            || animManager.isAnimated(animationId)) return;
                    //? if <1.21.11 {
                    net.minecraft.resources.ResourceLocation textureLocation =
                    //?} else {
                    net.minecraft.resources.Identifier textureLocation =
                    //?}
                            cache.getTextureLocation(hash, "cape");
                    if (textureLocation != null) {
                        animManager.registerAnimationAsync(
                                animationId, "local_cape:" + hash,
                                textureLocation, atlasImage, metadata);
                    }
                } finally {
                    PENDING_NETWORK_ANIMATIONS.remove(animationId, pending);
                }
            });
        });
    }

    /**
     * Refreshes all players using the specified texture
     */
    private static void refreshPlayersUsingTexture(String hash) {
        // The animation will be picked up automatically when CapeService loads the cape
        // No need to manually refresh - CapeService.loadLocalCape() now checks for network animations
    }

    /**
     * Handles server config sync (server sends full config to client on join)
     */
    //? if <1.21 {
    public static void handleSyncServerConfig(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String configJson = PacketHelper.readString(buf, TextureTransferLimits.MAX_JSON_BYTES);
    //?} else {
    public static void handleSyncServerConfig(SyncServerConfigPayload payload, NetworkManager.PacketContext context) {
    //?}
        //? if <1.21 {
        String receivedConfigJson = configJson;
        //?} else {
        String receivedConfigJson = payload.configJson();
        //?}
        Object sourceConnection = packetConnectionIdentity(context);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsReadyMode(sourceConnection)) return;
        if (!ClientTextureIngressLimiter.getInstance().allowConfigPacket()
                || !ClientTextureIngressLimiter.getInstance().allowControlBytes(
                receivedConfigJson.getBytes(StandardCharsets.UTF_8).length)) return;
        com.quickskin.mod.config.ServerConfig receivedServerConfig =
                com.quickskin.mod.config.ServerConfig.fromJson(receivedConfigJson);
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)) return;
            // Get current server override to detect changes
            com.quickskin.mod.config.ClientConfig clientConfig = com.quickskin.mod.config.ClientConfig.getInstance();
            com.quickskin.mod.config.ServerConfig oldServerConfig = clientConfig.getServerOverride();
            boolean oldTransparencySetting = oldServerConfig != null && oldServerConfig.disableSkinTransparency;

            // Parse server config from JSON
            com.quickskin.mod.config.ServerConfig serverConfig = receivedServerConfig;

            boolean newTransparencySetting = serverConfig.disableSkinTransparency;

            boolean firstConfig = oldServerConfig == null;
            boolean policyChanged = oldTransparencySetting != newTransparencySetting;
            long now = System.currentTimeMillis();
            boolean configChanged = firstConfig || policyChanged
                    || oldServerConfig.skinChangeCooldownSeconds
                            != serverConfig.skinChangeCooldownSeconds;
            if (configChanged) {
                clientConfig.applyServerOverride(serverConfig);
            }
            if (firstConfig || policyChanged) {
                InternalEventBus.getInstance().post(
                    new ServerConfigSyncEvent(
                        !serverConfig.disableSkinTransparency // allowTransparent
                    )
                );
            }

            if (policyChanged) {
                requestTransparencyReload(now);
            }

            // Bootstrap exactly once per connection; config replays must not re-upload assets.
            Minecraft mc = Minecraft.getInstance();
            if (!appearanceBootstrapSent && mc.player != null) {
                UUID playerId = mc.player.getUUID();
                com.quickskin.mod.common.data.PlayerAppearance currentAppearance =
                    com.quickskin.mod.common.data.PlayerAppearanceRepository.getInstance().getAppearance(playerId);

                if (currentAppearance != null) {
                    appearanceBootstrapSent = true;
                    NetworkSyncService.getInstance().syncAppearance(
                        playerId,
                        currentAppearance.getSkinId(),
                        currentAppearance.getCapeId(),
                        currentAppearance.getModel()
                    );
                }
            }
        });
    }

    /**
     * Checks if there's a pending transparency reload and executes it
     * Should be called when the settings GUI closes
     */
    public static void executePendingTransparencyReload() {
        executeDueTransparencyReload(System.currentTimeMillis());
    }

    /** Called from the client tick to coalesce hostile/replayed policy toggles. */
    public static void tick() {
        executeDueTransparencyReload(System.currentTimeMillis());
    }

    /** Drops UI work deferred by the server connection that is being closed. */
    public static void clearTransientState() {
        pendingTransparencyReload = false;
        pendingTransparencyReloadAtMillis = 0L;
        lastTransparencyReloadMillis = 0L;
        appearanceBootstrapSent = false;
        PENDING_NETWORK_ANIMATIONS.clear();
    }

    /**
     * Handles texture chunk from server (for large textures)
     */
    //? if <1.21 {
    public static void handleSendTextureChunk(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String hash = buf.readUtf(TextureTransferLimits.CONTENT_ID_LENGTH);
        String textureType = buf.readUtf(TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        int chunkIndex = buf.readInt();
        int totalChunks = buf.readInt();
        byte[] chunkData = buf.readByteArray(TextureTransferLimits.MAX_WIRE_CHUNK_BYTES);
    //?} else {
    public static void handleSendTextureChunk(SendTextureChunkPayload payload, NetworkManager.PacketContext context) {
    //?}
        Object sourceConnection = packetConnectionIdentity(context);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsWireMode(sourceConnection, false)) return;
        //? if <1.21 {
        if (!NetworkSecurity.isValidTextureType(textureType)
                || !NetworkSecurity.isValidLegacyContentId(hash)
                || !ClientTextureIngressLimiter.getInstance().allowWireBytes(chunkData.length)) return;
        //?} else {
        if (!NetworkSecurity.isValidTextureType(payload.textureType())
                || !NetworkSecurity.isValidLegacyContentId(payload.hash())
                || !ClientTextureIngressLimiter.getInstance()
                        .allowWireBytes(payload.chunkData().length)) return;
        //?}
        //? if <1.21 {
        String receivedHash = hash;
        String receivedTextureType = textureType;
        int receivedChunkIndex = chunkIndex;
        int receivedTotalChunks = totalChunks;
        byte[] receivedChunkData = chunkData;
        //?} else {
        String receivedHash = payload.hash();
        String receivedTextureType = payload.textureType();
        int receivedChunkIndex = payload.chunkIndex();
        int receivedTotalChunks = payload.totalChunks();
        byte[] receivedChunkData = payload.chunkData();
        //?}
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)) return;
            // Use TextureChunkReceiver to assemble chunks
            com.quickskin.mod.client.storage.TextureChunkReceiver.getInstance()
                .receiveChunk(receivedHash, receivedTextureType, receivedChunkIndex,
                        receivedTotalChunks, receivedChunkData, sourceConnection);
        });
    }

    //? if <1.21 {
    public static void handleSendTextureChunkV2(
            FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String receivedHash = buf.readUtf(TextureTransferLimits.MAX_CONTENT_ID_BYTES);
        String receivedTextureType = buf.readUtf(TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        int receivedChunkIndex = buf.readInt();
        int receivedTotalChunks = buf.readInt();
        byte[] receivedChunkData = buf.readByteArray(TextureTransferLimits.MAX_WIRE_CHUNK_BYTES);
    //?} else {
    public static void handleSendTextureChunkV2(
            SendTextureChunkV2Payload payload, NetworkManager.PacketContext context) {
        String receivedHash = payload.contentId();
        String receivedTextureType = payload.textureType();
        int receivedChunkIndex = payload.chunkIndex();
        int receivedTotalChunks = payload.totalChunks();
        byte[] receivedChunkData = payload.chunkData();
    //?}
        Object sourceConnection = packetConnectionIdentity(context);
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(sourceConnection);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsWireMode(sourceConnection, true)
                || !NetworkSecurity.isValidTextureType(receivedTextureType)
                || !NetworkSecurity.isValidStrongContentId(receivedHash)
                || receivedChunkData.length > profile.maximumChunkBytes()
                || !ClientTextureIngressLimiter.getInstance()
                        .allowWireBytes(receivedChunkData.length)) return;
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)
                    || !acceptsWireMode(sourceConnection, true)) return;
            com.quickskin.mod.client.storage.TextureChunkReceiver.getInstance().receiveChunk(
                    receivedHash, receivedTextureType, receivedChunkIndex,
                    receivedTotalChunks, receivedChunkData, sourceConnection,
                    profile.maximumTextureBytes(), profile.maximumChunkBytes());
        });
    }

    /** Completes request bookkeeping and retries metadata that arrived before async decode. */
    public static void onTextureStored(String textureType, String hash) {
        if (!NetworkSecurity.isValidTextureType(textureType)
                || !NetworkSecurity.isValidContentId(hash)) return;
        TextureRequestCoordinator.getInstance().markFulfilled(textureType, hash);
        if ("cape".equals(textureType)) {
            AnimationMetadata metadata =
                    ClientAnimationMetadataCache.getInstance().getMetadata(hash);
            if (metadata != null) registerNetworkCapeAnimation(hash, metadata);
        }
    }

    /**
     * Handles cooldown update from server
     */
    //? if <1.21 {
    public static void handleCooldownUpdate(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        long cooldownEndTime = buf.readLong();
        //?} else {
    public static void handleCooldownUpdate(CooldownUpdatePayload payload, NetworkManager.PacketContext context) {
    //?}
        Object sourceConnection = packetConnectionIdentity(context);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsReadyMode(sourceConnection)) return;
        if (!ClientTextureIngressLimiter.getInstance().allowControlBytes(Long.BYTES)) return;
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)) return;
            //? if <1.21 {
            com.quickskin.mod.client.services.CooldownService.getInstance().setCooldownEndTime(cooldownEndTime);
            //?} else {
            com.quickskin.mod.client.services.CooldownService.getInstance().setCooldownEndTime(payload.cooldownEndTime());
            //?}
        });
    }

    /** Completes only the exact retryable appearance snapshot request for this connection. */
    //? if <1.21 {
    public static void handleAppearanceSnapshotComplete(
            FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        long requestId = buf.readLong();
    //?} else {
    public static void handleAppearanceSnapshotComplete(
            AppearanceSnapshotCompletePayload payload,
            NetworkManager.PacketContext context) {
    //?}
        Object sourceConnection = packetConnectionIdentity(context);
        if (sourceConnection == null || !isCurrentConnection(sourceConnection)
                || !acceptsReadyMode(sourceConnection)
                || !ClientTextureIngressLimiter.getInstance()
                        .allowControlBytes(Long.BYTES)) return;
        context.queue(() -> {
            if (!isCurrentConnection(sourceConnection)) return;
            //? if <1.21 {
            NetworkSyncService.getInstance().confirmAppearanceSnapshot(
                    sourceConnection, requestId);
            //?} else {
            NetworkSyncService.getInstance().confirmAppearanceSnapshot(
                    sourceConnection, payload.requestId());
            //?}
        });
    }

    private static int controlBytes(String... values) {
        int total = 0;
        for (String value : values) {
            if (value == null) continue;
            total = Math.min(TextureTransferLimits.MAX_JSON_BYTES,
                    total + value.getBytes(StandardCharsets.UTF_8).length);
        }
        return total;
    }

    private static void requestTransparencyReload(long now) {
        long earliest = lastTransparencyReloadMillis <= 0L
                ? now
                : lastTransparencyReloadMillis + TRANSPARENCY_RELOAD_INTERVAL_MILLIS;
        pendingTransparencyReload = true;
        pendingTransparencyReloadAtMillis = Math.max(now, earliest);
        executeDueTransparencyReload(now);
    }

    private static void executeDueTransparencyReload(long now) {
        if (!pendingTransparencyReload || now < pendingTransparencyReloadAtMillis
                || isSettingsScreenOpen()) return;
        pendingTransparencyReload = false;
        pendingTransparencyReloadAtMillis = 0L;
        lastTransparencyReloadMillis = now;
        PlayerAppearanceService.getInstance().reloadSkinsForTransparencyChange();
    }

    private static boolean isSettingsScreenOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return false;
        //? if <26.2 {
        return minecraft.screen instanceof com.quickskin.mod.client.gui.screen.SettingsScreen;
        //?} else {
        return minecraft.gui.screen() instanceof com.quickskin.mod.client.gui.screen.SettingsScreen;
        //?}
    }

    private static Object currentConnectionIdentity() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getConnection();
    }

    private static Object packetConnectionIdentity(NetworkManager.PacketContext context) {
        if (context instanceof ExplicitConnectionPacketContext explicit) {
            return explicit.connectionIdentity();
        }
        Object player = context != null ? context.getPlayer() : null;
        if (player instanceof net.minecraft.client.player.LocalPlayer localPlayer) {
            return localPlayer.connection;
        }
        return null;
    }

    /** Packet context used only by compatibility bridges that own an exact fake connection. */
    public interface ExplicitConnectionPacketContext extends NetworkManager.PacketContext {
        Object connectionIdentity();
    }

    public static boolean isCurrentConnection(Object expectedConnection) {
        return expectedConnection != null && currentConnectionIdentity() == expectedConnection;
    }

    private static boolean acceptsWireMode(Object connection, boolean v2) {
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(connection);
        if (v2) {
            return profile.negotiated()
                    && profile.version() == 2
                    && profile.supports(ProtocolCapability.SHA256_CONTENT_IDS);
        }
        return profile.mode() == ProtocolProfile.Mode.LEGACY_V1;
    }

    private static boolean acceptsReadyMode(Object connection) {
        ProtocolProfile.Mode mode = ProtocolSessions.getInstance()
                .clientProfile(connection).mode();
        return mode == ProtocolProfile.Mode.LEGACY_V1
                || mode == ProtocolProfile.Mode.NEGOTIATED;
    }

    private record PendingAnimation(Object token, Object connection, long generation) {
    }
}
