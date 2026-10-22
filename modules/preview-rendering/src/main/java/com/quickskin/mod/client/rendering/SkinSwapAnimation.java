package com.quickskin.mod.client.rendering;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/**
 * The short move the skin menu's model makes when another skin is picked, so the change feels
 * alive without getting in the way.
 *
 * <p>The moves animate a small skeleton laid over the vanilla player model, the one Bedrock's
 * player animations use: a root at the feet, a waist at the hips carrying the body, and the body
 * carrying the head and the arms, while the legs hang from the root. Bending the waist therefore
 * bows the whole upper body, and lowering it while the legs stay put reads as bent knees. Every
 * bone has a rotation and an offset; {@link SkinSwapSkeleton} turns them into the vanilla parts'
 * poses. The model as a whole also turns, lifts and scales.
 *
 * <p>Each move is keyframed on a 24 fps grid with an anticipation and the action, the bones keyed
 * on their own beats so that the head and the arms follow through behind the body. A channel is a
 * Catmull-Rom spline whose tangents account for the uneven spacing of its keyframes, so a fast
 * segment never throws its neighbour past its keys. A channel's last keyframe is the pose it lets
 * go from: from there a damped spring carries it back to rest, overshooting a little and settling,
 * the hips first, then the body, the arms and the head last. The rolls of the arms and the legs
 * settle without overshooting, so a hand never swings into the body. Every channel starts at rest
 * with no velocity and ends at rest exactly, so the move blends into and out of whatever idle the
 * preview is playing.
 *
 * <p>Units follow the vanilla model, whose Y axis points down and whose front faces -Z: degrees
 * for rotations and model pixels for offsets. A positive X rotation bows the root, the waist or the
 * body forward, pitches the head down and swings a limb backwards; a positive Z rotation leans the
 * root, the waist or the body towards the left arm and opens a hanging right arm or leg (and closes
 * a hanging left one); a positive Y rotation turns the front towards the right arm. A positive Y
 * offset moves a bone down.
 *
 * <p>Sampling is a pure function of the variant and the elapsed time.
 */
public final class SkinSwapAnimation {

    /** The moves, picked at random without repeating the previous one. */
    public enum Variant {
        /** Crouches with the arms back, springs up swinging them and lands on soft knees. */
        HOP,
        /** Winds up and turns on the spot, the arms opening and one foot kicked up. */
        TWIRL,
        /** Chest out and chin up, rocks onto the toes and gives a pleased nod. */
        PROUD,
        /** Leans away from a raised hand and waves. */
        WAVE,
        /** Reaches up on tiptoe, arching back, and lets the arms fall loose. */
        STRETCH,
        /** Shakes the old look off with a shimmy that settles. */
        SHAKE_OFF
    }

    /** The skeleton's bones, parents before children. */
    public enum Bone {
        ROOT, WAIST, BODY, HEAD, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG;

        /** How long after its last keyframe the bone starts to settle, so the parts land in turn. */
        float settleDelay() {
            return switch (this) {
                case ROOT, WAIST, RIGHT_LEG, LEFT_LEG -> 0.0f;
                case BODY -> 0.03f;
                case RIGHT_ARM -> 0.06f;
                case LEFT_ARM -> 0.08f;
                case HEAD -> 0.11f;
            };
        }
    }

    /** What a bone channel moves. */
    public enum Axis {
        ROT_X, ROT_Y, ROT_Z, POS_X, POS_Y, POS_Z
    }

    /** What a whole-model channel moves: degrees for the turn, blocks for the lift, a factor for the scale. */
    public enum Whole {
        TURN, LIFT, SCALE
    }

    private static final int WHOLE_CHANNELS = Whole.values().length;
    private static final int AXES = Axis.values().length;
    private static final int CHANNELS = WHOLE_CHANNELS + Bone.values().length * AXES;

    /** The settle's spring: a little bouncy, and the gentler, critically damped one for limb rolls. */
    private static final double SPRING = 12.0;
    private static final double DAMPING = 0.65;
    private static final double ROLL_SPRING = 14.0;
    /** How long a settle lasts; its last half fades the spring's residue out, so it ends at rest exactly. */
    private static final float SETTLE = 0.7f;

    private static int index(Whole whole) {
        return whole.ordinal();
    }

    private static int index(Bone bone, Axis axis) {
        return WHOLE_CHANNELS + bone.ordinal() * AXES + axis.ordinal();
    }

    private static float rest(int channel) {
        return channel == index(Whole.SCALE) ? 1.0f : 0.0f;
    }

