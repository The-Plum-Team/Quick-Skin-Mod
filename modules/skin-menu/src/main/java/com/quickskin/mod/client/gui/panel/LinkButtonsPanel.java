package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.widget.LinkButton;
import net.minecraft.client.Minecraft;
//? if <26.1.2 {
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
 * Panel that manages the link buttons in the top-right corner
 * (Modrinth, CurseForge, Discord, Settings)
 */
public class LinkButtonsPanel extends AbstractWidget {

    private static final int SPACING = 4;
    private static final int BUTTON_SIZE = 20;

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

    public LinkButtonsPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    /**
     * Initialize the panel and create all child widgets
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen) {
        int linkButtonY = getY();
        int currentX = getX() + width - BUTTON_SIZE; // Start from right edge

        // Settings button (far right)
        screen.registerWidget(new LinkButton(
            currentX,
            linkButtonY,
            BUTTON_SIZE,
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
                screen.setOpeningSubScreen(true);
                //? if <26.2 {
                Minecraft.getInstance().setScreen(
                //?} else {
                Minecraft.getInstance().gui.setScreen(
                //?}
                    new com.quickskin.mod.client.gui.screen.SettingsScreen(screen)
                );
            }
        });

        // Discord button (left of settings)
        currentX -= (BUTTON_SIZE + SPACING);
        screen.registerWidget(new LinkButton(
            currentX,
            linkButtonY,
            BUTTON_SIZE,
            BUTTON_SIZE,
            DISCORD_ICON,
            DISCORD_URL,
            Component.translatable("quickskin.button.discord")
        ));

        // CurseForge button (left of Discord)
        currentX -= (BUTTON_SIZE + SPACING);
        screen.registerWidget(new LinkButton(
            currentX,
            linkButtonY,
            BUTTON_SIZE,
            BUTTON_SIZE,
            CURSEFORGE_ICON,
            CURSEFORGE_URL,
            Component.translatable("quickskin.button.curseforge")
        ));

        // Modrinth button (left of CurseForge)
        currentX -= (BUTTON_SIZE + SPACING);
        screen.registerWidget(new LinkButton(
            currentX,
            linkButtonY,
            BUTTON_SIZE,
            BUTTON_SIZE,
            MODRINTH_ICON,
            MODRINTH_URL,
            Component.translatable("quickskin.button.modrinth")
        ));
    }

    @Override
    //? if <26.1.2 {
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
