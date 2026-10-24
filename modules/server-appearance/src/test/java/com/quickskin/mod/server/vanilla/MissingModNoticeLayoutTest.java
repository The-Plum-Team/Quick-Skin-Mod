package com.quickskin.mod.server.vanilla;

import com.quickskin.mod.server.vanilla.MissingModNoticeLayout.Kind;
import com.quickskin.mod.server.vanilla.MissingModNoticeLayout.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mascot stands left of the message, and every line fits a chat of the default width. */
class MissingModNoticeLayoutTest {
    private static final int DEFAULT_CHAT_WIDTH = 320;
    private static final String LINK = "https://modrinth.com/mod/quick-skin";

    /** The width a line takes in the chat, by the same font widths the server breaks it with. */
    private static int width(List<Segment> line) {
        int width = 0;
        for (Segment segment : line) {
            width += switch (segment.kind()) {
                case BLANK, PIXEL -> 9 * segment.cells();
                case TITLE -> MissingModNoticeLayout.width(segment.text()) + segment.text().length();
                default -> MissingModNoticeLayout.width(segment.text());
            };
        }
        return width;
    }

    private static String bodyText(List<List<Segment>> lines, Kind kind) {
        StringBuilder text = new StringBuilder();
        for (List<Segment> line : lines) {
            for (Segment segment : line) {
                if (segment.kind() == kind && !segment.text().equals(MissingModNoticeLayout.GAP)) {
                    if (text.length() > 0) text.append(' ');
                    text.append(segment.text());
                }
            }
        }
        return text.toString();
    }

    @Test
    void theMascotIsARectangleOfKnownColours() {
        for (String row : MissingModNoticeLayout.ART) {
            assertEquals(MissingModNoticeLayout.ART_WIDTH, row.length(), row);
            for (char pixel : row.toCharArray()) {
                if (pixel != '.') assertDoesNotThrow(() -> MissingModNoticeLayout.colour(pixel));
            }
        }
    }

    @Test
    void theEnglishMessageSitsBesideTheMascotWithinTheDefaultChat() {
        List<List<Segment>> lines = MissingModNoticeLayout.lines(MissingModNoticeText.BUILT_IN_ENGLISH, LINK);

        assertEquals(MissingModNoticeLayout.ART.length, lines.size());
        for (List<Segment> line : lines) assertTrue(width(line) <= DEFAULT_CHAT_WIDTH, line.toString());
        assertEquals(MissingModNoticeText.BUILT_IN_ENGLISH, bodyText(lines, Kind.TEXT));
        assertEquals("modrinth.com/mod/quick-skin", bodyText(lines, Kind.LINK));
        assertEquals(MissingModNoticeLayout.TITLE, bodyText(lines, Kind.TITLE));
        // Title, three lines of text and the link, centred on the mascot's ten rows.
        assertEquals(Kind.TITLE, lines.get(2).get(lines.get(2).size() - 1).kind());
        assertEquals(Kind.LINK, lines.get(6).get(lines.get(6).size() - 1).kind());
    }

    @Test
    void withoutALinkNoLinkLineIsSent() {
        List<List<Segment>> lines = MissingModNoticeLayout.lines("Short text.", null);

        assertEquals("", bodyText(lines, Kind.LINK));
        assertEquals("Short text.", bodyText(lines, Kind.TEXT));
    }

    @Test
    void aLongMessageContinuesBelowTheMascotInItsColumn() {
        String text = "word ".repeat(120).strip();
        List<List<Segment>> lines = MissingModNoticeLayout.lines(text, LINK);

        assertTrue(lines.size() > MissingModNoticeLayout.ART.length);
        assertEquals(text, bodyText(lines, Kind.TEXT));
        List<Segment> below = lines.get(MissingModNoticeLayout.ART.length);
        assertEquals(Kind.BLANK, below.get(0).kind());
        assertEquals(MissingModNoticeLayout.ART_WIDTH, below.get(0).cells());
        for (List<Segment> line : lines) assertTrue(width(line) <= DEFAULT_CHAT_WIDTH, line.toString());
    }

    @Test
    void textWithoutSpacesBreaksBetweenCharacters() {
        String chinese = "此伺服器使用 Quick Skin 來顯示自訂外觀與披風。安裝此模組即可看見它們並穿上你自己的外觀。";
        List<String> lines = MissingModNoticeLayout.wrap(chinese, MissingModNoticeLayout.TEXT_WIDTH);

        assertTrue(lines.size() > 1);
        for (String line : lines) {
            assertTrue(MissingModNoticeLayout.width(line) <= MissingModNoticeLayout.TEXT_WIDTH, line);
        }
        assertEquals(chinese.replace(" ", ""), String.join("", lines).replace(" ", ""));
    }

    @Test
    void wordsStayWholeAndSpacesCollapse() {
        List<String> lines = MissingModNoticeLayout.wrap("  one   two\nthree  ", 40);

        assertEquals(List.of("one two", "three"), lines);
    }

    @Test
    void theLinkShowsWithoutItsSchemeOrOnlyItsHostWhenTooLong() {
        assertEquals("modrinth.com/mod/quick-skin", MissingModNoticeLayout.linkLabel(LINK + "/"));
        assertEquals("example.com", MissingModNoticeLayout.linkLabel(
                "https://example.com/" + "a-very-long-path/".repeat(10)));
    }
}
