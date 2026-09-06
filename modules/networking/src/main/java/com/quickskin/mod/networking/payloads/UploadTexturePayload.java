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

import java.util.UUID;

/**
 * Payload for uploading skin/cape texture
 * Format: UUID (player) + String (textureType) + byte[] (imageData)
 */
public record UploadTexturePayload(UUID playerId, String textureType, byte[] imageData) implements CustomPacketPayload {

    public static final Type<UploadTexturePayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "upload_texture")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "upload_texture")
        //?}
    );

    public static final StreamCodec<ByteBuf, UploadTexturePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeUUID(buf, payload.playerId);
            PayloadCodecs.writeString(buf, payload.textureType, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
            PayloadCodecs.writeByteArray(buf, payload.imageData, TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES);
        },
        buf -> {
            UUID playerId = PayloadCodecs.readUUID(buf);
            String textureType = PayloadCodecs.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
            byte[] imageData = PayloadCodecs.readByteArray(buf, TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES);
            return new UploadTexturePayload(playerId, textureType, imageData);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

//?}
