package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.widget.ChoiceBubble;
import com.quickskin.mod.client.gui.widget.ConfirmButton;
import com.quickskin.mod.client.gui.widget.IconButton;
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
 * <p>Body width and Upload to Mojang sit at shoulder height, Skin websites and Cape at the waist,
 * left and right of the model, as square icon buttons in Confirm's style at the inner end of their
 * rows. Skin websites opens a bubble to its left, pointing at it, with a Normal and an HD button
 * stacked inside, and stays highlighted while the bubble is open. This panel draws nothing; it only
 * places the buttons.
 */
public class ActionButtonsPanel extends AbstractWidget {

    /** Side of the square icon buttons, every floating button but the website choices. */
    public static final int ICON_BUTTON_SIZE = 24;
    private static final int SPACING = 2;
    /**
     * The website choices draw their labels at the crisp size nearest to this many GUI pixels per
     * font pixel (one at GUI scales 2 and 3, 1.25 at 4, 1.2 at 5) and take their height from it.
     */
    private static final float CHOICE_LABEL_SCALE = 1.15f;
    /** Room between the bubble's edge and its choices. */
    private static final int BUBBLE_PADDING = 4;
    /** Gap between the bubble's pointer and the button it points at. */
    private static final int POINTER_GAP = 1;
    /** The middle row of an icon button's face, above its lip, where the bubble points. */
    private static final int ICON_FACE_MIDDLE = 10;

