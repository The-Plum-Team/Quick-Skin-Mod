package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.rendering.SkinSwapAnimation.Axis;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Bone;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Pose;

/**
 * The preview's looping idles, walk and sit, posed on the skeleton the skin-change move uses (see
 * {@link SkinSwapAnimation} for its bones, units and signs), so the hips can bob and the upper
 * body can lean and twist over them instead of every part swinging on its own.
 *
 * <p>The idles stand facing the viewer with the arms and the feet a little apart, so the whole
 * skin shows, each with its own small business: breathing, shifting the weight, looking around,
 * swaying, stretching the neck or bouncing on the knees. Each loops on its own;
 * {@link IdleShuffle} picks one at random and another whenever the skin changes.
 *
 * <p>The walk is a stride in place. Each foot is on the ground for a little more than half the
 * stride, sweeping back at a steady pace, then lifts and swings forward, carrying on back a
 * touch as it lands. The hips dip where both feet are down and rise as one leg passes under
 * them, which keeps the planted foot on the ground; the shoulders twist against the hips with the
 * arms swinging against the legs a beat late, the weight shifts over the planted foot, and the
 * head holds its gaze against the twist.
 *
 * <p>The sit is seated on the preview's block with the legs out in front. The player breathes,
 * looks around, swings the feet now and then, and every so often leans back onto the hands before
 * sitting forward again with the hands on the thighs.
 *
 * <p>Both are pure functions of the time, so a frame is the same for the same time on every
 * Minecraft version.
 */
public final class PreviewLoops {

    /** One stride, a step with each foot, in seconds. */
    static final double WALK_CYCLE = 1.05;
    /** The share of the stride a foot spends on the ground. */
    private static final double STANCE = 0.6;
    /** How far a planted leg reaches forward and back, in degrees. */
    private static final double REACH = 22.0;
    /** How high a swinging leg is drawn up, in pixels. */
    private static final double STEP_LIFT = 1.6;
    /** The leg's length from the hip to the sole, in pixels. */
    private static final double LEG = 12.0;
    /** Where in the stride both feet are down, half way through the double support. */
    private static final double DOUBLE_SUPPORT = (STANCE - 0.5) / 2.0;

    /** How long the sit takes to go from sitting forward to leaning back and forward again. */
    static final double SIT_CYCLE = 15.0;

    private PreviewLoops() {
    }

    /** The preview's idles. */
    public enum Idle {
        /** Stands squarely and breathes, the head drifting: the skin menu's first stance. */
        CALM,
        /** Rests the weight on one leg and then the other, the hips over it. */
        WEIGHT_SHIFT,
        /** Looks around, and now and then down at a hand it brings up. */
        LOOK_AROUND,
        /** Sways from the ankles as if to music, nodding along. */
        SWAY,
        /** Tilts the head to one shoulder and the other, then rolls the shoulders. */
        NECK_STRETCH,
        /** Bounces lightly on the knees. */
        BOUNCE
    }

    /** An idle {@code seconds} into it. */
    public static Pose idle(Idle idle, double seconds) {
        Pose.Builder pose = calm(seconds);
        switch (idle) {
            case CALM -> {
            }
            case WEIGHT_SHIFT -> weightShift(pose, seconds);
            case LOOK_AROUND -> lookAround(pose, seconds);
            case SWAY -> sway(pose, seconds);
            case NECK_STRETCH -> neckStretch(pose, seconds);
            case BOUNCE -> bounce(pose, seconds);
        }
        return pose.build();
    }

