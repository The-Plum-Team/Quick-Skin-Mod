package com.quickskin.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.platform.PlatformHelper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Server-side configuration for QuickSkin
 * Stored in JSON format in config directory
 */
public class ServerConfig {
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static volatile ServerConfig instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Skin Settings
    public boolean disableSkinTransparency = false; // Disable transparency in player skins
    public int skinChangeCooldownSeconds = 0; // Cooldown in seconds for changing skin (0 = disabled)

    // Logging Settings

    private ServerConfig() {
        // Private constructor for singleton
    }

    public static synchronized ServerConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Load configuration from file
     */
    private static ServerConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try {
                String json = BoundedFileReader.readUtf8(configPath, MAX_CONFIG_BYTES);
                ServerConfig config = GSON.fromJson(json, ServerConfig.class);
                if (config != null) {
                    config.normalize();
                    return config;
                }
                QuickSkinInfo.LOGGER.warn("Server config {} contained JSON null; using defaults", configPath);
            } catch (Exception e) {
                QuickSkinInfo.LOGGER.warn("Could not load server config {}; using defaults", configPath, e);
            }
        }

        // Return default config and save it
        ServerConfig config = new ServerConfig();
        config.save();
        return config;
    }

    /**
     * Save configuration to file
     */
    public synchronized void save() {
        Path configPath = getConfigPath();

        try {
            // Ensure config directory exists
            Files.createDirectories(configPath.getParent());

            normalize();
            String json = GSON.toJson(this);
            writeAtomically(configPath, json);
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.error("Could not save server config {}", configPath, e);
        }
    }

    /**
     * Get config file path
     */
    private static Path getConfigPath() {
        return PlatformHelper.getConfigDirectory().resolve("quickskin-server.json");
    }

    /**
     * Reload configuration from file
     */
    public static synchronized void reload() {
        instance = load();
    }

    /**
     * Convert to JSON for network transmission
     */
    public synchronized String toJson() {
        normalize();
        return GSON.toJson(this);
    }

    /**
     * Create from JSON (for network reception)
     */
    public static ServerConfig fromJson(String json) {
        try {
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
                QuickSkinInfo.LOGGER.warn("Received an oversized QuickSkin server configuration; using defaults");
                return new ServerConfig();
            }
            ServerConfig config = GSON.fromJson(json, ServerConfig.class);
            if (config == null) {
                QuickSkinInfo.LOGGER.warn("Received a null QuickSkin server configuration; using defaults");
                return new ServerConfig();
            }
            config.normalize();
            return config;
        } catch (Exception e) {
            QuickSkinInfo.LOGGER.warn("Received an invalid QuickSkin server configuration; using defaults", e);
            return new ServerConfig();
        }
    }

    private void normalize() {
        skinChangeCooldownSeconds = Math.max(0, Math.min(skinChangeCooldownSeconds, 86_400));
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_CONFIG_BYTES) {
                throw new IOException("Server configuration exceeds the size limit");
            }
            Files.write(temporary, encoded);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
