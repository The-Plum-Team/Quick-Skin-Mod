package com.quickskin.mod.mixin;

import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
//? if <1.21.9 {
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
//?} else if <1.21.11 {
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to intercept AbstractClientPlayer skin lookups.
 *
 * In MC 1.21.1, the unified getSkin() method exists, but Essential mod (for backwards
 * compatibility) may still call PlayerSkin component getters. We intercept at the
 * getSkin() level and let its getters delegate to the unified record.
 */
@Mixin(value = AbstractClientPlayer.class, priority = 2000)
public abstract class MixinAbstractClientPlayer {

    @Shadow
    public abstract PlayerSkin getSkin();

    /**
     * Intercept getSkin() at RETURN to modify the returned PlayerSkin.
     * Using @At("RETURN") allows us to get the vanilla/Essential skin first,
     * then modify it before returning to the caller.
     */
    @Inject(
            method = "getSkin",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$modifySkin(CallbackInfoReturnable<PlayerSkin> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) return;

        PlayerSkin originalSkin = cir.getReturnValue();
        if (originalSkin == null) return;

        boolean hasCustomSkin = service.hasActiveSkin(self.getUUID());
        boolean hasCustomCape = service.hasActiveCape(self.getUUID());
        boolean hasModelOverride = service.hasModelOverride(self.getUUID());

        // Service-based overrides
        if (hasCustomSkin || hasCustomCape || hasModelOverride) {
//? if <1.21.9 {
            ResourceLocation skinTexture = originalSkin.texture();
            PlayerSkin.Model skinModel = originalSkin.model();
            ResourceLocation capeTexture = originalSkin.capeTexture();
            ResourceLocation elytraTexture = originalSkin.elytraTexture();
//?} else if <1.21.11 {
            ResourceLocation skinTexture = originalSkin.body().texturePath();
            PlayerModelType skinModel = originalSkin.model();
            ResourceLocation capeTexture = originalSkin.cape() != null ? originalSkin.cape().texturePath() : null;
            ClientAsset.Texture elytraTexture = originalSkin.elytra();
//?} else {
            Identifier skinTexture = originalSkin.body().texturePath();
            PlayerModelType skinModel = originalSkin.model();
            Identifier capeTexture = originalSkin.cape() != null ? originalSkin.cape().texturePath() : null;
            ClientAsset.Texture elytraTexture = originalSkin.elytra();
//?}
            boolean anyOverride = false;

            if (hasCustomSkin) {
//? if <1.21.11 {
                ResourceLocation customSkin = service.getSkinLocation(self.getUUID());
//?} else {
                Identifier customSkin = service.getSkinLocation(self.getUUID());
//?}
                if (customSkin != null) {
                    skinTexture = customSkin;
                    anyOverride = true;
                }
            }

            if (hasCustomSkin || hasModelOverride) {
                String customModel = service.getModelName(self.getUUID());
                if (customModel != null) {
//? if <1.21.9 {
                    skinModel = "slim".equals(customModel) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
//?} else {
                    skinModel = "slim".equals(customModel) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
//?}
                    anyOverride = true;
                }
            }

            if (hasCustomCape) {
//? if <1.21.11 {
                ResourceLocation customCape = service.getCapeLocation(self.getUUID());
//?} else {
                Identifier customCape = service.getCapeLocation(self.getUUID());
//?}
                if (customCape != null) {
                    capeTexture = customCape;
//? if <1.21.11 {
                    elytraTexture = customCape;
//?} else {
                    elytraTexture = new ClientAsset.ResourceTexture(customCape, customCape);
//?}
                    anyOverride = true;
                } else {
                    // An active network animation may deliberately resolve to null while its
                    // bounded first-frame texture is prepared. Never expose the full atlas or an
                    // unrelated profile Elytra then.
                    capeTexture = null;
                    elytraTexture = null;
                    anyOverride = true;
                }
            }

            if (anyOverride) {
                cir.setReturnValue(new PlayerSkin(
//? if <1.21.9 {
                        skinTexture,
                        originalSkin.textureUrl(),
                        capeTexture,
                        elytraTexture,
//?} else {
                        new ClientAsset.ResourceTexture(skinTexture, skinTexture),
                        capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
                        elytraTexture,
//?}
                        skinModel,
                        originalSkin.secure()
                ));
                return;
            }
        }

        // Title screen config fallback
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            boolean hasSkin = !config.activeSkinHash.isEmpty();
            boolean hasCape = !config.activeCapeHash.isEmpty();

            if (hasSkin || hasCape) {
//? if <1.21.9 {
                ResourceLocation skinTexture = originalSkin.texture();
                PlayerSkin.Model skinModel = originalSkin.model();
                ResourceLocation capeTexture = originalSkin.capeTexture();
                ResourceLocation elytraTexture = originalSkin.elytraTexture();
//?} else if <1.21.11 {
                ResourceLocation skinTexture = originalSkin.body().texturePath();
                PlayerModelType skinModel = originalSkin.model();
                ResourceLocation capeTexture = originalSkin.cape() != null ? originalSkin.cape().texturePath() : null;
                ClientAsset.Texture elytraTexture = originalSkin.elytra();
//?} else {
                Identifier skinTexture = originalSkin.body().texturePath();
                PlayerModelType skinModel = originalSkin.model();
                Identifier capeTexture = originalSkin.cape() != null ? originalSkin.cape().texturePath() : null;
                ClientAsset.Texture elytraTexture = originalSkin.elytra();
//?}
                boolean anyOverride = false;

                if (hasSkin) {
//? if <1.21.11 {
                    ResourceLocation loc = LocalAssetManager.getInstance()
//?} else {
                    Identifier loc = LocalAssetManager.getInstance()
//?}
                            .getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                    if (loc != null) {
                        skinTexture = loc;
                        String modelType = LocalAssetManager.getInstance().getSkinModelPreference(config.activeSkinHash);
                        if ("auto".equals(modelType)) {
                            AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(config.activeSkinHash);
                            if (metadata != null) {
                                modelType = metadata.skinModel();
                            }
                        }
//? if <1.21.9 {
                        skinModel = "slim".equals(modelType) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
//?} else {
                        skinModel = "slim".equals(modelType) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
//?}
                        anyOverride = true;
                    }
                }

                if (hasCape) {
//? if <1.21.11 {
                    ResourceLocation capeLoc = com.quickskin.mod.client.services.CapeService.getInstance()
//?} else {
                    Identifier capeLoc = com.quickskin.mod.client.services.CapeService.getInstance()
//?}
                            .getCapeLocation(null, config.activeCapeHash);
                    if (capeLoc != null) {
                        capeTexture = capeLoc;
//? if <1.21.11 {
                        elytraTexture = capeLoc;
//?} else {
                        elytraTexture = new ClientAsset.ResourceTexture(capeLoc, capeLoc);
//?}
                        anyOverride = true;
                    }
                }

                if (anyOverride) {
                    cir.setReturnValue(new PlayerSkin(
//? if <1.21.9 {
                            skinTexture,
                            originalSkin.textureUrl(),
                            capeTexture,
                            elytraTexture,
//?} else {
                            new ClientAsset.ResourceTexture(skinTexture, skinTexture),
                            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
                            elytraTexture,
//?}
                            skinModel,
                            originalSkin.secure()
                    ));
                }
            }
        }
    }
}
