package com.quickskin.mod.networking.payloads;

//? if >=1.21 {
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

public record SendTextureChunkV2Payload(
        String contentId, String textureType, int chunkIndex, int totalChunks, byte[] chunkData)
        implements CustomPacketPayload {
    public static final Type<SendTextureChunkV2Payload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "send_texture_chunk_v2")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "send_texture_chunk_v2")
            //?}
    );
    public static final StreamCodec<ByteBuf, SendTextureChunkV2Payload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeString(buf, payload.contentId(), TextureTransferLimits.MAX_CONTENT_ID_BYTES);
                PayloadCodecs.writeString(buf, payload.textureType(), TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
                buf.writeInt(payload.chunkIndex());
                buf.writeInt(payload.totalChunks());
                PayloadCodecs.writeByteArray(buf, payload.chunkData(), TextureTransferLimits.MAX_WIRE_CHUNK_BYTES);
            },
            buf -> new SendTextureChunkV2Payload(
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_CONTENT_ID_BYTES),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES),
                    buf.readInt(), buf.readInt(),
                    PayloadCodecs.readByteArray(buf, TextureTransferLimits.MAX_WIRE_CHUNK_BYTES)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
//?}