    private static float settleDelay(int channel) {
        return channel < WHOLE_CHANNELS ? 0.0f : Bone.values()[(channel - WHOLE_CHANNELS) / AXES].settleDelay();
    }

    /** Arm and leg rolls settle without overshooting, so a raised hand never crosses into the body. */
    private static boolean settlesWithoutOvershoot(int channel) {
        if (channel < WHOLE_CHANNELS) {
            return false;
        }
        Bone bone = Bone.values()[(channel - WHOLE_CHANNELS) / AXES];
        Axis axis = Axis.values()[(channel - WHOLE_CHANNELS) % AXES];
        return axis == Axis.ROT_Z && bone.ordinal() >= Bone.RIGHT_ARM.ordinal();
    }

    /** One sampled frame. */
    public static final class Pose {
        public static final Pose REST = new Pose(restValues());

        private final float[] values;

        private Pose(float[] values) {
            this.values = values;
        }

        public float get(Whole whole) {
            return values[index(whole)];
        }

        public float get(Bone bone, Axis axis) {
            return values[index(bone, axis)];
        }

        public boolean isRest() {
            return this == REST || Arrays.equals(values, REST.values);
        }

        private static float[] restValues() {
            float[] values = new float[CHANNELS];
            for (int channel = 0; channel < CHANNELS; channel++) {
                values[channel] = rest(channel);
            }
            return values;
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder("Pose[");
            for (Whole whole : Whole.values()) {
                if (get(whole) != rest(index(whole))) {
                    text.append(whole).append('=').append(get(whole)).append(' ');
                }
            }
            for (Bone bone : Bone.values()) {
                for (Axis axis : Axis.values()) {
                    if (get(bone, axis) != 0.0f) {
                        text.append(bone).append('.').append(axis).append('=').append(get(bone, axis)).append(' ');
                    }
                }
            }
            return text.append(']').toString();
        }
    }

    private static final float F = 1.0f / 24.0f;

    private static final Clip[] CLIPS = new Clip[Variant.values().length];

