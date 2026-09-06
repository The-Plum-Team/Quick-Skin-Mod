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
import java.util.UUID;

public record RequestTextureV2Payload(UUID playerId, String textureType, String contentId)
        implements CustomPacketPayload {
    public static final Type<RequestTextureV2Payload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "request_texture_v2")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "request_texture_v2")
            //?}
    );
    public static final StreamCodec<ByteBuf, RequestTextureV2Payload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeUUID(buf, payload.playerId());
                PayloadCodecs.writeString(buf, payload.textureType(), TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
                PayloadCodecs.writeString(buf, payload.contentId(), TextureTransferLimits.MAX_CONTENT_ID_BYTES);
            },
            buf -> new RequestTextureV2Payload(
                    PayloadCodecs.readUUID(buf),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_CONTENT_ID_BYTES)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
//?}
