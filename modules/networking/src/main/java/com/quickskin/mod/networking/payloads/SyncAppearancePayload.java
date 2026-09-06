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
 * Payload for syncing appearance to clients (S2C)
 * Format: UUID (player) + String (skinId) + String (capeId) + String (model)
 */
public record SyncAppearancePayload(UUID playerId, String skinId, String capeId, String model) implements CustomPacketPayload {

    public static final Type<SyncAppearancePayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "sync_appearance")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "sync_appearance")
        //?}
    );

    public static final StreamCodec<ByteBuf, SyncAppearancePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeUUID(buf, payload.playerId);
            PayloadCodecs.writeString(buf, payload.skinId != null ? payload.skinId : "", TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
            PayloadCodecs.writeString(buf, payload.capeId != null ? payload.capeId : "", TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
            PayloadCodecs.writeString(buf, payload.model != null ? payload.model : "classic", TextureTransferLimits.MAX_MODEL_BYTES);
        },
        buf -> new SyncAppearancePayload(
            PayloadCodecs.readUUID(buf),
            PayloadCodecs.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES),
            PayloadCodecs.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES),
            PayloadCodecs.readString(buf, TextureTransferLimits.MAX_MODEL_BYTES)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

//?}
