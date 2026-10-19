package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.rendering.SkinSwapAnimation.Axis;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Bone;
import com.quickskin.mod.client.rendering.SkinSwapSkeleton.Part;
import com.quickskin.mod.client.rendering.SkinSwapSkeleton.PartPose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skin-change move's skeleton nests the vanilla player model's sibling parts the Bedrock way:
 * the waist carries the body, the body carries the head and the arms, and the root carries all of
 * them about the feet.
 */
class SkinSwapSkeletonTest {

    private static final float EPSILON = 1.0e-4f;

    /** The vanilla player model's standing pose, as {@code PlayerModel} lays its parts out. */
    private static PartPose[] standing() {
        PartPose[] parts = new PartPose[Part.values().length];
        parts[Part.HEAD.ordinal()] = new PartPose().set(0, 0, 0, 0, 0, 0);
        parts[Part.BODY.ordinal()] = new PartPose().set(0, 0, 0, 0, 0, 0);
        parts[Part.RIGHT_ARM.ordinal()] = new PartPose().set(-5, 2, 0, 0.1f, 0, 0.05f);
        parts[Part.LEFT_ARM.ordinal()] = new PartPose().set(5, 2, 0, -0.1f, 0, -0.05f);
        parts[Part.RIGHT_LEG.ordinal()] = new PartPose().set(-1.9f, 12, 0, 0.2f, 0, 0);
        parts[Part.LEFT_LEG.ordinal()] = new PartPose().set(1.9f, 12, 0, -0.2f, 0, 0);
        return parts;
    }

    private static PartPose part(PartPose[] parts, Part part) {
        return parts[part.ordinal()];
    }

    private static PartPose[] posed(Bone bone, Axis axis, float value) {
        PartPose[] parts = standing();
        SkinSwapSkeleton.pose(SkinSwapAnimation.withBone(bone, axis, value), parts);
        return parts;
    }

    @Test
    void restLeavesEveryPartAsItWas() {
        PartPose[] parts = standing();
        SkinSwapSkeleton.pose(SkinSwapAnimation.Pose.REST, parts);
        PartPose[] expected = standing();
        for (Part part : Part.values()) {
            assertSame(expected[part.ordinal()], parts[part.ordinal()], part.name());
        }
    }

    /** Bowing at the waist pivots the head, body and arms about the hips; the legs stay. */
    @Test
    void theWaistCarriesTheUpperBody() {
        PartPose[] parts = posed(Bone.WAIST, Axis.ROT_X, 30.0f);
        float bow = (float) Math.toRadians(30.0);
        // The neck sits 12 pixels above the hips: it swings forward (-Z) and down (+Y).
        PartPose head = part(parts, Part.HEAD);
        assertEquals(12.0 - 12.0 * Math.cos(bow), head.y, EPSILON);
        assertEquals(-12.0 * Math.sin(bow), head.z, EPSILON);
        assertEquals(bow, head.xRot, EPSILON);
        assertEquals(bow, part(parts, Part.BODY).xRot, EPSILON);
        // The arms keep their own swing on top of the bow; their slight roll tilts the sum a hair.
        assertEquals(0.1f + bow, part(parts, Part.RIGHT_ARM).xRot, 1.0e-3);
        assertSame(standing()[Part.RIGHT_LEG.ordinal()], part(parts, Part.RIGHT_LEG), "right leg");
        assertSame(standing()[Part.LEFT_LEG.ordinal()], part(parts, Part.LEFT_LEG), "left leg");
    }

    /** Lowering the waist sinks the upper body into the hips, which reads as bent knees. */
    @Test
    void loweringTheWaistLeavesTheLegs() {
        PartPose[] parts = posed(Bone.WAIST, Axis.POS_Y, 2.5f);
        assertEquals(2.5f, part(parts, Part.HEAD).y, EPSILON);
        assertEquals(2.5f, part(parts, Part.BODY).y, EPSILON);
        assertEquals(4.5f, part(parts, Part.RIGHT_ARM).y, EPSILON);
        assertEquals(12.0f, part(parts, Part.RIGHT_LEG).y, EPSILON);
    }

