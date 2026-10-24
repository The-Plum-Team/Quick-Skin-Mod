package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The armour outline is the convex hull of a box's projected corners. */
class PreviewEquipmentHullTest {
    @Test
    void aSquareWithInnerPointsKeepsItsFourCorners() {
        float[] points = {0, 0, 2, 0, 2, 2, 0, 2, 1, 1, 1, 0.5F, 2, 1};
        float[] hull = PreviewEquipment.convexHull(points);
        assertEquals(8, hull.length);
        assertArrayEquals(new float[]{0, 0, 2, 0, 2, 2, 0, 2}, hull);
    }

    @Test
    void duplicatedCornersCollapse() {
        float[] points = {0, 0, 0, 0, 3, 0, 3, 0, 0, 3, 0, 3, 3, 3, 3, 3};
        assertEquals(8, PreviewEquipment.convexHull(points).length);
    }
}
