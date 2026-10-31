package com.quickskin.mod.client.rendering;

/**
 * The jumps the skin menu's model makes onto its block and off it when the player picks the
 * Sitting pose or leaves it.
 *
 * <p>{@link Move#ONTO}: the model crouches with the arms back and springs up raising them; at the
 * top of the jump the block pops up under it, growing past its size and settling; the model lands
 * sitting, and the block squashes under it and wobbles like jelly, carrying it down and up again,
 * until both are at rest.
 *
 * <p>{@link Move#OFF}, its reverse: the block squashes under the sitting model like a trampoline
 * and throws it up, still sitting, its arms flung up; in the air the block swells a little and
 * shrinks away, the model straightens at the top of the jump and lands on its feet with a small
 * squash, standing in its idle, from which the pose the player picked takes over.
 *
 * <p>Units: seconds for time; blocks for the lift, up positive, as the model stands one and a half
 * blocks tall over its feet; factors for the scales; radians for the limb offsets added to the
 * pose, with the vanilla model's signs (a negative X rotation swings an arm forward and up, and a
 * leg forward). The model scales about its feet, the block about the middle of its bottom face.
 *
 * <p>Sampling is a pure function of the move and the elapsed time; past the move's
 * {@link #duration} it is over, with the model at rest on the block or beside it.
 */
public final class SitJump {

    /** The two jumps. */
    public enum Move {
        /** From standing or walking onto the block, sitting. */
        ONTO,
        /** From the block back to standing or walking. */
        OFF
    }

    /** The crouch before taking off onto the block. */
    static final float CROUCH = 0.14f;
    /** The time in the air, from taking off to landing on the block. */
    static final float AIR = 0.6f;
    /** How high the feet rise, in blocks. */
    static final float HEIGHT = 0.5f;
    /** When the block pops up: just before the top of the jump. */
    static final float POP = CROUCH + AIR / 2 - 0.02f;
    /** How long the pop takes to settle at the block's size. */
    static final float POP_SETTLE = 0.38f;
    /** When the model lands on the block. */
    static final float LAND = CROUCH + AIR;
    /** How long the jelly wobble lasts. */
    static final float WOBBLE = 0.9f;

    /** How long the block takes to squash before it throws the model off. */
    static final float WIND_UP = 0.2f;
    /** The time in the air off the block. */
    static final float AIR_OFF = 0.62f;
    /** How high the jump off the block rises, in blocks. */
    static final float HEIGHT_OFF = 0.55f;
    /** How far the block squashes before it throws the model, as a fraction of its height. */
    static final float LAUNCH_SQUASH = 0.2f;
    /** The block springs back from the squash, then shrinks away. */
    static final float REBOUND = 0.2f;
    static final float SHRINK = 0.16f;
    /** When the model lands beside where the block was. */
    static final float LAND_OFF = WIND_UP + AIR_OFF;
    /** How long after landing the pose the player picked starts taking over from the idle, and how long that takes. */
    static final float SETTLE_DELAY = 0.1f;
    static final float SETTLE = 0.35f;

    /** The block's height in blocks, as the preview draws it; its top sinks with the squash. */
    static final float BLOCK_HEIGHT = 0.675f;

    /** Smaller than this, the block is not drawn: a scale of zero has no normals. */
    private static final float SMALLEST_BLOCK = 0.001f;

    /**
     * A jump at one moment. {@code sitting} says whether the model takes the sitting pose, and
     * {@code blockShown} whether the block is drawn. A model not sitting stands in its idle, and
     * {@code settled} says how far the pose the player picked has taken over from that idle.
     */
    public record Pose(boolean sitting, boolean blockShown, float lift, float scaleX, float scaleY,
                       float armPitch, float rightLegPitch, float leftLegPitch, float headPitch,
                       float blockScaleX, float blockScaleY, float settled) {
        /** Sitting on the block, as the Sitting pose is without a jump. */
        public static final Pose ON_BLOCK = new Pose(true, true, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f);
        /** Standing in the picked pose with no block, as it is without a jump. */
        public static final Pose OFF_BLOCK = new Pose(false, false, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f);
    }

    private SitJump() {
    }

    /** How long a move lasts. */
    public static float duration(Move move) {
        return move == Move.ONTO ? LAND + WOBBLE : LAND_OFF + SETTLE_DELAY + SETTLE;
    }

    /** A move {@code seconds} after the player picked the pose that started it. */
    public static Pose sample(Move move, float seconds) {
        if (seconds >= duration(move)) {
            return move == Move.ONTO ? Pose.ON_BLOCK : Pose.OFF_BLOCK;
        }
        float t = Math.max(0.0f, seconds);
        return move == Move.ONTO ? onto(t) : off(t);
    }

