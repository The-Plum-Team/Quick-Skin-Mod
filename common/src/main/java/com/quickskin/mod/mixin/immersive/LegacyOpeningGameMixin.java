package com.quickskin.mod.mixin.immersive;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.6 {
import com.quickskin.mod.client.immersive.LegacyOpeningRenderer;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

@Mixin(GameRenderer.class)
public abstract class LegacyOpeningGameMixin {
    //? if <1.21.6 {
    @Inject(method = "render", at = @At("TAIL"), require = 0, expect = 1, allow = 1)
    private void quickskin$release(CallbackInfo ci) {
        LegacyOpeningRenderer.releaseAfterMenu();
    }

    // Run after Iris has finalized the normal camera but before the hand and the immersive GUI draw.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target =
            "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(" +
            //? if <1.20.5 {
            "Lcom/mojang/blaze3d/vertex/PoseStack;FJZ" +
            //?} else if <1.21 {
            "FJZ" +
            //?} else if <1.21.2 {
            "Lnet/minecraft/client/DeltaTracker;Z" +
            //?} else {
            "Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;Z" +
            //?}
            "Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;" +
            //? if <1.21.4 {
            "Lnet/minecraft/client/renderer/LightTexture;" +
            //?}
            //? if >=1.20.5 {
            "Lorg/joml/Matrix4f;" +
            //?}
            "Lorg/joml/Matrix4f;)V"), require = 0, expect = 1, allow = 1)
    private void quickskin$composite(CallbackInfo ci) {
        LegacyOpeningRenderer.finish();
    }
    //?}
}
