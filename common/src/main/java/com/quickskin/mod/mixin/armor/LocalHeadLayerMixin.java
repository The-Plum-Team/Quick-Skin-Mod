package com.quickskin.mod.mixin.armor;

import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
//? if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Before 1.21.2: the local player's worn-head layer finds no head, pumpkin or other item in the
 * head slot when the player hides the helmet piece from everyone (ADR 0017). The layer is never
 * cut short: it reads the slot as empty and takes its own way out, so hooks other mods pair at
 * its start and its end stay paired ({@link LocalElytraLayerMixin}). From 1.21.2 the render state
 * the layer reads already lacks it, and this does nothing.
 */
@Mixin(CustomHeadLayer.class)
public abstract class LocalHeadLayerMixin {
//? if <1.21.2 {
    @ModifyVariable(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("STORE"), ordinal = 0, require = 0, expect = 1, allow = 1)
    private ItemStack quickskin$wearNoHiddenHead(ItemStack worn, PoseStack poseStack, MultiBufferSource buffers,
                                                 int light, LivingEntity entity, float limbSwing,
                                                 float limbSwingAmount, float partialTick, float ageInTicks,
                                                 float netHeadYaw, float headPitch) {
        return PlayerModelRenderer.hidesOwnPiece(entity, EquipmentSlot.HEAD) ? ItemStack.EMPTY : worn;
    }
//?}
}
