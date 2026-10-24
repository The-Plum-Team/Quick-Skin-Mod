package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.client.gui.util.DialogFrame;
import com.quickskin.mod.client.gui.util.MenuGuiScale;
import com.quickskin.mod.client.gui.util.UiText;
import com.quickskin.mod.common.data.Nickname;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
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
 * The dialog the skin menu's name tag opens: the player types the nickname others see them by,
 * or goes back to their account name, which commands keep using either way. The text box takes
 * only characters a nickname may hold, and Done waits until the text is a valid nickname; an
 * empty box shows the account name it stands for. Typed text survives a resize.
 */
@Environment(EnvType.CLIENT)
public class NicknameScreen extends Screen {
    private static final int MESSAGE_COLOR = 0xFFDDDDDD;
    private static final int HINT_COLOR = 0xFFFF9090;
    private static final int PANEL_WIDTH = 320;
    private static final int LINE_HEIGHT = 11;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 8;

    private final Screen parent;
    private final String accountName;
    private final String nickname;
    private final Consumer<String> callback;
    private String typed;
    private EditBox box;
    private Button doneButton;
    private int panelX;
    private int panelY;
    private int panelHeight;
    private int messageY;
    private int hintY;

    /**
     * @param nickname the player's nickname now, {@code ""} for none
     * @param callback receives the typed nickname, {@code ""} to go back to the account name
     */
    public NicknameScreen(Screen parent, String accountName, String nickname, Consumer<String> callback) {
        super(Component.translatable("quickskin.screen.nickname.title"));
        this.parent = parent;
        this.accountName = accountName;
        this.nickname = nickname;
        this.callback = callback;
        this.typed = nickname;
    }

    @Override
    protected void init() {
        // Drawn over a menu, the dialog takes the menu's scale so the menu behind keeps its size
        if (this.minecraft != null) {
            MenuGuiScale.follow(this.minecraft, this, this.parent);
        }
        super.init();

        // The header, two lines of message, the text box, a line for the hint, then the buttons
        this.panelHeight = DialogFrame.HEADER_HEIGHT + 12 + 2 * LINE_HEIGHT + 6 + 20 + 4 + 10 + 8
                + BUTTON_HEIGHT + 12 + 2 + DialogFrame.LIP_HEIGHT;
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;
        this.messageY = DialogFrame.bodyTop(this.panelY) + 12;

        int boxX = this.panelX + 16;
        int boxY = this.messageY + 2 * LINE_HEIGHT + 6;
        this.hintY = boxY + 20 + 4;
        this.box = new NicknameBox(this.font, boxX, boxY, PANEL_WIDTH - 32, 20, this.title);
        this.box.setMaxLength(Nickname.MAX_LENGTH);
        this.box.setValue(this.typed);
        this.box.setResponder(this::onTyped);
        this.addRenderableWidget(this.box);
        this.setInitialFocus(this.box);

        int buttonY = this.hintY + 10 + 8;
        int buttonWidth = (PANEL_WIDTH - 32 - 2 * BUTTON_GAP) / 3;
        this.addRenderableWidget(DialogFrame.button(
                boxX, buttonY, buttonWidth, BUTTON_HEIGHT,
                CommonComponents.GUI_CANCEL, button -> this.onClose()));
        Button reset = this.addRenderableWidget(DialogFrame.button(
                boxX + buttonWidth + BUTTON_GAP, buttonY, buttonWidth, BUTTON_HEIGHT,
                Component.translatable("quickskin.button.nickname_reset"), button -> finish("")));
        reset.active = !this.nickname.isEmpty();
        this.doneButton = this.addRenderableWidget(DialogFrame.button(
                boxX + 2 * (buttonWidth + BUTTON_GAP), buttonY, buttonWidth, BUTTON_HEIGHT,
                CommonComponents.GUI_DONE, button -> finish(this.box.getValue())));
        onTyped(this.box.getValue());
    }

    private void onTyped(String value) {
        this.typed = value;
        if (this.doneButton != null) {
            this.doneButton.active = Nickname.normalize(value) != null;
        }
        // An empty box means the account name, which shows faintly in it
        this.box.setSuggestion(value.isEmpty() ? this.accountName : null);
    }

    private void finish(String value) {
        // The callback runs on the menu, which this dialog then returns to
        this.callback.accept(value);
        this.onClose();
    }

    @Override
    //? if <1.21.9 {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?} else {
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = GuiCompat.keyCode(event);
    //?}
        // Enter means Done from the text box; on a focused button it presses that button
        if ((keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER)
                && this.doneButton.active && (this.getFocused() == this.box || this.getFocused() == null)) {
            //? if <1.21.9 {
            this.doneButton.onPress();
            //?} else {
            this.doneButton.onPress(event);
            //?}
            return true;
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
        if (this.parent != null) {
            BlurHandler.renderParent(this.parent, graphics, partialTicks);
        }
        //? if <1.21.6 {
        graphics.flush();
        //?}
        // From 1.21.6 the blur starts a new stratum, which draws the dialog over the 3D player
        BlurHandler.renderBlur(graphics);
        graphics.fill(0, 0, this.width, this.height, 0x60000000);

        DialogFrame.draw(graphics, this.font, this.panelX, this.panelY, PANEL_WIDTH, this.panelHeight,
                DialogFrame.Accent.EDIT, DialogFrame.ICON_PENCIL, this.title);
        int centerX = this.panelX + PANEL_WIDTH / 2;
        UiText.drawCentered(graphics, this.font, Component.translatable("quickskin.nickname.message"),
                centerX, this.messageY, MESSAGE_COLOR);
        UiText.drawCentered(graphics, this.font,
                Component.translatable("quickskin.nickname.account", this.accountName),
                centerX, this.messageY + LINE_HEIGHT, MESSAGE_COLOR);
        Nickname.Problem problem = Nickname.check(this.typed);
        if (problem != Nickname.Problem.NONE) {
            UiText.drawCentered(graphics, this.font, Component.translatable(switch (problem) {
                case NO_LETTER_OR_DIGIT -> "quickskin.nickname.problem.no_letter";
                case MISPLACED_MARK -> "quickskin.nickname.problem.mark";
                case TOO_LONG -> "quickskin.nickname.problem.too_long";
                default -> "quickskin.nickname.invalid";
            }), centerX, this.hintY, HINT_COLOR);
        }

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
        // A click outside the panel cancels, like the rename dialog
        if (mouseX < this.panelX || mouseX > this.panelX + PANEL_WIDTH
                || mouseY < this.panelY || mouseY > this.panelY + this.panelHeight) {
            this.onClose();
            return true;
        }
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

    /** Takes only the characters a nickname may hold, whether typed or pasted. */
    private static final class NicknameBox extends EditBox {
        NicknameBox(Font font, int x, int y, int width, int height, Component narration) {
            super(font, x, y, width, height, narration);
        }

        @Override
        public void insertText(String text) {
            StringBuilder kept = new StringBuilder(text.length());
            text.codePoints().filter(Nickname::isAllowedCharacter).forEach(kept::appendCodePoint);
            // A refused character leaves the text, and a selection, as they were; cutting inserts ""
            if (kept.length() == 0 && !text.isEmpty()) {
                return;
            }
            super.insertText(kept.toString());
        }
    }
}
