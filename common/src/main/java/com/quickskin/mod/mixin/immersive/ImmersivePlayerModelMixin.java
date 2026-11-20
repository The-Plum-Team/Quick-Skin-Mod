package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.9 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
//? if <1.21.11 {
import net.minecraft.client.model.PlayerModel;
//?} else {
import net.minecraft.client.model.player.PlayerModel;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * In the immersive view the player in the world plays the skin menu's poses: once the game has
 * posed the player's model, the menu's pose replaces the game's, for the draw
 * {@link ImmersiveLivingRendererMixin} prepared. The start of every pose puts back what a pose
 * moved ({@code PreviewSkinSwapPoseMixin}), so no other player is drawn with it.
 *
 * <p>{@code setupAnim(LivingEntity, FFFFF)} before 1.21.2, {@code setupAnim(PlayerRenderState)}
 * from 1.21.2 and {@code setupAnim(AvatarRenderState)} from 1.21.9; the model moves to
 * {@code model.player} at 1.21.11. {@code WorldPose.pose} takes the entity or the render state the
 * draw was bound by; from 1.21.9 the game poses the model again after {@code submit} returned, so
 * {@code pose} finds the draw among the kept ones there ({@code WorldPose.bound}, from 26.1 any
 * draw of the world pass, {@code WorldPose.passBound}), and before 1.21.9 only as the draw going
 * on ({@code WorldPose.drawing}). From 1.21.9 the cape's {@code PlayerCapeModel} and vanilla's
 * armour models are player models too: the armour takes the pose here, and the cape model is left
 * to {@link ImmersiveCapeLayerMixin} (by {@code WorldPose.pose} on 1.21.9 to 1.21.11, by this hook
 * from 26.1). Every version hooks it.
 */
@Mixin(PlayerModel.class)
public abstract class ImmersivePlayerModelMixin {
//? if <1.21 {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$takeMenuPose(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                        float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer() || WorldPose.posesOthers()) {
            WorldPose.pose(entity, (PlayerModel<?>) (Object) this);
        }
    }
//?} else if <1.21.2 {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$takeMenuPose(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                        float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer() || WorldPose.posesOthers()) {
            WorldPose.pose(entity, (PlayerModel<?>) (Object) this);
        }
    }
//?} else if <1.21.6 {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)V", at = @At("TAIL"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$takeMenuPose(PlayerRenderState state, CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer() || WorldPose.posesOthers()) {
            WorldPose.pose(state, (PlayerModel) (Object) this);
        }
    }
//?} else if <1.21.9 {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)V", at = @At("TAIL"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$takeMenuPose(PlayerRenderState state, CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer() || WorldPose.posesOthers()) {
            WorldPose.pose(state, (PlayerModel) (Object) this);
        }
    }
//?} else if <26.1 {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$takeMenuPose(AvatarRenderState state, CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer() || WorldPose.posesOthers()) {
            WorldPose.pose(state, (PlayerModel) (Object) this);
        }
    }
//?} else {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$takeMenuPose(AvatarRenderState state, CallbackInfo callback) {
        // The cape's model is a player model too; its body takes the posed one (ImmersiveCapeLayerMixin)
        if ((Object) this instanceof PlayerCapeModel) {
            return;
        }
        if (ImmersiveMode.posesPlayer() || WorldPose.posesOthers()) {
            WorldPose.pose(state, (PlayerModel) (Object) this);
        }
    }
//?}
}
