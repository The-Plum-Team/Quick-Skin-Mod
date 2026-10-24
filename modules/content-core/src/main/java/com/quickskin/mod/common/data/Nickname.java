package com.quickskin.mod.common.data;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The rules every nickname follows, on the client that types it, on the wire and on the server
 * that shows it. A nickname is a purely cosmetic name a player picks for roleplay: it replaces
 * the name in chat and on name tags, never the account name commands and selectors use.
 *
 * <p>A nickname is composed (NFC), trimmed, its runs of white space are single spaces, and it is
 * at most {@link #MAX_LENGTH} UTF-16 units long. It holds letters of any script, digits, combining
 * marks (at most two in a row, after a letter or digit), single spaces and {@code _ - . , '} or
 * {@code ’}, with at least one letter or digit. That leaves out every formatting code ({@code §}),
 * control, invisible and bidirectional character, emoji and icon glyph, the Hangul fillers and
 * variation selectors that draw nothing, and the brackets and angle brackets a nickname could use
 * to fake the {@code (Account)} the server adds or a chat prefix.</p>
 */
public final class Nickname {
    /** Longest nickname, in UTF-16 units: the length an {@code EditBox} and a 1.20.1 string count. */
    public static final int MAX_LENGTH = 32;
    private static final int MAX_COMBINING_MARKS_IN_A_ROW = 2;

    /** Why a typed nickname is refused. */
    public enum Problem {
        /** It is a valid nickname, or blank (no nickname). */
        NONE,
        /** It holds a character no nickname may hold. */
        CHARACTER,
        /** It has no letter or digit. */
        NO_LETTER_OR_DIGIT,
        /** An accent does not follow a letter or digit, or more than two follow one. */
        MISPLACED_MARK,
        /** It is longer than {@link #MAX_LENGTH} once composed and trimmed. */
        TOO_LONG
    }

    private Nickname() {
    }

    /**
     * The canonical form of a typed nickname: {@code ""} for a blank one, which means no
     * nickname, or {@code null} when it breaks a rule.
     */
    public static String normalize(String raw) {
        StringBuilder canonical = new StringBuilder();
        return check(raw, canonical) == Problem.NONE ? canonical.toString() : null;
    }

    /** Why the typed nickname is refused, or {@link Problem#NONE}. */
    public static Problem check(String raw) {
        return check(raw, new StringBuilder());
    }

    private static Problem check(String raw, StringBuilder canonical) {
        if (raw == null) {
            return Problem.NONE;
        }
        String composed = Normalizer.normalize(raw, Normalizer.Form.NFC);
        boolean pendingSpace = false;
        boolean hasLetterOrDigit = false;
        int combiningMarks = 0;
        for (int i = 0; i < composed.length(); ) {
            int codePoint = composed.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isSpace(codePoint)) {
                pendingSpace = canonical.length() > 0;
                combiningMarks = 0;
                continue;
            }
            if (isInvisible(codePoint)) {
                return Problem.CHARACTER;
            }
            if (isCombiningMark(codePoint)) {
                // A mark belongs to the letter or digit right before it
                if (pendingSpace || combiningMarks == -1 || canonical.length() == 0
                        || ++combiningMarks > MAX_COMBINING_MARKS_IN_A_ROW) {
                    return Problem.MISPLACED_MARK;
                }
                canonical.appendCodePoint(codePoint);
                continue;
            }
            boolean letterOrDigit = Character.isLetter(codePoint) || Character.isDigit(codePoint);
            if (!letterOrDigit && !isAllowedPunctuation(codePoint)) {
                return Problem.CHARACTER;
            }
            if (pendingSpace) {
                canonical.append(' ');
                pendingSpace = false;
            }
            canonical.appendCodePoint(codePoint);
            hasLetterOrDigit |= letterOrDigit;
            combiningMarks = letterOrDigit ? 0 : -1;
        }
        if (canonical.length() == 0) {
            return Problem.NONE;
        }
        if (!hasLetterOrDigit) {
            return Problem.NO_LETTER_OR_DIGIT;
        }
        return canonical.length() > MAX_LENGTH ? Problem.TOO_LONG : Problem.NONE;
    }

    /**
     * Whether a typed character can be part of a nickname, so a text box can refuse the others
     * as they are typed or pasted. Where it may stand is checked by {@link #normalize}.
     */
    public static boolean isAllowedCharacter(int codePoint) {
        if (isInvisible(codePoint)) return false;
        return Character.isLetter(codePoint) || Character.isDigit(codePoint) || isCombiningMark(codePoint)
                || codePoint == ' ' || isAllowedPunctuation(codePoint);
    }

    /** Whether the value is already a nickname in its canonical form ({@code ""} included). */
    public static boolean isCanonical(String value) {
        return value != null && value.equals(normalize(value));
    }

    /**
     * Whether a nickname another Quick Skin announced can be shown, by checks that do not depend
     * on the Java version's Unicode tables: within the length, trimmed, single spaces, and none of
     * the characters every version knows as formatting, control, invisible, private or as the
     * brackets the rules refuse. A server on a newer Java may accept a letter this one does not
     * know yet; such a nickname is still safe to show.
     */
    public static boolean isSafeToShow(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH
                || value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' '
                || value.contains("  ")) {
            return false;
        }
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (codePoint == '§' || isInvisible(codePoint) || "()[]<>{}".indexOf(codePoint) >= 0
                    || type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.PRIVATE_USE || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || (codePoint != ' ' && Character.isWhitespace(codePoint))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether two nicknames or names would read as the same name: equal once accents, case and
     * the Cyrillic and Greek letters Minecraft's font draws like Latin ones are set aside. A
     * nickname that reads like another player's name or nickname is refused.
     */
    public static boolean readsAs(String first, String second) {
        return first != null && second != null && !first.isEmpty() && !second.isEmpty()
                && reading(first).equals(reading(second));
    }

    /**
     * How a name reads: compatibility forms decomposed, accents dropped, look-alike letters folded
     * to Latin and lower case. Two names {@link #readsAs read alike} when their readings are equal;
     * {@code ""} for a missing or empty name.
     */
    public static String reading(String name) {
        if (name == null || name.isEmpty()) return "";
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD);
        StringBuilder folded = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); ) {
            int codePoint = decomposed.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isCombiningMark(codePoint) || isInvisible(codePoint)) continue;
            folded.appendCodePoint(lookAlike(codePoint));
        }
        return folded.toString().toLowerCase(Locale.ROOT);
    }

    /** The Latin letter a Cyrillic or Greek one is drawn like, or the letter itself. */
    private static int lookAlike(int codePoint) {
        int index = LOOK_ALIKES.indexOf(codePoint);
        return index >= 0 && index % 2 == 0 ? LOOK_ALIKES.charAt(index + 1) : codePoint;
    }

    /** Pairs of a Cyrillic or Greek letter and the Latin letter it looks like. */
    private static final String LOOK_ALIKES =
            // Cyrillic capitals
            "АAВBЕEКKМMНHОOРPСCТTУYХXЅSІIЈJԚQԜWӀIҮY"
                    // Cyrillic small letters
                    + "аaеeоoрpсcуyхxѕsіiјjԁdԛqԝwһhӏlүy"
                    // Greek capitals
                    + "ΑAΒBΕEΖZΗHΙIΚKΜMΝNΟOΡPΤTΥYΧX"
                    // Greek small letters
                    + "οoνvιiαaρpυuχx";

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /**
     * Characters that Java counts as letters or marks but that draw nothing in Minecraft or
     * only change how a neighbour draws: the Hangul fillers, the combining grapheme joiner, the
     * Khmer and Mongolian invisible signs and the variation selectors.
     */
    private static boolean isInvisible(int codePoint) {
        return codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 || codePoint == 0xFFA0
                || codePoint == 0x034F
                || (codePoint >= 0x17B4 && codePoint <= 0x17B5)
                || (codePoint >= 0x180B && codePoint <= 0x180F)
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isAllowedPunctuation(int codePoint) {
        return codePoint == '_' || codePoint == '-' || codePoint == '.' || codePoint == ','
                || codePoint == '\'' || codePoint == '’';
    }
}
