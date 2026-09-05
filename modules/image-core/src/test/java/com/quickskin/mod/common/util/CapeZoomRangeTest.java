package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cape adjust screen's zoom range and slider mapping.
 *
 * <p>These are the properties the screen relies on and cannot check for itself: that the range is
 * the wheel's historical one, that position and scale are exact inverses, that the ends clamp, and
 * that the mapping survives a target-resolution change without the slider moving.
 */
class CapeZoomRangeTest {

    /** The screen's resolution table; every entry is 2:1. */
    private static final int[] CAPE_WIDTHS = {64, 128, 256, 512, 1024};

    /** A 2:1 source, a wide one, a tall one, a tiny one and a very large one. */
    private static final int[][] SOURCES = {
            {64, 32}, {70, 40}, {4096, 2048}, {1000, 100}, {100, 1000}, {1, 1}, {8000, 3}
    };

    private static final double EPSILON = 1.0e-9;

    // --- the range is exactly the one mouseScrolled has always used ---

    /**
     * The bounds this class returns must equal the literal expression the screen's wheel handler
     * carried before the slider existed, including its integer {@code capeW / 2}.
     */
    @Test
    void boundsReproduceTheOriginalWheelClamp() {
        for (int capeW : CAPE_WIDTHS) {
            for (int[] source : SOURCES) {
                int srcW = source[0];
                int srcFrameH = source[1];
                double expectedMin = Math.min((double) capeW / srcW,
                        (double) (capeW / 2) / srcFrameH) * 0.25;
                double expectedMax = Math.max((double) capeW / srcW,
                        (double) (capeW / 2) / srcFrameH) * 8.0;
                assertEquals(expectedMin, CapeZoomRange.minScale(capeW, srcW, srcFrameH), 0.0,
                        "min for " + capeW + " / " + srcW + "x" + srcFrameH);
                assertEquals(expectedMax, CapeZoomRange.maxScale(capeW, srcW, srcFrameH), 0.0,
                        "max for " + capeW + " / " + srcW + "x" + srcFrameH);
            }
        }
    }

    /** {@link CapeZoomRange#coverScale} must be what "Reset Position" computes. */
    @Test
    void coverScaleIsTheResetFit() {
        for (int capeW : CAPE_WIDTHS) {
            int capeH = capeW / 2;
            for (int[] source : SOURCES) {
                double reset = Math.max((double) capeW / source[0], (double) capeH / source[1]);
                assertEquals(reset, CapeZoomRange.coverScale(capeW, source[0], source[1]), 0.0);
            }
        }
    }

    /** min below max for every source and resolution, so the clamp is never inverted. */
    @Test
    void minNeverExceedsMax() {
        for (int capeW : CAPE_WIDTHS) {
            for (int[] source : SOURCES) {
                double min = CapeZoomRange.minScale(capeW, source[0], source[1]);
                double max = CapeZoomRange.maxScale(capeW, source[0], source[1]);
                assertTrue(min > 0.0, "min must be positive");
                assertTrue(min < max, "min " + min + " must stay below max " + max);
            }
        }
    }

    /** The reset fit is always reachable, so the slider can always represent what Reset produces. */
    @Test
    void resetFitLiesInsideTheRange() {
        for (int capeW : CAPE_WIDTHS) {
            for (int[] source : SOURCES) {
                double cover = CapeZoomRange.coverScale(capeW, source[0], source[1]);
                assertEquals(cover, CapeZoomRange.clampScale(cover, capeW, source[0], source[1]),
                        0.0, "cover fit must survive the clamp");
            }
        }
    }

    // --- clamping ---

    @Test
    void clampPinsBothEnds() {
        for (int capeW : CAPE_WIDTHS) {
            for (int[] source : SOURCES) {
                double min = CapeZoomRange.minScale(capeW, source[0], source[1]);
                double max = CapeZoomRange.maxScale(capeW, source[0], source[1]);
                assertEquals(min, CapeZoomRange.clampScale(0.0, capeW, source[0], source[1]), 0.0);
                assertEquals(min, CapeZoomRange.clampScale(-5.0, capeW, source[0], source[1]), 0.0);
                assertEquals(min, CapeZoomRange.clampScale(min / 1000.0, capeW,
                        source[0], source[1]), 0.0);
                assertEquals(max, CapeZoomRange.clampScale(max * 1000.0, capeW,
                        source[0], source[1]), 0.0);
                assertEquals(max, CapeZoomRange.clampScale(Double.POSITIVE_INFINITY, capeW,
                        source[0], source[1]), 0.0);
                assertEquals(min, CapeZoomRange.clampScale(Double.NaN, capeW,
                        source[0], source[1]), 0.0);
            }
        }
    }

