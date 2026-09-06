//? if >=1.21 {
package com.quickskin.mod.networking.payloads;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.networking.TextureTransferLimits;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/**
 * Payload for sending texture chunk to client (S2C)
 * Format: String (hash) + String (textureType) + int (chunkIndex) + int (totalChunks) + byte[] (chunkData)
 */
public record SendTextureChunkPayload(String hash, String textureType, int chunkIndex, int totalChunks, byte[] chunkData) implements CustomPacketPayload {

    public static final Type<SendTextureChunkPayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "send_texture_chunk")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "send_texture_chunk")
        //?}
    );

    public static final StreamCodec<ByteBuf, SendTextureChunkPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeString(buf, payload.hash, TextureTransferLimits.CONTENT_ID_LENGTH);
            PayloadCodecs.writeString(buf, payload.textureType, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
            buf.writeInt(payload.chunkIndex);
            buf.writeInt(payload.totalChunks);
            PayloadCodecs.writeByteArray(buf, payload.chunkData, TextureTransferLimits.MAX_WIRE_CHUNK_BYTES);
        },
        buf -> {
            String hash = PayloadCodecs.readString(buf, TextureTransferLimits.CONTENT_ID_LENGTH);
            String textureType = PayloadCodecs.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
            int chunkIndex = buf.readInt();
            int totalChunks = buf.readInt();
            byte[] chunkData = PayloadCodecs.readByteArray(buf, TextureTransferLimits.MAX_WIRE_CHUNK_BYTES);
            return new SendTextureChunkPayload(hash, textureType, chunkIndex, totalChunks, chunkData);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

//?}
