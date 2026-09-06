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

public record SyncAppearanceV2Payload(UUID playerId, String skinId, String capeId, String model)
        implements CustomPacketPayload {
    public static final Type<SyncAppearanceV2Payload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "sync_appearance_v2")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "sync_appearance_v2")
            //?}
    );
    public static final StreamCodec<ByteBuf, SyncAppearanceV2Payload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeUUID(buf, payload.playerId());
                PayloadCodecs.writeString(buf, payload.skinId(), TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
                PayloadCodecs.writeString(buf, payload.capeId(), TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
                PayloadCodecs.writeString(buf, payload.model(), TextureTransferLimits.MAX_MODEL_BYTES);
            },
            buf -> new SyncAppearanceV2Payload(
                    PayloadCodecs.readUUID(buf),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_MODEL_BYTES)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
//?}
