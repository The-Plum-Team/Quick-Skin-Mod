package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge twin of {@code com.quickskin.mod.mixin.PreviewEquipmentMixin}; see that class for
 * why the preview has to answer equipment reads as empty and why this is a read override rather than
 * a write.
 *
 * <p>The duplicate exists because the loaders load different mixin configurations. NeoForge declares
 * only {@code quickskin-neoforge.mixins.json} in {@code neoforge.mods.toml}, so the common
 * {@code quickskin.mixins.json} - which is nonetheless packaged into the NeoForge jars - is never
 * read there and a mixin added only to it would silently not run. The same split already forces a
 * duplicate {@code CapeLayerMixin} in this package.
 *
 * <p>Registered through 1.21.5. The concrete reader moves from {@code Player} to
 * {@code LivingEntity} in 1.21.5; other living entities must keep their ordinary equipment reads.
 * From 1.21.6 the renderer blanks the extracted render state and this mixin is not applied.
 */
//? if <1.21.5 {
@Mixin(Player.class)
//?} else {
@Mixin(LivingEntity.class)
//?}
public class PreviewEquipmentMixin {

    @Inject(
            method = "getItemBySlot(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 1)
    private void quickskin$suppressPreviewEquipment(EquipmentSlot slot,
                                                    CallbackInfoReturnable<ItemStack> cir) {
        if (PlayerModelRenderer.suppressesPreviewEquipment(this, slot)) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
