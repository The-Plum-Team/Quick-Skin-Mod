package com.quickskin.mod.e2e;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/** Deterministic activation probe for the exact optional mod installed by the runtime controller. */
public final class CompatibilityProbe {
    private static final Map<String, String> INTEGRATIONS = Map.of(
            "cpm", "com.quickskin.mod.client.compat.CPMCompatIntegration",
            "ears", "com.quickskin.mod.client.compat.EarsCompatIntegration",
            "skin-layers-3d", "com.quickskin.mod.client.rendering.SkinLayers3DIntegration",
            "customnpcs", "com.quickskin.mod.client.compat.CustomNPCsIntegration",
            "essential", "com.quickskin.mod.client.compat.EssentialCompatIntegration",
            "replaymod", "com.quickskin.mod.client.compat.ReplayModHelper"
    );

    public record Result(boolean active, String detail) {}

    private CompatibilityProbe() {}

    public static Result verifyConfiguredIntegration() {
        String modId = System.getProperty("quickskin.e2e.compatibility", "").trim();
        String className = INTEGRATIONS.get(modId);
        if (className == null) {
            return new Result(false, "unknown or missing compatibility id: " + modId);
        }
        try {
            Class<?> integration = Class.forName(
                    className, true, CompatibilityProbe.class.getClassLoader());
            Method isAvailable = integration.getMethod("isAvailable");
            Object available = isAvailable.invoke(null);
            if (!Boolean.TRUE.equals(available)) {
                return new Result(false, modId + " integration reported unavailable");
            }
            return new Result(true, modId + " integration active via " + className);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new Result(false, modId + " integration probe threw " + cause);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return new Result(false, modId + " integration probe failed: " + e);
        }
    }
}
