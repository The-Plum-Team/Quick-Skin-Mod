package com.quickskin.mod.client.gui.effect;

//? if <1.21 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.client.gui.GuiCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.screens.Screen;
//? if <1.21 {
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
//?}

/**
 * Blurs whatever was drawn behind a Quick Skin menu or dialog with Minecraft's own blur, as the
 * Blur mod by Motschen does for vanilla screens (https://github.com/Motschen/Blur, MIT).
 *
 * <ul>
 *   <li>1.20.1 has no menu blur, so a fixed-radius {@code blur} post chain runs immediately.</li>
 *   <li>1.21.1 to 1.21.5 run the game renderer's menu blur immediately.</li>
 *   <li>From 1.21.6 the GUI blurs every earlier stratum once per frame, after extraction.</li>
 * </ul>
 *
 * From 1.21 the radius is Minecraft's Menu Background Blur option; at 0 no blur is drawn.
 */
@Environment(EnvType.CLIENT)
public class BlurHandler {
    /** Depth of dialogs currently drawing their parent screen, whose own blur is skipped. */
    private static int parentDepth;

    //? if <1.21 {
    private static final ResourceLocation BLUR_SHADER = new ResourceLocation("minecraft", "shaders/post/blur.json");
    private static PostChain blurShader;
    private static int blurWidth;
    private static int blurHeight;
    //?}

    /**
     * Blurs everything drawn so far behind a menu. Skipped while a dialog draws this menu as its
     * parent, so the dialog's own blur covers the menu as well.
     */
    //? if <26.1 {
    public static void blurBehindMenu(GuiGraphics graphics) {
    //?} else {
    public static void blurBehindMenu(GuiGraphicsExtractor graphics) {
    //?}
        if (parentDepth == 0) {
            blur(graphics);
        }
    }

    /** Blurs everything drawn so far behind a dialog, including the parent screen. */
    //? if <26.1 {
    public static void renderBlur(GuiGraphics graphics) {
    //?} else {
    public static void renderBlur(GuiGraphicsExtractor graphics) {
    //?}
        blur(graphics);
    }

    /** Draws a dialog's parent screen without the parent's own background blur. */
    //? if <26.1 {
    public static void renderParent(Screen parent, GuiGraphics graphics, float partialTick) {
    //?} else {
    public static void renderParent(Screen parent, GuiGraphicsExtractor graphics, float partialTick) {
    //?}
        parentDepth++;
        try {
            //? if <26.1 {
            GuiCompat.renderParent(parent, graphics, partialTick);
            //?} else {
            GuiCompat.extractParent(parent, graphics, partialTick);
            //?}
        } finally {
            parentDepth--;
        }
    }

    //? if <26.1 {
    private static void blur(GuiGraphics graphics) {
    //?} else {
    private static void blur(GuiGraphicsExtractor graphics) {
    //?}
        //? if <1.21.6 {
        Minecraft mc = Minecraft.getInstance();
        //?}
        //? if <1.21 {
        if (!ensureBlurShader(mc)) {
            return;
        }
        graphics.flush();
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        blurShader.process(0.0F);
        mc.getMainRenderTarget().bindWrite(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //?} else if <1.21.2 {
        // As vanilla's Screen.renderBlurredBackground does.
        graphics.flush();
        mc.gameRenderer.processBlurEffect(mc.getTimer().getGameTimeDeltaTicks());
        mc.getMainRenderTarget().bindWrite(false);
        //?} else if <1.21.6 {
        graphics.flush();
        mc.gameRenderer.processBlurEffect();
        //?} else {
        // Start a stratum so everything already drawn, including the panorama, is behind the blur.
        graphics.nextStratum();
        try {
            graphics.blurBeforeThisStratum();
        } catch (IllegalStateException alreadyBlurred) {
            // A screen drawn earlier this frame, such as a vanilla parent, already used the one
            // blur Minecraft allows per frame; this dialog keeps that blur.
        }
        //?}
    }

    //? if <1.21 {
    private static boolean ensureBlurShader(Minecraft mc) {
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        if (blurShader == null) {
            try {
                blurShader = new PostChain(mc.getTextureManager(), mc.getResourceManager(),
                    mc.getMainRenderTarget(), BLUR_SHADER);
            } catch (Exception e) {
                return false;
            }
            blurWidth = -1;
        }
        if (width != blurWidth || height != blurHeight) {
            blurShader.resize(width, height);
            blurWidth = width;
            blurHeight = height;
        }
        return true;
    }
    //?}

    /** Releases the post chain when a screen closes. */
    public static void cleanup() {
        //? if <1.21 {
        if (blurShader != null) {
            blurShader.close();
            blurShader = null;
        }
        //?}
    }
}
