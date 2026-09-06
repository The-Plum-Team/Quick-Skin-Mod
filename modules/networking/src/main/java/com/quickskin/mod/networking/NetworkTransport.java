package com.quickskin.mod.networking;

import com.quickskin.mod.networking.protocol.ProtocolAcknowledgement;
import com.quickskin.mod.networking.protocol.ProtocolOffer;

//? if >=1.21 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}
import net.minecraft.server.level.ServerPlayer;

//? if <1.21 {
import java.util.UUID;
//?}
public interface NetworkTransport {
    //? if <1.21 {
    NetworkTransport INSTANCE = new ModNetworking();
    //?} else {
        //? if <26.1 {
    NetworkTransport INSTANCE = new PayloadNetworkTransport();
        //?} else {
    NetworkTransport INSTANCE = new ModNetworking();
        //?}
    //?}

    void init();

    void initClient();
    //? if <1.21 {
    /** Logical S2C packets mapped to their exact registered legacy channel. */
    enum ServerPacket {
        APPEARANCE_V1,
        APPEARANCE_V2,
        TEXTURE_V1,
        TEXTURE_V2,
        TEXTURE_CHUNK_V1,
        TEXTURE_CHUNK_V2,
        ANIMATION_METADATA_V1,
        ANIMATION_METADATA_V2,
        SERVER_CONFIG,
        COOLDOWN,
        APPEARANCE_SNAPSHOT_COMPLETE,
        PROTOCOL_ACK
    }

    boolean canServerReceiveAppearance();
    boolean canServerReceiveAppearanceSnapshot();
    boolean canServerReceiveProtocolHello();
    boolean canServerReceiveLegacyProtocol();
    boolean canPlayerReceive(ServerPlayer player, ServerPacket packet);
    boolean canPlayerReceiveQuickSkin(ServerPlayer player);
    boolean canPlayerReceiveProtocolAck(ServerPlayer player);
    boolean canPlayerReceiveLegacyProtocol(ServerPlayer player);
    //?}

    //? if <1.21 {
    void sendAppearanceToServer(UUID playerId, String skinId, String capeId, String model);
    void sendTextureChunkToServer(
            String hash, String textureType, int chunkIndex, int totalChunks, byte[] chunkData);
    void sendAnimationMetadataToServer(String hash, String metadataJson);
    void requestTextureFromServer(UUID playerId, String textureType, String hash);
    void requestAppearanceSnapshotFromServer(UUID playerId, long requestId);
    void sendServerConfigUpdateToServer(String key, boolean value);
    void sendProtocolHelloToServer(long nonce, ProtocolOffer offer);
    void sendAppearanceV2ToServer(UUID playerId, String skinId, String capeId, String model);
    void sendTextureChunkV2ToServer(
            String contentId, String textureType, int chunkIndex, int totalChunks, byte[] chunkData);
    void sendAnimationMetadataV2ToServer(String contentId, String metadataJson);
    void requestTextureV2FromServer(UUID playerId, String textureType, String contentId);
    //?} else {
    boolean canServerReceive(CustomPacketPayload.Type<?> type);
    //?}

    //? if <1.21 {
    void sendCooldownToPlayer(ServerPlayer player, long cooldownEndTime);
    void sendTextureToPlayer(ServerPlayer player, String textureType, String hash, byte[] imageData);
    void sendTextureChunkToPlayer(
            ServerPlayer player, String hash, String textureType,
            int chunkIndex, int totalChunks, byte[] chunkData);
    void sendAppearanceToPlayer(
            ServerPlayer player, UUID playerId, String skinId, String capeId, String model);
    void sendAnimationMetadataToPlayer(ServerPlayer player, String hash, String metadataJson);
    void sendServerConfigToPlayer(ServerPlayer player, String configJson);
    void sendAppearanceSnapshotCompleteToPlayer(ServerPlayer player, long requestId);
    void sendProtocolAckToPlayer(
            ServerPlayer player, long nonce, ProtocolAcknowledgement acknowledgement);
    void sendTextureV2ToPlayer(
            ServerPlayer player, String textureType, String contentId, byte[] imageData);
    void sendTextureChunkV2ToPlayer(
            ServerPlayer player, String contentId, String textureType,
            int chunkIndex, int totalChunks, byte[] chunkData);
    void sendAppearanceV2ToPlayer(
            ServerPlayer player, UUID playerId, String skinId, String capeId, String model);
    void sendAnimationMetadataV2ToPlayer(
            ServerPlayer player, String contentId, String metadataJson);
    //?} else {
    boolean canPlayerReceive(ServerPlayer player, CustomPacketPayload.Type<?> type);

    void sendToServer(CustomPacketPayload payload);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
    //?}
}
