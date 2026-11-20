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
 * S2C: the immersive pose another player in the recipient's world plays now (ADR 0019),
 * {@link ImmersivePose#OFF} when it ended. Sent only to a negotiated session that supports
 * {@code immersive-poses}.
 */
public record SyncImmersivePosePayload(UUID playerId, ImmersivePose pose) implements CustomPacketPayload {
    public static final Type<SyncImmersivePosePayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "sync_immersive_pose")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "sync_immersive_pose")
            //?}
    );

    public static final StreamCodec<ByteBuf, SyncImmersivePosePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeUUID(buf, payload.playerId());
                ImmersivePoseCodec.write(buf, payload.pose());
            },
            buf -> new SyncImmersivePosePayload(PayloadCodecs.readUUID(buf), ImmersivePoseCodec.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
