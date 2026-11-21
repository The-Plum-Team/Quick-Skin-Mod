package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if <1.21 {
import com.quickskin.mod.client.immersive.compat.ChunkApertureGl;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
//?}

/**
 * Sodium 0.5 and Embeddium name their package {@code me.jellysquid}. With an Iris or Oculus
 * shader pack, its terrain programs discard the immersive opening themselves, so the world is
 * drawn once through the pack.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.gl.shader.GlShader", remap = false)
public abstract class LegacySodiumTerrainShaderMixin {
    //? if <1.21 {
    @Shadow @Final private ResourceLocation name;

    @ModifyArg(method = "<init>", index = 1, at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/gl/shader/ShaderWorkarounds;safeShaderSource(ILjava/lang/CharSequence;)V"),
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
