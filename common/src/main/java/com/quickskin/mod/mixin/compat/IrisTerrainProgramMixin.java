package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=1.21.6 {
import com.quickskin.mod.client.immersive.compat.ChunkApertureGl;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

@Pseudo
@Mixin(targets = {"net.irisshaders.iris.pipeline.programs.ExtendedShader",
        "net.irisshaders.iris.pipeline.programs.FallbackShader"}, remap = false)
public abstract class IrisTerrainProgramMixin {
    //? if >=1.21.6 {
    @Unique private final ChunkApertureGl quickskin$terrain = new ChunkApertureGl();

    @Inject(method = "iris$setupState", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$terrainUniforms(CallbackInfo ci) {
        quickskin$terrain.bound();
    }
    //?}
}
