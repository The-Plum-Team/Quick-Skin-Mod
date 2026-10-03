package com.quickskin.mod.client.gui.integration;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.AbstractWidget;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * FancyMenu's per-widget API, reached by reflection so Quick Skin never depends on FancyMenu.
 *
 * <p>FancyMenu's mixin adds these public methods to every {@link AbstractWidget}. They are
 * FancyMenu's own names and are never remapped, so a plain lookup finds them on every loader. When
 * the lookup fails, FancyMenu is absent (or cannot customize widgets), and every call is a no-op.
 */
@Environment(EnvType.CLIENT)
final class FancyMenuWidgets {
    /** The layout identifier of the title and pause player preview. */
    static final String PREVIEW_ID = "quickskin_player_preview";
    static final String CHANGE_SKIN_ID = "quickskin_change_skin_button";
    static final String ROTATE_ID = "quickskin_preview_rotate_button";
    static final String ANIMATION_TOGGLE_ID = "quickskin_preview_animation_toggle";
    static final String ANIMATION_ID_PREFIX = "quickskin_preview_animation_";

    private static final String SET_IDENTIFIER = "setWidgetIdentifierFancyMenu";
    private static final String IS_HIDDEN = "isHiddenFancyMenu";
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickSkin-FancyMenu");

    private FancyMenuWidgets() {
    }

    /** True when FancyMenu's widget API is present on {@link AbstractWidget}. */
    static boolean isPresent() {
        return Installed.API.isPresent();
    }

    /** Give {@code widget} a stable FancyMenu layout identifier; does nothing without FancyMenu. */
    static void setIdentifier(@Nullable AbstractWidget widget, String identifier) {
        if (widget != null) {
            Installed.API.setIdentifier(widget, identifier);
        }
    }

    /** True when a FancyMenu layout hides {@code widget}; false without FancyMenu. */
    static boolean isHidden(AbstractWidget widget) {
        return Installed.API.isHidden(widget);
    }

    /** Resolved lazily, so tests can build an {@link Api} for stub types without loading Minecraft. */
    private static final class Installed {
        static final Api API = new Api(AbstractWidget.class);
    }

    /** The two methods, resolved once for one widget type. A method that fails once is dropped. */
    static final class Api {
        private volatile @Nullable Method setIdentifier;
        private volatile @Nullable Method isHidden;

        Api(Class<?> widgetType) {
            this.setIdentifier = find(widgetType, SET_IDENTIFIER, String.class);
            this.isHidden = find(widgetType, IS_HIDDEN);
        }

        boolean isPresent() {
            return setIdentifier != null;
        }

        void setIdentifier(Object widget, String identifier) {
            Method method = setIdentifier;
            if (method != null && invoke(method, widget, identifier) == FAILED) {
                setIdentifier = null;
            }
        }

        boolean isHidden(Object widget) {
            Method method = isHidden;
            if (method == null) {
                return false;
            }
            Object result = invoke(method, widget);
            if (result == FAILED) {
                isHidden = null;
                return false;
            }
            return Boolean.TRUE.equals(result);
        }

        private static final Object FAILED = new Object();

        private static @Nullable Method find(Class<?> type, String name, Class<?>... parameters) {
            try {
                return type.getMethod(name, parameters);
            } catch (NoSuchMethodException | SecurityException | LinkageError e) {
                return null;
            }
        }

        private static @Nullable Object invoke(Method method, Object target, Object... arguments) {
            try {
                return method.invoke(target, arguments);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("FancyMenu's {} failed; Quick Skin stops calling it", method.getName(), e);
                return FAILED;
            }
        }
    }
}
