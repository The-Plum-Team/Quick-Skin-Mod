package com.quickskin.mod.mixin;

import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
//? if >=26.1 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Unique;
//?}
//? if <1.21.9 {
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
//?} else if <1.21.11 {
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
//?}
//? if >=1.21.9 && <26.1 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Unique;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.6 && <1.21.9 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.gui.screens.Screen;
//?}

/**
 * Mixin to intercept AbstractClientPlayer skin lookups.
 *
 * In MC 1.21.1, the unified getSkin() method exists, but Essential mod (for backwards
 * compatibility) may still call PlayerSkin component getters. We intercept at the
 * getSkin() level and let its getters delegate to the unified record.
 */
@Mixin(value = AbstractClientPlayer.class, priority = 2000)
public abstract class MixinAbstractClientPlayer {
//? if >=1.21.9 && <26.1 {

    /**
     * The skin the player in the world shows during the world's draw of the immersive view, as
     * the cape menu opened for one skin previews it ({@link WorldLook#skinLook}), with its model
     * and the cape and wings {@code worn} has, or null for their own.
     */
    @Unique
    private static PlayerSkin quickskin$screenSkin(AbstractClientPlayer player, PlayerSkin worn) {
        if (!ImmersiveMode.posesPlayer()) {
            return null;
        }
        Screen screen = Minecraft.getInstance().screen;
        PreviewPlayerData look = WorldLook.skinLook(ImmersiveMode.session(), player, screen,
                screen instanceof ImmersiveScreen);
        if (look == null) {
            return null;
        }
        var texture = look.getSkinLocation();
        return new PlayerSkin(new ClientAsset.ResourceTexture(texture, texture), worn.cape(), worn.elytra(),
                look.isSlim() ? PlayerModelType.SLIM : PlayerModelType.WIDE, worn.secure());
    }
//?}

    @Shadow
    public abstract PlayerSkin getSkin();
//? if >=26.1 {

    /**
     * The skin the player in the world shows during the world's draw of the immersive view, as
     * the cape menu opened for one skin previews it ({@link WorldLook#skinLook}), with its model
     * and the cape and wings {@code worn} has, or null for their own.
     */
    @Unique
    private static PlayerSkin quickskin$screenSkin(AbstractClientPlayer player, PlayerSkin worn) {
        // From 26.1 the game reads the skin as it extracts the player's render state, before it
        // draws the world, so the whole view is asked, not only its world pass
        if (worn == null || !ImmersiveMode.running()) {
            return null;
        }
    //? if <26.2 {
        Screen screen = Minecraft.getInstance().screen;
    //?} else {
        Screen screen = Minecraft.getInstance().gui.screen();
    //?}
        PreviewPlayerData look = WorldLook.skinLook(ImmersiveMode.session(), player, screen,
                screen instanceof ImmersiveScreen);
        if (look == null) {
            return null;
        }
        Identifier texture = look.getSkinLocation();
        return new PlayerSkin(new ClientAsset.ResourceTexture(texture, texture), worn.cape(), worn.elytra(),
                look.isSlim() ? PlayerModelType.SLIM : PlayerModelType.WIDE, worn.secure());
    }
//?}

    /**
     * Intercept getSkin() at RETURN to modify the returned PlayerSkin.
     * Using @At("RETURN") allows us to get the vanilla/Essential skin first,
     * then modify it before returning to the caller.
     */
    @Inject(
            method = "getSkin",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            expect = 1
    )
    private void quickskin$modifySkin(CallbackInfoReturnable<PlayerSkin> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
//? if >=26.1 {
        // The immersive view shows the skin a screen previews on the player in the world
        PlayerSkin quickskin$look = quickskin$screenSkin(self, cir.getReturnValue());
        if (quickskin$look != null) {
            cir.setReturnValue(quickskin$look);
            return;
        }
//?}
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) return;

        PlayerSkin originalSkin = cir.getReturnValue();
        if (originalSkin == null) return;
//? if >=1.21.9 && <26.1 {

