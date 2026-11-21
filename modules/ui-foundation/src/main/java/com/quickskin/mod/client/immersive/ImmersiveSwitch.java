package com.quickskin.mod.client.immersive;

/**
 * How one floating screen gives way to the next in the immersive view, such as the skin menu to
 * the cape menu and back: the screen that leaves fades out while the one that comes fades in over
 * it, and the camera travels from where the first stood the player to where the second does,
 * starting and stopping gently. Every answer goes by the seconds since the switch began.
 */
final class ImmersiveSwitch {
    /** How long a switch lasts, in seconds. */
    static final float LENGTH = 0.65f;
    /** The camera, and the player's turn, take this long. */
    private static final float TRAVEL = 0.62f;
    /** The screen that leaves is gone by here. */
    private static final float LEAVE = 0.26f;
    /** The screen that comes starts to show here, and shows whole this much later. */
    private static final float ARRIVE_FROM = 0.08f;
    private static final float ARRIVE = 0.32f;

    private ImmersiveSwitch() {
    }

    /** How far the camera has gone from the old screen's place (0) to the new one's (1). */
    static float travelled(float seconds) {
        return eased(seconds / TRAVEL);
    }

    /** How much of the screen that leaves still shows, from 1 to 0. */
    static float leaving(float seconds) {
        return 1.0f - eased(seconds / LEAVE);
    }

    /** How much of the screen that comes shows, from 0 to 1. */
    static float arriving(float seconds) {
        return eased((seconds - ARRIVE_FROM) / ARRIVE);
    }

    /** 0 to 1 as {@code share} does, slow at both ends. */
    private static float eased(float share) {
        float t = Math.max(0.0f, Math.min(1.0f, share));
        return t * t * (3.0f - 2.0f * t);
    }
}
