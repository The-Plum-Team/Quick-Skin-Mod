package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.util.UiText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A toast in a menu's bottom-right corner (the skin and cape menus), stacked with the others above
 * the newest: a card in the Confirm style on a one-pixel lip, with a strip on its left in the
 * colour of its kind and that kind's icon. Every toast is as wide as the others; a longer message
 * takes more lines. It fades in, shows for a few seconds, longer for a longer message, and fades
 * out.
 */
@Environment(EnvType.CLIENT)
public class ErrorToast {

    /** What a toast reports, which sets its strip's colour and icon. */
    public enum Kind {
        ERROR(0xFF7A2020, new String[] {
                "#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#"}),
        SUCCESS(0xFF1F5A2A, new String[] {
                "......#", ".....##", "#...##.", "##.##..", ".###...", "..#....", "......."}),
        INFO(0xFF1F3A6E, new String[] {
                "...#...", ".......", "..##...", "...#...", "...#...", "...#...", "..###.."});

        final int strip;
        final String[] icon;

        Kind(int strip, String[] icon) {
            this.strip = strip;
            this.icon = icon;
        }
    }

    private final Minecraft mc;
    private final Component message;
    private final Kind kind;
    private final long creationTime;
    private final long displayDuration;
    private List<String> lines;

    /** How long a short message shows... */
    private static final long DISPLAY_DURATION = 3000;
    /** ...and how much longer each character past {@link #SHORT_MESSAGE} keeps one, up to a limit. */
    private static final int SHORT_MESSAGE = 40;
    private static final long PER_EXTRA_CHARACTER = 50;
    private static final long MAX_DISPLAY_DURATION = 8000;
    private static final long FADE_IN = 300;
    private static final long FADE_OUT = 500;
    /** Width of every toast, so a stack lines up. */
    public static final int WIDTH = 200;
    /** The coloured strip on the left, holding the icon. */
    private static final int STRIP_WIDTH = 18;
    private static final int TEXT_PADDING = 6;
    private static final int LINE_HEIGHT = 10;
    /** Height of a line's glyphs, from the top of the capitals to the baseline. */
    private static final int GLYPH_HEIGHT = 7;
    private static final int MIN_HEIGHT = 24;
    private static final int LIP_HEIGHT = 1;

    private static final int OUTLINE = 0xFF0E0E0F;
    private static final int BORDER = 0xFF6E6F73;
    private static final int FACE = 0xFF2E2F32;
    private static final int LIP = 0xFF1C1D1F;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int ICON_SHADOW = 0xFF2A2A2A;

    public ErrorToast(Component message) {
        this(message, Kind.ERROR);
    }

    public ErrorToast(Component message, Kind kind) {
        this.mc = Minecraft.getInstance();
        this.message = message;
        this.kind = kind;
        this.creationTime = System.currentTimeMillis();
        int extra = Math.max(0, message.getString().length() - SHORT_MESSAGE);
        this.displayDuration = Math.min(MAX_DISPLAY_DURATION, DISPLAY_DURATION + extra * PER_EXTRA_CHARACTER);
    }

    /** Whether the toast has finished showing. */
    public boolean isExpired() {
        return System.currentTimeMillis() - creationTime > displayDuration;
    }

    /** The toast's height: its message's lines, at least one icon's room. */
    public int height() {
        int text = lines().size() * LINE_HEIGHT + 2 * TEXT_PADDING - 2;
        return Math.max(MIN_HEIGHT, text) + LIP_HEIGHT;
    }

    /**
     * Draws the toast with its top-left corner at {@code (x, y)}.
     *
     * @return true if still visible, false if expired
     */
    //? if <26.1 {
    public boolean render(GuiGraphics guiGraphics, int x, int y) {
    //?} else {
    public boolean render(GuiGraphicsExtractor guiGraphics, int x, int y) {
    //?}
        long elapsed = System.currentTimeMillis() - creationTime;
        if (elapsed > displayDuration) {
            return false;
        }
        float alpha = 1.0f;
        if (elapsed < FADE_IN) {
            alpha = elapsed / (float) FADE_IN;
        } else if (elapsed > displayDuration - FADE_OUT) {
            alpha = (displayDuration - elapsed) / (float) FADE_OUT;
        }
        alpha = Math.max(0.05f, Math.min(1.0f, alpha));

        int right = x + WIDTH;
        int bottom = y + height();
        int faceBottom = bottom - 1 - LIP_HEIGHT;
        guiGraphics.fill(x, y, right, bottom, fade(OUTLINE, alpha));
        guiGraphics.fill(x + 1, faceBottom, right - 1, bottom - 1, fade(LIP, alpha));
        guiGraphics.fill(x + 1, y + 1, right - 1, faceBottom, fade(BORDER, alpha));
        guiGraphics.fill(x + 2, y + 2, right - 2, faceBottom - 1, fade(FACE, alpha));
        guiGraphics.fill(x + 2, y + 2, x + 2 + STRIP_WIDTH, faceBottom - 1, fade(kind.strip, alpha));

        // The icon in the middle of the strip
        String[] icon = kind.icon;
        int iconX = x + 2 + (STRIP_WIDTH - icon[0].length()) / 2;
        int iconY = y + 2 + (faceBottom - 1 - (y + 2) - icon.length) / 2;
        for (int pass = 0; pass < 2; pass++) {
            int offset = pass == 0 ? 1 : 0;
            int color = fade(pass == 0 ? ICON_SHADOW : TEXT, alpha);
            for (int row = 0; row < icon.length; row++) {
                for (int column = 0; column < icon[row].length(); column++) {
                    if (icon[row].charAt(column) == '#') {
                        int px = iconX + column + offset;
                        int py = iconY + row + offset;
                        guiGraphics.fill(px, py, px + 1, py + 1, color);
                    }
                }
            }
        }

        // The message, wrapped to the toast's width and centred on its height
        List<String> text = lines();
        int textX = x + 2 + STRIP_WIDTH + TEXT_PADDING;
        // The lines' glyphs, without the gap after the last line, centred on the face inside the
        // border, as the icon is
        int faceTop = y + 2;
        int faceHeight = faceBottom - 1 - faceTop;
        int textHeight = text.size() * LINE_HEIGHT - (LINE_HEIGHT - GLYPH_HEIGHT);
        int textY = faceTop + (faceHeight - textHeight) / 2;
        int textColor = fade(TEXT, alpha);
        for (String line : text) {
            UiText.draw(guiGraphics, mc.font, line, textX, textY, textColor);
            textY += LINE_HEIGHT;
        }
        return true;
    }

    private List<String> lines() {
        if (lines == null) {
            lines = wrap(message.getString(), WIDTH - 4 - STRIP_WIDTH - 2 * TEXT_PADDING);
        }
        return lines;
    }

    /** Splits the text into lines no wider than {@code maxWidth}, breaking a too-long word. */
    private List<String> wrap(String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (mc.font.width(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                result.add(line.toString());
                line.setLength(0);
            }
            while (mc.font.width(word) > maxWidth) {
                String head = mc.font.plainSubstrByWidth(word, maxWidth);
                if (head.isEmpty()) break;
                result.add(head);
                word = word.substring(head.length());
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            result.add(line.toString());
        }
        return result;
    }

    private static int fade(int argb, float alpha) {
        int a = Math.round((argb >>> 24) * alpha);
        return a << 24 | (argb & 0x00FFFFFF);
    }
}
