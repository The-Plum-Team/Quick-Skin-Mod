package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.client.compat.CPMCompatIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops only CPM's already-extracted player submission while its model-to-skin transition crosses
 * a frame boundary. CPM's 26.1 Fabric collector otherwise dereferences render types discarded by
 * that transition; the following frame submits the ordinary player with the selected skin.
 */
@Pseudo
@Mixin(targets = "com.tom.cpm.client.CPMOrderedSubmitNodeCollector", remap = false)
public abstract class CpmSubmitCollectorMixin {

    @Inject(
            method = "submitModel",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            expect = 0,
            allow = 8,
            remap = false
    )
    private void quickskin$skipStaleExtractedModel(CallbackInfo ci) {
        if (CPMCompatIntegration.isSkinModeResetInProgress()) {
            ci.cancel();
        }
    }
}
