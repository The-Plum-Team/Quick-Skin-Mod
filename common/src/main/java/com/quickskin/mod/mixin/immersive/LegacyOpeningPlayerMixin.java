package com.quickskin.mod.mixin.immersive;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.6 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.LegacyOpeningRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if <1.21.2 {
import net.minecraft.world.entity.LivingEntity;
//?} else {
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
//?}
//?}

@Mixin(value = LivingEntityRenderer.class, priority = 2100)
public abstract class LegacyOpeningPlayerMixin {
    //? if <1.21.6 {
    @Inject(method =
            //? if <1.21.2 {
            "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            //?} else {
            "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            //?}
            at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$keepWholePlayer(
            //? if <1.21.2 {
            LivingEntity player, float yaw, float time,
            //?} else {
            LivingEntityRenderState player,
            //?}
            PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (LegacyOpeningRenderer.omit(player)) ci.cancel();
    }
    //?}
}
