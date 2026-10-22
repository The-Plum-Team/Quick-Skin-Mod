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
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/**
 * A square {@link ConfirmButton} that shows an icon from {@code textures/gui/icons} instead of a
 * label, such as the skin menu's Body width and Skin websites buttons. Like the skin column's
 * header buttons it stands on a lip of a single pixel, lighter than Confirm's. The icon is drawn at the
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
    /** The loading dots: their size, the gap between them, and how long each one is brightest. */
    private static final int DOT_SIZE = 2;
    private static final int DOT_GAP = 3;
    private static final long DOT_STEP_MILLIS = 250;
    private static final int DOT_BRIGHT = 0xFFFCFCFC;
    private static final int DOT_MID = 0xFFC8C8C8;
    private static final int DOT_DIM = 0xFF969696;
    private static final int DOT_SHADOW = 0xFF2A2A2A;

    /** Widest line of a tooltip, as wide as Minecraft wraps a widget's tooltip. */
    private static final int TOOLTIP_MAX_WIDTH = 170;
    /** How far a tooltip's frame reaches past its text on each side. */
    private static final int TOOLTIP_FRAME = 3;
    /** Room between the button and a tooltip drawn beside it. */
    private static final int TOOLTIP_GAP = 2;

    private boolean loading;
    /** A tooltip drawn left of the button, by {@link #renderTooltipOnLeft}, instead of the usual one. */
    private Component leftTooltip;

    //? if <1.21.11 {
    private ResourceLocation icon;
    //?} else {
    private Identifier icon;
    //?}

    /** The lip under the face: one pixel, like the skin column's header buttons. */
    private static final int LIP_HEIGHT = 1;

    /** A button showing {@code textures/gui/icons/<iconName>.png}. */
    public IconButton(int x, int y, int size, String iconName, Component label, OnPress onPress) {
        super(x, y, size, size, label, onPress);
        setIcon(iconName);
    }

    @Override
    protected int lipHeight() {
        return LIP_HEIGHT;
    }

    /**
     * Shows three dots instead of the icon while something is loading, such as the Search button
     * while it searches: the brightest moves from left to right, the one it left a little dimmer.
     */
    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    /**
     * Gives the button a tooltip that opens to its left, for buttons left of the skin menu's model,
     * where Minecraft's own one, to the right of the mouse, would cover the model. It replaces
     * any {@link #setTooltip} tooltip; the screen draws it with {@link #renderTooltipOnLeft}.
     */
    public void setTooltipOnLeft(Component tooltip) {
        this.leftTooltip = tooltip;
        this.setTooltip(null);
    }

    /**
     * Draws the tooltip of {@link #setTooltipOnLeft} while the mouse is over the button: at the
     * mouse's height, ending just left of the button and wrapped to the room there.
     */
    //? if <26.1 {
    public void renderTooltipOnLeft(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
    //?} else {
    public void renderTooltipOnLeft(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
    //?}
        if (leftTooltip == null || !this.visible || !this.isHovered()) {
            return;
        }
        // Minecraft keeps a tooltip's text at least 4 pixels from the screen's left edge
        int room = this.getX() - TOOLTIP_GAP - 2 * TOOLTIP_FRAME - 4;
        int wrap = Math.max(40, Math.min(TOOLTIP_MAX_WIDTH, room));
        java.util.List<Component> lines = new java.util.ArrayList<>();
        int width = 0;
        for (FormattedText line : font.getSplitter().splitLines(leftTooltip, wrap, Style.EMPTY)) {
            lines.add(Component.literal(line.getString()));
            width = Math.max(width, font.width(line));
        }
        // Minecraft draws a tooltip's text 12 pixels right of the point it is given
        int textRight = this.getX() - TOOLTIP_GAP - TOOLTIP_FRAME;
        GuiCompat.tooltip(graphics, font, lines, textRight - width - 12, mouseY);
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
        if (loading) {
            renderLoadingDots(graphics, innerLeft, innerTop, innerWidth, innerHeight);
            return;
        }
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

    //? if <26.1 {
    private void renderLoadingDots(GuiGraphics graphics, int left, int top, int width, int height) {
    //?} else {
    private void renderLoadingDots(GuiGraphicsExtractor graphics, int left, int top, int width, int height) {
    //?}
        int rowWidth = 3 * DOT_SIZE + 2 * DOT_GAP;
        int x = left + (width - rowWidth) / 2;
        int y = top + (height - DOT_SIZE) / 2;
        int bright = (int) ((System.currentTimeMillis() / DOT_STEP_MILLIS) % 3);
        for (int i = 0; i < 3; i++) {
            // A loading button is inactive while it works; the dots keep moving all the same
            int color = i == bright ? DOT_BRIGHT : i == (bright + 2) % 3 ? DOT_MID : DOT_DIM;
            int dotX = x + i * (DOT_SIZE + DOT_GAP);
            graphics.fill(dotX + 1, y + 1, dotX + DOT_SIZE + 1, y + DOT_SIZE + 1, DOT_SHADOW);
            graphics.fill(dotX, y, dotX + DOT_SIZE, y + DOT_SIZE, color);
        }
    }
}
