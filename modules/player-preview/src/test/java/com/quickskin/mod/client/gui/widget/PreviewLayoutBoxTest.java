package com.quickskin.mod.client.gui.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewLayoutBoxTest {

    /** The preview's scale formula before layout boxes existed, written out independently. */
    private static float legacyScale(int percentage) {
        float percentageAsFloat = (percentage - 1) / 99.0f;
        return 20.0f + percentageAsFloat * (200.0f - 20.0f);
    }

    @Test
    void scaleForPercentageIsTheConfiguredScaleFormula() {
        for (int percentage = 1; percentage <= 100; percentage++) {
            assertEquals(Float.floatToIntBits(legacyScale(percentage)),
                    Float.floatToIntBits(PreviewLayoutBox.scaleForPercentage(percentage)),
                    "percentage " + percentage);
        }
        assertEquals(20.0f, PreviewLayoutBox.scaleForPercentage(1));
        assertEquals(200.0f, PreviewLayoutBox.scaleForPercentage(100));
        assertEquals(72.727f, PreviewLayoutBox.scaleForPercentage(30), 0.001f);
        assertEquals(76.364f, PreviewLayoutBox.scaleForPercentage(32), 0.001f);
    }

    /** The scroll-wheel resize's scale-to-percentage formula before it moved here, written out independently. */
    private static int legacyPercentage(float scale) {
        return Math.round(((scale - 20.0f) / (200.0f - 20.0f)) * 99.0f) + 1;
    }

    @Test
    void percentageForScaleIsTheScrollResizeFormulaAndInvertsScaleForPercentage() {
        for (int percentage = 1; percentage <= 100; percentage++) {
            float scale = PreviewLayoutBox.scaleForPercentage(percentage);
            assertEquals(percentage, PreviewLayoutBox.percentageForScale(scale), "percentage " + percentage);
        }
        for (float scale = 20.0f; scale <= 200.0f; scale += 0.37f) {
            assertEquals(legacyPercentage(scale), PreviewLayoutBox.percentageForScale(scale), "scale " + scale);
        }
        assertEquals(20.0f, PreviewLayoutBox.clampToConfiguredRange(5.0f));
        assertEquals(200.0f, PreviewLayoutBox.clampToConfiguredRange(250.0f));
        assertEquals(87.2f, PreviewLayoutBox.clampToConfiguredRange(87.2f));
    }

    @Test
    void naturalBoxUsesTheDebugBorderArithmetic() {
        float scale = PreviewLayoutBox.scaleForPercentage(30);
        int height = (int) (scale * 2.0f);
        int width = (int) (height * 0.6f);

        PreviewLayoutBox.Box box = PreviewLayoutBox.natural(580, 236, scale);

        assertEquals(new PreviewLayoutBox.Box(580 - width / 2, 236 - height, width, height), box);
        // Title screen at 1600x900, GUI scale 2, default settings.
        assertEquals(new PreviewLayoutBox.Box(537, 91, 87, 145), box);
    }

    @Test
    void anUntouchedBoxGivesBackTheExactPlacement() {
        int[] centres = {-301, -1, 0, 1, 580, 1001};
        int[] feet = {-50, 236};
        for (int percentage = 1; percentage <= 100; percentage++) {
            float scale = PreviewLayoutBox.scaleForPercentage(percentage);
            for (int centerX : centres) {
                for (int feetY : feet) {
                    PreviewLayoutBox.Box box = PreviewLayoutBox.natural(centerX, feetY, scale);
                    PreviewLayoutBox.Placement placement = PreviewLayoutBox.place(
                            box, scale, box.x(), box.y(), box.width(), box.height());
                    assertEquals(new PreviewLayoutBox.Placement(centerX, feetY, scale), placement,
                            "percentage " + percentage + " at " + centerX + "," + feetY);
                }
            }
        }
    }

    @Test
    void aMovedBoxKeepsTheScaleAndShiftsTheModel() {
        float scale = PreviewLayoutBox.scaleForPercentage(32);
        PreviewLayoutBox.Box box = PreviewLayoutBox.natural(555, 229, scale);

        PreviewLayoutBox.Placement placement = PreviewLayoutBox.place(
                box, scale, box.x() - 486, box.y() + 63, box.width(), box.height());

        assertEquals(new PreviewLayoutBox.Placement(555 - 486, 229 + 63, scale), placement);
    }

    @Test
    void aResizedBoxScalesTheModelAndKeepsItStandingOnTheBottomEdge() {
        float scale = PreviewLayoutBox.scaleForPercentage(30);
        PreviewLayoutBox.Box box = PreviewLayoutBox.natural(580, 236, scale);

        PreviewLayoutBox.Placement half = PreviewLayoutBox.place(box, scale, 559, 164, 43, 72);
        assertEquals(559 + 43 / 2, half.centerX());
        assertEquals(164 + 72, half.feetY());
        assertEquals(scale / 2.0f, half.scale(), 1.0f);

        PreviewLayoutBox.Placement large = PreviewLayoutBox.place(box, scale, 515, 19, 130, 217);
        assertEquals(515 + 130 / 2, large.centerX());
        assertEquals(19 + 217, large.feetY());
        assertEquals(scale * 1.5f, large.scale(), 1.0f);
    }

    @Test
    void theLimitingSideDecidesAndTheModelNeverOverflowsTheBox() {
        float scale = PreviewLayoutBox.scaleForPercentage(30);
        PreviewLayoutBox.Box box = PreviewLayoutBox.natural(580, 236, scale);

        PreviewLayoutBox.Placement wide = PreviewLayoutBox.place(box, scale, 0, 0, 400, 100);
        assertEquals(50.0f, wide.scale(), 0.001f);
        PreviewLayoutBox.Placement tall = PreviewLayoutBox.place(box, scale, 0, 0, 60, 400);
        assertEquals(50.0f, tall.scale(), 0.001f);

        int[][] sizes = {{400, 100}, {60, 400}, {87, 144}, {86, 145}, {10, 300}, {333, 41}};
        for (int[] size : sizes) {
            PreviewLayoutBox.Placement placement = PreviewLayoutBox.place(box, scale, 10, 20, size[0], size[1]);
            PreviewLayoutBox.Box drawn = PreviewLayoutBox.natural(
                    placement.centerX(), placement.feetY(), placement.scale());
            assertTrue(drawn.width() <= size[0] && drawn.height() <= size[1],
                    "drawn " + drawn + " inside " + size[0] + "x" + size[1]);
            assertTrue(drawn.x() >= 10 && drawn.y() >= 20, "drawn " + drawn + " starts inside the box");
            assertEquals(20 + size[1], drawn.y() + drawn.height());
        }
    }

    @Test
    void aMissingSizeFallsBackToTheNaturalSizeAndExtremesAreClamped() {
        float scale = PreviewLayoutBox.scaleForPercentage(30);
        PreviewLayoutBox.Box box = PreviewLayoutBox.natural(580, 236, scale);

        PreviewLayoutBox.Placement noWidth = PreviewLayoutBox.place(box, scale, 40, 50, 0, 300);
        assertEquals(new PreviewLayoutBox.Placement(40 + box.width() / 2, 50 + box.height(), scale), noWidth);
        PreviewLayoutBox.Placement negative = PreviewLayoutBox.place(box, scale, 40, 50, 120, -1);
        assertEquals(new PreviewLayoutBox.Placement(40 + box.width() / 2, 50 + box.height(), scale), negative);

        assertEquals(PreviewLayoutBox.MIN_LAYOUT_SCALE, PreviewLayoutBox.place(box, scale, 0, 0, 1, 1).scale());
        assertEquals(PreviewLayoutBox.MAX_LAYOUT_SCALE,
                PreviewLayoutBox.place(box, scale, 0, 0, 100000, 100000).scale());
    }
}
