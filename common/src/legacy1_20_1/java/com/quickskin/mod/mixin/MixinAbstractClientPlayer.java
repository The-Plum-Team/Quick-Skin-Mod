package com.quickskin.mod.mixin;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to intercept AbstractClientPlayer skin lookups at the deepest level
 * This operates at the same depth as TLSkinCape, with higher priority (2000) to win
 */
@Mixin(AbstractClientPlayer.class)
public class MixinAbstractClientPlayer {

    /**
     * Intercept skin texture lookups and return QuickSkin's texture if active.
     * We inject at HEAD with cancellable=true to short-circuit TLSkinCape and vanilla.
     *
     * With global mixin priority 2000 (higher than TLSkinCape's default 1000),
     * this ensures QuickSkin gets the final say on player skins.
     */
    @Inject(
            method = "getSkinTextureLocation",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$getSkinTextureLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        // Get QuickSkin's texture for this player
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Only override if QuickSkin has an active custom skin for this player
        if (service.hasActiveSkin(self.getUUID())) {
            ResourceLocation customSkin = service.getSkinLocation(self.getUUID());
            if (customSkin != null) {
                cir.setReturnValue(customSkin); // QuickSkin wins here
                return;
            }
        }

        // Title screen fallback: when no world is loaded and config has a saved skin,
        // return it directly regardless of UUID (covers Essential's fake player entity)
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeSkinHash.isEmpty()) {
                ResourceLocation loc = LocalAssetManager.getInstance()
                        .getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                if (loc != null) {
                    cir.setReturnValue(loc);
                }
            }
        }
    }

    /**
     * Intercept model name lookups to return QuickSkin's model type.
     * Works in tandem with skin texture override to ensure correct model rendering.
     */
    @Inject(
            method = "getModelName",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$getModelName(CallbackInfoReturnable<String> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Override if QuickSkin has an active custom model OR a model override for this player
        if (service.hasActiveSkin(self.getUUID()) || service.hasModelOverride(self.getUUID())) {
            String customModel = service.getModelName(self.getUUID());
            if (customModel != null) {
                cir.setReturnValue(customModel);
                return;
            }
        }

        // Title screen fallback: return saved model type from config
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeSkinHash.isEmpty()) {
                LocalAssetManager assetManager = LocalAssetManager.getInstance();
                String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                if ("auto".equals(modelType)) {
                    AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);
                    if (metadata != null) {
                        modelType = metadata.skinModel();
                    }
                }
                if (modelType != null) {
                    // Convert to Minecraft model names: "classic" -> "default", "slim" stays "slim"
                    String mcModel = "classic".equals(modelType) ? "default" : modelType;
                    cir.setReturnValue(mcModel);
                }
            }
        }
    }

    /**
     * Intercept cape texture lookups to return QuickSkin's cape if active.
     */
    @Inject(
            method = "getCloakTextureLocation",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$getCloakTextureLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Only override if QuickSkin has an active custom cape for this player
        if (service.hasActiveCape(self.getUUID())) {
            com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(self.getUUID());

            // Check for the explicit "hide cape" identifier
            if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                cir.setReturnValue(null); // Return null to hide the cape completely
                return;
            }

            ResourceLocation customCape = service.getCapeLocation(self.getUUID());

            // If a custom cape is found (or still loading but intended), set it.
            cir.setReturnValue(customCape);
            return;
        }

        // Title screen fallback: return saved cape from config
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeCapeHash.isEmpty()) {
                // Use CapeService to resolve the location (handles animated capes too)
                ResourceLocation capeLoc = com.quickskin.mod.client.services.CapeService.getInstance()
                        .getCapeLocation(null, config.activeCapeHash);
                if (capeLoc != null) {
                    cir.setReturnValue(capeLoc);
                }
            }
        }
    }

    /**
     * A profile Elytra has priority over the cloak in vanilla's renderer. Mirror the Quick Skin
     * cape override here so a late profile-texture response cannot replace the selected cape only
     * on the equipped Elytra.
     */
    @Inject(
            method = "getElytraTextureLocation",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$getElytraTextureLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service.hasActiveCape(self.getUUID())) {
            cir.setReturnValue(service.getCapeLocation(self.getUUID()));
            return;
        }

        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeCapeHash.isEmpty()) {
                ResourceLocation capeLoc = com.quickskin.mod.client.services.CapeService.getInstance()
                        .getCapeLocation(null, config.activeCapeHash);
                if (capeLoc != null) {
                    cir.setReturnValue(capeLoc);
                }
            }
        }
    }
}
