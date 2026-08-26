package com.quickskin.mod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
//? if <26.2 {
import com.mojang.blaze3d.vertex.VertexConsumer;
//?} else {
//?}
import com.mojang.math.Axis;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.SkinLayers3DIntegration;
//? if <26.2 {
import net.minecraft.client.Minecraft;
//?} else {
//?}
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
//? if <26.1 {
import net.minecraft.client.gui.render.state.pip.GuiSkinRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
//?} else if <26.2 {
import net.minecraft.client.renderer.state.gui.pip.GuiSkinRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
//?} else {
import net.minecraft.client.renderer.state.gui.pip.GuiSkinRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
//?}
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
//? if <26.2 {
import net.minecraft.resources.Identifier;
//?} else {
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to inject cape rendering into the PiP (Picture-in-Picture) skin rendering system.
 *
 * In 26.2, the PiP renderToTexture pipeline was migrated off the immediate MultiBufferSource
 * (which was removed) onto the deferred {@link SubmitNodeCollector}. The method signature gained
 * a trailing SubmitNodeCollector parameter:
 *   renderToTexture(GuiSkinRenderState, PoseStack, SubmitNodeCollector)
 * We inject at TAIL (after the body parts have been submitted) and submit the cape part to the
 * same collector, mirroring how CapeLayerMixin submits the cape model in-world.
 */
@Mixin(GuiSkinRenderer.class)
public class GuiSkinRendererMixin {

    //? if <26.2 {
    @Inject(
            require = 0,
            expect = 1,
            allow = 1,
        //? if <26.1 {
            method = "renderToTexture(Lnet/minecraft/client/gui/render/state/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        //?} else {
            method = "renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        //?}
            at = @At("HEAD")
    )
    private void quickskin$prepareSkinLayersMeshes(
            GuiSkinRenderState state, PoseStack poseStack, CallbackInfo ci) {
        Boolean thinArms = PlayerModelRenderer.getQuickSkinPreviewThinArms(state.playerModel());
        if (thinArms != null) {
            SkinLayers3DIntegration.prepareInjectedPreview(
                    state.playerModel(), state.texture(), thinArms);
        }
    }

    //?}
    @Inject(
            require = 0,
            expect = 1,
            allow = 1,
//? if <26.1 {
            method = "renderToTexture(Lnet/minecraft/client/gui/render/state/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V"
            )
//?} else if <26.2 {
            method = "renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V"
            )
//?} else {
            method = "renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
            at = @At("HEAD")
//?}
    )
//? if <26.2 {
    private void quickskin$renderCapeInPiP(GuiSkinRenderState state, PoseStack poseStack, CallbackInfo ci) {
        // Use the shared buffer source (same instance used by the PiP system).
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
//?} else {
    private void quickskin$attachSkinLayersMeshes(GuiSkinRenderState state, PoseStack poseStack,
                                                   SubmitNodeCollector collector, CallbackInfo ci) {
        var root = state.playerModel().root();
        Boolean thinArms = PlayerModelRenderer.getQuickSkinPreviewThinArms(root);
//?}
//? if <26.2 {
//?} else {
        if (thinArms != null) {
            SkinLayers3DIntegration.attachDeferredMeshes(root, state.texture(), thinArms);
        }
    }
//?}

//? if <26.2 {
//?} else {
    @Inject(
            method = "renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
            at = @At("TAIL"),
            require = 0,
            expect = 1,
            allow = 1
    )
    private void quickskin$renderCapeInPiP(GuiSkinRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
//?}
        PlayerModelRenderer.PreviewCapeState cape =
//? if <26.2 {
                PlayerModelRenderer.consumePendingCape(
                        state.playerModel(), state.texture(), state.rotationX(), state.rotationY(),
                        state.pivotY(), state.x0(), state.y0(), state.x1(), state.y1(), state.scale());
        if (cape == null || cape.texture() == null
                || cape.bodyModel() == null || cape.capeModel() == null) {
//?} else {
                PlayerModelRenderer.consumePendingCape(state.playerModel());
        if (cape == null) {
//?}
            return;
        }

        RenderType capeRenderType = RenderTypes.entityTranslucent(cape.texture());
//? if <26.2 {
        VertexConsumer capeConsumer = bufferSource.getBuffer(capeRenderType);
//?} else {
//?}

        poseStack.pushPose();
        cape.bodyModel().body.translateAndRotate(poseStack);
        poseStack.translate(0.0, 0.0, 0.125);
        poseStack.mulPose(Axis.XP.rotationDegrees(6.0F));
//? if <26.2 {
        cape.capeModel().body.getChild("cape").render(
                poseStack, capeConsumer, 15728880, OverlayTexture.NO_OVERLAY);
//?} else {
        // 26.2: submit the cape part to the deferred collector instead of writing to a VertexConsumer.
        collector.submitModelPart(cape.capeModel().body.getChild("cape"), poseStack, capeRenderType,
                15728880, OverlayTexture.NO_OVERLAY, null);
//?}
        poseStack.popPose();
    }

    //? if <26.2 {
    @Inject(
            require = 0,
            expect = 1,
            allow = 1,
        //? if <26.1 {
            method = "renderToTexture(Lnet/minecraft/client/gui/render/state/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        //?} else {
            method = "renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        //?}
            at = @At("TAIL")
    )
    private void quickskin$clearSkinLayersMeshes(
            GuiSkinRenderState state, PoseStack poseStack, CallbackInfo ci) {
        SkinLayers3DIntegration.clearInjectedPreview(state.playerModel());
    }
    //?}
}