        // The immersive view shows the skin a screen previews on the player in the world
        PlayerSkin quickskin$look = quickskin$screenSkin(self, originalSkin);
        if (quickskin$look != null) {
            cir.setReturnValue(quickskin$look);
            return;
        }
//?}

        boolean hasCustomSkin = service.hasActiveSkin(self.getUUID());
        boolean hasCustomCape = service.hasActiveCape(self.getUUID());
        boolean hasModelOverride = service.hasModelOverride(self.getUUID());
        boolean hasSeparateElytra = service.hasSeparateElytra(self.getUUID());

        // Service-based overrides
        if (hasCustomSkin || hasCustomCape || hasModelOverride || hasSeparateElytra) {
//? if <1.21.9 {
            ResourceLocation skinTexture = originalSkin.texture();
            PlayerSkin.Model skinModel = originalSkin.model();
            ResourceLocation capeTexture = originalSkin.capeTexture();
            ResourceLocation elytraTexture = originalSkin.elytraTexture();
//?} else if <1.21.11 {
            ResourceLocation skinTexture = originalSkin.body().texturePath();
            PlayerModelType skinModel = originalSkin.model();
            ResourceLocation capeTexture = originalSkin.cape() != null ? originalSkin.cape().texturePath() : null;
            ClientAsset.Texture elytraTexture = originalSkin.elytra();
//?} else {
            Identifier skinTexture = originalSkin.body().texturePath();
            PlayerModelType skinModel = originalSkin.model();
            Identifier capeTexture = originalSkin.cape() != null ? originalSkin.cape().texturePath() : null;
            ClientAsset.Texture elytraTexture = originalSkin.elytra();
//?}
            boolean anyOverride = false;

            if (hasCustomSkin) {
//? if <1.21.11 {
                ResourceLocation customSkin = service.getSkinLocation(self.getUUID());
//?} else {
                Identifier customSkin = service.getSkinLocation(self.getUUID());
//?}
                if (customSkin != null) {
                    skinTexture = customSkin;
                    anyOverride = true;
                }
            }

            if (hasCustomSkin || hasModelOverride) {
                String customModel = service.getModelName(self.getUUID());
                if (customModel != null) {
//? if <1.21.9 {
                    skinModel = "slim".equals(customModel) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
//?} else {
                    skinModel = "slim".equals(customModel) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
//?}
                    anyOverride = true;
                }
            }

            if (hasCustomCape) {
//? if <1.21.11 {
                ResourceLocation customCape = service.getCapeLocation(self.getUUID());
//?} else {
                Identifier customCape = service.getCapeLocation(self.getUUID());
//?}
                if (customCape != null) {
                    capeTexture = customCape;
//? if <1.21.9 {
                    elytraTexture = customCape;
//?} else {
                    elytraTexture = new ClientAsset.ResourceTexture(customCape, customCape);
//?}
                    anyOverride = true;
                } else {
                    // An active network animation may deliberately resolve to null while its
                    // bounded first-frame texture is prepared. Never expose the full atlas or an
                    // unrelated profile Elytra then.
                    capeTexture = null;
                    elytraTexture = null;
                    anyOverride = true;
                }
            }

            if (hasSeparateElytra) {
                // The elytra wear another cape, or vanilla's elytra, instead of this cape
//? if <1.21.11 {
                ResourceLocation separateElytra = service.getSeparateElytraLocation(self.getUUID());
//?} else {
                Identifier separateElytra = service.getSeparateElytraLocation(self.getUUID());
//?}
//? if <1.21.9 {
                elytraTexture = separateElytra;
//?} else {
                elytraTexture = new ClientAsset.ResourceTexture(separateElytra, separateElytra);
//?}
                anyOverride = true;
            }

            if (anyOverride) {
                cir.setReturnValue(new PlayerSkin(
//? if <1.21.9 {
                        skinTexture,
                        originalSkin.textureUrl(),
                        capeTexture,
                        elytraTexture,
//?} else {
                        new ClientAsset.ResourceTexture(skinTexture, skinTexture),
                        capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
                        elytraTexture,
//?}
                        skinModel,
                        originalSkin.secure()
                ));
                return;
            }
        }

