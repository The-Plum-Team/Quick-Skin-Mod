package com.quickskin.mod.server.vanilla;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quickskin.mod.platform.QuickSkinInfo;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The text that tells a player without Quick Skin that the server uses it.
 *
 * <p>That player's game has none of Quick Skin's translations, so the server picks the text in the
 * language the client reported from the language files bundled in its own jar, and sends it
 * as literal text, beside the mascot of {@link MissingModNoticeLayout}. A language without the
 * text, or one the server cannot read, falls back to English.</p>
 */
public final class MissingModNoticeText {
    public static final String KEY = "quickskin.server.missing_mod_notice";
    static final String DEFAULT_LANGUAGE = "en_us";
    static final String BUILT_IN_ENGLISH = "This server uses Quick Skin for custom skins and capes."
            + " Install the mod to see them and to wear your own.";
    private static final int MAX_LANGUAGE_FILE_BYTES = 512 * 1024;
    private static final int MAX_TEXT_LENGTH = 512;
    private static final int MAX_CACHED_LANGUAGES = 64;
    /** Minecraft language codes such as {@code en_us}, {@code lzh} or {@code es_mx}. */
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2,3}(_[a-z0-9]{2,4})?");

    private final Function<String, InputStream> resources;
    private final Map<String, String> texts = new HashMap<>();

    /** @param resources opens a resource of the jar by path, or returns {@code null} when absent */
    MissingModNoticeText(Function<String, InputStream> resources) {
        this.resources = resources;
    }

    /** Reads the language files bundled with Quick Skin. */
    public static MissingModNoticeText bundled() {
        return new MissingModNoticeText(path -> MissingModNoticeText.class.getResourceAsStream("/" + path));
    }

    /** The message in this Minecraft language, or in English. */
    public synchronized String text(@Nullable String languageCode) {
        String language = languageCode == null ? DEFAULT_LANGUAGE : languageCode.toLowerCase(Locale.ROOT);
        if (!LANGUAGE_CODE.matcher(language).matches()) language = DEFAULT_LANGUAGE;
        String cached = texts.get(language);
        if (cached != null) return cached;
        String text = read(language);
        if (text == null && !language.equals(DEFAULT_LANGUAGE)) text = text(DEFAULT_LANGUAGE);
        if (text == null) text = BUILT_IN_ENGLISH;
        if (texts.size() < MAX_CACHED_LANGUAGES) texts.put(language, text);
        return text;
    }

    @Nullable
    private String read(String language) {
        String path = "assets/quickskin/lang/" + language + ".json";
        try (InputStream input = resources.apply(path)) {
            if (input == null) return null;
            byte[] bytes = input.readNBytes(MAX_LANGUAGE_FILE_BYTES + 1);
            if (bytes.length > MAX_LANGUAGE_FILE_BYTES) return null;
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return null;
            JsonObject object = root.getAsJsonObject();
            JsonElement value = object.get(KEY);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                return null;
            }
            String text = value.getAsString().trim();
            return text.isEmpty() || text.length() > MAX_TEXT_LENGTH ? null : text;
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.debug("Unable to read the Quick Skin notice text for {}", language, e);
            return null;
        }
    }

    /** The configured link when it is an absolute http(s) address with a host, else {@code null}. */
    @Nullable
    public static String validLink(@Nullable String link) {
        if (link == null) return null;
        String trimmed = link.trim();
        if (trimmed.isEmpty()) return null;
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            return scheme.equals("http") || scheme.equals("https") ? trimmed : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Floodgate (Geyser) gives Bedrock players a UUID whose upper half is zero. They cannot install
     * Java mods, so the message would only be noise for them.
     */
    public static boolean isBedrockPlayer(UUID playerId) {
        return playerId != null && playerId.getMostSignificantBits() == 0L;
    }
}
