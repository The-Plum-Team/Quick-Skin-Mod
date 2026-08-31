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
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.Entity;
    //? if <1.21.11 {
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
    //?} else {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
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
 * NeoForge-specific mixin to enable transparent arm rendering in first-person view.
 *
 * In 1.21.9 the immediate MultiBufferSource was removed and AvatarRenderer.renderHand submits the arm
 * via SubmitNodeCollector.submitModelPart(...). We redirect that submit to force entityTranslucent
 * when the player's skin has transparent pixels (mirrors the common ItemInHandRendererMixin).
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

    /**
     * Redirects the getBuffer call within AvatarRenderer's renderHand method.
     * This allows us to switch from RenderTypes.entitySolid to RenderTypes.entityTranslucent
     * when the player's skin has transparent pixels.
     */
    @Redirect(
//? if <1.21.9 {
            method = "renderHand",
//?} else if <1.21.11 {
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
//?} else {
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
//?}
            at = @At(
                    value = "INVOKE",
//? if <1.21.9 {
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
//?} else if <1.21.11 {
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"
//?} else {
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"
//?}
            ),
            require = 0,
//? if <1.21.9 {
            expect = 2,
            allow = 2
//?} else {
            expect = 1,
            allow = 1
//?}
    )
//? if <1.21.9 {
    private VertexConsumer quickskin$redirectRenderHandBuffer(MultiBufferSource instance, RenderType renderType,
                                                              PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return instance.getBuffer(renderType);

        // When CPM has a bound player, it manages the texture pipeline and already converts
        // entitySolidâ†’entityTranslucent when needed. Overriding the RenderType here would
        // use a different ResourceLocation, causing first-person arm texture artifacts.
        if (CPMCompatIntegration.isCPMActivelyRendering()) return instance.getBuffer(renderType);

        // Check if transparency is disabled globally by config
        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return instance.getBuffer(renderType);
        }
//?} else if <1.21.11 {
    private void quickskin$redirectSubmitModelPart(SubmitNodeCollector collector, ModelPart part,
                                                    PoseStack poseStack, RenderType renderType,
                                                    int packedLight, int overlay, TextureAtlasSprite sprite,
                                                    // Injected arguments from renderHand:
                                                    PoseStack poseStackOuter, SubmitNodeCollector bufferOuter,
                                                    int packedLightOuter, ResourceLocation skinTexture,
                                                    ModelPart arm, boolean slim) {
        if (CPMCompatIntegration.shouldDeferToCPM()) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }
//?} else {
    private void quickskin$redirectSubmitModelPart(SubmitNodeCollector collector, ModelPart part,
                                                    PoseStack poseStack, RenderType renderType,
                                                    int packedLight, int overlay, TextureAtlasSprite sprite,
                                                    // Injected arguments from renderHand:
                                                    PoseStack poseStackOuter, SubmitNodeCollector bufferOuter,
                                                    int packedLightOuter, Identifier skinTexture,
                                                    ModelPart arm, boolean slim) {
        if (CPMCompatIntegration.shouldDeferToCPM()) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }
//?}

//? if <1.21.9 {
        ResourceLocation skinTexture = player.getSkin().texture();
        if (skinTexture == null) {
            return instance.getBuffer(renderType);
        }

        // Determine if the skin needs a translucent render type
//?} else {
        if (CPMCompatIntegration.isCPMActivelyRendering()) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }

        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }

        if (skinTexture == null) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }

//?}
        boolean needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);

        if (needsTranslucent) {
//? if <1.21.9 {
            // The vanilla method calls getBuffer for both the solid arm and the translucent sleeve.
            // By forcing entityTranslucent here, we correctly render the arm with transparency.
            return instance.getBuffer(RenderType.entityTranslucent(skinTexture));
//?} else if <1.21.11 {
            RenderType translucentType = RenderType.entityTranslucent(skinTexture);
            collector.submitModelPart(part, poseStack, translucentType, packedLight, overlay, sprite);
        } else {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
//?} else {
            RenderType translucentType = RenderTypes.entityTranslucent(skinTexture);
            collector.submitModelPart(part, poseStack, translucentType, packedLight, overlay, sprite);
        } else {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
//?}
        }
//? if <1.21.9 {

        // If no transparency is needed, use the original render type provided by the vanilla method.
        return instance.getBuffer(renderType);
//?} else {
//?}
    }
}
