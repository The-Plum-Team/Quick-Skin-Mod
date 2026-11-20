package com.quickskin.mod.mixin.armor;

import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
//? if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Before 1.21.2: the local player's armour layer finds no piece in a slot whose piece the player
 * hides from everyone (ADR 0017). The piece's draw is never cut short: it reads the slot as empty
 * and takes its own way out, so hooks other mods pair at its start and its end stay paired
 * ({@link LocalElytraLayerMixin}). Vanilla, and Forge for 1.20.1, draw each piece through the
 * six-argument {@code renderArmorPiece}; NeoForge for 1.21.1 draws it through an overload that
 * also takes the animation values. Both are hooked, so either loader finds the one it calls. From
 * 1.21.2 the render state the layer reads already lacks the pieces, and this does nothing.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class LocalArmorLayerMixin {
//? if <1.21.2 {
    @ModifyVariable(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;)V",
            at = @At("STORE"), ordinal = 0, require = 0, expect = 0, allow = 1)
    private ItemStack quickskin$wearNoHiddenPiece(ItemStack worn, PoseStack poseStack, MultiBufferSource buffers,
                                                  LivingEntity entity, EquipmentSlot slot, int light,
                                                  HumanoidModel<?> model) {
        return PlayerModelRenderer.hidesOwnPiece(entity, slot) ? ItemStack.EMPTY : worn;
    }

    @ModifyVariable(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
            at = @At("STORE"), ordinal = 0, require = 0, expect = 0, allow = 1)
    private ItemStack quickskin$wearNoHiddenPieceWithAnimation(ItemStack worn, PoseStack poseStack,
                                                               MultiBufferSource buffers, LivingEntity entity,
                                                               EquipmentSlot slot, int light, HumanoidModel<?> model,
                                                               float limbSwing, float limbSwingAmount,
                                                               float partialTick, float ageInTicks,
                                                               float netHeadYaw, float headPitch) {
        return PlayerModelRenderer.hidesOwnPiece(entity, slot) ? ItemStack.EMPTY : worn;
    }
//?}
}
