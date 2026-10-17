package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
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
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The skin column of the skin menu: a scrollable grid of square skin tiles, a few across, or a list
 * of skin cards, one per row, with a slim scrollbar beside it.
 *
 * <p>The mouse wheel scrolls the grid and the scrollbar's thumb can be dragged. A click on a tile
 * selects its skin, or runs the action under the mouse on the strip a hovered tile shows.
 */
@Environment(EnvType.CLIENT)
public class SkinListWidget extends AbstractWidget {

    /** Space between tiles. */
    public static final int GAP = 4;
    /** Height of a card in the list, and the space between cards. */
    public static final int ROW_HEIGHT = 42;
    public static final int ROW_GAP = 3;
    /** Space between the tiles and the scrollbar, and the scrollbar's width. */
    public static final int SCROLLBAR_GAP = 3;
    public static final int SCROLLBAR_WIDTH = 3;
    private static final int MIN_THUMB_HEIGHT = 12;

    private final PlayerSkinMenuScreen parentScreen;
    private final int tileSize;
    /** Tiles per row: the grid's width, which the list's cards span. */
    private final int columns;
    /** Whether the skins are cards in rows instead of tiles. */
    private final boolean rows;
    private final List<SkinEntry> entries = new ArrayList<>();
    @Nullable
    private SkinEntry selected;
    @Nullable
    private SkinEntry hoveredEntry;
    private double scrollAmount;
    private boolean draggingScrollbar;
    private double scrollbarGrabOffset;

    public SkinListWidget(PlayerSkinMenuScreen parentScreen, Minecraft mc, int x, int y, int height,
                          int tileSize, int columns, boolean rows) {
        super(x, y, widthFor(tileSize, columns), height, Component.translatable("quickskin.menu.skins"));
        this.parentScreen = parentScreen;
        this.tileSize = tileSize;
        this.columns = columns;
        this.rows = rows;
    }

    /** Width of the grid plus its scrollbar, for tiles of the given size. */
    public static int widthFor(int tileSize, int columns) {
        return gridWidthFor(tileSize, columns) + SCROLLBAR_GAP + SCROLLBAR_WIDTH;
    }

    /** Width of the tiles alone, without the scrollbar. */
    public static int gridWidthFor(int tileSize, int columns) {
        return columns * tileSize + (columns - 1) * GAP;
    }

    /**
     * Add a skin entry to the list
     */
    public void addSkinEntry(AssetMetadata metadata) {
        entries.add(new SkinEntry(this, metadata));
    }

    /**
     * Clear all entries
     */
    public void clearSkinEntries() {
        entries.clear();
        selected = null;
        hoveredEntry = null;
        scrollAmount = 0;
    }

    /** The skins in display order. */
    public List<SkinEntry> children() {
        return Collections.unmodifiableList(entries);
    }

    @Nullable
    public SkinEntry getSelected() {
        return selected;
    }

    public void setSelected(@Nullable SkinEntry entry) {
        this.selected = entry;
    }

    /**
     * Make an entry visible by scrolling to it
     */
    public void makeVisible(SkinEntry entry) {
        int index = entries.indexOf(entry);
        if (index < 0) {
            return;
        }
        int top = rowOffset(index);
        if (top < scrollAmount) {
            setScrollAmount(top);
        } else if (top + entryHeight() > scrollAmount + height) {
            setScrollAmount(top + entryHeight() - height);
        }
    }

    /**
     * Called when a skin is selected
     */
    public void onSkinSelected(SkinEntry entry) {
        parentScreen.onSkinSelected(entry);
    }

    /** The square tile size of one entry. */
    public int entryTileSize() {
        return tileSize;
    }

    /** The entry the mouse was over in the last drawn frame, or {@code null}. */
    @Nullable
    public SkinEntry getHoveredEntry() {
        return hoveredEntry;
    }

    private int entryWidth() {
        return rows ? gridWidthFor(tileSize, columns) : tileSize;
    }

    private int entryHeight() {
        return rows ? ROW_HEIGHT : tileSize;
    }

    private int gap() {
        return rows ? ROW_GAP : GAP;
    }

    /** Entries side by side: one card per row in the list. */
    private int perRow() {
        return rows ? 1 : columns;
    }

    private int stride() {
        return entryHeight() + gap();
    }

    /** Distance from the top of the content to the top of an entry's row. */
    private int rowOffset(int index) {
        return (index / perRow()) * stride();
    }

