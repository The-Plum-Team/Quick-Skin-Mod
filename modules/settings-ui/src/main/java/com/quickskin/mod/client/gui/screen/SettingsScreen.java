package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.gui.util.MenuGuiScale;
import com.quickskin.mod.client.gui.GuiCompat;
import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.effect.BlurHandler;
//? if <1.21 {
import com.quickskin.mod.client.gui.GuiCompat;
//?}
import com.quickskin.mod.client.gui.util.ButtonFactory;
import com.quickskin.mod.client.gui.util.UiText;
import com.quickskin.mod.client.gui.util.DialogFrame;
import com.quickskin.mod.client.gui.widget.TabButton;
import com.quickskin.mod.client.input.KeybindRegistry;
import com.quickskin.mod.common.data.BackgroundStyle;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Modal settings dialog for QuickSkin configuration
 * Features tabbed interface for Client and Server settings
 */
@Environment(EnvType.CLIENT)
public class SettingsScreen extends Screen {

    @Nullable
    private final Screen parent;

    // A DialogFrame with a slate header and the gear; under it the tabs in a sidebar on the left,
    // each with an icon, and the tab's settings on the right
    // A dark face for the settings, under a lighter sidebar and the slate header
    private static final int DIALOG_FACE = 0xFF26272A;
    private static final int SIDEBAR_BG = 0xFF34353A;
    private static final int SIDEBAR_EDGE = 0xFF55565B;
    /** The line between two tabs, across the sidebar. */
    private static final int TAB_RULE = 0xFF2A2B2F;
    private static final int SIDEBAR_WIDTH = 112;

    // Settings rows: the checkboxes become rows with a toggle on the right
    private static final int ROW_PADDING = 3;
    //? if <1.21.6 {
    /** How far in front of the vanilla checkboxes their rows are drawn. */
    private static final float ROW_DEPTH = 10.0f;
    //?}
    private static final int ROW_BG = DIALOG_FACE;
    private static final int ROW_HOVER = 0xFF2E2F32;
    private static final int ROW_LINE = 0xFF323336;
    private static final int ROW_TEXT = 0xFFE0E0E0;
    private static final int ROW_TEXT_HOVER = 0xFFFFFFFF;
    // Dimmer than an active row, but still clearly readable beside its greyed-out toggle
    private static final int ROW_TEXT_DISABLED = 0xFFC8C8C8;
    private static final int TOGGLE_WIDTH = 30;
    private static final int KNOB_SIZE = 14;
    private static final int TOGGLE_OUTLINE = 0xFF0E0E0F;
    private static final int TOGGLE_OFF = 0xFF1E1F21;
    private static final int TOGGLE_OFF_SHADE = 0xFF151617;
    private static final int TOGGLE_ON = 0xFF3C8A3C;
    private static final int TOGGLE_ON_HOVER = 0xFF46A046;
    private static final int TOGGLE_DISABLED = 0xFF2B2C2E;
    private static final int KNOB_FACE = 0xFF3A3B3E;
    private static final int KNOB_FACE_HOVER = 0xFF4A4B4F;
    private static final int KNOB_BORDER = 0xFF6E6F73;
    private static final int KNOB_BORDER_HOVER = 0xFF8D8E93;
    private static final int KNOB_DISABLED_BORDER = 0xFF46474A;
    private static final int KNOB_LIP = 0xFF1C1D1F;

    // The tabs fill the sidebar's width, one under another with a line between them
    private static final int TAB_HEIGHT = 26;
    private static final int TAB_SPACING = 1;

    // Seven-pixel tab icons
    private static final String[] CLIENT_ICON = {"..###..", ".#####.", "..###..", ".......", "#######", "#######", "#######"};
    private static final String[] GUI_EDIT_ICON = {"#######", "#.....#", "#.###.#", "#.....#", "#.##..#", "#.....#", "#######"};
    private static final String[] MODPACK_ICON = {"...#...", "..###..", ".#####.", "#######", ".#####.", "..###..", "...#..."};
    private static final String[] SERVER_ICON = {"#######", "#.....#", "#######", ".......", "#######", "#.....#", "#######"};

    private int dialogX;
    private int dialogY;
    private int dialogWidth = 480;
    private int dialogHeight = 280;
    /** Left edge and width of the settings, right of the sidebar. */
    private int contentX;
    private int contentWidth;
    /** The row of the server tab's notice for players who are not admins, near the bottom. */
    private int noticeY;

    private enum Tab {
        GUI_EDIT("quickskin.settings.tab.gui_edit"),
        CLIENT("quickskin.settings.tab.client"),
        SERVER("quickskin.settings.tab.server"),
        MODPACK("quickskin.settings.tab.modpack");

        private final String translationKey;

        Tab(String translationKey) {
            this.translationKey = translationKey;
        }

        public String getTranslationKey() {
            return translationKey;
        }
    }

    private Tab activeTab = Tab.CLIENT;
    private TabButton guiEditTabButton;
    private TabButton clientTabButton;
    private TabButton serverTabButton;
    private TabButton modpackTabButton;
    private final List<AbstractWidget> guiEditSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> clientSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> serverSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> modpackSettingWidgets = new ArrayList<>();

