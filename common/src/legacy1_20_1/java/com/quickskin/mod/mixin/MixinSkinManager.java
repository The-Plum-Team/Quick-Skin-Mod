package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mixin to intercept SkinManager's skin loading so that Essential's title screen
 * player model (which loads skins via SkinManager.registerSkins, bypassing
 * AbstractClientPlayer.getSkinTextureLocation) uses QuickSkin's texture.
 */
@Mixin(SkinManager.class)
public class MixinSkinManager {

    private static final Logger CPMLOG = LoggerFactory.getLogger("QuickSkin-CPM");
    @Unique
    private static final Map<String, String> SLIM_MODEL_METADATA = Map.of("model", "slim");

    @Inject(
            method = "registerSkins",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 1
    )
    private void quickskin$wrapRegisterSkins(
            GameProfile profile,
            SkinManager.SkinTextureCallback callback,
            boolean requireSecure,
            CallbackInfo ci) {

        // A name-only GameProfile (a player_head whose SkullOwner is a bare name, handed to
        // registerSkins by CPM's profile loader) carries no UUID. Quick Skin keys appearances
        // by UUID, so there is nothing to override: let vanilla and CPM register the skin
        // exactly as if Quick Skin were absent. Mirrors the guard in quickskin$overrideSkinInfo.
        if (profile == null || profile.getId() == null) return;

        CPMLOG.info("registerSkins called for profile={} name={} CPM={}",
                profile.getId(), profile.getName(), CPMCompatIntegration.isAvailable());

        UUID localUuid = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                localUuid = mc.getUser().getProfileId();
            }
        } catch (Exception e) {
            // Safety: if anything goes wrong getting UUID, just pass through
        }

        CPMLOG.info("localUuid={} isLocal={}", localUuid,
                localUuid != null && localUuid.equals(profile.getId()));

        ClientConfig config = ClientConfig.getInstance();

        if (localUuid != null && localUuid.equals(profile.getId()) && !config.activeSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                ResourceLocation ourSkin;
                if (CPMCompatIntegration.isAvailable()) {
                    // When CPM is installed, register skin as HttpTexture so CPM can
                    // read pixel data and extract embedded 3D model from the PNG file
                    ourSkin = CPMCompatIntegration.getOrRegisterHttpTexture(config.activeSkinHash);
                    if (ourSkin == null) {
                        ourSkin = assetManager.getTextureLocation(
                                config.activeSkinHash, TextureQuality.FULL);
                    }
                } else {
                    ourSkin = assetManager.getTextureLocation(
                            config.activeSkinHash, TextureQuality.FULL);
                }

                if (ourSkin != null) {
                    // Build metadata with correct model type
                    String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                    Map<String, String> meta = new HashMap<>();
                    if ("slim".equals(modelType)) {
                        meta.put("model", "slim");
                    } else if ("auto".equals(modelType)) {
                        AssetMetadata skinMeta = assetManager.getMetadata(config.activeSkinHash);
                        if (skinMeta != null && "slim".equals(skinMeta.skinModel())) {
                            meta.put("model", "slim");
                        }
                    }

                    // Use file URL so CPM reads from our local file, not Mojang.
                    // getHash() extracts the last path segment as cache key,
                    // so CPM won't confuse this with the Mojang premium skin.
                    String textureUrl = "file:///quickskin/" + config.activeSkinHash;
                    java.nio.file.Path sourcePath = assetManager.getSourcePath(config.activeSkinHash);
                    if (sourcePath != null && sourcePath.toFile().exists()) {
                        textureUrl = "file:///" + sourcePath.toFile().getAbsolutePath().replace('\\', '/');
                    }

                    MinecraftProfileTexture modifiedTexture = new MinecraftProfileTexture(
                            textureUrl, meta);

                    // Cancel the original registerSkins and directly fire our SKIN callback.
                    // We do NOT re-invoke registerSkins because that would cause CPM's mixin
                    // to wrap the callback again, letting CPM see the raw Mojang skin data
                    // before our modifications. By firing the callback directly, CPM only
                    // processes our modified skin data.
                    CPMLOG.info("LOCAL player: cancelling registerSkins, textureUrl={}", textureUrl);
                    ci.cancel();
                    callback.onSkinTextureAvailable(
                            MinecraftProfileTexture.Type.SKIN, ourSkin, modifiedTexture);
                    return;
                }
            }
        }

        // Remote players with active QuickSkin skins: when CPM is installed, we must also
        // cancel registerSkins here. Otherwise Mojang's async skin lookup fires CPM's
        // callback wrapper with the Mojang skin, overriding our texture after a brief delay.
        if (CPMCompatIntegration.isAvailable()
                && (localUuid == null || !localUuid.equals(profile.getId()))) {
            PlayerAppearance appearance = PlayerAppearanceService.getInstance()
                    .getAppearance(profile.getId());
            CPMLOG.info("Remote player block: appearance={} skinId={}",
                    appearance != null, appearance != null ? appearance.getSkinId() : "null");
            if (appearance != null && appearance.getSkinId() != null) {
                String skinId = appearance.getSkinId();
                String hash = null;
                if (skinId.startsWith("local_skin:")) {
                    hash = skinId.substring("local_skin:".length());
                }
                CPMLOG.info("Remote: hash={}", hash);
                if (hash != null) {
                    ResourceLocation httpLoc = CPMCompatIntegration.getOrRegisterHttpTexture(hash);
                    CPMLOG.info("Remote: httpLoc={}", httpLoc);
                    if (httpLoc != null) {
                        Map<String, String> meta = new HashMap<>();
                        if ("slim".equals(appearance.getModel())) {
                            meta.put("model", "slim");
                        }

                        // Build file URL for CPM -- try local disk first, then network temp file
                        java.nio.file.Path sourcePath = LocalAssetManager.getInstance().getSourcePath(hash);
                        CPMLOG.info("Remote: localPath={}", sourcePath);
                        if (sourcePath == null || !sourcePath.toFile().exists()) {
                            sourcePath = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                                    .getOrCreateTempFile(hash, "skin");
                            CPMLOG.info("Remote: networkTempFile={}", sourcePath);
                        }
                        String textureUrl = "file:///quickskin/" + hash;
                        if (sourcePath != null && sourcePath.toFile().exists()) {
                            textureUrl = "file:///" + sourcePath.toFile().getAbsolutePath().replace('\\', '/');
                        }
                        CPMLOG.info("Remote: CANCELLING registerSkins, textureUrl={}", textureUrl);

                        MinecraftProfileTexture modifiedTexture = new MinecraftProfileTexture(
                                textureUrl, meta);

                        ci.cancel();
                        callback.onSkinTextureAvailable(
                                MinecraftProfileTexture.Type.SKIN, httpLoc, modifiedTexture);
                    }
                }
            }
        } else {
            CPMLOG.info("Skipped remote block: CPM={} isLocal={}",
                    CPMCompatIntegration.isAvailable(),
                    localUuid != null && localUuid.equals(profile.getId()));
        }
    }

    /**
     * Intercept getInsecureSkinInformation to replace the Mojang SKIN entry with our
     * QuickSkin data. This is critical for CPM compatibility: CPM's initTextures() calls
     * this method FIRST and if it finds a SKIN entry, it loads the skin image from Mojang's
     * cache and extracts the embedded CPM 3D model. By replacing the entry with our skin,
     * CPM reads our skin file (which has no CPM model) instead of the Mojang skin.
     */
    @Inject(
            method = "getInsecureSkinInformation",
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            // 1.20.1 has two bytecode RETURN sites in this one target method.
            expect = 2,
            allow = 2
    )
    private void quickskin$overrideSkinInfo(
            GameProfile profile,
            CallbackInfoReturnable<Map<MinecraftProfileTexture.Type, MinecraftProfileTexture>> cir) {

        if (!CPMCompatIntegration.isAvailable()) return;
        if (profile == null || profile.getId() == null) return;

        String hash = null;
        boolean slim = false;

        // Check local player
        UUID localUuid = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                localUuid = mc.getUser().getProfileId();
            }
        } catch (Exception e) {
            return;
        }

        if (localUuid != null && localUuid.equals(profile.getId())) {
            ClientConfig config = ClientConfig.getInstance();
            if (config.activeSkinHash.isEmpty()) return;
            hash = config.activeSkinHash;

            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            String modelType = assetManager.getSkinModelPreference(hash);
            if ("slim".equals(modelType)) {
                slim = true;
            } else if ("auto".equals(modelType)) {
                AssetMetadata skinMeta = assetManager.getMetadata(hash);
                if (skinMeta != null && "slim".equals(skinMeta.skinModel())) {
                    slim = true;
                }
            }
        } else {
            // Check remote player
            PlayerAppearance appearance = PlayerAppearanceService.getInstance()
                    .getAppearance(profile.getId());
            if (appearance == null || appearance.getSkinId() == null) return;
            String skinId = appearance.getSkinId();
            if (!skinId.startsWith("local_skin:")) return;
            hash = skinId.substring("local_skin:".length());
            if ("slim".equals(appearance.getModel())) {
                slim = true;
            }
        }

        if (hash == null || hash.isEmpty()) return;

        // SkullBlockRenderer asks for this map every frame for each rendered player head, so the
        // file lookup is cached per hash by the CPM bridge and nothing is logged here.
        String textureUrl = CPMCompatIntegration.resolveSkinFileUrl(hash);
        if (textureUrl == null) return; // No file available, let Mojang skin through

        // Create modified map with our skin replacing the Mojang SKIN entry
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> original = cir.getReturnValue();
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> modified = new HashMap<>(original);
        modified.put(MinecraftProfileTexture.Type.SKIN,
                new MinecraftProfileTexture(textureUrl, slim ? SLIM_MODEL_METADATA : Map.of()));
        cir.setReturnValue(modified);
    }

}
