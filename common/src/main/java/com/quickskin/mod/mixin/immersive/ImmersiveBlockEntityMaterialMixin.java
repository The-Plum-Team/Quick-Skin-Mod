package com.quickskin.mod.mixin.immersive;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.6 {
import com.quickskin.mod.client.immersive.ModernAperture;
import org.spongepowered.asm.mixin.injection.At;
//? if <26.3 {
import org.spongepowered.asm.mixin.injection.Redirect;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
//?} else {
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
//?}
//? if <1.21.11 {
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
//?} else if <26.2 {
@Mixin(net.minecraft.client.renderer.rendertype.RenderType.class)
//?} else {
@Mixin(net.minecraft.client.renderer.rendertype.PreparedRenderType.class)
//?}
//?} else {
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
//?}
public abstract class ImmersiveBlockEntityMaterialMixin {
    //? if >=1.21.6 && <26.3 {
    //? if <26.2 {
    @Redirect(method = "draw", at = @At(value = "INVOKE",
    //?} else {
    @Redirect(method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V", at = @At(value = "INVOKE",
    //?}
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$blockMaterial(RenderPass pass, RenderPipeline original) {
        RenderPipeline material = ModernAperture.blockMaterial(original);
        pass.setPipeline(material);
        if (material != original) ModernAperture.bind(pass);
    }
    //?} else if >=26.3 {
    @ModifyArgs(method = "draw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$blockMaterial(Args compile, StagedVertexBuffer.ExecuteInfo info,
            RenderPass pass, RenderPipeline original) {
        RenderPipeline material = ModernAperture.blockMaterial(original);
        if (material != original) {
            ModernAperture.bind(pass);
            compile.set(0, material);
        }
    }
    //?}
}
