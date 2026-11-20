package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldLook;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldLook;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * In the immersive view the player in the world plays the skin menu's poses. Right before the
 * game poses the player's model, with the draw already in the model's own space, the body is
 * turned to the camera, given the moves' lift and scale, and the sit's block is drawn under it
 * ({@code WorldPose.beforeModel}); {@link ImmersivePlayerModelMixin} then replaces the pose.
 * Only the world's draw of the local player during a view is touched, and the draws of other
 * players the server says play a pose in their own immersive view (ADR 0019).
 *
 * <p>From 1.21.2 the renderer is handed a render state: the draw is bound to its player first
 * ({@code WorldPose.bind}). To 1.21.8 the hook is right before {@code EntityModel.setupAnim} in
 * {@code render}; from 1.21.9 the method is {@code submit}, which collects the model and its layers
 * to draw later, and the hook is right before it asks {@code isBodyVisible}, the same model space.
 * There this handler also turns a local player who does not play the pose (riding, crouching, a
 * CPM look) as far as the open screen asks ({@code WorldLook.turnUnposed}), after the pose
 * decided; earlier versions do that in {@link ImmersiveLookRendererMixin}. Every version ends the
 * draw at the method's return ({@code WorldPose.drawn}).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ImmersiveLivingRendererMixin {
//? if <1.21 {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$playMenuPose(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                        MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        // The local player during this client's view, and other players the server says pose (ADR 0019)
        boolean local = ImmersiveMode.posesPlayer();
        if (local || WorldPose.posesOthers()) {
            WorldPose.beforeModel(local ? ImmersiveMode.session() : WorldPose.NO_SESSION, entity, partialTick,
                    poseStack, buffers, packedLight, ImmersiveMode.faceYaw(), ImmersiveMode.settle());
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$menuPosePlayed(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                          MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        WorldPose.drawn();
    }
//?} else if <1.21.2 {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$playMenuPose(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                        MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        // The local player during this client's view, and other players the server says pose (ADR 0019)
        boolean local = ImmersiveMode.posesPlayer();
        if (local || WorldPose.posesOthers()) {
            WorldPose.beforeModel(local ? ImmersiveMode.session() : WorldPose.NO_SESSION, entity, partialTick,
                    poseStack, buffers, packedLight, ImmersiveMode.faceYaw(), ImmersiveMode.settle());
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$menuPosePlayed(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                          MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        WorldPose.drawn();
    }
//?} else if <1.21.6 {
    // The renderer draws the render state the game extracted from the entity just before; the
    // pose point is the same, right before the model is posed, after the model-space translate
    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$playMenuPose(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                        int packedLight, CallbackInfo callback) {
        // The local player during this client's view, and other players the server says pose (ADR 0019)
        boolean local = ImmersiveMode.posesPlayer();
        if (local || WorldPose.posesOthers()) {
            WorldPose.beforeModel(local ? ImmersiveMode.session() : WorldPose.NO_SESSION, state, poseStack, buffers,
                    packedLight, ImmersiveMode.faceYaw(), ImmersiveMode.settle());
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$menuPosePlayed(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                          int packedLight, CallbackInfo callback) {
        WorldPose.drawn();
    }
//?} else if <1.21.9 {
    // The model is posed inside render, after the draw is in the model's own space
    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$playMenuPose(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                        int packedLight, CallbackInfo callback) {
        // The local player during this client's view, and other players the server says pose (ADR 0019)
        boolean local = ImmersiveMode.posesPlayer();
        if ((local || WorldPose.posesOthers()) && state instanceof PlayerRenderState player) {
            WorldPose.beforeModel(local ? ImmersiveMode.session() : WorldPose.NO_SESSION, player, poseStack,
                    buffers, packedLight, ImmersiveMode.faceYaw(), ImmersiveMode.settle());
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$menuPosePlayed(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                          int packedLight, CallbackInfo callback) {
        WorldPose.drawn();
    }
//?} else if <26.1 {
    // The model and every layer are submitted after the body's visibility is asked, each with a
    // copy of the pose stack, so the posing goes right before that question
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;isBodyVisible(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$playMenuPose(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                        CameraRenderState camera, CallbackInfo callback) {
        if (!(state instanceof AvatarRenderState avatar)) {
            return;
        }
        // The local player during this client's view, and other players the server says pose (ADR 0019)
        boolean local = ImmersiveMode.posesPlayer();
        if (local || WorldPose.posesOthers()) {
            WorldPose.beforeModel(local ? ImmersiveMode.session() : WorldPose.NO_SESSION, avatar, poseStack, collector,
                    ImmersiveMode.faceYaw(), ImmersiveMode.settle());
        }
        // The skin menu's pose turns the player itself; a player who does not play it (riding,
        // crouching, a CPM look) still turns as far as the open screen asks (WorldLook). Here, so
        // it always runs after the pose decided, not in the look's own mixin at the same point
        Minecraft minecraft = Minecraft.getInstance();
        if (local && !WorldPose.posing(avatar) && minecraft.player != null && avatar.id == minecraft.player.getId()) {
            float turn = ImmersiveMode.playerTurn();
            WorldLook.turnUnposed(minecraft.player, avatar.bodyRot, poseStack, ImmersiveMode.faceYaw() - turn, turn,
                    ImmersiveMode.settle());
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$menuPosePlayed(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                          CameraRenderState camera, CallbackInfo callback) {
        WorldPose.drawn();
    }
//?} else {
    // The draw is in the model's own space once the renderer has turned, flipped and lowered it,
    // right before it asks whether the body is visible: before the model and its layers are
    // collected, so the pose reaches both
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;isBodyVisible(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$playMenuPose(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                        CameraRenderState camera, CallbackInfo callback) {
        // The local player during this client's view, and other players the server says pose (ADR 0019)
        boolean local = ImmersiveMode.posesPlayer();
        if (local || WorldPose.posesOthers()) {
            WorldPose.beforeModel(local ? ImmersiveMode.session() : WorldPose.NO_SESSION, state, poseStack,
                    collector, ImmersiveMode.faceYaw(), ImmersiveMode.settle());
        }
        // The skin menu's pose turns the player itself; a player who does not play it (riding,
        // crouching, a CPM look) still turns as far as the open screen asks (WorldLook). Here, so
        // it always runs after the pose decided, not in the look's own mixin at the same point
        Minecraft minecraft = Minecraft.getInstance();
        if (local && state instanceof AvatarRenderState avatar && !WorldPose.posing(state)
                && minecraft.player != null && avatar.id == minecraft.player.getId()) {
            float turn = ImmersiveMode.playerTurn();
            WorldLook.turnUnposed(minecraft.player, state.bodyRot, poseStack, ImmersiveMode.faceYaw() - turn, turn,
                    ImmersiveMode.settle());
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$menuPosePlayed(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                          CameraRenderState camera, CallbackInfo callback) {
        WorldPose.drawn();
    }
//?}
}
