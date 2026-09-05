//? if >=1.21.2 && <1.21.6 {
package com.quickskin.mod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewCapeBindings;
import com.quickskin.mod.client.services.CapeAnimationHelper;
import com.quickskin.mod.client.services.CapeService;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Minecraft API-family cape adapter: render-state input with an immediate buffer. */
@Mixin(value = CapeLayer.class, priority = 1100)
public class CapeLayerMixin {

    /** CapeLayer owns a separate PlayerCapeModel from Minecraft 1.21.2 onward. */
    @Shadow @Final private HumanoidModel<?> model;

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1)
    private void quickskin$renderCustomCape(PoseStack poseStack, MultiBufferSource buffer,
                                            int packedLight, PlayerRenderState renderState,
                                            float yRot, float xRot, CallbackInfo ci) {
        if (CPMCompatIntegration.shouldDeferToCPM()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        AbstractClientPlayer player = null;
        UUID playerId = null;
        if (minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(renderState.id);
            if (entity instanceof AbstractClientPlayer resolvedPlayer) {
                player = resolvedPlayer;
                playerId = resolvedPlayer.getUUID();
            }
        }

        // The 1.21.2 entity render is immediate but CapeLayer receives PlayerRenderState.
        // consumePreviewCape falls back to the inline render's thread-scoped entity binding.
        PreviewCapeBindings.Resolution<ResourceLocation> preview =
                PlayerModelRenderer.consumePreviewCape(player);
        if (preview.decision() == PreviewCapeBindings.Decision.HIDDEN) {
            ci.cancel();
            return;
        }
        boolean previewing = preview.decision() == PreviewCapeBindings.Decision.PREVIEW;

        if (playerId == null && minecraft.level == null) {
            playerId = minecraft.getUser().getProfileId();
        }
        if (playerId == null && !previewing) {
            return;
        }

//? if <1.21.4 {
        if (!previewing && renderState.chestItem.is(Items.ELYTRA)) {
//?} else {
        if (!previewing && renderState.chestEquipment.is(Items.ELYTRA)) {
//?}
            ci.cancel();
            return;
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        boolean hasServiceCape = !previewing && service.hasActiveCape(playerId);
        boolean isLocalPlayer = playerId != null
                && playerId.equals(minecraft.getUser().getProfileId());
        ClientConfig config = ClientConfig.getInstance();
        boolean hasConfigCape = !previewing && !hasServiceCape && isLocalPlayer
                && !config.activeCapeHash.isEmpty();
        if (!previewing && !hasServiceCape && !hasConfigCape) {
            return;
        }

        String capeId = previewing ? null : service.getCapeId(playerId);
        if (capeId != null) {
            CapeAnimationHelper.markCapeVisible(capeId);
        }

        ResourceLocation capeTexture = previewing ? preview.texture() : null;
        if (!previewing && hasServiceCape) {
            capeTexture = service.getCapeLocation(playerId);
        }
        if (capeTexture == null && !previewing && hasConfigCape) {
            capeTexture = CapeService.getInstance().getCapeLocation(null, config.activeCapeHash);
        }
        if (capeTexture == null) {
            ci.cancel();
            return;
        }

        ResourceLocation finalTexture =
                CapeAnimationHelper.resolveCurrentFrame(capeTexture, capeId);
        if (finalTexture == null) {
            // Network animations deliberately render nothing until their bounded first frame exists.
            ci.cancel();
            return;
        }

        RenderType renderType = QuickSkinInfo.MOD_ID.equals(finalTexture.getNamespace())
                || TextureAlphaDetector.hasTransparency(finalTexture)
                ? RenderType.entityTranslucent(finalTexture)
                : RenderType.entitySolid(finalTexture);
        VertexConsumer vertices = buffer.getBuffer(renderType);

        // Copy the parent pose into CapeLayer's separate PlayerCapeModel, then animate it from
        // the same PlayerRenderState vanilla supplied for this frame.
        @SuppressWarnings("unchecked")
        HumanoidModel<PlayerRenderState> capeModel =
                (HumanoidModel<PlayerRenderState>) (HumanoidModel<?>) model;
        ((CapeLayer) (Object) this).getParentModel().copyPropertiesTo(capeModel);
        capeModel.setupAnim(renderState);
        capeModel.renderToBuffer(poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY);
        ci.cancel();
    }
}
//?} else {
package com.quickskin.mod.mixin;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewCapeBindings;
import com.quickskin.mod.client.services.CapeAnimationHelper;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
//? if <1.21 {
//?} else {
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
//?}
import com.mojang.blaze3d.vertex.PoseStack;
//? if <1.21.11 {
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
//?} else {
//?}
import net.minecraft.client.player.AbstractClientPlayer;
//? if <1.21.11 {
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
//?} else {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
//?}
import net.minecraft.client.renderer.entity.layers.CapeLayer;
//? if <1.21.11 {
//?} else {
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?}
import net.minecraft.client.renderer.texture.OverlayTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
//?} else {
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
//?}
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
//? if <1.21.11 {
//?} else {
import net.minecraft.client.model.HumanoidModel;
import org.spongepowered.asm.mixin.Final;
//?}
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.11 {
//?} else {
import org.spongepowered.asm.mixin.Shadow;
//?}
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if <1.21.11 {
//?} else {
import java.util.UUID;

//?}
@Mixin(value = CapeLayer.class, priority = 1100) // Higher priority to override TLSkinCape and other mods
public class CapeLayerMixin {

//? if <1.21 {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
//?} else if <1.21.11 {
    // Throttle logging to avoid spam

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
//?} else {
    // In MC 1.21.11+, CapeLayer has its own cape model (PlayerCapeModel) separate from PlayerModel
    @Shadow @Final private HumanoidModel<?> model;

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
//?}
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1)
//? if <1.21.11 {
    private void quickskin$renderCustomCape(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                            AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
//?} else {
    private void quickskin$renderCustomCape(PoseStack poseStack, SubmitNodeCollector buffer, int packedLight,
                                            AvatarRenderState renderState, float yRot, float xRot,
//?}
                                            CallbackInfo ci) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        // The GUI preview renders the real player entity, so without this the cape below would be
        // the one the player is wearing rather than the one the editor has selected. The preview
        // binds its cape to this draw only; an unbound draw keeps resolving the applied cape.
//? if <1.21.11 {
        PreviewCapeBindings.Resolution<ResourceLocation> quickskin$preview =
                PlayerModelRenderer.consumePreviewCape(player);
//?} else {
        PreviewCapeBindings.Resolution<Identifier> quickskin$preview =
                PlayerModelRenderer.consumePreviewCape(renderState);
//?}
        if (quickskin$preview.decision() == PreviewCapeBindings.Decision.HIDDEN) {
            ci.cancel(); // The editor has no cape selected: show none, do not fall back to the worn one.
            return;
        }
        boolean quickskin$previewing =
                quickskin$preview.decision() == PreviewCapeBindings.Decision.PREVIEW;

//? if <1.21.11 {
//?} else {
        // Don't render cape when elytra is equipped. Read that off the render state rather than the
        // live entity: in the world the state carries exactly what the player has on, so the rule is
        // unchanged, while in a preview the renderer has already blanked the state's equipment, so
        // the cape being previewed is never hidden by gear the preview is not drawing either.
        if (!quickskin$previewing && renderState.chestEquipment.is(Items.ELYTRA)) {
            ci.cancel();
            return;
        }

        // Look up the actual player entity from the render state to get UUID
        UUID playerUUID = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity entity = mc.level.getEntity(renderState.id);
            if (entity instanceof AbstractClientPlayer player) {
                playerUUID = player.getUUID();
            }
        }

        if (playerUUID == null && !quickskin$previewing) {
            return; // Can't identify player, let vanilla logic run
        }

//?}
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
//? if <1.21 {
        if (!quickskin$previewing && !service.hasActiveCape(player.getUUID())) {
//?} else if <1.21.11 {

        // Throttled debug logging

        if (!quickskin$previewing && !service.hasActiveCape(player.getUUID())) {
//?} else {

        if (!quickskin$previewing && !service.hasActiveCape(playerUUID)) {
//?}
            return; // No custom cape, let vanilla logic run
        }

//? if <1.21.11 {
        String capeId = quickskin$previewing ? null : service.getCapeId(player.getUUID());
//?} else {
        String capeId = quickskin$previewing ? null : service.getCapeId(playerUUID);
//?}
        if (capeId != null) {
            CapeAnimationHelper.markCapeVisible(capeId);
        }

//? if <1.21 {
        // A bound preview replaces the worn cape outright; otherwise resolve the applied one.
        ResourceLocation capeTexture = quickskin$previewing
                ? quickskin$preview.texture()
                : player.getCloakTextureLocation();
//?} else if <1.21.11 {
        // Get cape texture from our service instead of player.getSkin().capeTexture(),
        // because some mods (e.g. Essential) override getSkin() in a subclass,
        // bypassing our MixinAbstractClientPlayer that sets the correct cape texture.
        // A bound preview replaces the worn cape outright.
        ResourceLocation capeTexture = quickskin$previewing
                ? quickskin$preview.texture()
                : service.getCapeLocation(player.getUUID());
        if (capeTexture == null && !quickskin$previewing) {
            // Fallback to config-based lookup for title screen
            if (Minecraft.getInstance().level == null) {
                ClientConfig config = ClientConfig.getInstance();
                if (!config.activeCapeHash.isEmpty()) {
                    capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                            .getCapeLocation(null, config.activeCapeHash);
                }
            }
        }
//?} else {
        // Get cape texture from our service instead of renderState.skin cape,
        // because some mods (e.g. Essential) override getSkin() in a subclass,
        // bypassing our MixinAbstractClientPlayer that sets the correct cape texture.
        // A bound preview replaces the worn cape outright.
        Identifier capeTexture = quickskin$previewing
                ? quickskin$preview.texture()
                : service.getCapeLocation(playerUUID);
        if (capeTexture == null && !quickskin$previewing) {
            // Fallback to config-based lookup for title screen
            if (mc.level == null) {
                ClientConfig config = ClientConfig.getInstance();
                if (!config.activeCapeHash.isEmpty()) {
                    capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                            .getCapeLocation(null, config.activeCapeHash);
                }
            }
        }
//?}

        if (capeTexture == null) {
            ci.cancel(); // Don't render anything if QuickSkin wants to hide the cape
            return;
        }

//? if <1.21.11 {
        if (!quickskin$previewing && player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            ci.cancel();
            return;
        }

//?} else {
//?}
        // Check if this cape is animated. If so, get the current frame texture.
//? if <1.21.11 {
        ResourceLocation finalTexture =
                CapeAnimationHelper.resolveCurrentFrame(capeTexture, capeId);
//?} else {
        Identifier finalTexture =
                CapeAnimationHelper.resolveCurrentFrame(capeTexture, capeId);
//?}

        if (finalTexture == null) {
            ci.cancel();
            return;
        }

        RenderType renderType;

        // If the texture is from our mod (local, network, animated, or known),
        // always use the translucent render type to correctly handle transparency.
        if (finalTexture.getNamespace().equals(QuickSkinInfo.MOD_ID)) {
//? if <1.21.11 {
            renderType = RenderType.entityTranslucentCull(finalTexture);
//?} else {
            renderType = RenderTypes.entityTranslucent(finalTexture);
//?}
        } else {
            // For vanilla capes or capes from other mods, use the alpha detector.
            boolean hasTransparency = TextureAlphaDetector.hasTransparency(finalTexture);
            if (hasTransparency) {
//? if <1.21.11 {
                renderType = RenderType.entityTranslucentCull(finalTexture);
//?} else {
                renderType = RenderTypes.entityTranslucent(finalTexture);
//?}
            } else {
//? if <1.21.11 {
                renderType = RenderType.entitySolid(finalTexture);
//?} else {
                renderType = RenderTypes.entitySolid(finalTexture);
//?}
            }
        }

//? if <1.21.11 {
        VertexConsumer vertexconsumer = buffer.getBuffer(renderType);

//?} else {
//?}
        // Replicate the vanilla cape rendering logic with our custom render type
//? if <1.21.11 {
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 0.125D);
        double d0 = Mth.lerp(partialTicks, player.xCloakO, player.xCloak) - Mth.lerp(partialTicks, player.xo, player.getX());
        double d1 = Mth.lerp(partialTicks, player.yCloakO, player.yCloak) - Mth.lerp(partialTicks, player.yo, player.getY());
        double d2 = Mth.lerp(partialTicks, player.zCloakO, player.zCloak) - Mth.lerp(partialTicks, player.zo, player.getZ());
        float f = player.yBodyRotO + (player.yBodyRot - player.yBodyRotO);
        double d3 = Mth.sin(f * ((float)Math.PI / 180F));
        double d4 = -Mth.cos(f * ((float)Math.PI / 180F));
        float f1 = (float)d1 * 10.0F;
        f1 = Mth.clamp(f1, -6.0F, 32.0F);
        float f2 = (float)(d0 * d3 + d2 * d4) * 100.0F;
        f2 = Mth.clamp(f2, 0.0F, 150.0F);
        float f3 = (float)(d0 * d4 - d2 * d3) * 100.0F;
        f3 = Mth.clamp(f3, -20.0F, 20.0F);
        if (f2 < 0.0F) {
            f2 = 0.0F;
        }
//?} else {
        // In MC 1.21.11, CapeLayer uses SubmitNodeCollector.submitModel() instead of renderToBuffer()
        @SuppressWarnings("unchecked")
        HumanoidModel<AvatarRenderState> capeModel = (HumanoidModel<AvatarRenderState>) (HumanoidModel<?>) this.model;
        capeModel.setupAnim(renderState);
//?}

//? if <1.21.11 {
        float f4 = Mth.lerp(partialTicks, player.oBob, player.bob);
        f1 += Mth.sin(Mth.lerp(partialTicks, player.walkDistO, player.walkDist) * 6.0F) * 32.0F * f4;
        if (player.isCrouching()) {
            f1 += 25.0F;
        }

        poseStack.mulPose(Axis.XP.rotationDegrees(6.0F + f2 / 2.0F + f1));
        poseStack.mulPose(Axis.ZP.rotationDegrees(f3 / 2.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - f3 / 2.0F));

        // Render the cloak part of the model
        ((CapeLayer)(Object)this).getParentModel().renderCloak(poseStack, vertexconsumer, packedLight, OverlayTexture.NO_OVERLAY);

        poseStack.popPose();
//?} else {
        // Submit the cape model with our custom render type
        buffer.submitModel(capeModel, renderState, poseStack, renderType, packedLight,
                OverlayTexture.NO_OVERLAY, renderState.outlineColor, null);
//?}

        // Cancel the original vanilla method to prevent it from rendering a second time
        ci.cancel();
    }
}
//?}
