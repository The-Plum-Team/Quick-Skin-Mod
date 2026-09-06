package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.util.ButtonFactory;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Panel that manages the action buttons at the bottom of the screen
 * (Import, HD Skin Website, Skin Website, Cape, Done)
 */
public class ActionButtonsPanel extends AbstractWidget {

    private static final int SPACING = 4;
    private static final int COMPONENT_HEIGHT = 20;

    private Button doneButton;

    /**
     * Callbacks for button actions
     */
    public record ActionCallbacks(
        Runnable onImport,
        Runnable onHdSkinWebsite,
        Runnable onSkinWebsite,
        Runnable onCape,
        Runnable onDone
    ) {}

    public ActionButtonsPanel(int x, int y, int width, int height, ActionCallbacks callbacks) {
        super(x, y, width, height, Component.empty());
    }

    /**
     * Initialize the panel and create all child widgets
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen, ActionCallbacks callbacks) {
        int fullWidthX = getX();
        int fullComponentWidth = width;
        int bottomY = getY() + height;

        // Row 1 (Bottom-most): Done Button (full width)
        bottomY -= COMPONENT_HEIGHT;
        this.doneButton = ButtonFactory.createPrimary(
            fullWidthX, bottomY, fullComponentWidth, COMPONENT_HEIGHT,
            Component.translatable("quickskin.button.done"),
            button -> callbacks.onDone.run()
        );
        screen.registerWidget(this.doneButton);

        // Row 2: Import, HD Skin, Skin, Cape Buttons (4 equal width buttons)
        bottomY -= (COMPONENT_HEIGHT + SPACING);
        int fourButtonWidth = (fullComponentWidth - (SPACING * 3)) / 4;

        Button importButton = ButtonFactory.createStyled(
            fullWidthX, bottomY, fourButtonWidth, COMPONENT_HEIGHT,
            Component.translatable("quickskin.button.import_skin"),
            button -> callbacks.onImport.run()
        );
        screen.registerWidget(importButton);

        Button hdSkinWebsiteButton = ButtonFactory.createStyled(
            fullWidthX + fourButtonWidth + SPACING, bottomY, fourButtonWidth, COMPONENT_HEIGHT,
            Component.translatable("quickskin.button.hd_skin_website"),
            button -> callbacks.onHdSkinWebsite.run()
        );
        screen.registerWidget(hdSkinWebsiteButton);

        Button skinWebsiteButton = ButtonFactory.createStyled(
            fullWidthX + (fourButtonWidth + SPACING) * 2, bottomY, fourButtonWidth, COMPONENT_HEIGHT,
            Component.translatable("quickskin.button.skin_website"),
            button -> callbacks.onSkinWebsite.run()
        );
        screen.registerWidget(skinWebsiteButton);

        Button capeButton = ButtonFactory.createStyled(
            fullWidthX + (fourButtonWidth + SPACING) * 3, bottomY, fourButtonWidth, COMPONENT_HEIGHT,
            Component.translatable("quickskin.button.cape"),
            button -> callbacks.onCape.run()
        );
        screen.registerWidget(capeButton);
    }

    public Button getDoneButton() {
        return this.doneButton;
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
