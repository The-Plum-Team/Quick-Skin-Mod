package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if <1.21.2 {
import com.quickskin.mod.client.immersive.compat.ChunkApertureGl;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//?}

/**
 * Iris and Oculus before 1.21.6 hand each patched program to the game's shader loader as a
 * string resource. The terrain and block-entity programs discard the immersive opening, like the
 * Sodium terrain programs, so blocks drawn outside Sodium (the faces inside the block the camera
 * sits in, chests, signs) open with them.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class LegacyIrisTerrainShaderMixin {
    //? if <1.21.2 {
    @Unique private static String quickskin$program;

    @ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, expect = 1, allow = 1)
    private static String quickskin$programName(String name) {
        quickskin$program = name;
        return name;
    }

    @ModifyArg(method = "create", index = 5, at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/programs/ShaderCreator$IrisProgramResourceFactory;<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
            require = 0, expect = 1, allow = 1)
    private static String quickskin$fragmentSource(String fragment) {
        String name = quickskin$program;
        quickskin$program = null;
        return name != null && ChunkApertureGl.irisOccluder(name) ? ChunkApertureGl.wrap(fragment) : fragment;
    }
    //?}
}
