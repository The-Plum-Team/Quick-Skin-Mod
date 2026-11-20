package com.quickskin.mod.mixin.immersive;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.9 {
import com.quickskin.mod.client.immersive.light.PlayerFaceLight;
import com.quickskin.mod.client.immersive.light.PlayerLightPose;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}

@Mixin(PoseStack.Pose.class)
public abstract class ImmersiveLightPoseMixin
        //? if >=1.21.9 {
        implements PlayerLightPose
        //?}
{
    //? if >=1.21.9 {
    @Unique private PlayerFaceLight quickskin$faceLight;

    @Override
    public PlayerFaceLight quickskin$faceLight() {
        return quickskin$faceLight;
    }

    @Override
    public void quickskin$faceLight(PlayerFaceLight light) {
        quickskin$faceLight = light;
    }

    // pushPose, Pose.copy and the feature renderers' working stacks all use set. Copying
    // null matters just as much: the same stack entry is later reused for other entities.
    @Inject(method = "set(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)V", at = @At("RETURN"),
            require = 0, expect = 1)
    private void quickskin$copyLight(PoseStack.Pose source, CallbackInfo ci) {
        quickskin$faceLight = ((PlayerLightPose) (Object) source).quickskin$faceLight();
    }

    @Inject(method = "setIdentity", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$resetLight(CallbackInfo ci) {
        quickskin$faceLight = null;
    }

    @Inject(method = "transformNormal(FFFLorg/joml/Vector3f;)Lorg/joml/Vector3f;", at = @At("RETURN"),
            require = 0, expect = 1)
    private void quickskin$shadePlayerFace(float x, float y, float z, Vector3f result,
                                           CallbackInfoReturnable<Vector3f> ci) {
        if (quickskin$faceLight != null) quickskin$faceLight.apply(result.x, result.y, result.z, result);
    }
    //?}
}
