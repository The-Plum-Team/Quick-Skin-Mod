package com.quickskin.mod.mixin.immersive;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if <1.21.9 {
import com.quickskin.mod.client.immersive.light.PlayerLightBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//?} else {
import com.quickskin.mod.client.immersive.light.PlayerFaceLight;
import com.quickskin.mod.client.immersive.light.PlayerLightPose;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if <26.1 {
import net.minecraft.client.renderer.state.CameraRenderState;
//?} else {
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?}
//?}
//? if <1.21.2 {
import net.minecraft.world.entity.LivingEntity;
//?} else {
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
//? if <1.21.9 {
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
//?}
//?}

@Mixin(LivingEntityRenderer.class)
public abstract class ImmersivePlayerLightMixin {
    //? if <1.21.2 {
    @ModifyVariable(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, expect = 1, allow = 1)
    private MultiBufferSource quickskin$lightPlayer(MultiBufferSource buffers, LivingEntity who, float yaw,
                                                     float partialTick, PoseStack view) {
        if (!ImmersiveMode.posesPlayer() || who != Minecraft.getInstance().player) return buffers;
        return PlayerLightBuffers.wrap(view, buffers);
    }
    //?} else if <1.21.9 {
    @ModifyVariable(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, expect = 1, allow = 1)
    private MultiBufferSource quickskin$lightPlayer(MultiBufferSource buffers, LivingEntityRenderState who, PoseStack view) {
        if (!ImmersiveMode.posesPlayer() || !(who instanceof PlayerRenderState player)) return buffers;
        var local = Minecraft.getInstance().player;
        return local != null && player.id == local.getId() ? PlayerLightBuffers.wrap(view, buffers) : buffers;
    }
    //?} else {
    //? if <26.1 {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
    //?} else {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
    //?}
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0,
                    shift = At.Shift.AFTER), require = 0, expect = 1, allow = 1)
    // The body and equipment are submitted from inside the renderer's own push/pop. Capturing
    // before the body rotations leaves world up intact, and the caller's pose stays untouched.
    private void quickskin$lightPlayer(LivingEntityRenderState who, PoseStack view, SubmitNodeCollector collector,
                                       CameraRenderState camera, CallbackInfo ci) {
        if (!ImmersiveMode.posesPlayer() || !(who instanceof AvatarRenderState player)) return;
        var local = Minecraft.getInstance().player;
        if (local != null && player.id == local.getId()) {
            ((PlayerLightPose) (Object) view.last()).quickskin$faceLight(PlayerFaceLight.forView(view));
        }
    }
    //?}
}
