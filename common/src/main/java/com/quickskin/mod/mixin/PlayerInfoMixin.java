package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
//? if <1.21.11 {
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
//?}
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to intercept PlayerInfo skin lookups and apply custom skins/capes.
 */
@Mixin(value = PlayerInfo.class, priority = 500)
public abstract class PlayerInfoMixin {

    @Shadow
    @Final
    private GameProfile profile;

    // Cache for the custom PlayerSkin to avoid rebuilding it every frame
    @Unique
    private PlayerSkin quickskin$cachedSkin = null;

    // Cache the original skin's texture to detect when underlying skin changed
    @Unique
//? if <1.21.11 {
    private ResourceLocation quickskin$cachedOriginalTexture = null;
//?} else {
    private Identifier quickskin$cachedOriginalTexture = null;
//?}

    // Cache key components to detect when we need to rebuild
    @Unique
//? if <1.21.11 {
    private ResourceLocation quickskin$cachedSkinLocation = null;
//?} else {
    private Identifier quickskin$cachedSkinLocation = null;
//?}
    @Unique
//? if <1.21.11 {
    private ResourceLocation quickskin$cachedCapeLocation = null;
//?} else {
    private Identifier quickskin$cachedCapeLocation = null;
//?}
    @Unique
    private String quickskin$cachedModelName = null;

    /**
     * Inject at TAIL to override skin data when we have custom skin/cape/model.
     */
    @Inject(
            method = "getSkin",
            at = @At("TAIL"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$overrideSkinTail(CallbackInfoReturnable<PlayerSkin> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return;
        }

//? if <1.21.11 {
        boolean hasCustomSkin = service.hasActiveSkin(this.profile.getId());
        boolean hasCustomCape = service.hasActiveCape(this.profile.getId());
        boolean hasModelOverride = service.hasModelOverride(this.profile.getId());
//?} else {
        boolean hasCustomSkin = service.hasActiveSkin(this.profile.id());
        boolean hasCustomCape = service.hasActiveCape(this.profile.id());
        boolean hasModelOverride = service.hasModelOverride(this.profile.id());
//?}

        // Only modify if we have custom data
        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            // Title screen fallback: when no world is loaded, build skin from config
            if (Minecraft.getInstance().level == null) {
                PlayerSkin fallback = quickskin$buildTitleScreenFallback(cir.getReturnValue());
                if (fallback != null) {
                    cir.setReturnValue(fallback);
                    return;
                }
            }
            quickskin$cachedSkin = null;
            quickskin$cachedOriginalTexture = null;
            return;
        }

        PlayerSkin original = cir.getReturnValue();
        if (original == null) {
            return;
        }

        // Get current Quick-Skin data
//? if <1.21.11 {
        ResourceLocation currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.getId()) : null;
        ResourceLocation currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.getId()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.getId()) : null;
//?} else {
        Identifier currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.id()) : null;
        Identifier currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.id()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.id()) : null;
//?}

        // FAST PATH: Check if we can use cached result
        if (quickskin$cachedSkin != null &&
//? if <1.21.11 {
            java.util.Objects.equals(quickskin$cachedOriginalTexture, original.texture()) &&
//?} else {
            java.util.Objects.equals(quickskin$cachedOriginalTexture, original.body().texturePath()) &&
//?}
            java.util.Objects.equals(quickskin$cachedSkinLocation, currentSkinLocation) &&
            java.util.Objects.equals(quickskin$cachedCapeLocation, currentCapeLocation) &&
            java.util.Objects.equals(quickskin$cachedModelName, currentModelName)) {
            cir.setReturnValue(quickskin$cachedSkin);
            return;
        }

        // SLOW PATH: Cache miss, need to rebuild
//? if <1.21.11 {
        ResourceLocation skinTexture = original.texture();
        PlayerSkin.Model skinModel = original.model();
        ResourceLocation capeTexture = original.capeTexture();
        ResourceLocation elytraTexture = original.elytraTexture();
//?} else {
        Identifier skinTexture = original.body().texturePath();
        PlayerModelType skinModel = original.model();
        Identifier capeTexture = original.cape() != null ? original.cape().texturePath() : null;
        ClientAsset.Texture elytraTexture = original.elytra();
