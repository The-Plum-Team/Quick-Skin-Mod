package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.rendering.SkinSwapAnimation.Axis;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Bone;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Pose;
import com.quickskin.mod.client.rendering.SkinSwapSkeleton.Part;
import com.quickskin.mod.client.rendering.SkinSwapSkeleton.PartPose;
import org.junit.jupiter.api.Test;

import java.util.function.DoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preview's idles and walk keep their feet on the ground and their hands clear of the body,
 * and its sit rests the hands on the thighs and then on the block it sits on.
 */
class PreviewLoopsTest {

    private static final double FRAME = 1.0 / 60.0;
    /** Where the soles touch the ground, in model pixels below the neck. */
    private static final double GROUND = 24.0;
    /** The block the sit sits on: its top and how far it reaches sideways and back. */
    private static final double SEAT = 13.2;
    private static final double SEAT_HALF_WIDTH = 5.4;
    private static final double SEAT_BACK = 4.5;

    /** The vanilla player model's parts as {@code PlayerModel} bakes them, with classic arms. */
    private static PartPose[] standing() {
        PartPose[] parts = new PartPose[Part.values().length];
        parts[Part.HEAD.ordinal()] = new PartPose();
        parts[Part.BODY.ordinal()] = new PartPose();
        parts[Part.RIGHT_ARM.ordinal()] = new PartPose().set(-5, 2, 0, 0, 0, 0);
        parts[Part.LEFT_ARM.ordinal()] = new PartPose().set(5, 2, 0, 0, 0, 0);
        parts[Part.RIGHT_LEG.ordinal()] = new PartPose().set(-1.9f, 12, 0, 0, 0, 0);
        parts[Part.LEFT_LEG.ordinal()] = new PartPose().set(1.9f, 12, 0, 0, 0, 0);
        return parts;
    }

    private static PartPose[] posed(Pose pose) {
        PartPose[] parts = standing();
        SkinSwapSkeleton.pose(pose, parts);
        return parts;
    }

    /** A point given in a part's own space, in the model's. */
    private static double[] point(PartPose part, double x, double y, double z) {
        double[] r = SkinSwapSkeleton.rotation(part.xRot, part.yRot, part.zRot);
        return new double[] {
                part.x + r[0] * x + r[1] * y + r[2] * z,
                part.y + r[3] * x + r[4] * y + r[5] * z,
                part.z + r[6] * x + r[7] * y + r[8] * z};
    }

    /** The middle of a sole: the end of the leg, 12 pixels below its hip. */
    private static double[] sole(PartPose[] parts, Part leg) {
        return point(parts[leg.ordinal()], 0, 12, 0);
    }

    /** The middle of the palm of a classic arm, 10 pixels below its shoulder. */
    private static double[] hand(PartPose[] parts, Part arm) {
        return point(parts[arm.ordinal()], arm == Part.RIGHT_ARM ? -1 : 1, 10, 0);
    }

    /** Long enough for every idle to have gone through each of its own periods. */
    private static final double IDLE_SPAN = 60.0;

    @Test
    void everyIdleIsSmooth() {
        for (PreviewLoops.Idle idle : PreviewLoops.Idle.values()) {
            assertSmooth(idle.name(), t -> PreviewLoops.idle(idle, t), IDLE_SPAN, 5.0f, 0.1f);
        }
    }

    /**
     * An idle stands where it is: a relaxed leg may lift its heel a fraction of a pixel, and no
     * foot leaves the ground.
     */
    @Test
    void everyIdleKeepsTheFeetPlanted() {
        PartPose[] standing = posed(PreviewLoops.idle(PreviewLoops.Idle.CALM, 0.0));
        for (PreviewLoops.Idle idle : PreviewLoops.Idle.values()) {
            for (double t = 0.0; t < IDLE_SPAN; t += FRAME) {
                PartPose[] parts = posed(PreviewLoops.idle(idle, t));
                for (Part leg : new Part[] {Part.RIGHT_LEG, Part.LEFT_LEG}) {
                    double[] sole = sole(parts, leg);
                    double[] rest = sole(standing, leg);
                    String at = idle + " " + leg + " at " + t;
                    assertTrue(Math.abs(GROUND - sole[1]) <= 0.25, at + " off the ground by " + (GROUND - sole[1]));
                    assertTrue(Math.abs(sole[0] - rest[0]) <= 0.2, at + " slides sideways by " + (sole[0] - rest[0]));
                }
            }
        }
    }

    @Test
    void everyIdleHoldsTheHandsBesideTheBody() {
        for (PreviewLoops.Idle idle : PreviewLoops.Idle.values()) {
            for (double t = 0.0; t < IDLE_SPAN; t += FRAME) {
                PartPose[] parts = posed(PreviewLoops.idle(idle, t));
                assertTrue(hand(parts, Part.RIGHT_ARM)[0] <= -6.0, idle + " right hand inside at " + t);
                assertTrue(hand(parts, Part.LEFT_ARM)[0] >= 6.0, idle + " left hand inside at " + t);
            }
        }
    }

    @Test
    void theWalkLoopsWithoutASeam() {
        assertSameChannels(PreviewLoops.walk(0.0), PreviewLoops.walk(PreviewLoops.WALK_CYCLE));
        assertSameChannels(PreviewLoops.walk(0.3), PreviewLoops.walk(0.3 + 3 * PreviewLoops.WALK_CYCLE));
    }

