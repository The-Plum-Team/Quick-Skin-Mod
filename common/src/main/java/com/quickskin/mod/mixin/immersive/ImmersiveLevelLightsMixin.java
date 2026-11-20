package com.quickskin.mod.mixin.immersive;

import com.mojang.blaze3d.platform.Lighting;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.6 {
import com.quickskin.mod.client.immersive.light.PlayerFaceLight;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.2 {
import org.joml.Vector3fc;
//?} else {
import org.joml.Vector3f;
//?}
//?}

@Mixin(Lighting.class)
public abstract class ImmersiveLevelLightsMixin {
    //? if >=1.21.6 {
    @Inject(method = "updateBuffer", at = @At("RETURN"), require = 0, expect = 1)
    //? if >=26.2 {
    private void quickskin$copyLevelLights(Lighting.Entry entry, Vector3fc first, Vector3fc second, CallbackInfo ci) {
    //?} else {
    private void quickskin$copyLevelLights(Lighting.Entry entry, Vector3f first, Vector3f second, CallbackInfo ci) {
    //?}
        if (entry == Lighting.Entry.LEVEL) PlayerFaceLight.levelLights(first, second);
    }
    //?}
}
