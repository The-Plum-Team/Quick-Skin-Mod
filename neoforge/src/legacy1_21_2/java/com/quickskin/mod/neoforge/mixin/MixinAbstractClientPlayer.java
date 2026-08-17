package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.client.services.PlayerAppearanceService;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * NeoForge 21.2-specific mixin to intercept AbstractClientPlayer skin lookups.
 * Uses Mojmap names directly since NeoForge uses Mojmap at runtime.
 */
@Mixin(value = AbstractClientPlayer.class, priority = 100)
public abstract class MixinAbstractClientPlayer {

    @Unique
    private static Field quickskin$playerInfoField = null;

    @Unique
    private static boolean quickskin$fieldSearched = false;

    @Inject(
            method = "getSkin",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1)
    private void quickskin$overrideSkinAtHead(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return;
        }

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        boolean hasCustomSkin = service.hasActiveSkin(self.getUUID());
        boolean hasCustomCape = service.hasActiveCape(self.getUUID());
        boolean hasModelOverride = service.hasModelOverride(self.getUUID());

        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            return;
        }

        PlayerInfo playerInfo = quickskin$getPlayerInfo(self);
        if (playerInfo == null) {
            return;
        }

        PlayerSkin originalSkin = playerInfo.getSkin();
        if (originalSkin == null) {
            return;
        }

        ResourceLocation skinTexture = originalSkin.texture();
        PlayerSkin.Model skinModel = originalSkin.model();
        ResourceLocation capeTexture = originalSkin.capeTexture();
        ResourceLocation elytraTexture = originalSkin.elytraTexture();

        if (hasCustomSkin) {
            ResourceLocation customSkin = service.getSkinLocation(self.getUUID());
            if (customSkin != null) {
                skinTexture = customSkin;
            }
        }

        if (hasCustomSkin || hasModelOverride) {
            String customModel = service.getModelName(self.getUUID());
            if (customModel != null) {
                skinModel = "slim".equals(customModel) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
            }
        }

        if (hasCustomCape) {
            ResourceLocation customCape = service.getCapeLocation(self.getUUID());
            if (customCape != null) {
                capeTexture = customCape;
                elytraTexture = customCape;
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
            originalSkin.textureUrl(),
            capeTexture,
            elytraTexture,
            skinModel,
            originalSkin.secure()
        );

        cir.setReturnValue(customSkin);
    }

    @Unique
    private static PlayerInfo quickskin$getPlayerInfo(AbstractClientPlayer player) {
        if (!quickskin$fieldSearched) {
            quickskin$fieldSearched = true;
            for (Field field : AbstractClientPlayer.class.getDeclaredFields()) {
                if (PlayerInfo.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    quickskin$playerInfoField = field;
                    break;
                }
            }
        }

        if (quickskin$playerInfoField != null) {
            try {
                return (PlayerInfo) quickskin$playerInfoField.get(player);
            } catch (IllegalAccessException e) {
            }
        }
        return null;
    }
}
