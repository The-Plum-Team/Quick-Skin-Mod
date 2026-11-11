package com.quickskin.mod.mixin.menu;

import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.MenuGuiScale;
//? if <1.21.2 {
import net.minecraft.client.gui.components.toasts.ToastComponent;
//?} else {
import net.minecraft.client.gui.components.toasts.ToastManager;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the game's toasts off Quick Skin's menus. The game draws them (the tutorial, advancements,
 * unlocked recipes, other mods' notices) after the screen, in the top right corner, where they
 * cover a menu's header and buttons. While a menu is shown, or a dialog over one, the toasts are
 * neither advanced nor drawn, so none is lost: one that arrives meanwhile waits and comes in,
 * with its sound, once the menu goes away.
 *
 * <p>Before 1.21.2 one method advances and draws them. From 1.21.2 the game advances them in a
 * method of its own, and skipping only the draw would let them run out their time unseen. Quick
 * Skin's own messages in its menus are drawn by its screens and are not toasts of the game.
 * Without this hook the toasts show over the menus as before.
 */
//? if <1.21.2 {
@Mixin(ToastComponent.class)
//?} else {
@Mixin(ToastManager.class)
//?}
public abstract class MenuToastsMixin {
    //? if >=1.21.2 {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, allow = 1)
    private void quickskin$holdToastsBehindMenu(CallbackInfo callback) {
        if (MenuGuiScale.isScaled(GuiCompat.currentScreen())) {
            callback.cancel();
        }
    }
    //?}

    @Inject(
        //? if <26.1 {
        method = "render",
        //?} else {
        method = "extractRenderState",
        //?}
        at = @At("HEAD"),
        cancellable = true,
        require = 0, expect = 1, allow = 1
    )
    private void quickskin$keepToastsOffMenu(CallbackInfo callback) {
        if (MenuGuiScale.isScaled(GuiCompat.currentScreen())) {
            callback.cancel();
        }
    }
}
