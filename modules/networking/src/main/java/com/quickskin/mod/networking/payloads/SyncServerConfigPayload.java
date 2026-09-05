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
 * Payload for syncing server config to client (S2C)
 * Format: String (configJson)
 */
public record SyncServerConfigPayload(String configJson) implements CustomPacketPayload {

    public static final Type<SyncServerConfigPayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "sync_server_config")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "sync_server_config")
        //?}
    );

    public static final StreamCodec<ByteBuf, SyncServerConfigPayload> CODEC = StreamCodec.of(
        (buf, payload) -> PayloadCodecs.writeString(buf, payload.configJson, TextureTransferLimits.MAX_JSON_BYTES),
        buf -> new SyncServerConfigPayload(PayloadCodecs.readString(buf, TextureTransferLimits.MAX_JSON_BYTES))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

//?}
