package com.quickskin.mod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
//? if <1.21.9 {
import com.mojang.blaze3d.vertex.VertexConsumer;
//?} else {
//?}
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.model.geom.ModelPart;
//? if <1.21.2 {
import net.minecraft.client.player.AbstractClientPlayer;
//?}
//? if <1.21.9 {
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Enables transparent first-person arms on the legacy immediate-rendering pipeline.
 * Minecraft 1.21.9 and later already submit the hand with entityTranslucent; intercepting its
 * collector would bypass wrappers installed by model mods such as CPM.
 */
//? if <1.21.9 {
@Mixin(value = PlayerRenderer.class, priority = 1100) // Higher priority to override TLSkinCape and other mods
//?} else {
@Mixin(value = AvatarRenderer.class, priority = 1100)
//?}
public class ItemInHandRendererMixin {

//? if <1.21.9 {
    /** Redirects the legacy first-person arm buffers when the selected skin needs translucency. */
    @Redirect(
//? if <1.21.2 {
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V",
//?} else {
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
//?}
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            ),
//? if <1.21.2 {
            // Vanilla requests one buffer for the arm and one for the sleeve.
            expect = 2,
            allow = 2,
//?} else {
            // The newer immediate renderer performs exactly one arm draw.
            expect = 1,
            allow = 1,
//?}
            require = 1
    )
//? if <1.21.2 {
    private VertexConsumer quickskin$redirectRenderHandBuffer(MultiBufferSource instance, RenderType renderType,
                                                              // Injected arguments from renderHand:
                                                              PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve) {
//?} else {
    private VertexConsumer quickskin$redirectRenderHandBuffer(MultiBufferSource instance, RenderType renderType,
                                                              // Injected arguments from renderHand:
                                                              PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                                              ResourceLocation skinTexture, ModelPart arm, boolean slim) {
//?}
        if (CPMCompatIntegration.shouldDeferToCPM()) return instance.getBuffer(renderType);

        // When CPM has a bound player, it manages the texture pipeline and already converts
        // entitySolid→entityTranslucent when needed. Overriding the RenderType here would
        // use a different ResourceLocation (quickskin:skins/hash vs CPM's cpm:cpm_X),
        // causing first-person arm texture artifacts.
        if (CPMCompatIntegration.isCPMActivelyRendering()) return instance.getBuffer(renderType);

        // Check if transparency is disabled globally by config
        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return instance.getBuffer(renderType);
        }

//? if <1.21 {
        ResourceLocation skinTexture = player.getSkinTextureLocation();
//?} else if <1.21.2 {
        ResourceLocation skinTexture = player.getSkin().texture();
//?}
        if (skinTexture == null) {
            return instance.getBuffer(renderType);
        }

        // Determine if the skin needs a translucent render type
        boolean needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);

        if (needsTranslucent) {
            // The oldest renderer calls getBuffer for both the solid arm and translucent sleeve;
            // newer immediate renderers make one arm draw. Forcing entityTranslucent renders the
            // arm correctly and is harmless for the older sleeve, which is already translucent.
            // We use entityTranslucent instead of entityTranslucentCull to avoid z-fighting on complex layers.
            return instance.getBuffer(RenderType.entityTranslucent(skinTexture));
        }

        // If no transparency is needed, use the original render type provided by the vanilla method.
        return instance.getBuffer(renderType);
    }
//?}
}
