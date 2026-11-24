package com.quickskin.mod.client.gui.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeSkinButtonTest {

    @Test
    void theButtonGoesOnlyWhileAKeyOpensTheSkinMenu() {
        assertTrue(ChangeSkinButton.hidden(true, true));
        assertFalse(ChangeSkinButton.hidden(true, false), "nothing would open the skin menu");
        assertFalse(ChangeSkinButton.hidden(false, true));
        assertFalse(ChangeSkinButton.hidden(false, false));
    }
}
