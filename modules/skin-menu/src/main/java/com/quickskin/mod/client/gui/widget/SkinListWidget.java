package com.quickskin.mod.client.gui.widget;

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
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.network.chat.Component;

/**
 * Scrollable column of skin tiles on the left of the skin menu
 */
@Environment(EnvType.CLIENT)
public class SkinListWidget extends ContainerObjectSelectionList<SkinEntry> {

    private final PlayerSkinMenuScreen parentScreen;
    private final Minecraft mc;
    @org.jetbrains.annotations.Nullable
    private SkinEntry hoveredEntry;
    //? if >=1.21 {
    private int xPosition;
    private final int itemHeight;
    //?}

    public SkinListWidget(PlayerSkinMenuScreen parentScreen, Minecraft mc, int width, int height,
        //? if <1.21 {
                         int y, int entryHeight) {
        super(mc, width, height, y, y + height, entryHeight);
        //?} else {
                         int y, int x, int entryHeight) {
        super(mc, width, height, y, entryHeight);
        //?}
        this.parentScreen = parentScreen;
        this.mc = mc;
        //? if >=1.21 {
        this.xPosition = x;
        this.itemHeight = entryHeight;
        // Set the widget position
        this.setX(x);
        //?}
    }

    /**
     * Add a skin entry to the list
     */
    public void addSkinEntry(AssetMetadata metadata) {
        this.addEntry(new SkinEntry(this, metadata));
    }

    /**
     * Clear all entries (public wrapper for protected method)
     */
    public void clearSkinEntries() {
        this.clearEntries();
    }

    /**
     * Make an entry visible by scrolling to it
     */
    public void makeVisible(SkinEntry entry) {
        //? if <1.21.9 {
        this.ensureVisible(entry);
        //?} else {
        this.scrollToEntry(entry);
        //?}
    }

    /**
     * Called when a skin is selected
     */
    public void onSkinSelected(SkinEntry entry) {
        parentScreen.onSkinSelected(entry);
    }

    @Override
    public int getRowWidth() {
        return SkinEntry.rowWidth(entryTileSize());
    }

    /** The square tile size of one entry: the row height less vanilla's row padding. */
    public int entryTileSize() {
        //? if <1.21 {
        return this.itemHeight - 4;
        //?} else {
        return itemHeight - 4;
        //?}
    }

    /** Where a row after the last one would start, scrolled with the list: the import tile's place. */
    public int contentBottom() {
        return this.getRowTop(this.getItemCount());
    }

    //? if <1.21 {
    @Override
    public int getRowLeft() {
        return this.x0 + 4;
    }
    //?} else if <26.1 {

    // The column floats over the menu background: no list backdrop or header/footer separators.
    @Override
    protected void renderListBackground(GuiGraphics graphics) {
    }

    @Override
    protected void renderListSeparators(GuiGraphics graphics) {
    }
    //?} else {

    // The column floats over the menu background: no list backdrop or header/footer separators.
    @Override
    protected void extractListBackground(GuiGraphicsExtractor graphics) {
    }

    @Override
    protected void extractListSeparators(GuiGraphicsExtractor graphics) {
    }
    //?}

    /** Records the entry under the mouse this frame, for the screen's tooltip. */
    void setHoveredEntry(SkinEntry entry) {
        this.hoveredEntry = entry;
    }

    /** The entry the mouse was over in the last drawn frame, or {@code null}. */
    @org.jetbrains.annotations.Nullable
    public SkinEntry getHoveredEntry() {
        return hoveredEntry;
    }

    @Override
    //? if <1.21.4 {
    protected int getScrollbarPosition() {
        //? if <1.21 {
        return this.x1 - 6;
        //?} else {
        return this.getRight() - 6;
        //?}
    //?} else {
    protected int scrollBarX() {
        return this.getRight() - 6;
    //?}
    }

    @Override
    //? if <1.21 {
    protected void renderList(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        hoveredEntry = null;
        super.renderList(graphics, mouseX, mouseY, partialTicks);
    //?} else {
    public int getX() {
        return xPosition;
    }

    @Override
    public int getRowLeft() {
        return xPosition + 4;
    }

    @Override
        //? if <26.1 {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        hoveredEntry = null;
        super.renderWidget(graphics, mouseX, mouseY, partialTicks);
        //?} else {
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        hoveredEntry = null;
        // Call parent to render the list entries
        super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTicks);
        //?}
    //?}

    }

    /**
     * Request deletion confirmation for a skin
     */
    public void requestDeletion(SkinEntry entry) {
        parentScreen.showDeleteConfirmation(entry.getMetadata());
    }

    /**
     * Request rename dialog for a skin
     */
    public void requestRename(SkinEntry entry) {
        parentScreen.showRenameDialog(entry.getMetadata());
    }

    /**
     * Request upload to Mojang for a skin
     */
    public void requestUploadToMojang(SkinEntry entry) {
        parentScreen.showUploadToMojangDialog(entry.getMetadata());
    }
}
