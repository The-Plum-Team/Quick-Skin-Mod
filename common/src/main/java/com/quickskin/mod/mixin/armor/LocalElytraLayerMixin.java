package com.quickskin.mod.mixin.armor;

//? if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import net.minecraft.client.renderer.entity.layers.WingsLayer;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Before 1.21.2: the local player's elytra layer skips an elytra the player hides from everyone
 * with the chest piece (ADR 0017). From 1.21.2 the render state the wings layer reads already
 * lacks it, and this does nothing.
 */
//? if <1.21.2 {
@Mixin(ElytraLayer.class)
//?} else {
@Mixin(WingsLayer.class)
//?}
public abstract class LocalElytraLayerMixin {
//? if <1.21.2 {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$skipHiddenElytra(PoseStack poseStack, MultiBufferSource buffers, int light,
                                            LivingEntity entity, float limbSwing, float limbSwingAmount,
                                            float partialTick, float ageInTicks, float netHeadYaw,
                                            float headPitch, CallbackInfo ci) {
        if (PlayerModelRenderer.hidesOwnPiece(entity, EquipmentSlot.CHEST)) {
            ci.cancel();
        }
    }
//?}
}
