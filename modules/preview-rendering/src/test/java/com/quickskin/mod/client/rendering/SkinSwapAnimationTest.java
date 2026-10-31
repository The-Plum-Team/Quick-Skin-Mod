package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.rendering.SkinSwapAnimation.Axis;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Bone;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Pose;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Variant;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Whole;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skin menu's skin-change moves: each one blends in and out of the idle without a pop, moves
 * the body within the range the vanilla model shows without coming apart, never swings a hand into
 * the body, and the same move never plays twice in a row.
 */
class SkinSwapAnimationTest {

    private static final float FRAME = 1.0f / 60.0f;

    /** The inclusive range each whole-model channel may reach. */
    private static final Map<Whole, float[]> WHOLE_RANGES = new EnumMap<>(Whole.class);
    /** The inclusive range each bone's rotations (degrees) and offsets (pixels) may reach. */
    private static final Map<Bone, float[][]> BONE_RANGES = new EnumMap<>(Bone.class);

    static {
        WHOLE_RANGES.put(Whole.TURN, new float[] {-30, 375});
        WHOLE_RANGES.put(Whole.LIFT, new float[] {-0.05f, 0.15f});
        WHOLE_RANGES.put(Whole.SCALE, new float[] {0.97f, 1.03f});
        // No move changes the arms' width; only ArmWidthMorph does
        WHOLE_RANGES.put(Whole.ARM_WIDTH, new float[] {1.0f, 1.0f});
        // Rotations X, Y, Z, then offsets X, Y, Z.
        BONE_RANGES.put(Bone.ROOT, new float[][] {{-10, 10}, {-10, 10}, {-10, 10}, {0, 0}, {0, 0}, {0, 0}});
        BONE_RANGES.put(Bone.WAIST, new float[][] {{-15, 20}, {-25, 25}, {-10, 10}, {-2, 2}, {-1, 4}, {-2, 2}});
        BONE_RANGES.put(Bone.BODY, new float[][] {{-15, 15}, {-20, 20}, {-10, 10}, {-1, 1}, {-1, 1}, {-1, 1}});
        BONE_RANGES.put(Bone.HEAD, new float[][] {{-35, 35}, {-45, 45}, {-12, 12}, {-1, 1}, {-1, 1}, {-1, 1}});
        for (Bone arm : EnumSet.of(Bone.RIGHT_ARM, Bone.LEFT_ARM)) {
            BONE_RANGES.put(arm, new float[][] {{-180, 35}, {-10, 10}, {-60, 60}, {-1, 1}, {-1, 1}, {-1, 1}});
        }
        for (Bone leg : EnumSet.of(Bone.RIGHT_LEG, Bone.LEFT_LEG)) {
            BONE_RANGES.put(leg, new float[][] {{-35, 35}, {-10, 10}, {-10, 10}, {-2, 2}, {-2, 2}, {-2, 2}});
        }
    }

    @Test
    void everyChannelHasARange() {
        assertEquals(EnumSet.allOf(Whole.class), WHOLE_RANGES.keySet());
        assertEquals(EnumSet.allOf(Bone.class), BONE_RANGES.keySet());
    }

    @Test
    void everyMoveStartsAndEndsAtRest() {
        for (Variant variant : Variant.values()) {
            float duration = SkinSwapAnimation.duration(variant);
            assertTrue(SkinSwapAnimation.sample(variant, 0.0f).isRest(), variant.name());
            assertTrue(SkinSwapAnimation.sample(variant, duration).isRest(), variant.name());
            assertTrue(SkinSwapAnimation.sample(variant, -1.0f).isRest(), variant.name());
            assertTrue(SkinSwapAnimation.sample(variant, Float.NaN).isRest(), variant.name());

            assertNearRest(variant, SkinSwapAnimation.sample(variant, FRAME));
            assertNearRest(variant, SkinSwapAnimation.sample(variant, duration - FRAME));
        }
    }

    @Test
    void everyMoveIsShort() {
        for (Variant variant : Variant.values()) {
            float duration = SkinSwapAnimation.duration(variant);
            // The settle's tail is only a few degrees, so the visible move is shorter than this.
            assertTrue(duration >= 0.5f && duration <= 2.1f, variant + " lasts " + duration + " s");
        }
    }

