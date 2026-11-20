package com.quickskin.mod.mixin.immersive;

//? if >=1.21 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
//? if <1.21.11 {
import net.minecraft.Util;
//?} else {
import net.minecraft.util.Util;
//?}
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else if <1.21.9 {
//?} else if <26.1 {
//?} else {
//?}
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The immersive view goes by the time the game took at the start of each frame, before its ticks
 * (see {@code ImmersiveMode}). 1.20.1 hands that time to {@code GameRenderer.render}, so this does
 * nothing there. From 1.21 the game reads the clock for the frame only after the ticks, so the
 * start of {@code Minecraft.runTick} tells {@code ImmersiveMode.frameStarted} instead, which
 * {@code ImmersiveMode.beginFrame(float)} reads. Should the hook fail, the view goes by a clock read
 * later in the frame, which the length of a tick moves. On 1.21.2 to 1.21.5 it also answers the
 * floating picture as the game's main target while the screen draws into it (below).
 */
@Mixin(Minecraft.class)
public abstract class ImmersiveMinecraftMixin {
//? if >=1.21 {
    @Inject(method = "runTick(Z)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$frameStarts(boolean renderLevel, CallbackInfo callback) {
        ImmersiveMode.frameStarted(Util.getNanos());
    }
//?}
//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
    /**
     * From 1.21.2 to 1.21.5 each batch the GUI draws goes to whatever this answers at that
     * moment, so while the open screen is drawn into its floating picture the picture is answered
     * instead ({@code ImmersiveSurface.drawingInto}). Should the hook fail, the screen is drawn
     * flat.
     */
    @Inject(method = "getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;", at = @At("HEAD"),
            cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$drawIntoFloatingPicture(CallbackInfoReturnable<RenderTarget> callback) {
        RenderTarget picture = ImmersiveSurface.drawingInto();
        if (picture != null) {
            callback.setReturnValue(picture);
        }
    }
//?} else if <1.21.9 {
//?} else if <26.1 {
//?} else {
//?}
}
