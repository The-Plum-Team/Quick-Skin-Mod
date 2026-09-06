package com.quickskin.mod.networking;

//? if >=1.21 {
import com.quickskin.mod.networking.payloads.*;
//?}
import dev.architectury.networking.NetworkManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side networking initialization
 * Registers client-side packet receivers (S2C) - Architectury 13.x for MC 1.21.1
 */
@Environment(EnvType.CLIENT)
public class ClientNetworking {

    /**
     * Initializes client-side networking (registers S2C receivers and C2S payload types)
     * Called from QuickSkinClient.init() only on client
     */
    public static void init() {
        // Register S2C (Server to Client) payload receivers
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            //? if <1.21 {
            ModNetworking.SYNC_APPEARANCE,
            //?} else {
            SyncAppearancePayload.TYPE,
            SyncAppearancePayload.CODEC,
            //?}
            ClientNetworkHandler::handleSyncAppearance
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            //? if <1.21 {
            ModNetworking.SEND_TEXTURE,
            //?} else {
            SendTexturePayload.TYPE,
            SendTexturePayload.CODEC,
            //?}
            ClientNetworkHandler::handleSendTexture
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            //? if <1.21 {
            ModNetworking.SEND_TEXTURE_CHUNK,
            //?} else {
            SendTextureChunkPayload.TYPE,
            SendTextureChunkPayload.CODEC,
            //?}
            ClientNetworkHandler::handleSendTextureChunk
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            //? if <1.21 {
            ModNetworking.SEND_ANIMATION_METADATA,
            //?} else {
            SendAnimationMetadataPayload.TYPE,
            SendAnimationMetadataPayload.CODEC,
            //?}
            ClientNetworkHandler::handleSendAnimationMetadata
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            //? if <1.21 {
            ModNetworking.SYNC_SERVER_CONFIG,
            //?} else {
            SyncServerConfigPayload.TYPE,
            SyncServerConfigPayload.CODEC,
            //?}
            ClientNetworkHandler::handleSyncServerConfig
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            //? if <1.21 {
            ModNetworking.COOLDOWN_UPDATE,
            //?} else {
            CooldownUpdatePayload.TYPE,
            CooldownUpdatePayload.CODEC,
            //?}
            ClientNetworkHandler::handleCooldownUpdate
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            //? if <1.21 {
            ModNetworking.APPEARANCE_SNAPSHOT_COMPLETE,
            //?} else {
            AppearanceSnapshotCompletePayload.TYPE,
            AppearanceSnapshotCompletePayload.CODEC,
            //?}
            ClientNetworkHandler::handleAppearanceSnapshotComplete
        );

        //? if >=1.21 {
        NetworkManager.registerReceiver(
                NetworkManager.s2c(), ProtocolAckPayload.TYPE,
                ProtocolAckPayload.CODEC, ClientNetworkHandler::handleProtocolAck);
        NetworkManager.registerReceiver(
                NetworkManager.s2c(), SyncAppearanceV2Payload.TYPE,
                SyncAppearanceV2Payload.CODEC, ClientNetworkHandler::handleSyncAppearanceV2);
        NetworkManager.registerReceiver(
                NetworkManager.s2c(), SendTextureV2Payload.TYPE,
                SendTextureV2Payload.CODEC, ClientNetworkHandler::handleSendTextureV2);
        NetworkManager.registerReceiver(
                NetworkManager.s2c(), SendTextureChunkV2Payload.TYPE,
                SendTextureChunkV2Payload.CODEC, ClientNetworkHandler::handleSendTextureChunkV2);
        NetworkManager.registerReceiver(
                NetworkManager.s2c(), SendAnimationMetadataV2Payload.TYPE,
                SendAnimationMetadataV2Payload.CODEC,
                ClientNetworkHandler::handleSendAnimationMetadataV2);
        //?}

    }
}
