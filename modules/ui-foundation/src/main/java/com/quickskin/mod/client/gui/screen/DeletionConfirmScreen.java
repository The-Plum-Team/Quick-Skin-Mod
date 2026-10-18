package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.gui.util.UiText;
//? if <1.21.5 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.client.gui.util.DialogFrame;
import com.quickskin.mod.client.gui.util.MenuGuiScale;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.client.gui.widget.ConfirmButton;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import java.util.List;
import java.util.function.Consumer;

/**
 * Asks before deleting: a {@link DialogFrame} with a red header and the bin, the message, and
 * Cancel beside a red Delete. When given the skins it deletes, it shows their faces in a row above
 * the message, framed in red.
 */
@Environment(EnvType.CLIENT)
public class DeletionConfirmScreen extends Screen {
    private final Screen parent;
    private final Component message;
    private final Consumer<Boolean> callback;
    private final List<Preview> previews;

    /**
     * A skin to show: the face (8,8) and its hat (40,8) of a skin texture of the given size, or
     * the whole texture for an icon such as a CPM model's.
     */
    public record Preview(
            //? if <1.21.11 {
            ResourceLocation texture,
            //?} else {
            Identifier texture,
            //?}
            int textureWidth, int textureHeight, boolean wholeTexture) {
    }

    private static final int MESSAGE_COLOR = 0xFFDDDDDD;
    private static final int MORE_COLOR = 0xFFC8C8C8;
    private static final int FACE_FRAME = 0xFFE04040;
    /** Faces shown at most; more are counted after them. */
    private static final int MAX_FACES = 7;
    private static final int FACE_SIZE = 18;
    private static final int FACE_GAP = 5;

    // Panel dimensions
    private final int panelWidth = 300;
    private int panelHeight;
    private int panelX;
    private int panelY;

    public DeletionConfirmScreen(Screen parent, Component title, Component message, Consumer<Boolean> callback, boolean isPermanentDelete) {
        this(parent, title, message, callback, List.of());
    }

    public DeletionConfirmScreen(Screen parent, Component title, Component message, Consumer<Boolean> callback,
                                 List<Preview> previews) {
        super(title);
        this.parent = parent;
        this.message = message;
        this.callback = callback;
        this.previews = List.copyOf(previews);
    }

    @Override
    protected void init() {
        // Drawn over a menu, the dialog takes the menu's scale so the menu behind keeps its size
        if (this.minecraft != null) {
            MenuGuiScale.follow(this.minecraft, this, this.parent);
        }
        // The header, the faces when there are any, the message, then the buttons
        this.panelHeight = 136 + (previews.isEmpty() ? 0 : FACE_SIZE + 12);
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;

        // Button dimensions
        int buttonWidth = 120;
        int buttonHeight = 20;
        int buttonSpacing = 8;
        int buttonY = DialogFrame.bodyBottom(this.panelY, this.panelHeight) - buttonHeight - 12;

        // Calculate button positions (centered, side by side)
        int totalButtonWidth = (buttonWidth * 2) + buttonSpacing;
        int buttonStartX = this.panelX + (this.panelWidth - totalButtonWidth) / 2;

        // Cancel button (left, safe)
        Button cancelButton = DialogFrame.button(
            buttonStartX, buttonY, buttonWidth, buttonHeight,
            Component.translatable("quickskin.button.cancel"),
            (button) -> this.callback.accept(false)
        );

        // Confirm delete button (right, red/danger)
        ConfirmButton confirmButton = DialogFrame.button(
            buttonStartX + buttonWidth + buttonSpacing, buttonY,
            buttonWidth, buttonHeight,
            Component.translatable("quickskin.button.delete"),
            (button) -> this.callback.accept(true)
        );
        confirmButton.setDanger(true);

        this.addRenderableWidget(cancelButton);
        this.addRenderableWidget(confirmButton);
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
        //?} else {
        // Disable depth test so the blur/overlay/modal panels render on top of the 3D player widget
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        //?}
        BlurHandler.renderBlur(graphics);

        // Draw lighter overlay over entire screen (so blur is more visible)
        graphics.fill(0, 0, this.width, this.height, 0x60000000);

        DialogFrame.draw(graphics, this.font, this.panelX, this.panelY, this.panelWidth, this.panelHeight,
                DialogFrame.Accent.DANGER, DialogFrame.ICON_BIN, this.title);

        int bodyY = DialogFrame.bodyTop(this.panelY) + 12;
        if (!previews.isEmpty()) {
            renderFaces(graphics, bodyY);
            bodyY += FACE_SIZE + 12;
        }

        // Draw message (word-wrapped if needed)
        int messageY = bodyY;
        int messageMaxWidth = this.panelWidth - 32;

        // Simple word wrap for message
        String messageText = this.message.getString();
        java.util.List<String> wrappedLines = wrapText(messageText, messageMaxWidth);

        int lineHeight = 10;
        int currentY = messageY;
        for (String line : wrappedLines) {
            UiText.drawCentered(graphics, this.font, line, this.width / 2, currentY, MESSAGE_COLOR);
            currentY += lineHeight;
        }

        // Render buttons
        //? if <26.1 {
        super.render(graphics, mouseX, mouseY, partialTicks);
        //?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);

        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        //?}
    }

    /** The skins' faces in a row, centred, each in a red frame, and how many more there are. */
    private void renderFaces(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int y) {
        int shown = Math.min(previews.size(), MAX_FACES);
        int hidden = previews.size() - shown;
        String more = hidden > 0 ? "+" + hidden : "";
        int rowWidth = shown * FACE_SIZE + (shown - 1) * FACE_GAP
                + (hidden > 0 ? FACE_GAP + this.font.width(more) : 0);
        int x = this.panelX + (this.panelWidth - rowWidth) / 2;
        //? if <1.21.5 {
        RenderSystem.enableBlend();
        //?}
        for (int i = 0; i < shown; i++) {
            Preview preview = previews.get(i);
            graphics.fill(x - 1, y - 1, x + FACE_SIZE + 1, y + FACE_SIZE + 1, FACE_FRAME);
            graphics.fill(x, y, x + FACE_SIZE, y + FACE_SIZE, 0xFF1C1D1F);
            if (preview.wholeTexture()) {
                GuiCompat.blit(graphics, preview.texture(), x, y, FACE_SIZE, FACE_SIZE,
                        0, 0, preview.textureWidth(), preview.textureHeight(),
                        preview.textureWidth(), preview.textureHeight());
            } else {
                int texel = Math.max(1, preview.textureWidth() / 64);
                GuiCompat.blit(graphics, preview.texture(), x, y, FACE_SIZE, FACE_SIZE,
                        8.0f * texel, 8.0f * texel, 8 * texel, 8 * texel,
                        preview.textureWidth(), preview.textureHeight());
                GuiCompat.blit(graphics, preview.texture(), x, y, FACE_SIZE, FACE_SIZE,
                        40.0f * texel, 8.0f * texel, 8 * texel, 8 * texel,
                        preview.textureWidth(), preview.textureHeight());
            }
            x += FACE_SIZE + FACE_GAP;
        }
        //? if <1.21.5 {
        RenderSystem.disableBlend();
        //?}
        if (hidden > 0) {
            UiText.draw(graphics, this.font, more, x, y + (FACE_SIZE - 8) / 2, MORE_COLOR);
        }
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
            // Click outside panel - close the modal without confirming
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
        // Return to parent screen without confirming
        this.callback.accept(false);
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
