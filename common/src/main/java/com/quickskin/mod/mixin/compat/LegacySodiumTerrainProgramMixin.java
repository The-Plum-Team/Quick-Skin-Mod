package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if <1.21 {
import com.quickskin.mod.client.immersive.compat.ChunkApertureGl;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/** Uploads the opening to the pack's terrain programs that {@link LegacySodiumTerrainShaderMixin} wrapped. */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.gl.shader.GlProgram", remap = false)
public abstract class LegacySodiumTerrainProgramMixin {
    //? if <1.21 {
    @Unique private final ChunkApertureGl quickskin$terrain = new ChunkApertureGl();

    @Inject(method = "bind", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$bindTerrain(CallbackInfo ci) {
        quickskin$terrain.bound();
    }
    //?}
}