    /** Every move bends the body through the skeleton and moves the limbs, not only the head. */
    @Test
    void everyMoveMovesTheBodyAndTheLimbs() {
        for (Variant variant : Variant.values()) {
            boolean bends = false;
            boolean limbs = false;
            for (float t = 0.0f; t < SkinSwapAnimation.duration(variant); t += FRAME) {
                Pose pose = SkinSwapAnimation.sample(variant, t);
                for (Axis axis : Axis.values()) {
                    bends |= Math.abs(pose.get(Bone.WAIST, axis)) >= 3.0f
                            || Math.abs(pose.get(Bone.BODY, axis)) >= 3.0f;
                }
                for (Bone limb : EnumSet.range(Bone.RIGHT_ARM, Bone.LEFT_LEG)) {
                    limbs |= Math.abs(pose.get(limb, Axis.ROT_X)) >= 5.0f
                            || Math.abs(pose.get(limb, Axis.ROT_Z)) >= 5.0f;
                }
            }
            assertTrue(bends, variant + " never bends the waist or the body");
            assertTrue(limbs, variant + " keeps the limbs still");
        }
    }

    @Test
    void everyMoveStaysInRange() {
        for (Variant variant : Variant.values()) {
            for (float t = 0.0f; t < SkinSwapAnimation.duration(variant); t += FRAME / 4.0f) {
                Pose pose = SkinSwapAnimation.sample(variant, t);
                for (Whole whole : Whole.values()) {
                    float[] range = WHOLE_RANGES.get(whole);
                    float value = pose.get(whole);
                    assertTrue(value >= range[0] && value <= range[1],
                            variant + " " + whole + " = " + value + " at " + t);
                }
                for (Bone bone : Bone.values()) {
                    for (Axis axis : Axis.values()) {
                        float[] range = BONE_RANGES.get(bone)[axis.ordinal()];
                        float value = pose.get(bone, axis);
                        assertTrue(value >= range[0] && value <= range[1],
                                variant + " " + bone + "." + axis + " = " + value + " at " + t);
                    }
                }
            }
        }
    }

    /**
     * A vanilla part turns by Z after X, about fixed axes, so the roll that opens a hanging arm
     * closes a raised one. Wherever an arm points, its hand never comes inside the line it hangs
     * on at rest, where it would cut into the body or the head.
     */
    @Test
    void noMoveSwingsAHandIntoTheBody() {
        for (Variant variant : Variant.values()) {
            for (float t = 0.0f; t < SkinSwapAnimation.duration(variant); t += FRAME / 4.0f) {
                Pose pose = SkinSwapAnimation.sample(variant, t);
                // The right arm hangs on the -X side of the model and the left on the +X side.
                double right = handX(pose, Bone.RIGHT_ARM);
                double left = -handX(pose, Bone.LEFT_ARM);
                assertTrue(right <= 0.1, variant + " right hand inwards by " + right + " at " + t);
                assertTrue(left <= 0.1, variant + " left hand inwards by " + left + " at " + t);
            }
        }
    }

    /**
     * No pop between two frames at 60 fps, even in the full turns and the raised arms. A hop's
     * lift falls as fast as a landing does, and arms let go overhead drop about as fast as a turn
     * spins, so those have the loosest bounds.
     */
    @Test
    void everyMoveIsSmooth() {
        for (Variant variant : Variant.values()) {
            Pose previous = SkinSwapAnimation.sample(variant, 0.0f);
            for (float t = FRAME; t <= SkinSwapAnimation.duration(variant) + FRAME; t += FRAME) {
                Pose pose = SkinSwapAnimation.sample(variant, t);
                String at = variant + " at " + t;
                assertTrue(Math.abs(wrapDegrees(pose.get(Whole.TURN) - previous.get(Whole.TURN))) <= 18.0f, at + " turn");
                assertTrue(Math.abs(pose.get(Whole.LIFT) - previous.get(Whole.LIFT)) <= 0.03f, at + " lift");
                assertTrue(Math.abs(pose.get(Whole.SCALE) - previous.get(Whole.SCALE)) <= 0.01f, at + " scale");
                for (Bone bone : Bone.values()) {
                    for (Axis axis : Axis.values()) {
                        float step = Math.abs(pose.get(bone, axis) - previous.get(bone, axis));
                        float limit = axis.ordinal() < 3 ? 18.0f : 0.6f;
                        assertTrue(step <= limit, at + " " + bone + "." + axis + " jumps " + step);
                    }
                }
                previous = pose;
            }
        }
    }

