package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.client.gui.widget.DangerButton;
import com.quickskin.mod.client.gui.widget.PrimaryButton;
import com.quickskin.mod.client.gui.widget.StyledButton;
import com.quickskin.mod.client.gui.widget.TabButton;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Creates Quick Skin's text buttons, which share one style: grey ones for ordinary actions and a
 * green one for a screen's main action.
 */
@Environment(EnvType.CLIENT)
public class ButtonFactory {

    /** An ordinary action button. */
    public static Button createStyled(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        return new StyledButton(x, y, width, height, label, onPress);
    }

    /** A screen's main action, such as Done, in green. */
    public static Button createPrimary(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        return new PrimaryButton(x, y, width, height, label, onPress);
    }

    /** A destructive action, in red. */
    public static Button createDanger(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        return new DangerButton(x, y, width, height, label, onPress);
    }

    /** A tab of a tabbed screen. */
    public static Button createTab(int x, int y, int width, int height, Component label, boolean selected, Button.OnPress onPress) {
        return new TabButton(x, y, width, height, label, selected, onPress);
    }
}
