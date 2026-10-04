package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.client.compat.RealCameraCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets Real Camera bind its first-person camera to a Quick Skin skin.
 *
 * <p>Real Camera 0.7.4 and later pick a bind target by testing whether a captured buffer's texture
 * id contains the target's id, and its default targets only know vanilla skin ids. With no match
 * it reports a binding failure and stops rendering the body. Every such test reads the id through
 * this record accessor. The record moved from {@code util} to {@code renderer} in 0.7.6; only one
 * of the two classes exists in any build.</p>
 */
@Pseudo
@Mixin(targets = {
        "com.xtracr.realcamera.renderer.BuiltIterableBuffer",
        "com.xtracr.realcamera.util.BuiltIterableBuffer"
}, remap = false)
public abstract class RealCameraTextureIdMixin {

    @Inject(
            method = "textureId",
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 1,
            remap = false
    )
    private void quickskin$aliasQuickSkinSkin(CallbackInfoReturnable<String> cir) {
        String textureId = cir.getReturnValue();
        String alias = RealCameraCompat.aliasSkinTextureId(textureId);
        if (alias != null && !alias.equals(textureId)) {
            cir.setReturnValue(alias);
        }
    }
}
