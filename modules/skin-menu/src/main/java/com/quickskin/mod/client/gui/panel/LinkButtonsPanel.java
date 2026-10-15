package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.widget.FieldLinkButton;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/**
 * The skin menu's link buttons, in a row at its top right on the search row, left to right:
 * Modrinth, CurseForge, Discord and Settings. They are drawn flat like the Search button.
 */
public class LinkButtonsPanel extends AbstractWidget {

    /** The gap between the buttons, as between the search field and its button. */
    private static final int SPACING = 3;
    private static final int BUTTON_SIZE = ActionButtonsPanel.ICON_BUTTON_SIZE;

    // Icon textures
    //? if <1.21.11 {
        //? if <1.21 {
    private static final ResourceLocation DISCORD_ICON = new ResourceLocation("quickskin", "textures/gui/discord_icon.png");
    private static final ResourceLocation CURSEFORGE_ICON = new ResourceLocation("quickskin", "textures/gui/curseforge_icon.png");
    private static final ResourceLocation MODRINTH_ICON = new ResourceLocation("quickskin", "textures/gui/modrinth_icon.png");
    private static final ResourceLocation SETTINGS_ICON = new ResourceLocation("quickskin", "textures/gui/settings_icon.png");
        //?} else {
    private static final ResourceLocation DISCORD_ICON = ResourceLocation.fromNamespaceAndPath("quickskin", "textures/gui/discord_icon.png");
    private static final ResourceLocation CURSEFORGE_ICON = ResourceLocation.fromNamespaceAndPath("quickskin", "textures/gui/curseforge_icon.png");
    private static final ResourceLocation MODRINTH_ICON = ResourceLocation.fromNamespaceAndPath("quickskin", "textures/gui/modrinth_icon.png");
    private static final ResourceLocation SETTINGS_ICON = ResourceLocation.fromNamespaceAndPath("quickskin", "textures/gui/settings_icon.png");
        //?}
    //?} else {
    private static final Identifier DISCORD_ICON = Identifier.fromNamespaceAndPath("quickskin", "textures/gui/discord_icon.png");
    private static final Identifier CURSEFORGE_ICON = Identifier.fromNamespaceAndPath("quickskin", "textures/gui/curseforge_icon.png");
    private static final Identifier MODRINTH_ICON = Identifier.fromNamespaceAndPath("quickskin", "textures/gui/modrinth_icon.png");
    private static final Identifier SETTINGS_ICON = Identifier.fromNamespaceAndPath("quickskin", "textures/gui/settings_icon.png");
    //?}

    // URLs
    private static final String DISCORD_URL = "https://discord.gg/yGxdvA7qej";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/mc-mods/quick-skin";
    private static final String MODRINTH_URL = "https://modrinth.com/mod/quick-skin";

    /** The row's width. */
    public static int rowWidth() {
        return BUTTON_SIZE * 4 + SPACING * 3;
    }

    public LinkButtonsPanel(int x, int y) {
        super(x, y, rowWidth(), BUTTON_SIZE, Component.empty());
    }

    /**
     * Initialize the panel and create all child widgets, left to right: Modrinth, CurseForge,
     * Discord, Settings
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen) {
        int y = getY();
        int currentX = getX();

        screen.registerForegroundControl(new FieldLinkButton(
            currentX,
            y,
            BUTTON_SIZE,
            MODRINTH_ICON,
            MODRINTH_URL,
            Component.translatable("quickskin.button.modrinth")
        ));

        currentX += BUTTON_SIZE + SPACING;
        screen.registerForegroundControl(new FieldLinkButton(
            currentX,
            y,
            BUTTON_SIZE,
            CURSEFORGE_ICON,
            CURSEFORGE_URL,
            Component.translatable("quickskin.button.curseforge")
        ));

        currentX += BUTTON_SIZE + SPACING;
        screen.registerForegroundControl(new FieldLinkButton(
            currentX,
            y,
            BUTTON_SIZE,
            DISCORD_ICON,
            DISCORD_URL,
            Component.translatable("quickskin.button.discord")
        ));

        currentX += BUTTON_SIZE + SPACING;
        screen.registerForegroundControl(new FieldLinkButton(
            currentX,
            y,
            BUTTON_SIZE,
            SETTINGS_ICON,
            null, // No URL, will be handled differently
            Component.translatable("quickskin.button.settings")
        ) {
            @Override
            //? if <1.21.9 {
            public void onPress() {
            //?} else {
            public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            //?}
                // Open settings screen
                //? if <26.2 {
                Minecraft.getInstance().setScreen(
                //?} else {
                Minecraft.getInstance().gui.setScreen(
                //?}
                    new com.quickskin.mod.client.gui.screen.SettingsScreen(screen)
                );
            }
        });
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