    /** A value already inside the range must come back untouched, bit for bit. */
    @Test
    void clampLeavesInRangeScalesAlone() {
        double scale = CapeZoomRange.coverScale(64, 70, 40) * 1.37;
        assertEquals(scale, CapeZoomRange.clampScale(scale, 64, 70, 40), 0.0);
    }

    /** A zero-height frame would divide by zero; the range stays finite instead. */
    @Test
    void degenerateSourceStillYieldsAFiniteRange() {
        for (int[] degenerate : new int[][] {{0, 0}, {0, 32}, {64, 0}, {-4, -4}}) {
            double min = CapeZoomRange.minScale(64, degenerate[0], degenerate[1]);
            double max = CapeZoomRange.maxScale(64, degenerate[0], degenerate[1]);
            assertTrue(Double.isFinite(min) && min > 0.0, "min must stay finite and positive");
            assertTrue(Double.isFinite(max) && max > min, "max must stay finite and above min");
            assertTrue(Double.isFinite(CapeZoomRange.position(1.0, 64,
                    degenerate[0], degenerate[1])));
            assertTrue(Double.isFinite(CapeZoomRange.scaleAt(0.5, 64,
                    degenerate[0], degenerate[1])));
        }
    }

    // --- the mapping is an exact round trip ---

    /** position -> scale -> position must be a fixed point, which is what keeps the two views one. */
    @Test
    void positionRoundTripsThroughScale() {
        for (int capeW : CAPE_WIDTHS) {
            for (int[] source : SOURCES) {
                for (int step = 0; step <= 100; step++) {
                    double position = step / 100.0;
                    double scale = CapeZoomRange.scaleAt(position, capeW, source[0], source[1]);
                    double back = CapeZoomRange.position(scale, capeW, source[0], source[1]);
                    assertEquals(position, back, EPSILON,
                            "position " + position + " for " + capeW + " / "
                                    + source[0] + "x" + source[1]);
                }
            }
        }
    }

    /** And the other direction: scale -> position -> scale, for scales inside the range. */
    @Test
    void scaleRoundTripsThroughPosition() {
        for (int capeW : CAPE_WIDTHS) {
            for (int[] source : SOURCES) {
                double min = CapeZoomRange.minScale(capeW, source[0], source[1]);
                double max = CapeZoomRange.maxScale(capeW, source[0], source[1]);
                for (int step = 0; step <= 20; step++) {
                    double scale = min * Math.pow(max / min, step / 20.0);
                    double back = CapeZoomRange.scaleAt(
                            CapeZoomRange.position(scale, capeW, source[0], source[1]),
                            capeW, source[0], source[1]);
                    assertEquals(scale, back, scale * 1.0e-9,
                            "scale " + scale + " for " + capeW);
                }
            }
        }
    }

    @Test
    void endsOfTheTrackAreTheEndsOfTheRange() {
        for (int capeW : CAPE_WIDTHS) {
            for (int[] source : SOURCES) {
                assertEquals(CapeZoomRange.minScale(capeW, source[0], source[1]),
                        CapeZoomRange.scaleAt(0.0, capeW, source[0], source[1]), 0.0);
                assertEquals(CapeZoomRange.maxScale(capeW, source[0], source[1]),
                        CapeZoomRange.scaleAt(1.0, capeW, source[0], source[1]), 0.0);
                assertEquals(0.0, CapeZoomRange.position(
                        CapeZoomRange.minScale(capeW, source[0], source[1]),
                        capeW, source[0], source[1]), 0.0);
                assertEquals(1.0, CapeZoomRange.position(
                        CapeZoomRange.maxScale(capeW, source[0], source[1]),
                        capeW, source[0], source[1]), EPSILON);
            }
        }
    }

