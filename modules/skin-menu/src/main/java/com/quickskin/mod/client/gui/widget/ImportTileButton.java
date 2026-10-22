package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The skin column's import zone, under the last skin. It opens the skin file dialog; dropping
 * files anywhere on the menu imports them too.
 *
 * <p>It fills the column's free space and is drawn by {@link ImportZone}: "Drag skin files here"
 * and "or click to import" under a tray icon, or a bar with a plus where there is too little room.
 */
@Environment(EnvType.CLIENT)
public class ImportTileButton extends Button {
    public ImportTileButton(int x, int y, int width, int height, OnPress onPress) {
        super(x, y, width, height, Component.translatable("quickskin.button.import_skin"), onPress, DEFAULT_NARRATION);
    }

    /** Moves the zone's top edge and sets its height, as the free space under the skins changes. */
    public void setBounds(int y, int height) {
        setY(y);
        this.height = Math.max(1, height);
    }

    @Override
    //? if <1.21.11 {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //?} else {
        //? if <26.1 {
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        //?} else {
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        //?}
    //?}
        ImportZone.draw(graphics, Minecraft.getInstance().font, this.getX(), this.getY(), this.width, this.height,
                Component.translatable("quickskin.dropzone.skins.main"),
                Component.translatable("quickskin.dropzone.skins.click"), this.isHovered() && this.active);
    }

    @Override
    //? if <1.21.9 {
    protected boolean isValidClickButton(int button) {
        return button == InputConstants.MOUSE_BUTTON_LEFT;
    //?} else {
    protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
        return buttonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT;
    //?}
    }
}
