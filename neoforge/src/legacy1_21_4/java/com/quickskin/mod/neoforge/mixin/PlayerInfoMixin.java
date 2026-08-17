package com.quickskin.mod.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 21.4-specific mixin to intercept PlayerInfo skin lookups and apply custom skins/capes.
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
    private ResourceLocation quickskin$cachedOriginalTexture = null;

    @Unique
    private ResourceLocation quickskin$cachedSkinLocation = null;
    @Unique
    private ResourceLocation quickskin$cachedCapeLocation = null;
    @Unique
    private String quickskin$cachedModelName = null;

    @Inject(
            method = "getSkin",
            at = @At("TAIL"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1)
    private void quickskin$overrideSkinTail(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return;
        }

        boolean hasCustomSkin = service.hasActiveSkin(this.profile.getId());
        boolean hasCustomCape = service.hasActiveCape(this.profile.getId());
        boolean hasModelOverride = service.hasModelOverride(this.profile.getId());

        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            quickskin$cachedSkin = null;
            quickskin$cachedOriginalTexture = null;
            return;
        }

        PlayerSkin original = cir.getReturnValue();
        if (original == null) {
            return;
        }

        ResourceLocation currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.getId()) : null;
        ResourceLocation currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.getId()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.getId()) : null;

        if (quickskin$cachedSkin != null &&
            java.util.Objects.equals(quickskin$cachedOriginalTexture, original.texture()) &&
            java.util.Objects.equals(quickskin$cachedSkinLocation, currentSkinLocation) &&
            java.util.Objects.equals(quickskin$cachedCapeLocation, currentCapeLocation) &&
            java.util.Objects.equals(quickskin$cachedModelName, currentModelName)) {
            cir.setReturnValue(quickskin$cachedSkin);
            return;
        }

        ResourceLocation skinTexture = original.texture();
        PlayerSkin.Model skinModel = original.model();
        ResourceLocation capeTexture = original.capeTexture();
        ResourceLocation elytraTexture = original.elytraTexture();

        if (hasCustomSkin && currentSkinLocation != null) {
            skinTexture = currentSkinLocation;
        }

        if ((hasCustomSkin || hasModelOverride) && currentModelName != null) {
            skinModel = "slim".equals(currentModelName) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
        }

        if (hasCustomCape) {
            if (currentCapeLocation != null) {
                capeTexture = currentCapeLocation;
                elytraTexture = currentCapeLocation;
            } else {
                // Pending network animations intentionally resolve to null until their bounded
                // first-frame texture exists. Never publish the stacked atlas to other mods or
                // leave an unrelated profile Elytra beside a pending custom cape.
                capeTexture = null;
                elytraTexture = null;
            }
        }

        PlayerSkin customSkin = new PlayerSkin(
            skinTexture,
            original.textureUrl(),
            capeTexture,
            elytraTexture,
            skinModel,
            original.secure()
        );

        quickskin$cachedSkin = customSkin;
        quickskin$cachedOriginalTexture = original.texture();
        quickskin$cachedSkinLocation = currentSkinLocation;
        quickskin$cachedCapeLocation = currentCapeLocation;
        quickskin$cachedModelName = currentModelName;

        cir.setReturnValue(customSkin);
    }
}
