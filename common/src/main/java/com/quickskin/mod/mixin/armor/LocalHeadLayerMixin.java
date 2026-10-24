package com.quickskin.mod.mixin.armor;

import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
//? if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Before 1.21.2: the local player's worn-head layer skips a head, pumpkin or other item in the head
 * slot when the player hides the helmet piece from everyone (ADR 0017). From 1.21.2 the render
 * state the layer reads already lacks it, and this does nothing.
 */
@Mixin(CustomHeadLayer.class)
public abstract class LocalHeadLayerMixin {
//? if <1.21.2 {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$skipHiddenHead(PoseStack poseStack, MultiBufferSource buffers, int light,
                                          LivingEntity entity, float limbSwing, float limbSwingAmount,
                                          float partialTick, float ageInTicks, float netHeadYaw,
                                          float headPitch, CallbackInfo ci) {
        if (PlayerModelRenderer.hidesOwnPiece(entity, EquipmentSlot.HEAD)) {
            ci.cancel();
        }
    }
//?}
}