    /** Out-of-track positions and out-of-range scales saturate rather than escaping. */
    @Test
    void mappingSaturatesOutsideItsDomain() {
        double min = CapeZoomRange.minScale(128, 70, 40);
        double max = CapeZoomRange.maxScale(128, 70, 40);
        assertEquals(min, CapeZoomRange.scaleAt(-0.4, 128, 70, 40), 0.0);
        assertEquals(max, CapeZoomRange.scaleAt(9.0, 128, 70, 40), 0.0);
        assertEquals(min, CapeZoomRange.scaleAt(Double.NaN, 128, 70, 40), 0.0);
        assertEquals(0.0, CapeZoomRange.position(min / 100.0, 128, 70, 40), 0.0);
        assertEquals(1.0, CapeZoomRange.position(max * 100.0, 128, 70, 40), 0.0);
        assertEquals(0.0, CapeZoomRange.position(Double.NaN, 128, 70, 40), 0.0);
    }

    @Test
    void mappingIsMonotonic() {
        double previous = -1.0;
        for (int step = 0; step <= 200; step++) {
            double scale = CapeZoomRange.scaleAt(step / 200.0, 256, 70, 40);
            assertTrue(scale > previous, "scale must increase with slider position");
            previous = scale;
        }
    }

    // --- the range moves with the resolution, and the mapping absorbs it ---

    /**
     * The screen rescales the whole transform when the user picks a new resolution
     * ({@code imgScale *= newCapeW / oldCapeW}). Both bounds carry exactly one factor of
     * {@code capeW}, so that rescale must leave the slider position untouched. This is what lets
     * the slider be normalised over a range that moves.
     */
    @Test
    void sliderPositionSurvivesAResolutionChange() {
        for (int[] source : SOURCES) {
            for (int step = 0; step <= 10; step++) {
                double position = step / 10.0;
                double scale = CapeZoomRange.scaleAt(position, 64, source[0], source[1]);
                for (int capeW : CAPE_WIDTHS) {
                    double rescaled = scale * ((double) capeW / 64);
                    assertEquals(position,
                            CapeZoomRange.position(rescaled, capeW, source[0], source[1]),
                            EPSILON, "position must survive 64 -> " + capeW);
                }
            }
        }
    }

    /** And so must the number the label prints, so it does not jump while the handle stands still. */
    @Test
    void percentSurvivesAResolutionChange() {
        for (int[] source : SOURCES) {
            for (int step = 0; step <= 10; step++) {
                double scale = CapeZoomRange.scaleAt(step / 10.0, 64, source[0], source[1]);
                int expected = CapeZoomRange.percent(scale, 64, source[0], source[1]);
                for (int capeW : CAPE_WIDTHS) {
                    assertEquals(expected, CapeZoomRange.percent(
                            scale * ((double) capeW / 64), capeW, source[0], source[1]));
                }
            }
        }
    }

    /** The ratio between the ends is the same at every resolution — that is why the above holds. */
    @Test
    void spanIsResolutionIndependent() {
        for (int[] source : SOURCES) {
            double first = CapeZoomRange.span(64, source[0], source[1]);
            for (int capeW : CAPE_WIDTHS) {
                assertEquals(first, CapeZoomRange.span(capeW, source[0], source[1]),
                        first * 1.0e-12);
            }
            assertTrue(first >= 32.0 - 1.0e-9,
                    "the range always spans at least 32x (0.25x contain .. 8x cover)");
        }
    }

    /** A 2:1 source lands on exactly 32x, five octaves of travel. */
    @Test
    void aspectMatchedSourceSpansExactly32() {
        assertEquals(32.0, CapeZoomRange.span(64, 64, 32), 1.0e-12);
        assertEquals(32.0, CapeZoomRange.span(1024, 4096, 2048), 1.0e-12);
    }

    // --- the wheel and the slider share one metric ---

    /**
     * One wheel notch is a constant slider displacement. That is the property a linear mapping
     * would not have, and the reason the two inputs are two views of one value.
     */
    @Test
    void oneWheelNotchIsAConstantSliderStep() {
        for (int capeW : CAPE_WIDTHS) {
            for (int[] source : SOURCES) {
                double step = CapeZoomRange.wheelStepPosition(capeW, source[0], source[1]);
                assertTrue(step > 0.0 && step < 1.0, "a notch must be a fraction of the track");
                for (int start = 1; start <= 8; start++) {
                    double position = start / 10.0;
                    double scale = CapeZoomRange.scaleAt(position, capeW, source[0], source[1]);
                    double zoomed = CapeZoomRange.clampScale(scale * CapeZoomRange.WHEEL_STEP,
                            capeW, source[0], source[1]);
                    assertEquals(position + step,
                            CapeZoomRange.position(zoomed, capeW, source[0], source[1]),
                            EPSILON, "a notch must move the handle by the same amount everywhere");
                }
            }
        }
    }

