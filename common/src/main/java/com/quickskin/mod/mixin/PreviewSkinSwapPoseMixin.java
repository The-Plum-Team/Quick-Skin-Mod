package com.quickskin.mod.mixin;

import com.quickskin.mod.client.rendering.SkinSwapAnimation;
import com.quickskin.mod.client.rendering.SkinSwapPoser;
//? if <1.21.2 {
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
//?} else if <1.21.9 {
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerCapeModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
//?} else if <1.21.11 {
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerCapeModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?} else {
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the skin menu's preview of the live player play the skin-change move with its whole body.
 *
 * <p>In a world the preview draws the live player through vanilla's renderer, which poses the
 * shared player model in {@code setupAnim}. At its end this lays the move's skeleton over that
 * pose, but only for a draw {@link SkinSwapPoser} recognises as a preview playing a move: the
 * inline preview draw before 1.21.6, or the preview's own render state from 1.21.6. Every other
 * draw, the world's and other players' included, is posed exactly as vanilla poses it, since the
 * start of each pose puts back what a move changed. From 1.21.2 the cape hangs from a separate
 * model's body, posed from the same render state, so its body gets the same move and the cape
 * follows the bending body.
 *
 * <p>Losing the hook only stills an in-world preview's move.
 */
//? if <1.21.2 {
@Mixin(PlayerModel.class)
//?} else {
@Mixin({PlayerModel.class, PlayerCapeModel.class})
//?}
public class PreviewSkinSwapPoseMixin {

//? if <1.21.2 {
    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("HEAD"),
            require = 0,
            expect = 1,
            allow = 1)
    private void quickskin$restoreSkinSwap(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                          float ageInTicks, float netHeadYaw, float headPitch,
                                          CallbackInfo ci) {
        SkinSwapPoser.restore((PlayerModel<?>) (Object) this);
    }

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("TAIL"),
            require = 0,
            expect = 1,
            allow = 1)
    private void quickskin$applySkinSwap(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                        float ageInTicks, float netHeadYaw, float headPitch,
                                        CallbackInfo ci) {
        SkinSwapAnimation.Pose pose = SkinSwapPoser.poseFor(entity);
        if (pose != null) {
            SkinSwapPoser.apply((PlayerModel<?>) (Object) this, pose);
        }
    }
//?} else {
    @Inject(
    //? if <1.21.9 {
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)V",
    //?} else {
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
    //?}
            at = @At("HEAD"),
            require = 0,
            expect = 1,
            allow = 1)
    //? if <1.21.9 {
    private void quickskin$restoreSkinSwap(PlayerRenderState renderState, CallbackInfo ci) {
    //?} else {
    private void quickskin$restoreSkinSwap(AvatarRenderState renderState, CallbackInfo ci) {
    //?}
        if ((Object) this instanceof PlayerModel model) {
            SkinSwapPoser.restore(model);
        } else {
            SkinSwapPoser.restore(((HumanoidModel<?>) (Object) this).body);
        }
    }

    @Inject(
    //? if <1.21.9 {
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)V",
    //?} else {
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
    //?}
            at = @At("TAIL"),
            require = 0,
            expect = 1,
            allow = 1)
    //? if <1.21.9 {
    private void quickskin$applySkinSwap(PlayerRenderState renderState, CallbackInfo ci) {
    //?} else {
    private void quickskin$applySkinSwap(AvatarRenderState renderState, CallbackInfo ci) {
    //?}
        SkinSwapAnimation.Pose pose = SkinSwapPoser.poseFor(renderState);
        if (pose == null) {
            return;
        }
        if ((Object) this instanceof PlayerModel model) {
            SkinSwapPoser.apply(model, pose);
        } else {
            SkinSwapPoser.applyToBody(((HumanoidModel<?>) (Object) this).body, pose);
        }
    }
//?}
}
