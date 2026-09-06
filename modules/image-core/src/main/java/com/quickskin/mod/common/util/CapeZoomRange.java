package com.quickskin.mod.common.util;

/**
 * The cape adjust screen's zoom: the legal range for its {@code imgScale}, and the mapping between
 * that range and a 0..1 slider position.
 *
 * <p>The screen has exactly one zoom value. The mouse wheel multiplies it by {@link #WHEEL_STEP}
 * per notch and the slider sets it from a position; both then run it through
 * {@link #clampScale(double, int, int, int)}, so there is one range and one clamp rather than one
 * per input. The bounds are the ones the wheel has always used — a quarter of the contain fit at
 * the bottom, eight times the cover fit at the top — lifted here unchanged so the slider cannot
 * disagree with the wheel about where the ends are.
 *
 * <p><b>The range moves, and that is why the mapping is geometric.</b> Both bounds are exactly
 * proportional to the target atlas width: {@code minScale} and {@code maxScale} each carry one
 * factor of {@code capeW}. Their ratio is therefore independent of the selected resolution, so a
 * position expressed as {@code log(scale/min) / log(max/min)} is invariant when the screen rescales
 * the transform for a new resolution ({@code imgScale *= newCapeW / oldCapeW}). The slider does not
 * move when the user picks a different resolution, and it does not need to: the grid's own
 * display scale is inversely proportional to {@code capeW} too, so the rendered image is the same
 * size on screen before and after.
 *
 * <p>A geometric mapping is also the one the wheel already implies. Zoom is multiplicative, so
 * equal slider travel should mean equal ratio, not equal delta; under this mapping one wheel notch
 * is a constant slider displacement — {@link #wheelStepPosition(int, int, int)} — at every zoom
 * level and every resolution. A linear mapping would make a notch invisible at the bottom of the
 * track and a third of the track at the top.
 *
 * <p>Deliberately free of Minecraft and AWT types so the range, the mapping and the label stay
 * unit testable in the loader-independent test source set.
 */
public final class CapeZoomRange {

    /** Multiplier one mouse-wheel notch applies to the scale. */
    public static final double WHEEL_STEP = 1.15;

    /** How far below the contain fit the user may shrink. */
    private static final double MIN_FACTOR = 0.25;

    /** How far above the cover fit the user may grow. */
    private static final double MAX_FACTOR = 8.0;

    private CapeZoomRange() {
    }

    /**
     * Scale at which the source's width alone fills the atlas.
     *
     * <p>{@code srcW} is floored at 1 so a degenerate source cannot divide by zero. Every real
     * {@code BufferedImage} has a positive width, so this only ever changes behaviour in a case
     * that is already broken further up.
     */
    private static double widthFit(int capeW, int srcW) {
        return (double) Math.max(1, capeW) / Math.max(1, srcW);
    }

    /**
     * Scale at which one source frame's height alone fills the atlas.
     *
     * <p>The atlas height is written as {@code capeW / 2} — the same integer division the screen's
     * clamp used before this class existed — because every entry of the screen's resolution table
     * is 2:1 and every width in it is even, so this is exact. It is floored at 1 for the same
     * reason the source dimensions are: a zero would collapse the range and make {@link #scaleAt}
     * return {@code NaN}. No table entry is anywhere near small enough to reach that floor.
     */
    private static double heightFit(int capeW, int srcFrameH) {
        return (double) Math.max(1, capeW / 2) / Math.max(1, srcFrameH);
    }

    /** Largest scale at which the whole source still fits inside the atlas. */
    public static double containScale(int capeW, int srcW, int srcFrameH) {
        return Math.min(widthFit(capeW, srcW), heightFit(capeW, srcFrameH));
    }

    /**
     * Smallest scale at which the source still covers the whole atlas.
     *
     * <p>This is what "Reset Position" sets, and what {@link #percent} calls 100%.
     */
    public static double coverScale(int capeW, int srcW, int srcFrameH) {
        return Math.max(widthFit(capeW, srcW), heightFit(capeW, srcFrameH));
    }

    /** Bottom of the legal range. */
    public static double minScale(int capeW, int srcW, int srcFrameH) {
        return containScale(capeW, srcW, srcFrameH) * MIN_FACTOR;
    }

    /** Top of the legal range. */
    public static double maxScale(int capeW, int srcW, int srcFrameH) {
        return coverScale(capeW, srcW, srcFrameH) * MAX_FACTOR;
    }

