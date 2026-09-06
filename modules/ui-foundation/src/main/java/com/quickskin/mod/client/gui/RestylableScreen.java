package com.quickskin.mod.client.gui;

import net.minecraft.client.gui.screens.Screen;

/** A screen owns its reconstruction after global style settings change. */
public interface RestylableScreen {
    Screen recreateForStyleChange();
}
