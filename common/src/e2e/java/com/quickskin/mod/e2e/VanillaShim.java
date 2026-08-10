package com.quickskin.mod.e2e;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Isolates the handful of vanilla calls whose signature drifts across Minecraft versions, so the rest
 * of the harness stays a single version-agnostic source set. Everything here is reflection-based and
 * returns only version-stable types ({@link String}, {@link Screen}), importing no type that was
 * renamed/moved across versions. {@link SplashRenderer} and {@link DefaultPlayerSkin} are the two
 * deliberate class-literal exceptions: their packages are identical on every supported version,
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
 *   <li><b>skin/cape texture location</b> (the render-truthful value the player renderer samples):
 *       {@code getSkinTextureLocation()}/{@code getCloakTextureLocation()} (1.20.1) →
 *       {@code getSkin().texture()/capeTexture()} returning a ResourceLocation (1.21.x) →
 *       {@code getSkin().body()/cape()} returning a {@code ClientAsset.Texture} whose
 *       {@code texturePath()} is the Identifier (26.x). Returned as a String so no renamed type
 *       ({@code ResourceLocation}→{@code Identifier}) is imported.</li>
 *   <li><b>UUID-selected default skin</b>: {@code DefaultPlayerSkin.getDefaultSkin(UUID)} returning
 *       a ResourceLocation (1.20.1) vs {@code DefaultPlayerSkin.get(UUID)} returning the same
 *       evolving player-skin record described above.</li>
 *   <li><b>player name</b>: {@code GameProfile.getName()} (authlib class) vs {@code name()} (authlib
 *       record, 26.x).</li>
 *   <li><b>main render target</b> (for screenshots): {@code Minecraft.getMainRenderTarget()} vs
 *       {@code mc.gameRenderer.mainRenderTarget()} (26.x).</li>
 *   <li><b>Screenshot.grab</b>: classic Fabric exposes intermediary class/method names at runtime,
 *       and an {@code int downscale} param was inserted in 1.21.6; both mappings and shapes are
 *       handled.</li>
 *   <li><b>widget press</b>: {@code AbstractWidget.onPress()} (no-arg) vs
 *       {@code onPress(InputWithModifiers)} (26.x).</li>
 *   <li><b>title splash construction</b>: {@code SplashRenderer(String)} vs
 *       {@code SplashRenderer(Component)}, plus its remapped private field on {@code TitleScreen}.</li>
 * </ul>
 *
 * <p>GL-touching calls (screenshot) must run on the render thread, after at least one full frame.</p>
 */
public final class VanillaShim {

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
     * The vanilla default skin selected for this exact profile UUID, as a texture location string.
     *
     * <p>The method name and return shape drift across supported versions. Matching the static
     * UUID signature avoids relying on an obfuscated name, while the class literal ensures Fabric's
     * harness remapper rewrites the owner. String-returning model helpers in 1.20.1 are ignored
     * because they are not resource locations.</p>
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

    private static String resolveLoc(AbstractClientPlayer p, String directName, String[] skinAccessors) {
        if (p == null) return null;
        try {
            // 1.20.1: a direct getter returning a ResourceLocation.
            Method direct = findNoArg(
                    p.getClass(),
                    directName,
                    directName.equals("getSkinTextureLocation") ? "method_3117" : "method_3119",
                    directName.equals("getSkinTextureLocation") ? "m_108560_" : "m_108561_"
            );
            if (direct != null) {
                Object r = direct.invoke(p);
                return (r == null) ? null : r.toString();
            }
            // 1.21.x/26.x: getSkin() -> PlayerSkin, then an accessor for the skin/cape component.
            Method getSkin = findNoArg(p.getClass(), "getSkin", "method_52814", "method_52810");
            if (getSkin != null) {
                Object skin = getSkin.invoke(p);
                if (skin != null) {
                    for (String acc : skinAccessors) {
                        Method m = switch (acc) {
                            case "texture" -> findNoArg(skin.getClass(), acc, "comp_1626");
                            case "capeTexture" -> findNoArg(skin.getClass(), acc, "comp_1627");
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
        } catch (Throwable t) {
            E2ELog.warn("resolveLoc(" + directName + "): " + t);
        }
        return null;
    }

    private static String unwrapDefaultSkinTexture(Object candidate) throws ReflectiveOperationException {
        if (candidate == null) return null;

        // 1.21.x/26.x: PlayerSkin -> texture/body -> optional ClientAsset.Texture.texturePath().
        for (String accessor : new String[]{"texture", "body"}) {
            Method component = accessor.equals("texture")
                    ? findNoArg(candidate.getClass(), accessor, "comp_1626")
                    : findNoArg(candidate.getClass(), accessor);
            if (component == null) continue;
            Object texture = component.invoke(candidate);
            if (texture == null) return null;
            Method texturePath = findNoArg(texture.getClass(), "texturePath", "comp_3627");
            Object location = texturePath == null ? texture : texturePath.invoke(texture);
            String value = location == null ? null : location.toString();
            return isResourceLocation(value) ? value : null;
        }

        // 1.20.1: getDefaultSkin(UUID) returns the ResourceLocation directly. The same class also
        // exposes a UUID -> model-name helper, so accept only the lexical shape of a location.
        String value = candidate.toString();
        return isResourceLocation(value) ? value : null;
    }

    private static boolean isResourceLocation(String value) {
        return value != null && value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+");
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
     * Capture the current main framebuffer to {@code <runDir>/screenshots/<name>}.
     * @return true if the grab call was dispatched.
     */
    public static boolean screenshot(Minecraft mc, String name) {
        try {
            Class<?> screenshot = loadNamedClass("net.minecraft.client.Screenshot");
            File gameDir = new File(System.getProperty("user.dir"));
            Object fb = mainRenderTarget(mc);
            if (fb == null) { E2ELog.warn("main render target unavailable"); return false; }
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
            if (namedClass.equals("net.minecraft.client.Screenshot")) {
                try {
                    return Class.forName("net.minecraft.class_318");
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
}
