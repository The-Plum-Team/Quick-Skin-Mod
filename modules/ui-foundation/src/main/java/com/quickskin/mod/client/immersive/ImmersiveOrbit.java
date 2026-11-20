package com.quickskin.mod.client.immersive;

/**
 * A drag that takes the immersive view's camera round the player, as a drag turns a menu's own
 * model on its turntable. It goes by where the mouse really is across the window, not by where it
 * points on the floating screen, which moves with the camera it turns.
 */
public final class ImmersiveOrbit {
    /** Degrees of turn for each GUI unit the mouse moves, as the skin menu's model turns. */
    private static final float SENSITIVITY = 0.8f;

    private boolean turning;
    private double startX;
    private float startOrbit;

    /** Starts a drag in the immersive view; answers false, and starts nothing, outside it. */
    public boolean press() {
        if (!ImmersiveMode.active()) {
            return false;
        }
        turning = true;
        startX = ImmersiveMode.realMouseX();
        startOrbit = ImmersiveMode.orbit();
        return true;
    }

    /** Turns the camera for a drag this started; answers whether one is going on. */
    public boolean drag() {
        if (!turning) {
            return false;
        }
        if (!ImmersiveMode.active()) {
            turning = false;
            return false;
        }
        ImmersiveMode.setOrbit(turned(startOrbit, startX, ImmersiveMode.realMouseX()));
        return true;
    }

    /** Where a drag that started at {@code startX}, with the camera at {@code startOrbit}, has it at {@code nowX}. */
    static float turned(float startOrbit, double startX, double nowX) {
        return startOrbit + (float) (nowX - startX) * SENSITIVITY;
    }

    /** Ends the drag; answers whether one was going on. */
    public boolean release() {
        boolean was = turning;
        turning = false;
        return was;
    }
}
