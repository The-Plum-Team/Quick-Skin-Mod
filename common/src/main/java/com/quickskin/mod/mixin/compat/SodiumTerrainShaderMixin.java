package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=1.21 && <26.2 {
import com.quickskin.mod.client.immersive.compat.ChunkApertureGl;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
//?}

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlShader", remap = false)
public abstract class SodiumTerrainShaderMixin {
    //? if >=1.21 && <26.2 {
    //? if <1.21.11 {
    @Shadow @Final private ResourceLocation name;
    //?} else {
    @Shadow @Final private Identifier name;
    //?}

    @ModifyArg(method = "<init>", index = 1, at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderWorkarounds;safeShaderSource(ILjava/lang/CharSequence;)V"),
            require = 0, expect = 1, allow = 1)
    private CharSequence quickskin$terrainSource(int handle, CharSequence source) {
        // Iris gives every stage of a terrain program the same name, including tessellation.
        if (ChunkApertureGl.wrapsSodium(name.toString())
                && GL20C.glGetShaderi(handle, GL20C.GL_SHADER_TYPE) == GL20C.GL_FRAGMENT_SHADER) {
            return ChunkApertureGl.wrap(source.toString());
        }
        return source;
    }
    //?}
}
