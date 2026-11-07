package com.quickskin.mod.client.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quickskin.mod.common.util.BoundedFileReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * What this computer and one account's cloud library last agreed on, kept per account beside the
 * library: the cloud revision read last, the website session and every agreed entry. A file
 * that cannot be read starts from nothing, which only makes the next round merge both sides.
 */
public final class SyncState {
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 8192;

    public long rev;
    public String token;
    public long tokenExpiresAt;
    public Map<String, LibraryEntry> known = new HashMap<>();

    public static SyncState load(Path file) {
        SyncState state = new SyncState();
        if (!Files.isRegularFile(file)) return state;
        try {
            JsonElement parsed = JsonParser.parseString(BoundedFileReader.readUtf8(file, MAX_FILE_BYTES));
            if (!parsed.isJsonObject()) return state;
            JsonObject object = parsed.getAsJsonObject();
            state.rev = Math.max(0, longValue(object, "rev"));
            JsonElement token = object.get("token");
            if (token != null && token.isJsonPrimitive() && token.getAsJsonPrimitive().isString()
                    && token.getAsString().length() <= 4096) {
                state.token = token.getAsString();
                state.tokenExpiresAt = longValue(object, "tokenExpiresAt");
            }
            JsonElement known = object.get("known");
            if (known != null && known.isJsonArray()) {
                for (JsonElement element : known.getAsJsonArray()) {
                    if (state.known.size() >= MAX_ENTRIES) break;
                    LibraryEntry entry = SkinSyncApi.parseEntry(element);
                    if (entry != null) state.known.put(entry.key(), entry);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return new SyncState();
        }
        return state;
    }

    public void save(Path file) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("rev", rev);
        if (token != null) {
            object.addProperty("token", token);
            object.addProperty("tokenExpiresAt", tokenExpiresAt);
        }
        JsonArray entries = new JsonArray();
        for (LibraryEntry entry : known.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("kind", entry.kind());
            json.addProperty("id", entry.id());
            json.addProperty("rev", entry.rev());
            json.addProperty("deleted", entry.deleted());
            if (!entry.deleted()) {
                json.addProperty("name", entry.name());
                if (entry.model() != null) json.addProperty("model", entry.model());
                json.addProperty("favorite", entry.favorite());
            }
            entries.add(json);
        }
        object.add("known", entries);

        Path parent = file.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".cloud-sync-", ".tmp");
        try {
            Files.writeString(temporary, object.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long longValue(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 0;
        try {
            return value.getAsLong();
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }
}
