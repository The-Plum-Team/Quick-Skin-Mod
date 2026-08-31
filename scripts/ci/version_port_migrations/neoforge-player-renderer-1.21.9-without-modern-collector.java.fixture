package com.quickskin.mod.neoforge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
//? if <1.21.9 {
import com.mojang.blaze3d.vertex.VertexConsumer;
//?} else {
//?}
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
//? if <1.21.9 {
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
    //? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
    //?} else {
import net.minecraft.resources.Identifier;
    //?}
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if >=1.21.9 {
import java.util.UUID;
//?}

/**
 * Tracks rendered skins on NeoForge and enables transparent first-person arms on its legacy
 * immediate-rendering pipeline. Minecraft 1.21.9 and later already use entityTranslucent, so the
 * collector remains untouched for model-mod wrappers such as CPM.
 */
//? if <1.21.9 {
@Mixin(value = PlayerRenderer.class, priority = 1100)
//?} else {
@Mixin(value = AvatarRenderer.class, priority = 1100)
//?}
public class PlayerRendererMixin {

    /** Marks only an actual world-render lookup as active client working-set use. */
//? if <1.21.9 {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;",
//?} else if <1.21.11 {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/resources/ResourceLocation;",
//?} else {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/resources/Identifier;",
//?}
            at = @At("HEAD"),
            require = 0,
            expect = 1,
            allow = 1)
//? if <1.21.9 {
    private void quickskin$markRenderedSkin(
            AbstractClientPlayer player, CallbackInfoReturnable<ResourceLocation> cir) {
        PlayerAppearanceService.getInstance().markSkinVisible(player.getUUID());
//?} else if <1.21.11 {
    private void quickskin$markRenderedSkin(
            AvatarRenderState renderState, CallbackInfoReturnable<ResourceLocation> cir) {
        UUID playerId = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(renderState.id);
            if (entity instanceof net.minecraft.client.player.AbstractClientPlayer player) {
                playerId = player.getUUID();
            }
        }
        if (playerId != null) {
            PlayerAppearanceService.getInstance().markSkinVisible(playerId);
        }
//?} else {
    private void quickskin$markRenderedSkin(
            AvatarRenderState renderState, CallbackInfoReturnable<Identifier> cir) {
        UUID playerId = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(renderState.id);
            if (entity instanceof net.minecraft.client.player.AbstractClientPlayer player) {
                playerId = player.getUUID();
            }
        }
        if (playerId != null) {
            PlayerAppearanceService.getInstance().markSkinVisible(playerId);
        }
//?}
    }

//? if <1.21.9 {
    /** Redirects the legacy first-person arm buffers when the selected skin needs translucency. */
    @Redirect(
            method = "renderHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            ),
            require = 0,
            expect = 2,
            allow = 2
    )
    private VertexConsumer quickskin$redirectRenderHandBuffer(MultiBufferSource instance, RenderType renderType,
                                                              PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve) {
        // When CPM has a bound player, it manages the texture pipeline and already converts
        // entitySolid to entityTranslucent when needed. Overriding the RenderType here would
        // use a different ResourceLocation, causing first-person arm texture artifacts.
        if (CPMCompatIntegration.shouldPreserveFirstPersonHandRenderType()) {
            return instance.getBuffer(renderType);
        }

        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return instance.getBuffer(renderType);
        }

        ResourceLocation skinTexture = player.getSkin().texture();
        if (skinTexture == null) {
            return instance.getBuffer(renderType);
        }

        boolean needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);

        if (needsTranslucent) {
            return instance.getBuffer(RenderType.entityTranslucent(skinTexture));
        }

        return instance.getBuffer(renderType);
    }
//?}
}