    private int contentHeight() {
        int rowCount = (entries.size() + perRow() - 1) / perRow();
        return Math.max(0, rowCount * stride() - gap());
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - height);
    }

    private void setScrollAmount(double amount) {
        scrollAmount = Math.max(0, Math.min(maxScroll(), amount));
    }

    private int tileX(int index) {
        return getX() + (index % perRow()) * (tileSize + GAP);
    }

    private int tileY(int index) {
        return getY() + rowOffset(index) - (int) Math.round(scrollAmount);
    }

    private int scrollbarX() {
        return getX() + gridWidthFor(tileSize, columns) + SCROLLBAR_GAP;
    }

    private boolean isInViewport(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX < getX() + gridWidthFor(tileSize, columns)
                && mouseY >= getY() && mouseY < getY() + height;
    }

    private boolean isOnScrollbar(double mouseX, double mouseY) {
        int x = scrollbarX();
        // A little slack either side: the bar itself is only a few pixels wide
        return maxScroll() > 0 && mouseX >= x - 2 && mouseX < x + SCROLLBAR_WIDTH + 2
                && mouseY >= getY() && mouseY < getY() + height;
    }

    private int thumbHeight() {
        int content = Math.max(1, contentHeight());
        return Math.max(MIN_THUMB_HEIGHT, Math.min(height, height * height / content));
    }

    private int thumbY() {
        int max = maxScroll();
        return getY() + (max == 0 ? 0 : (int) Math.round(scrollAmount * (height - thumbHeight()) / max));
    }

    @Override
    //? if <26.1 {
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //?} else {
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
    //?}
        setScrollAmount(scrollAmount); // the content may have shrunk since the last frame
        hoveredEntry = null;
        boolean mouseInside = isInViewport(mouseX, mouseY) && !draggingScrollbar;

        graphics.enableScissor(getX(), getY(), getX() + gridWidthFor(tileSize, columns), getY() + height);
        for (int i = 0; i < entries.size(); i++) {
            int y = tileY(i);
            if (y + entryHeight() <= getY() || y >= getY() + height) {
                continue;
            }
            int x = tileX(i);
            boolean hovered = mouseInside && mouseX >= x && mouseX < x + entryWidth()
                    && mouseY >= y && mouseY < y + entryHeight();
            SkinEntry entry = entries.get(i);
            if (rows) {
                entry.renderRow(graphics, x, y, entryWidth(), entryHeight(), mouseX, mouseY, hovered, entry == selected);
            } else {
                entry.render(graphics, x, y, tileSize, mouseX, mouseY, hovered, entry == selected);
            }
            if (hovered) {
                hoveredEntry = entry;
            }
        }
        graphics.disableScissor();

        // A slim scrollbar beside the tiles, only when they do not all fit
        if (maxScroll() > 0) {
            int x = scrollbarX();
            graphics.fill(x, getY(), x + SCROLLBAR_WIDTH, getY() + height, 0x40FFFFFF);
            boolean thumbLit = draggingScrollbar || isOnScrollbar(mouseX, mouseY);
            int thumbY = thumbY();
            graphics.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight(), thumbLit ? 0xFFFFFFFF : 0xFFB0B0B0);
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
        if (!visible || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (isOnScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            int thumbY = thumbY();
            boolean onThumb = mouseY >= thumbY && mouseY < thumbY + thumbHeight();
            // Grabbing the thumb keeps its offset; clicking the track centres the thumb there
            scrollbarGrabOffset = onThumb ? mouseY - thumbY : thumbHeight() / 2.0;
            dragScrollbarTo(mouseY);
            return true;
        }
        if (!isInViewport(mouseX, mouseY)) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            int x = tileX(i);
            int y = tileY(i);
            if (mouseX >= x && mouseX < x + entryWidth() && mouseY >= y && mouseY < y + entryHeight()) {
                return entries.get(i).click();
            }
        }
        return false;
    }

    @Override
    //? if <1.21.9 {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    //?} else {
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseY = GuiCompat.mouseY(event);
    //?}
        if (draggingScrollbar) {
            dragScrollbarTo(mouseY);
            return true;
        }
        return false;
    }

    @Override
    //? if <1.21.9 {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
    //?} else {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
    //?}
        boolean wasDragging = draggingScrollbar;
        draggingScrollbar = false;
        return wasDragging;
    }

    private void dragScrollbarTo(double mouseY) {
        int track = height - thumbHeight();
        if (track <= 0) {
            return;
        }
        double thumbTop = mouseY - scrollbarGrabOffset - getY();
        setScrollAmount(thumbTop / track * maxScroll());
    }

    @Override
    //? if <1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
    //?} else {
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
    //?}
        if (!visible || maxScroll() == 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        setScrollAmount(scrollAmount - deltaY * stride() / 2.0);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE, getMessage());
        if (selected != null) {
            narrationElementOutput.add(NarratedElementType.USAGE,
                    Component.literal(selected.getSortName()));
        }
    }

    /**
     * Request deletion confirmation for a skin
     */
    public void requestDeletion(SkinEntry entry) {
        parentScreen.showDeleteConfirmation(entry.getMetadata());
    }
}
