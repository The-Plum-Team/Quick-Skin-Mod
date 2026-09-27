package com.quickskin.mod.platform.forge;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.NetworkHooks;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.file.Path;

/**
 * Forge implementation of PlatformHelper
 * This class provides Forge-specific implementations for @ExpectPlatform methods
 */
@SuppressWarnings("unused")
public class PlatformHelperImpl {

    public static String getPlatformName() {
        return "Forge";
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
        return !FMLLoader.isProduction();
    }

    /** From the server's FML mod data, which Forge receives before the client joins the world. */
    public static String getRemoteModVersion(Object connection, String modId) {
        ConnectionData data = connectionData(connection);
        if (data == null || modId == null) return null;
        Pair<String, String> mod = data.getModData().get(modId);
        String version = mod != null ? mod.getRight() : null;
        return version == null || version.isEmpty() ? null : version;
    }

    public static boolean remoteDeclaresChannel(Object connection, String channelId) {
        ConnectionData data = connectionData(connection);
        ResourceLocation channel = channelId != null ? ResourceLocation.tryParse(channelId) : null;
        return data != null && channel != null && data.getChannels().containsKey(channel);
    }

    private static ConnectionData connectionData(Object connection) {
        return connection instanceof Connection network ? NetworkHooks.getConnectionData(network) : null;
    }
}
