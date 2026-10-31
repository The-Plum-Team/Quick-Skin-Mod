package com.quickskin.mod.client.gui.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the cape menu's list puts everything, in content coordinates (before scrolling): one list
 * of sections, each a heading over rows of tiles, a fixed number of tiles per row. Drawing, clicks
 * and the sticky heading all read this one layout, so what is drawn is always what is clicked.
 *
 * <p>A section holds {@code count} capes and, for My Capes, one extra tile at its end for the
 * import zone, which stretches to the end of its row. Positions are in GUI units relative to the
 * top-left corner of the list.
 */
public final class CapeGridLayout {
    /** What a point of the list holds. */
    public record Hit(int section, int index, boolean importSlot) {
    }

    /** One section's place in the list. */
    public record Section(int top, int rowsTop, int rows, int count, boolean importSlot) {
        /** Tiles in the section, the import slot included. */
        public int slots() {
            return count + (importSlot ? 1 : 0);
        }
    }

    private final int columns;
    private final int cellWidth;
    private final int cellHeight;
    private final int headerHeight;
    private final int sectionGap;
    private final int width;
    private final List<Section> sections = new ArrayList<>();
    private final int contentHeight;

    /**
     * @param width       the list's width; the tiles spread evenly across it
     * @param cellWidth   one tile's width
     * @param cellHeight  one row's height, from the top of a row to the top of the next
     * @param counts      the capes in each section, in order
     * @param importSlots whether each section ends with the import tile
     * @param spareRows   empty rows left after each section's tiles, for air
     */
    public CapeGridLayout(int width, int cellWidth, int cellHeight, int headerHeight, int sectionGap,
                          int[] counts, boolean[] importSlots, int[] spareRows) {
        if (counts.length != importSlots.length || counts.length != spareRows.length) {
            throw new IllegalArgumentException("one import flag and spare row count per section");
        }
        this.width = Math.max(cellWidth, width);
        this.cellWidth = Math.max(1, cellWidth);
        this.cellHeight = Math.max(1, cellHeight);
        this.headerHeight = headerHeight;
        this.sectionGap = sectionGap;
        this.columns = Math.max(1, this.width / this.cellWidth);
        int y = 0;
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) {
                y += sectionGap;
            }
            int slots = Math.max(0, counts[i]) + (importSlots[i] ? 1 : 0);
            int rows = Math.max(1, (slots + columns - 1) / columns) + Math.max(0, spareRows[i]);
            sections.add(new Section(y, y + headerHeight, rows, Math.max(0, counts[i]), importSlots[i]));
            y += headerHeight + rows * this.cellHeight;
        }
        this.contentHeight = y;
    }

    public int columns() {
        return columns;
    }

    public int cellWidth() {
        return cellWidth;
    }

    public int cellHeight() {
        return cellHeight;
    }

    public int contentHeight() {
        return contentHeight;
    }

    public List<Section> sections() {
        return sections;
    }

    /** The space between tiles, spread so the first and last touch the list's edges. */
    private double step() {
        return columns > 1 ? (width - cellWidth) / (double) (columns - 1) : 0;
    }

    /** The left edge of a tile in column {@code column}. */
    public int cellX(int column) {
        return (int) Math.round(column * step());
    }

    /** The top of the row a slot is in. */
    public int cellY(int section, int slot) {
        Section s = sections.get(section);
        return s.rowsTop() + (slot / columns) * cellHeight;
    }

    /** The left edge of the cell of a slot. */
    public int slotX(int slot) {
        return cellX(slot % columns);
    }

    /** How wide the import tile at {@code slot} is: from its cell to the end of the row. */
    public int importWidth(int slot) {
        return width - slotX(slot);
    }

    /** What lies at a point of the list, or null for a heading, the gaps or outside. */
    public Hit hitTest(int x, int y) {
        if (x < 0 || x >= width || y < 0) {
            return null;
        }
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            int bottom = s.rowsTop() + s.rows() * cellHeight;
            if (y < s.rowsTop() || y >= bottom) {
                continue;
            }
            int row = (y - s.rowsTop()) / cellHeight;
            for (int column = 0; column < columns; column++) {
                int left = cellX(column);
                if (x >= left && x < left + cellWidth) {
                    int slot = row * columns + column;
                    if (slot < s.count()) {
                        return new Hit(i, slot, false);
                    }
                    if (s.importSlot() && slot == s.count()) {
                        return new Hit(i, slot, true);
                    }
                    break;
                }
            }
            // The import tile reaches the end of its row, over the cells after it
            int importSlot = s.count();
            if (s.importSlot() && row == importSlot / columns && x >= cellX(importSlot % columns)) {
                return new Hit(i, importSlot, true);
            }
            return null;
        }
        return null;
    }

    /**
     * The section the list is showing at a scroll offset: the last one whose heading has reached
     * the top. At the very bottom of the list, the last section that starts on screen, so a short
     * last section can still be the one the sticky heading names.
     */
    public int sectionAt(double scroll, int viewHeight) {
        if (sections.isEmpty()) {
            return -1;
        }
        int maxScroll = Math.max(0, contentHeight - viewHeight);
        int current = 0;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).top() <= scroll + 1) {
                current = i;
            }
        }
        if (maxScroll > 0 && scroll >= maxScroll - 1) {
            for (int i = sections.size() - 1; i > current; i--) {
                if (sections.get(i).top() < scroll + viewHeight) {
                    return i;
                }
            }
        }
        return current;
    }
}
