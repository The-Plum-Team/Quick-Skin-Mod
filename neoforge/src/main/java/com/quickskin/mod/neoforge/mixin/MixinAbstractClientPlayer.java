//? if <1.21.6 {
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
 * NeoForge-specific mixin to intercept AbstractClientPlayer skin lookups.
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
        boolean hasSeparateElytra = service.hasSeparateElytra(self.getUUID());

        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride && !hasSeparateElytra) {
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

        if (hasSeparateElytra) {
            // The elytra wear another cape, or vanilla's elytra, instead of the cape
            elytraTexture = service.getSeparateElytraLocation(self.getUUID());
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
//?} else {
package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.client.services.PlayerAppearanceService;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
//? if >=26.1 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//?}
//? if <1.21.9 {
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
//?} else if <1.21.11 {
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.Identifier;
//?}
//? if >=1.21.9 && <26.1 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.6 && <1.21.9 {
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//?}

import java.lang.reflect.Field;

/**
 * NeoForge-specific mixin to intercept AbstractClientPlayer skin lookups.
 * Uses Mojmap names directly since NeoForge uses Mojmap at runtime.
 */
@Mixin(value = AbstractClientPlayer.class, priority = 100)
public abstract class MixinAbstractClientPlayer {

    @Unique
    private static Field quickskin$playerInfoField = null;

    @Unique
    private static boolean quickskin$fieldSearched = false;
//? if >=26.1 {

    /**
     * The skin the player in the world shows during the world's draw of the immersive view, as
     * the cape menu opened for one skin previews it ({@link WorldLook#skinLook}), with its model
     * and the cape and wings the player's own has, or null for their own.
     */
    @Unique
    private static PlayerSkin quickskin$screenSkin(AbstractClientPlayer player) {
        // From 26.1 the game reads the skin as it extracts the player's render state, before it
        // draws the world, so the whole view is asked, not only its world pass
        if (!ImmersiveMode.running()) {
            return null;
        }
    //? if <26.2 {
        Screen screen = Minecraft.getInstance().screen;
    //?} else {
        Screen screen = Minecraft.getInstance().gui.screen();
    //?}
        PreviewPlayerData look = WorldLook.skinLook(ImmersiveMode.session(), player, screen,
                screen instanceof ImmersiveScreen);
        PlayerInfo playerInfo = look == null ? null : quickskin$getPlayerInfo(player);
        PlayerSkin worn = playerInfo == null ? null : playerInfo.getSkin();
        if (worn == null) {
            return null;
        }
        Identifier texture = look.getSkinLocation();
        return new PlayerSkin(new ClientAsset.ResourceTexture(texture, texture), worn.cape(), worn.elytra(),
                look.isSlim() ? PlayerModelType.SLIM : PlayerModelType.WIDE, worn.secure());
    }
//?}

    @Inject(
            method = "getSkin",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void quickskin$overrideSkinAtHead(CallbackInfoReturnable<PlayerSkin> cir) {
        //? if >=1.21.6 && <1.21.9 {
        if (quickskin$immersiveScreenSkin(cir)) {
            return;
        }
        //?}
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return;
        }
//? if >=26.1 {
        // The immersive view shows the skin a screen previews on the player in the world
        PlayerSkin quickskin$look = quickskin$screenSkin((AbstractClientPlayer) (Object) this);
        if (quickskin$look != null) {
            cir.setReturnValue(quickskin$look);
            return;
        }
//?}

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
//? if >=1.21.9 && <26.1 {

        // The immersive view shows the skin a screen previews on the player in the world
        PlayerSkin quickskin$look = quickskin$screenSkin(self);
        if (quickskin$look != null) {
            cir.setReturnValue(quickskin$look);
            return;
        }
//?}

        boolean hasCustomSkin = service.hasActiveSkin(self.getUUID());
        boolean hasCustomCape = service.hasActiveCape(self.getUUID());
        boolean hasModelOverride = service.hasModelOverride(self.getUUID());
        boolean hasSeparateElytra = service.hasSeparateElytra(self.getUUID());

        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride && !hasSeparateElytra) {
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

        if (hasCustomSkin) {
            //? if <1.21.11 {
            ResourceLocation customSkin = service.getSkinLocation(self.getUUID());
            //?} else {
            Identifier customSkin = service.getSkinLocation(self.getUUID());
            //?}
            if (customSkin != null) {
                skinTexture = customSkin;
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
                // An active Quick Skin cape owns the profile Elytra input too; vanilla gives that
                // dedicated field priority and would otherwise keep only the worn wings vanilla.
                //? if <1.21.9 {
                elytraTexture = customCape;
                //?} else {
                elytraTexture = new ClientAsset.ResourceTexture(customCape, customCape);
                //?}
            } else {
                // Pending network animations intentionally resolve to null until their bounded
                // first-frame texture exists. Never publish the stacked atlas to other mods or
                // leave an unrelated profile Elytra beside a pending custom cape.
                capeTexture = null;
                elytraTexture = null;
            }
        }

        if (hasSeparateElytra) {
            // The elytra wear another cape, or vanilla's elytra, instead of the cape
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
        }

        PlayerSkin customSkin = new PlayerSkin(
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
        );

        cir.setReturnValue(customSkin);
    }
//? if >=1.21.9 && <26.1 {

    /**
     * The skin the player in the world shows during the world's draw of the immersive view, as
     * the cape menu opened for one skin previews it ({@link WorldLook#skinLook}), with its model
     * and the cape and wings the player's own has, or null for their own.
     */
    @Unique
    private static PlayerSkin quickskin$screenSkin(AbstractClientPlayer player) {
        if (!ImmersiveMode.posesPlayer()) {
            return null;
        }
        Screen screen = Minecraft.getInstance().screen;
        PreviewPlayerData look = WorldLook.skinLook(ImmersiveMode.session(), player, screen,
                screen instanceof ImmersiveScreen);
        PlayerInfo playerInfo = look == null ? null : quickskin$getPlayerInfo(player);
        PlayerSkin worn = playerInfo == null ? null : playerInfo.getSkin();
        if (worn == null) {
            return null;
        }
        var texture = look.getSkinLocation();
        return new PlayerSkin(new ClientAsset.ResourceTexture(texture, texture), worn.cape(), worn.elytra(),
                look.isSlim() ? PlayerModelType.SLIM : PlayerModelType.WIDE, worn.secure());
    }
//?}

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
//? if >=1.21.6 && <1.21.9 {

    /**
     * The immersive view's cape menu opened for one skin shows that skin and its model on the
     * player in the world ({@link WorldLook#skinLook}), during the world's draw only: the draw
     * reads the skin here to choose the renderer of its model and to fill the render state. The
     * common twin of this mixin does the same on Fabric.
     */
    @Unique
    private boolean quickskin$immersiveScreenSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        if (!ImmersiveMode.posesPlayer() || CPMCompatIntegration.shouldDeferToCPM()) {
            return false;
        }
        Screen screen = Minecraft.getInstance().screen;
        PreviewPlayerData look = WorldLook.skinLook(ImmersiveMode.session(), (Object) this, screen,
                screen instanceof ImmersiveScreen);
        if (look == null) {
            return false;
        }
        // The cape and the wings are the look's own, bound to the draw (WorldLook.beforeDraw)
        cir.setReturnValue(new PlayerSkin(look.getSkinLocation(), null, null, null,
                look.isSlim() ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, true));
        return true;
    }
//?}
}
//?}