    /** A 2:1 source needs the same number of notches end to end whatever its size. */
    @Test
    void wheelStepIsIdenticalForEveryAspectMatchedSource() {
        double small = CapeZoomRange.wheelStepPosition(64, 64, 32);
        double large = CapeZoomRange.wheelStepPosition(64, 4096, 2048);
        assertEquals(small, large, 1.0e-12);
        assertEquals(Math.log(1.15) / Math.log(32.0), small, 1.0e-12);
    }

    // --- the anchor rule ---

    /** The point at the anchor must not move, which is the whole definition of zooming toward it. */
    @Test
    void reanchoringHoldsTheAnchorPointStill() {
        for (double anchor : new double[] {0.0, 16.0, 32.0, 512.0, -5.0}) {
            for (double offset : new double[] {-40.0, -0.5, 0.0, 7.25, 200.0}) {
                for (double ratio : new double[] {0.25, 0.999, 1.0, 1.15, 4.0}) {
                    // An image point p sits at offset + p*scale. Track the point that is under the
                    // anchor before the change and check it is still under it afterwards.
                    double scale = 3.0;
                    double point = (anchor - offset) / scale;
                    double moved = CapeZoomRange.reanchorOffset(offset, anchor, ratio);
                    assertEquals(anchor, moved + point * scale * ratio, 1.0e-9);
                }
            }
        }
    }

    /** Zooming about a fixed anchor is path independent: many small steps == one big one. */
    @Test
    void reanchoringIsPathIndependent() {
        double anchor = 32.0;
        double start = -12.5;
        double total = 8.0;
        double direct = CapeZoomRange.reanchorOffset(start, anchor, total);
        for (int steps : new int[] {2, 7, 64, 1000}) {
            double stepRatio = Math.pow(total, 1.0 / steps);
            double chained = start;
            for (int i = 0; i < steps; i++) {
                chained = CapeZoomRange.reanchorOffset(chained, anchor, stepRatio);
            }
            assertEquals(direct, chained, 1.0e-6,
                    steps + " steps must land where one step does");
        }
    }

    /**
     * The regression this rule exists to prevent: rounding the offset between steps — as the
     * screen's 1px offset snap does — destroys path independence, so the screen must chain off the
     * unrounded value. A slider drag arrives as many small steps, and rounding each of them to zero
     * in turn leaves the offset frozen while the scale climbs.
     */
    @Test
    void roundingBetweenStepsWouldBreakPathIndependence() {
        double anchor = 32.0;
        double start = 0.0;
        double total = 8.0;
        int steps = 1000;
        double stepRatio = Math.pow(total, 1.0 / steps);

        double exact = start;
        double rounded = start;
        for (int i = 0; i < steps; i++) {
            exact = CapeZoomRange.reanchorOffset(exact, anchor, stepRatio);
            rounded = Math.round(CapeZoomRange.reanchorOffset(rounded, anchor, stepRatio));
        }
        double direct = CapeZoomRange.reanchorOffset(start, anchor, total);
        assertEquals(direct, exact, 1.0e-6, "chaining off the unrounded offset must stay exact");
        assertTrue(Math.abs(rounded - direct) > 100.0,
                "rounding between steps must visibly diverge, or this test is not guarding anything");
        assertEquals(start, rounded, 0.0, "the rounded chain does not move at all");
    }

    // --- the label ---

