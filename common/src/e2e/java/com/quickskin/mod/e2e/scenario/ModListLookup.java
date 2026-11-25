package com.quickskin.mod.e2e.scenario;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * Asks the running loader's mod list for Quick Skin's configuration screen exactly as its Config
 * button does. The harness is one source for every loader, so each loader's API is reached by
 * name: only the running loader's classes exist.
 */
final class ModListLookup {
    static final String MOD_ID = "quickskin";
    static final String MOD_MENU_ENTRY = "com.quickskin.mod.fabric.QuickSkinModMenu";

    private ModListLookup() {
    }

    /**
     * @param route        receives the lookup that was made
     * @param declaredOnly receives the evidence when Fabric runs without Mod Menu, where no list
     *                     exists to ask; the screen returned is then {@code null}
     * @return the configuration screen, or {@code null}
     */
    static Screen configScreen(Minecraft mc, Screen parent, AtomicReference<String> route,
                               AtomicReference<String> declaredOnly) throws Exception {
        Class<?> fabricLoader = find("net.fabricmc.loader.api.FabricLoader");
        if (fabricLoader != null) {
            return fabric(fabricLoader, parent, route, declaredOnly);
        }
        Class<?> neoForgeList = find("net.neoforged.fml.ModList");
        if (neoForgeList != null) {
            return neoForge(mc, neoForgeList, parent, route);
        }
        Class<?> forgeList = find("net.minecraftforge.fml.ModList");
        if (forgeList != null) {
            return forge(mc, forgeList, parent, route);
        }
        throw new IllegalStateException("no known loader is running");
    }

    private static Screen fabric(Class<?> fabricLoader, Screen parent, AtomicReference<String> route,
                                 AtomicReference<String> declaredOnly) throws Exception {
        Object loader = fabricLoader.getMethod("getInstance").invoke(null);
        if ((boolean) fabricLoader.getMethod("isModLoaded", String.class).invoke(loader, "modmenu")) {
            route.set("Mod Menu's ModMenu.getConfigScreen");
            Class<?> modMenu = Class.forName("com.terraformersmc.modmenu.ModMenu");
            return (Screen) modMenu.getMethod("getConfigScreen", String.class, Screen.class)
                    .invoke(null, MOD_ID, parent);
        }
        route.set("Fabric's metadata (Mod Menu is not installed)");
        Optional<?> container = (Optional<?>) fabricLoader.getMethod("getModContainer", String.class)
                .invoke(loader, MOD_ID);
        Object mod = container.orElseThrow(() -> new IllegalStateException("Fabric lists no " + MOD_ID));
        Class<?> containerType = Class.forName("net.fabricmc.loader.api.ModContainer");
        List<?> roots = (List<?>) containerType.getMethod("getRootPaths").invoke(mod);
        for (Object root : roots) {
            Path metadata = ((Path) root).resolve("fabric.mod.json");
            if (!Files.isRegularFile(metadata)) continue;
            JsonObject json = JsonParser.parseString(Files.readString(metadata, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject entrypoints = json.getAsJsonObject("entrypoints");
            JsonArray modMenu = entrypoints == null ? null : entrypoints.getAsJsonArray("modmenu");
            if (modMenu == null || modMenu.size() != 1) {
                throw new IllegalStateException("fabric.mod.json declares no single modmenu entry point");
            }
            JsonElement entry = modMenu.get(0);
            String name = entry.isJsonPrimitive() ? entry.getAsString() : entry.getAsJsonObject().get("value").getAsString();
            if (!MOD_MENU_ENTRY.equals(name)) {
                throw new IllegalStateException("the modmenu entry point is " + name + ", not " + MOD_MENU_ENTRY);
            }
            Path entryClass = ((Path) root).resolve(name.replace('.', '/') + ".class");
            if (!Files.isRegularFile(entryClass)) {
                throw new IllegalStateException("the modmenu entry point class is not packaged: " + entryClass);
            }
            Path stub = ((Path) root).resolve("com/terraformersmc/modmenu/api/ModMenuApi.class");
            if (Files.exists(stub)) {
                throw new IllegalStateException("Quick Skin packages Mod Menu's API, which Mod Menu itself must bring");
            }
            declaredOnly.set("Mod Menu is not installed, and Quick Skin loaded without it: fabric.mod.json declares the"
                    + " modmenu entry point " + name + ", its class is packaged and Mod Menu's API is not");
            return null;
        }
        throw new IllegalStateException("Quick Skin's fabric.mod.json was not found in " + roots);
    }

    private static Screen neoForge(Minecraft mc, Class<?> modList, Screen parent, AtomicReference<String> route)
            throws Exception {
        route.set("NeoForge's IConfigScreenFactory extension point");
        Object container = container(modList);
        Class<?> containerType = Class.forName("net.neoforged.fml.ModContainer");
        Class<?> factoryType = Class.forName("net.neoforged.neoforge.client.gui.IConfigScreenFactory");
        Optional<?> extension = (Optional<?>) containerType.getMethod("getCustomExtension", Class.class)
                .invoke(container, factoryType);
        Object factory = extension.orElseThrow(() -> new IllegalStateException("Quick Skin registered no IConfigScreenFactory"));
        for (Method method : factoryType.getMethods()) {
            if (!method.getName().equals("createScreen") || method.getParameterCount() != 2) continue;
            // NeoForge 20.6 hands the factory the game, later versions the mod's container
            Object owner = method.getParameterTypes()[0].isInstance(container) ? container : mc;
            return (Screen) method.invoke(factory, owner, parent);
        }
        throw new IllegalStateException("IConfigScreenFactory has no createScreen");
    }

    @SuppressWarnings("unchecked")
    private static Screen forge(Minecraft mc, Class<?> modList, Screen parent, AtomicReference<String> route)
            throws Exception {
        route.set("Forge's ConfigScreenHandler.getScreenFactoryFor");
        Object container = container(modList);
        Object info = Class.forName("net.minecraftforge.fml.ModContainer").getMethod("getModInfo").invoke(container);
        Class<?> handler = Class.forName("net.minecraftforge.client.ConfigScreenHandler");
        Class<?> infoType = Class.forName("net.minecraftforge.forgespi.language.IModInfo");
        Optional<?> factory = (Optional<?>) handler.getMethod("getScreenFactoryFor", infoType).invoke(null, info);
        BiFunction<Minecraft, Screen, Screen> create = (BiFunction<Minecraft, Screen, Screen>) factory
                .orElseThrow(() -> new IllegalStateException("Forge has no configuration screen for Quick Skin"));
        return create.apply(mc, parent);
    }

    private static Object container(Class<?> modList) throws Exception {
        Object list = modList.getMethod("get").invoke(null);
        Optional<?> container = (Optional<?>) modList.getMethod("getModContainerById", String.class).invoke(list, MOD_ID);
        return container.orElseThrow(() -> new IllegalStateException("the mod list has no " + MOD_ID));
    }

    private static Class<?> find(String name) {
        try {
            return Class.forName(name, false, ModListLookup.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }
}
