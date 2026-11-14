package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerWidgetContextTest {

    @Test
    void aCapePreviewKeepsThePlayersBackToTheScreen() {
        assertFalse(WidgetContext.CAPE_MENU.turnsWhenDragged());
    }

    @Test
    void everyOtherPreviewTurnsWhenItsModelIsDragged() {
        for (WidgetContext context : WidgetContext.values()) {
            if (context != WidgetContext.CAPE_MENU) {
                assertTrue(context.turnsWhenDragged(), context.name());
            }
        }
    }
}