    private IconButton bodyWidthButton;
    private IconButton websitesButton;
    private ChoiceBubble websitesBubble;
    private Button normalWebsiteButton;
    private Button hdWebsiteButton;
    private IconButton capeButton;
    private IconButton uploadButton;
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
        Runnable onConfirm,
        Runnable onUpload
    ) {}

    /**
     * Where the buttons go: the left and right column x, the shoulder and waist rows and the
     * floating buttons' width, then Confirm's top-left corner and size. Confirm is larger than the
     * other buttons: it is the menu's main action.
     */
    public record Layout(int leftX, int rightX, int shoulderY, int waistY, int buttonWidth,
                         int confirmX, int confirmY, int confirmWidth, int confirmHeight) {}

    public ActionButtonsPanel(Layout layout) {
        super(layout.leftX(), layout.shoulderY(), layout.rightX() + layout.buttonWidth() - layout.leftX(),
                layout.confirmY() + layout.confirmHeight() - layout.shoulderY(), Component.empty());
    }

    /**
     * Initialize the panel and create all child widgets
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen, Layout layout,
                     ActionCallbacks callbacks) {
        int iconX = layout.leftX() + layout.buttonWidth() - ICON_BUTTON_SIZE;
        bodyWidthButton = new IconButton(
            iconX, layout.shoulderY(), ICON_BUTTON_SIZE, "body_wide", Component.empty(),
            button -> callbacks.onBodyWidth().run()
        );
        screen.registerWidget(bodyWidthButton);

        uploadButton = new IconButton(
            layout.rightX(), layout.shoulderY(), ICON_BUTTON_SIZE, "upload_skin",
            Component.translatable("quickskin.button.upload_skin"),
            button -> callbacks.onUpload().run()
        );
        uploadButton.setTooltip(Tooltip.create(Component.translatable("quickskin.screen.upload.title")));
        screen.registerWidget(uploadButton);

        websitesButton = new IconButton(
            iconX, layout.waistY(), ICON_BUTTON_SIZE, "skin_websites",
            Component.translatable("quickskin.button.skin_websites"),
            button -> setWebsitesOpen(!websitesOpen)
        );
        websitesButton.setTooltip(Tooltip.create(Component.translatable("quickskin.button.skin_websites")));
        screen.registerWidget(websitesButton);

        // The bubble is registered first, so that its choices are drawn over it
        int choiceHeight = ConfirmButton.heightForLabel(CHOICE_LABEL_SCALE);
        int choiceWidth = choiceWidth(screen.getFont());
        int bubbleWidth = bubbleWidth(choiceWidth);
        int bubbleHeight = 2 * choiceHeight + SPACING + 2 * BUBBLE_PADDING;
        int pointerY = layout.waistY() + ICON_FACE_MIDDLE;
        int bubbleX = iconX - POINTER_GAP - bubbleWidth;
        int bubbleY = pointerY - bubbleHeight / 2 + 1;
        websitesBubble = new ChoiceBubble(bubbleX, bubbleY, bubbleWidth, bubbleHeight, pointerY);
        screen.registerWidget(websitesBubble);

        int choiceX = bubbleX + BUBBLE_PADDING;
        int choiceY = bubbleY + BUBBLE_PADDING;
        normalWebsiteButton = new ConfirmButton(
            choiceX, choiceY, choiceWidth, choiceHeight,
            Component.translatable("quickskin.button.website_normal"),
            button -> {
                setWebsitesOpen(false);
                callbacks.onSkinWebsite().run();
            }
        );
        normalWebsiteButton.setTooltip(Tooltip.create(Component.translatable("quickskin.button.skin_website")));
        screen.registerWidget(normalWebsiteButton);

        hdWebsiteButton = new ConfirmButton(
            choiceX, choiceY + choiceHeight + SPACING, choiceWidth, choiceHeight,
            Component.translatable("quickskin.button.website_hd"),
            button -> {
                setWebsitesOpen(false);
                callbacks.onHdSkinWebsite().run();
            }
        );
        hdWebsiteButton.setTooltip(Tooltip.create(Component.translatable("quickskin.button.hd_skin_website")));
        screen.registerWidget(hdWebsiteButton);

        capeButton = new IconButton(
            layout.rightX(), layout.waistY(), ICON_BUTTON_SIZE, "cape",
            Component.translatable("quickskin.button.cape"),
            button -> callbacks.onCape().run()
        );
        capeButton.setTooltip(Tooltip.create(Component.translatable("quickskin.button.cape")));
        screen.registerWidget(capeButton);

        confirmButton = new ConfirmButton(
            layout.confirmX(), layout.confirmY(), layout.confirmWidth(), layout.confirmHeight(),
            Component.translatable("quickskin.button.confirm"),
            button -> callbacks.onConfirm().run()
        );
        screen.registerWidget(confirmButton);

        setWebsitesOpen(false);
    }

    private void setWebsitesOpen(boolean open) {
        websitesOpen = open;
        websitesButton.setSelected(open);
        websitesBubble.visible = open;
        normalWebsiteButton.visible = open;
        normalWebsiteButton.active = open;
        hdWebsiteButton.visible = open;
        hdWebsiteButton.active = open;
    }

    /** Whether the previewed skin can be uploaded to the player's Mojang account. */
    public void setUploadAvailable(boolean available) {
        if (uploadButton != null) {
            uploadButton.active = available;
        }
    }

    /**
     * Shows the previewed arm width on the Body width button, as its icon and in its tooltip; CPM
     * models have none to choose.
     */
    public void updateBodyWidth(boolean slim, Component modelName, boolean selectable) {
        if (bodyWidthButton == null) {
            return;
        }
        Component name = Component.translatable("quickskin.menu.body_width", modelName);
        bodyWidthButton.setIcon(slim ? "body_slim" : "body_wide");
        bodyWidthButton.setMessage(name);
        bodyWidthButton.setTooltip(Tooltip.create(name.copy().append("\n")
                .append(Component.translatable("quickskin.tooltip.body_width"))));
        bodyWidthButton.active = selectable;
    }

    /** Every button this panel places, for keeping the model from taking their clicks. */
    public java.util.List<Button> buttons() {
        return java.util.List.of(bodyWidthButton, uploadButton, websitesButton,
                normalWebsiteButton, hdWebsiteButton, capeButton, confirmButton);
    }

    /** The narrowest column that holds an icon button with the website bubble beside it. */
    public static int minimumButtonWidth(net.minecraft.client.gui.Font font) {
        return bubbleWidth(choiceWidth(font)) + POINTER_GAP + ICON_BUTTON_SIZE;
    }

    /** The width of the website choices: the wider of the two labels at full size. */
    private static int choiceWidth(net.minecraft.client.gui.Font font) {
        int choiceHeight = ConfirmButton.heightForLabel(CHOICE_LABEL_SCALE);
        return Math.max(
                ConfirmButton.fittingWidth(font, Component.translatable("quickskin.button.website_normal"), choiceHeight),
                ConfirmButton.fittingWidth(font, Component.translatable("quickskin.button.website_hd"), choiceHeight));
    }

    private static int bubbleWidth(int choiceWidth) {
        return choiceWidth + 2 * BUBBLE_PADDING + ChoiceBubble.POINTER_LENGTH;
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
