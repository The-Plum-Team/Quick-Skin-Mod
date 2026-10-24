package com.quickskin.mod.client.gui.widget;

/**
 * Timings and curves of the skin column's animations, in GUI pixels and milliseconds.
 *
 * <ul>
 *   <li>A skin added to the grid drops into its tile and bounces; added to the list, its card
 *       slides in from the column's left edge.</li>
 *   <li>A skin marked as a favorite flashes gold, swells a little and sends out a gold frame,
 *       and its star pops in.</li>
 *   <li>A deleted skin hops, then falls out of the column, tipping over.</li>
 *   <li>Every other skin glides from where it was drawn to its new place: with a little
 *       overshoot when it closes the gap of a deleted one, a moment after that one starts to
 *       fall.</li>
 * </ul>
 */
final class SkinListMotion {
    static final long ARRIVAL_MILLIS = 500L;
    static final long GLIDE_MILLIS = 500L;
    /** How long the skins behind a deleted one wait before they close its gap. */
    static final long GAP_DELAY_MILLIS = 120L;
    static final long FAVORITE_MILLIS = 500L;
    static final long FAVORITE_FRAME_MILLIS = 600L;
    /**
     * How far, in GUI pixels, a new favorite may be drawn past the list's edges: its frame's
     * growth by the end of the frame, and the swell of a card, fit in it.
     */
    static final int FAVORITE_OVERFLOW = 12;
    /** A deleted skin stops being drawn once it has left the column, or after this long. */
    static final long DEPARTURE_MAX_MILLIS = 1500L;
    /** The favorite gold, as the star and border use it. */
    static final int FAVORITE_COLOR = 0xFFFFC23A;

    private SkinListMotion() {
    }

    /** How an entry is drawn relative to its place: an offset, a scale and a turn about its centre. */
    record Pose(float dx, float dy, float scale, float degrees) {
        static final Pose REST = new Pose(0, 0, 1, 0);

        boolean isRest() {
            return dx == 0 && dy == 0 && scale == 1 && degrees == 0;
        }
    }

    /**
     * A newly added skin, {@code elapsed} milliseconds after it appeared. A tile drops from one
     * tile above its place; a card comes in from beyond the column's left edge.
     */
    static Pose arrival(boolean rows, long elapsed, int entryWidth, int entryHeight) {
        double p = progress(elapsed, ARRIVAL_MILLIS);
        if (rows) {
            return new Pose((float) (-(1 - outCubic(p)) * (entryWidth + 8)), 0, 1, 0);
        }
        return new Pose(0, (float) (-(1 - outBounce(p)) * entryHeight * 1.1), 1, 0);
    }

    /** A deleted skin, {@code elapsed} milliseconds after its deletion: a hop, then a fall. */
    static Pose departure(long elapsed) {
        double t = elapsed / 1000.0;
        double hop = Math.sin(Math.min(1.0, t * 5.0) * Math.PI) * 5.0;
        double fall = 45.0 * Math.pow(t / 0.5, 3);
        return new Pose(0, (float) (fall - hop), 1, (float) (t * 50.0));
    }

    /** The skin just marked as a favorite: a swell that settles back. */
    static Pose favorite(long elapsed) {
        double p = progress(elapsed, FAVORITE_MILLIS);
        return new Pose(0, 0, (float) (1 + 0.08 * (1 - p)), 0);
    }

    /** The gold over a new favorite, from 0 to 1: bright at once, gone after 70% of the time. */
    static float favoriteFlash(long elapsed) {
        return (float) (0.75 * (1 - clamp(progress(elapsed, FAVORITE_MILLIS) / 0.7)));
    }

    /** How far, in GUI pixels, the gold frame of a new favorite stands out from the entry. */
    static float favoriteFrameGrowth(long elapsed) {
        return (float) (2 + elapsed / 1000.0 * 15.0);
    }

    /** The gold frame's opacity, from 1 to 0. */
    static float favoriteFrameAlpha(long elapsed) {
        return (float) (1 - progress(elapsed, FAVORITE_FRAME_MILLIS));
    }

    /** The size of a new favorite's star, popping past its size before it settles. */
    static float starScale(long elapsed) {
        return (float) outBack(clamp(progress(elapsed, FAVORITE_MILLIS) * 1.6), 3.0);
    }

    /**
     * What is left of a glide that started {@code from} pixels away: from {@code from} down to
     * 0. After a deletion it waits {@link #GAP_DELAY_MILLIS} and overshoots a little.
     */
    static float glide(float from, long elapsed, boolean closingGap) {
        if (closingGap) {
            double p = progress(elapsed - GAP_DELAY_MILLIS, GLIDE_MILLIS);
            return (float) (from * (1 - outBack(p, 1.9)));
        }
        return (float) (from * (1 - outCubic(progress(elapsed, GLIDE_MILLIS))));
    }

    static boolean glideDone(long elapsed, boolean closingGap) {
        return elapsed >= GLIDE_MILLIS + (closingGap ? GAP_DELAY_MILLIS : 0);
    }

    static double progress(long elapsed, long duration) {
        return clamp((double) elapsed / duration);
    }

    static double clamp(double p) {
        return Math.max(0.0, Math.min(1.0, p));
    }

    static double outCubic(double p) {
        double q = 1 - clamp(p);
        return 1 - q * q * q;
    }

    static double outBack(double p, double overshoot) {
        double q = clamp(p) - 1;
        return 1 + (overshoot + 1) * q * q * q + overshoot * q * q;
    }

    static double outBounce(double p) {
        p = clamp(p);
        double n = 7.5625;
        double d = 2.75;
        if (p < 1 / d) {
            return n * p * p;
        }
        if (p < 2 / d) {
            p -= 1.5 / d;
            return n * p * p + 0.75;
        }
        if (p < 2.5 / d) {
            p -= 2.25 / d;
            return n * p * p + 0.9375;
        }
        p -= 2.625 / d;
        return n * p * p + 0.984375;
    }
}
