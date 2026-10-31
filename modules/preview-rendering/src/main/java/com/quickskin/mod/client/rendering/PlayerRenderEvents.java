package com.quickskin.mod.client.rendering;

import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Forge's and NeoForge's {@code RenderPlayerEvent} around the skin menu's equipment draw, as the
 * player renderer fires them around a player in the world, so mods that change what a player
 * shows only while it is drawn do so on the preview too. Relics hides the boots under its own
 * boots this way: it empties the feet slot in {@code Pre} and puts the boots back in {@code Post}.
 * Before 1.21.2 only, where the event carries the player rather than a render state.
 *
 * <p>The events are reached by reflection, so neither loader is a dependency. They carry a pose
 * stack and buffers of their own, which draw nowhere: a mod that draws in them draws for a
 * player in the world, not for a preview. As in the world, {@code Post} follows only a
 * {@code Pre} nobody cancelled. Should the events not match, or one fail, the preview goes on
 * without them for the rest of the session.
 */
@Environment(EnvType.CLIENT)
final class PlayerRenderEvents {
//? if <1.21.2 {
    private static final boolean ERA = true;
//?} else {
    private static final boolean ERA = false;
//?}

    /** The events' constructors, the bus and its post method; null until resolved, and {@link #MISSING} without them. */
    private record Api(@Nullable Constructor<?> pre, @Nullable Constructor<?> post, @Nullable Object bus,
                       @Nullable Method postMethod) {
    }

    private static final Api MISSING = new Api(null, null, null, null);
    @Nullable
    private static volatile Api api;

    private PlayerRenderEvents() {
    }

    /**
     * Runs {@code draw} between the events for {@code player} drawn through {@code renderer},
     * handing them {@code poseStack} and {@code buffers}, which should draw nowhere.
     */
    static void around(Object player, Object renderer, Object poseStack, Object buffers, int light, Runnable draw) {
        Api events = api();
        if (events == MISSING) {
            draw.run();
            return;
        }
        boolean posted = false;
        try {
            Object pre = events.pre().newInstance(player, renderer, 1.0F, poseStack, buffers, light);
            events.postMethod().invoke(events.bus(), pre);
            posted = !(Boolean) pre.getClass().getMethod("isCanceled").invoke(pre);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            disable(error);
        }
        try {
            draw.run();
        } finally {
            if (posted) {
                try {
                    events.postMethod().invoke(events.bus(),
                            events.post().newInstance(player, renderer, 1.0F, poseStack, buffers, light));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    disable(error);
                }
            }
        }
    }

    private static Api api() {
        Api resolved = api;
        if (resolved == null) {
            resolved = ERA ? resolve() : MISSING;
            api = resolved;
        }
        return resolved;
    }

    private static void disable(Throwable error) {
        if (api != MISSING) {
            api = MISSING;
            QuickSkinInfo.LOGGER.warn("The skin menu's preview leaves out other mods' player render events", error);
        }
    }

    private static Api resolve() {
        ClassLoader loader = PlayerRenderEvents.class.getClassLoader();
        // Forge, then NeoForge
        String[][] loaders = {
                {"net.minecraftforge.client.event.RenderPlayerEvent", "net.minecraftforge.common.MinecraftForge"},
                {"net.neoforged.neoforge.client.event.RenderPlayerEvent", "net.neoforged.neoforge.common.NeoForge"}};
        for (String[] names : loaders) {
            try {
                Class<?> pre = Class.forName(names[0] + "$Pre", false, loader);
                Class<?> post = Class.forName(names[0] + "$Post", false, loader);
                Object bus = Class.forName(names[1], true, loader).getField("EVENT_BUS").get(null);
                Method postMethod = null;
                for (Method method : bus.getClass().getMethods()) {
                    if (method.getName().equals("post") && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isAssignableFrom(pre)) {
                        postMethod = method;
                    }
                }
                Constructor<?> preConstructor = sixArguments(pre);
                Constructor<?> postConstructor = sixArguments(post);
                if (postMethod == null || preConstructor == null || postConstructor == null) {
                    return MISSING;
                }
                return new Api(preConstructor, postConstructor, bus, postMethod);
            } catch (ClassNotFoundException absent) {
                // Not this loader
            } catch (ReflectiveOperationException | RuntimeException | LinkageError changed) {
                QuickSkinInfo.LOGGER.warn("The skin menu's preview leaves out other mods' player render events", changed);
                return MISSING;
            }
        }
        return MISSING;
    }

    /** The constructor taking the player, renderer, partial tick, pose stack, buffers and light. */
    @Nullable
    private static Constructor<?> sixArguments(Class<?> event) {
        for (Constructor<?> constructor : event.getConstructors()) {
            if (constructor.getParameterCount() == 6) {
                return constructor;
            }
        }
        return null;
    }
}
