package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.gui.util.UiText;
import com.quickskin.mod.client.gui.util.MenuGuiScale;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.client.gui.util.DialogFrame;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A screen that prompts the user to enter a new name for a skin file.
 * Features a text box and Confirm/Cancel buttons with frosted glass effect.
 */
@Environment(EnvType.CLIENT)
public class RenameScreen extends Screen {

    private final Screen parent;
    private final Component message;
    private final String initialValue;
    private final Consumer<String> callback;
    private EditBox nameEditBox;
    private Button confirmButton;

    // A DialogFrame with a blue header and the pencil
    private static final int MESSAGE_COLOR = 0xFFDDDDDD;

    // Panel dimensions
    private final int panelWidth = 300;
    private int panelHeight;
    private int panelX;
    private int panelY;
    private int messageY;

    public RenameScreen(Screen parent, Component title, Component message, String initialValue, Consumer<String> callback) {
        super(title);
        this.parent = parent;
        this.message = message;
        this.initialValue = initialValue;
        this.callback = callback;
    }

    @Override
    protected void init() {
        // Drawn over a menu, the dialog takes the menu's scale so the menu behind keeps its size
        if (this.minecraft != null) {
            MenuGuiScale.follow(this.minecraft, this, this.parent);
        }
        super.init();

        // The header, the message when there is one, the text box, then the buttons
        boolean hasMessage = !this.message.getString().isEmpty();
        this.panelHeight = DialogFrame.HEADER_HEIGHT + 14 + (hasMessage ? 18 : 0) + 20 + 14 + 20 + 12
                + 2 + DialogFrame.LIP_HEIGHT;
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;
        this.messageY = DialogFrame.bodyTop(this.panelY) + 14;

        // Text box dimensions
        int boxWidth = this.panelWidth - 32;
        int boxX = this.panelX + (this.panelWidth - boxWidth) / 2;
        int boxY = this.messageY + (hasMessage ? 18 : 0);

        this.nameEditBox = new EditBox(this.font, boxX, boxY, boxWidth, 20, this.title);
        this.nameEditBox.setMaxLength(50);
        this.nameEditBox.setValue(this.initialValue);
        this.nameEditBox.setResponder(this::onNameChanged);
        this.addRenderableWidget(this.nameEditBox);

        this.setInitialFocus(this.nameEditBox);

        // Button dimensions
        int buttonWidth = 120;
        int buttonHeight = 20;
        int buttonSpacing = 8;
        int buttonY = boxY + 20 + 14;

        // Calculate button positions (centered, side by side)
        int totalButtonWidth = (buttonWidth * 2) + buttonSpacing;
        int buttonStartX = this.panelX + (this.panelWidth - totalButtonWidth) / 2;

        // Cancel button (left)
        this.addRenderableWidget(DialogFrame.button(
            buttonStartX, buttonY, buttonWidth, buttonHeight,
            CommonComponents.GUI_CANCEL,
            (button) -> this.onClose()
        ));

        // Confirm button (right)
        this.confirmButton = this.addRenderableWidget(DialogFrame.button(
            buttonStartX + buttonWidth + buttonSpacing, buttonY, buttonWidth, buttonHeight,
            CommonComponents.GUI_DONE,
            (button) -> {
                this.callback.accept(this.nameEditBox.getValue());
                this.onClose();
            }
        ));

        onNameChanged(this.initialValue); // Initial check to set button state
    }

    private void onNameChanged(String newName) {
        if (this.confirmButton != null) {
            this.confirmButton.active = !newName.trim().isEmpty();
        }
    }

    @Override
    //? if <1.21.9 {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?} else {
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = GuiCompat.keyCode(event);
    //?}
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            if (this.confirmButton.active) {
                //? if <1.21.9 {
                this.confirmButton.onPress();
                //?} else {
                this.confirmButton.onPress(event);
                //?}
                return true;
            }
        }
        //? if <1.21.9 {
        return super.keyPressed(keyCode, scanCode, modifiers);
        //?} else {
        return super.keyPressed(event);
        //?}
    }

    @Override
    //? if <26.1 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
    //?}
        // Render parent screen in background
        if (this.parent != null) {
            BlurHandler.renderParent(this.parent, graphics, partialTicks);
        }

        //? if <1.21.6 {
        graphics.flush();
        //?}
        // From 1.21.6 the blur starts a new stratum, which draws the dialog over the 3D player
        // widget; the GUI is drawn later by the render backend, which may not be OpenGL
        BlurHandler.renderBlur(graphics);

        // Draw lighter overlay over entire screen (so blur is more visible)
        graphics.fill(0, 0, this.width, this.height, 0x60000000);

        DialogFrame.draw(graphics, this.font, this.panelX, this.panelY, this.panelWidth, this.panelHeight,
                DialogFrame.Accent.EDIT, DialogFrame.ICON_PENCIL, this.title);

        // Draw message if it exists
        if (!this.message.getString().isEmpty()) {
            int messageY = this.messageY;
            UiText.drawCentered(graphics, this.font, this.message, this.width / 2, messageY, MESSAGE_COLOR);
        }

        // Render text box and buttons
        //? if <26.1 {
        super.render(graphics, mouseX, mouseY, partialTicks);
        //?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        //?}
    }

    @Override
    public void removed() {
        if (this.minecraft != null) {
            MenuGuiScale.leave(this.minecraft, this);
        }
        super.removed();
        // Cleanup blur resources
        BlurHandler.cleanup();
    }

    @Override
    //? if <1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
    //?}
        // Check if click is outside the panel
        if (mouseX < this.panelX || mouseX > this.panelX + this.panelWidth ||
            mouseY < this.panelY || mouseY > this.panelY + this.panelHeight) {
            // Click outside panel - close the modal without saving
            this.onClose();
            return true;
        }
        // Click inside panel - handle normally
        //? if <1.21.9 {
        return super.mouseClicked(mouseX, mouseY, button);
        //?} else {
        return super.mouseClicked(event, focused);
        //?}
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            //? if <26.2 {
            this.minecraft.setScreen(this.parent);
            //?} else {
            this.minecraft.gui.setScreen(this.parent);
            //?}
        }
    }
    //? if >=1.21 && <26.1 {
        //? if <1.21.2 {
    @Override
    public void renderBlurredBackground(float partialTick) {
        //?} else if <1.21.6 {
    @Override
    protected void renderBlurredBackground() {
        //?} else if <26.1 {
    @Override
    protected void renderBlurredBackground(net.minecraft.client.gui.GuiGraphics guiGraphics) {
        //?}
        // Disable the default Minecraft blur effect - we handle blur with BlurHandler
    }
        //? if <1.21.2 {
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Minecraft 1.21.1 invokes this from super.render() after our modal has been drawn.
    }
        //?}
    //?}
    //? if >=26.1 {

    @Override
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default background (panorama) - we render the parent screen's background manually
    }

    @Override
    protected void extractBlurredBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics) {
        // Disable the default Minecraft blur effect - we handle blur with BlurHandler
    }
    //?}
}
