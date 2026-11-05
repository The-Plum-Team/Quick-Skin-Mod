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

/** C2S appearance with the elytra's own cape, on a session that negotiated {@code elytra-texture}. */
public record UpdateAppearanceElytraPayload(UUID playerId, String skinId, String capeId, String elytraId,
        String model)
        implements CustomPacketPayload {
    public static final Type<UpdateAppearanceElytraPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "update_appearance_elytra")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "update_appearance_elytra")
            //?}
    );
    public static final StreamCodec<ByteBuf, UpdateAppearanceElytraPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeUUID(buf, payload.playerId());
                PayloadCodecs.writeString(buf, payload.skinId(), TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
                PayloadCodecs.writeString(buf, payload.capeId(), TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
                PayloadCodecs.writeString(buf, payload.elytraId(), TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
                PayloadCodecs.writeString(buf, payload.model(), TextureTransferLimits.MAX_MODEL_BYTES);
            },
            buf -> new UpdateAppearanceElytraPayload(
                    PayloadCodecs.readUUID(buf),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_MODEL_BYTES)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
//?}
