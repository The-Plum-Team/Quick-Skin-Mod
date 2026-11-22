//? if >=1.20.2 {
package com.quickskin.mod.mixin;

import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
//? if <1.21.9 {
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
    //? if >=1.21.2 {
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.Entity;
    //?}
//?} else {
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
    //? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
    //?} else {
import net.minecraft.resources.Identifier;
    //?}
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.6 && <1.21.9 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Unique;
//?}

//? if >=1.21.2 {
import java.util.UUID;
//?}
/**
 * Mixin on AvatarRenderer to intercept skin texture lookups at the renderer level.
 *
 * This is needed because some mods (e.g. Essential) create AbstractClientPlayer subclasses
 * that override getSkin() without calling super. The mixin on AbstractClientPlayer.getSkin()
 * doesn't fire for those subclasses, but getTextureLocation() on the renderer is always
 * called regardless of the entity's class hierarchy.
 */
//? if <1.21.9 {
@Mixin(PlayerRenderer.class)
//?} else {
@Mixin(AvatarRenderer.class)
//?}
public class PlayerRendererMixin {

//? if <1.21.2 {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;",
//?} else if <1.21.9 {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)Lnet/minecraft/resources/ResourceLocation;",
//?} else if <1.21.11 {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/resources/ResourceLocation;",
//?} else {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/resources/Identifier;",
//?}
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1)
//? if <1.21.2 {
    private void quickskin$overrideTextureLocation(AbstractClientPlayer player, CallbackInfoReturnable<ResourceLocation> cir) {
//?} else if <1.21.9 {
    private void quickskin$overrideTextureLocation(PlayerRenderState renderState, CallbackInfoReturnable<ResourceLocation> cir) {
//?} else if <1.21.11 {
    private void quickskin$overrideTextureLocation(AvatarRenderState renderState, CallbackInfoReturnable<ResourceLocation> cir) {
//?} else {
    private void quickskin$overrideTextureLocation(AvatarRenderState renderState, CallbackInfoReturnable<Identifier> cir) {
//?}
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) return;
//? if >=1.21.9 && <26.1 {
        // The immersive cape menu opened for one skin shows that skin on the player in the world,
        // which the skin lookup (MixinAbstractClientPlayer) already put in the state: asked by the
        // same rule as that lookup, this one leaves it there
        if (com.quickskin.mod.client.immersive.ImmersiveMode.posesPlayer()) {
            net.minecraft.client.gui.screens.Screen quickskin$screen = Minecraft.getInstance().screen;
            if (com.quickskin.mod.client.rendering.WorldLook.skinLookOf(
                    com.quickskin.mod.client.immersive.ImmersiveMode.session(), renderState, quickskin$screen,
                    quickskin$screen instanceof com.quickskin.mod.client.immersive.ImmersiveScreen) != null) {
                return;
            }
        }
//?}

//? if <1.21.2 {
//?} else {
        // Look up the actual player entity from the render state to get UUID
        UUID playerUUID = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity entity = mc.level.getEntity(renderState.id);
            if (entity instanceof AbstractClientPlayer player) {
                playerUUID = player.getUUID();
            }
        }

//? if <1.21.6 {
        if (playerUUID == null && mc.level == null) {
            playerUUID = mc.getUser().getProfileId();
        }
//?}
        if (playerUUID == null) return;
//? if >=26.1 {
        // The immersive view's cape menu opened for one skin shows that skin on the player in the
        // world (WorldLook.skinLook), which the player's skin lookup already put in the state
        if (mc.player != null && renderState.id == mc.player.getId()
                && com.quickskin.mod.client.immersive.ImmersiveMode.running()) {
    //? if <26.2 {
            net.minecraft.client.gui.screens.Screen quickskin$screen = mc.screen;
    //?} else {
            net.minecraft.client.gui.screens.Screen quickskin$screen = mc.gui.screen();
    //?}
            if (com.quickskin.mod.client.rendering.WorldLook.skinLook(
                    com.quickskin.mod.client.immersive.ImmersiveMode.session(), mc.player, quickskin$screen,
                    quickskin$screen instanceof com.quickskin.mod.client.immersive.ImmersiveScreen) != null) {
                return;
            }
        }
//?}

//?}
        // Cancelling here skips the RETURN callbacks other mods attach to this lookup (Entity
        // Texture Features builds its skin features there), so only do it when vanilla, which
        // already reads the Quick Skin texture through getSkin(), would answer differently.
//? if <1.21.2 {
        var vanilla = player.getSkin();
        ResourceLocation vanillaSkin = vanilla == null ? null : vanilla.texture();
//?} else if <1.21.9 {
        ResourceLocation vanillaSkin = renderState.skin == null ? null : renderState.skin.texture();
//?} else if <1.21.11 {
        ResourceLocation vanillaSkin = renderState.skin == null || renderState.skin.body() == null
                ? null : renderState.skin.body().texturePath();
//?} else {
        Identifier vanillaSkin = renderState.skin == null || renderState.skin.body() == null
                ? null : renderState.skin.body().texturePath();
//?}
//? if >=1.21.6 && <1.21.9 {
        // The immersive view's cape menu opened for one skin shows that skin on the player. The
        // render state took it from getSkin (MixinAbstractClientPlayer); a player class that
        // answers getSkin itself (Essential) gets it here instead.
        ResourceLocation screenSkin = quickskin$screenSkin(renderState);
        if (screenSkin != null) {
            if (!screenSkin.equals(vanillaSkin)) cir.setReturnValue(screenSkin);
            return;
        }
//?}

        // Try service-based lookup (covers registered data from Essential compat or server sync)
//? if <1.21.2 {
        service.markSkinVisible(player.getUUID());
        if (service.hasActiveSkin(player.getUUID()) && !CPMCompatIntegration.isWearingCpmModel(player.getUUID())) {
            ResourceLocation customSkin = service.getSkinLocation(player.getUUID());
//?} else if <1.21.11 {
        service.markSkinVisible(playerUUID);
        if (service.hasActiveSkin(playerUUID) && !CPMCompatIntegration.isWearingCpmModel(playerUUID)) {
            ResourceLocation customSkin = service.getSkinLocation(playerUUID);
//?} else {
        service.markSkinVisible(playerUUID);
        if (service.hasActiveSkin(playerUUID) && !CPMCompatIntegration.isWearingCpmModel(playerUUID)) {
            Identifier customSkin = service.getSkinLocation(playerUUID);
//?}
            if (customSkin != null) {
                if (!customSkin.equals(vanillaSkin)) cir.setReturnValue(customSkin);
                return;
            }
        }

        // Title screen fallback: load directly from saved config
//? if <1.21.2 {
        if (Minecraft.getInstance().level == null) {
//?} else {
        if (mc.level == null) {
//?}
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeSkinHash.isEmpty()) {
//? if <1.21.11 {
                ResourceLocation loc = LocalAssetManager.getInstance()
//?} else {
                Identifier loc = LocalAssetManager.getInstance()
//?}
                        .getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                if (loc != null) {
                    if (!loc.equals(vanillaSkin)) cir.setReturnValue(loc);
                    return;
                }
            }
        }
    }
//? if >=1.21.6 && <1.21.9 {

    /** The skin an immersive screen shows on the local player's draw ({@link WorldLook#skinLook}), or null. */
    @Unique
    private static ResourceLocation quickskin$screenSkin(PlayerRenderState renderState) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ImmersiveMode.posesPlayer() || minecraft.player == null || renderState.id != minecraft.player.getId()) {
            return null;
        }
        Screen screen = minecraft.screen;
        PreviewPlayerData look = WorldLook.skinLook(ImmersiveMode.session(), minecraft.player, screen,
                screen instanceof ImmersiveScreen);
        return look == null ? null : look.getSkinLocation();
    }
//?}
}
//?}
