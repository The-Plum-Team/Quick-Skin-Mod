package com.quickskin.mod.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The skin library every Minecraft instance of this computer shares, outside the instance: the
 * skins and capes the player imports and the choices made for them. Each instance's own upload
 * folders are still read beside it.
 *
 * <ul>
 *   <li>Windows: {@code %APPDATA%\QuickSkin}</li>
 *   <li>macOS: {@code ~/Library/Application Support/QuickSkin}</li>
 *   <li>Linux and others: {@code $XDG_DATA_HOME/quickskin}, else {@code ~/.local/share/quickskin}</li>
 * </ul>
 *
 * <p>{@code -Dquickskin.sharedLibraryDir=<path>} names another folder and
 * {@code -Dquickskin.sharedLibrary=false} turns it off. The packaged E2E harness never uses it,
 * so a run cannot read or change the library of the computer it runs on.
 */
public final class SharedLibrary {
    public static final String DISABLE_PROPERTY = "quickskin.sharedLibrary";
    public static final String DIRECTORY_PROPERTY = "quickskin.sharedLibraryDir";
    private static final String E2E_PROPERTY = "quickskin.e2e.enabled";

    private SharedLibrary() {
    }

    /** The shared library folder, or {@code null} when it is off or cannot be named. */
    public static Path root() {
        return resolve(System::getProperty, System.getenv());
    }

    static Path resolve(Function<String, String> properties, Map<String, String> environment) {
        if (Boolean.parseBoolean(properties.apply(E2E_PROPERTY))) return null;
        if ("false".equalsIgnoreCase(trimmed(properties.apply(DISABLE_PROPERTY)))) return null;
        try {
            String override = trimmed(properties.apply(DIRECTORY_PROPERTY));
            if (override != null) return absolute(override);

            String os = properties.apply("os.name");
            String home = trimmed(properties.apply("user.home"));
            String lowerOs = os == null ? "" : os.toLowerCase(Locale.ROOT);
            if (lowerOs.startsWith("windows")) {
                String appData = trimmed(environment.get("APPDATA"));
                if (appData != null) return absolute(appData).resolve("QuickSkin");
                return home == null ? null
                        : absolute(home).resolve("AppData").resolve("Roaming").resolve("QuickSkin");
            }
            if (lowerOs.startsWith("mac") || lowerOs.contains("darwin")) {
                return home == null ? null
                        : absolute(home).resolve("Library").resolve("Application Support").resolve("QuickSkin");
            }
            String dataHome = trimmed(environment.get("XDG_DATA_HOME"));
            if (dataHome != null && Path.of(dataHome).isAbsolute()) {
                return absolute(dataHome).resolve("quickskin");
            }
            return home == null ? null
                    : absolute(home).resolve(".local").resolve("share").resolve("quickskin");
        } catch (InvalidPathException error) {
            return null;
        }
    }

    public static Path skinsDirectory(Path root) {
        return root.resolve("skins");
    }

    public static Path capesDirectory(Path root) {
        return root.resolve("capes");
    }

    public static Path preferencesFile(Path root) {
        return root.resolve("skin-preferences.json");
    }

    /** The frame timing of the library's animated capes, one JSON file per cape content id. */
    public static Path animationsDirectory(Path root) {
        return root.resolve("animations");
    }

    /** The library's CPM models, mirrored into each instance's {@code player_models}. */
    public static Path cpmModelsDirectory(Path root) {
        return root.resolve("cpm-models");
    }

    private static Path absolute(String path) {
        Path resolved = Path.of(path);
        if (!resolved.isAbsolute()) throw new InvalidPathException(path, "not absolute");
        return resolved.normalize();
    }

    private static String trimmed(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
