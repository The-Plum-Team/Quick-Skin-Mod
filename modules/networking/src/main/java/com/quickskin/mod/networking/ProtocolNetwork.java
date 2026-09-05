package com.quickskin.mod.networking;

import com.quickskin.mod.common.data.ContentId;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolChannelContract;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
import com.quickskin.mod.server.storage.ServerTextureCache;
//? if >=1.21 {
import com.quickskin.mod.networking.payloads.*;
//?}
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Profile-aware wire selection and proven content-alias translation. */
public final class ProtocolNetwork {
    private ProtocolNetwork() {
    }

    public static ProtocolProfile profile(ServerPlayer player) {
        return player == null
                ? ProtocolProfile.localOnly("missing-player")
                : ProtocolSessions.getInstance().serverProfile(
                        player.getUUID(), player.connection);
    }

    public static boolean acceptsV2(ServerPlayer player) {
        ProtocolProfile profile = profile(player);
        return profile.negotiated() && profile.version() == 2
                && profile.supports(ProtocolCapability.SHA256_CONTENT_IDS)
                && profile.supports(ProtocolCapability.CHUNKED_TEXTURE_TRANSFER);
    }

    public static boolean isReady(ServerPlayer player) {
        ProtocolProfile.Mode mode = profile(player).mode();
        return mode == ProtocolProfile.Mode.LEGACY_V1
                || mode == ProtocolProfile.Mode.NEGOTIATED;
    }

    private static boolean canReceiveAppearancePacket(ServerPlayer player, boolean v2) {
        if (ProtocolChannelContract.guarantees(
                profile(player), ProtocolChannelContract.ServerPacket.APPEARANCE)) return true;
        //? if <1.21 {
        return NetworkTransport.INSTANCE.canPlayerReceive(
                player,
                v2 ? NetworkTransport.ServerPacket.APPEARANCE_V2
                        : NetworkTransport.ServerPacket.APPEARANCE_V1);
        //?} else {
        return v2
                ? NetworkTransport.INSTANCE.canPlayerReceive(player, SyncAppearanceV2Payload.TYPE)
                : NetworkTransport.INSTANCE.canPlayerReceive(player, SyncAppearancePayload.TYPE);
        //?}
    }

    private static boolean canReceiveTexturePacket(ServerPlayer player, boolean v2) {
        if (ProtocolChannelContract.guarantees(
                profile(player), ProtocolChannelContract.ServerPacket.TEXTURE)) return true;
        //? if <1.21 {
        return NetworkTransport.INSTANCE.canPlayerReceive(
                player,
                v2 ? NetworkTransport.ServerPacket.TEXTURE_V2
                        : NetworkTransport.ServerPacket.TEXTURE_V1);
        //?} else {
        return v2
                ? NetworkTransport.INSTANCE.canPlayerReceive(player, SendTextureV2Payload.TYPE)
                : NetworkTransport.INSTANCE.canPlayerReceive(player, SendTexturePayload.TYPE);
        //?}
    }

    private static boolean canReceiveTextureChunkPacket(ServerPlayer player, boolean v2) {
        if (ProtocolChannelContract.guarantees(
                profile(player), ProtocolChannelContract.ServerPacket.TEXTURE_CHUNK)) return true;
        //? if <1.21 {
        return NetworkTransport.INSTANCE.canPlayerReceive(
                player,
                v2 ? NetworkTransport.ServerPacket.TEXTURE_CHUNK_V2
                        : NetworkTransport.ServerPacket.TEXTURE_CHUNK_V1);
        //?} else {
        return v2
                ? NetworkTransport.INSTANCE.canPlayerReceive(player, SendTextureChunkV2Payload.TYPE)
                : NetworkTransport.INSTANCE.canPlayerReceive(player, SendTextureChunkPayload.TYPE);
        //?}
    }

