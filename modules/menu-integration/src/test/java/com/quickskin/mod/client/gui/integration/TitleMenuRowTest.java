package com.quickskin.mod.client.gui.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TitleMenuRowTest {

    private static final int SPACING = 4;
    // A 960-wide title screen: the Options/Quit Game row starts at width / 2 - 100.
    private static final int CENTER = 480;

    private static TitleMenuRow.Box quitAt(int y) {
        return new TitleMenuRow.Box(CENTER + 2, y, 98, 20);
    }

    private static TitleMenuRow.Box accessibilityAt(int y) {
        return new TitleMenuRow.Box(CENTER + 104, y, 20, 20);
    }

    @Test
    void theButtonFollowsTheAccessibilityButtonOnTheQuitRow() {
        TitleMenuRow.Box quit = quitAt(267);
        List<TitleMenuRow.Box> widgets = List.of(
                new TitleMenuRow.Box(CENTER - 100, 267, 98, 20),
                quit,
                accessibilityAt(267));

        // Vanilla's own layout: the slot that the old fixed position width / 2 + 128 named.
        assertEquals(CENTER + 128, TitleMenuRow.buttonX(quit, widgets, SPACING));
    }

    @Test
    void aShiftedRowKeepsTheSameSlot() {
        // NeoForge 1.21.1 puts the row at height / 4 + 138 instead of vanilla's + 132. The x
        // depends on the row's own widgets only; the caller takes the y from Quit Game.
        TitleMenuRow.Box quit = quitAt(273);
        List<TitleMenuRow.Box> widgets = List.of(
                new TitleMenuRow.Box(CENTER - 100, 249, 200, 20), // NeoForge's Mods button
                quit,
                accessibilityAt(273));

        assertEquals(CENTER + 128, TitleMenuRow.buttonX(quit, widgets, SPACING));
    }

    @Test
    void withoutAnAccessibilityButtonTheButtonFollowsQuitGame() {
        // Minecraft 26.2 moved accessibility into its own icon row.
        TitleMenuRow.Box quit = quitAt(279);
        List<TitleMenuRow.Box> widgets = List.of(quit, accessibilityAt(303));

        assertEquals(CENTER + 104, TitleMenuRow.buttonX(quit, widgets, SPACING));
    }

    @Test
    void everyIconThatContinuesTheRowIsSkipped() {
        TitleMenuRow.Box quit = quitAt(267);
        TitleMenuRow.Box modIcon = new TitleMenuRow.Box(CENTER + 128, 267, 20, 20);
        List<TitleMenuRow.Box> widgets = List.of(modIcon, quit, accessibilityAt(267));

        assertEquals(CENTER + 152, TitleMenuRow.buttonX(quit, widgets, SPACING));
    }

    @Test
    void widgetsAwayFromTheRowEndAreIgnored() {
        TitleMenuRow.Box quit = quitAt(267);
        List<TitleMenuRow.Box> widgets = List.of(
                quit,
                accessibilityAt(267),
                // Same row but further right, not touching the row end: left for the mod that owns it.
                new TitleMenuRow.Box(CENTER + 300, 267, 20, 20),
                // Right next to the row end but on another row or with another height.
                new TitleMenuRow.Box(CENTER + 128, 243, 20, 20),
                new TitleMenuRow.Box(CENTER + 128, 267, 20, 10));

        assertEquals(CENTER + 128, TitleMenuRow.buttonX(quit, widgets, SPACING));
    }

    @Test
    void overlappingWidgetsCannotLoop() {
        TitleMenuRow.Box quit = quitAt(267);
        TitleMenuRow.Box zeroWidth = new TitleMenuRow.Box(CENTER + 100, 267, 0, 20);
        List<TitleMenuRow.Box> widgets = List.of(quit, zeroWidth);

        assertEquals(CENTER + 104, TitleMenuRow.buttonX(quit, widgets, SPACING));
    }
}