    /**
     * Confine a scale to the legal range.
     *
     * <p>{@code min} can never exceed {@code max}: contain is by definition no larger than cover,
     * and the bottom factor is smaller than the top one, so {@code min = 0.25 * contain <=
     * 0.25 * cover < 8 * cover = max} for every source and every resolution.
     */
    public static double clampScale(double scale, int capeW, int srcW, int srcFrameH) {
        double min = minScale(capeW, srcW, srcFrameH);
        if (Double.isNaN(scale)) {
            return min;
        }
        return Math.max(min, Math.min(maxScale(capeW, srcW, srcFrameH), scale));
    }

    /** Ratio between the ends of the range; independent of the selected resolution. */
    public static double span(int capeW, int srcW, int srcFrameH) {
        return maxScale(capeW, srcW, srcFrameH) / minScale(capeW, srcW, srcFrameH);
    }

    /**
     * Slider position 0..1 for a scale. Inverse of {@link #scaleAt}.
     *
     * <p>Returns exactly {@code 0} and {@code 1} at the ends so the round trip through
     * {@link #scaleAt} is a fixed point there as well as in between.
     */
    public static double position(double scale, int capeW, int srcW, int srcFrameH) {
        double min = minScale(capeW, srcW, srcFrameH);
        double ratio = span(capeW, srcW, srcFrameH);
        if (!(ratio > 1.0)) {
            return 0.0;
        }
        double clamped = clampScale(scale, capeW, srcW, srcFrameH);
        double position = Math.log(clamped / min) / Math.log(ratio);
        if (!(position > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, position);
    }

    /** The scale a slider position maps to. Inverse of {@link #position}. */
    public static double scaleAt(double position, int capeW, int srcW, int srcFrameH) {
        double min = minScale(capeW, srcW, srcFrameH);
        double max = maxScale(capeW, srcW, srcFrameH);
        if (!(position > 0.0)) {
            return min;
        }
        if (position >= 1.0) {
            return max;
        }
        return min * Math.pow(max / min, position);
    }

    /**
     * Slider travel one wheel notch is worth.
     *
     * <p>Constant at every zoom level and every resolution, which is the property that lets the
     * wheel and the slider be two views of one value rather than two zoom models.
     */
    public static double wheelStepPosition(int capeW, int srcW, int srcFrameH) {
        double ratio = span(capeW, srcW, srcFrameH);
        if (!(ratio > 1.0)) {
            return 0.0;
        }
        return Math.log(WHEEL_STEP) / Math.log(ratio);
    }

    /**
     * The zoom as the user reads it, relative to the cover fit.
     *
     * <p>100% is what "Reset Position" produces — the source exactly covering the cape. Reading the
     * raw scale instead would print single-digit percentages for a large source and four-digit ones
     * for a small one; relative to cover the top of the range is always 800% and the bottom is
     * {@code 25% * contain/cover}, which is 25% for a 2:1 source and lower the further the source's
     * aspect is from the cape's. Because both scales carry the same factor of {@code capeW}, the
     * number does not jump when the user picks a different resolution — matching the slider, which
     * does not move either.
     *
     * <p>Floored at 1 so a wildly mismatched source — a 100:1 banner, say, whose bottom end is
     * under half a percent — reads as a small zoom rather than as {@code 0%}, which would say the
     * image is not being drawn at all when it plainly is.
     */
    public static int percent(double scale, int capeW, int srcW, int srcFrameH) {
        double cover = coverScale(capeW, srcW, srcFrameH);
        if (!(cover > 0.0)) {
            return 0;
        }
        return Math.max(1,
                (int) Math.round(clampScale(scale, capeW, srcW, srcFrameH) / cover * 100.0));
    }

    /** {@code "100%"} — the substituted half of the zoom slider's label. */
    public static String formatPercent(double scale, int capeW, int srcW, int srcFrameH) {
        return percent(scale, capeW, srcW, srcFrameH) + "%";
    }

    /**
     * Move an offset so that the point of cape space at {@code anchor} stays where it is when the
     * scale is multiplied by {@code scaleRatio}.
     *
     * <p>The screen's one zoom-anchoring rule. The wheel passes the cursor as the anchor and the
     * slider passes the centre of the cape area, which is the only difference between them.
     *
     * <p>Composing two calls equals one call with the product of the ratios, so a zoom dragged
     * through a hundred intermediate values lands exactly where one jump would. That only holds
     * while each call chains off the offset the previous one produced — feed it a rounded offset
     * and the property is lost, badly, because a small ratio asks for a sub-pixel move that rounds
     * to nothing and the offset stops following the scale at all.
     */
    public static double reanchorOffset(double offset, double anchor, double scaleRatio) {
        return anchor - (anchor - offset) * scaleRatio;
    }
}
