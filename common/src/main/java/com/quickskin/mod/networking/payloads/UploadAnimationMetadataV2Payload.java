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

public record UploadAnimationMetadataV2Payload(String contentId, String metadataJson)
        implements CustomPacketPayload {
    public static final Type<UploadAnimationMetadataV2Payload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "upload_animation_metadata_v2")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "upload_animation_metadata_v2")
            //?}
    );
    public static final StreamCodec<ByteBuf, UploadAnimationMetadataV2Payload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeString(buf, payload.contentId(), TextureTransferLimits.MAX_CONTENT_ID_BYTES);
                PayloadCodecs.writeString(buf, payload.metadataJson(), TextureTransferLimits.MAX_JSON_BYTES);
            },
            buf -> new UploadAnimationMetadataV2Payload(
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_CONTENT_ID_BYTES),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_JSON_BYTES)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
//?}
