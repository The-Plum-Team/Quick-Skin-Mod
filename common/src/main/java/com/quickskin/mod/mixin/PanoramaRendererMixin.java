package com.quickskin.mod.mixin;

import com.quickskin.mod.client.gui.util.PanoramaTimeSync;
//? if <26.1 {
import net.minecraft.client.renderer.PanoramaRenderer;
//?} else {
import net.minecraft.client.renderer.Panorama;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to sync ALL panorama renderers to use the same global time.
 * This ensures seamless panorama transition between any screens.
 */
//? if <26.1 {
@Mixin(PanoramaRenderer.class)
//?} else {
@Mixin(Panorama.class)
//?}
public class PanoramaRendererMixin {

//? if <26.1 {
    @Inject(method = "render", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
//?} else {
    @Inject(method = "extractRenderState", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
//?}
    private void quickskin$syncPanoramaTime(CallbackInfo ci) {
        // Sync this panorama's time to the global time before rendering
//? if <26.1 {
        PanoramaTimeSync.syncPanoramaRenderer((PanoramaRenderer)(Object)this);
//?} else {
        PanoramaTimeSync.syncPanoramaRenderer((Panorama)(Object)this);
//?}
    }
}
