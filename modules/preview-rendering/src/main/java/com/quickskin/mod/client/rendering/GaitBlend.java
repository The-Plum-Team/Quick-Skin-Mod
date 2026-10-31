package com.quickskin.mod.client.rendering;

/**
 * How far the skin menu's model is walking, from 0 (its idle) to 1 (the full stride), as it eases
 * into the walk when the player picks Walking and out of it when the player leaves it.
 *
 * <p>Starting takes {@link #START} seconds and stopping {@link #STOP}, both eased at each end, so
 * the stride grows from the idle and shrinks back into it. A change of mind halfway turns back from
 * wherever the blend is, at the same pace, so the model never jumps.
 */
public final class GaitBlend {

    /** How long the model takes to go from its idle into the full stride. */
    static final float START = 0.45f;
    /** How long it takes to stop walking and settle into its idle. */
    static final float STOP = 0.4f;

    private float from;
    private boolean walking;
    private long startNanos;
    private boolean blending;

    /** Walking or not at once, as a restored pose is. */
    public void set(boolean walking) {
        this.walking = walking;
        this.from = walking ? 1.0f : 0.0f;
        this.blending = false;
    }

    /** Eases from where the model is now towards walking or standing. */
    public void ease(boolean walking, long nowNanos) {
        if (walking == this.walking && !blending) {
            return;
        }
        this.from = amount(nowNanos);
        this.walking = walking;
        this.startNanos = nowNanos;
        this.blending = true;
    }

    /** How far the model is walking at {@code nowNanos}. */
    public float amount(long nowNanos) {
        float to = walking ? 1.0f : 0.0f;
        if (!blending) {
            return to;
        }
        // The whole way takes the full time; part of the way takes its share
        float span = Math.abs(to - from) * (walking ? START : STOP);
        float seconds = (nowNanos - startNanos) / 1.0e9f;
        if (span <= 0.0f || seconds >= span) {
            blending = false;
            from = to;
            return to;
        }
        float u = Math.max(0.0f, seconds / span);
        return from + (to - from) * (u * u * (3.0f - 2.0f * u));
    }
}
