package com.quickskin.mod.client.gui.widget;

//? if <1.21.2 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} else if >=1.21.2 {
import net.minecraft.client.renderer.RenderType;
//?}
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/**
 * A square {@link ConfirmButton} that shows an icon from {@code textures/gui/icons} instead of a
 * label, such as the skin menu's Body width and Skin websites buttons. The icon is drawn at the
 * largest whole multiple of its pixels that fits inside the face's border, so it stays crisp, and
 * greyed out while the button is inactive. The label is still the button's message, for its
 * narration.
 *
 * <p>An icon is a white figure in a 16x15 area, which this button centres inside the face's
 * border: an even-sized button leaves that inside an even number of pixels wide and an odd number
 * tall, so a figure of even width and odd height centres exactly. Its dark drop shadow falls
 * outside the area, in the texture's extra right column and bottom row (17x16 in all), since the
 * eye centres the white figure and ignores the shadow.
 */
@Environment(EnvType.CLIENT)
public class IconButton extends ConfirmButton {
    /** Size of the area holding an icon's figure, which is centred on the face... */
    private static final int ICON_WIDTH = 16;
    private static final int ICON_HEIGHT = 15;
    /** ...and of the icon textures, with a column and a row more for the figure's shadow. */
    private static final int TEXTURE_WIDTH = ICON_WIDTH + 1;
    private static final int TEXTURE_HEIGHT = ICON_HEIGHT + 1;
    /** The icon's tint while the button is inactive. */
    private static final float DISABLED_SHADE = 0.55f;

    //? if <1.21.11 {
    private ResourceLocation icon;
    //?} else {
    private Identifier icon;
    //?}

    /** A button showing {@code textures/gui/icons/<iconName>.png}. */
    public IconButton(int x, int y, int size, String iconName, Component label, OnPress onPress) {
        super(x, y, size, size, label, onPress);
        setIcon(iconName);
    }

    /** Shows {@code textures/gui/icons/<iconName>.png}. */
    public void setIcon(String iconName) {
        String path = "textures/gui/icons/" + iconName + ".png";
        //? if <1.21 {
        this.icon = new ResourceLocation(QuickSkinInfo.MOD_ID, path);
        //?} else if <1.21.11 {
        this.icon = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, path);
        //?} else {
        this.icon = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, path);
        //?}
    }

    @Override
    //? if <26.1 {
    protected void renderFaceContents(GuiGraphics graphics, int faceTop, int faceBottom) {
    //?} else {
    protected void renderFaceContents(GuiGraphicsExtractor graphics, int faceTop, int faceBottom) {
    //?}
        // Inside the face's one-pixel border, within the button's one-pixel outline
        int innerLeft = this.getX() + 2;
        int innerTop = faceTop + 1;
        int innerWidth = this.width - 4;
        int innerHeight = faceBottom - faceTop - 2;
        int scale = Math.max(1, Math.min(innerWidth / ICON_WIDTH, innerHeight / ICON_HEIGHT));
        int x = innerLeft + (innerWidth - ICON_WIDTH * scale) / 2;
        int y = innerTop + (innerHeight - ICON_HEIGHT * scale) / 2;
        int textureWidth = TEXTURE_WIDTH * scale;
        int textureHeight = TEXTURE_HEIGHT * scale;
        float shade = this.active ? 1.0f : DISABLED_SHADE;
        //? if <1.21.2 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(shade, shade, shade, 1.0f);
        GuiCompat.blit(graphics, icon, x, y, textureWidth, textureHeight,
                0.0f, 0.0f, TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        //?} else {
        int channel = Math.round(shade * 255.0f);
        int color = 0xFF000000 | channel << 16 | channel << 8 | channel;
            //? if <1.21.6 {
        graphics.blit(RenderType::guiTextured, icon, x, y, 0.0f, 0.0f, textureWidth, textureHeight,
                TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT, color);
            //?} else {
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0.0f, 0.0f, textureWidth, textureHeight,
                TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT, color);
            //?}
        //?}
    }
}
