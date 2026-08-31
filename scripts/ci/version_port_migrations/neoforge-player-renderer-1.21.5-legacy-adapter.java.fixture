package com.quickskin.mod.neoforge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** NeoForge Minecraft 1.21.5 renderer adapter. */
@Mixin(value = PlayerRenderer.class, priority = 1100)
public class PlayerRendererMixin {

    @Inject(
            method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"),
            require = 1,
            expect = 1,
            allow = 1)
    private void quickskin$markRenderedSkin(PlayerRenderState renderState,
                                            CallbackInfoReturnable<ResourceLocation> cir) {
        UUID playerId = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(renderState.id);
            if (entity instanceof AbstractClientPlayer player) {
                playerId = player.getUUID();
            }
        }
        if (playerId != null) {
            PlayerAppearanceService.getInstance().markSkinVisible(playerId);
        }
    }

    @Redirect(
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 1,
            expect = 1,
            allow = 1)
    private VertexConsumer quickskin$redirectRenderHandBuffer(MultiBufferSource instance,
                                                              RenderType originalRenderType,
                                                              PoseStack poseStack,
                                                              MultiBufferSource buffer,
                                                              int packedLight,
                                                              ResourceLocation skinTexture,
                                                              ModelPart arm,
                                                              boolean slim) {
        if (CPMCompatIntegration.shouldDeferToCPM()
                || CPMCompatIntegration.isCPMActivelyRendering()
                || ClientConfig.getInstance().shouldDisableSkinTransparency()
                || skinTexture == null) {
            return instance.getBuffer(originalRenderType);
        }

        boolean needsTranslucent = QuickSkin.MOD_ID.equals(skinTexture.getNamespace())
                || TextureAlphaDetector.hasTransparency(skinTexture);
        return instance.getBuffer(needsTranslucent
                ? RenderType.entityTranslucent(skinTexture)
                : originalRenderType);
    }
}
