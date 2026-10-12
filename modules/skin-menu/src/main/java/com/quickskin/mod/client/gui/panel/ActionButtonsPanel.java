package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.util.ButtonFactory;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * The buttons that float around the player model, and Confirm below it.
 *
 * <p>Body width and Edit skin sit at shoulder height, Skin websites and Cape at the waist, left and
 * right of the model. Skin websites unfolds a Normal and an HD button below itself. This panel
 * draws nothing; it only places the buttons.
 */
public class ActionButtonsPanel extends AbstractWidget {

    public static final int BUTTON_WIDTH = 104;
    public static final int BUTTON_HEIGHT = 20;
    public static final int CONFIRM_WIDTH = 160;
    private static final int SPACING = 2;

    private Button bodyWidthButton;
    private Button editSkinButton;
    private Button websitesButton;
    private Button normalWebsiteButton;
    private Button hdWebsiteButton;
    private Button capeButton;
    private Button confirmButton;
    private boolean websitesOpen;

    /**
     * Callbacks for button actions
     */
    public record ActionCallbacks(
        Runnable onBodyWidth,
        Runnable onSkinWebsite,
        Runnable onHdSkinWebsite,
        Runnable onCape,
        Runnable onConfirm
    ) {}

    /**
     * Where the buttons go: the left and right column x, the shoulder and waist rows, and the
     * top-left corner of Confirm.
     */
    public record Layout(int leftX, int rightX, int shoulderY, int waistY, int confirmX, int confirmY) {}

    public ActionButtonsPanel(Layout layout) {
        super(layout.leftX(), layout.shoulderY(), layout.rightX() + BUTTON_WIDTH - layout.leftX(),
                layout.confirmY() + BUTTON_HEIGHT - layout.shoulderY(), Component.empty());
    }

    /**
     * Initialize the panel and create all child widgets
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen, Layout layout,
                     ActionCallbacks callbacks) {
        bodyWidthButton = ButtonFactory.createStyled(
            layout.leftX(), layout.shoulderY(), BUTTON_WIDTH, BUTTON_HEIGHT,
            Component.empty(),
            button -> callbacks.onBodyWidth().run()
        );
        bodyWidthButton.setTooltip(Tooltip.create(Component.translatable("quickskin.tooltip.body_width")));
        screen.registerWidget(bodyWidthButton);

        // There is no skin editor yet; the button keeps its place in the layout.
        editSkinButton = ButtonFactory.createStyled(
            layout.rightX(), layout.shoulderY(), BUTTON_WIDTH, BUTTON_HEIGHT,
            Component.translatable("quickskin.button.edit_skin"),
            button -> { }
        );
        editSkinButton.active = false;
        editSkinButton.setTooltip(Tooltip.create(Component.translatable("quickskin.tooltip.coming_soon")));
        screen.registerWidget(editSkinButton);

        websitesButton = ButtonFactory.createStyled(
            layout.leftX(), layout.waistY(), BUTTON_WIDTH, BUTTON_HEIGHT,
            Component.translatable("quickskin.button.skin_websites"),
            button -> setWebsitesOpen(!websitesOpen)
        );
        screen.registerWidget(websitesButton);

        int halfWidth = (BUTTON_WIDTH - SPACING) / 2;
        int choiceY = layout.waistY() + BUTTON_HEIGHT + SPACING;
        normalWebsiteButton = ButtonFactory.createStyled(
            layout.leftX(), choiceY, halfWidth, BUTTON_HEIGHT,
            Component.translatable("quickskin.button.website_normal"),
            button -> {
                setWebsitesOpen(false);
                callbacks.onSkinWebsite().run();
            }
        );
        normalWebsiteButton.setTooltip(Tooltip.create(Component.translatable("quickskin.button.skin_website")));
        screen.registerWidget(normalWebsiteButton);

        hdWebsiteButton = ButtonFactory.createStyled(
            layout.leftX() + BUTTON_WIDTH - halfWidth, choiceY, halfWidth, BUTTON_HEIGHT,
            Component.translatable("quickskin.button.website_hd"),
            button -> {
                setWebsitesOpen(false);
                callbacks.onHdSkinWebsite().run();
            }
        );
        hdWebsiteButton.setTooltip(Tooltip.create(Component.translatable("quickskin.button.hd_skin_website")));
        screen.registerWidget(hdWebsiteButton);

        capeButton = ButtonFactory.createStyled(
            layout.rightX(), layout.waistY(), BUTTON_WIDTH, BUTTON_HEIGHT,
            Component.translatable("quickskin.button.cape"),
            button -> callbacks.onCape().run()
        );
        screen.registerWidget(capeButton);

        confirmButton = ButtonFactory.createPrimary(
            layout.confirmX(), layout.confirmY(), CONFIRM_WIDTH, BUTTON_HEIGHT,
            Component.translatable("quickskin.button.confirm"),
            button -> callbacks.onConfirm().run()
        );
        screen.registerWidget(confirmButton);

        setWebsitesOpen(false);
    }

    private void setWebsitesOpen(boolean open) {
        websitesOpen = open;
        normalWebsiteButton.visible = open;
        normalWebsiteButton.active = open;
        hdWebsiteButton.visible = open;
        hdWebsiteButton.active = open;
    }

    /** Shows the previewed arm width on the Body width button; CPM models have none to choose. */
    public void updateBodyWidth(Component modelName, boolean selectable) {
        if (bodyWidthButton == null) {
            return;
        }
        bodyWidthButton.setMessage(Component.translatable("quickskin.menu.body_width", modelName));
        bodyWidthButton.active = selectable;
    }

    /** Every button this panel places, for keeping the model from taking their clicks. */
    public java.util.List<Button> buttons() {
        return java.util.List.of(bodyWidthButton, editSkinButton, websitesButton,
                normalWebsiteButton, hdWebsiteButton, capeButton, confirmButton);
    }

    public Button getConfirmButton() {
        return this.confirmButton;
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
