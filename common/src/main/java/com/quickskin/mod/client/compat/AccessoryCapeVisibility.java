package com.quickskin.mod.client.compat;

import com.quickskin.mod.platform.PlatformHelper;
import com.quickskin.mod.platform.QuickSkinInfo;
//? if <1.21.2 {
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
//?} else if <1.21.9 {
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
//?} else {
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?}

import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The accessory renderer's cape decision, evaluated for the same player or frame as the worn cape. */
public final class AccessoryCapeVisibility {
    @FunctionalInterface
    public interface Rule {
//? if <1.21.2 {
        boolean allows(AbstractClientPlayer wearer);
//?} else if <1.21.9 {
        boolean allows(PlayerRenderState wearer);
//?} else {
        boolean allows(AvatarRenderState wearer);
//?}
    }

    private static Rule rule = wearer -> true;

    public static Rule rule() {
        return rule;
    }

    public static void use(Rule rendererRule) {
        rule = rendererRule;
    }

    /** Caelus publishes an optional render event on Forge and NeoForge. */
    public static void useCaelus() {
        if (!PlatformHelper.isModLoaded("caelus")) return;
//? if <1.21 {
        String owner = "top.theillusivec4.caelus.mixin.util.ClientMixinHooks";
        Class<?> subject = Player.class;
//?} else if <1.21.2 {
        String owner = "com.illusivesoulworks.caelus.mixin.util.ClientMixinHooks";
        Class<?> subject = Player.class;
//?} else {
        String owner = "com.illusivesoulworks.caelus.mixin.ClientMixinHooks";
    //? if <1.21.9 {
        Class<?> subject = PlayerRenderState.class;
    //?} else {
        Class<?> subject = AvatarRenderState.class;
    //?}
//?}
        try {
            Class<?> hooks = Class.forName(owner, false, AccessoryCapeVisibility.class.getClassLoader());
            var method = MethodHandles.publicLookup().findStatic(hooks, "canRenderCape",
                    MethodType.methodType(boolean.class, subject));
            use(MethodHandleProxies.asInterfaceInstance(Rule.class, method));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            QuickSkinInfo.LOGGER.warn("Cannot check cape visibility with the installed Caelus version", unavailable);
        }
    }

    private AccessoryCapeVisibility() {}
}
