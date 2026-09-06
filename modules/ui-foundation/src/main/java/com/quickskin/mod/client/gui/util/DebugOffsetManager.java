package com.quickskin.mod.client.gui.util;


import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Manages debug offsets for player widgets on different screens.
 * Allows independent positioning for Title Screen and Pause Screen models.
 */
public class DebugOffsetManager {

    private static final String CONFIG_FILE = "quickskin_debug_offsets.properties";
    private static Path configPath;

    // Default offsets for all 4 screens (base offset + config offset when config is 0)
    private static final int DEFAULT_TITLE_SCREEN_OFFSET_X = -20; // Was -26, now -26 + 6 = -20
    private static final int DEFAULT_TITLE_SCREEN_OFFSET_Y = -90; // Was -87, now -87 + (-3) = -90
    private static final int DEFAULT_WORLD_SELECTION_OFFSET_X = 0;
    private static final int DEFAULT_WORLD_SELECTION_OFFSET_Y = -82;
    private static final int DEFAULT_PAUSE_SCREEN_OFFSET_X = -23; // Was -22, now -22 + (-1) = -23
    private static final int DEFAULT_PAUSE_SCREEN_OFFSET_Y = -88; // Was -88, stays -88 + 0 = -88
    private static final int DEFAULT_SKIN_MENU_OFFSET_X = 0; // Stays 0 + 0 = 0
    private static final int DEFAULT_SKIN_MENU_OFFSET_Y = -18; // Was -15, now -15 + (-3) = -18

    // Current offsets
    private static int titleScreenOffsetX = DEFAULT_TITLE_SCREEN_OFFSET_X;
    private static int titleScreenOffsetY = DEFAULT_TITLE_SCREEN_OFFSET_Y;
    private static int worldSelectionOffsetX = DEFAULT_WORLD_SELECTION_OFFSET_X;
    private static int worldSelectionOffsetY = DEFAULT_WORLD_SELECTION_OFFSET_Y;
    private static int pauseScreenOffsetX = DEFAULT_PAUSE_SCREEN_OFFSET_X;
    private static int pauseScreenOffsetY = DEFAULT_PAUSE_SCREEN_OFFSET_Y;
    private static int skinMenuOffsetX = DEFAULT_SKIN_MENU_OFFSET_X;
    private static int skinMenuOffsetY = DEFAULT_SKIN_MENU_OFFSET_Y;

    // Debug mode flag
    private static boolean debugMode = false;

    static {
        try {
            // Get config directory
            Path configDir = Path.of("config");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            configPath = configDir.resolve(CONFIG_FILE);
            load();
        } catch (Exception e) {
        }
    }

    /**
     * Load offsets from config file
     */
    public static void load() {
        if (!Files.exists(configPath)) {
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configPath)) {
            props.load(in);

            titleScreenOffsetX = Integer.parseInt(props.getProperty("titleScreenOffsetX", String.valueOf(DEFAULT_TITLE_SCREEN_OFFSET_X)));
            titleScreenOffsetY = Integer.parseInt(props.getProperty("titleScreenOffsetY", String.valueOf(DEFAULT_TITLE_SCREEN_OFFSET_Y)));
            worldSelectionOffsetX = Integer.parseInt(props.getProperty("worldSelectionOffsetX", String.valueOf(DEFAULT_WORLD_SELECTION_OFFSET_X)));
            worldSelectionOffsetY = Integer.parseInt(props.getProperty("worldSelectionOffsetY", String.valueOf(DEFAULT_WORLD_SELECTION_OFFSET_Y)));
            pauseScreenOffsetX = Integer.parseInt(props.getProperty("pauseScreenOffsetX", String.valueOf(DEFAULT_PAUSE_SCREEN_OFFSET_X)));
            pauseScreenOffsetY = Integer.parseInt(props.getProperty("pauseScreenOffsetY", String.valueOf(DEFAULT_PAUSE_SCREEN_OFFSET_Y)));
            skinMenuOffsetX = Integer.parseInt(props.getProperty("skinMenuOffsetX", String.valueOf(DEFAULT_SKIN_MENU_OFFSET_X)));
            skinMenuOffsetY = Integer.parseInt(props.getProperty("skinMenuOffsetY", String.valueOf(DEFAULT_SKIN_MENU_OFFSET_Y)));
            debugMode = Boolean.parseBoolean(props.getProperty("debugMode", "false"));

        } catch (Exception e) {
        }
    }

    /**
     * Save offsets to config file
     */
    public static void save() {
        Properties props = new Properties();
        props.setProperty("titleScreenOffsetX", String.valueOf(titleScreenOffsetX));
        props.setProperty("titleScreenOffsetY", String.valueOf(titleScreenOffsetY));
        props.setProperty("worldSelectionOffsetX", String.valueOf(worldSelectionOffsetX));
        props.setProperty("worldSelectionOffsetY", String.valueOf(worldSelectionOffsetY));
        props.setProperty("pauseScreenOffsetX", String.valueOf(pauseScreenOffsetX));
        props.setProperty("pauseScreenOffsetY", String.valueOf(pauseScreenOffsetY));
        props.setProperty("skinMenuOffsetX", String.valueOf(skinMenuOffsetX));
        props.setProperty("skinMenuOffsetY", String.valueOf(skinMenuOffsetY));
        props.setProperty("debugMode", String.valueOf(debugMode));

        try (OutputStream out = Files.newOutputStream(configPath)) {
            props.store(out, "QuickSkin Debug Offsets - Hold SHIFT and drag the player model to adjust positioning");
        } catch (Exception e) {
        }
    }

    /**
     * Get offset X for a specific screen type
     */
    public static int getOffsetX(String screenType) {
        return switch (screenType) {
            case "title" -> titleScreenOffsetX;
            case "world_selection" -> worldSelectionOffsetX;
            case "pause" -> pauseScreenOffsetX;
            case "skin_menu" -> skinMenuOffsetX;
            default -> 0;
        };
    }

    /**
     * Get offset Y for a specific screen type
     */
    public static int getOffsetY(String screenType) {
        return switch (screenType) {
            case "title" -> titleScreenOffsetY;
            case "world_selection" -> worldSelectionOffsetY;
            case "pause" -> pauseScreenOffsetY;
            case "skin_menu" -> skinMenuOffsetY;
            default -> 0;
        };
    }

    /**
     * Check if debug mode is enabled
     */
    public static boolean isDebugMode() {
        return debugMode;
    }

    /**
     * Enable debug mode (allows dragging models)
     */
    public static void setDebugMode(boolean enabled) {
        debugMode = enabled;
        save();
    }
}
