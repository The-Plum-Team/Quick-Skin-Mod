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
        assertEquals(6, MenuGuiScale.forFramebuffer(3840, 2160)); // 640x360, not 548x308 at 7
        assertEquals(8, MenuGuiScale.forFramebuffer(5120, 2880)); // 640x360
        assertEquals(4, MenuGuiScale.forFramebuffer(2560, 1600)); // 640x400
        assertEquals(6, MenuGuiScale.forFramebuffer(3456, 2234)); // 576x372
    }

    @Test
    void neverPicksAScaleThatLeavesLessThanTheTargetCanvas() {
        // 1600x900 lies between 800x450 at 2 and 533x300 at 3, and 300 is nearer 360 by ratio
        assertEquals(3, MenuGuiScale.forFramebuffer(1600, 900));
        // 1440x900 would come nearer at 3, but its 480-unit canvas is too narrow
        assertEquals(2, MenuGuiScale.forFramebuffer(1440, 900));  // 720x450
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
