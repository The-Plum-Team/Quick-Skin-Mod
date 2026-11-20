package com.quickskin.mod.networking;

/**
 * The pose a player plays while their immersive skin menu floats round them, as it travels in
 * {@code immersive_pose} and {@code sync_immersive_pose} (capability {@code immersive-poses}):
 * which of the menu's poses, the way the body faces, whether the menu is closing, and a counter
 * of the skin-change moves with the one played last. Other Quick Skin clients play it on their
 * own clock; nothing of the player changes on the server.
 *
 * @param pose        {@link #NONE}, {@link #IDLE}, {@link #WALK} or {@link #SIT}
 * @param yaw         the way the body faces, in degrees, from -180 to 180
 * @param flags       {@link #CLOSING}, and from the server {@link #SETTLED}
 * @param swaps       how many skin-change moves the player has made, counted from 0 to 255 and round again
 * @param swapVariant which skin-change move the latest one was, from 0 to {@link #MAX_SWAP_VARIANT}
 */
public record ImmersivePose(int pose, float yaw, int flags, int swaps, int swapVariant) {
    /** No pose: the player is drawn as the game draws them. */
    public static final int NONE = 0;
    public static final int IDLE = 1;
    public static final int WALK = 2;
    public static final int SIT = 3;
    public static final int MAX_POSE = SIT;

    /** The menu is closing: the body turns back while the pose plays on, until {@link #NONE} follows. */
    public static final int CLOSING = 1;
    /**
     * Set only by the server, on a pose it sends a client that did not see it start (it joined,
     * changed world, or the pose began elsewhere): the body is already turned, with no jump.
     */
    public static final int SETTLED = 2;
    /** The flags a client may send. */
    public static final int CLIENT_FLAGS = CLOSING;
    /** The flags a server may send. */
    public static final int SERVER_FLAGS = CLOSING | SETTLED;

    public static final int MAX_SWAPS = 255;
    public static final int MAX_SWAP_VARIANT = 15;

    /** Bytes of one pose on the wire after the player's UUID: pose, flags, yaw, swaps, variant. */
    public static final int WIRE_BYTES = 1 + 1 + 4 + 1 + 1;

    public static final ImmersivePose OFF = new ImmersivePose(NONE, 0.0f, 0, 0, 0);

    /** Whether the player plays a pose at all. */
    public boolean active() {
        return pose != NONE;
    }

    public boolean closing() {
        return (flags & CLOSING) != 0;
    }

    public boolean settled() {
        return (flags & SETTLED) != 0;
    }

    /** The same pose with {@code flag} set. */
    public ImmersivePose with(int flag) {
        return new ImmersivePose(pose, yaw, flags | flag, swaps, swapVariant);
    }

    /** The same pose without {@code flag}. */
    public ImmersivePose without(int flag) {
        return new ImmersivePose(pose, yaw, flags & ~flag, swaps, swapVariant);
    }

    /** Whether a pose a client sent is well formed. */
    public static boolean isValidFromClient(int pose, float yaw, int flags, int swaps, int swapVariant) {
        return isValid(pose, yaw, flags, CLIENT_FLAGS, swaps, swapVariant);
    }

    /** Whether a pose a server sent is well formed. */
    public static boolean isValidFromServer(int pose, float yaw, int flags, int swaps, int swapVariant) {
        return isValid(pose, yaw, flags, SERVER_FLAGS, swaps, swapVariant);
    }

    private static boolean isValid(int pose, float yaw, int flags, int allowedFlags, int swaps, int swapVariant) {
        return pose >= NONE && pose <= MAX_POSE
                && Float.isFinite(yaw) && yaw >= -180.0f && yaw <= 180.0f
                && (flags & ~allowedFlags) == 0
                && swaps >= 0 && swaps <= MAX_SWAPS
                && swapVariant >= 0 && swapVariant <= MAX_SWAP_VARIANT;
    }

    /** {@code degrees} brought into -180 to 180, as the pose carries it; 0 for anything not finite. */
    public static float wrapYaw(float degrees) {
        if (!Float.isFinite(degrees)) return 0.0f;
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    /** The pose id of one of the skin menu's pose names ({@code idle}, {@code walk}, {@code sit}). */
    public static int poseOf(String animation) {
        if ("walk".equalsIgnoreCase(animation)) return WALK;
        if ("sit".equalsIgnoreCase(animation)) return SIT;
        return IDLE;
    }

    /** The skin menu's name of a pose id, or null for {@link #NONE}. */
    public static String animationOf(int pose) {
        return switch (pose) {
            case IDLE -> "idle";
            case WALK -> "walk";
            case SIT -> "sit";
            default -> null;
        };
    }
}
