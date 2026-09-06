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
 * Payload for server config update from admin client
 * Format: String (key) + boolean (value)
 */
public record UpdateServerConfigPayload(String key, boolean value) implements CustomPacketPayload {

    public static final Type<UpdateServerConfigPayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "update_server_config")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "update_server_config")
        //?}
    );

    public static final StreamCodec<ByteBuf, UpdateServerConfigPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeString(buf, payload.key, TextureTransferLimits.MAX_CONFIG_KEY_BYTES);
            buf.writeBoolean(payload.value);
        },
        buf -> new UpdateServerConfigPayload(
            PayloadCodecs.readString(buf, TextureTransferLimits.MAX_CONFIG_KEY_BYTES),
            buf.readBoolean()
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

//?}
