package com.quickskin.mod.client.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The cloud library's HTTP API ({@code /api/sync/v1} of the Quick Skin website) and Mojang's
 * session join, which proves to the website that the player owns the account. Every response is
 * untrusted: bodies are read up to a bound and every field is checked before use. The Minecraft
 * access token goes only to Mojang's session server, never to the website.
 */
public final class SkinSyncApi {
    public static final String DEFAULT_BASE_URL =
            "https://minecraft-skin-api.rubengalandiaz.workers.dev/api/sync/v1";
    public static final String DEFAULT_MOJANG_JOIN_URL =
            "https://sessionserver.mojang.com/session/minecraft/join";
    /** The largest file the cloud stores; a larger skin or cape stays only on this computer. */
    public static final int MAX_BLOB_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CHANGES_PER_REQUEST = 64;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final Pattern SERVER_ID = Pattern.compile("[0-9a-f]{40}");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http;
    private final String baseUrl;
    private final String mojangJoinUrl;
    private final String userAgent;

    public SkinSyncApi(String baseUrl, String mojangJoinUrl, String userAgent) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.baseUrl = stripSlash(baseUrl);
        this.mojangJoinUrl = mojangJoinUrl;
        this.userAgent = userAgent;
    }

    /** An HTTP answer the round cannot use; {@code status} is 0 for a transport failure. */
    public static final class ApiException extends IOException {
        private final int status;
        private final long retryAfterSeconds;

        public ApiException(int status, String message, long retryAfterSeconds) {
            super(message);
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int status() {
            return status;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }

        public boolean unauthorized() {
            return status == 401;
        }
    }

    public record Session(String token, long expiresAt) {
    }

    /**
     * Cloud changes since a revision. With {@code reset} the cloud could not give them (pruned
     * tombstones, an account deleted and created again) and {@code entries} is the whole library.
     */
    public record LibraryPage(long rev, boolean reset, List<LibraryEntry> entries) {
    }

    public record ChangeResult(long rev, List<String> applied, List<String> missingBlobs,
                               List<String> rejected) {
    }

    /**
     * Signs in with the Minecraft account: the website hands out a challenge, Mojang is told the
     * player joins it, and the website asks Mojang whether that happened.
     *
     * @throws ApiException with status 403 when Mojang refuses the account (offline or expired)
     */
    public Session signIn(UUID profile, String name, String accessToken) throws IOException, InterruptedException {
        String uuid = undashed(profile);
        JsonObject challengeBody = new JsonObject();
        challengeBody.addProperty("uuid", uuid);
        challengeBody.addProperty("name", name);
        JsonObject challenge = json(send(post(baseUrl + "/auth/challenge", null, challengeBody)));
        String serverId = string(challenge, "serverId");
        long issuedAt = number(challenge, "issuedAt");
        if (serverId == null || !SERVER_ID.matcher(serverId).matches() || issuedAt <= 0) {
            throw new ApiException(502, "invalid challenge", 0);
        }

        JsonObject join = new JsonObject();
        join.addProperty("accessToken", accessToken);
        join.addProperty("selectedProfile", uuid);
        join.addProperty("serverId", serverId);
        HttpResponse<InputStream> joined = http.send(HttpRequest.newBuilder(URI.create(mojangJoinUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(join.toString()))
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream ignored = joined.body()) {
            int status = joined.statusCode();
            if (status != 204 && status != 200) {
                throw new ApiException(status == 401 || status == 403 ? 403 : status,
                        "Mojang refused the session join", 0);
            }
        }

        JsonObject verifyBody = new JsonObject();
        verifyBody.addProperty("uuid", uuid);
        verifyBody.addProperty("name", name);
        verifyBody.addProperty("serverId", serverId);
        verifyBody.addProperty("issuedAt", issuedAt);
        JsonObject verified = json(send(post(baseUrl + "/auth/verify", null, verifyBody)));
        String token = string(verified, "token");
        long expiresAt = number(verified, "expiresAt");
        if (token == null || token.isEmpty() || token.length() > 4096 || expiresAt <= 0) {
            throw new ApiException(502, "invalid session", 0);
        }
        return new Session(token, expiresAt);
    }

    public LibraryPage library(String token, long since) throws IOException, InterruptedException {
        HttpRequest request = request(baseUrl + "/library?since=" + Math.max(0, since), token).GET().build();
        JsonObject page = json(send(request));
        long rev = number(page, "rev");
        JsonArray entries = page.has("entries") && page.get("entries").isJsonArray()
                ? page.getAsJsonArray("entries") : null;
        if (rev < 0 || entries == null || entries.size() > 100_000) {
            throw new ApiException(502, "invalid library", 0);
        }
        List<LibraryEntry> parsed = new ArrayList<>();
        for (JsonElement element : entries) {
            LibraryEntry entry = parseEntry(element);
            if (entry != null) parsed.add(entry);
        }
        return new LibraryPage(rev, bool(page, "reset"), List.copyOf(parsed));
    }

    public ChangeResult change(String token, List<LibraryEntry> changes) throws IOException, InterruptedException {
        if (changes.isEmpty() || changes.size() > MAX_CHANGES_PER_REQUEST) {
            throw new IllegalArgumentException("changes");
        }
        JsonArray array = new JsonArray();
        for (LibraryEntry change : changes) {
            JsonObject object = new JsonObject();
            object.addProperty("kind", change.kind());
            object.addProperty("id", change.id());
            object.addProperty("deleted", change.deleted());
            if (!change.deleted()) {
                object.addProperty("name", change.name());
                object.addProperty("favorite", change.favorite());
                if (change.model() != null) object.addProperty("model", change.model());
                else object.add("model", com.google.gson.JsonNull.INSTANCE);
            }
            object.add("cape", com.google.gson.JsonNull.INSTANCE);
            object.add("elytra", com.google.gson.JsonNull.INSTANCE);
            array.add(object);
        }
        JsonObject body = new JsonObject();
        body.add("changes", array);
        JsonObject result = json(send(post(baseUrl + "/library", token, body)));
        return new ChangeResult(number(result, "rev"), ids(result, "applied"),
                ids(result, "missingBlobs"), rejectedIds(result));
    }

    public void putBlob(String token, String kind, String id, byte[] png) throws IOException, InterruptedException {
        if (png.length > MAX_BLOB_BYTES) throw new IllegalArgumentException("blob too large");
        HttpRequest request = request(blobUrl(kind, id), token)
                .header("Content-Type", "image/png")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(png))
                .build();
        close(send(request));
    }

    public byte[] getBlob(String token, String kind, String id) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = send(request(blobUrl(kind, id), token).GET().build());
        try (InputStream body = response.body()) {
            return readBounded(body, MAX_BLOB_BYTES);
        }
    }

    private String blobUrl(String kind, String id) {
        LibraryEntry.local(kind, id, "x", null, false); // validates kind and id
        return baseUrl + "/blob/" + kind + "/" + id;
    }

    private HttpRequest.Builder request(String url, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", userAgent);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private HttpRequest post(String url, String token, JsonObject body) {
        return request(url, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException transport) {
            throw new ApiException(0, "network: " + transport.getClass().getSimpleName(), 0);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) return response;
        close(response);
        long retryAfter = response.headers().firstValue("Retry-After")
                .map(SkinSyncApi::parseSeconds).orElse(0L);
        throw new ApiException(status, "HTTP " + status, retryAfter);
    }

    private static void close(HttpResponse<InputStream> response) {
        try (InputStream ignored = response.body()) {
            // drained by close
        } catch (IOException ignored) {
            // nothing to release beyond the stream
        }
    }

    private static JsonObject json(HttpResponse<InputStream> response) throws IOException {
        byte[] bytes;
        try (InputStream body = response.body()) {
            bytes = readBounded(body, MAX_JSON_BYTES);
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new ApiException(502, "not a JSON object", 0);
            return parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new ApiException(502, "malformed JSON", 0);
        }
    }

    static byte[] readBounded(InputStream in, int limit) throws IOException {
        byte[] bytes = in.readNBytes(limit + 1);
        if (bytes.length > limit) throw new ApiException(502, "response too large", 0);
        return bytes;
    }

    static LibraryEntry parseEntry(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        try {
            String kind = string(object, "kind");
            String id = string(object, "id");
            long rev = number(object, "rev");
            boolean deleted = bool(object, "deleted");
            if (deleted) return LibraryEntry.tombstone(kind, id, rev);
            return new LibraryEntry(kind, id, string(object, "name"), string(object, "model"),
                    bool(object, "favorite"), false, rev);
        } catch (IllegalArgumentException invalid) {
            return null; // an entry this version cannot use is left to the cloud
        }
    }

    private static List<String> ids(JsonObject object, String field) {
        List<String> ids = new ArrayList<>();
        if (object.has(field) && object.get(field).isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray(field)) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    ids.add(element.getAsString());
                }
            }
        }
        return List.copyOf(ids);
    }

    private static List<String> rejectedIds(JsonObject object) {
        List<String> ids = new ArrayList<>();
        if (object.has("rejected") && object.get("rejected").isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray("rejected")) {
                if (element.isJsonObject()) {
                    String id = string(element.getAsJsonObject(), "id");
                    if (id != null) ids.add(id);
                }
            }
        }
        return List.copyOf(ids);
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static long number(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        try {
            return value.getAsLong();
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static boolean bool(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) return false;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        // D1 hands booleans back as 0/1
        return value.getAsJsonPrimitive().isNumber() && value.getAsLong() == 1;
    }

    private static long parseSeconds(String value) {
        try {
            return Math.max(0, Math.min(3600, Long.parseLong(value.trim())));
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
