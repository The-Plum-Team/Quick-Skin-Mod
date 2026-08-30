package com.quickskin.mod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
//? if <1.21.11 {
import com.mojang.blaze3d.vertex.VertexConsumer;
//?} else {
//?}
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.model.geom.ModelPart;
//? if <1.21.11 {
import net.minecraft.client.player.AbstractClientPlayer;
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
 * Mixin to enable transparent arm rendering in first-person view.
 * This mixin targets the era-specific player/avatar renderer responsible for the arm model.
 * It redirects the texture-buffer lookup (or deferred model submission) to use a translucent
 * render type for skins with transparency.
 *
 * In MC 1.21.11, the vanilla renderHand already uses entityTranslucent by default, so the
 * modern branch is mostly a safety net: it defers to wrappers installed by model mods such as
 * CPM instead of replacing the render type they selected.
 */
//? if <1.21.11 {
@Mixin(value = PlayerRenderer.class, priority = 1100) // Higher priority to override TLSkinCape and other mods
//?} else {
@Mixin(value = AvatarRenderer.class, priority = 1100)
//?}
public class ItemInHandRendererMixin {

    /**
     * Redirects the relevant draw call within the private renderHand method.
     * This allows us to ensure entityTranslucent is used when the player's skin has
     * transparent pixels.
     */
    @Redirect(
//? if <1.21.4 {
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V",
//?} else if <1.21.11 {
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
//?} else {
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
//?}
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            ),
            require = 1,
//? if <1.21.4 {
            // Vanilla requests one buffer for the arm and one for the sleeve.
            expect = 2,
            allow = 2
//?} else {
            // Minecraft 1.21.4 and later make a single arm draw call.
            expect = 1,
            allow = 1
//?}
    )
//? if <1.21.4 {
    private VertexConsumer quickskin$redirectRenderHandBuffer(MultiBufferSource instance, RenderType renderType,
                                                              // Injected arguments from renderHand:
                                                              PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return instance.getBuffer(renderType);

        // When CPM has a bound player, it manages the texture pipeline and already converts
        // entitySolid→entityTranslucent when needed. Overriding the RenderType here would
        // use a different ResourceLocation (quickskin:skins/hash vs CPM's cpm:cpm_X),
        // causing first-person arm texture artifacts.
        if (CPMCompatIntegration.isCPMActivelyRendering()) return instance.getBuffer(renderType);

        // Check if transparency is disabled globally by config
        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return instance.getBuffer(renderType);
//?} else if <1.21.11 {
    private VertexConsumer quickskin$redirectRenderHandBuffer(MultiBufferSource instance, RenderType renderType,
                                                              // Injected arguments from renderHand:
                                                              PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                                              ResourceLocation skinTexture, ModelPart arm, boolean slim) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return instance.getBuffer(renderType);
        if (CPMCompatIntegration.isCPMActivelyRendering()) return instance.getBuffer(renderType);

        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return instance.getBuffer(renderType);
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
//?}
        }

//? if <1.21 {
        ResourceLocation skinTexture = player.getSkinTextureLocation();
//?} else if <1.21.4 {
        ResourceLocation skinTexture = player.getSkin().texture();
//?} else if <1.21.11 {
//?} else {
        ResourceLocation skinTexture = player.getSkin().texture();
//?}
        if (skinTexture == null) {
            return instance.getBuffer(renderType);
        }

        // Determine if the skin needs a translucent render type
        boolean needsTranslucent = QuickSkin.MOD_ID.equals(skinTexture.getNamespace())
                || TextureAlphaDetector.hasTransparency(skinTexture);

        if (needsTranslucent) {
//? if <1.21.4 {
            // The vanilla method calls getBuffer for both the solid arm and the translucent sleeve.
            // By forcing entityTranslucent here, we correctly render the arm with transparency.
            // It's harmless to also request a translucent buffer for the sleeve, which already uses it.
//?} else if <1.21.11 {
            // Minecraft 1.21.4-1.21.10 performs one getBuffer lookup for this arm draw.
            // Force entityTranslucent so transparent body pixels remain visible.
//?}
//? if <1.21.11 {
            // We use entityTranslucent instead of entityTranslucentCull to avoid z-fighting on complex layers.
            return instance.getBuffer(RenderType.entityTranslucent(skinTexture));
        }

        // If no transparency is needed, use the original render type provided by the vanilla method.
        return instance.getBuffer(renderType);
    }
//?}
}
