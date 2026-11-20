package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.light.PlayerFaceLight;
import com.quickskin.mod.client.immersive.light.PlayerLightPose;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/** Sodium emits its cuboid vertices directly and caches six transformed normals per matrix. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.immediate.model.EntityRenderer", remap = false)
public abstract class SodiumPlayerLightMixin {
    //? if >=1.21.9 {
    @Unique private static PlayerFaceLight quickskin$light;
    @Unique private static final Vector3f quickskin$normal = new Vector3f();

    @Inject(method = "prepareNormalsIfChanged", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private static void quickskin$selectPlayerLight(PoseStack.Pose pose, CallbackInfo ci) {
        quickskin$light = ((PlayerLightPose) (Object) pose).quickskin$faceLight();
    }

    // Sodium assigns this normal once per emitted face, then writes it to its four vertices.
    // Its original matrix cache remains available to other models, including ones that share the pose.
    @ModifyVariable(method = "emitQuads", at = @At("STORE"), name = "packedNormal",
            require = 0, expect = 1, allow = 1)
    private static int quickskin$shadeCuboidFace(int packed) {
        if (quickskin$light == null) return packed;
        quickskin$light.apply((byte) packed / 127f, (byte) (packed >>> 8) / 127f,
                (byte) (packed >>> 16) / 127f, quickskin$normal);
        return quickskin$pack(quickskin$normal.x) | (quickskin$pack(quickskin$normal.y) << 8)
                | (quickskin$pack(quickskin$normal.z) << 16);
    }

    @Unique
    private static int quickskin$pack(float value) {
        return (int) (Math.clamp(value, -1f, 1f) * 127f) & 255;
    }
    //?}
}
