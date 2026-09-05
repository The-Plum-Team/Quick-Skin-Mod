package com.quickskin.mod.client.gui.widget;

//? if <1.21.11 {
import com.mojang.blaze3d.vertex.PoseStack;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class RotateButton extends Button {

    public RotateButton(int x, int y, int size, OnPress onPress) {
        super(x, y, size, size, Component.literal("↺"), onPress, DEFAULT_NARRATION);
    }

    @Override
    //? if <1.21.11 {
    public void renderString(GuiGraphics pGuiGraphics, Font pFont, int pColor) {
    //?} else {
        //? if <26.1.2 {
    protected void renderContents(GuiGraphics pGuiGraphics, int mouseX, int mouseY, float partialTick) {
        renderDefaultSprite(pGuiGraphics);
        Font pFont = net.minecraft.client.Minecraft.getInstance().font;
        //?} else {
    protected void extractContents(GuiGraphicsExtractor pGuiGraphics, int mouseX, int mouseY, float partialTick) {
        extractDefaultSprite(pGuiGraphics);
        Font pFont = net.minecraft.client.Minecraft.getInstance().font;
        //?}
    //?}
        Component message = this.getMessage();
        //? if <1.21.11 {
        PoseStack poseStack = pGuiGraphics.pose();
        poseStack.pushPose();
        //?} else {
        // In 1.21.11+, graphics.pose() returns Matrix3x2fStack with 2D push/pop/translate/scale
        var pose = pGuiGraphics.pose();
        pose.pushMatrix();
        //?}

        float scale = 2.8F;
        float textWidth = pFont.width(message);

        // Translate to the center of the button to scale from that point
        //? if <1.21.11 {
        poseStack.translate(this.getX() + this.getWidth() / 1.8F, this.getY() + this.getHeight() / 4F, 0);
        poseStack.scale(scale, scale, 1.0F);
        //?} else {
        pose.translate(this.getX() + this.getWidth() / 1.8F, this.getY() + this.getHeight() / 4F);
        pose.scale(scale, scale);
        //?}

        // Draw the string centered on the new (0, 0) origin
        //? if <1.21.11 {
        pGuiGraphics.drawString(pFont, message, (int)(-textWidth / 2), (int)(-pFont.lineHeight / 2.0F + 1), pColor);
        //?} else {
            //? if <26.1.2 {
        pGuiGraphics.drawString(pFont, message, (int)(-textWidth / 2), (int)(-pFont.lineHeight / 2.0F + 1), 0xFFFFFFFF);
            //?} else {
        pGuiGraphics.text(pFont, message, (int)(-textWidth / 2), (int)(-pFont.lineHeight / 2.0F + 1), 0xFFFFFFFF);
            //?}
        //?}

        //? if <1.21.11 {
        poseStack.popPose();
        //?} else {
        pose.popMatrix();
        //?}
    }

    @Override
    //? if <1.21.11 {
    protected boolean isValidClickButton(int button) {
    //?} else {
    protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
    //?}
        // Only allow left-click
        //? if <1.21.11 {
        return button == 0;
        //?} else {
        return buttonInfo.button() == 0;
        //?}
    }
}
