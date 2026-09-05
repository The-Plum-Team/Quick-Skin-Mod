package com.quickskin.mod.client.gui.effect;

//? if <1.21.2 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <1.21.2 {
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
//?}

/**
 * Handles blur rendering - simplified to be called directly from the screen.
 *
 * In MC 1.21.2+, the PostChain API no longer supports this legacy custom blur implementation.
 * The handler intentionally no-ops on those versions; screens still render their explicit modal
 * overlays and suppress vanilla's duplicate background blur.
 */
@Environment(EnvType.CLIENT)
public class BlurHandler {
    //? if <1.21.2 {
    private static PostChain blurShader = null;
        //? if <1.21 {
    private static final ResourceLocation BLUR_SHADER = new ResourceLocation("minecraft", "shaders/post/blur.json");
        //?} else {
    private static final ResourceLocation BLUR_SHADER = ResourceLocation.fromNamespaceAndPath("minecraft", "shaders/post/blur.json");
        //?}
    //?} else {

    private static boolean warned = false;
    //?}

    /**
     * Call this after rendering the background but before rendering UI.
     * No-op on 1.21.2+ due to PostChain API changes.
     */
    public static void renderBlur() {
        //? if <1.21.2 {
        Minecraft mc = Minecraft.getInstance();
        if (blurShader == null) {
            try {
                blurShader = new PostChain(mc.getTextureManager(), mc.getResourceManager(),
                    mc.getMainRenderTarget(), BLUR_SHADER);
                blurShader.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            } catch (Exception e) {
                return;
            }
        //?} else {
        if (!warned) {
            warned = true;
        //?}
        }
        //? if <1.21.2 {
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        blurShader.process(0.0f);
            //? if <1.21 {
        mc.getMainRenderTarget().bindWrite(false);
            //?} else {
        mc.getMainRenderTarget().bindWrite(true);
            //?}
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //?}
    }

    /**
     * Cleans up blur shader resources.
     * No-op on 1.21.2+ since no shader is loaded.
     */
    public static void cleanup() {
        //? if <1.21.2 {
        if (blurShader != null) {
            blurShader.close();
            blurShader = null;
        }
        //?}
    }
}
