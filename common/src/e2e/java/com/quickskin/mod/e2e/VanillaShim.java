package com.quickskin.mod.e2e;

import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.channels.SelectionKey;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Isolates the handful of vanilla calls whose signature drifts across Minecraft versions, so the rest
 * of the harness stays a single version-agnostic source set. Everything here is reflection-based and
 * returns only version-stable types ({@link String}, {@link Screen}), importing no type that was
 * renamed/moved across versions. {@link SplashRenderer}, {@link DefaultPlayerSkin} and
 * {@link TitleScreen} are the deliberate class-literal exceptions: their packages are identical on
 * every supported version,
 * and the harness jar's remapper must rewrite them for Fabric's intermediary runtime. Resolving a
 * Minecraft name as a string only works on Mojang-mapped loaders, so keep string lookups for
 * classes that genuinely move.
 *
 * <p>Drift absorbed (1.20.1 / 1.21.x / 26.x):</p>
 * <ul>
 *   <li><b>setScreen</b>: {@code Minecraft.setScreen(Screen)} (1.20.1..1.21.x) vs {@code Gui.setScreen}
 *       (26.x).</li>
 *   <li><b>current screen</b>: {@code Minecraft.screen} field (1.20.1..1.21.x) vs {@code mc.gui.screen()}
 *       (26.x).</li>
 *   <li><b>skin/cape/elytra texture location</b> (the render-truthful values the player renderer
 *       samples): {@code getSkinTextureLocation()}/{@code getCloakTextureLocation()}/
 *       {@code getElytraTextureLocation()} (1.20.1) → {@code getSkin().texture()/capeTexture()/
 *       elytraTexture()} returning a ResourceLocation (1.21.x) →
 *       {@code getSkin().body()/cape()/elytra()} returning a {@code ClientAsset.Texture} whose
 *       {@code texturePath()} is the Identifier (26.x). Returned as a String so no renamed type
 *       ({@code ResourceLocation}→{@code Identifier}) is imported.</li>
 *   <li><b>player model geometry</b>: {@code getModelName()} (1.20.1) vs the model component of
 *       {@code getSkin()} (1.21.x/26.x). The normalized result describes the value consumed by the
 *       renderer, rather than Quick Skin's requested appearance state.</li>
 *   <li><b>UUID-selected default skin</b>: {@code DefaultPlayerSkin.getDefaultSkin(UUID)} returning
 *       a ResourceLocation (1.20.1) vs {@code DefaultPlayerSkin.get(UUID)} returning the same
 *       evolving player-skin record described above.</li>
 *   <li><b>player name</b>: {@code GameProfile.getName()} (authlib class) vs {@code name()} (authlib
 *       record, 26.x).</li>
 *   <li><b>client walk distance</b>: public fields on {@code Entity} (1.20.1/1.21.1), then on
 *       {@code AbstractClientPlayer} (1.21.2..1.21.8), then private fields on
 *       {@code ClientAvatarState} reached through {@code avatarState()} (1.21.9/26.x).</li>
 *   <li><b>main render target</b> (for screenshots): {@code Minecraft.getMainRenderTarget()} vs
 *       {@code mc.gameRenderer.mainRenderTarget()} (26.x).</li>
 *   <li><b>terrain render readiness</b>: the player section must be compiled (and visible where
 *       supported) and the chunk/section render dispatcher must have drained before a semantic
 *       checkpoint may capture it.</li>
 *   <li><b>Screenshot.grab</b>: classic Fabric exposes intermediary class/method names at runtime,
 *       and an {@code int downscale} param was inserted in 1.21.6; both mappings and shapes are
 *       handled.</li>
 *   <li><b>widget press</b>: {@code AbstractWidget.onPress()} (no-arg) vs
 *       {@code onPress(InputWithModifiers)} (26.x).</li>
 *   <li><b>title splash construction</b>: {@code SplashRenderer(String)} vs
 *       {@code SplashRenderer(Component)}, plus its remapped private field on {@code TitleScreen}.</li>
 *   <li><b>transient overlays</b>: toast/chat access through {@code Minecraft}/{@code Gui}
 *       (1.20.1..26.1.x) vs {@code Gui.toastManager()}/{@code Gui.hud.getChat()} (26.2).</li>
 *   <li><b>disconnect to title</b>: {@code Level.disconnect()} vs
 *       {@code Level.disconnect(Component)}, followed by the loader-observable
 *       {@code Minecraft.disconnect} boundary (or its legacy no-arg form).</li>
 *   <li><b>mouse input</b>: {@code Screen.mouseClicked/mouseDragged/mouseReleased} take
 *       scalar coordinates before 1.21.9 and a {@code MouseButtonEvent} afterwards, so clicks fall
 *       back to {@code MouseHandler}'s GLFW callbacks, whose shape GLFW fixes; modern drags also
 *       construct the runtime event record and invoke the public drag override, with the handler's
 *       accumulated-movement route retained as a compatibility fallback.</li>
 * </ul>
 *
 * <p>GL-touching calls (screenshot) must run on the render thread, after at least one full frame.</p>
 */
public final class VanillaShim {

    private static volatile boolean terrainReadinessWarningEmitted;

    private VanillaShim() {}

    /**
     * Open (or, with {@code null}, close) a screen, version-agnostically. Finds the single-arg
     * {@code setScreen} on {@link Minecraft} (or, as a 26.x fallback, on {@code mc.gui}) and invokes it.
     * @param screen a {@code net.minecraft.client.gui.screens.Screen} instance, or {@code null} to close.
     */
    public static boolean setScreen(Minecraft mc, Object screen) {
        try {
            for (Method m : Minecraft.class.getMethods()) {
                if ((m.getName().equals("setScreen") || m.getName().equals("method_1507")
                        || m.getName().equals("m_91152_"))
                        && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(mc, screen);
                    return true;
                }
            }
            // 26.x: setScreen moved onto Gui (mc.gui.setScreen(...)).
            Object gui = mc.gui;
            if (gui != null) {
                for (Method m : gui.getClass().getMethods()) {
                    if (m.getName().equals("setScreen") && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        m.invoke(gui, screen);
                        return true;
                    }
                }
            }
            E2ELog.warn("setScreen method not found on Minecraft/Gui");
            return false;
        } catch (Throwable t) {
            E2ELog.warn("setScreen failed: " + t);
            return false;
        }
    }

    /** The currently open screen, or {@code null}. {@code Minecraft.screen} (1.20.1..1.21.x) vs {@code mc.gui.screen()} (26.x). */
    public static Screen currentScreen(Minecraft mc) {
        try {
            try {
                Field f;
                try {
                    f = Minecraft.class.getField("screen"); // named runtime
                } catch (NoSuchFieldException namedMissing) {
                    try {
                        f = Minecraft.class.getField("field_1755"); // Fabric intermediary runtime
                    } catch (NoSuchFieldException intermediaryMissing) {
                        f = Minecraft.class.getField("f_91080_"); // Forge SRG runtime
                    }
                }
                return (Screen) f.get(mc);
            } catch (NoSuchFieldException ignore) { /* 26.x: relocated to Gui */ }
            Object gui = mc.gui;
            if (gui != null) {
                Method m = findNoArg(gui.getClass(), "screen");
                if (m != null) return (Screen) m.invoke(gui);
            }
        } catch (Throwable t) {
            E2ELog.warn("currentScreen: " + t);
        }
        return null;
    }

    /**
     * Whether the terrain around {@code blockPos} has reached renderer truth, rather than merely
     * existing in the logical client level.
     *
     * <p>Forge can expose the flat-world block state before llvmpipe has compiled and uploaded its
     * render section. Capturing in that interval produces a sky-and-hotbar frame even though the
     * player and block are both present. The section predicate rejects the initial placeholder;
     * the dispatcher predicate then waits until outstanding terrain work has drained. Both methods
     * keep stable Fabric intermediary ids while their Mojang names changed in newer releases.</p>
     *
     * @param blockPos a vanilla {@code BlockPos}; kept as {@link Object} so renamed vanilla types
     *                 never escape this compatibility adapter
     */
    public static boolean isTerrainRenderReady(Minecraft mc, Object blockPos) {
        if (mc == null || mc.levelRenderer == null || blockPos == null) return false;
        try {
            Object renderer = mc.levelRenderer;
            Method sectionReady = findOneArg(
                    renderer.getClass(), blockPos,
                    "isSectionCompiled", "isSectionCompiledAndVisible",
                    "method_40050", "m_202430_"
            );
            Method dispatcherReady = findNoArg(
                    renderer.getClass(),
                    "hasRenderedAllChunks", "hasRenderedAllSections",
                    "method_3281", "m_109825_"
            );
            if (sectionReady == null || dispatcherReady == null) {
                warnTerrainReadinessOnce("renderer readiness methods not found on "
                        + renderer.getClass().getName());
                return false;
            }
            return Boolean.TRUE.equals(sectionReady.invoke(renderer, blockPos))
                    && Boolean.TRUE.equals(dispatcherReady.invoke(renderer));
        } catch (Throwable t) {
            warnTerrainReadinessOnce("renderer readiness check failed: " + t);
            return false;
        }
    }

