package com.quickskin.mod.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.CapeService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** NeoForge Minecraft 1.21.5 SkinManager adapter. */
@Mixin(SkinManager.class)
public class SkinManagerMixin {

    @Unique
    private static PlayerSkin quickskin$applyOverrides(PlayerSkin original, UUID uuid) {
        if (original == null || uuid == null) {
            return original;
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return original;
        }

        boolean hasCustomSkin = service.hasActiveSkin(uuid);
        boolean hasCustomCape = service.hasActiveCape(uuid);
        boolean hasModelOverride = service.hasModelOverride(uuid);
        if (hasCustomSkin || hasCustomCape || hasModelOverride) {
            ResourceLocation skinTexture = original.texture();
            PlayerSkin.Model skinModel = original.model();
            ResourceLocation capeTexture = original.capeTexture();
            ResourceLocation elytraTexture = original.elytraTexture();
            boolean anyOverride = false;

            if (hasCustomSkin) {
                // HttpTexture no longer exists in 1.21.5. This activates CPM's explicit one-time
                // degraded-capability report while QuickSkin keeps its normal DynamicTexture.
                CPMCompatIntegration.isAvailable();
                ResourceLocation customSkin = service.getSkinLocation(uuid);
                if (customSkin != null) {
                    skinTexture = customSkin;
                    anyOverride = true;
                }
            }

            if (hasCustomSkin || hasModelOverride) {
                String customModel = service.getModelName(uuid);
                if (customModel != null) {
                    skinModel = "slim".equals(customModel)
                            ? PlayerSkin.Model.SLIM
                            : PlayerSkin.Model.WIDE;
                    anyOverride = true;
                }
            }

            if (hasCustomCape) {
                ResourceLocation customCape = service.getCapeLocation(uuid);
                if (customCape != null) {
                    capeTexture = customCape;
                    elytraTexture = customCape;
                    anyOverride = true;
                } else {
                    // A pending custom texture must not fall through to unrelated Mojang cape or
                    // Elytra assets while its bounded first frame is being prepared.
                    capeTexture = null;
                    elytraTexture = null;
                    anyOverride = true;
                }
            }

            if (anyOverride) {
                return new PlayerSkin(
                        skinTexture,
                        original.textureUrl(),
                        capeTexture,
                        elytraTexture,
                        skinModel,
                        original.secure());
            }
        }

        // The saved selection is authoritative for the local profile when no world service state
        // exists yet (for example, a title-screen preview).
        if (uuid.equals(Minecraft.getInstance().getUser().getProfileId())) {
            ClientConfig config = ClientConfig.getInstance();
            boolean hasSkin = !config.activeSkinHash.isEmpty();
            boolean hasCape = !config.activeCapeHash.isEmpty();
            if (hasSkin || hasCape) {
                ResourceLocation skinTexture = original.texture();
                PlayerSkin.Model skinModel = original.model();
                ResourceLocation capeTexture = original.capeTexture();
                ResourceLocation elytraTexture = original.elytraTexture();
                boolean anyOverride = false;

                if (hasSkin) {
                    ResourceLocation location = LocalAssetManager.getInstance()
                            .getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                    if (location != null) {
                        skinTexture = location;
                        String modelType = LocalAssetManager.getInstance()
                                .getSkinModelPreference(config.activeSkinHash);
                        if ("auto".equals(modelType)) {
                            var metadata = LocalAssetManager.getInstance()
                                    .getMetadata(config.activeSkinHash);
                            if (metadata != null) {
                                modelType = metadata.skinModel();
                            }
                        }
                        skinModel = "slim".equals(modelType)
                                ? PlayerSkin.Model.SLIM
                                : PlayerSkin.Model.WIDE;
                        anyOverride = true;
                    }
                }

                if (hasCape) {
                    ResourceLocation capeLocation = CapeService.getInstance()
                            .getCapeLocation(null, config.activeCapeHash);
                    if (capeLocation != null) {
                        capeTexture = capeLocation;
                        elytraTexture = capeLocation;
                        anyOverride = true;
                    }
                }

                if (anyOverride) {
                    return new PlayerSkin(
                            skinTexture,
                            original.textureUrl(),
                            capeTexture,
                            elytraTexture,
                            skinModel,
                            original.secure());
                }
            }
        }

        return original;
    }

    @Inject(
            method = "getInsecureSkin",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 2,
            allow = 2)
    private void quickskin$modifyInsecureSkin(GameProfile profile,
                                              CallbackInfoReturnable<PlayerSkin> cir) {
        UUID uuid = profile.getId();
        if (uuid == null) {
            return;
        }
        PlayerSkin original = cir.getReturnValue();
        PlayerSkin modified = quickskin$applyOverrides(original, uuid);
        if (modified != original) {
            cir.setReturnValue(modified);
        }
    }

    @Inject(
            method = "getOrLoad",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1)
    private void quickskin$modifyGetOrLoad(
            GameProfile profile,
            CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
        UUID uuid = profile.getId();
        if (uuid == null) {
            return;
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        boolean hasServiceOverrides = service != null
                && (service.hasActiveSkin(uuid)
                || service.hasActiveCape(uuid)
                || service.hasModelOverride(uuid));
        boolean isLocalPlayer = uuid.equals(Minecraft.getInstance().getUser().getProfileId());
        ClientConfig config = ClientConfig.getInstance();
        boolean hasSavedFallback = isLocalPlayer
                && (!config.activeSkinHash.isEmpty() || !config.activeCapeHash.isEmpty());
        if (!hasServiceOverrides && !hasSavedFallback) {
            return;
        }

        CompletableFuture<Optional<PlayerSkin>> original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        cir.setReturnValue(original.thenApply(optionalSkin ->
                optionalSkin.map(skin -> quickskin$applyOverrides(skin, uuid))));
    }
}
