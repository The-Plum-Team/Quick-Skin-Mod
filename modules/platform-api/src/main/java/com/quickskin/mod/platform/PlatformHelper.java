package com.quickskin.mod.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;

import java.nio.file.Path;

/**
 * Stable loader services. Architectury binds these methods to the selected loader when
 * the assembled common JAR is transformed. No Minecraft client types enter this API.
 */
public class PlatformHelper {
    @ExpectPlatform
    public static String getPlatformName() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static Path getGameDirectory() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static Path getSkinsDirectory() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static Path getCapesDirectory() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static Path getConfigDirectory() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static Path getCacheDirectory() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static boolean isModLoaded(String modId) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static String getModVersion() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static boolean isDevelopmentEnvironment() {
        throw new AssertionError();
    }
}