    /**
     * 100% is exactly what "Reset Position" leaves behind.
     *
     * <p>The reference scale is recomputed here from the screen's own reset formula rather than
     * from {@link CapeZoomRange#coverScale}, so this cannot pass by dividing a number by itself:
     * it fails if 100% ever stops meaning the reset fit.
     */
    @Test
    void resetFitReadsAsOneHundredPercent() {
        for (int capeW : CAPE_WIDTHS) {
            int capeH = capeW / 2;
            for (int[] source : SOURCES) {
                double reset = Math.max((double) capeW / source[0], (double) capeH / source[1]);
                assertEquals(100, CapeZoomRange.percent(reset, capeW, source[0], source[1]));
                assertEquals("100%",
                        CapeZoomRange.formatPercent(reset, capeW, source[0], source[1]));
                // And a scale that is NOT the reset fit must not read as 100%.
                assertNotEquals(100,
                        CapeZoomRange.percent(reset * 2.0, capeW, source[0], source[1]));
                assertNotEquals(100,
                        CapeZoomRange.percent(reset * 0.5, capeW, source[0], source[1]));
            }
        }
    }

    /**
     * A source whose aspect is wildly unlike the cape's bottoms out under half a percent. The label
     * must still say a small zoom rather than {@code 0%}, which would read as "not drawn at all".
     */
    @Test
    void extremeAspectStillReadsAboveZero() {
        // 100:1 banner into a 2:1 cape: contain/cover is 0.02, so the raw bottom is 0.5%.
        assertEquals("1%", CapeZoomRange.formatPercent(
                CapeZoomRange.minScale(64, 3200, 32), 64, 3200, 32));
        for (int capeW : CAPE_WIDTHS) {
            for (int[] source : SOURCES) {
                assertTrue(CapeZoomRange.percent(
                        CapeZoomRange.minScale(capeW, source[0], source[1]),
                        capeW, source[0], source[1]) >= 1, "percent must never print zero");
            }
        }
    }

    /** A degenerate atlas width must not collapse the range into a NaN-producing zero. */
    @Test
    void degenerateAtlasWidthStillYieldsAFiniteRange() {
        for (int capeW : new int[] {0, 1, 2, -64}) {
            double min = CapeZoomRange.minScale(capeW, 64, 32);
            double max = CapeZoomRange.maxScale(capeW, 64, 32);
            assertTrue(Double.isFinite(min) && min > 0.0, "min must stay finite and positive");
            assertTrue(Double.isFinite(max) && max > min, "max must stay finite and above min");
            for (int step = 0; step <= 4; step++) {
                double scale = CapeZoomRange.scaleAt(step / 4.0, capeW, 64, 32);
                assertTrue(Double.isFinite(scale) && scale > 0.0,
                        "scaleAt must stay finite for capeW=" + capeW);
                assertTrue(Double.isFinite(
                        CapeZoomRange.position(scale, capeW, 64, 32)));
            }
        }
    }

    @Test
    void bothEndsOfTheTrackReadSensibly() {
        // A 2:1 source uses the whole nominal range: a quarter of the fit up to eight times it.
        assertEquals("25%", CapeZoomRange.formatPercent(
                CapeZoomRange.minScale(256, 64, 32), 256, 64, 32));
        assertEquals("800%", CapeZoomRange.formatPercent(
                CapeZoomRange.maxScale(256, 64, 32), 256, 64, 32));
        // A source whose aspect differs from the cape's starts higher, because the bottom of the
        // range is a quarter of the CONTAIN fit while 100% is the COVER fit.
        assertEquals("22%", CapeZoomRange.formatPercent(
                CapeZoomRange.minScale(64, 70, 40), 64, 70, 40));
        assertEquals("800%", CapeZoomRange.formatPercent(
                CapeZoomRange.maxScale(64, 70, 40), 64, 70, 40));
    }

    @Test
    void percentTracksTheScaleMonotonically() {
        int previous = -1;
        for (int step = 0; step <= 100; step++) {
            int percent = CapeZoomRange.percent(
                    CapeZoomRange.scaleAt(step / 100.0, 128, 70, 40), 128, 70, 40);
            assertTrue(percent >= previous, "percent must not go backwards");
            previous = percent;
        }
        assertNotEquals(CapeZoomRange.percent(CapeZoomRange.minScale(128, 70, 40), 128, 70, 40),
                CapeZoomRange.percent(CapeZoomRange.maxScale(128, 70, 40), 128, 70, 40));
    }

    @Test
    void percentClampsWithTheScale() {
        assertEquals(CapeZoomRange.percent(CapeZoomRange.minScale(64, 64, 32), 64, 64, 32),
                CapeZoomRange.percent(0.0, 64, 64, 32));
        assertEquals(800, CapeZoomRange.percent(Double.POSITIVE_INFINITY, 64, 64, 32));
    }
}
