package com.quickskin.mod.mixin.immersive;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.6 {
import com.quickskin.mod.client.immersive.ModernAperture;
import com.quickskin.mod.client.immersive.ApertureSectionDraw;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.injection.At;
//? if <26.3 {
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import org.spongepowered.asm.mixin.injection.Redirect;
//?} else {
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
//?}
@Mixin(ChunkSectionsToRender.class)
//?} else {
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
//?}
public abstract class ImmersiveTerrainChunksMixin {
    //? if >=1.21.6 && <26.3 {
    @Redirect(method = "renderGroup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$openTerrain(RenderPass pass, RenderPipeline original) {
        RenderPipeline variant = ModernAperture.variant(original);
        pass.setPipeline(variant);
        if (variant != original) ModernAperture.bind(pass);
    }
    @Redirect(method = "renderGroup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;close()V", ordinal = 0),
            require = 0, expect = 1, allow = 1)
    private void quickskin$closeSections(RenderPass pass, ChunkSectionLayerGroup group
            //? if >=1.21.11 {
            , com.mojang.blaze3d.textures.GpuSampler sampler
            //?}
    ) {
        try {
            if (group == ChunkSectionLayerGroup.OPAQUE) ApertureSectionDraw.draw(pass);
        } finally {
            pass.close();
        }
    }
    //?} else if >=26.3 {
    @org.spongepowered.asm.mixin.injection.Inject(method = "renderGroup", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$drawSections(ChunkSectionLayerGroup group, RenderPass pass,
            com.mojang.renderpearl.api.textures.GpuSampler sampler, com.mojang.renderpearl.api.textures.GpuTextureView atlas,
            boolean wireframe, org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        if (group == ChunkSectionLayerGroup.OPAQUE) ApertureSectionDraw.draw(pass);
    }

    @ModifyArgs(method = "renderLayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;render(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$openTerrain(Args draw) {
        if (ModernAperture.opening() == null) return;
        ChunkSectionLayer layer = draw.get(0);
        RenderPass pass = draw.get(1);
        RenderPipeline direct = draw.get(4), indirect = draw.get(5);
        draw.set(4, ModernAperture.variant(direct == null ? layer.pipeline(false) : direct));
        draw.set(5, ModernAperture.variant(indirect == null ? layer.pipeline(true) : indirect));
        ModernAperture.bind(pass);
    }
    //?}
}
