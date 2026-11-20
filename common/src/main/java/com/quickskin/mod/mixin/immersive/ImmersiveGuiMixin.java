package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
//?} else if <1.21.9 {
//?} else if <26.1 {
//?} else {
    //? if >=26.2 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
    //?}
//?}
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The floating screen in the frame's GUI from 26.2, where {@code Gui.extractRenderState} extracts
 * the whole GUI (the HUD, the screen and the toasts) that the game renderer extracted itself before:
 * a new frame's GUI starts at its head, and the floating screen goes in before the toasts, which is
 * where it floats while the camera comes back and no screen is drawn into its picture. Up to 26.1.2
 * {@link ImmersiveGameRendererMixin} does the same in {@code GameRenderer.extractGui}.
 *
 * <p>Only 26.2 and later hook it; the class exists, empty, on every version.
 */
@Mixin(Gui.class)
public abstract class ImmersiveGuiMixin {
//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
//?} else if <1.21.9 {
//?} else if <26.1 {
//?} else {
    //? if >=26.2 {
    @Inject(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V", at = @At("HEAD"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$newGuiFrame(DeltaTracker deltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded,
                                       CallbackInfo callback) {
        ImmersiveSurface.newFrame();
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawImmersiveScreen(DeltaTracker deltaTracker, boolean shouldRenderLevel,
                                               boolean resourcesLoaded, CallbackInfo callback) {
        // While the camera comes back no screen is drawn into the picture: the last one floats here
        ImmersiveMode.drawOverlay();
    }
    //?}
//?}
}