    /** This player's profile name. {@code GameProfile.getName()} (class) vs {@code name()} (record, 26.x). */
    public static String playerName(AbstractClientPlayer p) {
        try {
            Method gp = findNoArg(
                    p.getClass(), "getGameProfile", "method_7334", "m_36316_"
            );
            Object profile = (gp != null) ? gp.invoke(p) : null;
            if (profile != null) {
                Method m = findNoArg(profile.getClass(), "getName");
                if (m == null) m = findNoArg(profile.getClass(), "name");
                if (m != null) return String.valueOf(m.invoke(profile));
            }
        } catch (Throwable t) {
            E2ELog.warn("playerName: " + t);
        }
        return "?";
    }

    /**
     * The resolved skin texture location the renderer samples for this player, as a String
     * ("namespace:path"), or {@code null}. Render-truthful across versions.
     */
    public static String skinTexture(AbstractClientPlayer p) {
        return resolveLoc(p, "getSkinTextureLocation", new String[]{"texture", "body"});
    }

    /**
     * The normalized model geometry selected by the renderer ({@code slim} or {@code classic}).
     *
     * <p>Quick Skin's appearance repository records the requested model, but that alone cannot
     * prove that a loader's player-skin adapter exposed the same value to Minecraft. This reads the
     * final vanilla-facing accessor used to choose the slim or wide player renderer.</p>
     */
    public static String playerModel(AbstractClientPlayer p) {
        if (p == null) return null;
        try {
            // 1.20.1: PlayerRenderer selects between its models through this string getter.
            Method direct = findNoArg(
                    p.getClass(), "getModelName", "method_3121", "m_108564_"
            );
            if (direct != null) {
                return normalizePlayerModel(direct.invoke(p));
            }

            // 1.21.x/26.x: the renderer consumes the model component of PlayerSkin.
            Method getSkin = findNoArg(p.getClass(), "getSkin", "method_52814", "method_52810");
            Object skin = getSkin == null ? null : getSkin.invoke(p);
            Method model = skin == null
                    ? null
                    : findNoArg(skin.getClass(), "model", "comp_1629");
            if (model != null) {
                return normalizePlayerModel(model.invoke(skin));
            }
            // Production loaders may expose the record's official obfuscated accessor. Select the
            // unique enum component structurally instead of guessing a one-letter method name that
            // could collide with an unrelated accessor.
            if (skin != null && skin.getClass().isRecord()) {
                String resolved = null;
                for (RecordComponent component : skin.getClass().getRecordComponents()) {
                    if (!component.getType().isEnum()) continue;
                    String candidate = normalizePlayerModel(component.getAccessor().invoke(skin));
                    if (candidate == null) continue;
                    if (resolved != null) return null;
                    resolved = candidate;
                }
                return resolved;
            }
            return null;
        } catch (Throwable t) {
            E2ELog.warn("playerModel: " + t);
            return null;
        }
    }

    private static String normalizePlayerModel(Object value) {
        if (value == null) return null;
        Enum<?> enumValue = value instanceof Enum<?> candidate ? candidate : null;
        String model = enumValue == null ? String.valueOf(value) : enumValue.name();
        model = model.toLowerCase(Locale.ROOT);
        if (model.equals("slim") || model.endsWith(".slim")) return "slim";
        if (model.equals("classic") || model.equals("default") || model.equals("wide")
                || model.endsWith(".wide")) {
            return "classic";
        }
        // Fabric's intermediary runtime renames the enum constants themselves. Their declared
        // order is the stable vanilla contract in every supported PlayerSkin era: SLIM, then WIDE.
        if (enumValue != null && enumValue.ordinal() == 0) return "slim";
        if (enumValue != null && enumValue.ordinal() == 1) return "classic";
        return null;
    }

    /** The current camera FOV option, or {@code null} when it cannot be read. */
    public static Integer fieldOfView(Minecraft mc) {
        try {
            return mc == null || mc.options == null ? null : mc.options.fov().get();
        } catch (Throwable t) {
            E2ELog.warn("fieldOfView: " + t);
            return null;
        }
    }

    /** Set the camera FOV without persisting it; the scenario restores the previous value. */
    public static boolean setFieldOfView(Minecraft mc, int value) {
        try {
            if (mc == null || mc.options == null) return false;
            mc.options.fov().set(value);
            return value == mc.options.fov().get();
        } catch (Throwable t) {
            E2ELog.warn("setFieldOfView: " + t);
            return false;
        }
    }

