package com.quickskin.mod.client.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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

    public enum Stage { LOOKUP, PROFILE, DOWNLOAD }

    public enum Reason {
        INVALID_USERNAME, NOT_FOUND, NO_CUSTOM_SKIN, HTTP, NETWORK, INVALID_RESPONSE, RESPONSE_TOO_LARGE
    }

    /** Bounded diagnostic categories; response bodies and exception text are never user messages. */
    public static final class ImportFailure extends RuntimeException {
        private final Stage stage;
        private final Reason reason;
        private final Integer httpStatus;

        private ImportFailure(Stage stage, Reason reason, Integer httpStatus, Throwable cause) {
            super("Mojang skin import: " + stage + "/" + reason
                    + (httpStatus == null ? "" : " (HTTP " + httpStatus + ")"), cause);
            this.stage = stage;
            this.reason = reason;
            this.httpStatus = httpStatus;
        }

        public Stage stage() { return stage; }
        public Reason reason() { return reason; }
        public Integer httpStatus() { return httpStatus; }
    }

    public static ImportFailure findFailure(Throwable throwable) {
        while ((throwable instanceof CompletionException || throwable instanceof ExecutionException)
                && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable instanceof ImportFailure failure ? failure : null;
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    private final ConnectionFactory connections;

    private MojangApiService() {
        this(url -> (HttpURLConnection) url.openConnection());
    }

    MojangApiService(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    public static MojangApiService getInstance() {
        return INSTANCE;
    }

    public static void init() {
        getInstance();
    }

    /**
     * Fetch a player's UUID from their username
     * @param username The player's username
     * @return the UUID, or an exceptional future carrying an ImportFailure
     */
    public CompletableFuture<UUID> getUuidFromUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            return CompletableFuture.failedFuture(new ImportFailure(Stage.LOOKUP, Reason.INVALID_USERNAME, null, null));
        }
        return ClientIoExecutor.supplyAsync(() -> {
            byte[] body = readResponse(MOJANG_API_BASE + "/users/profiles/minecraft/" + username,
                    Stage.LOOKUP, 5000, MAX_JSON_BYTES);
            try {
                String id = requiredString(parseObject(body), "id");
                if (!id.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("Invalid UUID");
                return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
                        + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20));
            } catch (RuntimeException malformed) {
                throw new ImportFailure(Stage.LOOKUP, Reason.INVALID_RESPONSE, 200, malformed);
            }
        });
    }

    /**
     * Fetch a player's skin texture data from their UUID
     * @param uuid The player's UUID
     * @return CompletableFuture containing the skin texture data
     */
    public CompletableFuture<SkinTextureData> getSkinTextureData(UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.failedFuture(new ImportFailure(Stage.PROFILE, Reason.INVALID_RESPONSE, null, null));
        }
        return ClientIoExecutor.supplyAsync(() -> {
            byte[] body = readResponse(SESSION_SERVER_BASE + "/session/minecraft/profile/"
                    + uuid.toString().replace("-", ""), Stage.PROFILE, 5000, MAX_JSON_BYTES);
            try {
                JsonElement properties = parseObject(body).get("properties");
                if (properties == null || !properties.isJsonArray()) {
                    throw new IllegalArgumentException("Missing profile properties");
                }
                String encoded = null;
                for (JsonElement entry : properties.getAsJsonArray()) {
                    JsonObject property = entry.getAsJsonObject();
                    if ("textures".equals(requiredString(property, "name"))) {
                        encoded = requiredString(property, "value");
                        break;
                    }
                }
                if (encoded == null) throw new IllegalArgumentException("Missing textures property");
                if (encoded.length() > MAX_JSON_BYTES) {
                    throw new ImportFailure(Stage.PROFILE, Reason.RESPONSE_TOO_LARGE, 200, null);
                }
                byte[] decoded = Base64.getDecoder().decode(encoded);
                if (decoded.length > MAX_JSON_BYTES) {
                    throw new ImportFailure(Stage.PROFILE, Reason.RESPONSE_TOO_LARGE, 200, null);
                }
                JsonObject textures = requiredObject(parseObject(decoded), "textures");
                if (!textures.has("SKIN")) {
                    throw new ImportFailure(Stage.PROFILE, Reason.NO_CUSTOM_SKIN, 200, null);
                }
                JsonObject skin = requiredObject(textures, "SKIN");
                URL skinUrl = validateTextureUrl(requiredString(skin, "url"));
                String model = "default";
                if (skin.has("metadata")) {
                    JsonObject metadata = requiredObject(skin, "metadata");
                    if (metadata.has("model") && "slim".equals(requiredString(metadata, "model"))) model = "slim";
                }
                return new SkinTextureData(skinUrl.toString(), model);
            } catch (ImportFailure failure) {
                throw failure;
            } catch (Exception malformed) {
                throw new ImportFailure(Stage.PROFILE, Reason.INVALID_RESPONSE, 200, malformed);
            }
        });
    }

    /**
     * Download a skin image from a URL
     * @param skinUrl The URL to download from
     * @return CompletableFuture containing the BufferedImage
     */
    public CompletableFuture<BufferedImage> downloadSkinImage(String skinUrl) {
        return ClientIoExecutor.supplyAsync(() -> {
            URL url;
            try {
                url = validateTextureUrl(skinUrl);
            } catch (Exception malformed) {
                throw new ImportFailure(Stage.DOWNLOAD, Reason.INVALID_RESPONSE, null, malformed);
            }
            byte[] body = readResponse(url.toString(), Stage.DOWNLOAD, 10000, MAX_SKIN_BYTES);
            try {
                return SafeImageReader.readSkin(body);
            } catch (IOException | RuntimeException malformed) {
                throw new ImportFailure(Stage.DOWNLOAD, Reason.INVALID_RESPONSE, 200, malformed);
            }
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
                .thenCompose(uuid -> getSkinTextureData(uuid)
                        .thenCompose(texture -> downloadSkinImage(texture.url)
                                .thenApply(image -> new MojangSkinData(username, uuid, image, texture.modelType))));
    }

    private byte[] readResponse(String url, Stage stage, int timeout, int maximum) {
        HttpURLConnection connection = null;
        Integer status = null;
        try {
            connection = openConnection(new URL(url), timeout);
            status = connection.getResponseCode();
            if (status != 200) {
                // The name lookup answers a missing player with 404 (an empty 204 before Mojang
                // changed it). Any other status is a failed request rather than a missing player.
                Reason reason = stage == Stage.LOOKUP && (status == 404 || status == 204)
                        ? Reason.NOT_FOUND : Reason.HTTP;
                throw new ImportFailure(stage, reason, status, null);
            }
            return readBody(connection, maximum, stage);
        } catch (IOException network) {
            throw new ImportFailure(stage, Reason.NETWORK, status, network);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JsonObject parseObject(byte[] body) {
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing or invalid string: " + name);
        }
        return value.getAsString();
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Missing or invalid object: " + name);
        return value.getAsJsonObject();
    }

    private HttpURLConnection openConnection(URL url, int timeoutMillis)
            throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Mojang request must use HTTPS");
        }
        HttpURLConnection connection = connections.open(url);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestProperty("Accept", "application/json,image/png");
        connection.setRequestProperty("User-Agent", "QuickSkin");
        return connection;
    }

    private static byte[] readBody(HttpURLConnection connection, int maxBytes, Stage stage)
            throws IOException {
        long advertisedLength = connection.getContentLengthLong();
        if (advertisedLength > maxBytes) {
            throw new ImportFailure(stage, Reason.RESPONSE_TOO_LARGE, 200, null);
        }
        try (InputStream input = connection.getInputStream()) {
            byte[] body = input.readNBytes(maxBytes + 1);
            if (body.length > maxBytes) {
                throw new ImportFailure(stage, Reason.RESPONSE_TOO_LARGE, 200, null);
            }
            return body;
        }
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
