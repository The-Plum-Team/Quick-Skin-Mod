package com.quickskin.mod.networking.payloads;

//? if >=1.21 {
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.networking.protocol.ProtocolAcknowledgement;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/** S2C acknowledgement for one exact hello nonce. */
public record ProtocolAckPayload(long nonce, ProtocolAcknowledgement acknowledgement)
        implements CustomPacketPayload {
    public static final Type<ProtocolAckPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "protocol_ack")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "protocol_ack")
            //?}
    );
    public static final StreamCodec<ByteBuf, ProtocolAckPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                ProtocolAcknowledgement ack = payload.acknowledgement();
                buf.writeLong(payload.nonce());
                buf.writeBoolean(ack.accepted());
                buf.writeInt(ack.selectedVersion());
                buf.writeLong(ack.capabilityMask());
                buf.writeInt(ack.maximumTextureBytes());
                buf.writeInt(ack.maximumChunkBytes());
            },
            buf -> new ProtocolAckPayload(
                    buf.readLong(),
                    new ProtocolAcknowledgement(
                            buf.readBoolean(), buf.readInt(), buf.readLong(),
                            buf.readInt(), buf.readInt())));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
