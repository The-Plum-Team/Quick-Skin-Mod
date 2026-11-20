package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=26.2 {
import com.quickskin.mod.client.immersive.ModernAperture;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
//? if <26.3 {
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
//?} else {
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
//?}
//?}

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class SodiumTerrainPassMixin {
    //? if >=26.2 {
    //? if <26.3 {
    @ModifyArg(method = "render", index = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),
            require = 0, expect = 1, allow = 1)
    //?} else {
    @ModifyArg(method = "render", index = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"),
            require = 0, expect = 1, allow = 1)
    //?}
    private RenderPipeline quickskin$terrainPipeline(RenderPipeline original) {
        return ModernAperture.variant(original);
    }

    //? if <26.3 {
    @ModifyArg(method = "render", index = 1, at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;setContext(Lcom/mojang/blaze3d/systems/RenderPass;Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),
            require = 0, expect = 1, allow = 1)
    //?} else {
    @ModifyArg(method = "render", index = 1, at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;setContext(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V"),
            require = 0, expect = 1, allow = 1)
    //?}
    private RenderPipeline quickskin$terrainUniforms(RenderPass pass, RenderPipeline original) {
        var variant = ModernAperture.variant(original);
        if (variant != original) ModernAperture.bind(pass);
        return variant;
    }
    //?}
}