    /**
     * The stance every idle starts from: squarely facing the viewer, the arms and the feet a little
     * apart, breathing slowly. The breath lifts the arms a touch away from the body and the head
     * drifts, each on its own slow period, so the motion never looks mechanical.
     */
    private static Pose.Builder calm(double seconds) {
        // One breath every 4 seconds; the head and arm sway drift on slower periods.
        double breath = Math.sin(2.0 * Math.PI * seconds / 4.0);
        double drift = Math.sin(2.0 * Math.PI * seconds / 7.0);
        double sway = Math.sin(2.0 * Math.PI * seconds / 5.5 + 1.3);
        double armOut = 0.13 + (breath * 0.5 + 0.5) * 0.035;
        return new Pose.Builder()
                .set(Bone.HEAD, Axis.ROT_X, degrees(-0.02 + breath * 0.02))
                .set(Bone.HEAD, Axis.ROT_Y, degrees(drift * 0.08))
                .set(Bone.HEAD, Axis.ROT_Z, degrees(sway * 0.025))
                .set(Bone.BODY, Axis.ROT_X, degrees(breath * 0.012))
                .set(Bone.RIGHT_ARM, Axis.ROT_X, degrees(sway * 0.035))
                .set(Bone.RIGHT_ARM, Axis.ROT_Z, degrees(armOut))
                .set(Bone.LEFT_ARM, Axis.ROT_X, degrees(-sway * 0.035))
                .set(Bone.LEFT_ARM, Axis.ROT_Z, degrees(-armOut))
                .set(Bone.RIGHT_LEG, Axis.ROT_Z, degrees(0.07))
                .set(Bone.LEFT_LEG, Axis.ROT_Z, degrees(-0.07));
    }

    /**
     * The weight goes onto the right leg for about seven seconds and then onto the left. The hips
     * move over the standing leg with the feet planted, the other leg relaxes a little forward, and
     * the shoulders tilt against the hips.
     */
    private static void weightShift(Pose.Builder pose, double seconds) {
        // +1 with the weight on the right leg, -1 on the left, easing across in about two seconds.
        double weight = Math.tanh(2.5 * Math.sin(2.0 * Math.PI * seconds / 14.0)) / Math.tanh(2.5);
        double shift = 0.9 * weight;
        // The legs lean under the moved hips so that the feet stay where they are.
        float lean = (float) -Math.toDegrees(Math.asin(shift / LEG));
        double rightRelaxed = (1.0 - weight) * (1.0 - weight) / 4.0;
        double leftRelaxed = (1.0 + weight) * (1.0 + weight) / 4.0;
        pose.add(Bone.ROOT, Axis.POS_X, (float) -shift)
                .add(Bone.RIGHT_LEG, Axis.ROT_Z, lean)
                .add(Bone.LEFT_LEG, Axis.ROT_Z, lean)
                .add(Bone.RIGHT_LEG, Axis.ROT_X, (float) (-6.0 * rightRelaxed))
                .add(Bone.LEFT_LEG, Axis.ROT_X, (float) (-6.0 * leftRelaxed))
                .add(Bone.WAIST, Axis.ROT_Z, (float) (-1.5 * weight))
                .add(Bone.BODY, Axis.ROT_Z, (float) (2.5 * weight))
                .add(Bone.HEAD, Axis.ROT_Z, (float) (-1.5 * weight))
                .add(Bone.RIGHT_ARM, Axis.ROT_Z, (float) -weight)
                .add(Bone.LEFT_ARM, Axis.ROT_Z, (float) -weight);
    }

    /** Where the look-around idle glances, as yaw and pitch in degrees, one after another. */
    private static final double[][] GLANCES = {{34, -5}, {-30, 3}, {22, 26}, {-36, -6}, {16, 6}};
    /** The glance down at the right hand, which comes up to be looked at. */
    private static final int HAND_GLANCE = 2;
    private static final double GLANCE_TURN = 1.1;
    private static final double GLANCE_HOLD = 2.6;
    private static final double GLANCE_RETURN = 1.3;
    private static final double GLANCE_REST = 5.0;
    private static final double GLANCE = GLANCE_TURN + GLANCE_HOLD + GLANCE_RETURN + GLANCE_REST;

    /**
     * Glances somewhere, holds the look, turns back to the front and waits a moment before the
     * next glance, the body turning a little with the head; once a round it looks down at the
     * right hand, which it brings up.
     */
    private static void lookAround(Pose.Builder pose, double seconds) {
        double round = fraction(seconds / (GLANCES.length * GLANCE)) * GLANCES.length;
        int glance = (int) round;
        double t = (round - glance) * GLANCE;
        double away;
        if (t < GLANCE_TURN) {
            away = smootherstep(t / GLANCE_TURN);
        } else if (t < GLANCE_TURN + GLANCE_HOLD) {
            away = 1.0;
        } else if (t < GLANCE_TURN + GLANCE_HOLD + GLANCE_RETURN) {
            away = 1.0 - smootherstep((t - GLANCE_TURN - GLANCE_HOLD) / GLANCE_RETURN);
        } else {
            away = 0.0;
        }
        double yaw = GLANCES[glance][0] * away;
        double pitch = GLANCES[glance][1] * away;
        double hand = glance == HAND_GLANCE ? away : 0.0;
        pose.add(Bone.BODY, Axis.ROT_Y, (float) (0.25 * yaw))
                .add(Bone.HEAD, Axis.ROT_Y, (float) (0.75 * yaw))
                .add(Bone.HEAD, Axis.ROT_X, (float) pitch)
                .add(Bone.RIGHT_ARM, Axis.ROT_X, (float) (-38.0 * hand))
                .add(Bone.RIGHT_ARM, Axis.ROT_Z, (float) (-4.0 * hand));
    }

