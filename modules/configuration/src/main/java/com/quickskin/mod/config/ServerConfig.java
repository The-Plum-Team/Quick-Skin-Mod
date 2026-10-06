package com.quickskin.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.platform.PlatformHelper;

import java.io.IOException;
import java.math.BigDecimal;
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
    /** Bounds of {@link #maxTextureUploadKilobytes}; the upper one is the protocol's hard cap. */
    public static final int MIN_TEXTURE_UPLOAD_KILOBYTES = 64;
    public static final int MAX_TEXTURE_UPLOAD_KILOBYTES = 16 * 1024;
    private static volatile ServerConfig instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Skin Settings
    public boolean disableSkinTransparency = false; // Disable transparency in player skins
    public int skinChangeCooldownSeconds = 0; // Cooldown in seconds for changing skin (0 = disabled)
    /**
     * Largest skin or cape (the final PNG a client transmits, animation metadata included) this
     * server accepts, stores and serves, in KiB. 64 to 16384 (the default); a value outside that
     * range is clamped, and one that is not a number is ignored. It is read when the server
     * starts and the server writes its settings back when it stops, so edit the file while the
     * server is stopped.
     *
     * <p>Clients that include this change learn the limit when they connect, keep a larger
     * texture on their own screen and sync the rest of their appearance. Released Quick Skin
     * 3.0.x and 3.1.0 clients learn it too, but while one of their textures is over it they
     * withhold their whole appearance (skin, cape and model) and retry until they update or pick
     * a smaller texture. The server cannot change that; lower the limit only when those players
     * can update.</p>
     */
    public int maxTextureUploadKilobytes = MAX_TEXTURE_UPLOAD_KILOBYTES;
    /** False when the file could not be read; such a file is never overwritten. */
    private transient boolean persistable = true;

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
        return load(getConfigPath());
    }

    /**
     * Reads the file, or writes the defaults when there is none. A file that cannot be read is
     * left untouched: the defaults used instead are never saved over it.
     */
    static ServerConfig load(Path configPath) {
        if (Files.exists(configPath)) {
            try {
                String json = BoundedFileReader.readUtf8(configPath, MAX_CONFIG_BYTES);
                ServerConfig config = parse(json);
                if (config != null) {
                    config.normalize();
                    return config;
                }
                QuickSkinInfo.LOGGER.error("Server config {} is not a JSON object; using defaults"
                        + " and leaving the file untouched until it is fixed", configPath);
            } catch (Exception e) {
                QuickSkinInfo.LOGGER.error("Could not read server config {}; using defaults and"
                        + " leaving the file untouched until it is fixed", configPath, e);
            }
            ServerConfig fallback = new ServerConfig();
            fallback.persistable = false;
            return fallback;
        }

        // Return default config and save it
        ServerConfig config = new ServerConfig();
        config.save(configPath);
        return config;
    }

    /**
     * Parses a configuration document. An invalid number in one setting is repaired or dropped
     * (see {@link #sanitizeInteger}) instead of discarding the whole document.
     *
     * @return null when the document is not a JSON object
     */
    private static ServerConfig parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (root == null || !root.isJsonObject()) return null;
        JsonObject object = root.getAsJsonObject();
        sanitizeInteger(object, "skinChangeCooldownSeconds");
        sanitizeInteger(object, "maxTextureUploadKilobytes");
        return GSON.fromJson(object, ServerConfig.class);
    }

    /**
     * Gson refuses a number outside the int range or with a fraction, which used to discard the
     * whole file. Such a number is clamped to the int range and truncated, and a value that is
     * not a number at all is dropped so the default applies. {@link #normalize()} then clamps
     * the result to the range of the setting.
     */
    private static void sanitizeInteger(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null) return;
        BigDecimal number = null;
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isNumber() || primitive.isString()) {
                try {
                    number = new BigDecimal(primitive.getAsString().trim());
                } catch (NumberFormatException ignored) {
                    number = null;
                }
            }
        }
        if (number == null) {
            QuickSkinInfo.LOGGER.warn("Ignoring the invalid server config value {} = {}; using"
                    + " its default", name, value);
            object.remove(name);
            return;
        }
        BigDecimal clamped = number.max(BigDecimal.valueOf(Integer.MIN_VALUE))
                .min(BigDecimal.valueOf(Integer.MAX_VALUE));
        object.addProperty(name, clamped.intValue());
    }

    /**
     * Save configuration to file
     */
    public synchronized void save() {
        save(getConfigPath());
    }

    synchronized void save(Path configPath) {
        if (!persistable) {
            QuickSkinInfo.LOGGER.warn("Not saving server config {}: it could not be read when the"
                    + " server started, so it is left as it is", configPath);
            return;
        }
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
            ServerConfig config = parse(json);
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
        maxTextureUploadKilobytes = Math.max(MIN_TEXTURE_UPLOAD_KILOBYTES,
                Math.min(maxTextureUploadKilobytes, MAX_TEXTURE_UPLOAD_KILOBYTES));
    }

    /** The configured per-texture upload limit in bytes. */
    public synchronized int maxTextureUploadBytes() {
        normalize();
        return maxTextureUploadKilobytes * 1024;
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
