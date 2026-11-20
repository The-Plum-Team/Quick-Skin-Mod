package com.quickskin.mod.networking;

import io.netty.buffer.ByteBuf;

/**
 * The one layout of an {@link ImmersivePose} on the wire, after the player's UUID, used by both
 * {@code immersive_pose} and {@code sync_immersive_pose} in every era: pose, flags, yaw, swaps
 * and the latest move, {@link ImmersivePose#WIRE_BYTES} in all. Reading checks nothing: the
 * receiver validates the numbers before using them.
 */
public final class ImmersivePoseCodec {
    private ImmersivePoseCodec() {
    }

    public static void write(ByteBuf buf, ImmersivePose pose) {
        buf.writeByte(pose.pose());
        buf.writeByte(pose.flags());
        buf.writeFloat(pose.yaw());
        buf.writeByte(pose.swaps());
        buf.writeByte(pose.swapVariant());
    }

    public static ImmersivePose read(ByteBuf buf) {
        int pose = buf.readUnsignedByte();
        int flags = buf.readUnsignedByte();
        float yaw = buf.readFloat();
        int swaps = buf.readUnsignedByte();
        int variant = buf.readUnsignedByte();
        return new ImmersivePose(pose, yaw, flags, swaps, variant);
    }
}
