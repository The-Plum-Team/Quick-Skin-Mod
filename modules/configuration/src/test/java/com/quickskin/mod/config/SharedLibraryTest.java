package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SharedLibraryTest {
    private static Path resolve(Map<String, String> properties, Map<String, String> environment) {
        return SharedLibrary.resolve(properties::get, environment);
    }

    private static Map<String, String> system(String os, String home) {
        Map<String, String> properties = new HashMap<>();
        properties.put("os.name", os);
        properties.put("user.home", home);
        return properties;
    }

    @Test
    void eachSystemKeepsTheLibraryWhereItKeepsApplicationData() {
        Path linuxHome = Path.of("/home/steve");
        assertEquals(linuxHome.resolve(".local/share/quickskin"),
                resolve(system("Linux", "/home/steve"), Map.of()));
        assertEquals(Path.of("/data/xdg/quickskin"),
                resolve(system("Linux", "/home/steve"), Map.of("XDG_DATA_HOME", "/data/xdg")));
        // A relative XDG_DATA_HOME is invalid by the specification and ignored
        assertEquals(linuxHome.resolve(".local/share/quickskin"),
                resolve(system("Linux", "/home/steve"), Map.of("XDG_DATA_HOME", "relative")));
        assertEquals(Path.of("/Users/alex/Library/Application Support/QuickSkin"),
                resolve(system("Mac OS X", "/Users/alex"), Map.of()));
    }

    @Test
    void windowsUsesAppData() {
        Path root = resolve(system("Windows 11", "/home/steve"), Map.of("APPDATA", "/roaming"));
        assertEquals(Path.of("/roaming/QuickSkin"), root);
        Path fallback = resolve(system("Windows 11", "/home/steve"), Map.of());
        assertEquals(Path.of("/home/steve/AppData/Roaming/QuickSkin"), fallback);
    }

    @Test
    void itCanBeMovedOrTurnedOffAndNeverServesTheE2eHarness() {
        Map<String, String> properties = system("Linux", "/home/steve");
        properties.put(SharedLibrary.DIRECTORY_PROPERTY, "/elsewhere/lib");
        assertEquals(Path.of("/elsewhere/lib"), resolve(properties, Map.of()));

        properties.put(SharedLibrary.DISABLE_PROPERTY, "false");
        assertNull(resolve(properties, Map.of()));

        Map<String, String> e2e = system("Linux", "/home/steve");
        e2e.put("quickskin.e2e.enabled", "true");
        assertNull(resolve(e2e, Map.of()));
    }

    @Test
    void aMissingOrRelativeHomeNamesNoFolder() {
        assertNull(resolve(system("Linux", null), Map.of()));
        assertNull(resolve(system("Linux", "relative/home"), Map.of()));
        Map<String, String> relativeOverride = system("Linux", "/home/steve");
        relativeOverride.put(SharedLibrary.DIRECTORY_PROPERTY, "lib");
        assertNull(resolve(relativeOverride, Map.of()));
    }
}