    // Client setting widgets
    private Checkbox showOverlayCheckbox;
    private Checkbox disableSkinTransparencyCheckbox;
    private Checkbox enablePlayerPreviewCustomizationCheckbox;
    private Checkbox hideBuiltInCapesCheckbox;
    private Checkbox menuBackgroundCheckbox;
    private Checkbox showTitlePreviewCheckbox;
    private Checkbox showPausePreviewCheckbox;
    private Checkbox compactChangeSkinButtonCheckbox;
    private Button keybindButton;
    private AbstractWidget keybindLabel;

    // State for keybind editing
    @Nullable
    private KeyMapping selectedKey;

    // Server setting checkboxes
    private Checkbox serverDisableSkinTransparencyCheckbox;
    private EditBox skinChangeCooldownEditBox;
    private Button playerSkinsButton;

    // --- NEW --- Modpack setting widgets
    private Checkbox enablePlayerOwnSkinSystemCheckbox;

    // The settings a menu reads when it is built, as they were when the dialog opened: a change
    // rebuilds the parent on close
    private final String openedMenuSettings;

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("quickskin.screen.settings.title"));
        this.parent = parent;
        this.openedMenuSettings = menuSettings();
    }

    @Override
    protected void init() {
        // Drawn over a menu, the dialog takes the menu's scale so the menu behind keeps its size
        if (this.minecraft != null) {
            MenuGuiScale.follow(this.minecraft, this, this.parent);
        }
        super.init();

        // Clear widget lists to prevent duplication on resize
        guiEditSettingWidgets.clear();
        clientSettingWidgets.clear();
        serverSettingWidgets.clear();
        modpackSettingWidgets.clear();

        // Make dialog responsive to screen size
        dialogWidth = Math.min(480, this.width - 40);
        dialogHeight = Math.min(280, this.height - 20);

        // Center the dialog
        dialogX = (this.width - dialogWidth) / 2;
        dialogY = (this.height - dialogHeight) / 2;

        // The tabs in the sidebar, one under another
        contentX = dialogX + SIDEBAR_WIDTH + 16;
        contentWidth = dialogWidth - SIDEBAR_WIDTH - 32;
        noticeY = DialogFrame.bodyBottom(dialogY, dialogHeight) - 22;
        int tabX = dialogX + 2;
        int tabWidth = SIDEBAR_WIDTH - 2;
        int tabStartY = DialogFrame.bodyTop(dialogY);

        clientTabButton = (TabButton) ButtonFactory.createTab(
                tabX, tabStartY,
                tabWidth, TAB_HEIGHT,
                Component.translatable(Tab.CLIENT.getTranslationKey()),
                activeTab == Tab.CLIENT,
                btn -> switchTab(Tab.CLIENT)
        );
        this.addRenderableWidget(clientTabButton);

        guiEditTabButton = (TabButton) ButtonFactory.createTab(
                tabX, tabStartY + TAB_HEIGHT + TAB_SPACING,
                tabWidth, TAB_HEIGHT,
                Component.translatable(Tab.GUI_EDIT.getTranslationKey()),
                activeTab == Tab.GUI_EDIT,
                btn -> switchTab(Tab.GUI_EDIT)
        );
        this.addRenderableWidget(guiEditTabButton);

        modpackTabButton = (TabButton) ButtonFactory.createTab(
                tabX, tabStartY + (TAB_HEIGHT + TAB_SPACING) * 2,
                tabWidth, TAB_HEIGHT,
                Component.translatable(Tab.MODPACK.getTranslationKey()),
                activeTab == Tab.MODPACK,
                btn -> switchTab(Tab.MODPACK)
        );
        this.addRenderableWidget(modpackTabButton);

        serverTabButton = (TabButton) ButtonFactory.createTab(
                tabX, tabStartY + (TAB_HEIGHT + TAB_SPACING) * 3,
                tabWidth, TAB_HEIGHT,
                Component.translatable(Tab.SERVER.getTranslationKey()),
                activeTab == Tab.SERVER,
                btn -> switchTab(Tab.SERVER)
        );
        this.addRenderableWidget(serverTabButton);
        clientTabButton.setIcon(CLIENT_ICON);
        guiEditTabButton.setIcon(GUI_EDIT_ICON);
        modpackTabButton.setIcon(MODPACK_ICON);
        serverTabButton.setIcon(SERVER_ICON);
        for (TabButton tab : List.of(clientTabButton, guiEditTabButton, modpackTabButton, serverTabButton)) {
            tab.setNotchColor(DIALOG_FACE);
        }

        // Create settings for all tabs
        createGuiEditSettings();
        createClientSettings();
        createServerSettings();
        createModpackSettings();

        // The settings apply as they change, so the dialog closes from an X in its header
        Component closeLabel = Component.translatable("quickskin.button.close");
        Button closeButton = new com.quickskin.mod.client.gui.widget.DialogCloseButton(
                dialogX + dialogWidth - 6 - com.quickskin.mod.client.gui.widget.DialogCloseButton.SIZE,
                dialogY + (DialogFrame.HEADER_HEIGHT - com.quickskin.mod.client.gui.widget.DialogCloseButton.SIZE) / 2,
                closeLabel,
                btn -> this.onClose()
        );
        closeButton.setTooltip(Tooltip.create(closeLabel));
        this.addRenderableWidget(closeButton);

        // Show initial tab
        switchTab(activeTab);
    }

    /**
     * Builds a settings checkbox that runs {@code onChange} every time the user toggles it, so the
     * setting takes effect immediately instead of waiting for the dialog to close. It spans the
     * row, which {@link #drawToggleRow} draws over it: a click anywhere on the row toggles it.
     */
    private Checkbox checkbox(int x, int y, String translationKey, boolean selected,
                              @Nullable String tooltipKey, Runnable onChange) {
        Component label = Component.translatable(translationKey);
        //? if <1.21 {
        Checkbox checkbox = new Checkbox(x, y, contentWidth, 20, label, selected) {
            @Override
            public void onPress() {
                super.onPress();
                onChange.run();
            }
        };
        if (tooltipKey != null) {
            checkbox.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        }
        return checkbox;
        //?} else {
        // The vanilla checkbox draws the text it was built with, on one line that can run past the
        // row's right edge, so it is built without one; its message is the row's label
        Checkbox.Builder builder = Checkbox.builder(Component.empty(), this.font)
                .pos(x, y)
                .selected(selected)
                .onValueChange((box, value) -> onChange.run());
        if (tooltipKey != null) {
            builder.tooltip(Tooltip.create(Component.translatable(tooltipKey)));
        }
        Checkbox checkbox = builder.build();
        checkbox.setMessage(label);
        // The whole row toggles it, as drawToggleRow shows
        checkbox.setWidth(contentWidth);
        return checkbox;
        //?}
    }


    private void createGuiEditSettings() {
        ClientConfig config = ClientConfig.getInstance();
        int spacing = 30;
        // Settings content area starts below tabs
        int startY = DialogFrame.bodyTop(dialogY) + 14;
        int leftColumnX = contentX;
        int currentY = startY;

        showOverlayCheckbox = checkbox(leftColumnX, currentY,
                "quickskin.settings.enable_preview",
                config.showSkinPreviewOverlay,
                "quickskin.settings.enable_preview.tooltip", this::applyClientSettings);
        guiEditSettingWidgets.add(showOverlayCheckbox);
        currentY += spacing;

        enablePlayerPreviewCustomizationCheckbox = checkbox(leftColumnX, currentY,
                "quickskin.settings.enable_preview_custom",
                config.enablePlayerPreviewCustomization,
                "quickskin.settings.enable_preview_custom.tooltip", this::applyClientSettings);
        guiEditSettingWidgets.add(enablePlayerPreviewCustomizationCheckbox);
        currentY += spacing;

        hideBuiltInCapesCheckbox = checkbox(leftColumnX, currentY,
                "quickskin.settings.hide_builtin_capes",
                config.hideBuiltInCapes,
                "quickskin.settings.hide_builtin_capes.tooltip", this::applyClientSettings);
        guiEditSettingWidgets.add(hideBuiltInCapesCheckbox);
        currentY += spacing;

        menuBackgroundCheckbox = checkbox(leftColumnX, currentY,
                "quickskin.settings.menu_background",
                config.getMenuBackgroundStyle() == BackgroundStyle.VANILLA_BLUR,
                "quickskin.settings.menu_background.tooltip", this::applyClientSettings);
        guiEditSettingWidgets.add(menuBackgroundCheckbox);
    }

    private void createClientSettings() {
        ClientConfig config = ClientConfig.getInstance();
        int spacing = 30;
        // Settings content area starts below tabs
        int startY = DialogFrame.bodyTop(dialogY) + 14;
        int leftColumnX = contentX;
        int currentLeftY = startY;
        // The checkboxes go under the keybind, in the same column
        int rightColumnX = leftColumnX;
        int currentRightY = startY + spacing;

        // Left Column
        // Keybind button and label
        int keybindButtonWidth = 75;
        int keybindButtonSpacing = 5;

        // Minecraft's own button, as in its Controls screen, so it reads as a key binding
        keybindButton = Button.builder(KeybindRegistry.OPEN_SKIN_MENU.getTranslatedKeyMessage(),
                        button -> this.selectedKey = KeybindRegistry.OPEN_SKIN_MENU)
                .bounds(leftColumnX + contentWidth - keybindButtonWidth, currentLeftY, keybindButtonWidth, 20)
                .build();
        clientSettingWidgets.add(keybindButton);

        keybindLabel = new AbstractWidget(leftColumnX, currentLeftY, contentWidth - keybindButtonWidth - keybindButtonSpacing, 20, keybindLabelText()) {
            @Override
            //? if <26.1 {
            public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            //?} else {
            public void extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
            //?}
                drawRowLabel(guiGraphics, this.getMessage().getString(), this.getX(), this.getWidth(),
                        this.getY() + this.height / 2, ROW_TEXT);
            }

            @Override
            public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
                narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, this.getMessage());
            }

            @Override
            //? if <1.21.9 {
            public boolean mouseClicked(double pMouseX, double pMouseY, int pButton) {
            //?} else {
            public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
            //?}
                return false; // Make the label non-interactive
            }
        };
        clientSettingWidgets.add(keybindLabel);

        // Under it, the checkboxes
        disableSkinTransparencyCheckbox = checkbox(rightColumnX, currentRightY,
                "quickskin.settings.disable_transparency",
                config.disableSkinTransparency,
                "quickskin.settings.disable_transparency.tooltip", this::applyClientSettings);
        clientSettingWidgets.add(disableSkinTransparencyCheckbox);
        currentRightY += spacing;

        showTitlePreviewCheckbox = checkbox(rightColumnX, currentRightY,
                "quickskin.settings.show_title_preview",
                config.showTitleScreenPreview,
                "quickskin.settings.show_title_preview.tooltip", this::applyClientSettings);
        clientSettingWidgets.add(showTitlePreviewCheckbox);
        currentRightY += spacing;

        showPausePreviewCheckbox = checkbox(rightColumnX, currentRightY,
                "quickskin.settings.show_pause_preview",
                config.showPauseMenuPreview,
                "quickskin.settings.show_pause_preview.tooltip", this::applyClientSettings);
        clientSettingWidgets.add(showPausePreviewCheckbox);
        currentRightY += spacing;

        compactChangeSkinButtonCheckbox = checkbox(rightColumnX, currentRightY,
                "quickskin.settings.compact_change_skin_button",
                config.compactChangeSkinButton,
                "quickskin.settings.compact_change_skin_button.tooltip", this::applyClientSettings);
        clientSettingWidgets.add(compactChangeSkinButtonCheckbox);
    }

    private void createServerSettings() {
        ServerConfig config = ServerConfig.getInstance();
        int spacing = 30;
        // Settings content area starts below tabs
        int startY = DialogFrame.bodyTop(dialogY) + 14;
        int leftColumnX = contentX;
        int currentY = startY;

        // Check if player has admin permissions
        //? if <1.21.11 {
        boolean isAdmin = minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2);
        //?} else {
        boolean isAdmin = minecraft != null && minecraft.player != null && minecraft.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        //?}

        // Get current server transparency setting from server override
        // This is synced from the server on join (both singleplayer and multiplayer)
        ServerConfig serverOverride = ClientConfig.getInstance().getServerOverride();
        boolean currentTransparencySetting = serverOverride != null ? serverOverride.disableSkinTransparency : false;

        serverDisableSkinTransparencyCheckbox = checkbox(leftColumnX, currentY,
                "quickskin.settings.disable_transparency_server",
                currentTransparencySetting,
                "quickskin.settings.disable_transparency_server.tooltip", this::applyServerTransparency);
        // Only allow admins to change this setting
        serverDisableSkinTransparencyCheckbox.active = isAdmin;
        serverSettingWidgets.add(serverDisableSkinTransparencyCheckbox);
        currentY += spacing;

        // Skin Change Cooldown Setting
        int editBoxWidth = 60;
        int editBoxSpacing = 5;

        skinChangeCooldownEditBox = new EditBox(
                this.font,
                leftColumnX + contentWidth - editBoxWidth - 1, currentY,
                editBoxWidth, 20,
                Component.translatable("quickskin.settings.cooldown_label")
        );
        skinChangeCooldownEditBox.setValue(String.valueOf(config.skinChangeCooldownSeconds));
        skinChangeCooldownEditBox.setMaxLength(5);
        //? if <26.1 {
        skinChangeCooldownEditBox.setFilter(text -> text.isEmpty() || text.matches("\\d+"));
        //?}
        skinChangeCooldownEditBox.active = isAdmin;
        skinChangeCooldownEditBox.setTooltip(Tooltip.create(Component.translatable("quickskin.settings.cooldown_seconds.tooltip")));
        serverSettingWidgets.add(skinChangeCooldownEditBox);

        // Label for cooldown EditBox
        serverSettingWidgets.add(rowLabel(leftColumnX, currentY, contentWidth - editBoxWidth - editBoxSpacing - 1,
                Component.translatable("quickskin.settings.cooldown_seconds"), () -> skinChangeCooldownEditBox.active));
        currentY += spacing;

        // Player skins: the panel where an admin gives players a skin for now (ADR 0016), on a
        // server that lets this player hand them out
        int openWidth = 60;
        boolean handsOutSkins = isAdmin
                && com.quickskin.mod.client.api.ClientNetworkApi.actions().skinAdminView() != null;
        playerSkinsButton = DialogFrame.button(leftColumnX + contentWidth - openWidth - 1, currentY,
                openWidth, 20, Component.translatable("quickskin.settings.player_skins.open"), button -> openPlayerSkins());
        playerSkinsButton.active = handsOutSkins;
        playerSkinsButton.setTooltip(Tooltip.create(Component.translatable(
                handsOutSkins ? "quickskin.tooltip.player_skins" : "quickskin.player_skins.unavailable")));
        serverSettingWidgets.add(playerSkinsButton);
        serverSettingWidgets.add(rowLabel(leftColumnX, currentY, contentWidth - openWidth - editBoxSpacing - 1,
                Component.translatable("quickskin.button.player_skins"), () -> playerSkinsButton.active));
    }

    /** A row's label beside the control on its right, dimmed while that control is inactive. */
    private AbstractWidget rowLabel(int x, int y, int width, Component label, BooleanSupplier controlActive) {
        return new AbstractWidget(x, y, width, 20, label) {
            @Override
            //? if <26.1 {
            public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            //?} else {
            public void extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
            //?}
                drawRowLabel(guiGraphics, this.getMessage().getString(), this.getX(), this.getWidth(),
                        this.getY() + this.height / 2, controlActive.getAsBoolean() ? ROW_TEXT : ROW_TEXT_DISABLED);
            }

            @Override
            public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
                narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, this.getMessage());
            }

            @Override
            //? if <1.21.9 {
            public boolean mouseClicked(double pMouseX, double pMouseY, int pButton) {
            //?} else {
            public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
            //?}
                return false;
            }
        };
    }

    /**
     * Opens the panel where an admin gives players a skin for now, over this dialog, which it
     * returns to; the cooldown typed so far is applied first, since the dialog is built again.
     */
    private void openPlayerSkins() {
        if (minecraft == null) {
            return;
        }
        applyCooldownSetting();
        //? if <26.2 {
        minecraft.setScreen(new PlayerSkinsScreen(this));
        //?} else {
        minecraft.gui.setScreen(new PlayerSkinsScreen(this));
        //?}
    }

    private void createModpackSettings() {
        ClientConfig config = ClientConfig.getInstance();
        // Settings content area starts below tabs
        int startY = DialogFrame.bodyTop(dialogY) + 14;
        int leftColumnX = contentX;

        enablePlayerOwnSkinSystemCheckbox = checkbox(leftColumnX, startY,
                "quickskin.settings.enable_own_skin",
                config.enablePlayerOwnSkinSystem,
                "quickskin.settings.own_skin_tooltip", this::applyClientSettings);
        modpackSettingWidgets.add(enablePlayerOwnSkinSystemCheckbox);
    }

    private void switchTab(Tab tab) {
        activeTab = tab;

        // Remove all setting widgets
        for (AbstractWidget widget : guiEditSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : clientSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : serverSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : modpackSettingWidgets) {
            this.removeWidget(widget);
        }

        // Add widgets for active tab
        List<AbstractWidget> activeWidgets = switch (tab) {
            case GUI_EDIT -> guiEditSettingWidgets;
            case CLIENT -> clientSettingWidgets;
            case SERVER -> serverSettingWidgets;
            case MODPACK -> modpackSettingWidgets;
        };
        for (AbstractWidget widget : activeWidgets) {
            this.addRenderableWidget(widget);
        }

        // Update tab button selected states (visual feedback)
        guiEditTabButton.setSelected(tab == Tab.GUI_EDIT);
        clientTabButton.setSelected(tab == Tab.CLIENT);
        serverTabButton.setSelected(tab == Tab.SERVER);
        modpackTabButton.setSelected(tab == Tab.MODPACK);
    }

    @Override
    //? if <26.1 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Render parent screen in background
    //?}
        if (this.parent != null) {
            BlurHandler.renderParent(this.parent, graphics, partialTick);
        }

        //? if <1.21.6 {
        graphics.flush();
        //?}
        // From 1.21.6 the blur starts a new stratum, which draws the dialog over the 3D player
        // widget; the GUI is drawn later by the render backend, which may not be OpenGL
        BlurHandler.renderBlur(graphics);

        // Update keybind button text before rendering
        if (this.selectedKey == KeybindRegistry.OPEN_SKIN_MENU) {
            this.keybindButton.setMessage(Component.literal("> ").append(Component.literal("???").withStyle(ChatFormatting.YELLOW)).append(" <"));
        } else {
            this.keybindButton.setMessage(KeybindRegistry.OPEN_SKIN_MENU.getTranslatedKeyMessage());
        }
        this.keybindLabel.setMessage(keybindLabelText());
        //? if <1.21.6 {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 100); // Move modal forward in Z
        //?}

        // Draw darker overlay over entire screen
        graphics.fill(0, 0, this.width, this.height, 0x60000000);

        DialogFrame.draw(graphics, this.font, dialogX, dialogY, dialogWidth, dialogHeight,
                DialogFrame.Accent.SETTINGS, DialogFrame.ICON_GEAR, this.title, DIALOG_FACE);

        // The sidebar behind the tabs, from under the header to the lip
        int sidebarTop = DialogFrame.bodyTop(dialogY);
        int sidebarBottom = DialogFrame.bodyBottom(dialogY, dialogHeight);
        graphics.fill(dialogX + 2, sidebarTop, dialogX + SIDEBAR_WIDTH, sidebarBottom, SIDEBAR_BG);
        graphics.fill(dialogX + SIDEBAR_WIDTH, sidebarTop, dialogX + SIDEBAR_WIDTH + 1, sidebarBottom, SIDEBAR_EDGE);
        for (int i = 1; i < Tab.values().length; i++) {
            int ruleY = sidebarTop + i * (TAB_HEIGHT + TAB_SPACING) - TAB_SPACING;
            graphics.fill(dialogX + 2, ruleY, dialogX + SIDEBAR_WIDTH, ruleY + TAB_SPACING, TAB_RULE);
        }

        // A line under each row that is not a checkbox: the keybind, the cooldown and player skins
        for (AbstractWidget widget : activeTabWidgets()) {
            if (widget == keybindButton || widget == skinChangeCooldownEditBox || widget == playerSkinsButton) {
                int lineY = widget.getY() + widget.getHeight() + ROW_PADDING;
                graphics.fill(contentX - ROW_PADDING * 2, lineY, contentX + contentWidth + ROW_PADDING * 2, lineY + 1, ROW_LINE);
            }
        }

        // Render widgets (buttons, tabs, etc.) - this ensures they render AFTER everything above.
        // Each row draws its checkbox's label itself, on two lines when it needs them, but the
        // vanilla checkbox would draw it on one, running past the row's right edge, so its
        // message is empty while it draws and is put back for narration
        java.util.Map<Checkbox, Component> labels = new java.util.HashMap<>();
        for (AbstractWidget widget : activeTabWidgets()) {
            if (widget instanceof Checkbox checkbox) {
                labels.put(checkbox, checkbox.getMessage());
                checkbox.setMessage(Component.empty());
            }
        }
        //? if <26.1 {
        super.render(graphics, mouseX, mouseY, partialTick);
        //?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        //?}
        labels.forEach(Checkbox::setMessage);

        // Each checkbox as a row with a toggle, drawn over the vanilla checkbox under it. Text is
        // drawn a little in front of fills at the same depth, so before 1.21.6 the rows move
        // forward to cover the vanilla label; from 1.21.6 a later stratum draws over everything
        //? if <1.21.6 {
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, ROW_DEPTH);
        //?} else {
        graphics.nextStratum();
        //?}
        for (AbstractWidget widget : activeTabWidgets()) {
            if (widget instanceof Checkbox checkbox) {
                drawToggleRow(graphics, checkbox);
            }
        }
        //? if <1.21.6 {
        graphics.flush();
        graphics.pose().popPose();
        //?}

        // Render "Admin-Only" notice for Server tab if not admin (render last to ensure it's on top)
        if (activeTab == Tab.SERVER && minecraft != null) {
            //? if <1.21.11 {
            boolean isAdmin = minecraft.player != null && minecraft.player.hasPermissions(2);
            //?} else {
            boolean isAdmin = minecraft.player != null && minecraft.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
            //?}

            if (!isAdmin) {
                Component notice = Component.translatable("quickskin.settings.server_notice");
                int noticeWidth = this.font.width(notice);
                UiText.draw(graphics, this.font, notice, contentX + (contentWidth - noticeWidth) / 2, noticeY, 0xFFFFCC00);
            }
        }

        //? if <1.21.6 {
        graphics.pose().popPose();
        //?}
    }

    private List<AbstractWidget> activeTabWidgets() {
        return switch (activeTab) {
            case GUI_EDIT -> guiEditSettingWidgets;
            case CLIENT -> clientSettingWidgets;
            case SERVER -> serverSettingWidgets;
            case MODPACK -> modpackSettingWidgets;
        };
    }

    /**
     * A checkbox as a settings row: the label on the left, a toggle on the right (a sunk track,
     * green while on, under a raised knob that slides to the side it is set to), a line under it,
     * and a lighter row under the mouse. An inactive row is greyed out.
     */
    //? if <26.1 {
    private void drawToggleRow(GuiGraphics graphics, Checkbox checkbox) {
    //?} else {
    private void drawToggleRow(GuiGraphicsExtractor graphics, Checkbox checkbox) {
    //?}
        boolean on = checkbox.selected();
        boolean enabled = checkbox.active;
        boolean hovered = enabled && checkbox.isHovered();
        int left = contentX - ROW_PADDING * 2;
        int right = contentX + contentWidth + ROW_PADDING * 2;
        int top = checkbox.getY() - ROW_PADDING;
        int bottom = checkbox.getY() + checkbox.getHeight() + ROW_PADDING;
        graphics.fill(left, top, right, bottom, hovered ? ROW_HOVER : ROW_BG);
        graphics.fill(left, bottom, right, bottom + 1, ROW_LINE);

        // The toggle, right-aligned
        int trackRight = contentX + contentWidth;
        int trackLeft = trackRight - TOGGLE_WIDTH;
        int middle = checkbox.getY() + checkbox.getHeight() / 2;
        int trackTop = middle - 7;
        int trackBottom = middle + 7;
        graphics.fill(trackLeft, trackTop, trackRight, trackBottom, TOGGLE_OUTLINE);
        int track = !enabled ? TOGGLE_DISABLED : on ? (hovered ? TOGGLE_ON_HOVER : TOGGLE_ON) : TOGGLE_OFF;
        graphics.fill(trackLeft + 1, trackTop + 1, trackRight - 1, trackBottom - 1, track);
        if (!on && enabled) {
            graphics.fill(trackLeft + 1, trackTop + 1, trackRight - 1, trackTop + 3, TOGGLE_OFF_SHADE);
        }
        int knobLeft = on ? trackRight - KNOB_SIZE : trackLeft;
        int knobTop = middle - KNOB_SIZE / 2 - 1;
        int knobBottom = knobTop + KNOB_SIZE + 2;
        graphics.fill(knobLeft, knobTop, knobLeft + KNOB_SIZE, knobBottom, TOGGLE_OUTLINE);
        graphics.fill(knobLeft + 1, knobBottom - 3, knobLeft + KNOB_SIZE - 1, knobBottom - 1, KNOB_LIP);
        graphics.fill(knobLeft + 1, knobTop + 1, knobLeft + KNOB_SIZE - 1, knobBottom - 3,
                !enabled ? KNOB_DISABLED_BORDER : hovered ? KNOB_BORDER_HOVER : KNOB_BORDER);
        graphics.fill(knobLeft + 2, knobTop + 2, knobLeft + KNOB_SIZE - 2, knobBottom - 4,
                !enabled ? TOGGLE_DISABLED : hovered ? KNOB_FACE_HOVER : KNOB_FACE);

        // The label, in the room left of the toggle
        int color = !enabled ? ROW_TEXT_DISABLED : hovered ? ROW_TEXT_HOVER : ROW_TEXT;
        drawRowLabel(graphics, checkbox.getMessage().getString(), contentX, trackLeft - 8 - contentX, middle, color);
    }

    /**
     * A row's label, vertically centred on {@code middle}: on one line when it fits in
     * {@code room}, else on two, the second cut short if it still does not fit.
     */
    //? if <26.1 {
    private void drawRowLabel(GuiGraphics graphics, String label, int x, int room, int middle, int color) {
    //?} else {
    private void drawRowLabel(GuiGraphicsExtractor graphics, String label, int x, int room, int middle, int color) {
    //?}
        if (this.font.width(label) <= room) {
            UiText.draw(graphics, this.font, label, x, middle - 4, color);
            return;
        }
        // Break at the last space that keeps the first line within the room
        String first = this.font.plainSubstrByWidth(label, room);
        int space = first.lastIndexOf(' ');
        if (space > 0 && first.length() < label.length()) {
            first = first.substring(0, space);
        }
        String second = label.substring(first.length()).trim();
        if (this.font.width(second) > room) {
            second = this.font.plainSubstrByWidth(second, Math.max(0, room - this.font.width("..."))) + "...";
        }
        UiText.draw(graphics, this.font, first, x, middle - 9, color);
        UiText.draw(graphics, this.font, second, x, middle + 1, color);
    }

    /** "Assign a key…" while the skin menu has no key, else "Change the key…". */
    private static Component keybindLabelText() {
        return Component.translatable(KeybindRegistry.OPEN_SKIN_MENU.isUnbound()
                ? "quickskin.settings.keybind_assign" : "quickskin.settings.keybind_change");
    }

    /** The settings a menu reads when it is built, so a change can rebuild the menu behind. */
    private static String menuSettings() {
        ClientConfig config = ClientConfig.getInstance();
        return config.hideBuiltInCapes + "," + config.enablePlayerPreviewCustomization + ","
                + config.getMenuBackgroundStyle();
    }

    /**
     * Rebuilds a menu so it applies the settings it reads when it is built.
     */
    private Screen recreateScreen(Screen screen) {
        if (screen instanceof com.quickskin.mod.client.gui.RestylableScreen restylable) {
            return restylable.recreateForStyleChange();
        }
        // For other screens, return the original
        return screen;
    }

    @Override
    //? if <1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
    //?}
        // Handle setting a keybind with a mouse click
        if (this.selectedKey != null) {
            this.selectedKey.setKey(InputConstants.Type.MOUSE.getOrCreate(button));
            KeyMapping.resetMapping();
            this.selectedKey = null;
            return true;
        }

        // Click outside dialog (including tabs) = close
        if (mouseX < dialogX || mouseX > dialogX + dialogWidth ||
                mouseY < dialogY || mouseY > dialogY + dialogHeight) {
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
    //? if <1.21.9 {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    //?} else {
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
    //?}
        // Prevent parent screen interactions
        return false;
    }

    @Override
    //? if <1.21 {
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
    //?} else {
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
    //?}
        // Allow scrolling only within modal
        if (mouseX >= dialogX && mouseX <= dialogX + dialogWidth &&
                mouseY >= dialogY && mouseY <= dialogY + dialogHeight) {
            //? if <1.21 {
            return super.mouseScrolled(mouseX, mouseY, delta);
            //?} else {
            return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
            //?}
        }
        return false;
    }

    @Override
    //? if <1.21.9 {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?} else {
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = GuiCompat.keyCode(event);
    //?}
        // Handle setting a keybind with a keyboard press
        if (this.selectedKey != null) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                this.selectedKey.setKey(InputConstants.UNKNOWN);
            } else {
                //? if <1.21.9 {
                this.selectedKey.setKey(InputConstants.getKey(keyCode, scanCode));
                //?} else {
                this.selectedKey.setKey(InputConstants.getKey(event));
                //?}
            }
            KeyMapping.resetMapping();
            this.selectedKey = null;
            return true;
        }

        // ESC key closes dialog
        if (keyCode == InputConstants.KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        //? if <1.21.9 {
        return super.keyPressed(keyCode, scanCode, modifiers);
        //?} else {
        return super.keyPressed(event);
        //?}
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause game
    //? if >=1.21.2 {
        //? if <1.21.6 {
    }

    @Override
    protected void renderBlurredBackground() {
        // Disable the default Minecraft blur effect - we handle blur manually
        //?} else if <26.1 {
    }

    @Override
    protected void renderBlurredBackground(net.minecraft.client.gui.GuiGraphics guiGraphics) {
        // Disable the default Minecraft blur effect - we handle blur manually
        //?}
    //?}
    //? if >=26.1 {
    }

    @Override
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default background (panorama) - we render the parent screen's background manually
    }

    @Override
    protected void extractBlurredBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics) {
        // Disable the default Minecraft blur effect - we handle blur manually
    //?}
    }

    // Minecraft 1.21.1 calls renderBackground() from Screen.render() before it renders widgets.
    // This screen has already drawn and blurred its parent by then, so allowing that second
    // background pass would blur the modal panel while leaving the later widgets sharp.
    //? if >=1.21 {
        //? if <1.21.2 {
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // The modal owns its complete background pass.
    }
        //?}
    //?}

    @Override
    public void removed() {
        if (this.minecraft != null) {
            MenuGuiScale.leave(this.minecraft, this);
        }
        super.removed();
        // Cleanup blur resources
        BlurHandler.cleanup();
    }

    private boolean isAdmin() {
        //? if <1.21.11 {
        return minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2);
        //?} else {
        return minecraft != null && minecraft.player != null && minecraft.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        //?}
    }

    /**
     * Copies every client checkbox into {@link ClientConfig} and saves it. Runs on each toggle, so
     * a setting takes effect without closing the dialog, and once more on close.
     */
    private void applyClientSettings() {
        if (showOverlayCheckbox == null) {
            return;
        }
        ClientConfig config = ClientConfig.getInstance();
        boolean oldTransparencySetting = config.disableSkinTransparency;

        config.showSkinPreviewOverlay = showOverlayCheckbox.selected();
        config.disableSkinTransparency = disableSkinTransparencyCheckbox.selected();
        config.enablePlayerPreviewCustomization = enablePlayerPreviewCustomizationCheckbox.selected();
        config.enablePlayerOwnSkinSystem = enablePlayerOwnSkinSystemCheckbox.selected();
        config.hideBuiltInCapes = hideBuiltInCapesCheckbox.selected();
        config.showTitleScreenPreview = showTitlePreviewCheckbox.selected();
        config.showPauseMenuPreview = showPausePreviewCheckbox.selected();
        config.compactChangeSkinButton = compactChangeSkinButtonCheckbox.selected();
        config.setMenuBackgroundStyle(menuBackgroundCheckbox.selected()
                ? BackgroundStyle.VANILLA_BLUR : BackgroundStyle.OPAQUE_STARS);
        config.save();

        if (oldTransparencySetting != config.disableSkinTransparency) {
            com.quickskin.mod.client.services.PlayerAppearanceService.getInstance().reloadSkinsForTransparencyChange();
        }
    }

    /**
     * Sends the server transparency checkbox to the server as soon as an admin toggles it. The
     * server broadcasts the change to every client, including this one, which then reloads.
     */
    private void applyServerTransparency() {
        if (!isAdmin()) {
            return;
        }
        com.quickskin.mod.client.api.ClientNetworkApi.actions().updateServerConfig(
                "disableSkinTransparency", serverDisableSkinTransparencyCheckbox.selected());
    }

    private void applyCooldownSetting() {
        if (!isAdmin() || skinChangeCooldownEditBox == null || skinChangeCooldownEditBox.getValue().isEmpty()) {
            return;
        }
        try {
            int cooldownSeconds = Integer.parseInt(skinChangeCooldownEditBox.getValue());
            if (cooldownSeconds >= 0 && cooldownSeconds <= 86400) { // Max 24 hours
                ServerConfig config = ServerConfig.getInstance();
                config.skinChangeCooldownSeconds = cooldownSeconds;
                config.save();
            }
        } catch (NumberFormatException e) {
            // Invalid input, keep existing value
        }
    }

    @Override
    public void onClose() {
        // If we were editing a keybind, cancel it and save changes
        if(this.selectedKey != null) {
            KeyMapping.resetMapping();
            this.selectedKey = null;
        }

        // Execute any pending transparency reload from server config changes
        com.quickskin.mod.client.api.ClientNetworkApi.actions().flushPendingTransparencyReload();

        // Checkboxes already applied themselves when toggled; this catches any state set without a
        // press. The cooldown field is still applied on close.
        applyClientSettings();
        applyCooldownSetting();

        if (minecraft == null) {
            return;
        }
        // A menu reads some settings only when it is built, so a change to them rebuilds the
        // parent on close
        Screen target = parent;
        if (parent != null && !menuSettings().equals(openedMenuSettings)) {
            target = recreateScreen(parent);
        }
        //? if <26.2 {
        minecraft.setScreen(target);
        //?} else {
        minecraft.gui.setScreen(target);
        //?}
    }
}
