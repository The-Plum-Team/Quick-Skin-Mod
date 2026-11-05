package com.quickskin.mod.mixin.input;

import com.quickskin.mod.client.gui.fan.FavouritesFan;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the favourites fan is open the mouse points at its cards instead of turning the camera:
 * the movement the mouse handler gathered goes to the fan, and the player keeps walking with the
 * view held still. Without this hook the camera turns as before while the fan is open.
 */
@Mixin(MouseHandler.class)
public abstract class MouseTurnMixin {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$steerFavouritesFan(CallbackInfo callback) {
        if (FavouritesFan.capturesMouse()) {
            FavouritesFan.steer(accumulatedDX, accumulatedDY);
            accumulatedDX = 0;
            accumulatedDY = 0;
            callback.cancel();
        }
    }
}
