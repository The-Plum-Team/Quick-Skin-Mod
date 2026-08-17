package com.quickskin.mod.neoforge.mixin;

import com.mojang.authlib.GameProfile;
//? if <1.21.11 {
import com.quickskin.mod.QuickSkin;
//?} else {
//?}
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
//? if <1.21.11 {
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.client.resources.PlayerSkin;
//?} else {
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
//?}
import net.minecraft.client.resources.SkinManager;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if <1.21.11 {
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
//?} else {
import java.util.Optional;
//?}
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
//? if <1.21.11 {
import java.util.concurrent.ConcurrentHashMap;
//?} else {
//?}

/**
 * NeoForge-specific mixin on SkinManager to intercept skin resolution at the canonical level.
 * Uses Mojmap names directly since NeoForge uses Mojmap at runtime.
 *
 * This catches ALL skin lookups including those by mods like Essential that bypass
 * AbstractClientPlayer.getSkin() and PlayerRenderer.getTextureLocation() entirely.
 *
 * Two injection points, whose names and return types changed in 1.21.11:
 * - getInsecureSkin / createLookup — synchronous lookup construction
 * - getOrLoad / get — async loading
 *
 * Essential for MC >= 1.20.2 uses FallbackPlayer which calls getOrLoad() directly,
 * bypassing getInsecureSkin(). The getOrLoad mixin wraps the future with thenApply
 * so the skin override propagates to both paths.
 */
@Mixin(SkinManager.class)
public class SkinManagerMixin {
//? if <1.21.11 {

    // Cache for HttpTexture-backed ResourceLocations (for CPM compat)
    @Unique
    private static final Map<String, ResourceLocation> quickskin$httpTextureCache = new ConcurrentHashMap<>();
//?} else {
//?}

    /**
     * Shared helper that applies QuickSkin overrides to a PlayerSkin.
     * Used by both synchronous and asynchronous lookup mixin handlers.
     */
    @Unique
    private static PlayerSkin quickskin$applyOverrides(PlayerSkin original, UUID uuid, String profileName) {
        if (original == null || uuid == null) return original;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) return original;

        boolean hasCustomSkin = service.hasActiveSkin(uuid);
        boolean hasCustomCape = service.hasActiveCape(uuid);
        boolean hasModelOverride = service.hasModelOverride(uuid);

        // Try service-based overrides
        if (hasCustomSkin || hasCustomCape || hasModelOverride) {
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
            boolean anyOverride = false;

            if (hasCustomSkin) {
//? if <1.21.11 {
                ResourceLocation customSkin;
                if (CPMCompatIntegration.isAvailable()) {
                    // When CPM is installed, register skin as HttpTexture so CPM can read pixel data
                    customSkin = quickskin$getOrRegisterHttpTexture(uuid, service);
                } else {
                    customSkin = service.getSkinLocation(uuid);
                }
//?} else if <26.2 {
                // CPM 1.21.11+ bypasses registered player textures. Activate the
                // degraded-capability log, then keep QuickSkin's normal texture.
                CPMCompatIntegration.isAvailable();
                Identifier customSkin = service.getSkinLocation(uuid);
//?} else {
                // CPM 1.21.11+ reads authenticated profile texture payloads directly;
                // an HttpTexture registration cannot bridge embedded PNG data here.
                // The availability call activates the explicit degraded-capability log.
                CPMCompatIntegration.isAvailable();
                Identifier customSkin = service.getSkinLocation(uuid);
//?}
                if (customSkin != null) {
                    skinTexture = customSkin;
                    anyOverride = true;
                }
            }

            if (hasCustomSkin || hasModelOverride) {
                String customModel = service.getModelName(uuid);
                if (customModel != null) {
//? if <1.21.11 {
                    skinModel = "slim".equals(customModel) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
//?} else {
                    skinModel = "slim".equals(customModel) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
//?}
                    anyOverride = true;
                }
            }

            if (hasCustomCape) {
//? if <1.21.11 {
                ResourceLocation customCape = service.getCapeLocation(uuid);
//?} else {
                Identifier customCape = service.getCapeLocation(uuid);
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
                    // A pending custom texture must not fall through to unrelated Mojang cape or
                    // Elytra assets while its bounded first frame is being prepared.
                    capeTexture = null;
                    elytraTexture = null;
                    anyOverride = true;
                }
            }

            if (anyOverride) {
                return new PlayerSkin(
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
            }
        }

//? if <1.21.11 {
        // Title screen config fallback
        if (Minecraft.getInstance().level == null) {
//?} else {
        // Config-based fallback for local player (title screen and in-world)
        boolean isLocalPlayer = uuid.equals(Minecraft.getInstance().getUser().getProfileId());
        if (isLocalPlayer) {
//?}
            ClientConfig config = ClientConfig.getInstance();
            boolean hasSkin = !config.activeSkinHash.isEmpty();
            boolean hasCape = !config.activeCapeHash.isEmpty();

            if (hasSkin || hasCape) {
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
                            var metadata = LocalAssetManager.getInstance().getMetadata(config.activeSkinHash);
                            if (metadata != null) {
                                modelType = metadata.skinModel();
                            }
                        }
//? if <1.21.11 {
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
                    return new PlayerSkin(
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
                }
            }
        }

        return original;
    }

    /**
     * Intercept the synchronous lookup path. In 1.21.11,
     * SkinManager.createLookup(GameProfile, boolean) returns a Supplier&lt;PlayerSkin&gt; instead of
     * a resolved PlayerSkin, so we wrap the supplier to apply QuickSkin overrides on resolution.
     */
//? if <1.21.11 {
    @Unique
    private static ResourceLocation quickskin$getOrRegisterHttpTexture(UUID uuid, PlayerAppearanceService service) {
        com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(uuid);
        if (appearance == null) return null;

        String skinId = appearance.getSkinId();
        if (skinId == null || skinId.isEmpty()) return null;

        // Extract hash from skinId (format: "local_skin:hash")
        String hash = null;
        if (skinId.startsWith("local_skin:")) {
            hash = skinId.substring("local_skin:".length());
        }

        if (hash == null || hash.isEmpty()) {
            // Not a local skin (could be network skin) - fall back to DynamicTexture
            return service.getSkinLocation(uuid);
        }

        // Check cache first
        ResourceLocation cached = quickskin$httpTextureCache.get(hash);
        if (cached != null) {
            // Verify it's still registered in TextureManager
            if (Minecraft.getInstance().getTextureManager().getTexture(cached, null) != null) {
                return cached;
            }
            quickskin$httpTextureCache.remove(hash);
        }

        // Find the skin file on disk
        Path sourcePath = LocalAssetManager.getInstance().getSourcePath(hash);

        // Fallback: network-received textures stored in memory -- write to a temp file for CPM
        if (sourcePath == null || !sourcePath.toFile().exists()) {
            sourcePath = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                    .getOrCreateTempFile(hash, "skin");
        }

        if (sourcePath == null || !sourcePath.toFile().exists()) {
            // File not found, fall back to DynamicTexture
            return service.getSkinLocation(uuid);
        }

        // Create an HttpTexture pointing to the local file
        File skinFile = sourcePath.toFile();
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                QuickSkin.MOD_ID,
                "cpm_bridge/" + hash
        );

        // The HttpTexture constructor: (File file, String urlString, ResourceLocation fallback, boolean processLegacySkin, Runnable onDownloaded)
        // - file: the local skin file CPM will read
        // - urlString: dummy URL (CPM prefers file if it exists)
        // - fallback: fallback texture if loading fails
        // - processLegacySkin: true to handle old 64x32 skins
        // - onDownloaded: callback after download (no-op for us)
        HttpTexture httpTexture = new HttpTexture(
                skinFile,
                "file:///" + skinFile.getAbsolutePath().replace('\\', '/'),
                ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png"),
                true,
                () -> {}
        );

        Minecraft.getInstance().getTextureManager().register(location, httpTexture);
        quickskin$httpTextureCache.put(hash, location);

        return location;
    }

    /**
     * Intercept getInsecureSkin (synchronous path).
     * Used by vanilla code and any mod that calls SkinManager.getInsecureSkin() directly.
     */
    @Inject(
            method = "getInsecureSkin",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$modifyInsecureSkin(GameProfile profile, CallbackInfoReturnable<PlayerSkin> cir) {
        UUID uuid = profile.getId();
//?} else {
    @Inject(
            method = "createLookup",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 3,
            allow = 3
    )
    private void quickskin$modifyCreateLookup(GameProfile profile, boolean secure, CallbackInfoReturnable<java.util.function.Supplier<PlayerSkin>> cir) {
        UUID uuid = profile.id();
//?}
        if (uuid == null) return;

//? if <1.21.11 {
        PlayerSkin result = quickskin$applyOverrides(cir.getReturnValue(), uuid, profile.getName());
        if (result != cir.getReturnValue()) {
            cir.setReturnValue(result);
        }
//?} else {
        java.util.function.Supplier<PlayerSkin> original = cir.getReturnValue();
        if (original == null) return;

        cir.setReturnValue(() -> quickskin$applyOverrides(original.get(), uuid, profile.name()));
//?}
    }

    /**
     * Intercept the async loading path returning a CompletableFuture.
     *
     * Essential for MC >= 1.20.2 uses FallbackPlayer which calls getOrLoad() directly,
     * bypassing getInsecureSkin(). We wrap the returned future with thenApply to apply
     * QuickSkin overrides when the future resolves.
     */
//? if <1.21.11 {
    @Inject(
            method = "getOrLoad",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$modifyGetOrLoad(GameProfile profile, CallbackInfoReturnable<CompletableFuture<PlayerSkin>> cir) {
        UUID uuid = profile.getId();
//?} else {
    @Inject(
            method = "get",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 2,
            allow = 2
    )
    private void quickskin$modifyGet(GameProfile profile, CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
        UUID uuid = profile.id();
//?}
        if (uuid == null) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        boolean hasServiceOverrides = false;
        boolean hasTitleScreenFallback = false;

        if (service != null) {
            hasServiceOverrides = service.hasActiveSkin(uuid)
                    || service.hasActiveCape(uuid)
                    || service.hasModelOverride(uuid);
        }

//? if <1.21.11 {
        if (!hasServiceOverrides && Minecraft.getInstance().level == null) {
//?} else {
        boolean isLocalPlayer = uuid.equals(Minecraft.getInstance().getUser().getProfileId());
        if (!hasServiceOverrides && isLocalPlayer) {
//?}
            ClientConfig config = ClientConfig.getInstance();
            hasTitleScreenFallback = !config.activeSkinHash.isEmpty() || !config.activeCapeHash.isEmpty();
        }

        // Only wrap the future if we actually have overrides to apply
        if (!hasServiceOverrides && !hasTitleScreenFallback) return;

//? if <1.21.11 {
        String profileName = profile.getName();
        CompletableFuture<PlayerSkin> original = cir.getReturnValue();
        CompletableFuture<PlayerSkin> modified = original.thenApply(skin ->
            quickskin$applyOverrides(skin, uuid, profileName)
//?} else {
        String profileName = profile.name();
        CompletableFuture<Optional<PlayerSkin>> original = cir.getReturnValue();
        CompletableFuture<Optional<PlayerSkin>> modified = original.thenApply(optSkin ->
            optSkin.map(skin -> quickskin$applyOverrides(skin, uuid, profileName))
//?}
        );
        cir.setReturnValue(modified);
    }
}
