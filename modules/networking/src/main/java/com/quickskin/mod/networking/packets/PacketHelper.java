package com.quickskin.mod.networking.packets;

import com.quickskin.mod.networking.TextureTransferLimits;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
//? if >=1.21 {
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
//?}

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Helper class for creating and reading packet data
 */
public class PacketHelper {

    /**
     * Creates a packet for uploading skin/cape texture
     * Format: UUID (player) + String (textureType) + byte[] (imageData)
     */
    //? if <1.21 {
    public static FriendlyByteBuf createUploadTexturePacket(UUID playerId, String textureType, byte[] imageData) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    //?} else {
    public static RegistryFriendlyByteBuf createUploadTexturePacket(UUID playerId, String textureType, byte[] imageData) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    //?}
        buf.writeUUID(playerId);
        buf.writeUtf(textureType); // "skin" or "cape"
        buf.writeByteArray(imageData);
        return buf;
    }

    /**
     * Creates a packet for updating player appearance
     * Format: UUID (player) + String (skinId) + String (capeId) + String (model)
     */
    //? if <1.21 {
    public static FriendlyByteBuf createUpdateAppearancePacket(UUID playerId, String skinId, String capeId, String model) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    //?} else {
    public static RegistryFriendlyByteBuf createUpdateAppearancePacket(UUID playerId, String skinId, String capeId, String model) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    //?}
        buf.writeUUID(playerId);
        buf.writeUtf(skinId != null ? skinId : "");
        buf.writeUtf(capeId != null ? capeId : "");
        buf.writeUtf(model != null ? model : "classic");
        return buf;
    }

    /**
     * Creates a packet for syncing appearance to clients
     * Format: UUID (player) + String (skinId) + String (capeId) + String (model)
     */
    //? if <1.21 {
    public static FriendlyByteBuf createSyncAppearancePacket(UUID playerId, String skinId, String capeId, String model) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    //?} else {
    public static RegistryFriendlyByteBuf createSyncAppearancePacket(UUID playerId, String skinId, String capeId, String model) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    //?}
        buf.writeUUID(playerId);
        buf.writeUtf(skinId != null ? skinId : "");
        buf.writeUtf(capeId != null ? capeId : "");
        buf.writeUtf(model != null ? model : "classic");
        return buf;
    }

    /**
     * Creates a packet for requesting a texture from server
     * Format: UUID (player) + String (textureType) + String (hash)
     */
    //? if <1.21 {
    public static FriendlyByteBuf createRequestTexturePacket(UUID playerId, String textureType, String hash) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    //?} else {
    public static RegistryFriendlyByteBuf createRequestTexturePacket(UUID playerId, String textureType, String hash) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    //?}
        buf.writeUUID(playerId);
        buf.writeUtf(textureType); // "skin" or "cape"
        buf.writeUtf(hash);
        return buf;
    }

    /**
     * Creates a packet for sending texture data to client
     * Format: String (textureType) + String (hash) + byte[] (imageData)
     */
    //? if <1.21 {
    public static FriendlyByteBuf createSendTexturePacket(String textureType, String hash, byte[] imageData) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    //?} else {
    public static RegistryFriendlyByteBuf createSendTexturePacket(String textureType, String hash, byte[] imageData) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    //?}
        buf.writeUtf(textureType);
        buf.writeUtf(hash);
        buf.writeByteArray(imageData);
        return buf;
    }

    /**
     * Creates a packet for syncing server config to client
     * Format: boolean (allowSkins) + boolean (allowCapes) + boolean (allowTransparent)
     */
    /**
     * Create server config sync packet (Phase 9)
     * Sends full server config as JSON to client
     */
    //? if <1.21 {
    public static FriendlyByteBuf createSyncServerConfigPacket(String configJson) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    //?} else {
    public static RegistryFriendlyByteBuf createSyncServerConfigPacket(String configJson) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    //?}
        buf.writeUtf(configJson);
        return buf;
    }

    //? if <1.21 {
    public static FriendlyByteBuf createRequestAppearanceSnapshotPacket(
            UUID playerId, long requestId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(playerId);
        buf.writeLong(requestId);
        return buf;
    }

    public static FriendlyByteBuf createAppearanceSnapshotCompletePacket(long requestId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeLong(requestId);
        return buf;
    }
    //?}

    /**
     * Creates a packet for sending animation metadata to client
     * Format: String (hash) + String (metadataJson)
     */
    //? if <1.21 {
    public static FriendlyByteBuf createSendAnimationMetadataPacket(String hash, String metadataJson) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    //?} else {
    public static RegistryFriendlyByteBuf createSendAnimationMetadataPacket(String hash, String metadataJson) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    //?}
        buf.writeUtf(hash);
        writeAnimationMetadata(buf, metadataJson);
        return buf;
    }

    /**
     * Writes one animation metadata document within its validated UTF-8 byte bound, the size
     * {@code WirePayloadBudget} assumes for the metadata packets. Readers stay tolerant and accept
     * up to {@link TextureTransferLimits#MAX_JSON_BYTES}.
     */
    public static void writeAnimationMetadata(FriendlyByteBuf buf, String metadataJson) {
        int maximumBytes = TextureTransferLimits.MAX_ANIMATION_METADATA_JSON_BYTES;
        if (metadataJson == null
                || metadataJson.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new EncoderException("Animation metadata is over its " + maximumBytes + " byte bound");
        }
        buf.writeUtf(metadataJson, maximumBytes);
    }

    /**
     * Reads a UUID from buffer
     */
    public static UUID readPlayerId(FriendlyByteBuf buf) {
        return buf.readUUID();
    }

    /**
     * Reads a string from buffer.
     */
    public static String readString(FriendlyByteBuf buf) {
        return readString(buf, 32767);
    }

    public static String readString(FriendlyByteBuf buf, int maxLength) {
        return buf.readUtf(maxLength);
    }

    /**
     * Reads a byte array from buffer
     */
    public static byte[] readByteArray(FriendlyByteBuf buf) {
        return readByteArray(buf, 32 * 1024);
    }

    public static byte[] readByteArray(FriendlyByteBuf buf, int maxLength) {
        return buf.readByteArray(maxLength);
    }

    /**
     * Reads a boolean from buffer
     */
    public static boolean readBoolean(FriendlyByteBuf buf) {
        return buf.readBoolean();
    }
}
