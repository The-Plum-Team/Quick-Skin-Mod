package com.quickskin.mod.platform.neoforge;

import net.neoforged.fml.ModList;
//? if <1.21.9 {
import net.neoforged.fml.loading.FMLLoader;
//?} else {
import net.neoforged.fml.loading.FMLEnvironment;
//?}
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge implementation of loader services.
 */
@SuppressWarnings("unused")
public class PlatformHelperImpl {
    public static String getPlatformName() {
        return "NeoForge";
    }

    public static Path getGameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path getSkinsDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("quickskin").resolve("uploads").resolve("skins");
    }

    public static Path getCapesDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("quickskin").resolve("uploads").resolve("capes");
    }

    public static Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static Path getCacheDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("quickskin_cache");
    }

    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static String getModVersion() {
        return ModList.get()
                .getModContainerById("quickskin")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("UNKNOWN");
    }

    public static boolean isDevelopmentEnvironment() {
        //? if <1.21.9 {
        return !FMLLoader.isProduction();
        //?} else {
        return !FMLEnvironment.isProduction();
        //?}
    }
}
