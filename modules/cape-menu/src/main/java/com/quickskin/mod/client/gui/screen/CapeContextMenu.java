package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.UiText;
import com.quickskin.mod.client.gui.widget.CapeEditorStyle;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu a right click on a cape opens beside the mouse, in the skin menu's card style: mark or
 * unmark it as a favourite, and for a cape of the player's own, edit it in the cape editor (when
 * the editor kept its source) or delete it (through the usual confirmation). A click anywhere
 * else, Escape or the mouse wheel closes it.
 */
@Environment(EnvType.CLIENT)
class CapeContextMenu extends AbstractWidget {
    private static final int ITEM_HEIGHT = 13;
    private static final int TEXT_LEFT = 14;

    private static final int OUTLINE = 0xFF0E0E0F;
    private static final int BORDER = 0xFF6E6F73;
    private static final int FACE = 0xFF3A3B3E;
    private static final int FACE_HOVER = 0xFF4A4B4F;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int DANGER_TEXT = 0xFFFF9090;
    private static final int DANGER_FACE = 0xFFC03030;
    private static final int WHITE = 0xFFFFFFFF;

    static final String[] ICON_STAR = {"...#...", "..###..", "#######", ".#####.", "..###..", ".##.##.", "##...##"};
    private static final String[] ICON_PEN = {".....##", "....###", "...###.", "..###..", ".###...", "###....", "##....."};
    private static final String[] ICON_TRASH = {"..###..", "#######", ".#.#.#.", ".#.#.#.", ".#.#.#.", ".#.#.#.", ".#####."};

    /** What the menu does with the cape it was opened on. */
    interface Actions {
        void toggleFavorite(CapeEntry cape);

        void edit(CapeEntry cape);

        void delete(CapeEntry cape);
    }

    private enum Item { FAVORITE, EDIT, DELETE }

    private final Actions actions;
    private final Minecraft mc = Minecraft.getInstance();
    private final List<Item> items = new ArrayList<>();
    @Nullable
    private CapeEntry target;
    private boolean favorite;

    CapeContextMenu(Actions actions) {
        super(0, 0, 0, 0, Component.empty());
        this.actions = actions;
        this.visible = false;
    }

    boolean isOpen() {
        return visible && target != null;
    }

    /** Opens the menu for a cape with its corner at the mouse, moved left or up to stay on screen. */
    void open(CapeEntry cape, boolean favorite, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        this.target = cape;
        this.favorite = favorite;
        items.clear();
        items.add(Item.FAVORITE);
        if (cape.isLocal() && !cape.isKnown()) {
            if (cape.isEditable()) {
                items.add(Item.EDIT);
            }
            items.add(Item.DELETE);
        }
        int textWidth = 0;
        for (Item item : items) {
            textWidth = Math.max(textWidth, mc.font.width(label(item)));
        }
        textWidth = Math.max(textWidth, mc.font.width(Component.translatable("quickskin.context.unfavorite")));
        this.width = TEXT_LEFT + textWidth + 6;
        this.height = items.size() * ITEM_HEIGHT + 4;
        int x = mouseX + width + 2 > screenWidth ? mouseX - width : mouseX;
        int y = mouseY + height + 2 > screenHeight ? mouseY - height : mouseY;
        setX(Math.max(2, x));
        setY(Math.max(2, y));
        this.visible = true;
    }

    void close() {
        visible = false;
        target = null;
    }

    private Component label(Item item) {
        return Component.translatable(switch (item) {
            case FAVORITE -> favorite ? "quickskin.context.unfavorite" : "quickskin.context.favorite";
            case EDIT -> "quickskin.context.edit_cape";
            case DELETE -> "quickskin.context.delete";
        });
    }

    private static String[] icon(Item item) {
        return switch (item) {
            case FAVORITE -> ICON_STAR;
            case EDIT -> ICON_PEN;
            case DELETE -> ICON_TRASH;
        };
    }

    /** Draws the open menu over everything else, the player model included; the screen calls it last. */
    //? if <26.1 {
    void drawOnTop(GuiGraphics graphics, int mouseX, int mouseY) {
        //? if <1.21.6 {
        // Up to 1.21.5 the GUI is depth tested and the model reaches forward to z 250 at most
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 350.0F);
        renderWidget(graphics, mouseX, mouseY, 0);
        graphics.pose().popPose();
        //?} else {
        graphics.nextStratum();
        renderWidget(graphics, mouseX, mouseY, 0);
        //?}
    }
    //?} else {
    void drawOnTop(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.nextStratum();
        extractWidgetRenderState(graphics, mouseX, mouseY, 0);
    }
    //?}

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
        graphics.fill(x, y, x + width, y + height, OUTLINE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, BORDER);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, FACE);
        int hovered = itemAt(mouseX, mouseY);
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            int top = y + 2 + i * ITEM_HEIGHT;
            boolean danger = item == Item.DELETE;
            boolean lit = i == hovered;
            if (lit) {
                graphics.fill(x + 2, top, x + width - 2, top + ITEM_HEIGHT, danger ? DANGER_FACE : FACE_HOVER);
            }
            int color = danger ? (lit ? WHITE : DANGER_TEXT) : TEXT;
            CapeEditorStyle.icon(graphics, icon(item), x + 4, top + 3, color);
            UiText.draw(graphics, mc.font, label(item), x + TEXT_LEFT, top + 3, color);
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
        return click(mouseX, mouseY, button);
    }

    /** A click while the menu is open: on an item it acts, elsewhere it closes the menu. */
    boolean click(double mouseX, double mouseY, int button) {
        if (!isOpen()) {
            return false;
        }
        boolean inside = mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
        if (!inside) {
            // A left click elsewhere only closes the menu; a right click on another cape opens its own
            close();
            return button == InputConstants.MOUSE_BUTTON_LEFT;
        }
        int index = itemAt(mouseX, mouseY);
        if (index < 0 || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return true;
        }
        CapeEntry cape = target;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f));
        close();
        switch (items.get(index)) {
            case FAVORITE -> actions.toggleFavorite(cape);
            case EDIT -> actions.edit(cape);
            case DELETE -> actions.delete(cape);
        }
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        if (target != null) {
            narrationElementOutput.add(NarratedElementType.TITLE, Component.literal(target.getFriendlyName()));
        }
    }
}
