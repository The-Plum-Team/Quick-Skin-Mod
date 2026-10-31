package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.rendering.PreviewLoops.Idle;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Pose;

import java.util.function.IntUnaryOperator;

/**
 * A preview's idle: one picked at random, looping until {@link #shuffle()} asks for another,
 * which the skin menu does with every skin change. The next is never the same as the last, and it
 * crossfades in over a second and a half so the change is never a jump. Each menu has its own,
 * kept while it rebuilds its preview (see {@link PreviewPlayerData#shareIdles}).
 */
public final class IdleShuffle {

    private static final double BLEND = 1.5;

    private Idle current;
    private Idle previous;
    private double changedAt;
    private boolean shuffleRequested;

    /**
     * The idle pose {@code seconds} on the preview clock.
     *
     * @param random returns a value in {@code [0, bound)} for a bound
     */
    public Pose sample(double seconds, IntUnaryOperator random) {
        if (current == null) {
            current = pick(null, random);
            changedAt = seconds - BLEND;
            shuffleRequested = false;
        } else if (shuffleRequested) {
            previous = current;
            current = pick(current, random);
            changedAt = seconds;
            shuffleRequested = false;
        }
        Pose pose = PreviewLoops.idle(current, seconds);
        double blend = (seconds - changedAt) / BLEND;
        if (previous != null && blend < 1.0) {
            pose = Pose.mix(PreviewLoops.idle(previous, seconds), pose,
                    (float) PreviewLoops.smootherstep(Math.max(0.0, blend)));
        }
        return pose;
    }

    /** Changes to another idle at the next sample; before the first sample, there is none to change. */
    public void shuffle() {
        if (current != null) {
            shuffleRequested = true;
        }
    }

    /** The idle playing, or fading in; null before the first sample. */
    public Idle current() {
        return current;
    }

    static Idle pick(Idle previous, IntUnaryOperator random) {
        Idle[] idles = Idle.values();
        if (previous == null) {
            return idles[random.applyAsInt(idles.length)];
        }
        int index = random.applyAsInt(idles.length - 1);
        return idles[index >= previous.ordinal() ? index + 1 : index];
    }
}
