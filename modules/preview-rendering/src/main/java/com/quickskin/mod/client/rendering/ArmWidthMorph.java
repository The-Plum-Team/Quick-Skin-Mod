package com.quickskin.mod.client.rendering;

/**
 * The skin menu model's arms changing width when the other arm width is picked: they go from the
 * width they had to the new one in a little over half a second, pass it and spring back like
 * rubber, overshooting once more a little before they settle. Widths are in model pixels, 4 for
 * the classic arms and 3 for the slim ones.
 *
 * <p>Sampling is a pure function of the widths and the elapsed time.
 */
public final class ArmWidthMorph {

    /** How long the change lasts, in seconds. */
    public static final float DURATION = 0.56f;
    /** The spring's turns over the change and how fast its swing dies down. */
    private static final double TURNS = 1.3;
    private static final double DECAY = 3.0;
    /** Where the last of the swing starts fading out, so the change ends on the new width exactly. */
    private static final double FADE_FROM = 0.6;

    private ArmWidthMorph() {
    }

    /** The arms' width {@code seconds} into a change from {@code from} to {@code to}. */
    public static float width(float from, float to, float seconds) {
        if (!(seconds > 0.0f)) {
            return from;
        }
        if (seconds >= DURATION) {
            return to;
        }
        double t = seconds / DURATION;
        double fade = 1.0;
        if (t > FADE_FROM) {
            double k = (t - FADE_FROM) / (1.0 - FADE_FROM);
            fade = 1.0 - k * k * (3.0 - 2.0 * k);
        }
        double swing = Math.cos(t * 2.0 * Math.PI * TURNS) * Math.exp(-DECAY * t) * fade;
        return (float) (to + (from - to) * swing);
    }
}
