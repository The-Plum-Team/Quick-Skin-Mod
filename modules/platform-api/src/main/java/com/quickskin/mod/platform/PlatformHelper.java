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

    /**
     * The version a remote peer declared for a mod in the loader's login handshake, or
     * {@code null} when the loader has no such handshake or the peer did not declare it. The
     * value comes from the peer and is a hint about which receivers exist, never an authority.
     *
     * @param connection the exact {@code net.minecraft.network.Connection} of the session
     */
    @ExpectPlatform
    public static String getRemoteModVersion(Object connection, String modId) {
        throw new AssertionError();
    }

    /**
     * Whether the remote peer's loader login handshake declared a network channel, under the same
     * trust rule as {@link #getRemoteModVersion}.
     */
    @ExpectPlatform
    public static boolean remoteDeclaresChannel(Object connection, String channelId) {
        throw new AssertionError();
    }
}
