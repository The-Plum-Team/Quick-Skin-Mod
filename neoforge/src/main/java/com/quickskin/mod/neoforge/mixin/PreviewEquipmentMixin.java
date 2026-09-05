package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 1.21.1 twin of {@code com.quickskin.mod.mixin.PreviewEquipmentMixin}; see that class for
 * why the preview has to answer equipment reads as empty and why this is a read override rather than
 * a write.
 *
 * <p>The duplicate exists because the loaders load different mixin configurations. NeoForge declares
 * only {@code quickskin-neoforge.mixins.json} in {@code neoforge.mods.toml}, so the common
 * {@code quickskin.mixins.json} - which is nonetheless packaged into the NeoForge jars - is never
 * read there and a mixin added only to it would silently not run. The same split already forces a
 * duplicate {@code CapeLayerMixin} in this package.
 *
 * <p>Registered only in the 1.21.1 configuration. NeoForge 1.21.11, 26.1.2 and 26.2 blank the
 * extracted render state in the renderer instead, so this class is compiled but never applied there.
 */
@Mixin(Player.class)
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
        if (PlayerModelRenderer.suppressesPreviewEquipment((Player) (Object) this, slot)) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