    static {
        CLIPS[Variant.HOP.ordinal()] = new Clip()
                .whole(Whole.LIFT, 0, 0, 6, 0, 8, 0.09f, 9.5f, 0.12f, 11, 0.09f, 13, 0)
                .whole(Whole.SCALE, 0, 1, 3, 0.99f, 7, 1.015f, 13, 0.985f, 15, 0.995f)
                .key(Bone.WAIST, Axis.POS_Y, 0, 0, 3, 2.5f, 5, 1.5f, 7, -0.5f, 11, 0, 13.5f, 2.5f, 15.5f, 2.8f)
                .key(Bone.WAIST, Axis.ROT_X, 0, 0, 3, 12, 6, -4, 10, -2, 13, 14, 15.5f, 10)
                .key(Bone.HEAD, Axis.ROT_X, 0, 0, 4, -10, 8, 6, 11, 3, 14, -10, 15.5f, -8)
                .key(Bone.RIGHT_ARM, Axis.ROT_X, 0, 0, 3, 30, 6, -25, 8, -60, 10, -50, 12, -12, 14, 14, 15.5f, 12)
                .key(Bone.RIGHT_ARM, Axis.ROT_Z, 0, 0, 3, 8, 8, 25, 12, 15, 15.5f, 8)
                .key(Bone.LEFT_ARM, Axis.ROT_X, 0, 0, 4, 30, 7, -25, 9, -60, 11, -45, 13, -8, 15.5f, 14)
                .key(Bone.LEFT_ARM, Axis.ROT_Z, 0, 0, 4, -8, 9, -25, 13, -15, 15.5f, -8)
                .key(Bone.RIGHT_LEG, Axis.ROT_X, 0, 0, 3, -6, 8, -20, 10, -12, 12, -4, 15, 0)
                .key(Bone.RIGHT_LEG, Axis.ROT_Z, 0, 0, 12, 4, 15.5f, 3)
                .key(Bone.LEFT_LEG, Axis.ROT_X, 0, 0, 3, -6, 8, 12, 10, 6, 12, -4, 15, 0)
                .key(Bone.LEFT_LEG, Axis.ROT_Z, 0, 0, 12, -4, 15.5f, -3);
        CLIPS[Variant.TWIRL.ordinal()] = new Clip()
                .whole(Whole.TURN, 0, 0, 4, -15, 10, 120, 15, 280, 19.5f, 366)
                .whole(Whole.LIFT, 0, 0, 7, 0, 10, 0.04f, 15, 0.05f, 18, 0)
                .key(Bone.WAIST, Axis.ROT_Y, 0, 0, 4, 15, 10, -10, 19.5f, -8)
                .key(Bone.WAIST, Axis.ROT_Z, 0, 0, 4, 3, 9, -6, 15, -6, 19.5f, 3)
                .key(Bone.WAIST, Axis.POS_Y, 0, 0, 4, 1.5f, 8, -0.3f, 17, 0, 19.5f, 1.2f)
                .key(Bone.HEAD, Axis.ROT_Y, 0, 0, 4, 15, 10, -20, 16, -15, 19.5f, 10)
                .key(Bone.HEAD, Axis.ROT_X, 0, 0, 5, 4, 10, -6, 16, -6, 19.5f, 3)
                .key(Bone.RIGHT_ARM, Axis.ROT_X, 0, 0, 4, 10, 9, -12, 16, -10, 19.5f, 2)
                .key(Bone.RIGHT_ARM, Axis.ROT_Z, 0, 0, 5, 10, 10, 55, 16, 50, 19.5f, 22)
                .key(Bone.LEFT_ARM, Axis.ROT_X, 0, 0, 5, 10, 10, -12, 17, -10, 19.5f, 0)
                .key(Bone.LEFT_ARM, Axis.ROT_Z, 0, 0, 6, -10, 11, -55, 17, -50, 19.5f, -24)
                .key(Bone.LEFT_LEG, Axis.ROT_X, 0, 0, 6, 0, 10, -30, 16, -28, 19.5f, -6)
                .key(Bone.LEFT_LEG, Axis.ROT_Z, 0, 0, 10, -8, 16, -8, 19.5f, -2);
        CLIPS[Variant.PROUD.ordinal()] = new Clip()
                .whole(Whole.LIFT, 0, 0, 4, 0.02f, 9, 0, 11, 0.015f, 13, 0)
                .whole(Whole.SCALE, 0, 1, 4, 1.015f, 10, 1.005f)
                .key(Bone.WAIST, Axis.ROT_X, 0, 0, 2, 3, 5, -8, 18, -6)
                .key(Bone.WAIST, Axis.POS_Y, 0, 0, 2, 0.8f, 5, -0.5f, 18, -0.3f)
                .key(Bone.HEAD, Axis.ROT_X, 0, 0, 2, 4, 5, -14, 9, 6, 12, -6, 15, 3, 18, 1)
                .key(Bone.HEAD, Axis.ROT_Z, 0, 0, 6, 4, 18, 4)
                .key(Bone.RIGHT_ARM, Axis.ROT_X, 0, 0, 3, -6, 6, 12, 18, 10)
                .key(Bone.RIGHT_ARM, Axis.ROT_Z, 0, 0, 3, 8, 6, 34, 18, 32)
                .key(Bone.LEFT_ARM, Axis.ROT_X, 0, 0, 4, -6, 7, 12, 19, 10)
                .key(Bone.LEFT_ARM, Axis.ROT_Z, 0, 0, 4, -8, 7, -34, 19, -32)
                .key(Bone.RIGHT_LEG, Axis.ROT_Z, 0, 0, 5, 6, 18, 6)
                .key(Bone.LEFT_LEG, Axis.ROT_Z, 0, 0, 5, -6, 18, -6);
        CLIPS[Variant.WAVE.ordinal()] = new Clip()
                .whole(Whole.LIFT, 0, 0, 7, 0.015f, 25, 0.015f)
                .key(Bone.WAIST, Axis.ROT_Z, 0, 0, 7, 4, 25, 4)
                .key(Bone.WAIST, Axis.ROT_Y, 0, 0, 7, -8, 25, -8)
                .key(Bone.HEAD, Axis.ROT_Z, 0, 0, 8, 8, 25, 6)
                .key(Bone.HEAD, Axis.ROT_Y, 0, 0, 8, -10, 25, -8)
                .key(Bone.HEAD, Axis.ROT_X, 0, 0, 10, -3, 13, 2, 16, -3, 19, 2, 22, -2, 25, 0)
                .key(Bone.RIGHT_ARM, Axis.ROT_X, 0, 0, 4, -80, 7, -150, 25, -150)
                // The roll is back to rest before the raised arm drops, so it never closes inwards.
                .key(Bone.RIGHT_ARM, Axis.ROT_Z, 0, 0, 4, 0, 7, -20, 10, -40, 13, -12, 16, -40, 19, -12, 22, -30, 25, 0)
                .key(Bone.LEFT_ARM, Axis.ROT_X, 0, 0, 7, 5, 25, 5)
                .key(Bone.LEFT_ARM, Axis.ROT_Z, 0, 0, 7, -6, 25, -6);
        CLIPS[Variant.STRETCH.ordinal()] = new Clip()
                .whole(Whole.LIFT, 0, 0, 8, 0.035f, 20, 0.045f, 23, 0.04f)
                .whole(Whole.SCALE, 0, 1, 10, 1.02f, 21, 1.02f)
                .key(Bone.WAIST, Axis.ROT_X, 0, 0, 4, 4, 10, -10, 21, -12)
                .key(Bone.WAIST, Axis.ROT_Z, 0, 0, 12, 0, 15, -6, 18, 5, 21, 0)
                .key(Bone.HEAD, Axis.ROT_X, 0, 0, 4, 4, 10, -16, 21, -20)
                .key(Bone.RIGHT_ARM, Axis.ROT_X, 0, 0, 6, -130, 10, -165, 20, -170, 22, -170)
                // The rolls are back to rest before the raised arms drop, so they never close inwards.
                .key(Bone.RIGHT_ARM, Axis.ROT_Z, 0, 0, 4, 0, 10, -15, 20, -22, 23, -8, 25, 0)
                .key(Bone.LEFT_ARM, Axis.ROT_X, 0, 0, 6, -130, 10, -165, 20, -170, 22, -170)
                .key(Bone.LEFT_ARM, Axis.ROT_Z, 0, 0, 4, 0, 10, 15, 20, 22, 23, 8, 25, 0);
        CLIPS[Variant.SHAKE_OFF.ordinal()] = new Clip()
                .whole(Whole.TURN, 0, 0, 3, 6, 6, -6, 9, 4, 12, -3)
                .whole(Whole.SCALE, 0, 1, 1, 0.99f, 4, 1.01f)
                .key(Bone.WAIST, Axis.ROT_Y, 0, 0, 3, 14, 6, -14, 9, 12, 12, -9, 15, 6, 18, -3)
                .key(Bone.WAIST, Axis.POS_Y, 0, 0, 2, 1, 5, 0, 8, 1, 11, 0, 14, 0.8f)
                .key(Bone.HEAD, Axis.ROT_Z, 0, 0, 4, -8, 7, 8, 10, -6, 13, 5, 16, -3)
                .key(Bone.HEAD, Axis.ROT_Y, 0, 0, 4, -8, 7, 8, 10, -6, 13, 4)
                .key(Bone.RIGHT_ARM, Axis.ROT_X, 0, 0, 4, -12, 7, 10, 10, -8, 13, 6, 17, -3)
                .key(Bone.RIGHT_ARM, Axis.ROT_Z, 0, 0, 4, 18, 7, 4, 10, 16, 13, 5, 16, 10)
                .key(Bone.LEFT_ARM, Axis.ROT_X, 0, 0, 5, 10, 8, -12, 11, 8, 14, -8, 18, 3)
                .key(Bone.LEFT_ARM, Axis.ROT_Z, 0, 0, 4, -4, 7, -18, 10, -5, 13, -16, 16, -6)
                .key(Bone.RIGHT_LEG, Axis.ROT_Z, 0, 0, 3, 3, 6, 0, 9, 3)
                .key(Bone.LEFT_LEG, Axis.ROT_Z, 0, 0, 3, 0, 6, -3, 9, 0, 12, -3);
    }

