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

public record SendTextureV2Payload(String textureType, String contentId, byte[] imageData)
        implements CustomPacketPayload {
    public static final Type<SendTextureV2Payload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "send_texture_v2")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "send_texture_v2")
            //?}
    );
    public static final StreamCodec<ByteBuf, SendTextureV2Payload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeString(buf, payload.textureType(), TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
                PayloadCodecs.writeString(buf, payload.contentId(), TextureTransferLimits.MAX_CONTENT_ID_BYTES);
                PayloadCodecs.writeByteArray(buf, payload.imageData(), TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES);
            },
            buf -> new SendTextureV2Payload(
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_CONTENT_ID_BYTES),
                    PayloadCodecs.readByteArray(buf, TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
//?}
