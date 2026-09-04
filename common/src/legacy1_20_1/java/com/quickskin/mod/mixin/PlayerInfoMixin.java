package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Mixin to intercept PlayerInfo texture and model lookups
 * Allows QuickSkin to override player skins, capes, and models
 */
@Mixin(value = PlayerInfo.class, priority = 1100) // Higher priority to override TLSkinCape and other mods
public abstract class PlayerInfoMixin implements com.quickskin.mod.client.compat.QuickSkinPlayerInfoAccess {

    private static final Logger CPMLOG = LoggerFactory.getLogger("QuickSkin-CPM");

    @Shadow
    @Final
    private GameProfile profile;

    @Shadow
    @Final
    private Map<MinecraftProfileTexture.Type, ResourceLocation> textureLocations;

    @Shadow
    private boolean pendingTextures;

    @Shadow
    private String skinModel;

    @Shadow
    private void registerTextures() {}

    /**
     * Force re-registration of skin textures.
     * Clears the cached skin, resets pendingTextures, and directly calls registerTextures() so that
     * registerSkins() fires immediately through MixinSkinManager,
     * updating CPM's skin data with the new HttpTexture bridge.
     *
     * We must call registerTextures() directly because our getSkinLocation()
     * HEAD injection cancels the original method (preventing registerTextures()
     * from running naturally).
     */
    @Override
    public void quickskin$forceReRegisterSkins() {
        // registerTextures() only adds callback results to this map. If the replacement is an
        // offline/default skin, no SKIN callback arrives and the previous Quick Skin location
        // otherwise survives forever. Remove it first so getSkinLocation() can use its UUID
        // default while CPM rebuilds the player definition.
        this.textureLocations.remove(MinecraftProfileTexture.Type.SKIN);
        this.skinModel = null;
        this.pendingTextures = false;
        this.registerTextures();
    }

    /**
     * Inject into getSkinLocation to override with QuickSkin texture.
     * When CPM is installed, returns an HttpTexture-backed ResourceLocation so CPM
     * can read the skin file and extract embedded 3D model data.
     */
    // Throttle logging: only log once per player per second to avoid spam
    private long lastLogTime = 0;

    @Inject(
            method = "getSkinLocation",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$onGetSkinLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        boolean deferring = CPMCompatIntegration.shouldDeferToCPM();
        long now2 = System.currentTimeMillis();
        if ((now2 - lastLogTime) > 2000) {
            CPMLOG.info("getSkinLocation ENTRY player={} deferToCPM={} hasActiveSkin={}",
                    this.profile.getName(), deferring,
                    PlayerAppearanceService.getInstance().hasActiveSkin(this.profile.getId()));
            lastLogTime = now2;
        }
        if (deferring) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        long now = System.currentTimeMillis();
        boolean shouldLog = (now - lastLogTime) > 2000;

        // Only override if QuickSkin has an active custom skin for this player
        if (service.hasActiveSkin(this.profile.getId())) {
            // When CPM is available, return HttpTexture-backed location so CPM can
            // read the skin file for embedded 3D model data
            if (CPMCompatIntegration.isAvailable()) {
                com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(this.profile.getId());
                if (appearance != null && appearance.getSkinId() != null) {
                    String skinId = appearance.getSkinId();
                    String hash = null;
                    if (skinId.startsWith("local_skin:")) {
                        hash = skinId.substring("local_skin:".length());
                    }
                    if (shouldLog) {
                        CPMLOG.info("getSkinLocation player={} skinId={} hash={}",
                                this.profile.getName(), skinId, hash);
                    }
                    if (hash != null) {
                        ResourceLocation httpLoc = CPMCompatIntegration.getOrRegisterHttpTexture(hash);
                        if (shouldLog) {
                            CPMLOG.info("getSkinLocation httpLoc={}", httpLoc);
                            if (httpLoc != null) {
                                net.minecraft.client.renderer.texture.AbstractTexture tex =
                                        Minecraft.getInstance().getTextureManager().getTexture(httpLoc, null);
                                CPMLOG.info("getSkinLocation texture class={}",
                                        tex != null ? tex.getClass().getName() : "null");
                            }
                        }
                        if (httpLoc != null) {
                            if (shouldLog) lastLogTime = now;
                            cir.setReturnValue(httpLoc);
                            return;
                        }
                    }
                }
            }

            ResourceLocation customSkin = service.getSkinLocation(this.profile.getId());
            if (shouldLog) {
                CPMLOG.info("getSkinLocation fallback customSkin={} player={}",
                        customSkin, this.profile.getName());
                lastLogTime = now;
            }
            if (customSkin != null) {
                cir.setReturnValue(customSkin);
                return;
            }
        }

        // Title screen fallback: when no world is loaded, return saved skin from config
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeSkinHash.isEmpty()) {
                ResourceLocation loc;
                if (CPMCompatIntegration.isAvailable()) {
                    loc = CPMCompatIntegration.getOrRegisterHttpTexture(config.activeSkinHash);
                } else {
                    loc = null;
                }
                if (loc == null) {
                    loc = LocalAssetManager.getInstance()
                            .getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                }
                if (loc != null) {
                    cir.setReturnValue(loc);
                }
            }
        }
    }

    /**
     * Inject into getModelName to override with QuickSkin model type
     */
    @Inject(
            method = "getModelName",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$onGetModelName(CallbackInfoReturnable<String> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Override if QuickSkin has an active custom model OR a model override for this player
        // Check both to avoid race condition where model is set before skin data is populated
        if (service.hasActiveSkin(this.profile.getId()) || service.hasModelOverride(this.profile.getId())) {
            String customModel = service.getModelName(this.profile.getId());
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
     * Inject into getCapeLocation to override with QuickSkin cape
     */
    @Inject(
            method = "getCapeLocation",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$onGetCapeLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Only override if QuickSkin has an active custom cape for this player
        if (service.hasActiveCape(this.profile.getId())) {
            com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(this.profile.getId());

            // Check for the explicit "hide cape" identifier
            if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                cir.setReturnValue(null); // Return null to hide the cape completely
                return;
            }

            ResourceLocation customCape = service.getCapeLocation(this.profile.getId());

            // If a custom cape is found (or still loading but intended), set it.
            cir.setReturnValue(customCape);
            return;
        }

        // Title screen fallback: return saved cape from config
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

    /** Keep PlayerInfo's higher-priority Elytra slot on the same selected Quick Skin cape. */
    @Inject(
            method = "getElytraLocation",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$onGetElytraLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service.hasActiveCape(this.profile.getId())) {
            cir.setReturnValue(service.getCapeLocation(this.profile.getId()));
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
