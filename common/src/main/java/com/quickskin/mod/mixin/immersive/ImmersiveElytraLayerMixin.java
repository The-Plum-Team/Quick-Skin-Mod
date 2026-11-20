package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
//? if <1.21.2 {
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
//?} else {
import net.minecraft.client.renderer.entity.layers.WingsLayer;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * In the immersive view the player in the world plays the skin menu's poses, which lean, lower and
 * turn the body. The game draws the elytra where a body standing straight would be, so the wings
 * are hung from the posed body first, as the cape is ({@link ImmersiveCapeLayerMixin}), and tilted
 * back over the sit's block.
 *
 * <p>The layer is {@code WingsLayer} from 1.21.2, which hangs its wings the same way (one
 * {@code PoseStack.translate} before them); its draw is {@code render} to 1.21.8 and
 * {@code submit} from 1.21.9. Every version hooks it, at that translate.
 */
//? if <1.21.2 {
@Mixin(ElytraLayer.class)
//?} else {
@Mixin(WingsLayer.class)
//?}
public abstract class ImmersiveElytraLayerMixin {
//? if <1.21 {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangWingsFromMenuPose(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                                 LivingEntity entity, float limbSwing, float limbSwingAmount,
                                                 float partialTick, float ageInTicks, float netHeadYaw,
                                                 float headPitch, CallbackInfo callback) {
        WorldPose.hangFromBody(entity, ((ElytraLayer<?, ?>) (Object) this).getParentModel(), poseStack);
        WorldPose.clearSitBlockWithWings(entity, ((ElytraLayer<?, ?>) (Object) this).getParentModel(), poseStack);
    }
//?} else if <1.21.2 {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangWingsFromMenuPose(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                                 LivingEntity entity, float limbSwing, float limbSwingAmount,
                                                 float partialTick, float ageInTicks, float netHeadYaw,
                                                 float headPitch, CallbackInfo callback) {
        WorldPose.hangFromBody(entity, ((ElytraLayer<?, ?>) (Object) this).getParentModel(), poseStack);
        WorldPose.clearSitBlockWithWings(entity, ((ElytraLayer<?, ?>) (Object) this).getParentModel(), poseStack);
    }
//?} else if <1.21.6 {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangWingsFromMenuPose(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                                 HumanoidRenderState state, float yRot, float xRot,
                                                 CallbackInfo callback) {
        WorldPose.hangFromBody(state, ((WingsLayer<?, ?>) (Object) this).getParentModel(), poseStack);
        WorldPose.clearSitBlockWithWings(state, ((WingsLayer<?, ?>) (Object) this).getParentModel(), poseStack);
    }
//?} else if <1.21.9 {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangWingsFromMenuPose(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                                 HumanoidRenderState state, float yRot, float xRot,
                                                 CallbackInfo callback) {
        EntityModel<?> parent = ((WingsLayer<?, ?>) (Object) this).getParentModel();
        WorldPose.hangFromBody(state, parent, poseStack);
        WorldPose.clearSitBlockWithWings(state, parent, poseStack, state.elytraRotX);
    }
//?} else if <26.1 {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangWingsFromMenuPose(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                                                 HumanoidRenderState state, float yRot, float xRot,
                                                 CallbackInfo callback) {
        EntityModel<?> parent = ((WingsLayer<?, ?>) (Object) this).getParentModel();
        WorldPose.hangFromBody(state, parent, poseStack);
        WorldPose.clearSitBlockWithWings(state, parent, poseStack, state);
    }
//?} else {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangWingsFromMenuPose(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                                 HumanoidRenderState state, float yRot, float xRot, CallbackInfo callback) {
        EntityModel<?> parent = ((WingsLayer<?, ?>) (Object) this).getParentModel();
        WorldPose.hangFromBody(state, parent, poseStack);
        WorldPose.clearSitBlockWithWings(state, parent, state, poseStack);
    }
//?}
}
