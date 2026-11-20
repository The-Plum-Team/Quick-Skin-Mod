package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.9 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * In the immersive view the mouse points at a screen that floats in the world, so the place it
 * has in the window is turned into the place of the screen it points at before the game hears of
 * the move. Hover, clicks, drags and the wheel all read that place. Without this hook the screen
 * would answer where it would be drawn flat.
 *
 * <p>{@code onMove(JDD)V} up to 26.2 (the window is {@code getWindow().getWindow()} before 1.21.9
 * and {@code handle()} after); 26.3's SDL input has {@code onMove(JDDDD)V}, whose relative move is
 * passed on unchanged. Every version hooks it.
 */
@Mixin(MouseHandler.class)
public abstract class ImmersiveMouseMixin {
//? if <1.21 {
    @Unique
    private boolean quickskin$pointing;

    /** The button the game counts as held, or -1: unlike isLeftPressed it is kept with a screen open. */
    @Shadow
    private int activeButton;

    @Shadow
    private void onMove(long window, double x, double y) {
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$pointAtFloatingScreen(long window, double x, double y, CallbackInfo callback) {
        if (quickskin$pointing || window != Minecraft.getInstance().getWindow().getWindow()) {
            return;
        }
        double[] position = {x, y};
        ImmersiveMode.cursorMoved(position, this.activeButton != -1);
        if (position[0] != x || position[1] != y) {
            quickskin$pointing = true;
            try {
                this.onMove(window, position[0], position[1]);
            } finally {
                quickskin$pointing = false;
            }
            callback.cancel();
        }
    }
//?} else if <1.21.2 {
    @Unique
    private boolean quickskin$pointing;

    /** The button the game counts as held, or -1: unlike isLeftPressed it is kept with a screen open. */
    @Shadow
    private int activeButton;

    @Shadow
    private void onMove(long window, double x, double y) {
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$pointAtFloatingScreen(long window, double x, double y, CallbackInfo callback) {
        if (quickskin$pointing || window != Minecraft.getInstance().getWindow().getWindow()) {
            return;
        }
        double[] position = {x, y};
        ImmersiveMode.cursorMoved(position, this.activeButton != -1);
        if (position[0] != x || position[1] != y) {
            quickskin$pointing = true;
            try {
                this.onMove(window, position[0], position[1]);
            } finally {
                quickskin$pointing = false;
            }
            callback.cancel();
        }
    }
//?} else if <1.21.6 {
    @Unique
    private boolean quickskin$pointing;

    /** The button the game counts as held, or -1: unlike isLeftPressed it is kept with a screen open. */
    @Shadow
    private int activeButton;

    @Shadow
    private void onMove(long window, double x, double y) {
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$pointAtFloatingScreen(long window, double x, double y, CallbackInfo callback) {
        if (quickskin$pointing || window != Minecraft.getInstance().getWindow().getWindow()) {
            return;
        }
        double[] position = {x, y};
        ImmersiveMode.cursorMoved(position, this.activeButton != -1);
        if (position[0] != x || position[1] != y) {
            quickskin$pointing = true;
            try {
                this.onMove(window, position[0], position[1]);
            } finally {
                quickskin$pointing = false;
            }
            callback.cancel();
        }
    }
//?} else if <1.21.9 {
    @Unique
    private boolean quickskin$pointing;

    /** The button the game counts as held, or -1: unlike isLeftPressed it is kept with a screen open. */
    @Shadow
    private int activeButton;

    @Shadow
    private void onMove(long window, double x, double y) {
    }

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$pointAtFloatingScreen(long window, double x, double y, CallbackInfo callback) {
        if (quickskin$pointing || window != Minecraft.getInstance().getWindow().getWindow()) {
            return;
        }
        double[] position = {x, y};
        ImmersiveMode.cursorMoved(position, this.activeButton != -1);
        if (position[0] != x || position[1] != y) {
            quickskin$pointing = true;
            try {
                this.onMove(window, position[0], position[1]);
            } finally {
                quickskin$pointing = false;
            }
            callback.cancel();
        }
    }
//?} else if <26.1 {
    @Unique
    private boolean quickskin$pointing;

    /** The button the game counts as held, or null: unlike isLeftPressed it is kept with a screen open. */
    @Shadow
    private MouseButtonInfo activeButton;

    @Shadow
    private void onMove(long window, double x, double y) {
    }

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$pointAtFloatingScreen(long window, double x, double y, CallbackInfo callback) {
        if (quickskin$pointing || window != Minecraft.getInstance().getWindow().handle()) {
            return;
        }
        double[] position = {x, y};
        ImmersiveMode.cursorMoved(position, this.activeButton != null);
        if (position[0] != x || position[1] != y) {
            quickskin$pointing = true;
            try {
                this.onMove(window, position[0], position[1]);
            } finally {
                quickskin$pointing = false;
            }
            callback.cancel();
        }
    }
//?} else {
    @Unique
    private boolean quickskin$pointing;

    /** The button the game counts as held, or null: unlike isLeftPressed it is kept with a screen open. */
    @Shadow
    private MouseButtonInfo activeButton;

    //? if <26.3 {
    @Shadow
    private void onMove(long window, double x, double y) {
    }

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$pointAtFloatingScreen(long window, double x, double y, CallbackInfo callback) {
        if (quickskin$pointing || window != Minecraft.getInstance().getWindow().handle()) {
            return;
        }
        double[] position = {x, y};
        ImmersiveMode.cursorMoved(position, this.activeButton != null);
        if (position[0] != x || position[1] != y) {
            quickskin$pointing = true;
            try {
                this.onMove(window, position[0], position[1]);
            } finally {
                quickskin$pointing = false;
            }
            callback.cancel();
        }
    }
    //?} else {
    @Shadow
    public abstract void onMove(long window, double x, double y, double xrel, double yrel);

    // 26.3's SDL input hands the place in the window and the move since the last one; only the
    // place is pointed elsewhere (a screen reads the place, a grabbed mouse the move)
    @Inject(method = "onMove(JDDDD)V", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$pointAtFloatingScreen(long window, double x, double y, double xrel, double yrel,
                                                 CallbackInfo callback) {
        if (quickskin$pointing || window != Minecraft.getInstance().getWindow().handle()) {
            return;
        }
        double[] position = {x, y};
        ImmersiveMode.cursorMoved(position, this.activeButton != null);
        if (position[0] != x || position[1] != y) {
            quickskin$pointing = true;
            try {
                this.onMove(window, position[0], position[1], xrel, yrel);
            } finally {
                quickskin$pointing = false;
            }
            callback.cancel();
        }
    }
    //?}
//?}
}
