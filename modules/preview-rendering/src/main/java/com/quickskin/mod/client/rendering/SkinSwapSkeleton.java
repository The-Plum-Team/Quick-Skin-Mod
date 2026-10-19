package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.rendering.SkinSwapAnimation.Axis;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Bone;

/**
 * Lays the skin-change move's skeleton over the vanilla player model's six parts.
 *
 * <p>Vanilla poses the head, the body, the arms and the legs as siblings, each a pivot and Euler
 * angles applied Z, then Y, then X about the model's axes. The move's skeleton nests them the
 * Bedrock way instead: the root turns about the feet, the waist about the hips and carries the
 * body, and the body turns about the neck and carries the head and the arms; the legs hang from
 * the root. Each part's pose is that chain applied to the part's own pose as vanilla, or the
 * preview's idle, left it, with the bone's own rotation added to the part's angles; the result is
 * written back as a pivot and Euler angles, so it renders exactly as a nested model would.
 *
 * <p>Pure geometry on plain numbers, so it is the same on every Minecraft version.
 */
public final class SkinSwapSkeleton {

    /** The vanilla parts, in the order {@link #pose} takes them. */
    public enum Part {
        HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG
    }

    /** A part's pivot in model pixels and its Euler angles in radians, as a {@code ModelPart} holds them. */
    public static final class PartPose {
        public float x;
        public float y;
        public float z;
        public float xRot;
        public float yRot;
        public float zRot;

