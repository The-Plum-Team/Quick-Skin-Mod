package com.quickskin.mod.client.gui.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cape list's layout puts every tile where a click finds it, and the sticky heading follows the list. */
class CapeGridLayoutTest {
    private static final int CELL_W = 52;
    private static final int CELL_H = 68;
    private static final int HEADER = 14;
    private static final int GAP = 6;

    /** My Capes with None and three capes plus the import tile, then two built-in sections. */
    private static CapeGridLayout list() {
        return new CapeGridLayout(220, CELL_W, CELL_H, HEADER, GAP,
                new int[] {4, 16, 8}, new boolean[] {true, false, false}, new int[3]);
    }

    @Test
    void fourTilesFitARowAndSpreadToBothEdges() {
        CapeGridLayout layout = list();
        assertEquals(4, layout.columns());
        assertEquals(0, layout.cellX(0));
        assertEquals(220 - CELL_W, layout.cellX(3));
    }

    @Test
    void theImportTileStartsItsOwnRowAndStretchesAcrossIt() {
        CapeGridLayout layout = list();
        CapeGridLayout.Section mine = layout.sections().get(0);
        assertEquals(2, mine.rows(), "four capes fill a row, the import tile starts the next");
        assertEquals(220, layout.importWidth(4));
        int y = layout.cellY(0, 4) + 10;
        assertTrue(layout.hitTest(layout.slotX(4) + 5, y).importSlot());
        assertTrue(layout.hitTest(219, y).importSlot(), "the whole row is the import tile");
    }

    @Test
    void theImportTileFillsTheRestOfAPartRow() {
        CapeGridLayout layout = new CapeGridLayout(220, CELL_W, CELL_H, HEADER, GAP,
                new int[] {2}, new boolean[] {true}, new int[1]);
        assertEquals(220 - layout.slotX(2), layout.importWidth(2));
        int y = layout.cellY(0, 2) + 10;
        assertEquals(new CapeGridLayout.Hit(0, 1, false), layout.hitTest(layout.slotX(1) + 5, y));
        assertTrue(layout.hitTest(layout.slotX(3) + 5, y).importSlot());
    }

    @Test
    void everyTileIsFoundWhereItIsDrawn() {
        CapeGridLayout layout = list();
        for (int section = 0; section < layout.sections().size(); section++) {
            int count = layout.sections().get(section).count();
            for (int slot = 0; slot < count; slot++) {
                CapeGridLayout.Hit hit = layout.hitTest(layout.slotX(slot) + CELL_W / 2,
                        layout.cellY(section, slot) + CELL_H / 2);
                assertEquals(new CapeGridLayout.Hit(section, slot, false), hit);
            }
        }
    }

    @Test
    void headingsAndGapsHoldNoCape() {
        CapeGridLayout layout = list();
        CapeGridLayout.Section second = layout.sections().get(1);
        assertNull(layout.hitTest(10, second.top() + 2));
        assertNull(layout.hitTest(10, second.top() - 2));
        assertNull(layout.hitTest(-1, second.rowsTop() + 2));
    }

    @Test
    void sectionsFollowEachOtherWithoutOverlapping() {
        CapeGridLayout layout = list();
        int expected = 0;
        for (CapeGridLayout.Section s : layout.sections()) {
            assertEquals(expected, s.top());
            expected = s.rowsTop() + s.rows() * CELL_H + GAP;
        }
        assertEquals(expected - GAP, layout.contentHeight());
    }

    @Test
    void theSectionAtTheTopIsTheOneScrolledTo() {
        CapeGridLayout layout = list();
        int view = 240;
        assertEquals(0, layout.sectionAt(0, view));
        int second = layout.sections().get(1).top();
        assertEquals(1, layout.sectionAt(second, view));
        assertEquals(0, layout.sectionAt(second - 5, view));
    }

    @Test
    void aShortLastSectionIsMarkedOnceTheListReachesItsEnd() {
        CapeGridLayout layout = list();
        int view = 240;
        int maxScroll = layout.contentHeight() - view;
        assertEquals(2, layout.sectionAt(maxScroll, view));
    }

    @Test
    void anEmptySectionStillHasOneRow() {
        CapeGridLayout layout = new CapeGridLayout(220, CELL_W, CELL_H, HEADER, GAP,
                new int[] {0}, new boolean[] {false}, new int[1]);
        assertEquals(1, layout.sections().get(0).rows());
        assertNull(layout.hitTest(5, HEADER + 5));
    }

    @Test
    void aSpareRowLeavesAirUnderASectionAndHoldsNothing() {
        CapeGridLayout layout = new CapeGridLayout(220, CELL_W, CELL_H, HEADER, GAP,
                new int[] {2, 8}, new boolean[] {true, false}, new int[] {1, 0});
        CapeGridLayout.Section mine = layout.sections().get(0);
        assertEquals(2, mine.rows(), "the capes and import tile fill one row, the spare row is the second");
        assertEquals(mine.rowsTop() + 2 * CELL_H + GAP, layout.sections().get(1).top());
        assertNull(layout.hitTest(5, mine.rowsTop() + CELL_H + 10));
    }
}
