package com.quickskin.mod.server.vanilla;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The message reaches a player without Quick Skin in their own language, or in English. */
class MissingModNoticeTextTest {
    private final Map<String, String> files = new HashMap<>();
    private final List<String> opened = new ArrayList<>();
    private final MissingModNoticeText text = new MissingModNoticeText(path -> {
        opened.add(path);
        String content = files.get(path);
        return content == null ? null : stream(content);
    });

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private void language(String code, String message) {
        files.put("assets/quickskin/lang/" + code + ".json",
                "{\"other.key\": \"x\", \"" + MissingModNoticeText.KEY + "\": \"" + message + "\"}");
    }

    @Test
    void thePlayerReadsItInTheirOwnLanguage() {
        language("en_us", "English text");
        language("es_es", "Texto en español");

        assertEquals("Texto en español", text.text("es_es"));
        assertEquals("Texto en español", text.text("ES_ES"));
        assertEquals("English text", text.text("en_us"));
    }

    @Test
    void anUnknownOrMissingLanguageFallsBackToEnglish() {
        language("en_us", "English text");
        files.put("assets/quickskin/lang/fr_fr.json", "{\"other.key\": \"x\"}");

        assertEquals("English text", text.text("fr_fr"));
        assertEquals("English text", text.text("xx_yy"));
        assertEquals("English text", text.text(null));
    }

    @Test
    void anOddLanguageCodeNeverReachesTheResourcePath() {
        language("en_us", "English text");

        assertEquals("English text", text.text("../../secret"));
        assertEquals("English text", text.text("es_es/../../x"));
        assertTrue(opened.stream().noneMatch(path -> path.contains("..")), opened.toString());
    }

    @Test
    void withoutAnyLanguageFileTheBuiltInEnglishIsUsed() {
        assertEquals(MissingModNoticeText.BUILT_IN_ENGLISH, text.text("de_de"));
    }

    @Test
    void aBrokenOrOversizedFileFallsBack() {
        language("en_us", "English text");
        files.put("assets/quickskin/lang/de_de.json", "not json");
        files.put("assets/quickskin/lang/it_it.json",
                "{\"" + MissingModNoticeText.KEY + "\": \"" + "x".repeat(600 * 1024) + "\"}");
        files.put("assets/quickskin/lang/nl_nl.json", "{\"" + MissingModNoticeText.KEY + "\": 3}");

        assertEquals("English text", text.text("de_de"));
        assertEquals("English text", text.text("it_it"));
        assertEquals("English text", text.text("nl_nl"));
    }

    @Test
    void eachLanguageIsReadOnce() {
        language("en_us", "English text");
        text.text("en_us");
        text.text("en_us");

        assertEquals(1, opened.size());
    }

    @Test
    void onlyAbsoluteWebLinksAreSent() {
        assertEquals("https://modrinth.com/mod/quick-skin",
                MissingModNoticeText.validLink(" https://modrinth.com/mod/quick-skin "));
        assertEquals("http://example.com", MissingModNoticeText.validLink("http://example.com"));
        assertNull(MissingModNoticeText.validLink(""));
        assertNull(MissingModNoticeText.validLink(null));
        assertNull(MissingModNoticeText.validLink("modrinth.com/mod/quick-skin"));
        assertNull(MissingModNoticeText.validLink("file:///etc/passwd"));
        assertNull(MissingModNoticeText.validLink("javascript:alert(1)"));
        assertNull(MissingModNoticeText.validLink("https://exa mple.com"));
    }

    @Test
    void bedrockPlayersFromFloodgateAreRecognised() {
        assertTrue(MissingModNoticeText.isBedrockPlayer(new UUID(0L, 2535412345678901L)));
        assertFalse(MissingModNoticeText.isBedrockPlayer(UUID.nameUUIDFromBytes("OfflinePlayer:Alice".getBytes(StandardCharsets.UTF_8))));
        assertFalse(MissingModNoticeText.isBedrockPlayer(UUID.randomUUID()));
        assertFalse(MissingModNoticeText.isBedrockPlayer(null));
    }
}
