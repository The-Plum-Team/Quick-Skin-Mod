package com.quickskin.mod.server.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

/** Strict, single-document JSON reading for untrusted session-server data. */
final class StrictJson {
    private static final Gson GSON = new Gson();

    private StrictJson() {
    }

    static JsonObject parseObject(String json) {
        if (json == null) throw new IllegalArgumentException("The JSON document is missing");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement element = GSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("The JSON document has trailing content");
            }
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("The JSON document is not an object");
            }
            return element.getAsJsonObject();
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException("The JSON document is malformed", error);
        }
    }

    /** The string value of {@code key}, {@code null} when absent; any other type is rejected. */
    static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("The JSON field " + key + " is not a string");
        }
        return element.getAsString();
    }
}
