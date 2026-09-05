package com.quickskin.mod.mixin;

import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers the previewed player's equipment as empty for the length of a GUI preview draw.
 *
 * <p>Before 1.21.11 the GUI preview hands the live player entity straight to vanilla's renderer and
 * every render layer reads that entity, so the cape editor drew the elytra the player was wearing
 * over the cape being composed. There is no render state to blank on this era - it arrives with
 * 1.21.11 - so the suppression has to happen where the layers actually look, which is
 * {@code Player.getItemBySlot}. Every equipment layer funnels through it: the elytra layer reads the
 * chest slot directly, the armour layer reads four slots, the custom-head layer reads the head slot,
 * and the item-in-hand layer arrives through {@code getMainHandItem}/{@code getOffhandItem}, which
 * delegate here. The arm pose is computed from the same read, so it follows on its own.
 *
 * <p>This overrides a read; it never writes. The player's inventory, equipment and world state are
 * untouched, which is the difference between this and blanking the chest slot for the duration of a
 * draw - that would fire equip events, be visible to anything that looked mid-frame, and could
 * strand the player unequipped if the render threw.
 *
 * <p>The scope is opened around the inline entity render and closed in the matching {@code finally},
 * and it is keyed by reference identity <em>and</em> confined to the thread that opened it. The
 * integrated server ticks its own {@code ServerPlayer} on its own thread, so it can match neither
 * guard. Every caller outside that one draw - gameplay, the HUD, the E2E assertions - reads the real
 * equipment.
 *
 * <p>Registered only on the eras that need it: the 1.20.1 and 1.21.1 mixin configurations. From
 * 1.21.11 the renderer blanks the extracted render state instead and this class is never applied.
 * The injection allocates a callback per call on a warm method; the cost is a short-lived object
 * that dies in the nursery, and it buys a single choke point instead of one hook per equipment layer.
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
