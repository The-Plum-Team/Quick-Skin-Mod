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

import java.util.UUID;

/**
 * C2S: the player's cosmetic nickname, {@code ""} for none. Sent only on a negotiated session
 * that supports {@code nicknames}; the server answers with {@link NicknameResultPayload} carrying
 * the same request number.
 */
public record SetNicknamePayload(UUID playerId, int requestId, String nickname)
        implements CustomPacketPayload {
    public static final Type<SetNicknamePayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "set_nickname")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "set_nickname")
            //?}
    );

    public static final StreamCodec<ByteBuf, SetNicknamePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeUUID(buf, payload.playerId());
                buf.writeInt(payload.requestId());
                PayloadCodecs.writeString(buf, payload.nickname(), TextureTransferLimits.MAX_NICKNAME_BYTES);
            },
            buf -> new SetNicknamePayload(
                    PayloadCodecs.readUUID(buf),
                    buf.readInt(),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_NICKNAME_BYTES)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
