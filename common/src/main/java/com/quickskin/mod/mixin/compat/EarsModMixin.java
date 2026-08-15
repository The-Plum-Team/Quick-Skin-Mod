package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.client.compat.EarsCompatIntegration;
//? if <1.21.2 {
import net.minecraft.client.player.AbstractClientPlayer;
//?} else if <1.21.9 {
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
//?} else {
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?}
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into Ears' EarsMod.getEarsFeatures() for Fabric.
 * If Ears returns DISABLED (because the texture isn't an EarsFeaturesHolder),
 * check our stored features from QuickSkin's parsed skin data.
 */
@Pseudo
@Mixin(targets = "com.unascribed.ears.EarsMod")
public class EarsModMixin {

    @Inject(
            method = "getEarsFeatures",
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 2,
            remap = false
    )
    // Ears follows Minecraft's renderer API: the hook receives the player through 1.21.1,
    // PlayerRenderState from 1.21.2 through 1.21.8, and AvatarRenderState from 1.21.9 onward.
    //? if <1.21.2 {
    private static void quickskin$getEarsFeatures(AbstractClientPlayer peer, CallbackInfoReturnable<Object> cir) {
    //?} else if <1.21.9 {
    private static void quickskin$getEarsFeatures(PlayerRenderState peer, CallbackInfoReturnable<Object> cir) {
    //?} else {
    private static void quickskin$getEarsFeatures(AvatarRenderState peer, CallbackInfoReturnable<Object> cir) {
    //?}
        if (EarsCompatIntegration.isDisabledResult(cir.getReturnValue())) {
            //? if <1.21 {
            ResourceLocation skin = peer.getSkinTextureLocation();
            //?} else if <1.21.2 {
            ResourceLocation skin = peer.getSkin().texture();
            //?} else if <1.21.9 {
            ResourceLocation skin = peer.skin.texture();
            //?} else if <1.21.11 {
            ResourceLocation skin = peer.skin.body().texturePath();
            //?} else {
            Identifier skin = peer.skin.body().texturePath();
            //?}
            Object features = EarsCompatIntegration.getFeatures(skin);
            if (features != null) {
                cir.setReturnValue(features);
            }
        }
    }
}
