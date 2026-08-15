package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.client.compat.CPMCompatIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional, descriptor-neutral activity hooks for current CPM versions.
 * ClientBase keeps these four method names across every active band while its
 * Minecraft parameter descriptors change between render eras.
 */
@Pseudo
@Mixin(targets = "com.tom.cpm.client.ClientBase", remap = false)
public abstract class CpmRenderDepthMixin {

    @Inject(
            method = "playerRenderPre",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tom/cpm/shared/model/RenderManager;bindPlayer(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            // Legacy and modern CPM call different bind methods, so either target can be absent.
            require = 0,
            expect = 0,
            allow = 1,
            remap = false
    )
    private void quickskin$cpmLegacyPlayerRenderStart(CallbackInfo ci) {
        CPMCompatIntegration.onCpmRenderStart();
    }

    @Inject(
            method = "playerRenderPre",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tom/cpm/shared/model/RenderManager;bindPlayerState(Lcom/tom/cpm/shared/config/Player;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Lcom/tom/cpm/shared/animation/AnimationState;)V",
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            require = 0,
            expect = 0,
            allow = 1,
            remap = false
    )
    private void quickskin$cpmModernPlayerRenderStart(CallbackInfo ci) {
        CPMCompatIntegration.onCpmRenderStart();
    }

    @Inject(
            method = "renderHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tom/cpm/shared/model/RenderManager;bindHand(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            require = 0,
            expect = 1,
            allow = 1,
            remap = false
    )
    private void quickskin$cpmHandRenderStart(CallbackInfo ci) {
        CPMCompatIntegration.onCpmRenderStart();
    }

    @Inject(
            method = "playerRenderPost",
            at = @At("HEAD"),
            require = 0,
            expect = 1,
            allow = 1,
            remap = false
    )
    private void quickskin$cpmPlayerRenderEnd(CallbackInfo ci) {
        CPMCompatIntegration.onCpmRenderEnd();
    }

    @Inject(
            method = "renderHandPost",
            at = @At("HEAD"),
            require = 0,
            expect = 1,
            allow = 1,
            remap = false
    )
    private void quickskin$cpmHandRenderEnd(CallbackInfo ci) {
        CPMCompatIntegration.onCpmRenderEnd();
    }
}
