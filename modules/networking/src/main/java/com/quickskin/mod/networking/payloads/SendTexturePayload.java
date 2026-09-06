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
 * Payload for sending texture data to client (S2C)
 * Format: String (textureType) + String (hash) + byte[] (imageData)
 */
public record SendTexturePayload(String textureType, String hash, byte[] imageData) implements CustomPacketPayload {

    public static final Type<SendTexturePayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "send_texture")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "send_texture")
        //?}
    );

    public static final StreamCodec<ByteBuf, SendTexturePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeString(buf, payload.textureType, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
            PayloadCodecs.writeString(buf, payload.hash, TextureTransferLimits.CONTENT_ID_LENGTH);
            PayloadCodecs.writeByteArray(buf, payload.imageData, TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES);
        },
        buf -> {
            String textureType = PayloadCodecs.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
            String hash = PayloadCodecs.readString(buf, TextureTransferLimits.CONTENT_ID_LENGTH);
            byte[] imageData = PayloadCodecs.readByteArray(buf, TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES);
            return new SendTexturePayload(textureType, hash, imageData);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

//?}
