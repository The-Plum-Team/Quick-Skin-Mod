package com.quickskin.mod.client.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.common.util.SafeImageReader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Service for interacting with Mojang's API to fetch player skins
 */
@Environment(EnvType.CLIENT)
public final class MojangApiService {
    private static final MojangApiService INSTANCE = new MojangApiService();

    private static final String MOJANG_API_BASE = "https://api.mojang.com";
    private static final String SESSION_SERVER_BASE = "https://sessionserver.mojang.com";
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final int MAX_SKIN_BYTES = 2 * 1024 * 1024;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern TEXTURE_PATH =
            Pattern.compile("/texture/[0-9a-fA-F]{32,128}");
    private MojangApiService() {}

    public static MojangApiService getInstance() {
        return INSTANCE;
    }

    public static void init() {
        getInstance();
    }

    /**
     * Fetch a player's UUID from their username
     * @param username The player's username
     * @return CompletableFuture containing the UUID, or null if not found
     */
    public CompletableFuture<UUID> getUuidFromUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            return CompletableFuture.completedFuture(null);
        }
        return ClientIoExecutor.supplyAsync(() -> {
            HttpURLConnection connection = null;
            try {
                String urlString = MOJANG_API_BASE + "/users/profiles/minecraft/" + username;
                URL url = new URL(urlString);
                connection = openConnection(url, 5000);

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    JsonObject json = JsonParser.parseString(
                            readUtf8Body(connection, MAX_JSON_BYTES)).getAsJsonObject();
                    String uuidString = json.get("id").getAsString();

                    // Add dashes to UUID string
                    String formattedUuid = uuidString.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                        "$1-$2-$3-$4-$5"
                    );

                    return UUID.fromString(formattedUuid);
                }
            } catch (Exception e) {
                QuickSkinInfo.LOGGER.debug("Unable to resolve Mojang profile for {}", username, e);
            } finally {
                if (connection != null) connection.disconnect();
            }
            return null;
        });
    }

    /**
     * Fetch a player's skin texture data from their UUID
     * @param uuid The player's UUID
     * @return CompletableFuture containing the skin texture data
     */
    public CompletableFuture<SkinTextureData> getSkinTextureData(UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.completedFuture(null);
        }
        return ClientIoExecutor.supplyAsync(() -> {
            HttpURLConnection connection = null;
            try {
                String uuidString = uuid.toString().replace("-", "");
                String urlString = SESSION_SERVER_BASE + "/session/minecraft/profile/" + uuidString;
                URL url = new URL(urlString);
                connection = openConnection(url, 5000);

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    JsonObject json = JsonParser.parseString(
                            readUtf8Body(connection, MAX_JSON_BYTES)).getAsJsonObject();
                    JsonObject properties = json.getAsJsonArray("properties").get(0).getAsJsonObject();
                    String texturesBase64 = properties.get("value").getAsString();
                    if (texturesBase64.length() > MAX_JSON_BYTES) {
                        return null;
                    }

                    // Decode the base64 textures
                    byte[] decodedTextures = Base64.getDecoder().decode(texturesBase64);
                    if (decodedTextures.length > MAX_JSON_BYTES) return null;
                    String texturesJson = new String(decodedTextures, StandardCharsets.UTF_8);
                    JsonObject texturesObject = JsonParser.parseString(texturesJson).getAsJsonObject();
                    JsonObject textures = texturesObject.getAsJsonObject("textures");

                    if (textures.has("SKIN")) {
                        JsonObject skinObject = textures.getAsJsonObject("SKIN");
                        URL skinUrl = validateTextureUrl(skinObject.get("url").getAsString());

                        // Determine model type (slim/default)
                        String modelType = "default";
                        if (skinObject.has("metadata")) {
                            JsonObject metadata = skinObject.getAsJsonObject("metadata");
                            if (metadata.has("model") && metadata.get("model").getAsString().equals("slim")) {
                                modelType = "slim";
                            }
                        }

                        return new SkinTextureData(skinUrl.toString(), modelType);
                    }
                }
            } catch (Exception e) {
                QuickSkinInfo.LOGGER.debug("Unable to resolve Mojang skin texture for {}", uuid, e);
            } finally {
                if (connection != null) connection.disconnect();
            }
            return null;
        });
    }

    /**
     * Download a skin image from a URL
     * @param skinUrl The URL to download from
     * @return CompletableFuture containing the BufferedImage
     */
    public CompletableFuture<BufferedImage> downloadSkinImage(String skinUrl) {
        return ClientIoExecutor.supplyAsync(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = validateTextureUrl(skinUrl);
                connection = openConnection(url, 10000);

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    return SafeImageReader.readSkin(readBody(connection, MAX_SKIN_BYTES));
                }
            } catch (Exception e) {
                QuickSkinInfo.LOGGER.debug("Unable to download Mojang skin texture", e);
            } finally {
                if (connection != null) connection.disconnect();
            }
            return null;
        });
    }

    /**
     * Fetch and download a player's skin by username
     * Combines UUID lookup, texture data fetch, and image download
     * @param username The player's username
     * @return CompletableFuture containing the MojangSkinData
     */
    public CompletableFuture<MojangSkinData> fetchSkinByUsername(String username) {
        return getUuidFromUsername(username)
            .thenCompose(uuid -> {
                if (uuid == null) {
                    return CompletableFuture.completedFuture(null);
                }
                return getSkinTextureData(uuid)
                    .thenCompose(textureData -> {
                        if (textureData == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return downloadSkinImage(textureData.url)
                            .thenApply(image -> {
                                if (image == null) {
                                    return null;
                                }
                                return new MojangSkinData(username, uuid, image, textureData.modelType);
                            });
                    });
            });
    }

    private static HttpURLConnection openConnection(URL url, int timeoutMillis)
            throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Mojang request must use HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestProperty("Accept", "application/json,image/png");
        connection.setRequestProperty("User-Agent", "QuickSkin");
        return connection;
    }

    private static byte[] readBody(HttpURLConnection connection, int maxBytes)
            throws IOException {
        long advertisedLength = connection.getContentLengthLong();
        if (advertisedLength > maxBytes) {
            throw new IOException("Mojang response exceeds " + maxBytes + " bytes");
        }
        try (InputStream input = connection.getInputStream()) {
            byte[] body = input.readNBytes(maxBytes + 1);
            if (body.length > maxBytes) {
                throw new IOException("Mojang response grew beyond " + maxBytes + " bytes");
            }
            return body;
        }
    }

    private static String readUtf8Body(HttpURLConnection connection, int maxBytes)
            throws IOException {
        return new String(readBody(connection, maxBytes), StandardCharsets.UTF_8);
    }

    /** Accept historical HTTP payloads only for Mojang's exact texture host, then upgrade them. */
    private static URL validateTextureUrl(String value) throws Exception {
        if (value == null || value.length() > 512) {
            throw new IOException("Invalid Mojang texture URL");
        }
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || !"textures.minecraft.net".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getQuery() != null || uri.getFragment() != null
                || !TEXTURE_PATH.matcher(uri.getPath()).matches()) {
            throw new IOException("Untrusted Mojang texture URL");
        }
        return new URI("https", null, "textures.minecraft.net", -1,
                uri.getPath(), null, null).toURL();
    }

    /**
     * Container for skin texture data
     */
    public static class SkinTextureData {
        public final String url;
        public final String modelType;

        public SkinTextureData(String url, String modelType) {
            this.url = url;
            this.modelType = modelType;
        }
    }

    /**
     * Container for complete Mojang skin data
     */
    public static class MojangSkinData {
        public final String username;
        public final UUID uuid;
        public final BufferedImage image;
        public final String modelType;

        public MojangSkinData(String username, UUID uuid, BufferedImage image, String modelType) {
            this.username = username;
            this.uuid = uuid;
            this.image = image;
            this.modelType = modelType;
        }
    }
}