    private static boolean canReceiveAnimationMetadataPacket(
            ServerPlayer player, boolean v2) {
        if (ProtocolChannelContract.guarantees(
                profile(player),
                ProtocolChannelContract.ServerPacket.ANIMATION_METADATA)) return true;
        //? if <1.21 {
        return NetworkTransport.INSTANCE.canPlayerReceive(
                player,
                v2 ? NetworkTransport.ServerPacket.ANIMATION_METADATA_V2
                        : NetworkTransport.ServerPacket.ANIMATION_METADATA_V1);
        //?} else {
        return v2
                ? NetworkTransport.INSTANCE.canPlayerReceive(
                        player, SendAnimationMetadataV2Payload.TYPE)
                : NetworkTransport.INSTANCE.canPlayerReceive(
                        player, SendAnimationMetadataPayload.TYPE);
        //?}
    }

    public static boolean canReceiveServerConfig(ServerPlayer player) {
        if (player == null || !isReady(player)) return false;
        if (ProtocolChannelContract.guarantees(
                profile(player), ProtocolChannelContract.ServerPacket.SERVER_CONFIG)) return true;
        //? if <1.21 {
        return NetworkTransport.INSTANCE.canPlayerReceive(
                player, NetworkTransport.ServerPacket.SERVER_CONFIG);
        //?} else {
        return NetworkTransport.INSTANCE.canPlayerReceive(player, SyncServerConfigPayload.TYPE);
        //?}
    }

    public static boolean canReceiveCooldown(ServerPlayer player) {
        if (player == null || !isReady(player)) return false;
        if (ProtocolChannelContract.guarantees(
                profile(player), ProtocolChannelContract.ServerPacket.COOLDOWN)) return true;
        //? if <1.21 {
        return NetworkTransport.INSTANCE.canPlayerReceive(
                player, NetworkTransport.ServerPacket.COOLDOWN);
        //?} else {
        return NetworkTransport.INSTANCE.canPlayerReceive(player, CooldownUpdatePayload.TYPE);
        //?}
    }

    public static boolean canReceiveAppearanceSnapshotComplete(ServerPlayer player) {
        if (player == null || !isReady(player)) return false;
        ProtocolProfile profile = profile(player);
        if (profile.negotiated()
                && !profile.supports(ProtocolCapability.APPEARANCE_SNAPSHOT_ACK)) return false;
        if (ProtocolChannelContract.guarantees(
                profile,
                ProtocolChannelContract.ServerPacket.APPEARANCE_SNAPSHOT_COMPLETE)) return true;
        //? if <1.21 {
        return NetworkTransport.INSTANCE.canPlayerReceive(
                player, NetworkTransport.ServerPacket.APPEARANCE_SNAPSHOT_COMPLETE);
        //?} else {
        return NetworkTransport.INSTANCE.canPlayerReceive(
                player, AppearanceSnapshotCompletePayload.TYPE);
        //?}
    }

    public static boolean canReceive(ServerPlayer player) {
        if (player == null || !isReady(player)) return false;
        return canReceiveAppearancePacket(player, acceptsV2(player));
    }

    /** Classifies old and vanilla peers from explicit registered S2C channels at join time. */
    public static void classifyServerPeer(ServerPlayer player) {
        if (player == null || profile(player).mode() != ProtocolProfile.Mode.LOCAL_ONLY) return;
        //? if <1.21 {
        boolean helloCapable = NetworkTransport.INSTANCE.canPlayerReceiveProtocolAck(player);
        boolean legacyCapable = NetworkTransport.INSTANCE.canPlayerReceiveLegacyProtocol(player);
        //?} else {
        boolean helloCapable = NetworkTransport.INSTANCE.canPlayerReceive(player, ProtocolAckPayload.TYPE);
        boolean legacyCapable = NetworkTransport.INSTANCE.canPlayerReceive(player, SyncAppearancePayload.TYPE);
        //?}
        if (!helloCapable && legacyCapable) {
            ProtocolSessions.getInstance().classifyLegacyClient(
                    player.getUUID(), player.connection);
        }
    }

    public static String translateContentId(ServerPlayer recipient, String contentId) {
        ProtocolProfile profile = profile(recipient);
        ContentId.Algorithm algorithm;
        if (profile.mode() == ProtocolProfile.Mode.LEGACY_V1) {
            algorithm = ContentId.Algorithm.SHA1;
        } else if (acceptsV2(recipient)) {
            algorithm = ContentId.Algorithm.SHA256;
        } else {
            return null;
        }
        return ServerTextureCache.getInstance().contentIdFor(contentId, algorithm);
    }

