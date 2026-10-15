package com.quickskin.mod.client.gui.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuGuiScaleTest {

    @Test
    void commonWindowsGetACanvasOfAboutTheSameSize() {
        assertEquals(3, MenuGuiScale.forFramebuffer(1920, 1080)); // 640x360
        assertEquals(3, MenuGuiScale.forFramebuffer(1918, 1010)); // maximised window, 639x336
        assertEquals(2, MenuGuiScale.forFramebuffer(1280, 720));  // 640x360
        assertEquals(2, MenuGuiScale.forFramebuffer(1366, 768));  // 683x384
        assertEquals(4, MenuGuiScale.forFramebuffer(2560, 1440)); // 640x360
        assertEquals(7, MenuGuiScale.forFramebuffer(3840, 2160)); // 548x308
    }

    @Test
    void smallWindowsKeepReadableText() {
        // Too small for the target canvas at scale 2, but scale 1 would make the text tiny
        assertEquals(2, MenuGuiScale.forFramebuffer(856, 516));
        assertEquals(2, MenuGuiScale.forFramebuffer(854, 480));
    }

    @Test
    void neverExceedsWhatMinecraftAllows() {
        assertEquals(1, MenuGuiScale.forFramebuffer(600, 400));
        assertEquals(1, MenuGuiScale.forFramebuffer(320, 240));
    }
}
