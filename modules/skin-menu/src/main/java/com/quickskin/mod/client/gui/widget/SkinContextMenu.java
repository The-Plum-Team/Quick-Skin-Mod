package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.UiText;
import com.quickskin.mod.common.data.AssetMetadata;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
//? if <1.21.11 {
import net.minecraft.Util;
//?} else {
import net.minecraft.util.Util;
//?}
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu a right click on a skin opens beside the mouse: mark or unmark it as a favorite (not
 * the player's own skin, which is pinned first anyway), rename it, or delete it. Deleting takes
 * a second click on the same item, which first turns red and asks; there is no dialog. A click
 * anywhere else, Escape or the mouse wheel closes the menu. It pops out of its corner at the
 * mouse when it opens and shrinks back into it when it closes.
 */
@Environment(EnvType.CLIENT)
public class SkinContextMenu extends AbstractWidget {
    private static final boolean ANIMATED = !Boolean.getBoolean("quickskin.e2e.enabled");
    private static final int ITEM_HEIGHT = 13;
    private static final int TEXT_LEFT = 14;
    private static final int OPEN_MILLIS = 120;
    private static final int CLOSE_MILLIS = 100;

    // The card style of the skin list: a charcoal face in a grey border and a black outline
    private static final int OUTLINE = 0xFF0E0E0F;
    private static final int BORDER = 0xFF6E6F73;
    private static final int FACE = 0xFF3A3B3E;
    private static final int FACE_HOVER = 0xFF4A4B4F;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int DANGER_TEXT = 0xFFFF9090;
    private static final int DANGER_FACE = 0xFFC03030;
    private static final int DANGER_FACE_ARMED = 0xFF9A2424;
    private static final int WHITE = 0xFFFFFFFF;

    private static final String[] ICON_STAR = {"...#...", "..###..", "#######", ".#####.", "..###..", ".##.##.", "##...##"};
    private static final String[] ICON_PEN = {".....##", "....###", "...###.", "..###..", ".###...", "###....", "##....."};
    private static final String[] ICON_TRASH = {"..###..", "#######", ".#.#.#.", ".#.#.#.", ".#.#.#.", ".#.#.#.", ".#####."};

    /** What the menu does with the skin it was opened on. */
    public interface Actions {
        void toggleFavorite(AssetMetadata metadata);

        void rename(AssetMetadata metadata);

        void delete(AssetMetadata metadata);
    }

    private enum Item { FAVORITE, RENAME, DELETE }

    private final Actions actions;
    private final Minecraft mc = Minecraft.getInstance();
    private final List<Item> items = new ArrayList<>();
    @Nullable
    private AssetMetadata target;
    private boolean favorite;
    /** Whether Delete was clicked once and now asks to be clicked again. */
    private boolean armed;
    private long openedAt;
    /** When it started to close, or -1 while it is open; it is drawn until it has shrunk away. */
    private long closingAt = -1;

    public SkinContextMenu(Actions actions) {
        super(0, 0, 0, 0, Component.empty());
        this.actions = actions;
        this.visible = false;
    }

    /** Whether it is open and takes clicks; a closing menu is only drawn. */
    public boolean isOpen() {
        return visible && closingAt < 0;
    }

    /**
     * Opens the menu for a skin with its top-left corner at the mouse, moved left or up to stay
     * on the screen.
     */
    public void open(AssetMetadata metadata, boolean ownSkin, boolean favorite, int mouseX, int mouseY,
                     int screenWidth, int screenHeight) {
        this.target = metadata;
        this.favorite = favorite;
        this.armed = false;
        items.clear();
        if (!ownSkin) {
            items.add(Item.FAVORITE);
        }
        items.add(Item.RENAME);
        items.add(Item.DELETE);
        int textWidth = 0;
        for (String key : new String[] {"quickskin.context.favorite", "quickskin.context.unfavorite",
                "quickskin.context.rename", "quickskin.context.delete", "quickskin.context.delete_confirm"}) {
            textWidth = Math.max(textWidth, mc.font.width(Component.translatable(key)));
        }
        this.width = TEXT_LEFT + textWidth + 6;
        this.height = items.size() * ITEM_HEIGHT + 4;
        int x = mouseX + width + 2 > screenWidth ? mouseX - width : mouseX;
        int y = mouseY + height + 2 > screenHeight ? mouseY - height : mouseY;
        setX(Math.max(2, x));
        setY(Math.max(2, y));
        this.openedAt = Util.getMillis();
        this.closingAt = -1;
        this.visible = true;
    }

    /** Closes the menu: it shrinks away, or vanishes at once in packaged E2E. */
    public void close() {
        if (!isOpen()) {
            return;
        }
        if (ANIMATED) {
            closingAt = Util.getMillis();
        } else {
            finishClosing();
        }
    }

    private void finishClosing() {
        visible = false;
        target = null;
        armed = false;
        closingAt = -1;
    }

    private Component label(Item item) {
        return Component.translatable(switch (item) {
            case FAVORITE -> favorite ? "quickskin.context.unfavorite" : "quickskin.context.favorite";
            case RENAME -> "quickskin.context.rename";
            case DELETE -> armed ? "quickskin.context.delete_confirm" : "quickskin.context.delete";
        });
    }

    private static String[] icon(Item item) {
        return switch (item) {
            case FAVORITE -> ICON_STAR;
            case RENAME -> ICON_PEN;
            case DELETE -> ICON_TRASH;
        };
    }

    /** The item under the mouse, or -1. */
    private int itemAt(double mouseX, double mouseY) {
        if (mouseX < getX() + 2 || mouseX >= getX() + width - 2 || mouseY < getY() + 2) {
            return -1;
        }
        int index = (int) ((mouseY - getY() - 2) / ITEM_HEIGHT);
        return index < items.size() ? index : -1;
    }

    @Override
    //? if <26.1 {
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //?} else {
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
    //?}
        if (target == null) {
            return;
        }
        int x = getX();
        int y = getY();
        long now = Util.getMillis();
        float scale = 1f;
        if (closingAt >= 0) {
            // Shrinking back into its corner at the mouse
            double p = (now - closingAt) / (double) CLOSE_MILLIS;
            if (p >= 1) {
                finishClosing();
                return;
            }
            scale = (float) (1 - p * p);
        } else if (ANIMATED && now - openedAt < OPEN_MILLIS) {
            // A quick pop out of its corner at the mouse
            scale = (float) SkinListMotion.outBack((now - openedAt) / (double) OPEN_MILLIS, 2.0) * 0.15f + 0.85f;
        }
        boolean transformed = scale != 1f;
        if (transformed) {
            GuiTransform.push(graphics, x, y, 0, 0, scale, 0);
        }
        graphics.fill(x, y, x + width, y + height, OUTLINE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, BORDER);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, FACE);
        int hovered = closingAt >= 0 ? -1 : itemAt(mouseX, mouseY);
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            int top = y + 2 + i * ITEM_HEIGHT;
            boolean danger = item == Item.DELETE;
            boolean lit = i == hovered || danger && armed;
            if (lit) {
                graphics.fill(x + 2, top, x + width - 2, top + ITEM_HEIGHT,
                        danger ? (armed && i != hovered ? DANGER_FACE_ARMED : DANGER_FACE) : FACE_HOVER);
            }
            int color = danger ? (lit ? WHITE : DANGER_TEXT) : TEXT;
            SkinEntry.drawIcon(graphics, icon(item), x + 4, top + 3, color, true);
            UiText.draw(graphics, mc.font, label(item), x + TEXT_LEFT, top + 3, color);
        }
        if (transformed) {
            GuiTransform.pop(graphics);
        }
    }

    @Override
    //? if <1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
    //?}
        if (!isOpen() || target == null) {
            return false;
        }
        int index = itemAt(mouseX, mouseY);
        boolean inside = mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
        if (!inside) {
            // A left click elsewhere only closes the menu; a right click on another skin opens its own
            close();
            return button == InputConstants.MOUSE_BUTTON_LEFT;
        }
        if (index < 0 || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return true;
        }
        AssetMetadata metadata = target;
        switch (items.get(index)) {
            case FAVORITE -> {
                playClick();
                close();
                actions.toggleFavorite(metadata);
            }
            case RENAME -> {
                playClick();
                close();
                actions.rename(metadata);
            }
            case DELETE -> {
                if (!armed) {
                    playClick();
                    armed = true;
                } else {
                    close();
                    actions.delete(metadata);
                }
            }
        }
        return true;
    }

    private void playClick() {
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        if (target != null) {
            narrationElementOutput.add(NarratedElementType.TITLE, Component.literal(target.friendlyName()));
        }
    }
}
