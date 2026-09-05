package com.quickskin.mod.client.gui.widget;

//? if <1.21.11 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.client.gui.GuiCompat;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

public class IconActionButton extends Button {

    //? if <1.21.11 {
    private final ResourceLocation texture;
    //?} else {
    private final Identifier texture;
    //?}
    private final int textureWidth;
    private final int textureHeight;

    //? if <1.21.11 {
    public IconActionButton(int x, int y, int width, int height, ResourceLocation texture, OnPress onPress, Component tooltip) {
    //?} else {
    public IconActionButton(int x, int y, int width, int height, Identifier texture, OnPress onPress, Component tooltip) {
    //?}
        super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
        this.texture = texture;
        this.textureWidth = 256;
        this.textureHeight = 256;
        this.setTooltip(Tooltip.create(tooltip));
    }

    @Override
    //? if <1.21.11 {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        //? if <1.21 {
        graphics.blitNineSliced(WIDGETS_LOCATION, this.getX(), this.getY(), this.getWidth(), this.getHeight(), 20, 4, 200, 20, 0, 46 + 20);
        //?} else {
        super.renderWidget(graphics, mouseX, mouseY, partialTicks);
        //?}
        //? if <1.21.5 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        //?}
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, this.alpha);
    //?} else {
        //? if <26.1.2 {
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderDefaultSprite(graphics);
        //?} else {
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        extractDefaultSprite(graphics);
        //?}
    //?}

        int padding = 2;
        GuiCompat.blit(graphics, this.texture,
                this.getX() + padding, this.getY() + padding,
                this.width - (padding * 2), this.height - (padding * 2),
                0.0F, 0.0F,
                this.textureWidth, this.textureHeight,
                this.textureWidth, this.textureHeight
        );
    }
}
