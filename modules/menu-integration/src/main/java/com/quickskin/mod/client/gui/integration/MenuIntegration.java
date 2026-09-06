package com.quickskin.mod.client.gui.integration;

import com.quickskin.mod.client.gui.util.DebugOffsetManager;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PreviewAnimationState;
import com.quickskin.mod.common.data.AssetMetadata;
import dev.architectury.event.events.client.ClientGuiEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/** Owns Quick Skin's controls and preview injected into vanilla title and pause menus. */
@Environment(EnvType.CLIENT)
public final class MenuIntegration {
    private static boolean initialized;
    private static PlayerWidget playerWidget;

    // Preserve rotation across screen rebuilds, including world exit.
    private static float titleScreenBodyYaw = 20.0f;
    private static float titleScreenTargetRotation = 20.0f;
    private static Button animationToggleButton;
    private static final java.util.List<Button> animationButtons = new java.util.ArrayList<>();
    private static boolean isAnimationDropdownOpen = false;

    private MenuIntegration() {
    }

    public static synchronized void init(java.util.function.Consumer<Screen> openSkinMenu) {
        java.util.Objects.requireNonNull(openSkinMenu, "openSkinMenu");
        if (initialized) return;
        initialized = true;

        // Screen init (after screen is initialized, before render)
        ClientGuiEvent.INIT_POST.register((client, screenAccess) -> {
            Screen screen = screenAccess.getScreen();

            // Determine screen type for all menu screens
            String screenType = determineScreenType(screen);
            if (screenType == null) {
                return; // Not a screen we care about
            }

            // Check for Essential mod compatibility
            boolean essentialPresent = com.quickskin.mod.client.compat.EssentialCompatIntegration.isAvailable();

            // Ensure Essential's player model uses QuickSkin's skin/cape
            if (essentialPresent) {
                com.quickskin.mod.client.compat.EssentialCompatIntegration.registerMenuAppearance();
            }

            // Inject QuickSkin button
            int buttonX = 0;
            int buttonY = 0;
            int buttonWidth = 98;
            int buttonHeight = 20;
            int spacing = 4;

            if (screen instanceof TitleScreen titleScreen) {
                boolean positioned = false;

                // If Essential is present, position beside its right-hand action rail
                if (essentialPresent) {
                    net.minecraft.client.gui.components.events.GuiEventListener bottomWidget =
                            com.quickskin.mod.client.compat.EssentialCompatIntegration.findBottomEssentialWidget(screen);
                    if (bottomWidget instanceof net.minecraft.client.gui.components.AbstractWidget essentialWidget) {
                        buttonWidth = 20;
                        buttonHeight = 20;
                        buttonX = essentialWidget.getX() - buttonWidth - spacing;
                        buttonY = essentialWidget.getY();
                        positioned = true;
                    }
                }

                if (!positioned) {
                    //? if >=26.2 {
                    // Minecraft 26.2 moved accessibility into a new icon row. Anchor to the
                    // stable Quit Game action so the whole Quick Skin cluster follows it.
                    Button quitGameButton = null;
                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof Button button &&
                                button.getMessage().getContents() instanceof
                                        net.minecraft.network.chat.contents.TranslatableContents contents &&
                                "menu.quit".equals(contents.getKey())) {
                            quitGameButton = button;
                            break;
                        }
                    }

                    if (quitGameButton != null) {
                        buttonX = quitGameButton.getX() + quitGameButton.getWidth() + spacing;
                        buttonY = quitGameButton.getY();
                    } else {
                        // Match the right-hand side of the vanilla Options/Quit Game row.
                        buttonX = titleScreen.width / 2 + 104;
                        buttonY = titleScreen.height / 4 + 48 + 96;
                    }
                    //?} else {
                    // Position next to accessibility button on title screen
                    // The Y coordinate for the row with the vanilla language and accessibility buttons
                    final int vanillaButtonsY = titleScreen.height / 4 + 48 + 72;

                    net.minecraft.client.gui.components.ImageButton accessibilityButton = null;

                    // Find the right-most ImageButton on the right half of the screen in that specific row
                    // This specifically targets vanilla buttons and avoids other mods' buttons
                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof net.minecraft.client.gui.components.ImageButton imgButton) {
                            if (imgButton.getY() == vanillaButtonsY &&
                                    //? if <1.21 {
                                    imgButton.getX() > titleScreen.width / 2 &&
                                    imgButton.getWidth() == 20 &&
                                    imgButton.getHeight() == 20) {
                                    //?} else {
                                imgButton.getX() > titleScreen.width / 2 &&
                                imgButton.getWidth() == 20 &&
                                imgButton.getHeight() == 20) {
                                    //?}
                                if (accessibilityButton == null || imgButton.getX() > accessibilityButton.getX()) {
                                    accessibilityButton = imgButton;
                                }
                            }
                        }
                    }

                    // Position next to the found accessibility button
                    if (accessibilityButton != null) {
                        buttonX = accessibilityButton.getX() + accessibilityButton.getWidth() + spacing;
                        buttonY = accessibilityButton.getY();
                    } else {
                        // Fallback if we couldn't find the accessibility button
                        buttonX = titleScreen.width / 2 + 128;
                        buttonY = titleScreen.height / 4 + 48 + 84;
                    }
                    //?}
                }

            } else if (screen instanceof PauseScreen pauseScreen) {
                boolean positioned = false;

                // If Essential is present, position beside its right-hand action rail
                if (essentialPresent) {
                    net.minecraft.client.gui.components.events.GuiEventListener bottomWidget =
                            com.quickskin.mod.client.compat.EssentialCompatIntegration.findBottomEssentialWidget(screen);
                    if (bottomWidget instanceof net.minecraft.client.gui.components.AbstractWidget essentialWidget) {
                        buttonWidth = 20;
                        buttonHeight = 20;
                        buttonX = essentialWidget.getX() - buttonWidth - spacing;
                        buttonY = essentialWidget.getY();
                        positioned = true;
                    }
                }

                if (!positioned) {
                    // Position next to "Save and Quit to Title" button
                    Button saveAndQuitButton = null;
                    int maxWidth = 0;

                    // Find the widest button (vanilla buttons)
                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof Button button && button.getWidth() > maxWidth) {
                            maxWidth = button.getWidth();
                        }
                    }

                    // Find the bottom-most button with that max width (Save and Quit to Title)
                    if (maxWidth > 0) {
                        int maxY = -1;
                        for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                            if (listener instanceof Button button && button.getWidth() == maxWidth && button.getY() > maxY) {
                                maxY = button.getY();
                                saveAndQuitButton = button;
                            }
                        }
                    }

                    if (saveAndQuitButton != null) {
                        // Position directly next to the vanilla quit button
                        buttonX = saveAndQuitButton.getX() + saveAndQuitButton.getWidth() + spacing;
                        buttonY = saveAndQuitButton.getY();
                    } else {
                        // Fallback position if we can't find the button
                        buttonX = pauseScreen.width - buttonWidth - spacing;
                        buttonY = spacing;
                    }
                }
            } else {
                // For other screens (world selection, etc.), use similar logic to PauseScreen
                Button referenceButton = findLargestButton(screen);
                if (referenceButton != null) {
                    int targetY = referenceButton.getY();
                    int rightmostX = referenceButton.getX() + referenceButton.getWidth();

                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof AbstractWidget widget && widget.getY() == targetY) {
                            rightmostX = Math.max(rightmostX, widget.getX() + widget.getWidth());
                        }
                    }

                    buttonX = rightmostX + spacing;
                    buttonY = targetY;
                } else {
                    // Fallback
                    buttonX = screen.width - buttonWidth - spacing;
                    buttonY = screen.height - buttonHeight - spacing;
                }
            }

            // Create and add the "Change Skin" button
            final Button changeSkinButton;
            if (essentialPresent) {
                // Use icon button when Essential is present
                changeSkinButton = new com.quickskin.mod.client.gui.widget.IconActionButton(
                        buttonX, buttonY, buttonWidth, buttonHeight,
                        //? if <1.21.11 {
                            //? if <1.21 {
                        new ResourceLocation("quickskin", "textures/gui/quickskin_icon.png"),
                            //?} else {
                        ResourceLocation.fromNamespaceAndPath("quickskin", "textures/gui/quickskin_icon.png"),
                            //?}
                        //?} else {
                        Identifier.fromNamespaceAndPath("quickskin", "textures/gui/quickskin_icon.png"),
                        //?}
                        button -> openSkinMenu.accept(screen),
                        Component.translatable("quickskin.button.change_skin")
                );
            } else {
                changeSkinButton = Button.builder(
                        Component.translatable("quickskin.button.change_skin"),
                        button -> openSkinMenu.accept(screen)
                ).bounds(buttonX, buttonY, buttonWidth, buttonHeight).build();
            }

            screenAccess.addRenderableWidget(changeSkinButton);

            // Skip PlayerWidget, rotate button, and animation buttons when Essential is present
            // (Essential has its own player model rendering)
            if (!essentialPresent) {
                // Create and add the PlayerWidget above the button using debug offsets
                int widgetSize = 144;
                int offsetX = DebugOffsetManager.getOffsetX(screenType);
                int offsetY = DebugOffsetManager.getOffsetY(screenType);

                int widgetX = buttonX + offsetX;
                int widgetY = buttonY + offsetY;

                // Get player skin and model type from saved config or player
                //? if <1.21.11 {
                ResourceLocation skinLocation = null;
                //?} else {
                Identifier skinLocation = null;
                //?}
                String modelType = "classic";
                LocalPlayer player = Minecraft.getInstance().player;

                com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

                // First priority: Use saved skin from config (works on title screen when player is null)
                if (!config.activeSkinHash.isEmpty()) {
                    com.quickskin.mod.client.services.LocalAssetManager assetManager =
                            com.quickskin.mod.client.services.LocalAssetManager.getInstance();
                    com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

                    if (metadata != null) {
                        // Load the saved skin texture
                        skinLocation = assetManager.getTextureLocation(config.activeSkinHash, com.quickskin.mod.common.data.TextureQuality.FULL);

                        // Get saved model type preference for this skin
                        modelType = assetManager.getSkinModelPreference(config.activeSkinHash);

                        // If auto mode, use the detected model type from metadata
                        if ("auto".equals(modelType)) {
                            modelType = metadata.skinModel();
                        }
                    }
                }

                // Second priority: Use current player skin (when in-game)
                if (skinLocation == null && player != null) {
                    //? if <1.21.9 {
                        //? if <1.21 {
                    skinLocation = player.getSkinTextureLocation();
                        //?} else {
                    skinLocation = player.getSkin().texture();
                        //?}
                    //?} else {
                    skinLocation = player.getSkin().body().texturePath();
                    //?}

                    // Get model type from the active skin if available
                    if (!config.activeSkinHash.isEmpty()) {
                        LocalAssetManager assetManager = LocalAssetManager.getInstance();
                        modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                        AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

                        // If auto mode, detect from the active custom skin (if any)
                        if ("auto".equals(modelType) && metadata != null) {
                            // Use the detected model type from the custom skin metadata
                            modelType = metadata.skinModel();
                        } else {
                            // Fallback: detect from the vanilla player's model
                            //? if <1.21.9 {
                                //? if <1.21 {
                            modelType = player.getModelName(); // "default" or "slim"
                                //?} else {
                            modelType = player.getSkin().model().id(); // "default" or "slim"
                                //?}
                            if ("default".equals(modelType)) {
                                modelType = "classic";
                            }
                            //?} else {
                            modelType = player.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM ? "slim" : "classic";
                            //?}
                        }
                    } else if ("auto".equals(modelType)) {
                        // No custom skin active, use vanilla player's model
                        //? if <1.21.9 {
                            //? if <1.21 {
                        modelType = player.getModelName(); // "default" or "slim"
                            //?} else {
                        modelType = player.getSkin().model().id(); // "default" or "slim"
                            //?}
                        if ("default".equals(modelType)) {
                            modelType = "classic";
                        }
                        //?} else {
                        modelType = player.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM ? "slim" : "classic";
                        //?}
                    }
                }

                // Fallback: Use default Steve skin
                if (skinLocation == null) {
                    //? if <1.21.11 {
                        //? if <1.21 {
                    skinLocation = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
                        //?} else {
                    skinLocation = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
                        //?}
                    //?} else {
                    skinLocation = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
                    //?}
                    modelType = "classic";
                }

                // Load saved cape from config
                String capeId = config.activeCapeHash;
                //? if <1.21.11 {
                ResourceLocation capeLocation = null;
                //?} else {
                Identifier capeLocation = null;
                //?}
                if (capeId != null && !capeId.isEmpty()) {
                    // Use the service to resolve the location. This will also trigger animation registration.
                    // The UUID is not used for local/known capes, so we can pass null.
                    capeLocation = com.quickskin.mod.client.services.CapeService.getInstance().getCapeLocation(null, capeId);
                }

                // Save rotation and animation state from existing widget before creating new one
                if (playerWidget != null) {
                    titleScreenBodyYaw = playerWidget.getBodyYaw();
                    titleScreenTargetRotation = playerWidget.getTargetYRotation();
                    String currentAnimation = playerWidget.getAnimation();
                    if (currentAnimation != null && !currentAnimation.isEmpty()) {
                        PreviewAnimationState.set(currentAnimation);
                    }
                }

                playerWidget = new PlayerWidget(widgetX, widgetY, widgetSize, widgetSize, skinLocation, capeLocation, capeId, modelType);
                // Set context based on screen type
                if ("title".equals(screenType)) {
                    playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.TITLE_SCREEN);
                } else if ("pause".equals(screenType)) {
                    playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.PAUSE_MENU);
                }
                screenAccess.addRenderableWidget(playerWidget);

                // Restore saved rotation and animation state
                playerWidget.setRotationState(titleScreenBodyYaw, titleScreenTargetRotation);
                String savedAnimation = PreviewAnimationState.get();
                if (savedAnimation != null && !savedAnimation.isEmpty()) {
                    playerWidget.setAnimation(savedAnimation);
                }

                // Create and add rotate button (above Change Skin button, aligned to the left edge)
                int rotateButtonSize = 20;
                int rotateButtonX = buttonX;
                int rotateButtonY = buttonY - rotateButtonSize - spacing;

                com.quickskin.mod.client.gui.widget.RotateButton rotateButton =
                        new com.quickskin.mod.client.gui.widget.RotateButton(
                                rotateButtonX,
                                rotateButtonY,
                                rotateButtonSize,
                                button -> playerWidget.toggleRotation()
                        );
                screenAccess.addRenderableWidget(rotateButton);

                //? if <1.21 {
                playerWidget.clearPriorityWidgets(); // Clear old priorities
                playerWidget.addPriorityWidget(changeSkinButton); // Change Skin button
                playerWidget.addPriorityWidget(rotateButton); // Rotate button
                //?}
                // Clear animation buttons from previous screen
                animationButtons.clear();
                isAnimationDropdownOpen = false;

                // Only add animation buttons on title screen, not in-game (pause menu)
                if ("title".equals(screenType)) {
                    // Create animation toggle button (right of rotate button)
                    int animToggleWidth = 20;
                    int animToggleX = buttonX + buttonWidth - animToggleWidth;
                    int animToggleY = rotateButtonY;

                    animationToggleButton = Button.builder(
                            Component.literal(">"),
                            button -> toggleAnimationDropdown()
                    ).bounds(animToggleX, animToggleY, animToggleWidth, rotateButtonSize).build();
                    screenAccess.addRenderableWidget(animationToggleButton);
                    //? if <1.21 {
                    playerWidget.addPriorityWidget(animationToggleButton);
                    //?}

                    // Create numbered animation buttons (dropdown)
                    java.util.List<String> availableAnimations = getAvailableAnimations();
                    for (int i = 0; i < availableAnimations.size(); i++) {
                        final String animName = availableAnimations.get(i);
                        final int index = i;

                        Button animButton = Button.builder(
                                Component.literal(String.valueOf(index + 1)),
                                button -> {
                                    // Set the animation on the player widget
                                    if (playerWidget != null) {
                                        playerWidget.setAnimation(animName);
                                        // Save animation state for persistence across all screens
                                        PreviewAnimationState.set(animName);
                                    }
                                    toggleAnimationDropdown();
                                }
                        ).bounds(animToggleX, animToggleY - (i + 1) * 22, animToggleWidth, rotateButtonSize).build();

                        animButton.visible = false;
                        animButton.active = false;
                        animationButtons.add(animButton);
                        screenAccess.addRenderableWidget(animButton);
                        //? if <1.21 {
                        playerWidget.addPriorityWidget(animButton);
                        //?}
                    }
                }
            } else {
                // Essential is present - hide our player widget and controls
                playerWidget = null;
                animationButtons.clear();
                isAnimationDropdownOpen = false;
            }
        });

        /*
         * Post-screen overlay pass for the preview injected into a vanilla screen.
         *
         * Registered only on the painter's-order pipeline. Before 1.21.6 the GUI is immediate mode
         * over a depth buffer, the preview is drawn at GUI z = +50 and depth-rejects whatever the
         * host screen paints afterwards, so there is nothing to defer and no listener to add.
         *
         * From 1.21.6 vanilla records the GUI into a GuiRenderState and composites it as a
         * painter's algorithm with every pipeline built NO_DEPTH_TEST. The depth that used to keep
         * the 3D preview in front is gone, and the last intersecting submission wins instead.
         * TitleScreen.render calls super.render(...) - which is where an injected renderable widget
         * draws - and only then paints the logo, the splash and the version string, so the splash
         * lands in a node above the model. Re-submitting the model once the host screen has
         * finished restores the order the depth buffer used to give for free.
         *
         * nextStratum() is vanilla's own mechanism for this and the only public layering API:
         * Screen.renderWithTooltipAndSubtitles brackets its phases with it, and DebugScreenOverlay
         * uses it for the one vanilla case of a picture-in-picture element that must beat text (the
         * profiler pie chart over the F3 lines). It appends one node to a list walked in index
         * order - O(1), no flush, no GPU work - and unlike relying on the intersection rule alone it
         * cannot be defeated by the encompassing-bounds fast path in
         * GuiRenderState.findAppropriateNode, nor by glyphs being emitted after elements when both
         * land in one node.
         *
         * The pass is driven by the widget's own per-frame handoff rather than by matching the
         * screen: submitDeferredPreview only draws when the widget actually rendered this frame, so
         * a hidden preview stays hidden and a preview drawn through a modal's inline parent render
         * still gets composited. The stratum is opened only when there is something to put in it.
         */
        //? if <1.21.6 {
        //?} else {
        ClientGuiEvent.RENDER_POST.register((screen, graphics, mouseX, mouseY, delta) -> {
            PlayerWidget widget = playerWidget;
            if (widget != null && widget.hasDeferredPreview()) {
                graphics.nextStratum();
                widget.submitDeferredPreview(graphics);
            }
            com.quickskin.mod.client.compat.CPMCompatIntegration.onRenderedFrameBoundary();
        });
        //?}
    }

    public static void resetSessionState() {
        playerWidget = null;
        PreviewAnimationState.set("idle");
        animationToggleButton = null;
        animationButtons.clear();
        isAnimationDropdownOpen = false;
    }

    /**
     * Determine screen type for the player widget
     * Returns: "title" or "pause", or null if not a supported screen
     * ONLY adds widgets to Title Screen and Pause Screen
     */
    private static String determineScreenType(Screen screen) {
        if (screen instanceof TitleScreen) {
            return "title";
        } else if (screen instanceof PauseScreen) {
            return "pause";
        }

        // Don't add widgets to any other screens (skin menu, world selection, etc.)
        return null;
    }

    /**
     * Find the largest button on a screen (used for positioning reference)
     */
    private static Button findLargestButton(Screen screen) {
        Button largest = null;
        int maxWidth = 0;
        int maxY = -1;

        for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
            if (listener instanceof Button button) {
                if (button.getWidth() > maxWidth) {
                    maxWidth = button.getWidth();
                }
            }
        }

        if (maxWidth > 0) {
            for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                if (listener instanceof Button button && button.getWidth() == maxWidth && button.getY() > maxY) {
                    maxY = button.getY();
                    largest = button;
                }
            }
        }

        return largest;
    }

    /**
     * Toggle the animation dropdown open/closed
     */
    private static void toggleAnimationDropdown() {
        isAnimationDropdownOpen = !isAnimationDropdownOpen;
        updateAnimationDropdownState();
    }

    /**
     * Update animation dropdown button visibility and toggle button text
     */
    private static void updateAnimationDropdownState() {
        if (animationToggleButton != null) {
            animationToggleButton.setMessage(Component.literal(isAnimationDropdownOpen ? "×" : ">"));
        }
        for (Button button : animationButtons) {
            button.visible = isAnimationDropdownOpen;
            button.active = isAnimationDropdownOpen;
        }
    }

    /**
     * Get list of available animations
     * Returns vanilla Minecraft animation states
     */
    private static java.util.List<String> getAvailableAnimations() {
        java.util.List<String> animations = new java.util.ArrayList<>();
        animations.add("idle");   // Button 1: Idle pose
        animations.add("walk");   // Button 2: Walking pose
        animations.add("sit");    // Button 3: Sitting pose
        return animations;
    }
}