    private static Variant lastVariant;

    private SkinSwapAnimation() {
    }

    /** How long the move lasts, settle included, in seconds. */
    public static float duration(Variant variant) {
        return CLIPS[variant.ordinal()].duration();
    }

    /** The offsets {@code seconds} into the move; {@link Pose#REST} before and after it. */
    public static Pose sample(Variant variant, float seconds) {
        Clip clip = CLIPS[variant.ordinal()];
        if (!(seconds > 0.0f) || seconds >= clip.duration()) {
            return Pose.REST;
        }
        float[] values = new float[CHANNELS];
        for (int channel = 0; channel < CHANNELS; channel++) {
            Track track = clip.tracks[channel];
            values[channel] = track != null ? sample(channel, track, seconds) : rest(channel);
        }
        return new Pose(values);
    }

    private static float sample(int channel, Track track, float seconds) {
        float released = track.lastTime();
        if (seconds <= released) {
            return track.sample(seconds);
        }
        // Let go of the last keyframe: the spring carries it to rest. A turn settles to the
        // nearest whole turn, which looks the same as no turn at all.
        float from = track.lastValue();
        float to = channel == index(Whole.TURN) ? Math.round(from / 360.0f) * 360.0f : rest(channel);
        float weight = settleWeight(channel, seconds - released - settleDelay(channel));
        return to + (from - to) * weight;
    }

