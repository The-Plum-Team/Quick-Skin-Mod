package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.gui.widget.SkinListWidget;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Panel that manages the skin column on the left side of the screen: a grid of tiles, or a list of
 * cards one per row
 */
public class SkinListPanel extends AbstractWidget {

    /** Size of a skin tile: a 24 px face (three pixels per skin texel) inside a 4 px frame. */
    public static final int TILE_SIZE = 32;

    /** Tiles per row of the grid, whose width the list's cards span. */
    private final int columns;
    /** Whether the skins are cards in rows instead of tiles. */
    private final boolean rows;

    @Nullable
    private SkinListWidget skinListWidget;

    private final Minecraft minecraft;
    private final Consumer<SkinEntry> onSkinSelectedCallback;

    public SkinListPanel(int x, int y, int columns, boolean rows, int height, Minecraft minecraft,
                         Consumer<SkinEntry> onSkinSelectedCallback) {
        super(x, y, SkinListWidget.widthFor(TILE_SIZE, columns), height, Component.empty());
        this.columns = columns;
        this.rows = rows;
        this.minecraft = minecraft;
        this.onSkinSelectedCallback = onSkinSelectedCallback;
    }

    /**
     * Initialize the panel and create the skin list widget
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen) {
        // Create skin list widget
        skinListWidget = new SkinListWidget(screen, minecraft, getX(), getY(), height, TILE_SIZE, columns, rows);
        screen.registerWidget(skinListWidget);

        // Load skins from LocalAssetManager
        loadSkins();
    }

    /**
     * Load skins from LocalAssetManager
     */
    public void loadSkins() {
        if (skinListWidget == null) {
            return;
        }

        LocalAssetManager assetManager = LocalAssetManager.getInstance();
        List<AssetMetadata> skins = assetManager.getAllSkins();

        for (AssetMetadata metadata : skins) {
            skinListWidget.addSkinEntry(metadata);
        }
    }

    /**
     * Refresh the skin list after importing
     */
    public void refresh() {
        if (skinListWidget == null) {
            return;
        }

        // Clear and reload, keeping the selected skin selected wherever it lands
        SkinEntry selected = skinListWidget.getSelected();
        String selectedHash = selected != null ? selected.getMetadata().hash() : null;
        skinListWidget.clearSkinEntries();
        loadSkins();
        if (selectedHash != null) {
            for (SkinEntry entry : skinListWidget.children()) {
                if (entry.getMetadata().hash().equals(selectedHash)) {
                    skinListWidget.setSelected(entry);
                    break;
                }
            }
        }
    }

    /**
     * Set the selected skin programmatically
     * @param metadata The skin metadata to select
     */
    public void setSelected(AssetMetadata metadata) {
        setSelected(metadata, true);
    }

    /**
     * Set the selected skin programmatically
     * @param metadata The skin metadata to select
     * @param triggerCallback Whether to trigger the selection callback
     */
    public void setSelected(AssetMetadata metadata, boolean triggerCallback) {
        if (skinListWidget == null || metadata == null) {
            return;
        }

        // Find and select the entry
        for (int i = 0; i < skinListWidget.children().size(); i++) {
            SkinEntry entry = skinListWidget.children().get(i);
            if (entry.getMetadata().hash().equals(metadata.hash())) {
                skinListWidget.setSelected(entry);
                if (triggerCallback) {
                    onSkinSelectedCallback.accept(entry);
                }
                skinListWidget.makeVisible(entry);
                break;
            }
        }
    }

    /** Scrolls the list to show a skin's entry, without selecting it. */
    public void reveal(String hash) {
        if (skinListWidget == null) {
            return;
        }
        for (SkinEntry entry : skinListWidget.children()) {
            if (entry.getMetadata().hash().equals(hash)) {
                skinListWidget.makeVisible(entry);
                return;
            }
        }
    }

    /**
     * Get the skin list widget
     */
    @SuppressWarnings("unused")
    @Nullable
    public SkinListWidget getSkinListWidget() {
        return skinListWidget;
    }

    /**
     * Get the currently selected skin entry
     */
    @Nullable
    public SkinEntry getSelected() {
        if (skinListWidget == null) {
            return null;
        }
        return skinListWidget.getSelected();
    }

    /** Number of skins in the column. */
    public int size() {
        return skinListWidget == null ? 0 : skinListWidget.children().size();
    }

    /** One-based position of the selected skin, or 0 when none is selected. */
    public int selectedPosition() {
        SkinEntry selected = getSelected();
        return selected == null || skinListWidget == null ? 0 : skinListWidget.children().indexOf(selected) + 1;
    }

    @Override
    //? if <26.1 {
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        // This panel doesn't render anything itself - child widgets handle rendering
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // No narration needed for container panel
    }
}
