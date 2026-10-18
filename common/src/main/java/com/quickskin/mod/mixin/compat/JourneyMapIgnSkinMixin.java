package com.quickskin.mod.mixin.compat;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.compat.JourneyMapPlayerFaces;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives JourneyMap's player head icons the Quick Skin skin a player wears.
 *
 * <p>Every JourneyMap build from 1.20.1 to 26.3 resolves a player's face through
 * {@code IgnSkin.getFace(GameProfile)}, and none of them reaches Quick Skin's skin on its own
 * (see {@link JourneyMapPlayerFaces}). JourneyMap's classes are not obfuscated, so the target is
 * named by string and nothing here is remapped; without a Quick Skin skin JourneyMap keeps its
 * own face.</p>
 */
@Pseudo
@Mixin(targets = "journeymap.client.texture.IgnSkin", remap = false)
public abstract class JourneyMapIgnSkinMixin {

    @Inject(
            method = "getFace",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 1,
            remap = false
    )
    private static void quickskin$quickSkinFace(GameProfile profile, CallbackInfoReturnable<DynamicTexture> cir) {
        if (profile == null) {
            return;
        }
        //? if <1.21.9 {
        DynamicTexture face = JourneyMapPlayerFaces.face(profile.getId());
        //?} else {
        DynamicTexture face = JourneyMapPlayerFaces.face(profile.id());
        //?}
        if (face != null) {
            cir.setReturnValue(face);
        }
    }
}