    /**
     * Sways from side to side from the ankles, a sway to each beat, the waist and the head
     * following a moment later and the head nodding on every beat; the arms hang loose and swing
     * behind the sway.
     */
    private static void sway(Pose.Builder pose, double seconds) {
        double bar = 1.8;
        double side = Math.sin(2.0 * Math.PI * seconds / bar);
        double nod = 0.5 - 0.5 * Math.cos(4.0 * Math.PI * (seconds - 0.1) / bar);
        double arms = 3.0 * Math.sin(2.0 * Math.PI * (seconds - 0.25) / bar);
        pose.add(Bone.ROOT, Axis.ROT_Z, (float) (2.2 * side))
                .add(Bone.WAIST, Axis.ROT_Z, (float) (-1.2 * Math.sin(2.0 * Math.PI * (seconds - 0.12) / bar)))
                .add(Bone.HEAD, Axis.ROT_Z, (float) (3.0 * Math.sin(2.0 * Math.PI * (seconds - 0.2) / bar)))
                .add(Bone.HEAD, Axis.ROT_X, (float) (3.0 * nod))
                .add(Bone.RIGHT_ARM, Axis.ROT_Z, (float) arms)
                .add(Bone.LEFT_ARM, Axis.ROT_Z, (float) arms);
    }

    /**
     * Over nine seconds: tilts the head to the right shoulder and holds it, then to the left, comes
     * back up, and rolls both shoulders twice.
     */
    private static void neckStretch(Pose.Builder pose, double seconds) {
        double t = fraction(seconds / 9.0) * 9.0;
        // -1 towards the right shoulder, +1 towards the left.
        double tilt;
        if (t < 1.2) {
            tilt = -smootherstep(t / 1.2);
        } else if (t < 2.5) {
            tilt = -1.0;
        } else if (t < 3.7) {
            tilt = -1.0 + 2.0 * smootherstep((t - 2.5) / 1.2);
        } else if (t < 5.0) {
            tilt = 1.0;
        } else if (t < 6.0) {
            tilt = 1.0 - smootherstep(t - 5.0);
        } else {
            tilt = 0.0;
        }
        // Two shoulder rolls between 6 and 8 seconds: up, back, down and forward.
        double roll = t >= 6.0 && t < 8.0 ? 2.0 * Math.PI * (t - 6.0) : 0.0;
        float up = (float) (-0.7 * (0.5 - 0.5 * Math.cos(roll)));
        float back = (float) (0.6 * Math.sin(roll));
        pose.add(Bone.HEAD, Axis.ROT_Z, (float) (14.0 * tilt))
                .add(Bone.HEAD, Axis.ROT_X, (float) (4.0 * Math.abs(tilt)))
                .add(Bone.BODY, Axis.ROT_Z, (float) (-1.5 * tilt))
                .add(Bone.RIGHT_ARM, Axis.POS_Y, up)
                .add(Bone.RIGHT_ARM, Axis.POS_Z, back)
                .add(Bone.LEFT_ARM, Axis.POS_Y, up)
                .add(Bone.LEFT_ARM, Axis.POS_Z, back);
    }

