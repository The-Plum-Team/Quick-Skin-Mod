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

/** Exact request acknowledgement emitted only after every paced appearance was sent. */
public record AppearanceSnapshotCompletePayload(long requestId) implements CustomPacketPayload {
    public static final Type<AppearanceSnapshotCompletePayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "appearance_snapshot_complete")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "appearance_snapshot_complete")
            //?}
    );

    public static final StreamCodec<ByteBuf, AppearanceSnapshotCompletePayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeLong(payload.requestId()),
                    buf -> new AppearanceSnapshotCompletePayload(buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
