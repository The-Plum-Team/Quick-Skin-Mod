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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public class DeletionConfirmScreen extends Screen {
    private final Screen parent;
    private final Component message;
    private final Consumer<Boolean> callback;

    // Panel styling
    private static final int PANEL_BG = 0xB0000000;           // Darker semi-transparent background for frosted glass effect
    private static final int PANEL_OUTLINE = 0x60FFFFFF;      // Subtle white outline
    // Keep the Stonecutter seam aligned with the release branches; both APIs require opaque ARGB.
    //? if <1.21.11 {
    private static final int TITLE_COLOR = 0xFFFFFFFF;        // Opaque white title
    private static final int MESSAGE_COLOR = 0xFFFFFFFF;      // Opaque white message
    private static final int WARNING_COLOR = 0xFFFFCC00;      // Opaque orange warning text
    //?} else {
    private static final int TITLE_COLOR = 0xFFFFFFFF;        // Opaque white title
    private static final int MESSAGE_COLOR = 0xFFFFFFFF;      // Opaque white message
    private static final int WARNING_COLOR = 0xFFFFCC00;      // Opaque orange warning text
    //?}

    // Panel dimensions
    private final int panelWidth = 340;
    private final int panelHeight = 160;
    private int panelX;
    private int panelY;

    public DeletionConfirmScreen(Screen parent, Component title, Component message, Consumer<Boolean> callback, boolean isPermanentDelete) {
        super(title);
        this.parent = parent;
        this.message = message;
        this.callback = callback;
    }

    @Override
    protected void init() {
        // Calculate centered panel position
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;

        // Button dimensions
        int buttonWidth = 120;
        int buttonHeight = 20;
        int buttonSpacing = 10;
        int buttonY = this.panelY + this.panelHeight - buttonHeight - 20;

        // Calculate button positions (centered, side by side)
        int totalButtonWidth = (buttonWidth * 2) + buttonSpacing;
        int buttonStartX = this.panelX + (this.panelWidth - totalButtonWidth) / 2;

        // Cancel button (left, safe)
        Button cancelButton = ButtonFactory.createStyled(
            buttonStartX, buttonY, buttonWidth, buttonHeight,
            Component.translatable("quickskin.button.cancel"),
            (button) -> this.callback.accept(false)
        );

        // Confirm delete button (right, red/danger)
        Button confirmButton = ButtonFactory.createDanger(
            buttonStartX + buttonWidth + buttonSpacing, buttonY,
            buttonWidth, buttonHeight,
            Component.translatable("quickskin.button.delete"),
            (button) -> this.callback.accept(true)
        );

        this.addRenderableWidget(cancelButton);
        this.addRenderableWidget(confirmButton);
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

        // Draw warning icon (simple exclamation mark)
        int iconY = this.panelY + 45;
        String warningIcon = "!";
        //? if <26.1.2 {
        graphics.drawCenteredString(this.font, warningIcon,
        //?} else {
        graphics.centeredText(this.font, warningIcon,
        //?}
                                   this.width / 2, iconY,
                                   WARNING_COLOR);

        // Draw message (word-wrapped if needed)
        int messageY = this.panelY + 65;
        int messageMaxWidth = this.panelWidth - 40; // 20px padding on each side

        // Simple word wrap for message
        String messageText = this.message.getString();
        java.util.List<String> wrappedLines = wrapText(messageText, messageMaxWidth);

        int lineHeight = 10;
        int currentY = messageY;
        for (String line : wrappedLines) {
            //? if <26.1.2 {
            graphics.drawCenteredString(this.font, line,
            //?} else {
            graphics.centeredText(this.font, line,
            //?}
                                       this.width / 2, currentY,
                                       MESSAGE_COLOR);
            currentY += lineHeight;
        }

        // Render buttons
        //? if <26.1.2 {
        super.render(graphics, mouseX, mouseY, partialTicks);
        //?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);

        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        //?}
    }

    private java.util.List<String> wrapText(String text, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String testLine = currentLine.isEmpty() ? word : currentLine + " " + word;
            int lineWidth = this.font.width(testLine);

            if (lineWidth > maxWidth && !currentLine.isEmpty()) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            } else {
                if (!currentLine.isEmpty()) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            }
        }

        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }

        return lines;
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
            // Click outside panel - close the modal without confirming
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
        // Return to parent screen without confirming
        this.callback.accept(false);
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
