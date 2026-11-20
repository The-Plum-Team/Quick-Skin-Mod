package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=1.21.6 && <26.2 {
import com.quickskin.mod.client.immersive.compat.ChunkApertureGl;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlProgram", remap = false)
public abstract class SodiumTerrainProgramMixin {
    //? if >=1.21.6 && <26.2 {
    @Unique private final ChunkApertureGl quickskin$terrain = new ChunkApertureGl();

    @Inject(method = "bind", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$bindTerrain(CallbackInfo ci) {
        quickskin$terrain.bound();
    }
    //?}
}
