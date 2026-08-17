package com.quickskin.mod.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
//? if <1.21.9 {
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
//?} else if <1.21.11 {
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
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
 * NeoForge-specific mixin to intercept PlayerInfo skin lookups and apply custom skins/capes.
 * Uses Mojmap names directly since NeoForge uses Mojmap at runtime.
 */
@Mixin(value = PlayerInfo.class, priority = 500)
public abstract class PlayerInfoMixin {

    @Shadow
    @Final
    private GameProfile profile;

    @Unique
    private PlayerSkin quickskin$cachedSkin = null;

    @Unique
    //? if <1.21.11 {
    private ResourceLocation quickskin$cachedOriginalTexture = null;
    //?} else {
    private Identifier quickskin$cachedOriginalTexture = null;
    //?}

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

//? if <1.21.9 {
        boolean hasCustomSkin = service.hasActiveSkin(this.profile.getId());
        boolean hasCustomCape = service.hasActiveCape(this.profile.getId());
        boolean hasModelOverride = service.hasModelOverride(this.profile.getId());
//?} else {
        boolean hasCustomSkin = service.hasActiveSkin(this.profile.id());
        boolean hasCustomCape = service.hasActiveCape(this.profile.id());
        boolean hasModelOverride = service.hasModelOverride(this.profile.id());
//?}

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

        //? if <1.21.9 {
        ResourceLocation currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.getId()) : null;
        ResourceLocation currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.getId()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.getId()) : null;
        //?} else if <1.21.11 {
        ResourceLocation currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.id()) : null;
        ResourceLocation currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.id()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.id()) : null;
        //?} else {
        Identifier currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.id()) : null;
        Identifier currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.id()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.id()) : null;
        //?}

        if (quickskin$cachedSkin != null &&
            //? if <1.21.9 {
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

        //? if <1.21.9 {
        ResourceLocation skinTexture = original.texture();
        PlayerSkin.Model skinModel = original.model();
        ResourceLocation capeTexture = original.capeTexture();
        ResourceLocation elytraTexture = original.elytraTexture();
        //?} else if <1.21.11 {
        ResourceLocation skinTexture = original.body().texturePath();
        PlayerModelType skinModel = original.model();
        ResourceLocation capeTexture = original.cape() != null ? original.cape().texturePath() : null;
        ResourceLocation elytraTexture = original.elytra() != null ? original.elytra().texturePath() : null;
        //?} else {
        Identifier skinTexture = original.body().texturePath();
        PlayerModelType skinModel = original.model();
        Identifier capeTexture = original.cape() != null ? original.cape().texturePath() : null;
        ClientAsset.Texture elytraTexture = original.elytra();
        //?}

        if (hasCustomSkin && currentSkinLocation != null) {
            skinTexture = currentSkinLocation;
        }

        if ((hasCustomSkin || hasModelOverride) && currentModelName != null) {
            //? if <1.21.9 {
            skinModel = "slim".equals(currentModelName) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
            //?} else {
            skinModel = "slim".equals(currentModelName) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
            //?}
        }

        if (hasCustomCape) {
            if (currentCapeLocation != null) {
                capeTexture = currentCapeLocation;
                // Vanilla gives the dedicated profile-Elytra field priority over the cape, so an
                // active Quick Skin cape must own both renderer inputs.
                //? if <1.21.11 {
                elytraTexture = currentCapeLocation;
                //?} else {
                elytraTexture = new ClientAsset.ResourceTexture(
                        currentCapeLocation, currentCapeLocation);
                //?}
            } else {
                // Pending network animations intentionally resolve to null until their bounded
                // first-frame texture exists. Never publish the stacked atlas to other mods, and
                // never leave an unrelated profile Elytra beside the cleared cape.
                capeTexture = null;
                elytraTexture = null;
            }
        }

        PlayerSkin customSkin = new PlayerSkin(
            //? if <1.21.9 {
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

        quickskin$cachedSkin = customSkin;
        //? if <1.21.9 {
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
        //? if <1.21.9 {
        ResourceLocation skinTexture = null;
        PlayerSkin.Model skinModel = (original != null) ? original.model() : PlayerSkin.Model.WIDE;
        ResourceLocation capeTexture = null;
        ResourceLocation elytraTexture = null;
        //?} else if <1.21.11 {
        ResourceLocation skinTexture = null;
        PlayerModelType skinModel = (original != null) ? original.model() : PlayerModelType.WIDE;
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
            //? if <1.21.9 {
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
            //? if >=1.21.9 {
            ClientAsset.Texture skinAsset = skinTexture != null ? new ClientAsset.ResourceTexture(skinTexture, skinTexture) : original.body();
            ClientAsset.Texture capeAsset = capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : original.cape();
            //?}
            return new PlayerSkin(
                //? if <1.21.9 {
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
            //? if <1.21.9 {
            skinTexture, null, capeTexture, elytraTexture, skinModel, false
            //?} else {
            skinTexture != null ? new ClientAsset.ResourceTexture(skinTexture, skinTexture) : null,
            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
            elytraTexture, skinModel, false
            //?}
        );
    }
}
