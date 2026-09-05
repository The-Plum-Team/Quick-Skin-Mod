package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.client.gui.util.ButtonFactory;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

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

    // Panel styling (same as DeletionConfirmScreen)
    private static final int PANEL_BG = 0xB0000000;           // Darker semi-transparent background for frosted glass effect
    private static final int PANEL_OUTLINE = 0x60FFFFFF;      // Subtle white outline
    // Keep the Stonecutter seam aligned with the release branches; both APIs require opaque ARGB.
    //? if <1.21.11 {
    private static final int TITLE_COLOR = 0xFFFFFFFF;        // Opaque white title
    private static final int MESSAGE_COLOR = 0xFFFFFFFF;      // Opaque white message
    //?} else {
    private static final int TITLE_COLOR = 0xFFFFFFFF;        // Opaque white title
    private static final int MESSAGE_COLOR = 0xFFFFFFFF;      // Opaque white message
    //?}

    // Panel dimensions
    private final int panelWidth = 340;
    private final int panelHeight = 180;
    private int panelX;
    private int panelY;

    public RenameScreen(Screen parent, Component title, Component message, String initialValue, Consumer<String> callback) {
        super(title);
        this.parent = parent;
        this.message = message;
        this.initialValue = initialValue;
        this.callback = callback;
    }

    @Override
    protected void init() {
        super.init();

        // Calculate centered panel position
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;

        // Text box dimensions
        int boxWidth = 260;
        int boxX = this.panelX + (this.panelWidth - boxWidth) / 2;
        int boxY = this.panelY + 75;

        this.nameEditBox = new EditBox(this.font, boxX, boxY, boxWidth, 20, this.title);
        this.nameEditBox.setMaxLength(50);
        this.nameEditBox.setValue(this.initialValue);
        this.nameEditBox.setResponder(this::onNameChanged);
        this.addRenderableWidget(this.nameEditBox);

        this.setInitialFocus(this.nameEditBox);

        // Button dimensions
        int buttonWidth = 120;
        int buttonHeight = 20;
        int buttonSpacing = 10;
        int buttonY = this.panelY + this.panelHeight - buttonHeight - 20;

        // Calculate button positions (centered, side by side)
        int totalButtonWidth = (buttonWidth * 2) + buttonSpacing;
        int buttonStartX = this.panelX + (this.panelWidth - totalButtonWidth) / 2;

        // Cancel button (left)
        this.addRenderableWidget(ButtonFactory.createStyled(
            buttonStartX, buttonY, buttonWidth, buttonHeight,
            CommonComponents.GUI_CANCEL,
            (button) -> this.onClose()
        ));

        // Confirm button (right)
        this.confirmButton = this.addRenderableWidget(ButtonFactory.createStyled(
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
    //? if <1.21.11 {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?} else {
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = GuiCompat.keyCode(event);
    //?}
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (this.confirmButton.active) {
                //? if <1.21.11 {
                this.confirmButton.onPress();
                //?} else {
                this.confirmButton.onPress(event);
                //?}
                return true;
            }
        }
        //? if <1.21.11 {
        return super.keyPressed(keyCode, scanCode, modifiers);
        //?} else {
        return super.keyPressed(event);
        //?}
    }

    @Override
    //? if <26.1.2 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
    //?}
        // Render parent screen in background
        if (this.parent != null) {
            //? if <26.1.2 {
            this.parent.render(graphics, -1, -1, partialTicks);
            //?} else {
            GuiCompat.extractParent(this.parent, graphics, partialTicks);
            //?}
        }

        //? if <1.21.11 {
        graphics.flush();
        //?} else {
        // Disable depth test so the blur/overlay/modal panels render on top of the 3D player widget
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        //?}
        BlurHandler.renderBlur();

        // Draw lighter overlay over entire screen (so blur is more visible)
        graphics.fill(0, 0, this.width, this.height, 0x60000000);

        // Draw main panel background with frosted glass effect
        graphics.fill(this.panelX, this.panelY,
                     this.panelX + this.panelWidth,
                     this.panelY + this.panelHeight,
                     PANEL_BG);

        // Draw subtle outline around panel
        // Top
        graphics.fill(this.panelX, this.panelY,
                     this.panelX + this.panelWidth, this.panelY + 1,
                     PANEL_OUTLINE);
        // Bottom
        graphics.fill(this.panelX, this.panelY + this.panelHeight - 1,
                     this.panelX + this.panelWidth, this.panelY + this.panelHeight,
                     PANEL_OUTLINE);
        // Left
        graphics.fill(this.panelX, this.panelY,
                     this.panelX + 1, this.panelY + this.panelHeight,
                     PANEL_OUTLINE);
        // Right
        graphics.fill(this.panelX + this.panelWidth - 1, this.panelY,
                     this.panelX + this.panelWidth, this.panelY + this.panelHeight,
                     PANEL_OUTLINE);

        // Draw title (centered)
        int titleY = this.panelY + 20;
        //? if <26.1.2 {
        graphics.drawCenteredString(this.font, this.title,
        //?} else {
        graphics.centeredText(this.font, this.title,
        //?}
                                   this.width / 2, titleY,
                                   TITLE_COLOR);

        // Draw message if it exists
        if (!this.message.getString().isEmpty()) {
            int messageY = this.panelY + 45;
            //? if <26.1.2 {
            graphics.drawCenteredString(this.font, this.message,
            //?} else {
            graphics.centeredText(this.font, this.message,
            //?}
                                       this.width / 2, messageY,
                                       MESSAGE_COLOR);
        }

        // Render text box and buttons
        //? if <26.1.2 {
        super.render(graphics, mouseX, mouseY, partialTicks);
        //?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);

        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        //?}
    }

    @Override
    public void removed() {
        super.removed();
        // Cleanup blur resources
        BlurHandler.cleanup();
    }

    @Override
    //? if <1.21.11 {
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
        //? if <1.21.11 {
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
    //? if >=1.21 {
        //? if <1.21.11 {
    @Override
    public void renderBlurredBackground(float partialTick) {
        // Disable the default Minecraft blur effect - we handle blur with BlurHandler
    }
        //?}
        //? if <1.21.2 {
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Minecraft 1.21.1 invokes this from super.render() after our modal has been drawn.
    }
        //?}
    //?}
    //? if >=26.1.2 {

    private void hidePlayerWidgets(boolean hide) {
        if (this.parent == null) return;
        for (var child : this.parent.children()) {
            if (child instanceof com.quickskin.mod.client.gui.widget.PlayerWidget pw) {
                pw.visible = !hide;
            }
        }
    }

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
