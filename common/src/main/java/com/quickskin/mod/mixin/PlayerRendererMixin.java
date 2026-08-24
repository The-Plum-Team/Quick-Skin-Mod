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
    //? if >=1.21.6 {
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

//? if >=1.21.6 {
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

//? if <1.21.6 {
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
//? if <1.21.6 {
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

//? if <1.21.6 {
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

        if (playerUUID == null) return;

//?}
        // Try service-based lookup (covers registered data from Essential compat or server sync)
//? if <1.21.6 {
        service.markSkinVisible(player.getUUID());
        if (service.hasActiveSkin(player.getUUID())) {
            ResourceLocation customSkin = service.getSkinLocation(player.getUUID());
//?} else if <1.21.11 {
        service.markSkinVisible(playerUUID);
        if (service.hasActiveSkin(playerUUID)) {
            ResourceLocation customSkin = service.getSkinLocation(playerUUID);
//?} else {
        service.markSkinVisible(playerUUID);
        if (service.hasActiveSkin(playerUUID)) {
            Identifier customSkin = service.getSkinLocation(playerUUID);
//?}
            if (customSkin != null) {
                cir.setReturnValue(customSkin);
                return;
            }
        }

        // Title screen fallback: load directly from saved config
//? if <1.21.6 {
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
                    cir.setReturnValue(loc);
                    return;
                }
            }
        }
    }
}
