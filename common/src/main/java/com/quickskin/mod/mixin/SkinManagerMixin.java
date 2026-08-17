package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
//? if <1.21.11 {
import com.quickskin.mod.client.compat.CPMCompatIntegration;
//?} else {
//?}
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
//? if <1.21.11 {
import net.minecraft.client.resources.PlayerSkin;
//?} else {
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
//?}
import net.minecraft.client.resources.SkinManager;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if <1.21.4 {
//?} else {
import java.util.Optional;
//?}
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Mixin on SkinManager to intercept skin resolution at the canonical level.
 *
 * This catches ALL skin lookups including those by mods like Essential that bypass
 * AbstractClientPlayer.getSkin() and PlayerRenderer.getTextureLocation() entirely.
 *
 * Two injection points:
 * - getInsecureSkin(GameProfile) — synchronous, used by vanilla code paths
 * - getOrLoad(GameProfile) — async (CompletableFuture), used by Essential's FallbackPlayer on 1.20.2+
 *
 * Essential for MC >= 1.20.2 uses FallbackPlayer which calls getOrLoad() directly,
 * bypassing getInsecureSkin(). The getOrLoad mixin wraps the future with thenApply
 * so the skin override propagates to both paths.
 */
@Mixin(SkinManager.class)
public class SkinManagerMixin {

    /**
     * Shared helper that applies QuickSkin overrides to a PlayerSkin.
     * Used by both getInsecureSkin and getOrLoad mixin handlers.
     *
     * @param original the original PlayerSkin from Mojang/vanilla
     * @param uuid     the player's UUID
     * @return the modified PlayerSkin, or the original if no overrides apply
     */
    @Unique
    private static PlayerSkin quickskin$applyOverrides(PlayerSkin original, UUID uuid) {
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
//? if <1.21.4 {
                ResourceLocation customSkin;
                if (CPMCompatIntegration.isAvailable()) {
                    // When CPM is installed, register skin as HttpTexture so CPM can
                    // read pixel data and extract embedded 3D model from the PNG file.
                    // CPM's 1.21+ pipeline checks instanceof HttpTexture on the texture.
                    com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(uuid);
                    String hash = null;
                    if (appearance != null && appearance.getSkinId() != null) {
                        String skinId = appearance.getSkinId();
                        if (skinId.startsWith("local_skin:")) {
                            hash = skinId.substring("local_skin:".length());
                        }
                    }
                    if (hash != null) {
                        customSkin = CPMCompatIntegration.getOrRegisterHttpTexture(hash);
                        if (customSkin == null) {
                            customSkin = service.getSkinLocation(uuid);
                        }
                    } else {
                        customSkin = service.getSkinLocation(uuid);
                    }
                } else {
                    customSkin = service.getSkinLocation(uuid);
                }
//?} else if <1.21.11 {
                // Minecraft 1.21.4 removed HttpTexture. Keep normal QuickSkin texture
                // replacement and activate the one-time explicit degraded CPM capability log.
                CPMCompatIntegration.isAvailable();
                ResourceLocation customSkin = service.getSkinLocation(uuid);
//?} else {
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
     * Intercept createLookup (26.2 synchronous path; renamed from getInsecureSkin).
     * In 26.2 SkinManager.createLookup(GameProfile, boolean) returns a Supplier&lt;PlayerSkin&gt; instead of
     * a resolved PlayerSkin, so we wrap the supplier to apply QuickSkin overrides on resolution.
     * Used by vanilla code and any mod that resolves a skin synchronously via SkinManager.
     */
//? if <1.21.11 {
    @Inject(
            method = "getInsecureSkin",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            // Minecraft 1.21.4-1.21.5 has two RETURN opcodes here; both must be wrapped.
            expect = 2,
            allow = 2
    )
    private void quickskin$modifyInsecureSkinLegacy(GameProfile profile, CallbackInfoReturnable<PlayerSkin> cir) {
        UUID uuid = profile.getId();
//?} else if <26.2 {
    @Inject(
            method = "getInsecureSkin",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$modifyInsecureSkin(GameProfile profile, CallbackInfoReturnable<PlayerSkin> cir) {
        UUID uuid = profile.id();
//?} else {
    @Inject(
            method = "createLookup",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$modifyInsecureSkin(GameProfile profile, boolean secure, CallbackInfoReturnable<java.util.function.Supplier<PlayerSkin>> cir) {
        UUID uuid = profile.id();
//?}
        if (uuid == null) return;

//? if <26.2 {
        PlayerSkin result = quickskin$applyOverrides(cir.getReturnValue(), uuid);
        if (result != cir.getReturnValue()) {
            cir.setReturnValue(result);
        }
//?} else {
        java.util.function.Supplier<PlayerSkin> original = cir.getReturnValue();
        if (original == null) return;

        cir.setReturnValue(() -> quickskin$applyOverrides(original.get(), uuid));
//?}
    }

    /**
     * Intercept getOrLoad (async path returning CompletableFuture<PlayerSkin>).
     *
     * Essential for MC >= 1.20.2 uses FallbackPlayer which calls getOrLoad() directly,
     * bypassing getInsecureSkin(). We wrap the returned future with thenApply to apply
     * QuickSkin overrides when the future resolves.
     *
     * Note: thenApply runs synchronously when the source future is already completed
     * (cache hit), so getInsecureSkin (which calls getOrLoad().getNow(null)) will also
     * see the modified skin through this mixin.
     */
//? if <1.21.4 {
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
//?} else if <1.21.11 {
    /** In Minecraft 1.21.4-1.21.10, getOrLoad returns an optional skin. */
    @Inject(
            method = "getOrLoad",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$modifyGetOrLoad(GameProfile profile, CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
        UUID uuid = profile.getId();
//?} else if <26.1.2 {
    /**
     * In MC 1.21.11+, getOrLoad returns CompletableFuture<Optional<PlayerSkin>>.
     */
    @Inject(
            method = "getOrLoad",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$modifyGetOrLoad(GameProfile profile, CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
        UUID uuid = profile.id();
//?} else if <26.2 {
    /** In MC 1.21.11+, getOrLoad returns CompletableFuture<Optional<PlayerSkin>>. */
    @Inject(
            method = "getOrLoad",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$modifyGetOrLoad(GameProfile profile, CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
        UUID uuid = profile.id();
//?} else {
    /** In MC 26.2, the async loader was renamed to get. */
    @Inject(
            method = "get",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$modifyGetOrLoad(GameProfile profile, CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
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

//? if <1.21.4 {
        CompletableFuture<PlayerSkin> original = cir.getReturnValue();
        CompletableFuture<PlayerSkin> modified = original.thenApply(skin -> {
            return quickskin$applyOverrides(skin, uuid);
//?} else {
        CompletableFuture<Optional<PlayerSkin>> original = cir.getReturnValue();
        CompletableFuture<Optional<PlayerSkin>> modified = original.thenApply(optSkin -> {
            return optSkin.map(skin -> quickskin$applyOverrides(skin, uuid));
//?}
        });
        cir.setReturnValue(modified);
    }
}
