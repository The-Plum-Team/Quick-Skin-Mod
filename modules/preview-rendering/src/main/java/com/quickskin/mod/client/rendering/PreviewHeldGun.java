package com.quickskin.mod.client.rendering;

import com.quickskin.mod.platform.PlatformHelper;

import java.util.function.BooleanSupplier;

/**
 * Recognises a gun of an optional gun mod in the previewed player's hand, without compiling against
 * that mod.
 *
 * <p>Timeless and Classics Zero poses the player's arms around a held gun from a hook on the player
 * model and draws the gun through vanilla's item-in-hand layer. Both start from one question - is
 * the main-hand item a gun - which TaCZ answers with {@code instanceof IGun}. This class asks the
 * same question the same way, so the HUD preview keeps the main hand visible exactly when TaCZ
 * poses the arms for it, and the gun and the stance cannot disagree.
 *
 * <p>TaCZ is not a supported integration and is never on the compile classpath. Its marker type is
 * looked up by name, once, without initialising it, and only when the loader reports the mod. An
 * absent mod, a renamed or removed type and a linkage failure all answer "not a gun", which leaves
 * the preview exactly as it is without the mod. Nothing here calls into TaCZ.
 *
 * <p>Free of Minecraft types, so the lookup stays unit testable in the loader-independent test
 * source set.
 */
final class PreviewHeldGun {

    /** TaCZ's loader id and the interface every one of its gun items implements. */
    static final PreviewHeldGun TACZ = new PreviewHeldGun(
            () -> PlatformHelper.isModLoaded("tacz"),
            "com.tacz.guns.api.item.IGun",
            PreviewHeldGun::findClass);

    /** Resolves a class by name without initialising it. */
    @FunctionalInterface
    interface TypeLookup {
        Class<?> find(String name) throws ClassNotFoundException;
    }

    private final BooleanSupplier modLoaded;
    private final String gunTypeName;
    private final TypeLookup lookup;

    private volatile boolean resolved;
    private volatile Class<?> gunType;

    PreviewHeldGun(BooleanSupplier modLoaded, String gunTypeName, TypeLookup lookup) {
        this.modLoaded = modLoaded;
        this.gunTypeName = gunTypeName;
        this.lookup = lookup;
    }

    /** Whether {@code item} is a gun of the mod. Never throws; {@code false} whenever in doubt. */
    boolean matches(Object item) {
        if (item == null) {
            return false;
        }
        Class<?> type = gunType();
        return type != null && type.isInstance(item);
    }

    /** The mod's gun type, resolved on first use and then kept, or {@code null} for "no such mod". */
    private Class<?> gunType() {
        if (!resolved) {
            Class<?> found = null;
            try {
                if (modLoaded.getAsBoolean()) {
                    found = lookup.find(gunTypeName);
                }
            } catch (ClassNotFoundException | LinkageError | RuntimeException absent) {
                // Not installed, or not the TaCZ this was written against: the preview hides the hand.
                found = null;
            }
            gunType = found;
            resolved = true;
        }
        return gunType;
    }

    private static Class<?> findClass(String name) throws ClassNotFoundException {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            try {
                return Class.forName(name, false, context);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Fall through to the loader that defined this mod's own classes.
            }
        }
        return Class.forName(name, false, PreviewHeldGun.class.getClassLoader());
    }
}