    /**
     * Bounces lightly on the knees, the upper body dipping onto the legs, the head nodding a moment
     * after and the arms lifting a little on each dip; the hips rock to every other bounce.
     */
    private static void bounce(Pose.Builder pose, double seconds) {
        double period = 0.75;
        double dip = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * seconds / period);
        double arms = 3.0 * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * (seconds - 0.08) / period));
        pose.add(Bone.WAIST, Axis.POS_Y, (float) (0.6 * dip))
                .add(Bone.WAIST, Axis.ROT_Z, (float) (1.5 * Math.sin(2.0 * Math.PI * seconds / (2.0 * period))))
                .add(Bone.HEAD, Axis.ROT_X, (float) (2.0 * Math.sin(2.0 * Math.PI * (seconds - 0.1) / period)))
                .add(Bone.RIGHT_ARM, Axis.ROT_Z, (float) arms)
                .add(Bone.LEFT_ARM, Axis.ROT_Z, (float) -arms);
    }

    /** The walk {@code seconds} into it. */
    public static Pose walk(double seconds) {
        double p = fraction(seconds / WALK_CYCLE);
        double left = fraction(p + 0.5);

        // The hips are lowest where the legs are spread with both feet down, and highest as a
        // leg passes under them: the drop that keeps a planted sole on the ground.
        double spread = Math.toRadians(stanceAngle(DOUBLE_SUPPORT / STANCE));
        double dip = 0.5 + 0.5 * Math.cos(4.0 * Math.PI * (p - DOUBLE_SUPPORT));
        double drop = LEG * (1.0 - Math.cos(spread)) * dip;

        // +1 when the right foot is under the body, -1 when the left is.
        double weight = Math.cos(2.0 * Math.PI * (p - STANCE / 2.0));
        // +1 when the right leg reaches forward and the left back.
        double stride = Math.cos(2.0 * Math.PI * p);
        double arms = Math.cos(2.0 * Math.PI * (p - 0.07));
        double lean = 3.0 + 1.5 * dip;
        double twist = 8.0 * stride;

        return new Pose.Builder()
                .set(Bone.ROOT, Axis.POS_Y, (float) drop)
                .set(Bone.WAIST, Axis.ROT_X, (float) lean)
                .set(Bone.WAIST, Axis.ROT_Z, (float) (-1.2 * weight))
                .set(Bone.WAIST, Axis.POS_X, (float) (-0.4 * weight))
                .set(Bone.BODY, Axis.ROT_Y, (float) twist)
                .set(Bone.HEAD, Axis.ROT_X, (float) (-0.6 * lean
                        + 1.2 * Math.cos(4.0 * Math.PI * (p - DOUBLE_SUPPORT - 0.08))))
                .set(Bone.HEAD, Axis.ROT_Y, (float) (-0.8 * twist))
                .set(Bone.HEAD, Axis.ROT_Z, (float) (0.6 * weight))
                .set(Bone.RIGHT_ARM, Axis.ROT_X, (float) (26.0 * arms - 3.0))
                .set(Bone.RIGHT_ARM, Axis.ROT_Z, 5.0f)
                .set(Bone.LEFT_ARM, Axis.ROT_X, (float) (-26.0 * arms - 3.0))
                .set(Bone.LEFT_ARM, Axis.ROT_Z, -5.0f)
                .set(Bone.RIGHT_LEG, Axis.ROT_X, (float) legAngle(p))
                .set(Bone.RIGHT_LEG, Axis.POS_Y, (float) -legLift(p))
                .set(Bone.LEFT_LEG, Axis.ROT_X, (float) legAngle(left))
                .set(Bone.LEFT_LEG, Axis.POS_Y, (float) -legLift(left))
                .build();
    }

    /** The sit {@code seconds} into it. */
    public static Pose sit(double seconds) {
        // 0 sitting forward with the hands on the thighs, 1 leaning back onto the hands.
        double back = leanBack(fraction(seconds / SIT_CYCLE) * SIT_CYCLE);
        double breath = Math.sin(2.0 * Math.PI * seconds / 3.8);

        // The feet swing now and then, one down as the other comes back up: only below the legs'
        // rest, so a thigh never rises into a hand on it, and only a little while the hands rest
        // there, so it never leaves them in the air.
        double swinging = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * seconds / 11.0);
        double kick = 0.5 * Math.sin(2.0 * Math.PI * seconds / 1.5);
        double reach = mix(3.0, 9.0, back) * swinging * swinging;
        double rightKick = reach * (0.5 + kick);
        double leftKick = reach * (0.5 - kick);

        // A slow look around, wider while leaning back.
        double look = (0.6 + 0.4 * back) * (20.0 * Math.sin(2.0 * Math.PI * seconds / 9.3)
                + 9.0 * Math.sin(2.0 * Math.PI * seconds / 4.7 + 1.3));
        double lean = mix(9.0, -12.0, back) + 0.8 * breath;
        // The arms open on the way, so the hands pass outside the thighs.
        double open = 10.0 * Math.sin(Math.PI * back);

        return new Pose.Builder()
                .set(Bone.WAIST, Axis.ROT_X, (float) lean)
                .set(Bone.BODY, Axis.ROT_X, (float) (1.2 * breath))
                .set(Bone.BODY, Axis.ROT_Y, (float) (0.2 * look))
                .set(Bone.HEAD, Axis.ROT_X, (float) (-0.75 * lean + 4.0 * Math.sin(2.0 * Math.PI * seconds / 6.1)))
                .set(Bone.HEAD, Axis.ROT_Y, (float) (0.8 * look))
                .set(Bone.HEAD, Axis.ROT_Z, (float) (3.0 * Math.sin(2.0 * Math.PI * seconds / 7.7)))
                // Forward onto the thighs, or back and down onto the block beside the hips.
                .set(Bone.RIGHT_ARM, Axis.ROT_X, (float) mix(-40.0, 20.0, back))
                .set(Bone.RIGHT_ARM, Axis.ROT_Z, (float) (mix(-7.0, 2.0, back) + open))
                .set(Bone.RIGHT_ARM, Axis.POS_Y, (float) (1.0 * back))
                .set(Bone.LEFT_ARM, Axis.ROT_X, (float) mix(-40.0, 20.0, back))
                .set(Bone.LEFT_ARM, Axis.ROT_Z, (float) (mix(7.0, -2.0, back) - open))
                .set(Bone.LEFT_ARM, Axis.POS_Y, (float) (1.0 * back))
                // Legs out in front, a little apart.
                .set(Bone.RIGHT_LEG, Axis.ROT_X, (float) (-80.0 + rightKick))
                .set(Bone.RIGHT_LEG, Axis.ROT_Y, 12.0f)
                .set(Bone.RIGHT_LEG, Axis.ROT_Z, 2.0f)
                .set(Bone.LEFT_LEG, Axis.ROT_X, (float) (-80.0 + leftKick))
                .set(Bone.LEFT_LEG, Axis.ROT_Y, -12.0f)
                .set(Bone.LEFT_LEG, Axis.ROT_Z, -2.0f)
                .build();
    }

    /**
     * A leg's swing at {@code phase} of its own stride, in degrees, back positive: from its heel
     * strike it sweeps back at a steady pace while planted, then swings forward, eased so that it
     * leaves and lands moving back the way the planted leg does.
     */
    static double legAngle(double phase) {
        if (phase < STANCE) {
            return stanceAngle(phase / STANCE);
        }
        double s = (phase - STANCE) / (1.0 - STANCE);
        // Hermite from back to front, with the planted sweep's own speed at both ends.
        double speed = 2.0 * REACH / STANCE * (1.0 - STANCE);
        double s2 = s * s;
        double s3 = s2 * s;
        return (2.0 * s3 - 3.0 * s2 + 1.0) * REACH
                + (s3 - 2.0 * s2 + s) * speed
                + (-2.0 * s3 + 3.0 * s2) * -REACH
                + (s3 - s2) * speed;
    }

    /** How high a leg is drawn up at {@code phase} of its own stride, in pixels. */
    static double legLift(double phase) {
        if (phase < STANCE) {
            return 0.0;
        }
        double swing = Math.sin(Math.PI * (phase - STANCE) / (1.0 - STANCE));
        return STEP_LIFT * swing * swing;
    }

    private static double stanceAngle(double sweep) {
        return -REACH + 2.0 * REACH * sweep;
    }

    /**
     * 0 while sitting forward, 1 while leaning back: forward for six seconds, a second and a half
     * to lean back, back for six, and a second and a half to sit forward again.
     */
    private static double leanBack(double seconds) {
        if (seconds < 6.0) {
            return 0.0;
        }
        if (seconds < 7.5) {
            return smootherstep((seconds - 6.0) / 1.5);
        }
        if (seconds < 13.5) {
            return 1.0;
        }
        return 1.0 - smootherstep((seconds - 13.5) / 1.5);
    }

    private static float degrees(double radians) {
        return (float) Math.toDegrees(radians);
    }

    static double smootherstep(double x) {
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    }

    private static double mix(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private static double fraction(double value) {
        return value - Math.floor(value);
    }
}
