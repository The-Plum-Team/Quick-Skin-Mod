package com.quickskin.mod.networking.payloads;

//? if >=1.21 {
import com.quickskin.mod.networking.TextureTransferLimits;
import com.quickskin.mod.platform.QuickSkinInfo;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/**
 * S2C, to the player concerned: the skin an admin gave them for now ({@code local_skin:} id and
 * model), which other players see instead of theirs, or an empty id once it is taken back.
 */
public record AssignedSkinPayload(String skinId, String model) implements CustomPacketPayload {
    public static final Type<AssignedSkinPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "assigned_skin")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "assigned_skin")
            //?}
    );

    public static final StreamCodec<ByteBuf, AssignedSkinPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeString(buf, payload.skinId(), TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
                PayloadCodecs.writeString(buf, payload.model(), TextureTransferLimits.MAX_MODEL_BYTES);
            },
            buf -> new AssignedSkinPayload(
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_MODEL_BYTES)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