    /**
     * How much of the released offset remains {@code seconds} into the settle: the step response
     * of a damped spring from 1 towards 0, starting with no velocity, faded out over the settle's
     * second half so that it reaches 0 exactly.
     */
    static float settleWeight(int channel, float seconds) {
        if (seconds <= 0.0f) {
            return 1.0f;
        }
        if (seconds >= SETTLE) {
            return 0.0f;
        }
        double t = seconds;
        double spring;
        if (settlesWithoutOvershoot(channel)) {
            spring = Math.exp(-ROLL_SPRING * t) * (1.0 + ROLL_SPRING * t);
        } else {
            double damped = SPRING * Math.sqrt(1.0 - DAMPING * DAMPING);
            spring = Math.exp(-DAMPING * SPRING * t)
                    * (Math.cos(damped * t) + DAMPING / Math.sqrt(1.0 - DAMPING * DAMPING) * Math.sin(damped * t));
        }
        double fade = Math.max(0.0, (t - SETTLE / 2.0) / (SETTLE / 2.0));
        double window = 1.0 - fade * fade * (3.0 - 2.0 * fade);
        return (float) (spring * window);
    }

    /**
     * The next move: any but the previous one. Called on the client thread only.
     *
     * @param random returns a value in {@code [0, bound)} for a bound
     */
    public static Variant next(IntUnaryOperator random) {
        Variant picked = pick(lastVariant, random);
        lastVariant = picked;
        return picked;
    }

    static Variant pick(Variant previous, IntUnaryOperator random) {
        Variant[] variants = Variant.values();
        if (previous == null) {
            return variants[random.applyAsInt(variants.length)];
        }
        int index = random.applyAsInt(variants.length - 1);
        return variants[index >= previous.ordinal() ? index + 1 : index];
    }

    /** A resting pose with one bone channel set, for tests of the skeleton. */
    static Pose withBone(Bone bone, Axis axis, float value) {
        float[] values = Pose.restValues();
        values[index(bone, axis)] = value;
        return new Pose(values);
    }

    private static final class Clip {
        final Track[] tracks = new Track[CHANNELS];
        private float duration;

        /** Keyframes of a whole-model channel as (frame, value) pairs on the 24 fps grid. */
        Clip whole(Whole whole, float... frameValuePairs) {
            return track(index(whole), frameValuePairs);
        }

        /** Keyframes of a bone channel as (frame, value) pairs on the 24 fps grid. */
        Clip key(Bone bone, Axis axis, float... frameValuePairs) {
            return track(index(bone, axis), frameValuePairs);
        }

        float duration() {
            return duration;
        }

        private Clip track(int channel, float[] frameValuePairs) {
            int count = frameValuePairs.length / 2;
            float[] times = new float[count];
            float[] values = new float[count];
            for (int i = 0; i < count; i++) {
                times[i] = frameValuePairs[2 * i] * F;
                values[i] = frameValuePairs[2 * i + 1];
            }
            Track track = new Track(times, values);
            tracks[channel] = track;
            // A channel that ends away from rest still has its settle to play.
            float end = track.lastValue() == rest(channel)
                    ? track.lastTime()
                    : track.lastTime() + settleDelay(channel) + SETTLE;
            duration = Math.max(duration, end);
            return this;
        }
    }

    /** One channel's keyframes; held at the first before it starts. */
    private record Track(float[] times, float[] values) {
        float lastTime() {
            return times[times.length - 1];
        }

        float lastValue() {
            return values[values.length - 1];
        }

        float sample(float seconds) {
            int last = times.length - 1;
            if (seconds <= times[0]) {
                return values[0];
            }
            if (seconds >= times[last]) {
                return values[last];
            }
            int i = 0;
            while (seconds >= times[i + 1]) {
                i++;
            }
            float span = times[i + 1] - times[i];
            float u = (seconds - times[i]) / span;
            return hermite(values[i], values[i + 1], tangent(i) * span, tangent(i + 1) * span, u);
        }

        /** Units per second at a keyframe; zero at the first and last, where the channel is still. */
        private float tangent(int i) {
            if (i == 0 || i == times.length - 1) {
                return 0.0f;
            }
            return (values[i + 1] - values[i - 1]) / (times[i + 1] - times[i - 1]);
        }

        private static float hermite(float p0, float p1, float m0, float m1, float u) {
            float u2 = u * u;
            float u3 = u2 * u;
            return (2.0f * u3 - 3.0f * u2 + 1.0f) * p0
                    + (u3 - 2.0f * u2 + u) * m0
                    + (-2.0f * u3 + 3.0f * u2) * p1
                    + (u3 - u2) * m1;
        }
    }
}
