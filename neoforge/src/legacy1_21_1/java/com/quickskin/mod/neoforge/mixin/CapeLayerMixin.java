package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.platform.QuickSkinInfo;
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
            renderType = RenderType.entityTranslucentCull(finalTexture);
        } else {
            boolean hasTransparency = TextureAlphaDetector.hasTransparency(finalTexture);
            if (hasTransparency) {
                renderType = RenderType.entityTranslucentCull(finalTexture);
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
