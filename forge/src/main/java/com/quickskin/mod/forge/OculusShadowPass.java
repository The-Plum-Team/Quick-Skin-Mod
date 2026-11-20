package com.quickskin.mod.forge;

import com.quickskin.mod.platform.QuickSkinInfo;
import net.minecraftforge.fml.ModList;

import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.BooleanSupplier;

/** Distinguishes shadow-map stages from the player's camera stages when Oculus is installed. */
final class OculusShadowPass {
    private static final BooleanSupplier ACTIVE = findShadowPass();

    static boolean active() {
        return ACTIVE.getAsBoolean();
    }

    private static BooleanSupplier findShadowPass() {
        if (ModList.get().isLoaded("oculus")) {
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                var check = MethodHandles.publicLookup().findVirtual(api, "isRenderingShadowPass",
                        MethodType.methodType(boolean.class)).bindTo(instance);
                return MethodHandleProxies.asInterfaceInstance(BooleanSupplier.class, check);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                QuickSkinInfo.LOGGER.warn("Cannot identify Oculus shadow passes with the installed Iris API", unavailable);
            }
        }
        return () -> false;
    }

    private OculusShadowPass() {}
}
