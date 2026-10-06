package com.quickskin.mod.mixin;

import com.quickskin.mod.client.gui.util.PanoramaTimeSync;
//? if <26.1 {
import net.minecraft.client.renderer.PanoramaRenderer;
//?} else {
import net.minecraft.client.renderer.Panorama;
//?}
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Turns every panorama renderer with Quick Skin's shared clock, so the background stays continuous
 * between the title screen and Quick Skin's own screens.
 *
 * <p>Each read of the renderer's {@code spin} in its render method returns the shared angle.
 * Vanilla still advances and stores its own spin, but that value no longer reaches the screen.
 * Redirecting the read names the field through the mappings, so neither the order of the class's
 * fields nor a field another mod adds to it can be mistaken for the angle. Every matrix target
 * reads {@code spin} exactly twice there: once to advance it and once for the cube map (render
 * state from 26.1).
 */
//? if <26.1 {
@Mixin(PanoramaRenderer.class)
//?} else {
@Mixin(Panorama.class)
//?}
public class PanoramaRendererMixin {

//? if <26.1 {
    @Redirect(
        method = "render",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/PanoramaRenderer;spin:F",
            opcode = Opcodes.GETFIELD
        ),
        require = 0, expect = 2, allow = 2
    )
    private float quickskin$sharedPanoramaSpin(PanoramaRenderer renderer) {
//?} else {
    @Redirect(
        method = "extractRenderState",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/Panorama;spin:F",
            opcode = Opcodes.GETFIELD
        ),
        require = 0, expect = 2, allow = 2
    )
    private float quickskin$sharedPanoramaSpin(Panorama renderer) {
//?}
        return PanoramaTimeSync.panoramaSpin();
    }
}
