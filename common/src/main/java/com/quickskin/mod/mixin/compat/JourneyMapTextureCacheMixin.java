package com.quickskin.mod.mixin.compat;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.compat.JourneyMapPlayerFaces;
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
 * Points JourneyMap's minimap player icons at the Quick Skin face.
 *
 * <p>JourneyMap 1.21.6 and later draw those icons by texture id: {@code getPlayerSkinLocation}
 * registers the face of {@code IgnSkin.getFace} once per player and returns that id for the rest
 * of the session, so a face registered before the Quick Skin appearance arrived, or before the
 * player changed skin, would never be replaced. Older builds have no such method and leave this
 * injection unapplied.</p>
 */
@Pseudo
@Mixin(targets = "journeymap.client.texture.TextureCache", remap = false)
public abstract class JourneyMapTextureCacheMixin {

    @Inject(
            method = "getPlayerSkinLocation",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 1,
            remap = false
    )
    //? if <1.21.11 {
    private static void quickskin$quickSkinFaceLocation(GameProfile profile, CallbackInfoReturnable<ResourceLocation> cir) {
    //?} else {
    private static void quickskin$quickSkinFaceLocation(GameProfile profile, CallbackInfoReturnable<Identifier> cir) {
    //?}
        if (profile == null) {
            return;
        }
        //? if <1.21.9 {
        var location = JourneyMapPlayerFaces.faceLocation(profile.getId());
        //?} else {
        var location = JourneyMapPlayerFaces.faceLocation(profile.id());
        //?}
        if (location != null) {
            cir.setReturnValue(location);
        }
    }
}
