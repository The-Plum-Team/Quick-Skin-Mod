//? if <1.21.2 {
package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.platform.CapeRenderTypes;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewCapeBindings;
import com.quickskin.mod.client.services.CapeAnimationHelper;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CapeLayer.class, priority = 1100)
public class CapeLayerMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1)
    private void quickskin$renderCustomCape(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                            AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                            CallbackInfo ci) {
        // The GUI preview renders the real player entity, so without this the cape below would be
        // the one the player is wearing rather than the one the editor has selected. The preview
        // binds its cape to this draw only; an unbound draw keeps resolving the applied cape.
        PreviewCapeBindings.Resolution<ResourceLocation> quickskin$preview =
                PlayerModelRenderer.consumePreviewCape(player);
        if (quickskin$preview.decision() == PreviewCapeBindings.Decision.HIDDEN) {
            ci.cancel(); // The editor has no cape selected: show none, do not fall back to the worn one.
            return;
        }
        boolean quickskin$previewing =
                quickskin$preview.decision() == PreviewCapeBindings.Decision.PREVIEW;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Check service-based cape
        boolean hasServiceCape = !quickskin$previewing && service.hasActiveCape(player.getUUID());

        // Check config-based cape for local player (works both title screen and in-world)
        boolean hasConfigCape = false;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        boolean isLocalPlayer = mc.player != null && player.getUUID().equals(mc.player.getUUID());
        if (!quickskin$previewing && !hasServiceCape && isLocalPlayer) {
            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            hasConfigCape = !config.activeCapeHash.isEmpty();
        }

        if (!quickskin$previewing && !hasServiceCape && !hasConfigCape) {
            return; // No cape from either source, let vanilla handle
        }

        String capeId = quickskin$previewing ? null : service.getCapeId(player.getUUID());
        // Visibility must be recorded before the getter can return null while a bounded network
        // first-frame texture is being prepared; this also drives bounded activation retry.
        if (capeId != null) {
            CapeAnimationHelper.markCapeVisible(capeId);
        }

        // A bound preview replaces the worn cape outright.
        ResourceLocation capeTexture = quickskin$previewing ? quickskin$preview.texture() : null;
        if (!quickskin$previewing) {
            if (hasServiceCape) {
                capeTexture = player.getSkin().capeTexture();
            }
            if (capeTexture == null && isLocalPlayer) {
                com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
                if (!config.activeCapeHash.isEmpty()) {
                    capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                            .getCapeLocation(null, config.activeCapeHash);
                }
            }
            if (capeTexture == null) {
                capeTexture = player.getSkin().capeTexture();
            }
        }

        if (capeTexture == null) {
            ci.cancel();
            return;
        }

        if (!quickskin$previewing && player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            ci.cancel();
            return;
        }

        ResourceLocation finalTexture = CapeAnimationHelper.resolveCurrentFrame(capeTexture, capeId);
        if (finalTexture == null) {
            // A network animation deliberately renders nothing until its bounded first-frame
            // texture is ready; never expose the stacked atlas as a cape.
            ci.cancel();
            return;
        }

        RenderType renderType;

        if (finalTexture.getNamespace().equals(QuickSkinInfo.MOD_ID)) {
            renderType = CapeRenderTypes.translucent(finalTexture);
        } else {
            boolean hasTransparency = TextureAlphaDetector.hasTransparency(finalTexture);
            if (hasTransparency) {
                renderType = CapeRenderTypes.translucent(finalTexture);
            } else {
                renderType = RenderType.entitySolid(finalTexture);
            }
        }

        VertexConsumer vertexconsumer = buffer.getBuffer(renderType);

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

        float f4 = Mth.lerp(partialTicks, player.oBob, player.bob);
        f1 += Mth.sin(Mth.lerp(partialTicks, player.walkDistO, player.walkDist) * 6.0F) * 32.0F * f4;
        if (player.isCrouching()) {
            f1 += 25.0F;
        }

        poseStack.mulPose(Axis.XP.rotationDegrees(6.0F + f2 / 2.0F + f1));
        poseStack.mulPose(Axis.ZP.rotationDegrees(f3 / 2.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - f3 / 2.0F));

        ((CapeLayer)(Object)this).getParentModel().renderCloak(poseStack, vertexconsumer, packedLight, OverlayTexture.NO_OVERLAY);

        poseStack.popPose();

        ci.cancel();
    }
}
//?} else if <1.21.6 {
package com.quickskin.mod.neoforge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.platform.CapeRenderTypes;
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
            ci.cancel();
            return;
        }

        RenderType renderType = QuickSkinInfo.MOD_ID.equals(finalTexture.getNamespace())
                || TextureAlphaDetector.hasTransparency(finalTexture)
                ? CapeRenderTypes.translucent(finalTexture)
                : RenderType.entitySolid(finalTexture);
        VertexConsumer vertices = buffer.getBuffer(renderType);

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
package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.platform.CapeRenderTypes;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewCapeBindings;
import com.quickskin.mod.client.services.CapeAnimationHelper;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
//? if <1.21.9 {
import com.mojang.blaze3d.vertex.VertexConsumer;
//?}
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
//? if <1.21.11 {
import net.minecraft.client.renderer.RenderType;
//?} else {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?}
//? if <1.21.9 {
import net.minecraft.client.renderer.MultiBufferSource;
//?} else {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?}
import net.minecraft.client.renderer.entity.layers.CapeLayer;
//? if <1.21.9 {
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
//?} else {
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?}
import net.minecraft.client.renderer.texture.OverlayTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = CapeLayer.class, priority = 1100)
public class CapeLayerMixin {

