package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.client.rendering.CosmeticArmorPreview;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a Cosmetic Armor Reworked piece the local player hides through Quick Skin out of the
 * player's armour slots while that mod draws the player, so the worn piece shows in its place, as
 * the server has every other player see it (ADR 0017). That mod has no such state of its own: its
 * toggle hides the cosmetic piece and the worn one together.
 *
 * <p>The mod's render handler reads each slot's cosmetic item before it swaps it in; this
 * redirects that read to {@link CosmeticArmorPreview#drawnCosmetic}, which answers the same item
 * for every other player and slot. The target and its handlers are named by string, the slots are
 * an {@code Object} here, and the read itself goes through the mod's API, so nothing of the mod is
 * compiled against. It fails open: the handlers of the original (Forge 1.20.1, NeoForge 1.21.1
 * on render events, from 1.21.3 on the render state's extraction) and of its fork, on NeoForge,
 * are named; any that is renamed or changed is left alone ({@code require = 0}). The fork on
 * Fabric reads the slot with intermediary names, which the second hook names. Each runtime takes
 * one of the two hooks, so neither expects a count; the audited builds read a slot at most four
 * times (the fork: twice before drawing the player, once for each hand).
 */
@Pseudo
@Mixin(targets = "lain.mods.cos.impl.client.PlayerRenderHandler")
public abstract class CosmeticArmorRenderMixin {
    @Redirect(
            method = {
                    "handlePreRenderPlayer_High",
                    "handleRenderHand_High",
                    "handleRenderArm_High",
                    "onExtractPlayerRenderState",
                    "handlePreRenderPlayer",
                    "handleRenderHand",
                    "handleRenderArm"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Llain/mods/cos/impl/inventory/InventoryCosArmor;getStackInSlot(I)Lnet/minecraft/world/item/ItemStack;",
                    remap = false
            ),
            require = 0,
            expect = 0,
            allow = 4,
            remap = false
    )
    private ItemStack quickskin$cosmeticUnlessHidden(@Coerce Object stacks, int slot) {
        return CosmeticArmorPreview.drawnCosmetic(stacks, slot);
    }

    @Redirect(
            method = {"handlePreRenderPlayer", "handleRenderHand", "handleRenderArm"},
            at = @At(
                    value = "INVOKE",
                    target = "Llain/mods/cos/impl/inventory/InventoryCosArmor;getStackInSlot(I)Lnet/minecraft/class_1799;",
                    remap = false
            ),
            require = 0,
            expect = 0,
            allow = 4,
            remap = false
    )
    private ItemStack quickskin$cosmeticUnlessHiddenIntermediary(@Coerce Object stacks, int slot) {
        return CosmeticArmorPreview.drawnCosmetic(stacks, slot);
    }
}
