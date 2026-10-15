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
 * Panel that manages the skin grid on the left side of the screen
 */
public class SkinListPanel extends AbstractWidget {

    /** Size of a skin tile: a 24 px face (three pixels per skin texel) inside a 4 px frame. */
    public static final int TILE_SIZE = 32;

    /** Tiles per row of the grid. */
    private final int columns;

    @Nullable
    private SkinListWidget skinListWidget;

    private final Minecraft minecraft;
    private final Consumer<SkinEntry> onSkinSelectedCallback;

    public SkinListPanel(int x, int y, int columns, int height, Minecraft minecraft, Consumer<SkinEntry> onSkinSelectedCallback) {
        super(x, y, SkinListWidget.widthFor(TILE_SIZE, columns), height, Component.empty());
        this.columns = columns;
        this.minecraft = minecraft;
        this.onSkinSelectedCallback = onSkinSelectedCallback;
    }

    /**
     * Initialize the panel and create the skin list widget
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen) {
        // Create skin list widget
        skinListWidget = new SkinListWidget(screen, minecraft, getX(), getY(), height, TILE_SIZE, columns);
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

        // Clear and reload
        skinListWidget.clearSkinEntries();
        loadSkins();
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
