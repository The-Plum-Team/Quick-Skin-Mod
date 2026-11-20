package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if <1.21.6 {
import com.quickskin.mod.client.immersive.compat.ChunkApertureGl;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/** Uploads the opening when Iris applies a program that {@link LegacyIrisTerrainShaderMixin} wrapped. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ExtendedShader", remap = false)
public abstract class LegacyIrisTerrainProgramMixin {
    //? if <1.21.6 {
    @Unique private final ChunkApertureGl quickskin$terrain = new ChunkApertureGl();

    // The program is in use when Iris finishes applying it. Through 1.21.4 that is
    // ShaderInstance/CompiledShaderProgram.apply, under its Forge (SRG), Fabric (intermediary)
    // and Mojang names. On 1.21.5 the game's command encoder binds the program and Iris sets its
    // state in GlProgram.setDefaultUniforms.
    //? if <1.21.5 {
    @Inject(method = {"m_173363_", "method_34586", "apply"}, at = @At("RETURN"), require = 0, expect = 1)
    //?} else {
    @Inject(method = {"method_60897", "setDefaultUniforms"}, at = @At("RETURN"), require = 0, expect = 1)
    //?}
    private void quickskin$applyTerrain(CallbackInfo ci) {
        quickskin$terrain.bound();
    }
    //?}
}
