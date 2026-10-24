package com.quickskin.mod.networking.payloads;

//? if >=1.21 {
import com.quickskin.mod.platform.QuickSkinInfo;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/** S2C: the server's {@code SkinAdminStatus} for an admin's request, and how many it touched. */
public record SkinAdminResultPayload(int requestId, int status, int count) implements CustomPacketPayload {
    public static final Type<SkinAdminResultPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "skin_admin_result")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "skin_admin_result")
            //?}
    );

    public static final StreamCodec<ByteBuf, SkinAdminResultPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeInt(payload.requestId());
                buf.writeByte(payload.status());
                buf.writeInt(payload.count());
            },
            buf -> new SkinAdminResultPayload(buf.readInt(), buf.readUnsignedByte(), buf.readInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
