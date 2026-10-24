package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.common.data.AssetMetadata;
import net.fabricmc.api.EnvType;
//? if <1.21.11 {
import net.minecraft.Util;
//?} else {
import net.minecraft.util.Util;
//?}
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The skin column of the skin menu: a scrollable grid of square skin tiles, a few across, or a list
 * of skin cards, one per row, with a slim scrollbar beside it.
 *
 * <p>The mouse wheel scrolls the grid and the scrollbar's thumb can be dragged. A click on a tile
 * selects its skin or toggles its favorite star; in the menu's delete mode it marks the skin for
 * deletion instead.
 *
 * <p>When the skins change, the column animates the difference ({@link SkinListMotion}): an added
 * skin comes in, a deleted one falls out, a new favorite flashes gold and every other skin glides
 * to its new place. Packaged E2E turns the animations off, so its captures never catch a skin on
 * its way.
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
    private static final boolean ANIMATED = !Boolean.getBoolean("quickskin.e2e.enabled");

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

    /** A skin's running animations; times are {@link Util#getMillis()}, -1 when not running. */
    private static final class Motion {
        float fromX;
        float fromY;
        long glideStart = -1;
        boolean closingGap;
        long arrivalStart = -1;
        long favoriteStart = -1;
    }

    /**
     * A deleted skin, drawn while it falls from where it was, in content coordinates, as wide as
     * it was: the list's cards widen when the scrollbar goes, and its grey bust has its old size.
     */
    private record Departure(SkinEntry entry, int contentX, int contentY, int width, long start) {
    }

    private final Map<String, Motion> motions = new HashMap<>();
    private final List<Departure> departures = new ArrayList<>();
    /** Where each skin was drawn, in content coordinates, while the list is being rebuilt. */
    @Nullable
    private Map<String, float[]> rememberedPositions;
    @Nullable
    private Map<String, SkinEntry> rememberedEntries;
    private int rememberedWidth;

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

    /** Notes where every skin is drawn now, before the list is rebuilt; see {@link #animateChanges}. */
    public void rememberLayout() {
        if (!ANIMATED) {
            return;
        }
        long now = Util.getMillis();
        rememberedPositions = new HashMap<>();
        rememberedEntries = new HashMap<>();
        rememberedWidth = entryWidth();
        for (int i = 0; i < entries.size(); i++) {
            SkinEntry entry = entries.get(i);
            String hash = entry.getMetadata().hash();
            float[] offset = offset(motions.get(hash), now);
            rememberedPositions.put(hash, new float[] {contentX(i) + offset[0], rowOffset(i) + offset[1]});
            rememberedEntries.put(hash, entry);
        }
    }

    /**
     * Animates the rebuilt list from the layout {@link #rememberLayout} noted: new skins come in,
     * skins no longer listed fall out, and the others glide from where they were drawn.
     */
    public void animateChanges() {
        Map<String, float[]> before = rememberedPositions;
        Map<String, SkinEntry> beforeEntries = rememberedEntries;
        rememberedPositions = null;
        rememberedEntries = null;
        if (before == null || beforeEntries == null) {
            return;
        }
        long now = Util.getMillis();
        Map<String, Motion> previous = new HashMap<>(motions);
        motions.clear();
        List<String> listed = new ArrayList<>();
        for (SkinEntry entry : entries) {
            listed.add(entry.getMetadata().hash());
        }
        boolean closingGap = false;
        for (Map.Entry<String, float[]> old : before.entrySet()) {
            if (!listed.contains(old.getKey())) {
                SkinEntry gone = beforeEntries.get(old.getKey());
                gone.depart();
                departures.add(new Departure(gone, Math.round(old.getValue()[0]), Math.round(old.getValue()[1]),
                        rememberedWidth, now));
                closingGap = true;
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            String hash = listed.get(i);
            Motion carried = previous.get(hash);
            Motion motion = new Motion();
            float[] was = before.get(hash);
            if (was == null) {
                motion.arrivalStart = now;
            } else {
                motion.fromX = was[0] - contentX(i);
                motion.fromY = was[1] - rowOffset(i);
                if (Math.abs(motion.fromX) > 0.5f || Math.abs(motion.fromY) > 0.5f) {
                    motion.glideStart = now;
                    motion.closingGap = closingGap;
                }
                if (carried != null) {
                    motion.favoriteStart = carried.favoriteStart;
                }
            }
            if (motion.glideStart >= 0 || motion.arrivalStart >= 0 || motion.favoriteStart >= 0) {
                motions.put(hash, motion);
            }
        }
    }

    /**
     * Carries on the animations of the list this one replaces, when the menu is rebuilt: after a
     * dialog that deleted skins, or a resize. A list of the other kind starts still.
     */
    public void continueAnimations(SkinListWidget previous) {
        if (previous == this || previous.rows != rows) {
            return;
        }
        motions.putAll(previous.motions);
        departures.addAll(previous.departures);
    }

    /** Flashes a skin just marked as a favorite. */
    public void celebrateFavorite(String hash) {
        if (!ANIMATED) {
            return;
        }
        motions.computeIfAbsent(hash, key -> new Motion()).favoriteStart = Util.getMillis();
    }

    /**
     * Keeps what the skins about to be deleted need to fall out of the column grey once their
     * textures are released.
     */
    public void prepareDeparture(Collection<String> hashes) {
        if (!ANIMATED) {
            return;
        }
        for (SkinEntry entry : entries) {
            if (hashes.contains(entry.getMetadata().hash())) {
                entry.prepareDeparture();
            }
        }
    }

    /** Whether a skin is still on its way somewhere. */
    public boolean isAnimating() {
        return !motions.isEmpty() || !departures.isEmpty();
    }

    /** How far a skin is drawn from its place now, by its glide and its arrival. */
    private float[] offset(@Nullable Motion motion, long now) {
        if (motion == null) {
            return new float[] {0, 0};
        }
        float dx = 0;
        float dy = 0;
        if (motion.glideStart >= 0) {
            long elapsed = now - motion.glideStart;
            dx += SkinListMotion.glide(motion.fromX, elapsed, motion.closingGap);
            dy += SkinListMotion.glide(motion.fromY, elapsed, motion.closingGap);
        }
        if (motion.arrivalStart >= 0) {
            SkinListMotion.Pose arrival = SkinListMotion.arrival(rows, now - motion.arrivalStart, entryWidth(), entryHeight());
            dx += arrival.dx();
            dy += arrival.dy();
        }
        return new float[] {dx, dy};
    }

    /** Whether every animation of a skin has ended, clearing the ones that have. */
    private static boolean settle(Motion motion, long now) {
        if (motion.glideStart >= 0 && SkinListMotion.glideDone(now - motion.glideStart, motion.closingGap)) {
            motion.glideStart = -1;
        }
        if (motion.arrivalStart >= 0 && now - motion.arrivalStart >= SkinListMotion.ARRIVAL_MILLIS) {
            motion.arrivalStart = -1;
        }
        if (motion.favoriteStart >= 0 && now - motion.favoriteStart >= SkinListMotion.FAVORITE_FRAME_MILLIS) {
            motion.favoriteStart = -1;
        }
        return motion.glideStart < 0 && motion.arrivalStart < 0 && motion.favoriteStart < 0;
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

    /** Called when the player clicks a skin: wears it at once. */
    public void onSkinSelected(SkinEntry entry) {
        parentScreen.onSkinClicked(entry);
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
        return rows ? viewportWidth() : tileSize;
    }

    /**
     * Width of the area the skins are drawn in. The list's cards span the widget, which the menu
     * widens to its header, and leave room for the scrollbar only when it is shown.
     */
    private int viewportWidth() {
        if (!rows) {
            return gridWidthFor(tileSize, columns);
        }
        return maxScroll() > 0 ? width - SCROLLBAR_GAP - SCROLLBAR_WIDTH : width;
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

    /** Height of every row of skins, without scrolling. */
    public int contentHeight() {
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
        return getX() + contentX(index);
    }

    /** Distance from the content's left edge to an entry's. */
    private int contentX(int index) {
        return (index % perRow()) * (tileSize + GAP);
    }

    private int tileY(int index) {
        return getY() + rowOffset(index) - (int) Math.round(scrollAmount);
    }

    private int scrollbarX() {
        return getX() + width - SCROLLBAR_WIDTH;
    }

    private boolean isInViewport(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX < getX() + viewportWidth()
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

        long now = Util.getMillis();
        renderDepartures(graphics, mouseX, mouseY, now);

        graphics.enableScissor(getX(), getY(), getX() + viewportWidth(), getY() + height);
        List<Integer> favorites = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            SkinEntry entry = entries.get(i);
            Motion motion = motions.get(entry.getMetadata().hash());
            if (motion != null && settle(motion, now)) {
                motions.remove(entry.getMetadata().hash());
                motion = null;
            }
            int y = tileY(i);
            if (motion == null && (y + entryHeight() <= getY() || y >= getY() + height)) {
                continue;
            }
            int x = tileX(i);
            boolean hovered = mouseInside && mouseX >= x && mouseX < x + entryWidth()
                    && mouseY >= y && mouseY < y + entryHeight();
            if (hovered) {
                hoveredEntry = entry;
            }
            if (motion != null && motion.favoriteStart >= 0) {
                favorites.add(i);
                continue;
            }
            renderEntry(graphics, entry, x, y, motion, now, mouseX, mouseY, hovered);
        }
        graphics.disableScissor();

        // A new favorite swells and sends out its gold frame past the list's edges: it is drawn
        // last, over the skins it passes, with that much room around the list
        if (!favorites.isEmpty()) {
            int margin = SkinListMotion.FAVORITE_OVERFLOW;
            graphics.enableScissor(getX() - margin, getY() - margin,
                    getX() + viewportWidth() + margin, getY() + height + margin);
            for (int i : favorites) {
                SkinEntry entry = entries.get(i);
                renderEntry(graphics, entry, tileX(i), tileY(i), motions.get(entry.getMetadata().hash()), now,
                        mouseX, mouseY, entry == hoveredEntry);
            }
            graphics.disableScissor();
        }

        // A slim scrollbar beside the tiles, only when they do not all fit
        if (maxScroll() > 0) {
            int x = scrollbarX();
            graphics.fill(x, getY(), x + SCROLLBAR_WIDTH, getY() + height, 0x40FFFFFF);
            boolean thumbLit = draggingScrollbar || isOnScrollbar(mouseX, mouseY);
            int thumbY = thumbY();
            graphics.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight(), thumbLit ? 0xFFFFFFFF : 0xFFB0B0B0);
        }
    }

    /** Draws one skin at its place, moved, swollen or flashing by its running animations. */
    private void renderEntry(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            SkinEntry entry, int x, int y, @Nullable Motion motion, long now, int mouseX, int mouseY, boolean hovered) {
        if (motion == null) {
            drawEntry(graphics, entry, x, y, mouseX, mouseY, hovered);
            return;
        }
        float[] offset = offset(motion, now);
        long favoriteElapsed = motion.favoriteStart >= 0 ? now - motion.favoriteStart : -1;
        float scale = favoriteElapsed >= 0 ? SkinListMotion.favorite(favoriteElapsed).scale() : 1f;
        int w = entryWidth();
        int h = entryHeight();
        GuiTransform.push(graphics, x + w / 2f, y + h / 2f, offset[0], offset[1], scale, 0);
        entry.setStarScale(favoriteElapsed >= 0 ? SkinListMotion.starScale(favoriteElapsed) : 1f);
        drawEntry(graphics, entry, x, y, mouseX, mouseY, hovered);
        entry.setStarScale(1f);
        if (favoriteElapsed >= 0) {
            int flash = Math.round(255 * SkinListMotion.favoriteFlash(favoriteElapsed));
            if (flash > 0) {
                graphics.fill(x, y, x + w, y + h, flash << 24 | SkinListMotion.FAVORITE_COLOR & 0x00FFFFFF);
            }
            int frame = Math.round(255 * SkinListMotion.favoriteFrameAlpha(favoriteElapsed));
            if (frame > 0) {
                int g = Math.round(SkinListMotion.favoriteFrameGrowth(favoriteElapsed));
                int color = frame << 24 | SkinListMotion.FAVORITE_COLOR & 0x00FFFFFF;
                graphics.fill(x - g, y - g, x + w + g, y - g + 1, color);
                graphics.fill(x - g, y + h + g - 1, x + w + g, y + h + g, color);
                graphics.fill(x - g, y - g + 1, x - g + 1, y + h + g - 1, color);
                graphics.fill(x + w + g - 1, y - g + 1, x + w + g, y + h + g - 1, color);
            }
        }
        GuiTransform.pop(graphics);
    }

    private void drawEntry(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            SkinEntry entry, int x, int y, int mouseX, int mouseY, boolean hovered) {
        if (rows) {
            entry.renderRow(graphics, x, y, entryWidth(), entryHeight(), mouseX, mouseY, hovered, entry == selected);
        } else {
            entry.render(graphics, x, y, tileSize, mouseX, mouseY, hovered, entry == selected);
        }
    }

    /**
     * Deleted skins fall from where they were, under the listed ones and out of the column: they
     * may cross the import zone below the list, never the column's sides.
     */
    private void renderDepartures(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int mouseX, int mouseY, long now) {
        if (departures.isEmpty()) {
            return;
        }
        int bottom = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        graphics.enableScissor(getX(), getY(), getX() + viewportWidth(), bottom);
        Iterator<Departure> iterator = departures.iterator();
        while (iterator.hasNext()) {
            Departure departure = iterator.next();
            long elapsed = now - departure.start();
            SkinListMotion.Pose pose = SkinListMotion.departure(elapsed);
            int x = getX() + departure.contentX();
            int y = getY() + departure.contentY() - (int) Math.round(scrollAmount);
            if (elapsed >= SkinListMotion.DEPARTURE_MAX_MILLIS || y + pose.dy() - entryHeight() > bottom) {
                iterator.remove();
                continue;
            }
            int w = departure.width();
            int h = entryHeight();
            GuiTransform.push(graphics, x + w / 2f, y + h / 2f, pose.dx(), pose.dy(), pose.scale(), pose.degrees());
            if (rows) {
                departure.entry().renderRow(graphics, x, y, w, h, mouseX, mouseY, false, false);
            } else {
                departure.entry().render(graphics, x, y, tileSize, mouseX, mouseY, false, false);
            }
            GuiTransform.pop(graphics);
        }
        graphics.disableScissor();
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
        if (!visible) {
            return false;
        }
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            // A right click on a skin opens its menu
            if (!isInViewport(mouseX, mouseY)) {
                return false;
            }
            for (int i = 0; i < entries.size(); i++) {
                int x = tileX(i);
                int y = tileY(i);
                if (mouseX >= x && mouseX < x + entryWidth() && mouseY >= y && mouseY < y + entryHeight()) {
                    parentScreen.openSkinContextMenu(entries.get(i).getMetadata(), (int) mouseX, (int) mouseY);
                    return true;
                }
            }
            return false;
        }
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
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
        parentScreen.closeSkinContextMenu();
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

    /** Marks or unmarks a skin as a favorite. */
    public void toggleFavorite(SkinEntry entry) {
        parentScreen.toggleFavorite(entry.getMetadata());
    }

    /**
     * Sets how tall the list is: as tall as its skins while they leave room below for the import
     * zone, or the whole column, scrolling, once they do not.
     */
    public void setViewportHeight(int height) {
        if (this.height != height) {
            this.height = height;
            setScrollAmount(scrollAmount);
        }
    }

    /** Whether the menu is in its delete mode, where a click marks skins to delete together. */
    public boolean isDeleteMode() {
        return parentScreen.isDeleteMode();
    }

    /** Whether a skin is marked for deletion in the delete mode. */
    public boolean isMarked(SkinEntry entry) {
        return parentScreen.isMarkedForDeletion(entry.getMetadata());
    }

    /** Marks or unmarks a skin for deletion. */
    public void toggleMarked(SkinEntry entry) {
        parentScreen.toggleMarkedForDeletion(entry.getMetadata());
    }
}
