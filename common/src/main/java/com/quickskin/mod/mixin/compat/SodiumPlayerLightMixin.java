package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.light.PlayerLightPose;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/** Sodium emits its cuboid vertices directly and caches six transformed normals per matrix. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.immediate.model.EntityRenderer", remap = false)
public abstract class SodiumPlayerLightMixin {
    //? if >=1.21.9 {
    @Shadow @Final private static int[] CUBE_FACE_NORMAL;
    @Unique private static final int[] quickskin$playerNormals = new int[6];
    @Unique private static final Vector3f quickskin$normal = new Vector3f();
    @Unique private static boolean quickskin$litCuboid;

    // Both the unchanged-matrix return and the recompute return precede emitQuads. Keep
    // Sodium's cached array pristine so an untagged cuboid can immediately reuse the matrix.
    @Inject(method = "prepareNormalsIfChanged", at = @At("RETURN"), require = 0, expect = 2)
    private static void quickskin$preparePlayerNormals(PoseStack.Pose pose, CallbackInfo ci) {
        var light = ((PlayerLightPose) (Object) pose).quickskin$faceLight();
        quickskin$litCuboid = light != null;
        if (light == null) return;
        for (int face = 0; face < quickskin$playerNormals.length; face++) {
            int packed = CUBE_FACE_NORMAL[face];
            light.apply((byte) packed / 127f, (byte) (packed >>> 8) / 127f,
                    (byte) (packed >>> 16) / 127f, quickskin$normal);
            quickskin$playerNormals[face] = quickskin$pack(quickskin$normal.x)
                    | (quickskin$pack(quickskin$normal.y) << 8) | (quickskin$pack(quickskin$normal.z) << 16);
        }
    }

    @Unique
    private static int quickskin$pack(float value) {
        return (int) (Math.clamp(value, -1f, 1f) * 127f) & 255;
    }

    @Redirect(method = "emitQuads", at = @At(value = "FIELD", target =
            "Lnet/caffeinemc/mods/sodium/client/render/immediate/model/EntityRenderer;CUBE_FACE_NORMAL:[I"),
            require = 0, expect = 1, allow = 1)
    private static int[] quickskin$normalsForCuboid() {
        return quickskin$litCuboid ? quickskin$playerNormals : CUBE_FACE_NORMAL;
    }
    //?}
}
