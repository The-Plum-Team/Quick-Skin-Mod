package com.quickskin.mod.mixin.armor;

import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
//? if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Before 1.21.2: the local player's armour layer skips the pieces the player hides from everyone
 * (ADR 0017). Vanilla, and Forge for 1.20.1, draw each piece through the six-argument
 * {@code renderArmorPiece}; NeoForge for 1.21.1 draws it through an overload that also takes the
 * animation values. Both are hooked, so either loader finds the one it calls. From 1.21.2 the
 * render state the layer reads already lacks the pieces, and this does nothing.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class LocalArmorLayerMixin {
//? if <1.21.2 {
    @Inject(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;)V",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 0, allow = 1)
    private void quickskin$skipHiddenPiece(PoseStack poseStack, MultiBufferSource buffers, LivingEntity entity,
                                           EquipmentSlot slot, int light, HumanoidModel<?> model,
                                           CallbackInfo ci) {
        if (PlayerModelRenderer.hidesOwnPiece(entity, slot)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 0, allow = 1)
    private void quickskin$skipHiddenPieceWithAnimation(PoseStack poseStack, MultiBufferSource buffers,
                                                        LivingEntity entity, EquipmentSlot slot, int light,
                                                        HumanoidModel<?> model, float limbSwing,
                                                        float limbSwingAmount, float partialTick,
                                                        float ageInTicks, float netHeadYaw, float headPitch,
                                                        CallbackInfo ci) {
        if (PlayerModelRenderer.hidesOwnPiece(entity, slot)) {
            ci.cancel();
        }
    }
//?}
}
