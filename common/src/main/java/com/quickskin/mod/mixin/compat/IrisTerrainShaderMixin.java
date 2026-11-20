package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=26.2 {
import com.quickskin.mod.client.immersive.compat.ChunkApertureGl;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//?}

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class IrisTerrainShaderMixin {
    //? if >=26.2 {
    @ModifyVariable(method = "createShader(Ljava/lang/String;Lnet/irisshaders/iris/gl/shader/ShaderType;Ljava/lang/String;)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0, expect = 1, allow = 1)
    private static String quickskin$terrainSource(String source, String name, @Coerce Object type) {
        if (ChunkApertureGl.irisTerrain(name) && type instanceof Enum<?> stage && stage.name().equals("FRAGMENT")) {
            return ChunkApertureGl.wrap(source);
        }
        return source;
    }
    //?}
}