        public PartPose set(float x, float y, float z, float xRot, float yRot, float zRot) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.xRot = xRot;
            this.yRot = yRot;
            this.zRot = zRot;
            return this;
        }
    }

    private static final double[] FEET = {0.0, 24.0, 0.0};
    private static final double[] HIPS = {0.0, 12.0, 0.0};

    private SkinSwapSkeleton() {
    }

    /** Poses the six parts, indexed by {@link Part}, in place. */
    public static void pose(SkinSwapAnimation.Pose pose, PartPose[] parts) {
        PartPose body = parts[Part.BODY.ordinal()];
        Affine root = bone(pose, Bone.ROOT, FEET);
        Affine waist = root.times(bone(pose, Bone.WAIST, HIPS));
        Affine upper = waist.times(bone(pose, Bone.BODY, new double[] {body.x, body.y, body.z}));

        place(upper, body, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        placeChild(upper, pose, Bone.HEAD, parts[Part.HEAD.ordinal()]);
        placeChild(upper, pose, Bone.RIGHT_ARM, parts[Part.RIGHT_ARM.ordinal()]);
        placeChild(upper, pose, Bone.LEFT_ARM, parts[Part.LEFT_ARM.ordinal()]);
        placeChild(root, pose, Bone.RIGHT_LEG, parts[Part.RIGHT_LEG.ordinal()]);
        placeChild(root, pose, Bone.LEFT_LEG, parts[Part.LEFT_LEG.ordinal()]);
    }

    /**
     * Poses a body part on its own, as {@link #pose} would: the body of the model a cape hangs
     * from, so the cape follows the body.
     */
    public static void poseBody(SkinSwapAnimation.Pose pose, PartPose body) {
        Affine root = bone(pose, Bone.ROOT, FEET);
        Affine waist = root.times(bone(pose, Bone.WAIST, HIPS));
        Affine upper = waist.times(bone(pose, Bone.BODY, new double[] {body.x, body.y, body.z}));
        place(upper, body, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    /**
     * The move's transform of a body resting at the origin, as a row-major 3x3 rotation followed
     * by a translation in model pixels: for a cape drawn in the model's space instead of the body's.
     */
    public static float[] bodyTransform(SkinSwapAnimation.Pose pose) {
        Affine root = bone(pose, Bone.ROOT, FEET);
        Affine waist = root.times(bone(pose, Bone.WAIST, HIPS));
        Affine upper = waist.times(bone(pose, Bone.BODY, new double[] {0.0, 0.0, 0.0}));
        float[] transform = new float[12];
        for (int i = 0; i < 9; i++) {
            transform[i] = (float) upper.r[i];
        }
        for (int i = 0; i < 3; i++) {
            transform[9 + i] = (float) upper.t[i];
        }
        return transform;
    }

    /** A child part: its bone's offset moves its pivot and its bone's rotation adds to its angles. */
    private static void placeChild(Affine parent, SkinSwapAnimation.Pose pose, Bone bone, PartPose part) {
        place(parent, part,
                pose.get(bone, Axis.POS_X), pose.get(bone, Axis.POS_Y), pose.get(bone, Axis.POS_Z),
                radians(pose.get(bone, Axis.ROT_X)), radians(pose.get(bone, Axis.ROT_Y)),
                radians(pose.get(bone, Axis.ROT_Z)));
    }

    private static void place(Affine parent, PartPose part, float dx, float dy, float dz,
                              float dxRot, float dyRot, float dzRot) {
        Affine local = new Affine(
                rotation(part.xRot + dxRot, part.yRot + dyRot, part.zRot + dzRot),
                new double[] {part.x + dx, part.y + dy, part.z + dz});
        Affine placed = parent.times(local);
        part.x = (float) placed.t[0];
        part.y = (float) placed.t[1];
        part.z = (float) placed.t[2];
        double[] r = placed.r;
        // r = Rz(c) Ry(b) Rx(a): r[6] = -sin b, r[7] = cos b sin a, r[8] = cos b cos a,
        // r[3] = sin c cos b, r[0] = cos c cos b.
        part.xRot = (float) Math.atan2(r[7], r[8]);
        part.yRot = (float) Math.asin(Math.max(-1.0, Math.min(1.0, -r[6])));
        part.zRot = (float) Math.atan2(r[3], r[0]);
    }

    /** A bone's transform: its rotation about {@code pivot}, then its offset. */
    private static Affine bone(SkinSwapAnimation.Pose pose, Bone bone, double[] pivot) {
        double[] r = rotation(radians(pose.get(bone, Axis.ROT_X)), radians(pose.get(bone, Axis.ROT_Y)),
                radians(pose.get(bone, Axis.ROT_Z)));
        double[] turned = apply(r, pivot);
        return new Affine(r, new double[] {
                pose.get(bone, Axis.POS_X) + pivot[0] - turned[0],
                pose.get(bone, Axis.POS_Y) + pivot[1] - turned[1],
                pose.get(bone, Axis.POS_Z) + pivot[2] - turned[2]});
    }

    /** Rz(z) Ry(y) Rx(x), row-major, the order a {@code ModelPart} applies its angles in. */
    static double[] rotation(double x, double y, double z) {
        double ca = Math.cos(x);
        double sa = Math.sin(x);
        double cb = Math.cos(y);
        double sb = Math.sin(y);
        double cc = Math.cos(z);
        double sc = Math.sin(z);
        return new double[] {
                cc * cb, cc * sb * sa - sc * ca, cc * sb * ca + sc * sa,
                sc * cb, sc * sb * sa + cc * ca, sc * sb * ca - cc * sa,
                -sb, cb * sa, cb * ca};
    }

    private static double[] apply(double[] r, double[] v) {
        return new double[] {
                r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
                r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
                r[6] * v[0] + r[7] * v[1] + r[8] * v[2]};
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    /** x -> r x + t. */
    private record Affine(double[] r, double[] t) {
        Affine times(Affine inner) {
            double[] product = new double[9];
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    product[row * 3 + column] = r[row * 3] * inner.r[column]
                            + r[row * 3 + 1] * inner.r[3 + column]
                            + r[row * 3 + 2] * inner.r[6 + column];
                }
            }
            double[] moved = apply(r, inner.t);
            return new Affine(product, new double[] {moved[0] + t[0], moved[1] + t[1], moved[2] + t[2]});
        }
    }
}
