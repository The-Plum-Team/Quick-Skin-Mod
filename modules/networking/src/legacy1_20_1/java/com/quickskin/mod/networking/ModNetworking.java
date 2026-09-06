package com.quickskin.mod.networking;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.networking.packets.PacketHelper;
import com.quickskin.mod.networking.protocol.ProtocolAcknowledgement;
import com.quickskin.mod.networking.protocol.ProtocolOffer;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Central networking registry for QuickSkin
 * Defines all packet IDs used by the mod
 */
public class ModNetworking implements NetworkTransport {

    // Client to Server packets (C2S)
    public static final ResourceLocation UPLOAD_SKIN =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "upload_skin");

    public static final ResourceLocation UPLOAD_CAPE =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "upload_cape");

    public static final ResourceLocation UPDATE_APPEARANCE =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "update_appearance");

    public static final ResourceLocation REQUEST_TEXTURE =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "request_texture");

    public static final ResourceLocation REQUEST_APPEARANCE_SNAPSHOT =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "request_appearance_snapshot");

    public static final ResourceLocation TEXTURE_CHUNK =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "texture_chunk");

    public static final ResourceLocation UPLOAD_ANIMATION_METADATA =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "upload_animation_metadata");

    public static final ResourceLocation UPDATE_SERVER_CONFIG =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "update_server_config");

    // Negotiation and hash-bearing v2 packets. Historical IDs above remain unchanged.
    public static final ResourceLocation PROTOCOL_HELLO =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "protocol_hello");
    public static final ResourceLocation UPDATE_APPEARANCE_V2 =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "update_appearance_v2");
    public static final ResourceLocation REQUEST_TEXTURE_V2 =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "request_texture_v2");
    public static final ResourceLocation TEXTURE_CHUNK_V2 =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "texture_chunk_v2");
    public static final ResourceLocation UPLOAD_ANIMATION_METADATA_V2 =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "upload_animation_metadata_v2");

    // Server to Client packets (S2C)
    public static final ResourceLocation SYNC_APPEARANCE =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "sync_appearance");

    public static final ResourceLocation SEND_TEXTURE =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "send_texture");

    public static final ResourceLocation SEND_TEXTURE_CHUNK =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "send_texture_chunk");

    public static final ResourceLocation SEND_ANIMATION_METADATA =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "send_animation_metadata");

    public static final ResourceLocation SYNC_SERVER_CONFIG =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "sync_server_config");

    public static final ResourceLocation COOLDOWN_UPDATE =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "cooldown_update");

    public static final ResourceLocation APPEARANCE_SNAPSHOT_COMPLETE =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "appearance_snapshot_complete");

    public static final ResourceLocation PROTOCOL_ACK =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "protocol_ack");
    public static final ResourceLocation SYNC_APPEARANCE_V2 =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "sync_appearance_v2");
    public static final ResourceLocation SEND_TEXTURE_V2 =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "send_texture_v2");
    public static final ResourceLocation SEND_TEXTURE_CHUNK_V2 =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "send_texture_chunk_v2");
    public static final ResourceLocation SEND_ANIMATION_METADATA_V2 =
        new ResourceLocation(QuickSkinInfo.MOD_ID, "send_animation_metadata_v2");

    /**
     * Initializes networking (registers server-side receivers)
     * Called from QuickSkin.init() on both client and server
     */
    @Override
    public void init() {
        // Register server-side packet receivers (C2S)
        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UPLOAD_SKIN,
            ServerNetworkHandler::handleUploadTexture
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UPLOAD_CAPE,
            ServerNetworkHandler::handleUploadTexture
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UPDATE_APPEARANCE,
            ServerNetworkHandler::handleUpdateAppearance
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            REQUEST_TEXTURE,
            ServerNetworkHandler::handleRequestTexture
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            REQUEST_APPEARANCE_SNAPSHOT,
            ServerNetworkHandler::handleRequestAppearanceSnapshot
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UPLOAD_ANIMATION_METADATA,
            ServerNetworkHandler::handleUploadAnimationMetadata
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            TEXTURE_CHUNK,
            ServerNetworkHandler::handleTextureChunk
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UPDATE_SERVER_CONFIG,
            ServerNetworkHandler::handleUpdateServerConfig
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(), PROTOCOL_HELLO, ServerNetworkHandler::handleProtocolHello);
        NetworkManager.registerReceiver(
            NetworkManager.c2s(), UPDATE_APPEARANCE_V2,
            ServerNetworkHandler::handleUpdateAppearanceV2);
        NetworkManager.registerReceiver(
            NetworkManager.c2s(), REQUEST_TEXTURE_V2,
            ServerNetworkHandler::handleRequestTextureV2);
        NetworkManager.registerReceiver(
            NetworkManager.c2s(), TEXTURE_CHUNK_V2,
            ServerNetworkHandler::handleTextureChunkV2);
        NetworkManager.registerReceiver(
            NetworkManager.c2s(), UPLOAD_ANIMATION_METADATA_V2,
            ServerNetworkHandler::handleUploadAnimationMetadataV2);

    }

    @Override
    public void initClient() {
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SYNC_APPEARANCE,
            ClientNetworkHandler::handleSyncAppearance
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SEND_TEXTURE,
            ClientNetworkHandler::handleSendTexture
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SEND_TEXTURE_CHUNK,
            ClientNetworkHandler::handleSendTextureChunk
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SEND_ANIMATION_METADATA,
            ClientNetworkHandler::handleSendAnimationMetadata
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SYNC_SERVER_CONFIG,
            ClientNetworkHandler::handleSyncServerConfig
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            COOLDOWN_UPDATE,
            ClientNetworkHandler::handleCooldownUpdate
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            APPEARANCE_SNAPSHOT_COMPLETE,
            ClientNetworkHandler::handleAppearanceSnapshotComplete
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(), PROTOCOL_ACK, ClientNetworkHandler::handleProtocolAck);
        NetworkManager.registerReceiver(
            NetworkManager.s2c(), SYNC_APPEARANCE_V2,
            ClientNetworkHandler::handleSyncAppearanceV2);
        NetworkManager.registerReceiver(
            NetworkManager.s2c(), SEND_TEXTURE_V2,
            ClientNetworkHandler::handleSendTextureV2);
        NetworkManager.registerReceiver(
            NetworkManager.s2c(), SEND_TEXTURE_CHUNK_V2,
            ClientNetworkHandler::handleSendTextureChunkV2);
        NetworkManager.registerReceiver(
            NetworkManager.s2c(), SEND_ANIMATION_METADATA_V2,
            ClientNetworkHandler::handleSendAnimationMetadataV2);
    }

    @Override
    public boolean canServerReceiveAppearance() {
        return NetworkManager.canServerReceive(UPDATE_APPEARANCE);
    }

    @Override
    public boolean canServerReceiveAppearanceSnapshot() {
        return NetworkManager.canServerReceive(REQUEST_APPEARANCE_SNAPSHOT);
    }

    @Override
    public boolean canServerReceiveProtocolHello() {
        return NetworkManager.canServerReceive(PROTOCOL_HELLO);
    }

    @Override
    public boolean canServerReceiveLegacyProtocol() {
        return NetworkManager.canServerReceive(UPDATE_APPEARANCE)
                && NetworkManager.canServerReceive(REQUEST_TEXTURE);
    }

    @Override
    public boolean canPlayerReceive(
            ServerPlayer player, NetworkTransport.ServerPacket packet) {
        if (player == null || packet == null) return false;
        ResourceLocation channel = switch (packet) {
            case APPEARANCE_V1 -> ModNetworking.SYNC_APPEARANCE;
            case APPEARANCE_V2 -> ModNetworking.SYNC_APPEARANCE_V2;
            case TEXTURE_V1 -> ModNetworking.SEND_TEXTURE;
            case TEXTURE_V2 -> ModNetworking.SEND_TEXTURE_V2;
            case TEXTURE_CHUNK_V1 -> ModNetworking.SEND_TEXTURE_CHUNK;
            case TEXTURE_CHUNK_V2 -> ModNetworking.SEND_TEXTURE_CHUNK_V2;
            case ANIMATION_METADATA_V1 -> ModNetworking.SEND_ANIMATION_METADATA;
            case ANIMATION_METADATA_V2 -> ModNetworking.SEND_ANIMATION_METADATA_V2;
            case SERVER_CONFIG -> ModNetworking.SYNC_SERVER_CONFIG;
            case COOLDOWN -> ModNetworking.COOLDOWN_UPDATE;
            case APPEARANCE_SNAPSHOT_COMPLETE ->
                    ModNetworking.APPEARANCE_SNAPSHOT_COMPLETE;
            case PROTOCOL_ACK -> ModNetworking.PROTOCOL_ACK;
        };
        return NetworkManager.canPlayerReceive(player, channel);
    }

    @Override
    public boolean canPlayerReceiveQuickSkin(ServerPlayer player) {
        ProtocolProfile profile = ProtocolSessions.getInstance().serverProfile(
                player.getUUID(), player.connection);
        return switch (profile.mode()) {
            case LEGACY_V1 -> canPlayerReceive(
                    player, NetworkTransport.ServerPacket.APPEARANCE_V1);
            case NEGOTIATED -> canPlayerReceive(
                    player, NetworkTransport.ServerPacket.APPEARANCE_V2);
            default -> false;
        };
    }

    @Override
    public boolean canPlayerReceiveProtocolAck(ServerPlayer player) {
        return canPlayerReceive(player, NetworkTransport.ServerPacket.PROTOCOL_ACK);
    }

    @Override
    public boolean canPlayerReceiveLegacyProtocol(ServerPlayer player) {
        return canPlayerReceive(player, NetworkTransport.ServerPacket.APPEARANCE_V1);
    }

    @Override
    public void sendAppearanceToServer(
            UUID playerId, String skinId, String capeId, String model) {
        NetworkManager.sendToServer(
                UPDATE_APPEARANCE,
                PacketHelper.createUpdateAppearancePacket(playerId, skinId, capeId, model));
    }

    @Override
    public void sendTextureChunkToServer(
            String hash, String textureType, int chunkIndex, int totalChunks, byte[] chunkData) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(hash);
        buffer.writeUtf(textureType);
        buffer.writeInt(chunkIndex);
        buffer.writeInt(totalChunks);
        buffer.writeByteArray(chunkData);
        NetworkManager.sendToServer(TEXTURE_CHUNK, buffer);
    }

    @Override
    public void sendAnimationMetadataToServer(String hash, String metadataJson) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(hash);
        buffer.writeUtf(metadataJson);
        NetworkManager.sendToServer(UPLOAD_ANIMATION_METADATA, buffer);
    }

    @Override
    public void requestTextureFromServer(UUID playerId, String textureType, String hash) {
        NetworkManager.sendToServer(
                REQUEST_TEXTURE,
                PacketHelper.createRequestTexturePacket(playerId, textureType, hash));
    }

    @Override
    public void requestAppearanceSnapshotFromServer(UUID playerId, long requestId) {
        NetworkManager.sendToServer(
                REQUEST_APPEARANCE_SNAPSHOT,
                PacketHelper.createRequestAppearanceSnapshotPacket(playerId, requestId));
    }

    @Override
    public void sendServerConfigUpdateToServer(String key, boolean value) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(key);
        buffer.writeBoolean(value);
        NetworkManager.sendToServer(UPDATE_SERVER_CONFIG, buffer);
    }

    @Override
    public void sendProtocolHelloToServer(long nonce, ProtocolOffer offer) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeLong(nonce);
        buffer.writeInt(offer.minimumVersion());
        buffer.writeInt(offer.maximumVersion());
        buffer.writeLong(offer.capabilityMask());
        buffer.writeInt(offer.maximumTextureBytes());
        buffer.writeInt(offer.maximumChunkBytes());
        NetworkManager.sendToServer(PROTOCOL_HELLO, buffer);
    }

    @Override
    public void sendAppearanceV2ToServer(
            UUID playerId, String skinId, String capeId, String model) {
        NetworkManager.sendToServer(
                UPDATE_APPEARANCE_V2,
                PacketHelper.createUpdateAppearancePacket(playerId, skinId, capeId, model));
    }

    @Override
    public void sendTextureChunkV2ToServer(
            String contentId, String textureType,
            int chunkIndex, int totalChunks, byte[] chunkData) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(contentId, TextureTransferLimits.MAX_CONTENT_ID_BYTES);
        buffer.writeUtf(textureType, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        buffer.writeInt(chunkIndex);
        buffer.writeInt(totalChunks);
        buffer.writeByteArray(chunkData);
        NetworkManager.sendToServer(TEXTURE_CHUNK_V2, buffer);
    }

    @Override
    public void sendAnimationMetadataV2ToServer(String contentId, String metadataJson) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(contentId, TextureTransferLimits.MAX_CONTENT_ID_BYTES);
        buffer.writeUtf(metadataJson, TextureTransferLimits.MAX_JSON_BYTES);
        NetworkManager.sendToServer(UPLOAD_ANIMATION_METADATA_V2, buffer);
    }

    @Override
    public void requestTextureV2FromServer(
            UUID playerId, String textureType, String contentId) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUUID(playerId);
        buffer.writeUtf(textureType, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        buffer.writeUtf(contentId, TextureTransferLimits.MAX_CONTENT_ID_BYTES);
        NetworkManager.sendToServer(REQUEST_TEXTURE_V2, buffer);
    }

    @Override
    public void sendCooldownToPlayer(ServerPlayer player, long cooldownEndTime) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeLong(cooldownEndTime);
        NetworkManager.sendToPlayer(player, COOLDOWN_UPDATE, buffer);
    }

    @Override
    public void sendTextureToPlayer(
            ServerPlayer player, String textureType, String hash, byte[] imageData) {
        NetworkManager.sendToPlayer(
                player, SEND_TEXTURE,
                PacketHelper.createSendTexturePacket(textureType, hash, imageData));
    }

    @Override
    public void sendTextureChunkToPlayer(
            ServerPlayer player, String hash, String textureType,
            int chunkIndex, int totalChunks, byte[] chunkData) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(hash);
        buffer.writeUtf(textureType);
        buffer.writeInt(chunkIndex);
        buffer.writeInt(totalChunks);
        buffer.writeByteArray(chunkData);
        NetworkManager.sendToPlayer(player, SEND_TEXTURE_CHUNK, buffer);
    }

    @Override
    public void sendAppearanceToPlayer(
            ServerPlayer player, UUID playerId, String skinId, String capeId, String model) {
        NetworkManager.sendToPlayer(
                player, SYNC_APPEARANCE,
                PacketHelper.createSyncAppearancePacket(playerId, skinId, capeId, model));
    }

    @Override
    public void sendAnimationMetadataToPlayer(
            ServerPlayer player, String hash, String metadataJson) {
        NetworkManager.sendToPlayer(
                player, SEND_ANIMATION_METADATA,
                PacketHelper.createSendAnimationMetadataPacket(hash, metadataJson));
    }

    @Override
    public void sendServerConfigToPlayer(ServerPlayer player, String configJson) {
        NetworkManager.sendToPlayer(
                player, SYNC_SERVER_CONFIG,
                PacketHelper.createSyncServerConfigPacket(configJson));
    }

    @Override
    public void sendAppearanceSnapshotCompleteToPlayer(
            ServerPlayer player, long requestId) {
        NetworkManager.sendToPlayer(
                player, APPEARANCE_SNAPSHOT_COMPLETE,
                PacketHelper.createAppearanceSnapshotCompletePacket(requestId));
    }

    @Override
    public void sendProtocolAckToPlayer(
            ServerPlayer player, long nonce, ProtocolAcknowledgement acknowledgement) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeLong(nonce);
        buffer.writeBoolean(acknowledgement.accepted());
        buffer.writeInt(acknowledgement.selectedVersion());
        buffer.writeLong(acknowledgement.capabilityMask());
        buffer.writeInt(acknowledgement.maximumTextureBytes());
        buffer.writeInt(acknowledgement.maximumChunkBytes());
        NetworkManager.sendToPlayer(player, PROTOCOL_ACK, buffer);
    }

    @Override
    public void sendTextureV2ToPlayer(
            ServerPlayer player, String textureType, String contentId, byte[] imageData) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(textureType, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        buffer.writeUtf(contentId, TextureTransferLimits.MAX_CONTENT_ID_BYTES);
        buffer.writeByteArray(imageData);
        NetworkManager.sendToPlayer(player, SEND_TEXTURE_V2, buffer);
    }

    @Override
    public void sendTextureChunkV2ToPlayer(
            ServerPlayer player, String contentId, String textureType,
            int chunkIndex, int totalChunks, byte[] chunkData) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(contentId, TextureTransferLimits.MAX_CONTENT_ID_BYTES);
        buffer.writeUtf(textureType, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        buffer.writeInt(chunkIndex);
        buffer.writeInt(totalChunks);
        buffer.writeByteArray(chunkData);
        NetworkManager.sendToPlayer(player, SEND_TEXTURE_CHUNK_V2, buffer);
    }

    @Override
    public void sendAppearanceV2ToPlayer(
            ServerPlayer player, UUID playerId, String skinId, String capeId, String model) {
        NetworkManager.sendToPlayer(
                player, SYNC_APPEARANCE_V2,
                PacketHelper.createSyncAppearancePacket(playerId, skinId, capeId, model));
    }

    @Override
    public void sendAnimationMetadataV2ToPlayer(
            ServerPlayer player, String contentId, String metadataJson) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(contentId, TextureTransferLimits.MAX_CONTENT_ID_BYTES);
        buffer.writeUtf(metadataJson, TextureTransferLimits.MAX_JSON_BYTES);
        NetworkManager.sendToPlayer(player, SEND_ANIMATION_METADATA_V2, buffer);
    }
}
