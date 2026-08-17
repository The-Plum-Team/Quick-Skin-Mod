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
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.Identifier;
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
    private Identifier quickskin$cachedOriginalTexture = null;

    @Unique
    private Identifier quickskin$cachedSkinLocation = null;
    @Unique
    private Identifier quickskin$cachedCapeLocation = null;
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

        boolean hasCustomSkin = service.hasActiveSkin(this.profile.id());
        boolean hasCustomCape = service.hasActiveCape(this.profile.id());
        boolean hasModelOverride = service.hasModelOverride(this.profile.id());

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

        Identifier currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.id()) : null;
        Identifier currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.id()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.id()) : null;

        if (quickskin$cachedSkin != null &&
            java.util.Objects.equals(quickskin$cachedOriginalTexture, original.body().texturePath()) &&
            java.util.Objects.equals(quickskin$cachedSkinLocation, currentSkinLocation) &&
            java.util.Objects.equals(quickskin$cachedCapeLocation, currentCapeLocation) &&
            java.util.Objects.equals(quickskin$cachedModelName, currentModelName)) {
            cir.setReturnValue(quickskin$cachedSkin);
            return;
        }

        Identifier skinTexture = original.body().texturePath();
        PlayerModelType skinModel = original.model();
        Identifier capeTexture = original.cape() != null ? original.cape().texturePath() : null;
        ClientAsset.Texture elytraTexture = original.elytra();

        if (hasCustomSkin && currentSkinLocation != null) {
            skinTexture = currentSkinLocation;
        }

        if ((hasCustomSkin || hasModelOverride) && currentModelName != null) {
            skinModel = "slim".equals(currentModelName) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
        }

        if (hasCustomCape) {
            if (currentCapeLocation != null) {
                capeTexture = currentCapeLocation;
                elytraTexture = new ClientAsset.ResourceTexture(
                        currentCapeLocation, currentCapeLocation);
            } else {
                // Pending network animations intentionally resolve to null until their bounded
                // first-frame texture exists. Never publish the stacked atlas to other mods or
                // leave an unrelated profile Elytra beside a pending custom cape.
                capeTexture = null;
                elytraTexture = null;
            }
        }

        PlayerSkin customSkin = new PlayerSkin(
            new ClientAsset.ResourceTexture(skinTexture, skinTexture),
            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
            elytraTexture,
            skinModel,
            original.secure()
        );

        quickskin$cachedSkin = customSkin;
        quickskin$cachedOriginalTexture = original.body().texturePath();
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
        Identifier skinTexture = null;
        PlayerModelType skinModel = (original != null) ? original.model() : PlayerModelType.WIDE;
        Identifier capeTexture = null;
        ClientAsset.Texture elytraTexture = null;

        if (hasSkin) {
            skinTexture = assetManager.getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
            String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
            if ("auto".equals(modelType)) {
                AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);
                if (metadata != null) {
                    modelType = metadata.skinModel();
                }
            }
            skinModel = "slim".equals(modelType) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
        }

        if (hasCape) {
            capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                    .getCapeLocation(null, config.activeCapeHash);
            if (capeTexture != null) {
                elytraTexture = new ClientAsset.ResourceTexture(capeTexture, capeTexture);
            }
        }

        if (skinTexture == null && capeTexture == null) {
            return null;
        }

        if (original != null) {
            ClientAsset.Texture skinAsset = skinTexture != null ? new ClientAsset.ResourceTexture(skinTexture, skinTexture) : original.body();
            ClientAsset.Texture capeAsset = capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : original.cape();
            return new PlayerSkin(
                skinAsset,
                capeAsset,
                capeTexture != null ? elytraTexture : original.elytra(),
                skinModel,
                original.secure()
            );
        }

        // Last resort: build with just our textures
        return new PlayerSkin(
            skinTexture != null ? new ClientAsset.ResourceTexture(skinTexture, skinTexture) : null,
            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
            elytraTexture, skinModel, false
        );
    }
}