    /**
     * Zero both previous and current client walk distances across their versioned owners.
     *
     * <p>The values feed cape interpolation independently of {@code walkAnimation}. Returning a
     * diagnostic instead of silently continuing keeps visual evidence fail-closed when mappings
     * drift again.</p>
     *
     * @return {@code null} on success, otherwise a bounded diagnostic.
     */
    public static String resetWalkDistance(Object player) {
        if (player == null) return "walk-distance reset requires a player";
        try {
            if (zeroWalkDistanceFields(player)) return null;

            Method avatarState = findNoArg(
                    player.getClass(), "avatarState", "method_74192"
            );
            Object state = avatarState == null ? null : avatarState.invoke(player);
            if (state != null && zeroWalkDistanceFields(state)) return null;
            return "walk-distance fields were not found on the player or avatar state";
        } catch (Throwable failure) {
            return "could not reset walk distance: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    private static boolean zeroWalkDistanceFields(Object target)
            throws ReflectiveOperationException {
        Field current = findField(
                target.getClass(),
                "walkDist", "field_5973", "field_53039", "field_62569", "f_19787_"
        );
        Field previous = findField(
                target.getClass(),
                "walkDistO", "field_6039", "field_53038", "field_62570", "f_19867_"
        );
        if (current == null && previous == null) return false;
        if (current == null || previous == null
                || current.getType() != float.class || previous.getType() != float.class) {
            throw new NoSuchFieldException("walk-distance field pair is incomplete");
        }
        current.setFloat(target, 0.0F);
        previous.setFloat(target, 0.0F);
        if (current.getFloat(target) != 0.0F || previous.getFloat(target) != 0.0F) {
            throw new IllegalStateException("walk-distance fields rejected zero values");
        }
        return true;
    }

    private static Field findField(Class<?> type, String... names) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // Try the next mapping name or superclass.
                }
            }
        }
        return null;
    }

    /**
     * The vanilla default skin selected for this exact profile UUID, as a texture location string.
     *
     * <p>The method name and return shape drift across supported versions. Matching the static
     * UUID signature avoids relying on an obfuscated name, while the class literal ensures Fabric's
     * harness remapper rewrites the owner. Newer return values are nested records whose accessor
     * names are also remapped, so their runtime record components are traversed instead of guessing
     * those names. String-returning model helpers in 1.20.1 are ignored because they are not default
     * skin texture locations.</p>
     */
    public static String expectedDefaultSkinTexture(AbstractClientPlayer p) {
        if (p == null) return null;
        UUID playerId = p.getUUID();
        for (Method method : DefaultPlayerSkin.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != UUID.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                String location = unwrapDefaultSkinTexture(method.invoke(null, playerId));
                if (location != null) return location;
            } catch (Throwable ignored) {
                // Another UUID helper may have a shape that is not a texture; try the next one.
            }
        }
        return null;
    }

    /** True only when the renderer has converged on the UUID-selected vanilla default texture. */
    public static boolean isExpectedDefaultSkinResolved(AbstractClientPlayer p) {
        String expected = expectedDefaultSkinTexture(p);
        return expected != null && expected.equals(skinTexture(p));
    }

    /** As {@link #skinTexture} for the cape ({@code null} when no cape). */
    public static String cloakTexture(AbstractClientPlayer p) {
        return resolveLoc(p, "getCloakTextureLocation", new String[]{"capeTexture", "cape"});
    }

    /** As {@link #skinTexture} for a profile-provided elytra ({@code null} for vanilla fallback). */
    public static String elytraTexture(AbstractClientPlayer p) {
        return resolveLoc(p, "getElytraTextureLocation", new String[]{"elytraTexture", "elytra"});
    }

    /** Null-safe ("null" string) variants for diagnostic logging. */
    public static String skinTextureStr(AbstractClientPlayer p) { return String.valueOf(skinTexture(p)); }
    public static String cloakTextureStr(AbstractClientPlayer p) { return String.valueOf(cloakTexture(p)); }

    /**
     * Whether a loader compatibility, warning, or error screen is blocking startup. Packaged-JAR
     * E2E treats this as a hard failure instead of accepting it on the user's behalf.
     */
    public static boolean isWarningOrErrorScreen(Screen sc) {
        if (sc == null) return false;
        String cn = sc.getClass().getName().toLowerCase(Locale.ROOT);
        return cn.contains("loadingerror") || cn.contains("errorscreen") || cn.contains("warning");
    }

    /** Whether vanilla has already rejected or lost the connection before the world was joined. */
    public static boolean isDisconnectedScreen(Screen sc) {
        // Keep the class literal so Loom remaps it to Fabric's stable intermediary class at runtime.
        // A string comparison against the Mojang name only works on Forge/NeoForge.
        return sc instanceof DisconnectedScreen;
    }

    /** Whether vanilla is still showing its live multiplayer connection screen. */
    public static boolean isConnectScreen(Screen sc) {
        return sc instanceof ConnectScreen;
    }

    /**
     * Bounded state from the live Netty channel behind a connection screen. This diagnostic is
     * intentionally structural: the private connection/channel field names are remapped on
     * Fabric, while their runtime types and channel contract are stable.
     */
    public static String connectionDiagnostic(Screen sc) {
        if (!isConnectScreen(sc)) return "not-connect-screen";
        try {
            Channel channel = liveConnectionChannel(sc);
            if (channel == null) return "channel=<unavailable>";
            return "channelType=" + channel.getClass().getName()
                    + "; channelActive=" + channel.isActive()
                    + "; open=" + channel.isOpen()
                    + "; registered=" + channel.isRegistered()
                    + "; writable=" + channel.isWritable()
                    + "; autoRead=" + channel.config().isAutoRead()
                    + "; nio=" + nioReadDiagnostic(channel)
                    + "; pipeline=" + channel.pipeline().names();
        } catch (Throwable t) {
            return "channel=<diagnostic-failed:" + t.getClass().getSimpleName() + ">";
        }
    }

    /**
     * Repairs the one invalid NIO state observed in ReplayMod's 1.20.1 startup path: auto-read is
     * enabled on an active channel, but its selection key has no OP_READ interest and its event
     * loop is asleep in select(). Restore that interest and wake the selector without reconnecting,
     * replacing handlers, or manufacturing any protocol traffic.
     */
    public static boolean repairMissingConnectionRead(Screen sc) {
        if (!isConnectScreen(sc)) return false;
        try {
            Channel channel = liveConnectionChannel(sc);
            if (channel == null || !channel.isActive() || !channel.config().isAutoRead()) {
                return false;
            }
            SelectionKey key = nioSelectionKey(channel);
            if (key == null || !key.isValid()
                    || (key.interestOps() & SelectionKey.OP_READ) != 0) {
                return false;
            }
            E2ELog.info("connection read repair before -> " + nioReadDiagnostic(channel));
            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
            key.selector().wakeup();
            E2ELog.info("connection read repair after -> " + nioReadDiagnostic(channel));
            return true;
        } catch (Throwable t) {
            E2ELog.warn("connection read repair inspection failed: " + t);
            return false;
        }
    }

    private static Channel liveConnectionChannel(Screen sc) throws IllegalAccessException {
        for (Class<?> screenType = sc.getClass();
                screenType != null && Screen.class.isAssignableFrom(screenType);
                screenType = screenType.getSuperclass()) {
            for (Field field : screenType.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Channel channel = nestedChannel(field.get(sc));
                if (channel != null) return channel;
            }
        }
        return null;
    }

    private static String nioReadDiagnostic(Channel channel) {
        try {
            SelectionKey key = nioSelectionKey(channel);
            String readPending = "unavailable";
            for (Class<?> type = channel.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType() == boolean.class
                            && field.getName().equals("readPending")) {
                        field.setAccessible(true);
                        readPending = Boolean.toString(field.getBoolean(channel));
                    }
                }
            }
            if (key == null) return "selectionKey=unavailable,readPending=" + readPending;
            return "selectionKeyValid=" + key.isValid()
                    + ",interestOps=" + key.interestOps()
                    + ",readyOps=" + key.readyOps()
                    + ",readPending=" + readPending;
        } catch (Throwable t) {
            return "unavailable:" + t.getClass().getSimpleName();
        }
    }

    private static SelectionKey nioSelectionKey(Channel channel) throws IllegalAccessException {
        for (Class<?> type = channel.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !SelectionKey.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(channel);
                if (value instanceof SelectionKey key) return key;
            }
        }
        return null;
    }

    private static Channel nestedChannel(Object owner) throws IllegalAccessException {
        if (owner instanceof Channel direct) return direct;
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !Channel.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value instanceof Channel channel) return channel;
            }
        }
        return null;
    }

    /**
     * True only for vanilla's two explicit pre-world login timeout reasons. The E2E orchestrator
     * may retry this infrastructure-shaped failure once; every other disconnect remains fatal.
     */
    public static boolean isConnectionTimeoutScreen(Screen sc) {
        if (!isDisconnectedScreen(sc)) return false;
        ScreenDetails details = inspectScreen(sc);
        if (details.translationKeys.contains("disconnect.timeout")
                || details.translationKeys.contains("multiplayer.disconnect.slow_login")) {
            return true;
        }
        // The remote endpoint may send a literal component instead of retaining its translation
        // key. Packaged E2E always runs with Minecraft's en_us default, so retain a narrow fallback.
        for (String text : details.texts) {
            String normalized = text.toLowerCase(Locale.ROOT);
            if (normalized.equals("timed out") || normalized.equals("took too long to log in")) {
                return true;
            }
        }
        return false;
    }

    /** A bounded, credential-free description of the current screen for startup diagnostics. */
    public static String screenDiagnostic(Screen sc) {
        if (sc == null) return "screen=<none/in-world-hud>";
        ScreenDetails details = inspectScreen(sc);
        return "screen=" + sc.getClass().getName()
                + "; translationKeys=" + details.translationKeys
                + "; text=" + details.texts;
    }

    private record ScreenDetails(Set<String> translationKeys, Set<String> texts) {}

    private static ScreenDetails inspectScreen(Screen sc) {
        Set<String> keys = new LinkedHashSet<>();
        Set<String> texts = new LinkedHashSet<>();
        if (sc == null) return new ScreenDetails(keys, texts);

        for (Class<?> type = sc.getClass();
                type != null && Screen.class.isAssignableFrom(type);
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    collectComponents(field.get(sc), 0, keys, texts);
                } catch (Throwable ignored) {
                    // One inaccessible implementation field must not hide other readable reasons.
                }
            }
        }
        return new ScreenDetails(keys, texts);
    }

    private static void collectComponents(
            Object candidate,
            int depth,
            Set<String> keys,
            Set<String> texts
    ) {
        if (candidate == null || depth > 3 || keys.size() + texts.size() >= 24) return;
        if (candidate instanceof Component component) {
            String text = component.getString().replaceAll("\\s+", " ").trim();
            if (!text.isEmpty()) texts.add(text.length() <= 300 ? text : text.substring(0, 300));
            if (component.getContents() instanceof TranslatableContents translated) {
                keys.add(translated.getKey());
            }
            for (Component sibling : component.getSiblings()) {
                collectComponents(sibling, depth + 1, keys, texts);
            }
            return;
        }
        if (candidate instanceof Optional<?> optional) {
            optional.ifPresent(value -> collectComponents(value, depth + 1, keys, texts));
            return;
        }
        Class<?> type = candidate.getClass();
        if (!type.isRecord()) return;
        for (RecordComponent component : type.getRecordComponents()) {
            try {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                collectComponents(accessor.invoke(candidate), depth + 1, keys, texts);
            } catch (Throwable ignored) {
                // Continue with the remaining bounded record components.
            }
        }
    }

    private static String resolveLoc(AbstractClientPlayer p, String directName, String[] skinAccessors) {
        if (p == null) return null;
        try {
            // 1.21.x/26.x: getSkin() -> PlayerSkin, then the component the renderer consumes.
            // Some modern runtimes retain a legacy-shaped direct getter that can return null even
            // while PlayerSkin carries the actual custom texture, so this path must be authoritative.
            Method getSkin = findNoArg(p.getClass(), "getSkin", "method_52814", "method_52810");
            if (getSkin != null) {
                Object skin = getSkin.invoke(p);
                if (skin != null) {
                    for (String acc : skinAccessors) {
                        Method m = switch (acc) {
                            case "texture" -> findNoArg(skin.getClass(), acc, "comp_1626");
                            case "capeTexture" -> findNoArg(skin.getClass(), acc, "comp_1627");
                            case "elytraTexture" -> findNoArg(skin.getClass(), acc, "comp_1628");
                            default -> findNoArg(skin.getClass(), acc);
                        };
                        if (m == null) continue;
                        Object tex = m.invoke(skin);
                        if (tex == null) return null;
                        // 26.x: the accessor returns a ClientAsset.Texture; unwrap texturePath() -> Identifier.
                        Method tp = findNoArg(tex.getClass(), "texturePath", "comp_3627");
                        Object loc = (tp != null) ? tp.invoke(tex) : tex; // 1.21.x: already a ResourceLocation
                        return (loc == null) ? null : loc.toString();
                    }
                }
            }

            // 1.20.1 fallback: a direct getter returning a ResourceLocation.
            String intermediaryName = switch (directName) {
                case "getSkinTextureLocation" -> "method_3117";
                case "getElytraTextureLocation" -> "method_3122";
                default -> "method_3119";
            };
            String officialName = switch (directName) {
                case "getSkinTextureLocation" -> "m_108560_";
                case "getElytraTextureLocation" -> "m_108563_";
                default -> "m_108561_";
            };
            Method direct = findNoArg(
                    p.getClass(),
                    directName,
                    intermediaryName,
                    officialName
            );
            if (direct != null) {
                Object r = direct.invoke(p);
                return (r == null) ? null : r.toString();
            }
        } catch (Throwable t) {
            E2ELog.warn("resolveLoc(" + directName + "): " + t);
        }
        return null;
    }

    private static String unwrapDefaultSkinTexture(Object candidate) throws ReflectiveOperationException {
        return unwrapDefaultSkinTexture(candidate, 0);
    }

    private static String unwrapDefaultSkinTexture(
            Object candidate, int depth) throws ReflectiveOperationException {
        if (candidate == null || depth > 3) return null;

        String direct = candidate.toString();
        if (isDefaultSkinTextureLocation(direct)) return direct;

        Class<?> type = candidate.getClass();
        if (!type.isRecord()) return null;
        for (RecordComponent component : type.getRecordComponents()) {
            String nested = unwrapDefaultSkinTexture(
                    component.getAccessor().invoke(candidate), depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }

    private static boolean isDefaultSkinTextureLocation(String value) {
        return value != null
                && value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                && value.contains(":textures/entity/player/")
                && value.endsWith(".png");
    }

    /**
     * Invoke a widget's press action version-agnostically: {@code onPress()} (1.20.1..1.21.x) or
     * {@code onPress(InputWithModifiers)} (26.x, passed {@code null} — the button/checkbox handlers
     * here don't read the input).
     */
    public static boolean press(Object widget) {
        if (widget == null) return false;
        try {
            Method m0 = findNoArg(
                    widget.getClass(), "onPress", "method_25306", "m_5691_"
            );
            if (m0 != null) { m0.invoke(widget); return true; }
            for (Method m : widget.getClass().getMethods()) {
                if ((m.getName().equals("onPress") || m.getName().equals("method_25306"))
                        && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(widget, new Object[]{ null });
                    return true;
                }
            }
        } catch (Throwable t) {
            E2ELog.warn("press: " + t);
        }
        return false;
    }

    /**
     * Install one deterministic title-screen splash without exposing constructor or remapping drift
     * to a scenario. The return value is {@code null} on success and a fail-closed diagnostic on
     * failure.
     */
    public static String installDeterministicSplash(Screen screen, String text) {
        if (screen == null || text == null || text.isEmpty()) {
            return "title splash requires a screen and non-empty text";
        }
        try {
            // A class literal is remapped with the harness jar; the same name resolved as a string
            // only works on Mojang-mapped loaders and fails on Fabric's intermediary runtime.
            Class<?> rendererType = SplashRenderer.class;
            Object renderer;
            try {
                renderer = rendererType.getConstructor(String.class).newInstance(text);
            } catch (NoSuchMethodException stringEraEnded) {
                Component yellow = Component.literal(text)
                        .withStyle(style -> style.withColor(0xFFFF00));
                renderer = rendererType.getConstructor(Component.class).newInstance(yellow);
            }

            Field splashField = null;
            for (Field candidate : screen.getClass().getDeclaredFields()) {
                if (candidate.getType() != rendererType) continue;
                if (splashField != null) {
                    return "title screen exposes multiple SplashRenderer fields";
                }
                splashField = candidate;
            }
            if (splashField == null) return "title screen exposes no SplashRenderer field";
            splashField.setAccessible(true);
            splashField.set(screen, renderer);
            if (splashField.get(screen) != renderer) {
                return "title screen rejected the deterministic SplashRenderer";
            }
            return null;
        } catch (Throwable failure) {
            return "could not install deterministic title splash: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    /**
     * Leave the current server the way vanilla's pause-menu Disconnect does and land on a fresh
     * {@link TitleScreen}.
     *
     * <p>Vanilla first disconnects the level, then enters {@code Minecraft.disconnect}, which
     * clears the client level and installs the next screen. Both operations changed shape across
     * the supported range: the level disconnect gained a reason component, while the Minecraft
     * boundary gained a screen and resource-pack flag. Architectury's player-quit event fires from
     * that Minecraft boundary, which is where Quick Skin ends its session; calling
     * {@code clearClientLevel(Screen)} directly would clear vanilla state while silently bypassing
     * the product callback.</p>
     *
     * @return {@code null} on success, otherwise a bounded fail-closed diagnostic.
     */
    public static String disconnectToTitle(Minecraft mc) {
        if (mc == null) return "disconnect requires the Minecraft instance";
        try {
            Object level = mc.level;
            if (level == null) return "no level is loaded; nothing to disconnect from";
            Method disconnect = findNoArg(level.getClass(), "disconnect", "method_8525", "m_7462_");
            Component reason = Component.literal("Quick Skin E2E disconnect");
            if (disconnect != null) {
                disconnect.invoke(level);
            } else {
                disconnect = findPublicVoidOneArg(
                        level.getClass(), Component.class,
                        "disconnect", "method_8525", "m_7462_");
                if (disconnect == null) {
                    return "ClientLevel.disconnect() is unavailable on this runtime";
                }
                disconnect.invoke(level, reason);
            }

            TitleScreen title = new TitleScreen();
            Method disconnectClient = findPublicMethod(
                    mc.getClass(), new Class<?>[]{Screen.class, boolean.class},
                    "disconnect", "method_18096", "method_76795");
            if (disconnectClient != null) {
                disconnectClient.invoke(mc, title, false);
            } else {
                Method disconnectClientWithEngineReset = findPublicMethod(
                        mc.getClass(),
                        new Class<?>[]{Screen.class, boolean.class, boolean.class},
                        "disconnect", "method_18096");
                if (disconnectClientWithEngineReset != null) {
                    disconnectClientWithEngineReset.invoke(mc, title, false, true);
                } else {
                    Method legacyDisconnect = findNoArg(
                            mc.getClass(), "clearLevel", "disconnect",
                            "method_18099", "m_91399_");
                    if (legacyDisconnect == null) {
                        return "Minecraft has no supported disconnect method";
                    }
                    legacyDisconnect.invoke(mc);
                    if (!setScreen(mc, title)) return "could not open the title screen";
                }
            }
            if (mc.level != null || mc.player != null) {
                return "level/player survived the disconnect: level=" + mc.level
                        + " player=" + mc.player;
            }

            Screen current = currentScreen(mc);
            if (!(current instanceof TitleScreen)) {
                return "title screen did not become current: "
                        + (current == null ? "<none>" : current.getClass().getName());
            }
            return null;
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : failure;
            return "could not disconnect to the title screen: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    /**
     * Remove vanilla's transient chat and toast overlays before a contract screenshot.
     *
     * <p>Secure-chat and social-interaction notices are connection timing artifacts, not Quick
     * Skin evidence. Their APIs keep stable shapes but have renamed accessors across supported
     * versions and loaders, so this boundary resolves named, intermediary, and SRG forms and fails
     * closed when either surface cannot be cleared.</p>
     *
     * @return {@code null} on success, otherwise a bounded diagnostic suitable for an E2E report.
     */
    public static String clearTransientOverlays(Minecraft mc) {
        try {
            Method toastAccessor = findNoArg(
                    mc.getClass(), "getToasts", "getToastManager", "method_1566", "m_91300_"
            );
            Object toastManager = toastAccessor == null ? null : toastAccessor.invoke(mc);
            Object gui = mc.gui;
            if (toastManager == null && gui != null) {
                // 26.2 is an official-namespace lane and moved this accessor under Gui.
                Method guiToastAccessor = findNoArg(gui.getClass(), "toastManager");
                toastManager = guiToastAccessor == null ? null : guiToastAccessor.invoke(gui);
            }
            Method clearToasts = toastManager == null
                    ? null
                    : findNoArg(
                            toastManager.getClass(), "clear", "method_2000", "m_94919_"
                    );
            if (clearToasts == null) {
                return "vanilla toast clear surface is unavailable";
            }
            clearToasts.invoke(toastManager);

            Method chatAccessor = gui == null
                    ? null
                    : findNoArg(gui.getClass(), "getChat", "method_1743", "m_93076_");
            Object chat = chatAccessor == null ? null : chatAccessor.invoke(gui);
            if (chat == null && gui != null) {
                // 26.2 moved ChatComponent under Gui.hud. Resolve the unique field exposing
                // getChat() structurally, so no version-specific HUD type enters shared source.
                chat = invokeUniqueNoArgOnFieldValue(gui, "getChat");
            }
            Method clearChat = null;
            if (chat != null) {
                for (Method method : chat.getClass().getMethods()) {
                    if ((method.getName().equals("clearMessages")
                            || method.getName().equals("method_1808")
                            || method.getName().equals("m_93795_"))
                            && method.getParameterCount() == 1
                            && method.getParameterTypes()[0] == boolean.class) {
                        method.setAccessible(true);
                        clearChat = method;
                        break;
                    }
                }
            }
            if (clearChat == null) {
                return "vanilla chat clear surface is unavailable";
            }
            clearChat.invoke(chat, true);
            return null;
        } catch (Throwable failure) {
            return "could not clear vanilla transient overlays: "
                    + failure.getClass().getSimpleName();
        }
    }

    /**
     * Capture the current main framebuffer to {@code <runDir>/screenshots/<name>}.
     * @return true if the grab call was dispatched.
     */
    public static boolean screenshot(Minecraft mc, String name) {
        try {
            Class<?> screenshot = loadNamedClass("net.minecraft.client.Screenshot");
            File gameDir = new File(System.getProperty("user.dir"));
            Object fb = mainRenderTarget(mc);
            if (fb == null) { E2ELog.warn("main render target unavailable"); return false; }
            // Essential observes vanilla's grab(...) entry point and opens its recent-screenshot
            // tray on the title screen. Capture that compatibility lane from the same framebuffer
            // through vanilla's lower-level image copy so the test itself does not alter the UI it
            // is trying to prove.
            if ("essential".equals(System.getProperty("quickskin.e2e.compatibility"))) {
                return rawScreenshot(screenshot, gameDir, name, fb);
            }
            Consumer<Object> noop = msg -> {};

            for (Method m : screenshot.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                // 1.20.1-1.21.5: (File dir, String name, RenderTarget fb, Consumer<Component> msg)
                if (p.length == 4 && p[0] == File.class && p[1] == String.class
                        && p[2].isInstance(fb) && p[3] == Consumer.class) {
                    m.setAccessible(true);
                    m.invoke(null, gameDir, name, fb, noop);
                    return true;
                }
                // 1.21.6+/26.x: (File dir, String name, RenderTarget fb, int downscale, Consumer<Component> msg)
                if (p.length == 5 && p[0] == File.class && p[1] == String.class
                        && p[2].isInstance(fb) && p[3] == int.class && p[4] == Consumer.class) {
                    m.setAccessible(true);
                    m.invoke(null, gameDir, name, fb, 1, noop);
                    return true;
                }
            }
            E2ELog.warn("Screenshot.grab signature not found (" + screenshot.getName() + ")");
            return false;
        } catch (Throwable t) {
            E2ELog.warn("screenshot failed: " + t);
            return false;
        }
    }

    private static boolean rawScreenshot(
            Class<?> screenshot, File gameDir, String name, Object framebuffer) {
        Object image = null;
        try {
            // Through 1.21.4 the low-level copy returns NativeImage synchronously.
            for (Method method : screenshot.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers())
                        && parameters.length == 1
                        && parameters[0].isInstance(framebuffer)
                        && method.getReturnType() != void.class) {
                    method.setAccessible(true);
                    image = method.invoke(null, framebuffer);
                    if (image == null) {
                        E2ELog.warn("raw Screenshot.takeScreenshot returned no image");
                        return false;
                    }
                    return writeRawScreenshot(image, gameDir, name);
                }
            }

            // 1.21.5+ performs the GPU readback asynchronously and supplies NativeImage to a
            // callback. 1.21.6 also adds an overload with an integer downscale argument; prefer the
            // two-argument shape when present, then use a factor of one for the newer shape.
            for (int parameterCount : new int[] {2, 3}) {
                for (Method method : screenshot.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    boolean matchingShape = parameters.length == parameterCount
                            && parameters[0].isInstance(framebuffer)
                            && parameters[parameters.length - 1] == Consumer.class
                            && method.getReturnType() == void.class
                            && (parameterCount == 2 || parameters[1] == int.class);
                    if (!Modifier.isStatic(method.getModifiers()) || !matchingShape) continue;
                    Consumer<Object> writer = captured -> {
                        try {
                            if (captured == null || !writeRawScreenshot(captured, gameDir, name)) {
                                E2ELog.warn("raw Screenshot.takeScreenshot callback wrote no image");
                            }
                        } finally {
                            closeRawScreenshot(captured);
                        }
                    };
                    method.setAccessible(true);
                    if (parameterCount == 2) {
                        method.invoke(null, framebuffer, writer);
                    } else {
                        method.invoke(null, framebuffer, 1, writer);
                    }
                    return true;
                }
            }
            E2ELog.warn("raw Screenshot.takeScreenshot signature not found");
            return false;
        } catch (Throwable failure) {
            E2ELog.warn("raw screenshot failed: " + failure);
            return false;
        } finally {
            closeRawScreenshot(image);
        }
    }

    private static boolean writeRawScreenshot(Object image, File gameDir, String name) {
        try {
            File screenshotDirectory = new File(gameDir, "screenshots");
            if (!screenshotDirectory.isDirectory() && !screenshotDirectory.mkdirs()) {
                E2ELog.warn("could not create raw screenshot directory " + screenshotDirectory);
                return false;
            }
            File output = new File(screenshotDirectory, name);
            Method pathWriter = null;
            for (Method method : image.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || method.getReturnType() != void.class) continue;
                if (parameters[0] == File.class) {
                    method.invoke(image, output);
                    return true;
                }
                if (parameters[0] == java.nio.file.Path.class) pathWriter = method;
            }
            if (pathWriter != null) {
                pathWriter.invoke(image, output.toPath());
                return true;
            }
            E2ELog.warn("raw NativeImage writer signature not found");
            return false;
        } catch (Throwable failure) {
            E2ELog.warn("raw screenshot write failed: " + failure);
            return false;
        }
    }

    private static void closeRawScreenshot(Object image) {
        if (image instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** The main {@code RenderTarget}: {@code Minecraft.getMainRenderTarget()} or {@code mc.gameRenderer.mainRenderTarget()} (26.x). */
    private static Object mainRenderTarget(Minecraft mc) {
        try {
            Method m = findNoArg(
                    mc.getClass(), "getMainRenderTarget", "method_1522", "m_91385_"
            ); // 1.20.1..1.21.x
            if (m != null) return m.invoke(mc);
            Object gr = mc.gameRenderer; // 26.x: public field; RenderTarget via GameRenderer.mainRenderTarget()
            if (gr != null) {
                Method mm = findNoArg(gr.getClass(), "mainRenderTarget");
                if (mm != null) return mm.invoke(gr);
            }
        } catch (Throwable t) {
            E2ELog.warn("mainRenderTarget: " + t);
        }
        return null;
    }

    /** Resolve a named Minecraft class through Fabric's runtime mapping resolver when necessary. */
    private static Class<?> loadNamedClass(String namedClass) throws ClassNotFoundException {
        try {
            return Class.forName(namedClass);
        } catch (ClassNotFoundException namedMissing) {
            String intermediaryClass = null;
            if (namedClass.equals("net.minecraft.client.Screenshot")) {
                intermediaryClass = "net.minecraft.class_318";
            } else if (namedClass.equals("net.minecraft.server.permissions.Permission")) {
                intermediaryClass = "net.minecraft.class_12087";
            } else if (namedClass.equals(
                    "net.minecraft.server.permissions.Permission$HasCommandLevel")) {
                intermediaryClass = "net.minecraft.class_12087$class_12089";
            } else if (namedClass.equals(
                    "net.minecraft.server.permissions.PermissionLevel")) {
                intermediaryClass = "net.minecraft.class_12094";
            } else if (namedClass.equals("net.minecraft.server.permissions.PermissionSet")) {
                intermediaryClass = "net.minecraft.class_12096";
            }
            if (intermediaryClass != null) {
                try {
                    return Class.forName(intermediaryClass);
                } catch (ClassNotFoundException intermediaryMissing) {
                    namedMissing.addSuppressed(intermediaryMissing);
                }
            }
            try {
                Class<?> fabricLoaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
                Object loader = fabricLoaderClass.getMethod("getInstance").invoke(null);
                Object resolver = fabricLoaderClass.getMethod("getMappingResolver").invoke(loader);
                Class<?> resolverClass = Class.forName("net.fabricmc.loader.api.MappingResolver");
                String runtimeName = (String) resolverClass
                        .getMethod("mapClassName", String.class, String.class)
                        .invoke(resolver, "named", namedClass);
                return Class.forName(runtimeName);
            } catch (ClassNotFoundException noFabricLoader) {
                throw namedMissing;
            } catch (ReflectiveOperationException mappingFailure) {
                namedMissing.addSuppressed(mappingFailure);
                throw namedMissing;
            }
        }
    }

    /**
     * The window's current GUI scale factor, or {@code 0} if it could not be read.
     *
     * <p>Called straight through rather than reflected, unlike its neighbours here: the drift is in
     * the <em>type</em>, not the name - {@code double} through 1.21.5 and {@code int} from 1.21.6 -
     * and a widening conversion absorbs that at compile time, which reflection could not do without
     * knowing each era's obfuscated name for it. Kept in this class anyway because it is exactly the
     * kind of per-era drift the rest of the harness must not have to know about.
     */
    public static int guiScale(Minecraft mc) {
        try {
            double scale = mc.getWindow().getGuiScale();
            return (int) Math.round(scale);
        } catch (Throwable t) {
            E2ELog.warn("guiScale: " + t);
            return 0;
        }
    }

    /**
     * Set the window's GUI scale factor. The caller must reopen its screen afterwards, because the
     * scaled dimensions a screen lays itself out against are read once, when it is opened.
     */
    public static boolean setGuiScale(Minecraft mc, int scale) {
        try {
            mc.getWindow().setGuiScale(scale);
            return true;
        } catch (Throwable t) {
            E2ELog.warn("setGuiScale: " + t);
            return false;
        }
    }

    private static Method findNoArg(Class<?> c, String... names) {
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() == 0) {
                for (String name : names) {
                    if (m.getName().equals(name)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
        }
        return null;
    }

    private static Method findOneArg(Class<?> c, Object argument, String... names) {
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() != 1
                    || (m.getReturnType() != boolean.class
                    && m.getReturnType() != Boolean.class)
                    || !m.getParameterTypes()[0].isInstance(argument)) {
                continue;
            }
            for (String name : names) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static void warnTerrainReadinessOnce(String message) {
        if (terrainReadinessWarningEmitted) return;
        terrainReadinessWarningEmitted = true;
        E2ELog.warn(message);
    }

    private static Object invokeUniqueNoArgOnFieldValue(Object owner, String... methodNames)
            throws ReflectiveOperationException {
        Field matchedField = null;
        Method matchedAccessor = null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Method accessor = findNoArg(field.getType(), methodNames);
                if (accessor == null) continue;
                if (matchedField != null) return null;
                field.setAccessible(true);
                matchedField = field;
                matchedAccessor = accessor;
            }
        }
        if (matchedField == null) return null;
        Object fieldValue = matchedField.get(owner);
        return fieldValue == null ? null : matchedAccessor.invoke(fieldValue);
    }

    // =============================================================================================
    // Mouse input
    // =============================================================================================

    /** {@code MouseHandler} cursor position, by named/intermediary/SRG name. */
    private static final Set<String> MOUSE_X_FIELDS = Set.of("xpos", "field_1795", "f_91507_");
    private static final Set<String> MOUSE_Y_FIELDS = Set.of("ypos", "field_1794", "f_91508_");
    private static final int GLFW_PRESS = 1;
    private static final int GLFW_RELEASE = 0;

    /**
     * Presses and releases a mouse button over the current screen at a GUI-space point, exactly as
     * a user would.
     *
     * <p>Minecraft changed this boundary at 1.21.11: {@code Screen.mouseClicked(double,double,int)}
     * became {@code mouseClicked(MouseButtonEvent, boolean)}. The harness is one unpreprocessed
     * source tree compiled against every supported version, so it cannot spell both signatures.
     * Where the old one exists it is called directly and its verdict returned. Otherwise the click
     * is driven through {@code MouseHandler}'s GLFW button callback, whose
     * {@code (long, int, int, int)} shape is fixed by GLFW itself and therefore identical on every
     * version; Minecraft then builds whatever event object that version expects and dispatches it
     * through the same screen path. That callback returns nothing, so the caller must assert on the
     * resulting state rather than on a boolean.</p>
     *
     * @return {@code null} on success, or a message describing why the click could not be dispatched
     */
    public static String clickAt(Minecraft mc, double guiX, double guiY, int button) {
        String pressed = mousePress(mc, guiX, guiY, button);
        return pressed != null ? pressed : mouseRelease(mc, guiX, guiY, button);
    }

    /** Presses a mouse button over the current screen at a GUI-space point. */
    public static String mousePress(Minecraft mc, double guiX, double guiY, int button) {
        return dispatch(mc, guiX, guiY, button, "press");
    }

    /** Moves the held cursor to a GUI-space point, producing the era's drag dispatch. */
    public static String mouseDragTo(
            Minecraft mc, double guiX, double guiY, int button, double fromX, double fromY) {
        Screen screen = currentScreen(mc);
        if (screen == null) return "no screen is open";
        Method legacy = findMouseMethod6(screen.getClass(), "mouseDragged", "method_25403", "m_7979_");
        if (legacy != null) {
            try {
                legacy.setAccessible(true);
                Object handled = legacy.invoke(
                        screen, guiX, guiY, button, guiX - fromX, guiY - fromY);
                return Boolean.FALSE.equals(handled)
                        ? "screen " + screen.getClass().getSimpleName() + " ignored the drag"
                        : null;
            } catch (Throwable t) {
                return "Screen.mouseDragged failed: " + t;
            }
        }

        Method modern = findModernMouseDrag(
                screen.getClass(), "mouseDragged", "method_25403", "m_7979_");
        if (modern != null) {
            try {
                modern.setAccessible(true);
                Object event = newMouseButtonEvent(
                        modern.getParameterTypes()[0], guiX, guiY, button);
                Object handled = modern.invoke(
                        screen, event, guiX - fromX, guiY - fromY);
                return Boolean.FALSE.equals(handled)
                        ? "screen " + screen.getClass().getSimpleName() + " ignored the drag"
                        : null;
            } catch (Throwable t) {
                return "Screen.mouseDragged failed: " + t;
            }
        }
        return dispatchMove(mc, guiX, guiY);
    }

    /** Releases a mouse button over the current screen at a GUI-space point. */
    public static String mouseRelease(Minecraft mc, double guiX, double guiY, int button) {
        return dispatch(mc, guiX, guiY, button, "release");
    }

    private static String dispatch(
            Minecraft mc, double guiX, double guiY, int button, String action) {
        Screen screen = currentScreen(mc);
        if (screen == null) return "no screen is open";
        Method legacy = "press".equals(action)
                ? findMouseMethod(screen.getClass(), "mouseClicked", "method_25402", "m_6375_")
                : findMouseMethod(screen.getClass(), "mouseReleased", "method_25406", "m_6348_");
        if (legacy != null) {
            // The old signature carries the point, so pass it through untouched. Snapping it to
            // the cursor's integer GUI pixel would move a fractional target such as a slider
            // handle and silently change the value the widget computes.
            try {
                legacy.setAccessible(true);
                Object handled = legacy.invoke(screen, guiX, guiY, button);
                if ("press".equals(action) && Boolean.FALSE.equals(handled)) {
                    return "screen " + screen.getClass().getSimpleName()
                            + " did not handle the click at gui (" + guiX + "," + guiY + ")";
                }
                return null;
            } catch (Throwable t) {
                return "Screen.mouse" + action + " failed: " + t;
            }
        }
        // The newer callback reads the point from the cursor instead, so place it first.
        String positioned = moveMouseTo(mc, guiX, guiY);
        if (positioned != null) return positioned;
        return dispatchThroughMouseHandler(
                mc, button, "press".equals(action) ? GLFW_PRESS : GLFW_RELEASE);
    }


    /**
     * The {@code (double,double,int)} form of a screen mouse method, or null on newer versions.
     *
     * <p>Resolved against the concrete screen class with {@code getMethod}, because these are
     * public and, on several versions, inherited as {@code ContainerEventHandler} default methods
     * rather than declared anywhere in the class hierarchy. Walking superclasses alone silently
     * misses them and sends every click down the newer-era path.</p>
     */
    private static Method findMouseMethod(Class<?> owner, String... names) {
        return findPublicMethod(owner, new Class<?>[]{double.class, double.class, int.class}, names);
    }

    /** The six-argument {@code mouseDragged} of the pre-1.21.11 era, or null on newer versions. */
    private static Method findMouseMethod6(Class<?> owner, String... names) {
        return findPublicMethod(
                owner,
                new Class<?>[]{double.class, double.class, int.class, double.class, double.class},
                names);
    }

    /** The event-record {@code mouseDragged} used by 1.21.9 and newer, or null. */
    private static Method findModernMouseDrag(Class<?> owner, String... names) {
        Set<String> wanted = Set.of(names);
        Method shapeMatch = null;
        for (Method method : owner.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ((method.getReturnType() != boolean.class
                    && method.getReturnType() != Boolean.class)
                    || parameters.length != 3
                    || parameters[0].isPrimitive()
                    || parameters[1] != double.class
                    || parameters[2] != double.class) {
                continue;
            }
            if (wanted.contains(method.getName())) return method;
            if (shapeMatch != null) return null;
            shapeMatch = method;
        }
        return shapeMatch;
    }

    /** Builds the modern mouse event without importing types absent from older versions. */
    private static Object newMouseButtonEvent(
            Class<?> eventType, double guiX, double guiY, int button)
            throws ReflectiveOperationException {
        Constructor<?> eventConstructor = null;
        for (Constructor<?> constructor : eventType.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 3
                    || parameters[0] != double.class
                    || parameters[1] != double.class
                    || parameters[2].isPrimitive()) {
                continue;
            }
            if (eventConstructor != null) {
                throw new NoSuchMethodException("ambiguous modern mouse event constructor");
            }
            eventConstructor = constructor;
        }
        if (eventConstructor == null) {
            throw new NoSuchMethodException("modern mouse event constructor not found");
        }

        Class<?> buttonInfoType = eventConstructor.getParameterTypes()[2];
        Constructor<?> buttonInfoConstructor =
                buttonInfoType.getDeclaredConstructor(int.class, int.class);
        buttonInfoConstructor.setAccessible(true);
        Object buttonInfo = buttonInfoConstructor.newInstance(button, 0);
        eventConstructor.setAccessible(true);
        return eventConstructor.newInstance(guiX, guiY, buttonInfo);
    }

    private static Method findPublicMethod(Class<?> owner, Class<?>[] params, String... names) {
        for (String name : names) {
            try {
                return owner.getMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // try the next mapping name
            }
        }
        return null;
    }

    private static Method findPublicVoidOneArg(
            Class<?> owner, Class<?> parameter, String... names) {
        Method named = findPublicMethod(owner, new Class<?>[]{parameter}, names);
        if (named != null && named.getReturnType() == void.class) return named;
        Method shapeMatch = null;
        for (Method method : owner.getMethods()) {
            if (method.getReturnType() != void.class
                    || !java.util.Arrays.equals(
                    method.getParameterTypes(), new Class<?>[]{parameter})) {
                continue;
            }
            if (shapeMatch != null) return null;
            shapeMatch = method;
        }
        return shapeMatch;
    }

    private static Method findPublicNoArgReturning(
            Class<?> owner, Class<?> returnType, String... names) {
        Method named = findPublicNoArg(owner, names);
        if (named != null && returnType.isAssignableFrom(named.getReturnType())) return named;
        Method shapeMatch = null;
        for (Method method : owner.getMethods()) {
            if (method.getParameterCount() != 0
                    || !returnType.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if (shapeMatch != null) return null;
            shapeMatch = method;
        }
        return shapeMatch;
    }

    private static Method findPublicStaticIntFactory(Class<?> owner, String... names) {
        Method named = findPublicMethod(owner, new Class<?>[]{int.class}, names);
        if (named != null && Modifier.isStatic(named.getModifiers())
                && owner.isAssignableFrom(named.getReturnType())) {
            return named;
        }
        Method shapeMatch = null;
        for (Method method : owner.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || !owner.isAssignableFrom(method.getReturnType())
                    || !java.util.Arrays.equals(
                    method.getParameterTypes(), new Class<?>[]{int.class})) {
                continue;
            }
            if (shapeMatch != null) return null;
            shapeMatch = method;
        }
        return shapeMatch;
    }

    private static Method findPublicBooleanOneArg(
            Class<?> owner, Class<?> parameter, String... names) {
        Method named = findPublicMethod(owner, new Class<?>[]{parameter}, names);
        if (named != null && (named.getReturnType() == boolean.class
                || named.getReturnType() == Boolean.class)) {
            return named;
        }
        Method shapeMatch = null;
        for (Method method : owner.getMethods()) {
            if ((method.getReturnType() != boolean.class
                    && method.getReturnType() != Boolean.class)
                    || !java.util.Arrays.equals(
                    method.getParameterTypes(), new Class<?>[]{parameter})) {
                continue;
            }
            if (shapeMatch != null) return null;
            shapeMatch = method;
        }
        return shapeMatch;
    }

    /**
     * Drives one press/release pair through {@code MouseHandler}'s GLFW button callback.
     *
     * <p>The callback is resolved by named/intermediary/SRG name first; a version whose name is not
     * in that set still resolves because the GLFW callback is the only {@code (long,int,int,int)}
     * void method on the class.</p>
     */
    private static String dispatchThroughMouseHandler(Minecraft mc, int button, int action) {
        Object handler = mc.mouseHandler;
        if (handler == null) return "mouseHandler is null";
        Method onPress = findGlfwCallback(
                handler, new Class<?>[]{long.class, int.class, int.class, int.class},
                "onPress", "method_1611", "m_91530_");
        if (onPress == null) return "MouseHandler has no (long,int,int,int) GLFW button callback";
        try {
            onPress.setAccessible(true);
            onPress.invoke(handler, windowHandle(mc), button, action, 0);
            return null;
        } catch (Throwable t) {
            return "MouseHandler." + onPress.getName() + " failed: " + t;
        }
    }

    private static String dispatchMove(Minecraft mc, double guiX, double guiY) {
        Object handler = mc.mouseHandler;
        if (handler == null) return "mouseHandler is null";
        Method onMove = findGlfwCallback(
                handler, new Class<?>[]{long.class, double.class, double.class},
                "onMove", "method_1600", "m_91561_");
        if (onMove == null) return "MouseHandler has no (long,double,double) GLFW move callback";
        try {
            var window = mc.getWindow();
            int screenW = window.getScreenWidth();
            int screenH = window.getScreenHeight();
            int guiW = window.getGuiScaledWidth();
            int guiH = window.getGuiScaledHeight();
            if (screenW <= 0 || screenH <= 0 || guiW <= 0 || guiH <= 0) {
                return "window geometry unavailable: " + screenW + "x" + screenH
                        + " gui " + guiW + "x" + guiH;
            }
            int targetX = (int) Math.floor(guiX);
            int targetY = (int) Math.floor(guiY);
            double px = (targetX + 0.5) * screenW / guiW;
            double py = (targetY + 0.5) * screenH / guiH;
            onMove.setAccessible(true);
            onMove.invoke(handler, windowHandle(mc), px, py);

            // Since 1.21.9 onMove only accumulates a delta; the render loop normally drains it
            // later. The E2E gesture releases synchronously, so drain it now while activeButton is
            // still set or the screen never receives mouseDragged.
            Method movement = findNoArg(
                    handler.getClass(), "handleAccumulatedMovement", "method_55793");
            if (movement == null) {
                return "MouseHandler has no accumulated-movement drain";
            }
            movement.invoke(handler);
            int actualX = guiMouseX(mc);
            int actualY = guiMouseY(mc);
            if (actualX != targetX || actualY != targetY) {
                return "mouse drag landed at gui (" + actualX + "," + actualY
                        + ") expected (" + targetX + "," + targetY + ")";
            }
            return null;
        } catch (Throwable t) {
            return "MouseHandler." + onMove.getName() + " failed: " + t;
        }
    }

    /**
     * Resolves a GLFW callback by named/intermediary/SRG name, falling back to the only method of
     * that exact shape when a future mapping renames it. Several callbacks can share a GLFW shape,
     * so an ambiguous shape-only lookup fails closed instead of invoking an unrelated callback.
     */
    private static Method findGlfwCallback(Object owner, Class<?>[] params, String... names) {
        Set<String> wanted = Set.of(names);
        Method shapeMatch = null;
        boolean ambiguousShape = false;
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getReturnType() != void.class) continue;
                if (!java.util.Arrays.equals(method.getParameterTypes(), params)) continue;
                if (wanted.contains(method.getName())) return method;
                if (shapeMatch == null) {
                    shapeMatch = method;
                } else {
                    ambiguousShape = true;
                }
            }
        }
        return ambiguousShape ? null : shapeMatch;
    }

    public static int guiMouseX(Minecraft mc) {
        var window = mc.getWindow();
        return (int) (mc.mouseHandler.xpos() * (double) window.getGuiScaledWidth()
                / (double) window.getScreenWidth());
    }

    public static int guiMouseY(Minecraft mc) {
        var window = mc.getWindow();
        return (int) (mc.mouseHandler.ypos() * (double) window.getGuiScaledHeight()
                / (double) window.getScreenHeight());
    }

    /**
     * Put the real mouse at a GUI coordinate so the next {@code Screen.render(mouseX, mouseY)}
     * sees it there (tooltips, hover highlights).
     *
     * <p>First asks GLFW to warp the cursor to the matching physical pixel. GLFW deliberately does
     * not synthesise a cursor-position event for its own warps, and it ignores the request entirely
     * when the window is unfocused (the Xvfb case), so if {@code MouseHandler} still reports the old
     * position afterwards the handler's private {@code xpos}/{@code ypos} are written directly:
     * that pair is exactly what the render loop reads, and it is what the GLFW callback would have
     * stored. Minecraft field names are looked up as named + intermediary + SRG.
     *
     * @return null on success, otherwise the failure description
     */
    public static String moveMouseTo(Minecraft mc, double guiX, double guiY) {
        try {
            var window = mc.getWindow();
            int screenW = window.getScreenWidth();
            int screenH = window.getScreenHeight();
            int guiW = window.getGuiScaledWidth();
            int guiH = window.getGuiScaledHeight();
            if (screenW <= 0 || screenH <= 0 || guiW <= 0 || guiH <= 0) {
                return "window geometry unavailable: " + screenW + "x" + screenH
                        + " gui " + guiW + "x" + guiH;
            }
            int targetX = (int) Math.floor(guiX);
            int targetY = (int) Math.floor(guiY);
            // Aim at the middle of the GUI pixel so the truncating back-conversion lands on it.
            double px = (targetX + 0.5) * screenW / guiW;
            double py = (targetY + 0.5) * screenH / guiH;

            String warp = warpCursor(windowHandle(mc), px, py);
            if (guiMouseX(mc) != targetX || guiMouseY(mc) != targetY) {
                String forced = forceMouseHandlerPosition(mc, px, py);
                if (forced != null) {
                    return forced + (warp == null ? "" : "; cursor warp: " + warp);
                }
            }
            int gx = guiMouseX(mc);
            int gy = guiMouseY(mc);
            if (gx != targetX || gy != targetY) {
                return "mouse is at gui (" + gx + "," + gy + ") expected (" + targetX + ","
                        + targetY + "); handler=(" + mc.mouseHandler.xpos() + ","
                        + mc.mouseHandler.ypos() + ") wanted (" + px + "," + py + ")";
            }
            return null;
        } catch (Throwable t) {
            return "moveMouseTo failed: " + t;
        }
    }

    /** Best-effort {@code GLFW.glfwSetCursorPos}, resolved reflectively so the harness compiles without LWJGL. */
    private static String warpCursor(long windowHandle, double px, double py) {
        try {
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
            Method set = glfw.getMethod("glfwSetCursorPos", long.class, double.class, double.class);
            set.invoke(null, windowHandle, px, py);
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    private static String forceMouseHandlerPosition(Minecraft mc, double px, double py) {
        Object handler = mc.mouseHandler;
        if (handler == null) return "mouseHandler is null";
        Field xField = null;
        Field yField = null;
        List<String> doubles = new ArrayList<>();
        for (Class<?> type = handler.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != double.class) continue;
                doubles.add(field.getName());
                if (xField == null && MOUSE_X_FIELDS.contains(field.getName())) xField = field;
                if (yField == null && MOUSE_Y_FIELDS.contains(field.getName())) yField = field;
            }
        }
        if (xField == null || yField == null) {
            return "MouseHandler xpos/ypos fields not found among " + doubles;
        }
        try {
            xField.setAccessible(true);
            yField.setAccessible(true);
            xField.setDouble(handler, px);
            yField.setDouble(handler, py);
            return null;
        } catch (Throwable t) {
            return "could not write MouseHandler position: " + t;
        }
    }

    /** The GLFW window handle; {@code Window.getWindow()} is remapped per era. */
    private static long windowHandle(Minecraft mc) throws Exception {
        Object window = mc.getWindow();
        Method handle = findPublicNoArg(
                window.getClass(), "getWindow", "handle", "method_4490", "m_85439_");
        if (handle == null) throw new NoSuchMethodException("Window GLFW handle accessor not found");
        return ((Number) handle.invoke(window)).longValue();
    }

    private static Method findPublicNoArg(Class<?> owner, String... names) {
        for (String name : names) {
            try {
                return owner.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                // try the next mapping name
            }
        }
        return null;
    }

    /**
     * Scrolls the wheel over the current screen. {@code mouseScrolled} gained a horizontal axis at
     * 1.21.11, so both arities are resolved and the older one simply drops it.
     */
    public static String scrollAt(Minecraft mc, double guiX, double guiY, double notches) {
        Screen screen = currentScreen(mc);
        if (screen == null) return "no screen is open";
        String[] names = {"mouseScrolled", "method_25401", "m_6050_"};
        Method four = findPublicMethod(
                screen.getClass(),
                new Class<?>[]{double.class, double.class, double.class, double.class}, names);
        Method three = findPublicMethod(
                screen.getClass(),
                new Class<?>[]{double.class, double.class, double.class}, names);
        try {
            Object handled = four != null
                    ? four.invoke(screen, guiX, guiY, 0.0D, notches)
                    : three != null ? three.invoke(screen, guiX, guiY, notches) : null;
            if (four == null && three == null) return "Screen has no mouseScrolled overload";
            return Boolean.FALSE.equals(handled)
                    ? "screen " + screen.getClass().getSimpleName() + " ignored the scroll"
                    : null;
        } catch (Throwable t) {
            return "Screen.mouseScrolled failed: " + t;
        }
    }

    /**
     * Presses and releases a key over the current screen. {@code Screen.keyPressed} took
     * {@code (int,int,int)} before 1.21.11 and a {@code KeyEvent} afterwards, so the newer era is
     * driven through {@code KeyboardHandler}'s GLFW callback, whose shape GLFW fixes.
     *
     * @return {@code null} when the screen consumed the key, or a message explaining why not
     */
    public static String pressKey(Minecraft mc, int keyCode, int scanCode, int modifiers) {
        Screen screen = currentScreen(mc);
        if (screen == null) return "no screen is open";
        Method legacy = findPublicMethod(
                screen.getClass(), new Class<?>[]{int.class, int.class, int.class},
                "keyPressed", "method_25404", "m_7933_");
        if (legacy != null) {
            try {
                Object handled = legacy.invoke(screen, keyCode, scanCode, modifiers);
                return Boolean.FALSE.equals(handled)
                        ? "screen " + screen.getClass().getSimpleName()
                                + " did not consume key " + keyCode
                        : null;
            } catch (Throwable t) {
                return "Screen.keyPressed failed: " + t;
            }
        }
        Object handler = mc.keyboardHandler;
        if (handler == null) return "keyboardHandler is null";
        Method keyPress = findGlfwCallback(
                handler,
                new Class<?>[]{long.class, int.class, int.class, int.class, int.class},
                "keyPress", "method_1466", "m_90893_");
        if (keyPress == null) return "KeyboardHandler has no GLFW key callback";
        try {
            keyPress.setAccessible(true);
            long window = windowHandle(mc);
            keyPress.invoke(handler, window, keyCode, scanCode, GLFW_PRESS, modifiers);
            keyPress.invoke(handler, window, keyCode, scanCode, GLFW_RELEASE, modifiers);
            return null;
        } catch (Throwable t) {
            return "KeyboardHandler." + keyPress.getName() + " failed: " + t;
        }
    }

    /**
     * One packed pixel of a {@code NativeImage}. The accessor is renamed per era and its channel
     * order is not stable across those renames, so callers must compare packed values (opaque
     * black is {@code 0xFF000000} in every order) rather than unpack named channels.
     *
     * @return the packed pixel, or {@code null} when no accessor resolves
     */
    public static Integer nativeImagePixel(Object nativeImage, int x, int y) {
        if (nativeImage == null) return null;
        Method pixel = findPublicMethod(
                nativeImage.getClass(), new Class<?>[]{int.class, int.class},
                "getPixelRGBA", "getPixelABGR", "getPixel", "method_4315", "m_84985_");
        if (pixel == null) return null;
        try {
            return ((Number) pixel.invoke(nativeImage, x, y)).intValue();
        } catch (Throwable t) {
            E2ELog.warn("nativeImagePixel: " + t);
            return null;
        }
    }

    /**
     * Whether the player holds at least the given operator level.
     *
     * @return the verdict, or {@code null} when no accessor resolves in this runtime
     */
    public static Boolean hasPermissionLevel(Object player, int level) {
        if (player == null) return null;
        Method has = findPublicMethod(
                player.getClass(), new Class<?>[]{int.class},
                "hasPermissions", "hasPermission", "method_5687", "method_64475", "m_20310_");
        try {
            if (has != null) return (Boolean) has.invoke(player, level);

            Class<?> permissionSetType = loadNamedClass(
                    "net.minecraft.server.permissions.PermissionSet");
            Method permissions = findPublicNoArgReturning(
                    player.getClass(), permissionSetType, "permissions");
            Object permissionSet = permissions == null ? null : permissions.invoke(player);
            if (permissionSet == null) return null;

            Class<?> permissionLevelType = loadNamedClass(
                    "net.minecraft.server.permissions.PermissionLevel");
            Method byId = findPublicStaticIntFactory(permissionLevelType, "byId");
            Object permissionLevel = byId == null ? null : byId.invoke(null, level);
            if (permissionLevel == null) return null;

            Class<?> permissionType = loadNamedClass(
                    "net.minecraft.server.permissions.Permission");
            Class<?> commandLevelType = loadNamedClass(
                    "net.minecraft.server.permissions.Permission$HasCommandLevel");
            Object permission = commandLevelType
                    .getConstructor(permissionLevelType)
                    .newInstance(permissionLevel);
            Method hasPermission = findPublicBooleanOneArg(
                    permissionSetType, permissionType, "hasPermission");
            return hasPermission == null
                    ? null
                    : (Boolean) hasPermission.invoke(permissionSet, permission);
        } catch (Throwable t) {
            E2ELog.warn("hasPermissionLevel: " + t);
            return null;
        }
    }

    /** Unwraps a registry lookup that became {@code Optional<Holder.Reference<T>>} in newer eras. */
    public static Object unwrapRegistryValue(Object looked) {
        Object value = looked;
        for (int hop = 0; hop < 4 && value != null; hop++) {
            if (value instanceof java.util.Optional<?> optional) {
                value = optional.orElse(null);
                continue;
            }
            Method unwrap = findPublicNoArg(value.getClass(), "value", "comp_349", "get");
            if (unwrap == null || unwrap.getReturnType() == void.class) return value;
            try {
                Object next = unwrap.invoke(value);
                if (next == null || next == value) return value;
                value = next;
            } catch (Throwable t) {
                return value;
            }
        }
        return value;
    }
}
