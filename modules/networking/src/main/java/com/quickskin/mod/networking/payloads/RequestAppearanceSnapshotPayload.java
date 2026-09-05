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

import java.util.UUID;

/** C2S retryable request for a complete, paced view of active player appearances. */
public record RequestAppearanceSnapshotPayload(UUID playerId, long requestId)
        implements CustomPacketPayload {
    public static final Type<RequestAppearanceSnapshotPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "request_appearance_snapshot")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "request_appearance_snapshot")
            //?}
    );

    public static final StreamCodec<ByteBuf, RequestAppearanceSnapshotPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        PayloadCodecs.writeUUID(buf, payload.playerId());
                        buf.writeLong(payload.requestId());
                    },
                    buf -> new RequestAppearanceSnapshotPayload(
                            PayloadCodecs.readUUID(buf), buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
