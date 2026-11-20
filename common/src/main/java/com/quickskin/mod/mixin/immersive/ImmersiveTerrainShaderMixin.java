package com.quickskin.mod.mixin.immersive;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.6 {
import com.google.common.collect.ImmutableMap;
import com.quickskin.mod.client.immersive.ModernApertureShader;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import java.util.Map;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
//? if <26.3 {
import com.mojang.blaze3d.shaders.ShaderType;
//?} else {
import com.mojang.renderpearl.api.pipeline.ShaderType;
//?}
@Mixin(ShaderManager.class)
//?} else {
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
//?}
public abstract class ImmersiveTerrainShaderMixin {
    //? if >=1.21.6 {
    @ModifyArgs(method = "loadShader", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableMap$Builder;put(Ljava/lang/Object;Ljava/lang/Object;)Lcom/google/common/collect/ImmutableMap$Builder;",
            remap = false), require = 0, expect = 1, allow = 1)
    private static void quickskin$wrapTerrainShader(Args stored,
            //? if <1.21.11 {
            ResourceLocation file,
            //?} else {
            Identifier file,
            //?}
            Resource resource, ShaderType type,
            //? if <26.3 {
            Map<?, ?> includes,
            //?}
            ImmutableMap.Builder<?, ?> output) {
        String path = file.toString();
        if (path.equals("quickskin:shaders/core/aperture_section_gl.fsh")
                || path.equals("quickskin:shaders/core/aperture_section_gpu.fsh")) {
            String source = stored.get(1);
            stored.set(1, source.replace("#define QUICKSKIN_APERTURE_FUNCTIONS", ModernApertureShader.fragmentTest()));
            return;
        }
        if (path.equals("minecraft:shaders/core/terrain.vsh") || path.equals("minecraft:shaders/core/terrain.fsh")
                || path.equals("sodium:shaders/blocks/block_layer_opaque.vsh")
                || path.equals("sodium:shaders/blocks/block_layer_opaque.fsh")) {
            //? if <26.3 {
            boolean explicitLocations = false;
            //?} else {
            boolean explicitLocations = true;
            //?}
            stored.set(1, ModernApertureShader.wrap(stored.get(1), type == ShaderType.VERTEX, explicitLocations));
        }
    }
    //?}
}
