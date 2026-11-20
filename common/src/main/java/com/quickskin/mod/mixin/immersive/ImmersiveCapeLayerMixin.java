package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.9 {
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
//? if <1.21.2 {
import net.minecraft.client.renderer.entity.layers.CapeLayer;
//?} else if <1.21.11 {
import net.minecraft.client.model.PlayerCapeModel;
//?} else {
import net.minecraft.client.model.player.PlayerCapeModel;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * In the immersive view the player in the world plays the skin menu's poses, which lean, lower and
 * turn the body. The game draws a cape where a body standing straight would be, so the cape the
 * game itself draws (a Mojang cape) is hung from the posed body first, and tilted back over the
 * sit's block; a Quick Skin cape, which {@code CapeLayerMixin} draws, does the same there.
 *
 * <p>Before 1.21.2 the cape layer draws the cape in the model's own space, so this hooks the layer.
 * From 1.21.2 the layer copies the player's pose into its own {@code PlayerCapeModel} and then has
 * that model pose itself from the render state, which undoes the copy: there the hook belongs at
 * the end of {@code PlayerCapeModel.setupAnim}, copying the posed body in
 * ({@code WorldPose.poseCape}, handed the draw going on, {@code WorldPose.drawing(state)}, to
 * 1.21.8; from 1.21.9, where the cape model is posed again after the player's {@code submit}
 * returned, the kept draw, {@code WorldPose.bound(state)}, and from 26.1 any draw of the world pass,
 * {@code WorldPose.passBound(state)}), which covers the Mojang cape and Quick Skin's alike. Every
 * version hooks it.
 */
//? if <1.21.2 {
@Mixin(CapeLayer.class)
//?} else {
@Mixin(PlayerCapeModel.class)
//?}
public abstract class ImmersiveCapeLayerMixin {
//? if <1.21 {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangCapeFromMenuPose(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                                AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                                float partialTick, float ageInTicks, float netHeadYaw,
                                                float headPitch, CallbackInfo callback) {
        WorldPose.hangFromBody(player, ((CapeLayer) (Object) this).getParentModel(), poseStack);
        WorldPose.clearSitBlock(player, ((CapeLayer) (Object) this).getParentModel(), poseStack, partialTick);
    }
//?} else if <1.21.2 {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangCapeFromMenuPose(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                                AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                                float partialTick, float ageInTicks, float netHeadYaw,
                                                float headPitch, CallbackInfo callback) {
        WorldPose.hangFromBody(player, ((CapeLayer) (Object) this).getParentModel(), poseStack);
        WorldPose.clearSitBlock(player, ((CapeLayer) (Object) this).getParentModel(), poseStack, partialTick);
    }
//?} else if <1.21.6 {
    // PlayerCapeModel.setupAnim has posed its body from the state, undoing the player's pose the
    // cape layer copied in: the posed body goes back in, and the cape, its child, follows
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)V", at = @At("TAIL"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangCapeFromMenuPose(PlayerRenderState state, CallbackInfo callback) {
        WorldPose.Binding binding = WorldPose.drawing(state);
        ModelPart body = ((HumanoidModel<?>) (Object) this).body;
        if (WorldPose.poseCape(binding, body)) {
            WorldPose.clearSitBlock(binding, body, body.getChild("cape"), state);
        }
    }
//?} else if <1.21.9 {
    // The cape model is posed inside the player's draw (the game's cape layer and Quick Skin's alike)
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)V", at = @At("TAIL"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangCapeFromMenuPose(PlayerRenderState state, CallbackInfo callback) {
        WorldPose.Binding binding = WorldPose.drawing(state);
        HumanoidModel<?> capeModel = (HumanoidModel<?>) (Object) this;
        if (WorldPose.poseCape(binding, capeModel.body)) {
            WorldPose.clearSitBlock(binding, capeModel.body, state.capeFlap);
        }
    }
//?} else if <26.1 {
    @Shadow
    @Final
    private ModelPart cape;

    // The game poses the cape model only once the player's submit returned, when it draws it
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangCapeFromMenuPose(AvatarRenderState state, CallbackInfo callback) {
        if (!ImmersiveMode.posesPlayer() && !WorldPose.posesOthers()) {
            return;
        }
        WorldPose.Binding binding = WorldPose.bound(state);
        if (WorldPose.poseCape(binding, ((HumanoidModel<?>) (Object) this).body)) {
            WorldPose.clearSitBlock(binding, this.cape, state);
        }
    }
//?} else {
    // The cape model is posed inside the player's draw (Quick Skin's own cape) and again when the
    // collected model is drawn, after the draw returned: the draw is the one going on or one of the
    // world pass (WorldPose.passBound)
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$hangCapeFromMenuPose(AvatarRenderState state, CallbackInfo callback) {
        if (!ImmersiveMode.posesPlayer() && !WorldPose.posesOthers()) {
            return;
        }
        WorldPose.Binding binding = WorldPose.passBound(state);
        ModelPart body = ((PlayerCapeModel) (Object) this).body;
        if (WorldPose.poseCape(binding, body)) {
            WorldPose.clearSitBlock(binding, body, body.getChild("cape"), state);
        }
    }
//?}
}