    @Test
    void theWalkAndTheSitAreSmooth() {
        assertSmooth("walk", PreviewLoops::walk, 2 * PreviewLoops.WALK_CYCLE, 4.0f, 0.25f);
        assertSmooth("sit", PreviewLoops::sit, 2 * PreviewLoops.SIT_CYCLE, 1.5f, 0.05f);
    }

    /**
     * A planted foot stays on the ground, give or take a fraction of a pixel where it lands and
     * leaves, and a swinging foot is lifted clear of it.
     */
    @Test
    void theWalkKeepsThePlantedFootOnTheGround() {
        for (double t = 0.0; t < PreviewLoops.WALK_CYCLE; t += FRAME / 4.0) {
            PartPose[] parts = posed(PreviewLoops.walk(t));
            double phase = t / PreviewLoops.WALK_CYCLE;
            checkFoot(parts, Part.RIGHT_LEG, phase, t);
            checkFoot(parts, Part.LEFT_LEG, (phase + 0.5) % 1.0, t);
        }
    }

    private static void checkFoot(PartPose[] parts, Part leg, double phase, double t) {
        double height = GROUND - sole(parts, leg)[1];
        if (PreviewLoops.legLift(phase) == 0.0) {
            assertTrue(Math.abs(height) <= 0.35, leg + " planted foot off the ground by " + height + " at " + t);
        } else {
            assertTrue(height >= -0.2, leg + " swinging foot below the ground by " + -height + " at " + t);
        }
    }

    @Test
    void theWalkSwingsTheHandsBesideTheBody() {
        for (double t = 0.0; t < PreviewLoops.WALK_CYCLE; t += FRAME / 4.0) {
            PartPose[] parts = posed(PreviewLoops.walk(t));
            // The body is 8 pixels wide and a hand 4, so a hand beside it is at least 6 out.
            assertTrue(hand(parts, Part.RIGHT_ARM)[0] <= -6.0, "right hand inside at " + t);
            assertTrue(hand(parts, Part.LEFT_ARM)[0] >= 6.0, "left hand inside at " + t);
        }
    }

    /** Sitting forward, the hands rest on the thighs, a little in front of the hips. */
    @Test
    void sittingForwardRestsTheHandsOnTheThighs() {
        for (double t = 0.0; t <= 6.0; t += 0.25) {
            PartPose[] parts = posed(PreviewLoops.sit(t));
            for (Part arm : new Part[] {Part.RIGHT_ARM, Part.LEFT_ARM}) {
                Part leg = arm == Part.RIGHT_ARM ? Part.RIGHT_LEG : Part.LEFT_LEG;
                double[] palm = hand(parts, arm);
                // The top of the thigh under the palm: a leg is 4 pixels thick.
                double along = Math.max(0.0, -palm[2]);
                double thigh = point(parts[leg.ordinal()], 0, along, -2)[1];
                assertTrue(palm[2] < -4.0, arm + " not in front of the hips at " + t);
                assertTrue(thigh - palm[1] >= -0.2 && thigh - palm[1] <= 0.9,
                        arm + " " + (thigh - palm[1]) + " above its thigh at " + t);
            }
        }
    }

    /** Leaning back, the hands rest on the block's edges, behind the hips. */
    @Test
    void leaningBackRestsTheHandsOnTheBlock() {
        for (double t = 7.5; t <= 13.5; t += 0.25) {
            PartPose[] parts = posed(PreviewLoops.sit(t));
            for (Part arm : new Part[] {Part.RIGHT_ARM, Part.LEFT_ARM}) {
                double[] palm = hand(parts, arm);
                assertTrue(SEAT - palm[1] >= -0.1 && SEAT - palm[1] <= 0.5,
                        arm + " " + (SEAT - palm[1]) + " above the block at " + t);
                assertTrue(palm[2] > 1.0 && palm[2] < SEAT_BACK, arm + " not over the block's back at " + t);
                // A hand is 4 pixels wide, so its inner half lies on the block.
                assertTrue(Math.abs(palm[0]) < SEAT_HALF_WIDTH + 2.0, arm + " off the block's side at " + t);
            }
        }
    }

    private static void assertSameChannels(Pose expected, Pose actual) {
        for (Bone bone : Bone.values()) {
            for (Axis axis : Axis.values()) {
                assertEquals(expected.get(bone, axis), actual.get(bone, axis), 1.0e-3f, bone + "." + axis);
            }
        }
    }

    private static void assertSmooth(String name, DoubleFunction<Pose> loop, double span,
                                     float degrees, float pixels) {
        Pose previous = loop.apply(0.0);
        for (double t = FRAME; t <= span; t += FRAME) {
            Pose pose = loop.apply(t);
            for (Bone bone : Bone.values()) {
                for (Axis axis : Axis.values()) {
                    float step = Math.abs(pose.get(bone, axis) - previous.get(bone, axis));
                    float limit = axis.ordinal() < 3 ? degrees : pixels;
                    assertTrue(step <= limit, name + " " + bone + "." + axis + " jumps " + step + " at " + t);
                }
            }
            previous = pose;
        }
    }
}
