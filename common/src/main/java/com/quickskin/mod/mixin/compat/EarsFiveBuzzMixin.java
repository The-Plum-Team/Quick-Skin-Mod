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
 * Mixin into the Ears 2.x feature lookup of the Buzz port (Minecraft 1.21.8 and older).
 * Buzz.getEarsFeatures(Object) receives the player through 1.21.1 and the player render state
 * afterwards, and only recognises textures Ears created itself. Answer from QuickSkin's parsed
 * skin data before Ears looks.
 */
@Pseudo
@Mixin(targets = "diy.y2k.five.buzz.Buzz", remap = false)
public class EarsFiveBuzzMixin {

    @Inject(
            method = "getEarsFeatures",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 1,
            remap = false
    )
    private static void quickskin$getEarsFeatures(Object peer, CallbackInfoReturnable<Object> cir) {
        // Buzz applies its invisibility rule inside this method, so keep it here: an invisible
        // player falls through to Ears, which reports no features for a QuickSkin texture.
        // Buzz does not exist from 1.21.9 onward; those branches only keep the class compiling.
        //? if <1.21 {
        ResourceLocation skin = peer instanceof AbstractClientPlayer player && !player.isInvisible()
                ? player.getSkinTextureLocation() : null;
        //?} else if <1.21.2 {
        ResourceLocation skin = peer instanceof AbstractClientPlayer player && !player.isInvisible()
                ? player.getSkin().texture() : null;
        //?} else if <1.21.9 {
        ResourceLocation skin = peer instanceof PlayerRenderState state && !state.isInvisible
                ? state.skin.texture() : null;
        //?} else if <1.21.11 {
        ResourceLocation skin = peer instanceof AvatarRenderState state && !state.isInvisible
                ? state.skin.body().texturePath() : null;
        //?} else {
        Identifier skin = peer instanceof AvatarRenderState state && !state.isInvisible
                ? state.skin.body().texturePath() : null;
        //?}
        Object features = EarsCompatIntegration.getFeatures(skin);
        if (features != null) {
            cir.setReturnValue(features);
        }
    }
}