    public static String translateAppearanceId(
            ServerPlayer recipient, String appearanceId, String prefix) {
        if (appearanceId == null || appearanceId.isEmpty() || !appearanceId.startsWith(prefix)) {
            return appearanceId;
        }
        String translated = translateContentId(
                recipient, appearanceId.substring(prefix.length()));
        return translated == null ? null : prefix + translated;
    }

    public static boolean sendAppearance(
            ServerPlayer recipient, UUID playerId,
            String skinId, String capeId, String model) {
        if (!canReceive(recipient)) return false;
        String wireSkin = translateAppearanceId(recipient, skinId, "local_skin:");
        String wireCape = translateAppearanceId(recipient, capeId, "local_cape:");
        if (wireSkin == null || wireCape == null) return false;
        if (acceptsV2(recipient)) {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendAppearanceV2ToPlayer(
                    recipient, playerId, wireSkin, wireCape, model);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(
                    recipient,
                    new SyncAppearanceV2Payload(playerId, wireSkin, wireCape, model));
            //?}
        } else {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendAppearanceToPlayer(
                    recipient, playerId, wireSkin, wireCape, model);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(
                    recipient,
                    new SyncAppearancePayload(playerId, wireSkin, wireCape, model));
            //?}
        }
        return true;
    }

    public static boolean sendAnimationMetadata(
            ServerPlayer recipient, String contentId, String metadataJson) {
        if (!canReceive(recipient)) return false;
        ProtocolProfile profile = profile(recipient);
        if (profile.negotiated()
                && !profile.supports(ProtocolCapability.ANIMATION_METADATA)) return false;
        if (!canReceiveAnimationMetadataPacket(recipient, profile.negotiated())) return false;
        String wireId = translateContentId(recipient, contentId);
        if (wireId == null) return false;
        if (acceptsV2(recipient)) {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendAnimationMetadataV2ToPlayer(
                    recipient, wireId, metadataJson);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(
                    recipient, new SendAnimationMetadataV2Payload(wireId, metadataJson));
            //?}
        } else {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendAnimationMetadataToPlayer(
                    recipient, wireId, metadataJson);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(
                    recipient, new SendAnimationMetadataPayload(wireId, metadataJson));
            //?}
        }
        return true;
    }

    public static boolean sendTexture(
            ServerPlayer recipient, String textureType, String contentId, byte[] data) {
        if (!canReceive(recipient)) return false;
        boolean v2 = acceptsV2(recipient);
        if (!canReceiveTexturePacket(recipient, v2)) return false;
        String wireId = translateContentId(recipient, contentId);
        if (wireId == null) return false;
        if (v2) {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendTextureV2ToPlayer(
                    recipient, textureType, wireId, data);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(
                    recipient, new SendTextureV2Payload(textureType, wireId, data));
            //?}
        } else {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendTextureToPlayer(
                    recipient, textureType, wireId, data);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(
                    recipient, new SendTexturePayload(textureType, wireId, data));
            //?}
        }
        return true;
    }

    public static boolean sendTextureChunk(
            ServerPlayer recipient, String contentId, String textureType,
            int chunkIndex, int totalChunks, byte[] data) {
        if (!canReceive(recipient)) return false;
        boolean v2 = acceptsV2(recipient);
        if (!canReceiveTextureChunkPacket(recipient, v2)) return false;
        String wireId = translateContentId(recipient, contentId);
        if (wireId == null) return false;
        if (v2) {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendTextureChunkV2ToPlayer(
                    recipient, wireId, textureType, chunkIndex, totalChunks, data);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(
                    recipient,
                    new SendTextureChunkV2Payload(
                            wireId, textureType, chunkIndex, totalChunks, data));
            //?}
        } else {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendTextureChunkToPlayer(
                    recipient, wireId, textureType, chunkIndex, totalChunks, data);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(
                    recipient,
                    new SendTextureChunkPayload(
                            wireId, textureType, chunkIndex, totalChunks, data));
            //?}
        }
        return true;
    }
}