    // In MC 1.21.4+, CapeLayer has its own cape model (PlayerCapeModel) separate from PlayerModel.
    @Shadow @Final private HumanoidModel<?> model;

//? if <1.21.9 {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V",
//?} else {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
//?}
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1)
//? if <1.21.9 {
    private void quickskin$renderCustomCape(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                            PlayerRenderState renderState, float yRot, float xRot,
//?} else {
    private void quickskin$renderCustomCape(PoseStack poseStack, SubmitNodeCollector buffer, int packedLight,
                                            AvatarRenderState renderState, float yRot, float xRot,
//?}
                                            CallbackInfo ci) {
        // The GUI preview renders the real player entity, so without this the cape below would be
        // the one the player is wearing rather than the one the editor has selected. The preview
        // binds its cape to this draw only; an unbound draw keeps resolving the applied cape.
//? if <1.21.11 {
        PreviewCapeBindings.Resolution<ResourceLocation> quickskin$preview =
//?} else {
        PreviewCapeBindings.Resolution<Identifier> quickskin$preview =
//?}
                PlayerModelRenderer.consumePreviewCape(renderState);
        if (quickskin$preview.decision() == PreviewCapeBindings.Decision.HIDDEN) {
            ci.cancel(); // The editor has no cape selected: show none, do not fall back to the worn one.
            return;
        }
        boolean quickskin$previewing =
                quickskin$preview.decision() == PreviewCapeBindings.Decision.PREVIEW;

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

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Check service-based cape
        boolean hasServiceCape = !quickskin$previewing && service.hasActiveCape(playerUUID);

        // Check config-based cape for local player (works both title screen and in-world)
        boolean hasConfigCape = false;
        boolean isLocalPlayer = mc.player != null && mc.player.getUUID().equals(playerUUID);
        if (!quickskin$previewing && !hasServiceCape && isLocalPlayer) {
            ClientConfig config = ClientConfig.getInstance();
            hasConfigCape = !config.activeCapeHash.isEmpty();
        }

        if (!quickskin$previewing && !hasServiceCape && !hasConfigCape) {
            return; // No cape from either source, let vanilla handle
        }

        String capeId = quickskin$previewing ? null : service.getCapeId(playerUUID);
        // Visibility must be recorded before the getter can return null while a bounded network
        // first-frame texture is being prepared; this also drives bounded activation retry.
        if (capeId != null) {
            CapeAnimationHelper.markCapeVisible(capeId);
        }

        // A bound preview replaces the worn cape outright.
//? if <1.21.11 {
        ResourceLocation capeTexture = quickskin$previewing ? quickskin$preview.texture() : null;
//?} else {
        Identifier capeTexture = quickskin$previewing ? quickskin$preview.texture() : null;
//?}
        if (!quickskin$previewing) {
            if (hasServiceCape) {
                capeTexture = service.getCapeLocation(playerUUID);
            }
            if (capeTexture == null && isLocalPlayer) {
                ClientConfig config = ClientConfig.getInstance();
                if (!config.activeCapeHash.isEmpty()) {
                    capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                            .getCapeLocation(null, config.activeCapeHash);
                }
            }
            if (capeTexture == null) {
                // Fall back to render state's skin cape
//? if <1.21.9 {
                if (renderState.skin != null) {
                    capeTexture = renderState.skin.capeTexture();
                }
//?} else {
                if (renderState.skin != null && renderState.skin.cape() != null) {
                    capeTexture = renderState.skin.cape().texturePath();
                }
//?}
            }
        }

        if (capeTexture == null) {
            ci.cancel();
            return;
        }

//? if <1.21.11 {
        ResourceLocation finalTexture = CapeAnimationHelper.resolveCurrentFrame(capeTexture, capeId);
//?} else {
        Identifier finalTexture = CapeAnimationHelper.resolveCurrentFrame(capeTexture, capeId);
//?}
        if (finalTexture == null) {
            // A network animation deliberately renders nothing until its bounded first-frame
            // texture is ready; never expose the stacked atlas as a cape.
            ci.cancel();
            return;
        }

        RenderType renderType;

        if (finalTexture.getNamespace().equals(QuickSkinInfo.MOD_ID)) {
            renderType = CapeRenderTypes.translucent(finalTexture);
        } else {
            boolean hasTransparency = TextureAlphaDetector.hasTransparency(finalTexture);
            if (hasTransparency) {
                renderType = CapeRenderTypes.translucent(finalTexture);
            } else {
//? if <1.21.11 {
                renderType = RenderType.entitySolid(finalTexture);
//?} else {
                renderType = RenderTypes.entitySolid(finalTexture);
//?}
            }
        }

        // Replicate the vanilla cape rendering logic with our custom render type.
        @SuppressWarnings("unchecked")
//? if <1.21.9 {
        HumanoidModel<PlayerRenderState> capeModel =
                (HumanoidModel<PlayerRenderState>) (HumanoidModel<?>) this.model;
        ((CapeLayer)(Object)this).getParentModel().copyPropertiesTo(capeModel);
        capeModel.setupAnim(renderState);

        VertexConsumer vertexConsumer = buffer.getBuffer(renderType);
        capeModel.renderToBuffer(poseStack, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY);
//?} else {
        HumanoidModel<AvatarRenderState> capeModel = (HumanoidModel<AvatarRenderState>) (HumanoidModel<?>) this.model;
        capeModel.setupAnim(renderState);

        // Submit the cape model with our custom render type
        buffer.submitModel(capeModel, renderState, poseStack, renderType, packedLight,
                OverlayTexture.NO_OVERLAY, renderState.outlineColor, null);
//?}

        ci.cancel();
    }
}
//?}
