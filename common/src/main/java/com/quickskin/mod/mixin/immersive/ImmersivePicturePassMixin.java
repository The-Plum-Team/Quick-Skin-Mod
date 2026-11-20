package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
//?} else if <1.21.9 {
//?} else if <26.1 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
//?}
//? if <1.21.6 {
import net.minecraft.client.Minecraft;
//?} else {
import net.minecraft.client.gui.render.GuiRenderer;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * From 1.21.6 the floating screen is drawn into its picture by an extra run of the game's own GUI
 * pass ({@code ImmersiveSurface.drawScreenPass}), so the pass runs twice a frame. At its end each
 * run lets go of the oversized-item renderers (the textures of items drawn larger than their slot)
 * that run did not use, so the frame's own run would close every one the screen used, to be made
 * again the next frame. The picture's run keeps them: the frame's run, which comes after it, then
 * lets go of those neither run used, as the game's single run would.
 *
 * <p>{@code GuiRenderer.clearUnusedOversizedItemRenderers}, private, called at the end of
 * {@code render}, the same in vanilla and NeoForge from 1.21.9 to 1.21.11. Only 1.21.9 to 1.21.11
 * hook it. Before 1.21.6 the GUI is drawn as it is extracted and there is nothing to keep: the
 * class then hooks nothing, on {@code Minecraft}. 1.21.6 to 1.21.8 and 26.1 on run the same extra
 * pass but do not hook it, so there the frame's run makes the screen's oversized-item renderers
 * again each frame; the class exists there, empty.
 */
//? if <1.21.6 {
@Mixin(Minecraft.class)
//?} else {
@Mixin(GuiRenderer.class)
//?}
public abstract class ImmersivePicturePassMixin {
//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
//?} else if <1.21.9 {
//?} else if <26.1 {
    @Inject(method = "clearUnusedOversizedItemRenderers", at = @At("HEAD"), cancellable = true,
            require = 0, expect = 1, allow = 1)
    private void quickskin$keepWhatThePictureUsed(CallbackInfo callback) {
        // The run into the picture is the one the target was swapped for
        RenderTarget picture = ImmersiveSurface.picture();
        if (picture != null && Minecraft.getInstance().getMainRenderTarget() == picture) {
            callback.cancel();
        }
    }
//?} else {
//?}
}
