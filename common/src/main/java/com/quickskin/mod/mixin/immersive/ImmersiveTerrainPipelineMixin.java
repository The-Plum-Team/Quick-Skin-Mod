package com.quickskin.mod.mixin.immersive;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.6 {
import com.quickskin.mod.client.immersive.ModernAperture;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if <26.3 {
import com.mojang.blaze3d.pipeline.RenderPipeline;
//?} else {
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//?}
@Mixin(RenderPipeline.Builder.class)
//?} else {
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
//?}
public abstract class ImmersiveTerrainPipelineMixin {
    //? if >=1.21.6 {
    @Inject(method = "build", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$terrainVariant(CallbackInfoReturnable<RenderPipeline> callback) {
        ModernAperture.register(callback.getReturnValue(), (RenderPipeline.Builder)(Object)this);
    }
    //?}
}
