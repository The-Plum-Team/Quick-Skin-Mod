package com.quickskin.mod.client.immersive;

import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.BooleanSupplier;

/** Reads Iris's live pack and shadow-pass flags without linking the optional mod. */
public final class ImmersiveShaderPacks {
    private static final BooleanSupplier OFF = () -> false;
    private static final Hooks HOOKS = findIris();

    public static boolean active() {
        return HOOKS.pack.getAsBoolean();
    }

    public static boolean shadows() {
        return HOOKS.shadow.getAsBoolean();
    }

    private static Hooks findIris() {
        for (String name : new String[] {"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"}) {
            try {
                Class<?> api = Class.forName(name);
                Object iris = api.getMethod("getInstance").invoke(null);
                return new Hooks(flag(api, iris, "isShaderPackInUse"), flag(api, iris, "isRenderingShadowPass"));
            } catch (ReflectiveOperationException | LinkageError absent) {
                // Older Iris and Oculus distributions expose the other API package.
            }
        }
        return new Hooks(OFF, OFF);
    }

    private static BooleanSupplier flag(Class<?> api, Object iris, String name) throws ReflectiveOperationException {
        var method = MethodHandles.publicLookup().findVirtual(api, name, MethodType.methodType(boolean.class)).bindTo(iris);
        return MethodHandleProxies.asInterfaceInstance(BooleanSupplier.class, method);
    }

    private record Hooks(BooleanSupplier pack, BooleanSupplier shadow) {}

    private ImmersiveShaderPacks() {}
}
