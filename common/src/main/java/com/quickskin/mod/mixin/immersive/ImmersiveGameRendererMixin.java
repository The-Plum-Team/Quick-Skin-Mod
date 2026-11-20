package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.9 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
    //? if <26.2 {
import com.quickskin.mod.client.immersive.ImmersiveSurface;
    //?}
//?}
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The immersive view's place in a frame, and its floating screen: the view is worked out before
 * anything is drawn or the mouse is read, from the time the game took at the start of the frame,
 * and the floating screen is drawn over the world once the screen itself has been drawn, before the
 * toasts. What the world is drawn with (the world pass, the field of view, the walk's view bobbing)
 * is {@link ImmersiveWorldViewMixin}'s, a class of its own so the two can be ported apart.
 *
 * <p>The frame starts at the head of {@code GameRenderer.render} to 1.21.11 and of
 * {@code GameRenderer.update} from 26.1, which also moves the mouse with the floating screen. On
 * 1.20.1 {@code render} is handed the frame's start time; from 1.21 it is not, and
 * {@link ImmersiveMinecraftMixin} tells it to {@code ImmersiveMode.frameStarted} instead
 * ({@code ImmersiveMode.beginFrame(float)} reads it). The floating screen goes in before the
 * toasts ({@code ToastComponent} to 1.21.1, {@code ToastManager} from 1.21.2), in
 * {@code GameRenderer.render} to 1.21.11 and {@code GameRenderer.extractGui} on 26.1 to 26.1.2,
 * where a new frame's GUI also starts at its head; from 26.2 {@link ImmersiveGuiMixin} does both.
 * From 1.21.6 that is only while the view closes and no screen is drawn into its picture: the
 * screen hook hands the frame's GUI the mesh otherwise ({@code ImmersiveScreenMixin}).
 */
@Mixin(GameRenderer.class)
public abstract class ImmersiveGameRendererMixin {
//? if <1.21 {
    @Inject(method = "render", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$beginImmersiveFrame(float partialTick, long nanoTime, boolean renderLevel,
                                               CallbackInfo callback) {
        // The game took this time at the start of the frame, before its ticks: the view goes by it
        ImmersiveMode.beginFrame(partialTick, nanoTime);
        // The floating screen moves under a still mouse while the camera travels
        ImmersiveMouseAccessor mouse = (ImmersiveMouseAccessor) Minecraft.getInstance().mouseHandler;
        double[] cursor = ImmersiveMode.cursor(mouse.quickskin$activeButton() != -1);
        if (cursor != null) {
            mouse.quickskin$setXpos(cursor[0]);
            mouse.quickskin$setYpos(cursor[1]);
        }
    }

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;render(Lnet/minecraft/client/gui/GuiGraphics;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawImmersiveScreen(float partialTick, long nanoTime, boolean renderLevel,
                                               CallbackInfo callback) {
        ImmersiveMode.drawOverlay();
    }
//?} else if <1.21.2 {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$beginImmersiveFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        // The frame's start time was taken before the ticks (ImmersiveMinecraftMixin); the partial
        // tick is the one the camera is placed with (renderLevel asks for it the same way)
        ImmersiveMode.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
        // The floating screen moves under a still mouse while the camera travels
        ImmersiveMouseAccessor mouse = (ImmersiveMouseAccessor) Minecraft.getInstance().mouseHandler;
        double[] cursor = ImmersiveMode.cursor(mouse.quickskin$activeButton() != -1);
        if (cursor != null) {
            mouse.quickskin$setXpos(cursor[0]);
            mouse.quickskin$setYpos(cursor[1]);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;render(Lnet/minecraft/client/gui/GuiGraphics;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawImmersiveScreen(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        ImmersiveMode.drawOverlay();
    }
//?} else if <1.21.6 {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$beginImmersiveFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        // The game draws into its own target again, whatever last frame's screen left
        ImmersiveSurface.frameStarts();
        // The frame's start time was taken before the ticks (ImmersiveMinecraftMixin); the partial
        // tick is the one the camera is placed with (renderLevel asks for it the same way)
        ImmersiveMode.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
        // The floating screen moves under a still mouse while the camera travels
        ImmersiveMouseAccessor mouse = (ImmersiveMouseAccessor) Minecraft.getInstance().mouseHandler;
        double[] cursor = ImmersiveMode.cursor(mouse.quickskin$activeButton() != -1);
        if (cursor != null) {
            mouse.quickskin$setXpos(cursor[0]);
            mouse.quickskin$setYpos(cursor[1]);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;render(Lnet/minecraft/client/gui/GuiGraphics;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawImmersiveScreen(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        ImmersiveMode.drawOverlay();
    }
//?} else if <1.21.9 {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$beginImmersiveFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        // The frame's screens extract into this state, which the floating screen's mesh joins
        ImmersiveSurface.frameStarts(((ImmersiveGameRendererAccessor) (Object) this).quickskin$guiRenderState());
        // The view goes by the time ImmersiveMinecraftMixin took at the start of the frame
        ImmersiveMode.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
        // The floating screen moves under a still mouse while the camera travels
        ImmersiveMouseAccessor mouse = (ImmersiveMouseAccessor) Minecraft.getInstance().mouseHandler;
        double[] cursor = ImmersiveMode.cursor(mouse.quickskin$activeButton() != -1);
        if (cursor != null) {
            mouse.quickskin$setXpos(cursor[0]);
            mouse.quickskin$setYpos(cursor[1]);
        }
    }

    // While the view closes no screen is drawn: the last picture floats from here, before the toasts
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;render(Lnet/minecraft/client/gui/GuiGraphics;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawImmersiveScreen(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        ImmersiveMode.drawOverlay();
    }
//?} else if <26.1 {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$beginImmersiveFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        // The floating screen's mesh goes into this frame's GUI, which the game extracts later on
        ImmersiveSurface.newFrame(((ImmersiveGameRendererAccessor) this).quickskin$guiRenderState());
        // The view goes by the time the frame started, which ImmersiveMinecraftMixin took before the ticks
        ImmersiveMode.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
        // The floating screen moves under a still mouse while the camera travels
        ImmersiveMouseAccessor mouse = (ImmersiveMouseAccessor) Minecraft.getInstance().mouseHandler;
        double[] cursor = ImmersiveMode.cursor(mouse.quickskin$activeButton() != null);
        if (cursor != null) {
            mouse.quickskin$setXpos(cursor[0]);
            mouse.quickskin$setYpos(cursor[1]);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;render(Lnet/minecraft/client/gui/GuiGraphics;)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawImmersiveScreen(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        // While the screen floats its hook has put the picture in already; on the way back, with
        // no screen left, the last picture floats from here
        ImmersiveMode.drawOverlay();
    }
//?} else {
    //? if <26.2 {
    // 26.x splits a frame into update (the camera), extract (the world's and the GUI's render
    // states) and render; the view is worked out as update starts, before the camera is placed
    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$beginImmersiveFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo callback) {
        quickskin$beginFrame(deltaTracker);
    }
    //?} else {
    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$beginImmersiveFrame(DeltaTracker deltaTracker, CallbackInfo callback) {
        quickskin$beginFrame(deltaTracker);
    }
    //?}

    @Unique
    private static void quickskin$beginFrame(DeltaTracker deltaTracker) {
        ImmersiveMode.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
        // The floating screen moves under a still mouse while the camera travels
        ImmersiveMouseAccessor mouse = (ImmersiveMouseAccessor) Minecraft.getInstance().mouseHandler;
        double[] cursor = ImmersiveMode.cursor(mouse.quickskin$activeButton() != null);
        if (cursor != null) {
            mouse.quickskin$setXpos(cursor[0]);
            mouse.quickskin$setYpos(cursor[1]);
        }
    }
    //? if <26.2 {

    // To 26.1.2 the game renderer extracts the GUI itself; from 26.2 Gui does ({@link ImmersiveGuiMixin})
    @Inject(method = "extractGui", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$newGuiFrame(DeltaTracker deltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded,
                                       CallbackInfo callback) {
        ImmersiveSurface.newFrame();
    }

    @Inject(method = "extractGui",
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