    /** A body twist carries the shoulders round the neck: the arm on the turning side goes back. */
    @Test
    void theBodyCarriesTheShoulders() {
        PartPose[] parts = posed(Bone.BODY, Axis.ROT_Y, 30.0f);
        float twist = (float) Math.toRadians(30.0);
        PartPose right = part(parts, Part.RIGHT_ARM);
        PartPose left = part(parts, Part.LEFT_ARM);
        assertEquals(-5.0 * Math.cos(twist), right.x, EPSILON);
        assertEquals(5.0 * Math.sin(twist), right.z, EPSILON);
        assertEquals(5.0 * Math.cos(twist), left.x, EPSILON);
        assertEquals(-5.0 * Math.sin(twist), left.z, EPSILON);
        assertEquals(twist, part(parts, Part.HEAD).yRot, EPSILON);
        assertSame(standing()[Part.RIGHT_LEG.ordinal()], part(parts, Part.RIGHT_LEG), "right leg");
    }

    /** Leaning from the root tilts the whole body about the point between the feet. */
    @Test
    void theRootPivotsAtTheFeet() {
        PartPose[] parts = standing();
        part(parts, Part.RIGHT_LEG).xRot = 0.0f;
        part(parts, Part.LEFT_LEG).xRot = 0.0f;
        SkinSwapSkeleton.pose(SkinSwapAnimation.withBone(Bone.ROOT, Axis.ROT_Z, 8.0f), parts);
        float lean = (float) Math.toRadians(8.0);
        double footX = 0.0;
        double footY = 0.0;
        for (Part leg : new Part[] {Part.RIGHT_LEG, Part.LEFT_LEG}) {
            PartPose pose = part(parts, leg);
            double[] r = SkinSwapSkeleton.rotation(pose.xRot, pose.yRot, pose.zRot);
            // The foot is 12 pixels below the hip pivot, along the leg.
            footX += (pose.x + r[1] * 12.0) / 2.0;
            footY += (pose.y + r[4] * 12.0) / 2.0;
            assertEquals(lean, pose.zRot, EPSILON, leg + " leans with the root");
        }
        assertEquals(0.0, footX, 1.0e-3, "between the feet x");
        assertEquals(24.0, footY, 1.0e-3, "between the feet y");
        assertEquals(lean, part(parts, Part.BODY).zRot, EPSILON);
        assertTrue(part(parts, Part.HEAD).x > 0.0f, "the head leans towards the left arm");
    }

    /** A cape's body follows exactly as the model's body does. */
    @Test
    void aCapeBodyMatchesTheModelBody() {
        SkinSwapAnimation.Pose pose = SkinSwapAnimation.sample(SkinSwapAnimation.Variant.SHAKE_OFF, 0.3f);
        PartPose[] parts = standing();
        SkinSwapSkeleton.pose(pose, parts);
        PartPose body = new PartPose().set(0, 0, 0, 0, 0, 0);
        SkinSwapSkeleton.poseBody(pose, body);
        assertSame(part(parts, Part.BODY), body, "cape body");

        float[] transform = SkinSwapSkeleton.bodyTransform(pose);
        double[] r = SkinSwapSkeleton.rotation(body.xRot, body.yRot, body.zRot);
        for (int i = 0; i < 9; i++) {
            assertEquals(r[i], transform[i], EPSILON, "rotation " + i);
        }
        assertEquals(body.x, transform[9], EPSILON);
        assertEquals(body.y, transform[10], EPSILON);
        assertEquals(body.z, transform[11], EPSILON);
    }

    private static void assertSame(PartPose expected, PartPose actual, String part) {
        assertEquals(expected.x, actual.x, EPSILON, part + " x");
        assertEquals(expected.y, actual.y, EPSILON, part + " y");
        assertEquals(expected.z, actual.z, EPSILON, part + " z");
        assertEquals(expected.xRot, actual.xRot, EPSILON, part + " xRot");
        assertEquals(expected.yRot, actual.yRot, EPSILON, part + " yRot");
        assertEquals(expected.zRot, actual.zRot, EPSILON, part + " zRot");
    }
}