        // Title screen config fallback
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            boolean hasSkin = !config.activeSkinHash.isEmpty();
            boolean hasCape = !config.activeCapeHash.isEmpty();
//? if <1.21.11 {
            ResourceLocation configuredElytra = service.getConfiguredElytraLocation();
//?} else {
            Identifier configuredElytra = service.getConfiguredElytraLocation();
//?}

            if (hasSkin || hasCape || configuredElytra != null) {
//? if <1.21.9 {
                ResourceLocation skinTexture = originalSkin.texture();
                PlayerSkin.Model skinModel = originalSkin.model();
                ResourceLocation capeTexture = originalSkin.capeTexture();
                ResourceLocation elytraTexture = originalSkin.elytraTexture();
//?} else if <1.21.11 {
                ResourceLocation skinTexture = originalSkin.body().texturePath();
                PlayerModelType skinModel = originalSkin.model();
                ResourceLocation capeTexture = originalSkin.cape() != null ? originalSkin.cape().texturePath() : null;
                ClientAsset.Texture elytraTexture = originalSkin.elytra();
//?} else {
                Identifier skinTexture = originalSkin.body().texturePath();
                PlayerModelType skinModel = originalSkin.model();
                Identifier capeTexture = originalSkin.cape() != null ? originalSkin.cape().texturePath() : null;
                ClientAsset.Texture elytraTexture = originalSkin.elytra();
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
                            AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(config.activeSkinHash);
                            if (metadata != null) {
                                modelType = metadata.skinModel();
                            }
                        }
//? if <1.21.9 {
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
//? if <1.21.9 {
                        elytraTexture = capeLoc;
//?} else {
                        elytraTexture = new ClientAsset.ResourceTexture(capeLoc, capeLoc);
//?}
                        anyOverride = true;
                    } else if (com.quickskin.mod.client.services.SkinCapes.isNoCape(config.activeCapeHash)) {
                        // The skin worn has no cape: none at all, not the account's Mojang cape
                        capeTexture = null;
                        elytraTexture = null;
                        anyOverride = true;
                    }
                }

                if (configuredElytra != null) {
//? if <1.21.9 {
                    elytraTexture = configuredElytra;
//?} else {
                    elytraTexture = new ClientAsset.ResourceTexture(configuredElytra, configuredElytra);
//?}
                    anyOverride = true;
                }

                if (anyOverride) {
                    cir.setReturnValue(new PlayerSkin(
//? if <1.21.9 {
                            skinTexture,
                            originalSkin.textureUrl(),
                            capeTexture,
                            elytraTexture,
//?} else {
                            new ClientAsset.ResourceTexture(skinTexture, skinTexture),
                            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
                            elytraTexture,
//?}
                            skinModel,
                            originalSkin.secure()
                    ));
                }
            }
        }
    }
//? if >=1.21.6 && <1.21.9 {

    /**
     * The immersive view's cape menu opened for one skin shows that skin and its model on the
     * player in the world ({@link WorldLook#skinLook}), during the world's draw only: the draw
     * reads the skin here to choose the renderer of its model and to fill the render state.
     */
    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true, require = 1, expect = 1, allow = 1)
    private void quickskin$immersiveScreenSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        if (!ImmersiveMode.posesPlayer() || CPMCompatIntegration.shouldDeferToCPM()) {
            return;
        }
        Screen screen = Minecraft.getInstance().screen;
        PreviewPlayerData look = WorldLook.skinLook(ImmersiveMode.session(), (Object) this, screen,
                screen instanceof ImmersiveScreen);
        if (look != null) {
            // The cape and the wings are the look's own, bound to the draw (WorldLook.beforeDraw)
            cir.setReturnValue(new PlayerSkin(look.getSkinLocation(), null, null, null,
                    look.isSlim() ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, true));
        }
    }
//?}
}
