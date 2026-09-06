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
 * Panel that manages the skin list widget on the left side of the screen
 */
public class SkinListPanel extends AbstractWidget {

    @Nullable
    private SkinListWidget skinListWidget;

    private final Minecraft minecraft;
    private final Consumer<SkinEntry> onSkinSelectedCallback;

    public SkinListPanel(int x, int y, int width, int height, Minecraft minecraft, Consumer<SkinEntry> onSkinSelectedCallback) {
        super(x, y, width, height, Component.empty());
        this.minecraft = minecraft;
        this.onSkinSelectedCallback = onSkinSelectedCallback;
    }

    /**
     * Initialize the panel and create the skin list widget
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen) {
        // Create skin list widget
        skinListWidget = new SkinListWidget(
            screen,
            minecraft,
            width,
            height,
            getY(),
            //? if >=1.21 {
            getX(), // X position
            //?}
            40 // Entry height - matches original
        );
        //? if <1.21 {
        skinListWidget.setLeftPos(getX());
        skinListWidget.setRenderBackground(false);
        skinListWidget.setRenderTopAndBottom(false);
        //?}
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
