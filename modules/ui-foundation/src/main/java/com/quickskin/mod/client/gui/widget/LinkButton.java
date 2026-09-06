package com.quickskin.mod.client.gui.widget;

//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.client.gui.GuiCompat;
//? if <1.21.11 {
import net.minecraft.Util;
//?} else {
import net.minecraft.util.Util;
//?}
//? if <26.1 {
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

public class LinkButton extends Button {

    //? if <1.21.11 {
    private final ResourceLocation texture;
    //?} else {
    private final Identifier texture;
    //?}
    private final int textureWidth;
    private final int textureHeight;

    //? if <1.21.11 {
    public LinkButton(int x, int y, int width, int height, ResourceLocation texture, String url, Component tooltip) {
    //?} else {
    public LinkButton(int x, int y, int width, int height, Identifier texture, String url, Component tooltip) {
    //?}
        super(x, y, width, height, Component.empty(), button -> {
            if (url != null) {
                openLink(url);
            }
        }, DEFAULT_NARRATION);

        this.texture = texture;

        // Assuming square textures for logos
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
        //? if <1.21.6 {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, this.alpha);
        //?}
    //?} else {
        //? if <26.1 {
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderDefaultSprite(graphics);
        //?} else {
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        extractDefaultSprite(graphics);
        //?}
    //?}

        // Draw the logo texture on top, inset slightly to fit within the rounded border.
        int padding = 2;
        GuiCompat.blit(graphics, this.texture,
                this.getX() + padding, this.getY() + padding,           // Screen position (x, y) with padding
                this.width - (padding * 2), this.height - (padding * 2), // Size on screen (width, height) reduced by padding
                0.0F, 0.0F,                                              // Texture UV start
                this.textureWidth, this.textureHeight,                   // Region in texture to draw (the whole image)
                this.textureWidth, this.textureHeight                    // Total texture size
        );
    }

    private static void openLink(String url) {
        // Open the link in the default browser
        Util.getPlatform().openUri(url);
    }

    @Override
    //? if <1.21.9 {
    protected boolean isValidClickButton(int button) {
    //?} else {
    protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
    //?}
        // Only allow left-click
        //? if <1.21.9 {
        return button == 0;
        //?} else {
        return buttonInfo.button() == 0;
        //?}
    }
}
