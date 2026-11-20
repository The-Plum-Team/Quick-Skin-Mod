package com.quickskin.mod.mixin.immersive;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.3 {
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.quickskin.mod.client.immersive.ModernAperture;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(RenderPipelines.class)
//?} else {
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
//?}
public abstract class ImmersiveTerrainProgramsMixin {
    //? if >=26.3 {
    @Inject(method = "optionalPipelines", at = @At("RETURN"), cancellable = true, require = 0, expect = 1)
    private static void quickskin$precompileApertures(CallbackInfoReturnable<List<RenderPipeline>> callback) {
        callback.setReturnValue(ModernAperture.withTerrainVariants(callback.getReturnValue()));
    }
    //?}
}
