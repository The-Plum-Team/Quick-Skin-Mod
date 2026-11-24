package com.quickskin.mod.client.gui.integration;

/**
 * Whether the Change Skin button of the title screen and the pause menu is left out.
 *
 * <p>The button and the skin menu's key are the only ways into the skin menu, and the settings
 * are reached from that menu. So the button goes only while a key is assigned: a player who hid
 * it and then lost the key gets the button back, never a menu nothing opens.
 */
final class ChangeSkinButton {

    private ChangeSkinButton() {
    }

    static boolean hidden(boolean hiddenInSettings, boolean menuKeyAssigned) {
        return hiddenInSettings && menuKeyAssigned;
    }
}
