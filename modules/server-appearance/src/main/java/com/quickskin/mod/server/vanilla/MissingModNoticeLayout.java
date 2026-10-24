package com.quickskin.mod.server.vanilla;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out the message for players without Quick Skin as chat lines: the Plum mascot drawn with
 * coloured block characters on the left, and the title, the text and the link on its right.
 *
 * <p>That player's game has no Quick Skin resource, so every pixel of the mascot is a vanilla
 * {@code █}, one chat line per row. The server knows no font either: it breaks the text itself,
 * by the widths of the vanilla default font, so the lines stay beside the mascot in a chat of the
 * default width. A chat set narrower wraps some lines again; the message still reads.</p>
 */
public final class MissingModNoticeLayout {
    public static final String TITLE = "Quick Skin";
    public static final int TITLE_RGB = 0xD873AD;
    /** The block character; 8 pixels wide with a 9-pixel advance, the chat line's height. */
    static final String PIXEL = "█";
    /** The widest text beside the mascot: it and the gap fill the default 320-pixel chat. */
    static final int TEXT_WIDTH = 200;
    static final String GAP = "  ";

    /** The mascot of The Plum Team, holding a grass block; {@code .} is an empty pixel. */
    static final String[] ART = {
            "......SLL..",
            ".....S.....",
            "...PPPPP...",
            "..hPPPPPPp.",
            ".hPKPPPKPPp",
            ".hPPPKPPPPp",
            ".GGGgGPPPPp",
            "PDdDDdPPPp.",
            ".DDdDD.Pp..",
            "..P....p...",
    };
    static final int ART_WIDTH = ART[0].length();

    /**
     * Advances of the vanilla default font from {@code ' '} to {@code '~'}, in pixels; the same
     * in every supported version.
     */
    private static final String ASCII_ADVANCES =
            "42466662444626266666666666225656766666666466666666666666666464663666665662653666666646666664247";

    public enum Kind { PIXEL, BLANK, TITLE, TEXT, LINK }

    /**
     * One run of a chat line. A {@link Kind#BLANK} is {@code cells} empty pixels; a
     * {@link Kind#PIXEL} is {@code text} drawn in {@code rgb}.
     */
    public record Segment(Kind kind, String text, int rgb, int cells) {
    }

    private MissingModNoticeLayout() {
    }

    /** The chat lines of the message, from the top. */
    public static List<List<Segment>> lines(String text, @Nullable String link) {
        List<List<Segment>> body = new ArrayList<>();
        body.add(List.of(new Segment(Kind.TITLE, TITLE, TITLE_RGB, 0)));
        for (String line : wrap(text, TEXT_WIDTH)) {
            body.add(List.of(new Segment(Kind.TEXT, line, 0xFFFFFF, 0)));
        }
        if (link != null) body.add(List.of(new Segment(Kind.LINK, linkLabel(link), 0x55FFFF, 0)));

        int start = Math.max(0, (ART.length - body.size()) / 2);
        int rows = Math.max(ART.length, start + body.size());
        List<List<Segment>> lines = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            List<Segment> line = new ArrayList<>();
            int bodyRow = row - start;
            boolean hasBody = bodyRow >= 0 && bodyRow < body.size();
            if (row < ART.length) {
                addArtRow(line, ART[row]);
            } else if (hasBody) {
                line.add(new Segment(Kind.BLANK, "", 0, ART_WIDTH));
            }
            if (hasBody) {
                line.add(new Segment(Kind.TEXT, GAP, 0xFFFFFF, 0));
                line.addAll(body.get(bodyRow));
            }
            lines.add(line);
        }
        return lines;
    }

    private static void addArtRow(List<Segment> line, String row) {
        int index = 0;
        while (index < row.length()) {
            char pixel = row.charAt(index);
            int end = index + 1;
            while (end < row.length() && row.charAt(end) == pixel) end++;
            int cells = end - index;
            if (pixel == '.') {
                line.add(new Segment(Kind.BLANK, "", 0, cells));
            } else {
                line.add(new Segment(Kind.PIXEL, PIXEL.repeat(cells), colour(pixel), cells));
            }
            index = end;
        }
    }

    static int colour(char pixel) {
        return switch (pixel) {
            case 'S' -> 0x5B3A25; // stem
            case 'L' -> 0x78C24A; // leaf
            case 'P' -> 0xB8468A; // plum
            case 'p' -> 0x8F3068; // plum shade
            case 'h' -> 0xD873AD; // plum light
            case 'K' -> 0x2E1422; // eyes and mouth
            case 'G' -> 0x6EC23C; // grass
            case 'g' -> 0x4E9A2C; // grass shade
            case 'D' -> 0x8D5B34; // dirt
            case 'd' -> 0x6A4224; // dirt shade
            default -> throw new IllegalArgumentException("No colour for pixel " + pixel);
        };
    }

    /** The link as shown: without its scheme, or only its host when the whole is too wide. */
    static String linkLabel(String link) {
        String label = link.replaceFirst("(?i)^https?://", "");
        if (label.endsWith("/")) label = label.substring(0, label.length() - 1);
        if (width(label) <= TEXT_WIDTH) return label;
        int slash = label.indexOf('/');
        return slash > 0 ? label.substring(0, slash) : label;
    }

    /**
     * Breaks the text into lines no wider than {@code maxWidth}, at spaces where it can and
     * between any two characters otherwise (Chinese and Japanese text has no spaces).
     */
    static List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String normalized = text.strip().replaceAll("\\s+", " ");
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        int index = 0;
        while (index < normalized.length()) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            int advance = advance(codePoint);
            if (lineWidth + advance > maxWidth && line.length() > 0) {
                int space = line.lastIndexOf(" ");
                String rest;
                if (codePoint == ' ') {
                    rest = null;
                } else if (space > 0) {
                    rest = line.substring(space + 1);
                    line.setLength(space);
                } else {
                    rest = "";
                }
                lines.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
                if (rest == null) continue;
                line.append(rest);
                lineWidth = width(rest);
            }
            if (line.length() == 0 && codePoint == ' ') continue;
            line.appendCodePoint(codePoint);
            lineWidth += advance;
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    static int width(String text) {
        int width = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            width += advance(codePoint);
            index += Character.charCount(codePoint);
        }
        return width;
    }

    /**
     * A character's advance in the default font. Characters outside ASCII are estimated on the
     * wide side: full-width scripts take 9 pixels, everything else 7.
     */
    static int advance(int codePoint) {
        if (codePoint >= ' ' && codePoint <= '~') return ASCII_ADVANCES.charAt(codePoint - ' ') - '0';
        if (codePoint >= 0x2E80) return 9;
        return 7;
    }
}
