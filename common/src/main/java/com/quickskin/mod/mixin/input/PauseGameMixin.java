package com.quickskin.mod.mixin.input;

import com.quickskin.mod.client.gui.fan.FavouritesFan;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Esc while the favourites fan is open closes the fan instead of pausing the game, before the game
 * opens the pause menu or pauses its sounds. Forge reports the key only after the game handled it,
 * so the pause itself is where the fan takes it. A window that loses focus pauses on the next
 * tick, once the fan has closed. Without this hook Esc opens the pause menu over the fan.
 */
@Mixin(Minecraft.class)
public abstract class PauseGameMixin {
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$closeFavouritesFan(boolean pauseOnly, CallbackInfo callback) {
        if (FavouritesFan.capturesMouse()) {
            FavouritesFan.close();
            callback.cancel();
        }
    }
}
