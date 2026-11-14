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
import java.util.WeakHashMap;
import java.util.function.Supplier;

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
 * is kept and put back before the model is posed again. An inline draw puts it back as soon as it
 * ends, into the parts the model holds then: Customizable Player Models swaps parts of its own
 * into the model for each draw, starts them from the vanilla parts' poses and leaves each vanilla
 * part with the pose it was drawn in, so a pose kept only by the part that was moved would leave
 * the move on the vanilla parts, for the next draw to start from and for the world to show.
 *
 * <p>The preview's looping walk and sit ({@link PreviewLoops}) use the same skeleton on the
 * preview's own model, laid over the pose the model was baked with.
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
    /** The six parts of the model the inline draw laid its move over, as it holds them when asked. */
    private static Supplier<ModelPart[]> inlineHeld;
    /** That model's six poses before the move, indexed by {@link Part}. */
    private static PartPose[] inlineUnmoved;
    /** Puts back what the move changed on that model besides its six parts' poses. */
    private static Runnable inlineSettle;
    /**
     * The pose each preview model part was baked with, taken the first time it is posed, before
     * anything has moved it. A part has no {@code equals}, so this is keyed by identity, and a
     * model baked again on a resource reload leaves its old parts to be collected.
     */
    private static final Map<ModelPart, PartPose> baked = new WeakHashMap<>();

    private SkinSwapPoser() {
    }

    /** Opens the inline preview draw that plays {@code pose}; a resting pose opens nothing. */
    static void beginInline(SkinSwapAnimation.Pose pose) {
        inlinePose = pose.isRest() ? null : pose;
    }

    /**
     * Closes the inline preview draw and puts back what its move changed: the poses of the model
     * it was laid over, into the parts that model holds now, and every other moved part's.
     */
    static void endInline() {
        inlinePose = null;
        if (inlineHeld != null) {
            ModelPart[] parts = inlineHeld.get();
            for (int i = 0; i < parts.length; i++) {
                unmoved.remove(parts[i]);
                write(inlineUnmoved[i], parts[i]);
                parts[i].xScale = 1.0f;
            }
            if (inlineSettle != null) {
                inlineSettle.run();
            }
            inlineHeld = null;
            inlineUnmoved = null;
            inlineSettle = null;
        }
        if (!unmoved.isEmpty()) {
            unmoved.forEach((part, pose) -> {
                write(pose, part);
                part.xScale = 1.0f;
            });
            unmoved.clear();
        }
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
//? if <1.21.2 {
        model.rightSleeve.xScale = 1.0f;
        model.leftSleeve.xScale = 1.0f;
//?}
    }

    /** Puts back the pose the move took a single part from, if it moved it. */
    public static void restore(ModelPart part) {
        PartPose pose = unmoved.remove(part);
        if (pose != null) {
            write(pose, part);
            part.xScale = 1.0f;
        }
    }

    /**
     * Puts the pivots of the preview's own model back where it was baked, so a pose that sets
     * only the angles starts from a straight stance after a loop moved them.
     */
//? if <1.21.2 {
    public static void standStraight(PlayerModel<?> model) {
//?} else {
    public static void standStraight(PlayerModel model) {
//?}
        for (ModelPart part : parts(model)) {
            PartPose pose = baked(part);
            part.x = pose.x;
            part.y = pose.y;
            part.z = pose.z;
        }
    }

    /** Poses the preview's own model in a frame of one of its loops, from the pose it was baked with. */
//? if <1.21.2 {
    public static void loop(PlayerModel<?> model, SkinSwapAnimation.Pose pose) {
//?} else {
    public static void loop(PlayerModel model, SkinSwapAnimation.Pose pose) {
//?}
        ModelPart[] parts = parts(model);
        PartPose[] poses = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) {
            PartPose rest = baked(parts[i]);
            poses[i] = new PartPose().set(rest.x, rest.y, rest.z, rest.xRot, rest.yRot, rest.zRot);
        }
        SkinSwapSkeleton.pose(pose, poses);
        for (int i = 0; i < parts.length; i++) {
            write(poses[i], parts[i]);
        }
    }

    /** Lays the move over a posed model. */
//? if <1.21.2 {
    public static void apply(PlayerModel<?> model, SkinSwapAnimation.Pose pose) {
//?} else {
    public static void apply(PlayerModel model, SkinSwapAnimation.Pose pose) {
//?}
//? if <1.21.2 {
        boolean inline = layOver(() -> parts(model), pose);
//?} else {
        layOver(() -> parts(model), pose);
//?}
        // The arms changing width: each is scaled about its pivot and moved so its inner side,
        // one pixel from the pivot in both widths, stays against the body
        float armWidth = pose.get(SkinSwapAnimation.Whole.ARM_WIDTH);
        if (armWidth != 1.0f) {
            model.rightArm.xScale = armWidth;
            model.rightArm.x += 1.0f - armWidth;
            model.leftArm.xScale = armWidth;
            model.leftArm.x -= 1.0f - armWidth;
        }
//? if <1.21.2 {
        // The outer layers are separate parts that copy their base part's pose, though not its scale.
        model.hat.copyFrom(model.head);
        model.jacket.copyFrom(model.body);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightPants.copyFrom(model.rightLeg);
        model.leftPants.copyFrom(model.leftLeg);
        model.rightSleeve.xScale = model.rightArm.xScale;
        model.leftSleeve.xScale = model.leftArm.xScale;
        if (inline) {
            inlineSettle = () -> {
                model.rightSleeve.xScale = 1.0f;
                model.leftSleeve.xScale = 1.0f;
            };
        }
//?}
    }

    /**
     * Lays the move over the six posed parts {@code held} supplies, indexed by {@link Part}, and
     * keeps their poses before it. Inside an inline draw the first model moved is the one put
     * back when the draw ends, into the parts {@code held} supplies then.
     *
     * @return whether this model is the one the inline draw puts back
     */
    static boolean layOver(Supplier<ModelPart[]> held, SkinSwapAnimation.Pose pose) {
        ModelPart[] parts = held.get();
        PartPose[] poses = new PartPose[parts.length];
        boolean inline = inlinePose != null && inlineHeld == null;
        PartPose[] before = inline ? new PartPose[parts.length] : null;
        for (int i = 0; i < parts.length; i++) {
            poses[i] = read(parts[i]);
            PartPose kept = unmoved.putIfAbsent(parts[i], read(parts[i]));
            if (inline) {
                before[i] = kept != null ? kept : read(parts[i]);
            }
        }
        if (inline) {
            inlineHeld = held;
            inlineUnmoved = before;
        }
        SkinSwapSkeleton.pose(pose, poses);
        for (int i = 0; i < parts.length; i++) {
            write(poses[i], parts[i]);
        }
        return inline;
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
    /** The model's six parts, indexed by {@link Part}. */
//? if <1.21.2 {
    private static ModelPart[] parts(PlayerModel<?> model) {
//?} else {
    private static ModelPart[] parts(PlayerModel model) {
//?}
        ModelPart[] parts = new ModelPart[Part.values().length];
        parts[Part.HEAD.ordinal()] = model.head;
        parts[Part.BODY.ordinal()] = model.body;
        parts[Part.RIGHT_ARM.ordinal()] = model.rightArm;
        parts[Part.LEFT_ARM.ordinal()] = model.leftArm;
        parts[Part.RIGHT_LEG.ordinal()] = model.rightLeg;
        parts[Part.LEFT_LEG.ordinal()] = model.leftLeg;
        return parts;
    }

    private static PartPose baked(ModelPart part) {
        return baked.computeIfAbsent(part, SkinSwapPoser::read);
    }

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
