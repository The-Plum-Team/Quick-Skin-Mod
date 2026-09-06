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
 * Payload for requesting a texture from server
 * Format: UUID (player) + String (textureType) + String (hash)
 */
public record RequestTexturePayload(UUID playerId, String textureType, String hash) implements CustomPacketPayload {

    public static final Type<RequestTexturePayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "request_texture")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "request_texture")
        //?}
    );

    public static final StreamCodec<ByteBuf, RequestTexturePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeUUID(buf, payload.playerId);
            PayloadCodecs.writeString(buf, payload.textureType, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
            PayloadCodecs.writeString(buf, payload.hash, TextureTransferLimits.CONTENT_ID_LENGTH);
        },
        buf -> new RequestTexturePayload(
            PayloadCodecs.readUUID(buf),
            PayloadCodecs.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES),
            PayloadCodecs.readString(buf, TextureTransferLimits.CONTENT_ID_LENGTH)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

//?}
