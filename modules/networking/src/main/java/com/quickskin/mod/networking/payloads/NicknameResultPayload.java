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
 * S2C answer to {@link SetNicknamePayload}: the request it answers, a
 * {@link com.quickskin.mod.networking.NicknameStatus} code, the nickname the player has now and,
 * for a cooldown, how long until it may change.
 */
public record NicknameResultPayload(int requestId, int status, String nickname, long retryAfterMillis)
        implements CustomPacketPayload {
    public static final Type<NicknameResultPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "nickname_result")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "nickname_result")
            //?}
    );

    public static final StreamCodec<ByteBuf, NicknameResultPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeInt(payload.requestId());
                buf.writeByte(payload.status());
                PayloadCodecs.writeString(buf, payload.nickname(), TextureTransferLimits.MAX_NICKNAME_BYTES);
                buf.writeLong(payload.retryAfterMillis());
            },
            buf -> new NicknameResultPayload(
                    buf.readInt(),
                    buf.readUnsignedByte(),
                    PayloadCodecs.readString(buf, TextureTransferLimits.MAX_NICKNAME_BYTES),
                    buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