    private static Pose onto(float t) {
        if (t < CROUCH) {
            float k = easeOut(t / CROUCH);
            return new Pose(false, false, 0.0f, 1.0f + 0.04f * k, 1.0f - 0.1f * k,
                    0.7f * k, 0.0f, 0.0f, 0.15f * k, 1.0f, 1.0f, 0.0f);
        }
        float pop = t < POP ? 0.0f : elastic((t - POP) / POP_SETTLE);
        boolean blockShown = pop > SMALLEST_BLOCK;
        if (t < LAND) {
            float u = (t - CROUCH) / AIR;
            float lift = HEIGHT * 4.0f * u * (1.0f - u);
            float apex = CROUCH + AIR / 2;
            if (t < apex) {
                // Rising: the arms swing up, the legs part, and the body springs out of the crouch
                // into a stretch that eases off by the top
                float k = easeOut((t - CROUCH) / 0.12f);
                float v = (t - CROUCH) / (apex - CROUCH);
                float spring = smooth((t - CROUCH) / 0.1f);
                return new Pose(false, blockShown, lift, lerp(1.04f, lerp(0.97f, 1.0f, v), spring),
                        lerp(0.9f, lerp(1.08f, 1.0f, v), spring),
                        lerp(0.7f, -2.7f, k), -0.55f * k, 0.35f * k, 0.0f, pop, pop, 0.0f);
            }
            // Falling: already sitting, the arms coming down to the thighs
            float k = easeOut((t - apex) / 0.16f);
            return new Pose(true, blockShown, lift, 1.0f, 1.0f, lerp(-1.8f, 0.0f, k), 0.0f, 0.0f, 0.0f, pop, pop, 0.0f);
        }
        // Landed: the block squashes and wobbles, and the model rides its top. The squash starts at
        // the speed the model lands with, so the fall carries on into it
        float w = t - LAND;
        float k = (float) (Math.exp(-w / 0.16) * Math.sin(w / 0.36 * 2.0 * Math.PI));
        float blockScaleY = 1.0f - 0.3f * k;
        float blockScaleX = 1.0f + 0.16f * k;
        return new Pose(true, true, -(1.0f - blockScaleY) * BLOCK_HEIGHT, 1.0f + 0.04f * k, 1.0f - 0.08f * k,
                0.0f, 0.0f, 0.0f, 0.0f, pop * blockScaleX, pop * blockScaleY, 0.0f);
    }

    private static Pose off(float t) {
        if (t < WIND_UP) {
            // The block squashes under the model like a trampoline, the model riding its top down
            float k = smooth(t / WIND_UP);
            float blockScaleY = 1.0f - LAUNCH_SQUASH * k;
            return new Pose(true, true, (blockScaleY - 1.0f) * BLOCK_HEIGHT, 1.0f + 0.03f * k, 1.0f - 0.05f * k,
                    0.5f * k, 0.0f, 0.0f, 0.1f * k, 1.0f + 0.1f * k, blockScaleY, 0.0f);
        }
        // The block springs back past its height, then shrinks away before the model comes down
        float blockScaleX;
        float blockScaleY;
        float sinceLaunch = t - WIND_UP;
        if (sinceLaunch < REBOUND) {
            float k = easeOut(sinceLaunch / REBOUND);
            blockScaleX = lerp(1.1f, 0.95f, k);
            blockScaleY = lerp(1.0f - LAUNCH_SQUASH, 1.12f, k);
        } else {
            float u = Math.min(1.0f, (sinceLaunch - REBOUND) / SHRINK);
            float left = 1.0f - u * u;
            blockScaleX = 0.95f * left;
            blockScaleY = 1.12f * left;
        }
        boolean blockShown = Math.min(blockScaleX, blockScaleY) > SMALLEST_BLOCK;
        if (t < LAND_OFF) {
            // Thrown from the squashed block's top, it rises over the swelling block and lands on the floor
            float u = sinceLaunch / AIR_OFF;
            float start = -LAUNCH_SQUASH * BLOCK_HEIGHT;
            float lift = start * (1.0f - u) + HEIGHT_OFF * 4.0f * u * (1.0f - u);
            float apex = WIND_UP + AIR_OFF / 2;
            if (t < apex) {
                // Still sitting, the arms flung up and the body stretching out of the squash
                float k = easeOut(sinceLaunch / 0.15f);
                float spring = smooth(sinceLaunch / 0.1f);
                float v = sinceLaunch / (apex - WIND_UP);
                return new Pose(true, blockShown, lift, lerp(1.03f, lerp(0.97f, 1.0f, v), spring),
                        lerp(0.95f, lerp(1.06f, 1.0f, v), spring),
                        lerp(0.5f, -2.2f, k), 0.0f, 0.0f, lerp(0.1f, 0.0f, k), blockScaleX, blockScaleY, 0.0f);
            }
            // Standing from the top of the jump, the arms coming down and the legs closing to land
            float k = easeOut((t - apex) / 0.2f);
            float v = (t - apex) / (LAND_OFF - apex);
            return new Pose(false, blockShown, lift, 1.0f, 1.0f, lerp(-2.2f, 0.0f, k),
                    lerp(-0.3f, 0.0f, v), lerp(0.2f, 0.0f, v), 0.0f, blockScaleX, blockScaleY, 0.0f);
        }
        // Landed on its feet with a small squash, then the picked pose takes over from the idle
        float w = t - LAND_OFF;
        float k = (float) (Math.exp(-w / 0.12) * Math.sin(w / 0.3 * 2.0 * Math.PI));
        return new Pose(false, false, 0.0f, 1.0f + 0.05f * k, 1.0f - 0.1f * k, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, smooth((w - SETTLE_DELAY) / SETTLE));
    }

    private static float easeOut(float x) {
        float c = Math.max(0.0f, Math.min(1.0f, x));
        return 1.0f - (1.0f - c) * (1.0f - c) * (1.0f - c);
    }

    private static float smooth(float x) {
        float c = Math.max(0.0f, Math.min(1.0f, x));
        return c * c * (3.0f - 2.0f * c);
    }

    private static float lerp(float a, float b, float u) {
        return a + (b - a) * Math.max(0.0f, Math.min(1.0f, u));
    }

    /** From 0 to 1, overshooting and settling. */
    private static float elastic(float u) {
        if (u <= 0.0f) {
            return 0.0f;
        }
        if (u >= 1.0f) {
            return 1.0f;
        }
        return (float) (1.0 - Math.exp(-6.0 * u) * Math.cos(u * 3.2 * Math.PI));
    }
}
