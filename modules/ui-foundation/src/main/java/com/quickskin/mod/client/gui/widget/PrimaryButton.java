package com.quickskin.mod.client.gui.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.Component;

/**
 * The main button of a screen, such as Done: a {@link StyledButton} in the green of the settings
 * toggles.
 */
@Environment(EnvType.CLIENT)
public class PrimaryButton extends StyledButton {
    public PrimaryButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress);
    }

    @Override
    protected int faceColor(boolean hovered) {
        return hovered ? 0xFF48A048 : 0xFF3C8A3C;
    }

    @Override
    protected int borderColor(boolean hovered) {
        return hovered ? 0xFF9AE09A : 0xFF6FCF6F;
    }

    @Override
    protected int lipColor() {
        return 0xFF1E4A1E;
    }
}
