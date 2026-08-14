package com.quickskin.mod.e2e;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;
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

    /**
     * Hold one player in a fully deterministic standing pose for a visual checkpoint.
     *
     * <p>Network interpolation keeps separate previous/current head and body rotations. Updating
     * only the ordinary yaw can therefore leave a remote player's head apparently looking through
     * its own cape for a frame, which makes an otherwise correct rear-view screenshot semantically
     * ambiguous. Pinning every render-relevant value is limited to the disposable E2E client and
     * does not change production rendering.</p>
     */
    public static void pinStandingPose(Player player, float yaw) {
        player.setDeltaMovement(0, 0, 0);
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setYHeadRot(yaw);
        player.yHeadRotO = yaw;
        player.setYBodyRot(yaw);
        player.yBodyRotO = yaw;
        player.setXRot(0f);
        player.xRotO = 0f;
    }

    /**
     * Deterministically prove that an observer used for cape evidence is behind the subject and
     * that every interpolated subject rotation still carries the requested pose.
     */
    public static Step.Result checkRearView(Player subject, Player observer, float expectedYaw) {
        float yawError = angularError(subject.getYRot(), expectedYaw);
        float headError = angularError(subject.getYHeadRot(), expectedYaw);
        float bodyError = angularError(subject.yBodyRot, expectedYaw);
        if (yawError > 0.5f || headError > 0.5f || bodyError > 0.5f) {
            return Step.Result.fail(String.format(Locale.ROOT,
                    "rear-view subject pose drifted: yaw=%.1f head=%.1f body=%.1f expected=%.1f",
                    subject.getYRot(), subject.getYHeadRot(), subject.yBodyRot, expectedYaw));
        }

        double observerX = observer.getX() - subject.getX();
        double observerZ = observer.getZ() - subject.getZ();
        double distance = Math.hypot(observerX, observerZ);
        if (distance < 0.1) {
            return Step.Result.fail("rear-view observer overlaps the subject");
        }
        double radians = Math.toRadians(subject.getYRot());
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double observerCos = (forwardX * observerX + forwardZ * observerZ) / distance;
        if (observerCos > -0.90) {
            return Step.Result.fail(String.format(Locale.ROOT,
                    "observer is not behind the subject: rearCos=%.3f", observerCos));
        }
        return Step.Result.pass(String.format(Locale.ROOT,
                "fixed rear-view pose: subject yaw/head/body=%.0f/%.0f/%.0f, observer rearCos=%.3f",
                subject.getYRot(), subject.getYHeadRot(), subject.yBodyRot, observerCos));
    }

    private static void pinPlayer(Minecraft mc, float yaw) {
        pinStandingPose(mc.player, yaw);
    }

    private static float angularError(float actual, float expected) {
        float difference = (actual - expected) % 360.0f;
        if (difference < -180.0f) difference += 360.0f;
        if (difference > 180.0f) difference -= 360.0f;
        return Math.abs(difference);
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