    /**
     * The body lets go before the head: once the waist has started back to rest, the head is still
     * held where the move left it, so the parts land in turn instead of all at once.
     */
    @Test
    void theHeadSettlesAfterTheBody() {
        Variant variant = Variant.HOP;
        float release = 15.5f / 24.0f;
        Pose held = SkinSwapAnimation.sample(variant, release);
        Pose later = SkinSwapAnimation.sample(variant, release + 0.08f);
        assertTrue(Math.abs(later.get(Bone.WAIST, Axis.ROT_X)) < Math.abs(held.get(Bone.WAIST, Axis.ROT_X)) - 1.0f,
                "the waist is on its way back: " + later);
        assertEquals(held.get(Bone.HEAD, Axis.ROT_X), later.get(Bone.HEAD, Axis.ROT_X), 1.0e-4f,
                "the head is still held");
    }

    /** Released channels swing a little past rest before they settle, as a body with weight does. */
    @Test
    void releasedChannelsOvershootRest() {
        Variant variant = Variant.STRETCH;
        float lowest = 0.0f;
        for (float t = 22.0f / 24.0f; t < SkinSwapAnimation.duration(variant); t += FRAME) {
            lowest = Math.max(lowest, SkinSwapAnimation.sample(variant, t).get(Bone.RIGHT_ARM, Axis.ROT_X));
        }
        assertTrue(lowest > 3.0f, "the dropped arms swing back past their hang: " + lowest);
    }

    @Test
    void theSameMoveNeverPlaysTwiceInARow() {
        Variant[] variants = Variant.values();
        for (Variant previous : variants) {
            Set<Variant> reached = EnumSet.noneOf(Variant.class);
            for (int roll = 0; roll < variants.length - 1; roll++) {
                int fixed = roll;
                Variant picked = SkinSwapAnimation.pick(previous, bound -> {
                    assertEquals(variants.length - 1, bound);
                    return fixed;
                });
                assertNotEquals(previous, picked);
                reached.add(picked);
            }
            assertEquals(variants.length - 1, reached.size(), "every other move is reachable after " + previous);
        }
    }

    @Test
    void theFirstMoveCanBeAnyMove() {
        Variant[] variants = Variant.values();
        for (int roll = 0; roll < variants.length; roll++) {
            int fixed = roll;
            assertEquals(variants[roll], SkinSwapAnimation.pick(null, bound -> fixed));
        }
    }

    private static void assertNearRest(Variant variant, Pose pose) {
        String message = variant + " " + pose;
        assertTrue(Math.abs(wrapDegrees(pose.get(Whole.TURN))) <= 3.0f, message);
        assertTrue(Math.abs(pose.get(Whole.LIFT)) <= 0.01f, message);
        assertTrue(Math.abs(pose.get(Whole.SCALE) - 1.0f) <= 0.01f, message);
        for (Bone bone : Bone.values()) {
            for (Axis axis : Axis.values()) {
                float limit = axis.ordinal() < 3 ? 3.0f : 0.2f;
                assertTrue(Math.abs(pose.get(bone, axis)) <= limit, message);
            }
        }
    }

    /**
     * Sideways offset of a ten-unit arm's hand from its shoulder, rotated as a vanilla
     * {@code ModelPart} does: X first, then Y, then Z, about the model's axes (Y pointing down).
     */
    private static double handX(Pose pose, Bone arm) {
        double x = Math.toRadians(pose.get(arm, Axis.ROT_X));
        double y = Math.toRadians(pose.get(arm, Axis.ROT_Y));
        double z = Math.toRadians(pose.get(arm, Axis.ROT_Z));
        double hy = 10.0 * Math.cos(x);
        double hz = 10.0 * Math.sin(x);
        double turnedX = hz * Math.sin(y);
        return turnedX * Math.cos(z) - hy * Math.sin(z);
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped > 180.0f) {
            wrapped -= 360.0f;
        } else if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }
}
