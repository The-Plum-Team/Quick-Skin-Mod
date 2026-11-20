package com.quickskin.mod.mixin.armor;

//? if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//?} else {
import net.minecraft.client.renderer.entity.layers.WingsLayer;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Before 1.21.2: the local player's elytra layer finds no elytra when the player hides the chest
 * piece from everyone (ADR 0017). The layer is never cut short: it reads the chest slot as empty,
 * draws nothing and takes its own way out, so a hook another mod put at its start stays paired
 * with the one at its end (Touch'n Motion pushes a pose there and pops it; a call cut short
 * stopped the game with "Pose stack not empty"). From 1.21.2 the render state the wings layer
 * reads already lacks the elytra, and this does nothing.
 */
//? if <1.21.2 {
@Mixin(ElytraLayer.class)
//?} else {
@Mixin(WingsLayer.class)
//?}
public abstract class LocalElytraLayerMixin {
//? if <1.21.2 {
    @ModifyVariable(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("STORE"), ordinal = 0, require = 0, expect = 1, allow = 1)
    private ItemStack quickskin$wearNoHiddenElytra(ItemStack worn, PoseStack poseStack, MultiBufferSource buffers,
                                                   int light, LivingEntity entity, float limbSwing,
                                                   float limbSwingAmount, float partialTick, float ageInTicks,
                                                   float netHeadYaw, float headPitch) {
        if (WorldLook.dressesWithWings(entity)) return worn;
        return PlayerModelRenderer.hidesOwnPiece(entity, EquipmentSlot.CHEST) ? ItemStack.EMPTY : worn;
    }
//?}
}