//?}

        // Override skin texture
        if (hasCustomSkin && currentSkinLocation != null) {
            skinTexture = currentSkinLocation;
        }

        // Override model
        if ((hasCustomSkin || hasModelOverride) && currentModelName != null) {
//? if <1.21.11 {
            skinModel = "slim".equals(currentModelName) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
//?} else {
            skinModel = "slim".equals(currentModelName) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
//?}
        }

        // Override cape
        if (hasCustomCape) {
            if (currentCapeLocation != null) {
                capeTexture = currentCapeLocation;
//? if <1.21.11 {
                elytraTexture = currentCapeLocation;
//?} else {
                elytraTexture = new ClientAsset.ResourceTexture(
                        currentCapeLocation, currentCapeLocation);
//?}
            } else {
                // An active network animation may deliberately resolve to null while its
                // bounded first-frame texture is prepared. Never expose the full atlas or an
                // unrelated profile Elytra then.
                capeTexture = null;
                elytraTexture = null;
            }
        }

        // Create new PlayerSkin with our custom values
        PlayerSkin customSkin = new PlayerSkin(
//? if <1.21.11 {
            skinTexture,
            original.textureUrl(),
            capeTexture,
            elytraTexture,
//?} else {
            new ClientAsset.ResourceTexture(skinTexture, skinTexture),
            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
            elytraTexture,
//?}
            skinModel,
            original.secure()
        );

        // Cache the result
        quickskin$cachedSkin = customSkin;
//? if <1.21.11 {
        quickskin$cachedOriginalTexture = original.texture();
//?} else {
        quickskin$cachedOriginalTexture = original.body().texturePath();
//?}
        quickskin$cachedSkinLocation = currentSkinLocation;
        quickskin$cachedCapeLocation = currentCapeLocation;
        quickskin$cachedModelName = currentModelName;

        cir.setReturnValue(customSkin);
    }

    /**
     * Builds a PlayerSkin from saved config for title screen fallback.
     * Returns null if no saved skin/cape is configured.
     */
    @Unique
    private PlayerSkin quickskin$buildTitleScreenFallback(PlayerSkin original) {
        ClientConfig config = ClientConfig.getInstance();
        boolean hasSkin = !config.activeSkinHash.isEmpty();
        boolean hasCape = !config.activeCapeHash.isEmpty();

        if (!hasSkin && !hasCape) {
            return null;
        }

        LocalAssetManager assetManager = LocalAssetManager.getInstance();
//? if <1.21.11 {
        ResourceLocation skinTexture = null;
        PlayerSkin.Model skinModel = (original != null) ? original.model() : PlayerSkin.Model.WIDE;
        ResourceLocation capeTexture = null;
        ResourceLocation elytraTexture = null;
//?} else {
        Identifier skinTexture = null;
        PlayerModelType skinModel = (original != null) ? original.model() : PlayerModelType.WIDE;
        Identifier capeTexture = null;
        ClientAsset.Texture elytraTexture = null;
//?}

        if (hasSkin) {
            skinTexture = assetManager.getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
            String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
            if ("auto".equals(modelType)) {
                AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);
                if (metadata != null) {
                    modelType = metadata.skinModel();
                }
            }
//? if <1.21.11 {
            skinModel = "slim".equals(modelType) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
//?} else {
            skinModel = "slim".equals(modelType) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
//?}
        }

        if (hasCape) {
            capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                    .getCapeLocation(null, config.activeCapeHash);
            if (capeTexture != null) {
//? if <1.21.11 {
                elytraTexture = capeTexture;
//?} else {
                elytraTexture = new ClientAsset.ResourceTexture(capeTexture, capeTexture);
//?}
            }
        }

        if (skinTexture == null && capeTexture == null) {
            return null;
        }

        if (original != null) {
//? if <1.21.11 {
//?} else {
            ClientAsset.Texture skinAsset = skinTexture != null ? new ClientAsset.ResourceTexture(skinTexture, skinTexture) : original.body();
            ClientAsset.Texture capeAsset = capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : original.cape();
//?}
            return new PlayerSkin(
//? if <1.21.11 {
                skinTexture != null ? skinTexture : original.texture(),
                original.textureUrl(),
                capeTexture != null ? capeTexture : original.capeTexture(),
                capeTexture != null ? elytraTexture : original.elytraTexture(),
//?} else {
                skinAsset,
                capeAsset,
                capeTexture != null ? elytraTexture : original.elytra(),
//?}
                skinModel,
                original.secure()
            );
        }

        // Last resort: build with just our textures
        return new PlayerSkin(
//? if <1.21.11 {
            skinTexture, null, capeTexture, elytraTexture, skinModel, false
//?} else {
            skinTexture != null ? new ClientAsset.ResourceTexture(skinTexture, skinTexture) : null,
            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
            elytraTexture, skinModel, false
//?}
        );
    }
}
