package com.quickskin.mod.runtime;

import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import com.quickskin.mod.client.rendering.SkinSwapAnimation;
import com.quickskin.mod.client.rendering.WorldPose;
import com.quickskin.mod.networking.ImmersivePose;
import com.quickskin.mod.networking.ImmersivePoseSyncService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Tells the server, once a client tick, the pose the local player plays in this client's
 * immersive view (ADR 0019): the skin menu's pose, the way the body faces the camera's resting
 * place, whether the view is closing, and the skin-change moves made. {@link ImmersivePoseSyncService}
 * decides what of it to send. Outside a view, or while the menu shows a CPM model, the player
 * plays no pose. When the client changes world it forgets the other players' poses told in the
 * last one.
 */
@Environment(EnvType.CLIENT)
public final class ImmersivePoseBroadcast {
    /** When the skin-change move last counted started, so each move is counted once. */
    private static long countedSwapStart = Long.MIN_VALUE;
    private static int swaps;
    private static int swapVariant;

    private ImmersivePoseBroadcast() {
    }

    /** The world the other players' poses were last kept for. */
    private static Object keptWorld;

    /** Client thread, after each client tick. */
    public static void tick() {
        Object world = net.minecraft.client.Minecraft.getInstance().level;
        if (world != keptWorld) {
            // The server tells no client that left a world the end of the poses played there
            keptWorld = world;
            com.quickskin.mod.common.data.RemoteImmersivePoses.getInstance().keepOnly(world);
            WorldPose.clearOthers();
        }
        ImmersivePoseSyncService.getInstance().update(current());
    }

    private static ImmersivePose current() {
        PreviewPlayerData preview = ImmersiveMode.running() ? WorldPose.playingPreview(ImmersiveMode.session()) : null;
        if (preview == null) {
            return ImmersivePose.OFF;
        }
        SkinSwapAnimation.Variant variant = preview.getSkinSwapVariant();
        if (variant != null && preview.getSkinSwapStartNanos() != countedSwapStart) {
            countedSwapStart = preview.getSkinSwapStartNanos();
            swaps = (swaps + 1) & ImmersivePose.MAX_SWAPS;
            swapVariant = Math.min(variant.ordinal(), ImmersivePose.MAX_SWAP_VARIANT);
        }
        return new ImmersivePose(
                ImmersivePose.poseOf(preview.getCurrentAnimation()),
                ImmersivePose.wrapYaw(ImmersiveMode.faceYawTarget()),
                ImmersiveMode.active() ? 0 : ImmersivePose.CLOSING,
                swaps,
                swapVariant);
    }
}
