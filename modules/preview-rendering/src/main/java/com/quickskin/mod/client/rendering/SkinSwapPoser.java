package com.quickskin.mod.client.rendering;

//? if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
//?}
import com.quickskin.mod.client.rendering.SkinSwapSkeleton.Part;
import com.quickskin.mod.client.rendering.SkinSwapSkeleton.PartPose;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <1.21.11 {
import net.minecraft.client.model.PlayerModel;
//?} else {
import net.minecraft.client.model.player.PlayerModel;
//?}
import net.minecraft.client.model.geom.ModelPart;

//? if <1.21.2 {
import org.joml.Matrix3f;
import org.joml.Matrix4f;
//?}

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Puts the skin-change move on a vanilla player model, and tells the models' pose hooks which
 * draws are previews playing one.
 *
 * <p>The move is laid over the pose the model already has for the frame: the preview's own idle
 * when it draws a bare model, or vanilla's when it draws the live player, in which case
 * {@code PreviewSkinSwapPoseMixin} calls in at the end of {@code setupAnim}. The hook only acts for
 * a preview draw. Before 1.21.6 that draw runs inline, so the move is scoped to the call; from
 * 1.21.6 the GUI renders it later from its render state, so the move is bound to that state object
 * and every other render state, the world's included, finds nothing.
 *
 * <p>The move moves the parts' pivots as well as their angles, and neither vanilla before 1.21.2
 * nor the preview's idle resets every one of them each frame, so each part's pose before the move
 * is kept and put back before the model is posed again.
 */
@Environment(EnvType.CLIENT)
public final class SkinSwapPoser {

    private static final int BOUND_STATES = 8;

    private static SkinSwapAnimation.Pose inlinePose;
    private static final Object[] boundStates = new Object[BOUND_STATES];
    private static final SkinSwapAnimation.Pose[] boundPoses = new SkinSwapAnimation.Pose[BOUND_STATES];
    private static int nextBinding;
    /** Each moved part's pose before the move, until it is put back. */
    private static final Map<ModelPart, PartPose> unmoved = new IdentityHashMap<>();

    private SkinSwapPoser() {
    }

    /** Opens the inline preview draw that plays {@code pose}; a resting pose opens nothing. */
    static void beginInline(SkinSwapAnimation.Pose pose) {
        inlinePose = pose.isRest() ? null : pose;
    }

    static void endInline() {
        inlinePose = null;
    }

    /**
     * Binds {@code pose} to a render state the GUI draws after this frame is extracted. The last
     * few bindings are kept, since each frame extracts a fresh state, so nothing has to release them.
     */
    static void bind(Object renderState, SkinSwapAnimation.Pose pose) {
        if (pose.isRest()) {
            return;
        }
        boundStates[nextBinding] = renderState;
        boundPoses[nextBinding] = pose;
        nextBinding = (nextBinding + 1) % BOUND_STATES;
    }

    /**
     * The move the model posed for {@code renderState} (the entity itself before 1.21.2) plays, or
     * null when it is not a preview playing one.
     */
    public static SkinSwapAnimation.Pose poseFor(Object renderState) {
        if (inlinePose != null) {
            return inlinePose;
        }
        for (int i = 0; i < BOUND_STATES; i++) {
            if (boundStates[i] == renderState) {
                return boundPoses[i];
            }
        }
        return null;
    }

    /** Puts back the pose the move took the model's parts from, if it moved them. */
//? if <1.21.2 {
    public static void restore(PlayerModel<?> model) {
//?} else {
    public static void restore(PlayerModel model) {
//?}
        if (unmoved.isEmpty()) {
            return;
        }
        restore(model.head);
        restore(model.body);
        restore(model.rightArm);
        restore(model.leftArm);
        restore(model.rightLeg);
        restore(model.leftLeg);
    }

    /** Puts back the pose the move took a single part from, if it moved it. */
    public static void restore(ModelPart part) {
        PartPose pose = unmoved.remove(part);
        if (pose != null) {
            write(pose, part);
        }
    }

    /** Lays the move over a posed model. */
//? if <1.21.2 {
    public static void apply(PlayerModel<?> model, SkinSwapAnimation.Pose pose) {
//?} else {
    public static void apply(PlayerModel model, SkinSwapAnimation.Pose pose) {
//?}
        ModelPart[] parts = new ModelPart[Part.values().length];
        parts[Part.HEAD.ordinal()] = model.head;
        parts[Part.BODY.ordinal()] = model.body;
        parts[Part.RIGHT_ARM.ordinal()] = model.rightArm;
        parts[Part.LEFT_ARM.ordinal()] = model.leftArm;
        parts[Part.RIGHT_LEG.ordinal()] = model.rightLeg;
        parts[Part.LEFT_LEG.ordinal()] = model.leftLeg;
        PartPose[] poses = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) {
            poses[i] = read(parts[i]);
            unmoved.putIfAbsent(parts[i], read(parts[i]));
        }
        SkinSwapSkeleton.pose(pose, poses);
        for (int i = 0; i < parts.length; i++) {
            write(poses[i], parts[i]);
        }
//? if <1.21.2 {
        // The outer layers are separate parts that copy their base part's pose.
        model.hat.copyFrom(model.head);
        model.jacket.copyFrom(model.body);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightPants.copyFrom(model.rightLeg);
        model.leftPants.copyFrom(model.leftLeg);
//?}
    }

    /** Lays the move over the posed body of the model a cape hangs from. */
    public static void applyToBody(ModelPart body, SkinSwapAnimation.Pose pose) {
        PartPose posed = read(body);
        unmoved.putIfAbsent(body, read(body));
        SkinSwapSkeleton.poseBody(pose, posed);
        write(posed, body);
    }

//? if <1.21.2 {
    /**
     * Before 1.21.2 the cape is drawn in the model's own space rather than hanging from the body,
     * so the move's transform of the body is put on the cape's pose instead.
     */
    public static void transformCape(PoseStack poseStack, SkinSwapAnimation.Pose pose) {
        float[] m = SkinSwapSkeleton.bodyTransform(pose);
        // Column-major, and from model pixels to the pose stack's blocks.
        Matrix4f transform = new Matrix4f(
                m[0], m[3], m[6], 0.0f,
                m[1], m[4], m[7], 0.0f,
                m[2], m[5], m[8], 0.0f,
                m[9] / 16.0f, m[10] / 16.0f, m[11] / 16.0f, 1.0f);
        poseStack.last().pose().mul(transform);
        poseStack.last().normal().mul(new Matrix3f(transform));
    }

//?}
    private static PartPose read(ModelPart part) {
        return new PartPose().set(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot);
    }

    private static void write(PartPose pose, ModelPart part) {
        part.x = pose.x;
        part.y = pose.y;
        part.z = pose.z;
        part.xRot = pose.xRot;
        part.yRot = pose.yRot;
        part.zRot = pose.zRot;
    }
}
