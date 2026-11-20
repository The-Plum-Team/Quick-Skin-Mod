package com.quickskin.mod.mixin.immersive;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;

//? if <1.21.6 {
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.gen.Accessor;
//?}

@Mixin(value = RenderSystem.class, remap = false)
public interface ImmersiveLightDirectionsAccessor {
    //? if <1.21.6 {
    @Accessor("shaderLightDirections")
    static Vector3f[] quickskin$lights() {
        throw new AssertionError();
    }
    //?}
}
