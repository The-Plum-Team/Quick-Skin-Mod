package com.quickskin.mod.mixin.immersive;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.6 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ModernAperture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import org.spongepowered.asm.mixin.injection.At;
//? if <1.21.9 {
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.client.renderer.MultiBufferSource;
import com.quickskin.mod.client.immersive.BlockEntityVertices;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
//?} else {
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
//?}
@Mixin(BlockEntityRenderDispatcher.class)
//?} else {
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
//?}
public abstract class ImmersiveBlockEntityLightMixin {
    //? if >=1.21.6 && <1.21.9 {
    @Redirect(method = "setupAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/world/phys/Vec3;)V"),
            require = 0, expect = 1, allow = 1)
    private static <T extends BlockEntity> void quickskin$markBlockModel(BlockEntityRenderer<T> renderer, T block,
            float time, PoseStack pose, MultiBufferSource buffers, int light, int overlay, Vec3 camera) {
        if (ModernAperture.markBlockLight(block.getBlockPos(), 0) != 0) {
            MultiBufferSource original = buffers;
            buffers = type -> new BlockEntityVertices(original.getBuffer(type));
        }
        renderer.render(block, time, pose, buffers, light, overlay, camera);
    }
    //?} else if >=1.21.9 {
    @ModifyArgs(method = "submit", at = @At(value = "INVOKE",
            //? if <26.1 {
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V"),
            //?} else {
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"),
            //?}
            require = 0, expect = 1, allow = 1)
    private void quickskin$markBlockModel(Args submission) {
        // The dispatcher creates a fresh state for this block and frame before queuing its meshes.
        BlockEntityRenderState state = submission.get(0);
        state.lightCoords = ModernAperture.markBlockLight(state.blockPos, state.lightCoords);
    }
    //?}
}
