package com.quickskin.mod.mixin.immersive;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.6 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.LegacyOpeningRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
//? if <1.21.2 {
import net.minecraft.world.entity.Entity;
//?} else {
import net.minecraft.client.renderer.entity.state.EntityRenderState;
//?}
//?}

@Mixin(EntityRenderDispatcher.class)
public abstract class LegacyOpeningShadowMixin {
    //? if <1.21.6 {
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderShadow(" +
            "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;" +
            //? if <1.21.2 {
            "Lnet/minecraft/world/entity/Entity;FF" +
            //?} else if <1.21.5 {
            "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FF" +
            //?} else {
            "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F" +
            //?}
            "Lnet/minecraft/world/level/LevelReader;F)V"), index = 1, require = 0, expect = 1, allow = 1)
    private MultiBufferSource quickskin$captureShadow(PoseStack poses, MultiBufferSource buffers,
            //? if <1.21.2 {
            Entity caster,
            //?} else {
            EntityRenderState caster,
            //?}
            float opacity,
            //? if <1.21.5 {
            float time,
            //?}
            LevelReader level, float radius) {
        return LegacyOpeningRenderer.shadow(caster, buffers);
    }
    //?}
}
