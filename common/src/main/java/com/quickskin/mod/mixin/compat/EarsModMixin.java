package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.client.compat.EarsCompatIntegration;
import net.minecraft.client.player.AbstractClientPlayer;
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
    private static void quickskin$getEarsFeatures(AbstractClientPlayer peer, CallbackInfoReturnable<Object> cir) {
        if (EarsCompatIntegration.isDisabledResult(cir.getReturnValue())) {
            //? if <1.21 {
            ResourceLocation skin = peer.getSkinTextureLocation();
            //?} else if <1.21.11 {
            ResourceLocation skin = peer.getSkin().texture();
            //?} else {
            Identifier skin = peer.getSkin().body().texturePath();
            //?}
            Object features = EarsCompatIntegration.getFeatures(skin);
            if (features != null) {
                cir.setReturnValue(features);
            }
        }
    }
}
