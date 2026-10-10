package com.quickskin.mod.client.gui.integration;

import java.util.List;

/**
 * Places Quick Skin's title-screen button at the end of the row that holds Quit Game.
 *
 * <p>Loaders and mods move that row: NeoForge inserts its Mods button above it and shifts it down,
 * so a position computed from vanilla's layout leaves the button beside the row instead of on it.
 * The row is therefore read from the Quit Game button itself. Icon buttons that continue the row
 * directly to its right (vanilla's accessibility button before 26.2) are skipped, so the Quick
 * Skin button follows the last of them.
 *
 * <p>Deliberately free of Minecraft types, so the placement rule is unit testable.
 */
final class TitleMenuRow {

    /** A widget's bounds in GUI coordinates. */
    record Box(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }
    }

    private TitleMenuRow() {
    }

    /**
     * The x of the first free slot on the Quit Game row.
     *
     * @param quit the Quit Game button
     * @param widgets every widget on the screen, Quit Game included
     * @param spacing the gap between adjacent buttons
     */
    static int buttonX(Box quit, List<Box> widgets, int spacing) {
        int end = quit.right();
        // Each pass moves past one adjacent widget; the bound stops overlapping widgets cycling.
        for (int pass = 0; pass < widgets.size(); pass++) {
            Box next = null;
            for (Box widget : widgets) {
                if (widget.y() == quit.y() && widget.height() == quit.height()
                        && widget.x() >= end && widget.x() <= end + spacing
                        && (next == null || widget.x() < next.x())) {
                    next = widget;
                }
            }
            if (next == null) {
                break;
            }
            end = next.right();
        }
        return end + spacing;
    }
}
