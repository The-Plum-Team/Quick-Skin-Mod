package com.quickskin.mod.networking.payloads;

//? if >=1.21 {
import com.quickskin.mod.networking.ImmersivePose;
import com.quickskin.mod.networking.ImmersivePoseCodec;
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
 * C2S: the pose the player plays while their immersive skin menu floats round them (ADR 0019),
 * {@link ImmersivePose#OFF} once it has closed. Sent only on a negotiated session that supports
 * {@code immersive-poses}, when the pose changes and every few seconds while it lasts; the
 * sequence number grows with each one.
 */
public record ImmersivePosePayload(UUID playerId, int sequence, ImmersivePose pose) implements CustomPacketPayload {
    public static final Type<ImmersivePosePayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "immersive_pose")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "immersive_pose")
            //?}
    );

    public static final StreamCodec<ByteBuf, ImmersivePosePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeUUID(buf, payload.playerId());
                buf.writeInt(payload.sequence());
                ImmersivePoseCodec.write(buf, payload.pose());
            },
            buf -> new ImmersivePosePayload(PayloadCodecs.readUUID(buf), buf.readInt(), ImmersivePoseCodec.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
