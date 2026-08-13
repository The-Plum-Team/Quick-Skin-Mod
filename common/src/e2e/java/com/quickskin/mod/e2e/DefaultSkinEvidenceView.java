package com.quickskin.mod.e2e;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Composes an unambiguous default-skin checkpoint around the complete local player model.
 *
 * <p>An isolated first-person sleeve is not enough evidence for Minecraft's modern built-in
 * defaults: valid skins such as Noor and Makena intentionally use red or yellow clothing that can
 * look custom when the rest of the model is outside the frame. The harness still proves the exact
 * UUID-selected texture separately; this view makes the same fact independently inspectable by a
 * human or semantic reviewer.</p>
 */
public final class DefaultSkinEvidenceView {

    /** Third-person-back normally places the camera about four blocks behind the player. */
    private static final double REMOTE_BEHIND_CAMERA_CLEARANCE = 8.0;

    private DefaultSkinEvidenceView() {}

    /**
     * Hold a stable full-body rear view of the local player.
     *
     * @param requireRemoteBehind when true, wait for the other player and keep it far enough behind
     *                            the third-person camera that it cannot leak into the checkpoint
     */
    public static boolean hold(Minecraft mc, boolean requireRemoteBehind) {
        try {
            if (mc == null || mc.player == null || mc.options == null) return false;

            VanillaShim.setScreen(mc, null);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            mc.options.keyShift.setDown(false);
            mc.player.setShiftKeyDown(false);

            if (!requireRemoteBehind) {
                pinPlayer(mc, 180f);
                return true;
            }

            AbstractClientPlayer remote = findOther(mc);
            if (remote == null) {
                pinPlayer(mc, 180f);
                return false;
            }

            double awayX = mc.player.getX() - remote.getX();
            double awayZ = mc.player.getZ() - remote.getZ();
            double distance = Math.hypot(awayX, awayZ);
            if (distance < REMOTE_BEHIND_CAMERA_CLEARANCE) {
                // Move in small server-accepted increments. Once settled, the remote player is
                // beyond the ordinary third-person camera offset and therefore behind the camera.
                double stepX = distance < 0.01 ? 1.0 : awayX / distance;
                double stepZ = distance < 0.01 ? 0.0 : awayZ / distance;
                mc.player.setDeltaMovement(0, 0, 0);
                mc.player.setPos(
                        mc.player.getX() + stepX * 0.25,
                        mc.player.getY(),
                        mc.player.getZ() + stepZ * 0.25);
                return false;
            }

            float yaw = (float) Math.toDegrees(Math.atan2(-awayX, awayZ));
            pinPlayer(mc, yaw);

            double remoteX = (remote.getX() - mc.player.getX()) / distance;
            double remoteZ = (remote.getZ() - mc.player.getZ()) / distance;
            double lookX = -Math.sin(Math.toRadians(yaw));
            double lookZ = Math.cos(Math.toRadians(yaw));
            return lookX * remoteX + lookZ * remoteZ <= -0.95;
        } catch (Throwable t) {
            E2ELog.warn("default-skin evidence composition failed: " + t);
            return false;
        }
    }

    /** Restore the observer camera before framing another player. */
    public static void enterFirstPerson(Minecraft mc) {
        if (mc != null && mc.options != null) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        VanillaShim.setScreen(mc, null);
    }

    private static void pinPlayer(Minecraft mc, float yaw) {
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setYHeadRot(yaw);
        mc.player.yHeadRotO = yaw;
        mc.player.setYBodyRot(yaw);
        mc.player.yBodyRotO = yaw;
        mc.player.setXRot(0f);
        mc.player.xRotO = 0f;
    }

    private static AbstractClientPlayer findOther(Minecraft mc) {
        if (mc.level == null) return null;
        UUID localId = mc.player.getUUID();
        for (Player player : mc.level.players()) {
            if (player instanceof AbstractClientPlayer clientPlayer
                    && !clientPlayer.getUUID().equals(localId)) {
                return clientPlayer;
            }
        }
        return null;
    }
}
