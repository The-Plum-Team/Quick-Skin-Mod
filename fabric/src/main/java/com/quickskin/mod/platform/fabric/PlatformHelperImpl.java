package com.quickskin.mod.platform.fabric;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Fabric implementation of loader services.
 */
@SuppressWarnings("unused")
public class PlatformHelperImpl {
    public static String getPlatformName() {
        return "Fabric";
    }

    public static Path getGameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static Path getSkinsDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("quickskin").resolve("uploads").resolve("skins");
    }

    public static Path getCapesDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("quickskin").resolve("uploads").resolve("capes");
    }

    public static Path getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static Path getCacheDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("quickskin_cache");
    }

    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer("quickskin")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("UNKNOWN");
    }

    public static boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    /** Fabric has no login-handshake mod list; channel queries remain the only evidence. */
    public static String getRemoteModVersion(Object connection, String modId) {
        return null;
    }

    public static boolean remoteDeclaresChannel(Object connection, String channelId) {
        return false;
    }
}
