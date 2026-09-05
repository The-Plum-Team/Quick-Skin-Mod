package com.quickskin.mod.client.gui.effect;

//? if <1.21.11 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <1.21.11 {
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
//?}

/**
 * Handles blur rendering - simplified to be called directly from the screen.
 *
 * In MC 1.21.11+, the PostChain API was reworked (constructor changed, resize/close/process
 * methods removed or changed signatures), so custom PostChain-based blur is not supported.
 * This handler gracefully no-ops to allow compilation and runtime without blur effects.
 */
@Environment(EnvType.CLIENT)
public class BlurHandler {
    //? if <1.21.11 {
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
     * No-op on 1.21.11+ due to PostChain API changes.
     */
    public static void renderBlur() {
        //? if <1.21.11 {
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
        //? if <1.21.11 {
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
     * No-op on 1.21.11+ since no shader is loaded.
     */
    public static void cleanup() {
        //? if <1.21.11 {
        if (blurShader != null) {
            blurShader.close();
            blurShader = null;
        }
        //?}
    }
}
