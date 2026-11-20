package com.quickskin.mod.mixin.immersive;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.client.immersive.LegacyOpeningRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if <1.21 {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
//?} else {
import net.minecraft.client.DeltaTracker;
//?}
//? if <1.21.4 {
import net.minecraft.client.renderer.LightTexture;
//?}
//? if >=1.21.2 {
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
//?}
//?}

@Mixin(value = LevelRenderer.class, priority = 2100)
public abstract class LegacyOpeningLevelMixin {
    //? if <1.21 {
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$opening(PoseStack stack, float time, long frame, boolean outline, Camera camera,
                                   GameRenderer game, LightTexture lights, Matrix4f projection, CallbackInfo ci) {
        if (!LegacyOpeningRenderer.beforeWorld()) return;
        PoseStack original = new PoseStack();
        original.last().pose().set(stack.last().pose());
        original.last().normal().set(stack.last().normal());
        LegacyOpeningRenderer.begin(camera, time, new Matrix4f(RenderSystem.getModelViewMatrix()).mul(stack.last().pose()),
                projection, cut -> ((LevelRenderer)(Object)this).renderLevel(original, time, Util.getNanos(), false,
                        camera, game, lights, cut));
    }

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$terrain(RenderType layer, PoseStack poses, double x, double y, double z,
                                  Matrix4f projection, CallbackInfo ci) {
        LegacyOpeningRenderer.beforeLayer(layer);
    }
    //?} else if <1.21.6 {
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$opening(
            //? if >=1.21.2 {
            GraphicsResourceAllocator allocator,
            //?}
            DeltaTracker delta, boolean outline, Camera camera, GameRenderer game,
            //? if <1.21.4 {
            LightTexture lights,
            //?}
            Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        if (!LegacyOpeningRenderer.beforeWorld()) return;
        LegacyOpeningRenderer.begin(camera, delta.getGameTimeDeltaPartialTick(true), view, projection,
                cut -> ((LevelRenderer)(Object)this).renderLevel(
                        //? if >=1.21.2 {
                        allocator,
                        //?}
                        delta, false, camera, game,
                        //? if <1.21.4 {
                        lights,
                        //?}
                        view, cut));
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$terrain(RenderType layer, double x, double y, double z,
                                  Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        LegacyOpeningRenderer.beforeLayer(layer);
    }
    //?}
    // The first entity starts after solid and cutout sections, even if Sodium replaced their draws.
    //? if <1.21.6 {
    @Inject(method =
            //? if <1.21.2 {
            "renderEntity",
            //?} else {
            "renderEntities",
            //?}
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$terrainDepth(CallbackInfo ci) {
        LegacyOpeningRenderer.beforeEntities();
    }
    //?}
}
