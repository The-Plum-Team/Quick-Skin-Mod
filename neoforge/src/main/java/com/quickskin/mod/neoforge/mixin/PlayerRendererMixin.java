//? if >=1.21.2 && <1.21.6 {
package com.quickskin.mod.neoforge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.platform.QuickSkinInfo;
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

/** Minecraft API-family renderer adapter. */
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
        if (CPMCompatIntegration.shouldPreserveFirstPersonHandRenderType()
                || ClientConfig.getInstance().shouldDisableSkinTransparency()
                || skinTexture == null) {
            return instance.getBuffer(originalRenderType);
        }

        boolean needsTranslucent = QuickSkinInfo.MOD_ID.equals(skinTexture.getNamespace())
                || TextureAlphaDetector.hasTransparency(skinTexture);
        return instance.getBuffer(needsTranslucent
                ? RenderType.entityTranslucent(skinTexture)
                : originalRenderType);
    }
}
//?} else {
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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
    //? if <1.21.6 {
import net.minecraft.client.player.AbstractClientPlayer;
    //?} else {
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.Entity;
    //?}
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

//? if >=1.21.6 {
import java.util.UUID;
//?}

/**
 * NeoForge-specific mixin to enable transparent arm rendering in first-person view.
 *
 * The immediate renderer needs the transparent arm buffer bridge. From 1.21.9 vanilla already
 * submits the arm with entityTranslucent; retain only the appearance visibility hook for that family.
 */
//? if <1.21.9 {
@Mixin(value = PlayerRenderer.class, priority = 1100)
//?} else {
@Mixin(value = AvatarRenderer.class, priority = 1100)
//?}
public class PlayerRendererMixin {

    /** Marks only an actual world-render lookup as active client working-set use. */
//? if <1.21.6 {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;",
//?} else if <1.21.9 {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)Lnet/minecraft/resources/ResourceLocation;",
//?} else if <1.21.11 {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/resources/ResourceLocation;",
//?} else {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/resources/Identifier;",
//?}
            at = @At("HEAD"),
            require = 0,
            expect = 1,
            allow = 1)
//? if <1.21.6 {
    private void quickskin$markRenderedSkin(
            AbstractClientPlayer player, CallbackInfoReturnable<ResourceLocation> cir) {
        PlayerAppearanceService.getInstance().markSkinVisible(player.getUUID());
//?} else if <1.21.9 {
    private void quickskin$markRenderedSkin(
            PlayerRenderState renderState, CallbackInfoReturnable<ResourceLocation> cir) {
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

//? if <1.21.2 {
    /**
     * Redirects the getBuffer call within AvatarRenderer's renderHand method.
     * This allows us to switch from RenderTypes.entitySolid to RenderTypes.entityTranslucent
     * when the player's skin has transparent pixels.
     */
    @Redirect(
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V",
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
        // entitySolidâ†’entityTranslucent when needed. Overriding the RenderType here would
        // use a different ResourceLocation, causing first-person arm texture artifacts.
        if (CPMCompatIntegration.shouldPreserveFirstPersonHandRenderType()) {
            return instance.getBuffer(renderType);
        }

        // Check if transparency is disabled globally by config
        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return instance.getBuffer(renderType);
        }

        ResourceLocation skinTexture = player.getSkin().texture();
        if (skinTexture == null) {
            return instance.getBuffer(renderType);
        }

        // Determine if the skin needs a translucent render type
        boolean needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);

        if (needsTranslucent) {
            // The vanilla method calls getBuffer for both the solid arm and the translucent sleeve.
            // By forcing entityTranslucent here, we correctly render the arm with transparency.
            return instance.getBuffer(RenderType.entityTranslucent(skinTexture));
        }

        // If no transparency is needed, use the original render type provided by the vanilla method.
        return instance.getBuffer(renderType);
    }
//?} else if <1.21.9 {
    /**
     * Redirects the getBuffer call within AvatarRenderer's renderHand method.
     * This allows us to switch from RenderTypes.entitySolid to RenderTypes.entityTranslucent
     * when the player's skin has transparent pixels.
     */
    @Redirect(
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            ),
            require = 0,
            expect = 1,
            allow = 1
    )
    private VertexConsumer quickskin$redirectRenderHandBuffer(MultiBufferSource instance, RenderType renderType,
                                                              PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                                              ResourceLocation skinTexture, ModelPart arm, boolean slim) {
        // When CPM has a bound player, it manages the texture pipeline and already converts
        // entitySolidâ†’entityTranslucent when needed. Overriding the RenderType here would
        // use a different ResourceLocation, causing first-person arm texture artifacts.
        if (CPMCompatIntegration.shouldPreserveFirstPersonHandRenderType()) {
            return instance.getBuffer(renderType);
        }

        // Check if transparency is disabled globally by config
        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return instance.getBuffer(renderType);
        }

        if (skinTexture == null) {
            return instance.getBuffer(renderType);
        }

        // Determine if the skin needs a translucent render type
        boolean needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);

        if (needsTranslucent) {
            // The vanilla method calls getBuffer for both the solid arm and the translucent sleeve.
            // By forcing entityTranslucent here, we correctly render the arm with transparency.
            return instance.getBuffer(RenderType.entityTranslucent(skinTexture));
        }

        // If no transparency is needed, use the original render type provided by the vanilla method.
        return instance.getBuffer(renderType);
    }
//?}
}
//?}
