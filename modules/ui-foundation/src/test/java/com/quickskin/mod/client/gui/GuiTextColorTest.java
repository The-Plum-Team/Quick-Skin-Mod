package com.quickskin.mod.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiTextColorTest {
    @Test
    void makesRgbOpaqueForGuiTextRendering() {
        assertEquals(0xFFFFFFFF, GuiTextColor.opaqueRgb(0xFFFFFF));
        assertEquals(0xFF40A040, GuiTextColor.opaqueRgb(0x40A040));
        assertEquals(0xFF000000, GuiTextColor.opaqueRgb(0x000000));
    }

    @Test
    void ignoresAnyAlphaBitsInAnRgbInput() {
        assertEquals(0xFF55AAFF, GuiTextColor.opaqueRgb(0x7755AAFF));
    }
}
