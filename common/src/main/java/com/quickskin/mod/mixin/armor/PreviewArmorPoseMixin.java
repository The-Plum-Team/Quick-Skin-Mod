package com.quickskin.mod.mixin.armor;

import net.minecraft.client.model.HumanoidModel;
//? if >=1.21.9 {
import com.quickskin.mod.client.rendering.PreviewEquipment;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * From 1.21.9 an armour model poses itself from the render state when the submitted features are
 * drawn, after the skin menu's preview submitted it; at the end of that pose this gives it the
 * preview's own pose, which {@link com.quickskin.mod.client.rendering.PreviewEquipment} bound to
 * the preview's render state (ADR 0017). Every other draw is posed as vanilla poses it. Before
 * 1.21.9 armour copies its pose from the model it is drawn over, and this does nothing.
 */
@Mixin(HumanoidModel.class)
public abstract class PreviewArmorPoseMixin {
//? if >=1.21.9 {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("TAIL"), require = 0, expect = 1, allow = 1)
    private void quickskin$takePreviewPose(HumanoidRenderState state, CallbackInfo ci) {
        PreviewEquipment.applyBoundPose((HumanoidModel<?>) (Object) this, state);
    }
//?}
}
