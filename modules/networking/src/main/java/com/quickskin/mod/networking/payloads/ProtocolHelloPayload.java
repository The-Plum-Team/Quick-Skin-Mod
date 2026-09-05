package com.quickskin.mod.networking.payloads;

//? if >=1.21 {
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.networking.protocol.ProtocolOffer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/** C2S application-level negotiation offer, bound to one client-generated nonce. */
public record ProtocolHelloPayload(long nonce, ProtocolOffer offer) implements CustomPacketPayload {
    public static final Type<ProtocolHelloPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "protocol_hello")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "protocol_hello")
            //?}
    );
    public static final StreamCodec<ByteBuf, ProtocolHelloPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeLong(payload.nonce());
                writeOffer(buf, payload.offer());
            },
            buf -> new ProtocolHelloPayload(buf.readLong(), readOffer(buf)));

    private static void writeOffer(ByteBuf buf, ProtocolOffer offer) {
        buf.writeInt(offer.minimumVersion());
        buf.writeInt(offer.maximumVersion());
        buf.writeLong(offer.capabilityMask());
        buf.writeInt(offer.maximumTextureBytes());
        buf.writeInt(offer.maximumChunkBytes());
    }

    private static ProtocolOffer readOffer(ByteBuf buf) {
        return new ProtocolOffer(
                buf.readInt(), buf.readInt(), buf.readLong(), buf.readInt(), buf.readInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
