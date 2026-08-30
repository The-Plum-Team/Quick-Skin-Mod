package com.quickskin.mod.event;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.util.DebugOffsetManager;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.event.InternalEventBus;
import com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent;
import com.quickskin.mod.common.event.ServerConfigSyncEvent;
import com.quickskin.mod.common.event.SkinTexturesReloadedEvent;
import com.quickskin.mod.runtime.ClientRuntime;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.client.ClientRawInputEvent;
import dev.architectury.event.events.client.ClientScreenInputEvent;
import dev.architectury.event.events.client.ClientTickEvent;
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
import org.lwjgl.glfw.GLFW;

import java.awt.image.BufferedImage;

/**
 * Client-side event handlers
 * Uses Architectury's event system for cross-platform compatibility
 */
@Environment(EnvType.CLIENT)
public class ClientEvents {

    private static final java.util.List<InternalEventBus.Subscription> INTERNAL_SUBSCRIPTIONS =
            new java.util.ArrayList<>();
    private static boolean initialized;
    private static volatile boolean closed;
    private static java.util.concurrent.CompletableFuture<?> playerOwnSkinTask;
    private static volatile boolean playerOwnSkinBootstrapped;

    private static int tickCounter = 0;
    private static PlayerWidget playerWidget;

    // Title screen rotation state (preserved across screen rebuilds)
    private static float titleScreenBodyYaw = 20.0f;
    private static float titleScreenTargetRotation = 20.0f;

    // Shared animation state (preserved across all screens)
    private static String sharedAnimation = "idle";

    /**
     * Get the current shared animation state
     */
    public static String getSharedAnimation() {
        return sharedAnimation;
    }

    /**
     * Set the shared animation state
     */
    public static void setSharedAnimation(String animation) {
        if (animation != null && !animation.isEmpty()) {
            sharedAnimation = animation;
        }
    }

    // Animation buttons (for dropdown menu)
    private static Button animationToggleButton;
    private static final java.util.List<Button> animationButtons = new java.util.ArrayList<>();
    private static boolean isAnimationDropdownOpen = false;
    private static boolean isLeftDraggingOverlay = false;
    private static boolean isRightDraggingOverlay = false;

    /**
     * Initializes client event listeners
     * Called from QuickSkinClient.init()
     */
    public static void init() {
        init(ClientRuntime.getInstance());
    }

    /** Registers platform callbacks and binds them to the client-owned session runtime. */
    public static synchronized void init(ClientRuntime clientRuntime) {
        if (initialized) {
            return;
        }
        if (clientRuntime == null) {
            throw new IllegalArgumentException("clientRuntime cannot be null");
        }
        closed = false;
        initialized = true;

        registerInternalListeners();

        CapeTransparencyEvents.register();

        // Client tick (fires every game tick, ~20 times per second)
        ClientTickEvent.CLIENT_POST.register(client -> {
            // The session user is not readable from every platform's client entry point, so retry
            // the own-skin bootstrap from the first tick that runs. It disarms itself once started.
            ensurePlayerOwnSkinExists();

            com.quickskin.mod.client.compat.CPMCompatIntegration
                    .prepareForBackgroundModelLoading();
            // This also ensures the singleton instance is created.
            com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                    .tickWorkingSet();
            AnimatedTextureManager.getInstance().tick();
            com.quickskin.mod.networking.ClientNetworkHandler.tick();
            com.quickskin.mod.networking.NetworkSyncService.getInstance().tick();

            // Handle HUD overlay dragging only when a GUI is open (cursor is visible)
            if (!client.mouseHandler.isMouseGrabbed()) {
                //? if <1.21.9 {
                boolean leftMouseDown = GLFW.glfwGetMouseButton(client.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
                boolean rightMouseDown = GLFW.glfwGetMouseButton(client.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
                //?} else {
                boolean leftMouseDown = GLFW.glfwGetMouseButton(client.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
                boolean rightMouseDown = GLFW.glfwGetMouseButton(client.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
                //?}
                double mouseX = client.mouseHandler.xpos() * (double)client.getWindow().getGuiScaledWidth() / (double)client.getWindow().getScreenWidth();
                double mouseY = client.mouseHandler.ypos() * (double)client.getWindow().getGuiScaledHeight() / (double)client.getWindow().getScreenHeight();

                // Handle Left Click for moving
                if (leftMouseDown) {
                    if (!isLeftDraggingOverlay) {
                        if (SkinPreviewOverlay.onMouseClicked(mouseX, mouseY, 0).interruptsFurtherEvaluation()) {
                            isLeftDraggingOverlay = true;
                        }
                    } else {
                        SkinPreviewOverlay.onMouseDragged(mouseX, mouseY, 0, 0, 0);
                    }
                } else if (isLeftDraggingOverlay) {
                    SkinPreviewOverlay.onMouseReleased(mouseX, mouseY, 0);
                    isLeftDraggingOverlay = false;
                }

                // Handle Right Click for rotating
                if (rightMouseDown) {
                    if (!isRightDraggingOverlay) {
                        if (SkinPreviewOverlay.onRightMouseClicked(mouseX, mouseY, 1).interruptsFurtherEvaluation()) {
                            isRightDraggingOverlay = true;
                        }
                    } else {
                        SkinPreviewOverlay.onMouseDragged(mouseX, mouseY, 1, 0, 0);
                    }
                } else if (isRightDraggingOverlay) {
                    SkinPreviewOverlay.onMouseReleased(mouseX, mouseY, 1);
                    isRightDraggingOverlay = false;
                }
            } else {
                // If no screen is open or mouse is grabbed, ensure dragging is stopped
                if (isLeftDraggingOverlay) isLeftDraggingOverlay = false;
                if (isRightDraggingOverlay) isRightDraggingOverlay = false;
            }
        });

        // Download player's own skin on startup (async, won't block). Platforms whose client entry
        // point runs before Minecraft exists retry this from the client tick registered above.
        ensurePlayerOwnSkinExists();

        // Player joins world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> {
            clientRuntime.beginSession(
                    player != null ? player.getUUID() : null,
                    player != null ? player.connection : Minecraft.getInstance().getConnection());
            resetSessionUiState();

            //? if <1.21 {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.hasSingleplayerServer()) {
                com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
                com.quickskin.mod.config.ClientConfig.getInstance().applyServerOverride(serverConfig);
            }
            //?}
            // Restore saved skin and model type from config
            restoreSavedAppearance(player);
        });

        // Player quits world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> {
            boolean ended = clientRuntime.endSession(
                    player != null ? player.getUUID() : null,
                    player != null ? player.connection : null);
            if (!ended) {
                return;
            }
            resetSessionUiState();

            // Re-register appearance for Essential's title screen player model
            com.quickskin.mod.client.compat.EssentialCompatIntegration.registerMenuAppearance();
        });

        // Respawn event (player dies and respawns)
        ClientPlayerEvent.CLIENT_PLAYER_RESPAWN.register((oldPlayer, newPlayer) -> {

            // Re-apply appearance after respawn
            restoreSavedAppearance(newPlayer);
        });

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
                        //? if <26.2 {
                        button -> Minecraft.getInstance().setScreen(new PlayerSkinMenuScreen(screen)),
                        //?} else {
                        button -> Minecraft.getInstance().gui.setScreen(new PlayerSkinMenuScreen(screen)),
                        //?}
                        Component.translatable("quickskin.button.change_skin")
                );
            } else {
                changeSkinButton = Button.builder(
                        Component.translatable("quickskin.button.change_skin"),
                        //? if <26.2 {
                        button -> Minecraft.getInstance().setScreen(new PlayerSkinMenuScreen(screen))
                        //?} else {
                        button -> Minecraft.getInstance().gui.setScreen(new PlayerSkinMenuScreen(screen))
                        //?}
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
                        setSharedAnimation(currentAnimation);
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
                String savedAnimation = getSharedAnimation();
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
                                        setSharedAnimation(animName);
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

        // Use PRE event for scrolling so we can interrupt it
        //? if <1.21 {
        ClientScreenInputEvent.MOUSE_SCROLLED_PRE.register((client, screen, mouseX, mouseY, amount) -> {
        //?} else {
        ClientScreenInputEvent.MOUSE_SCROLLED_PRE.register((client, screen, mouseX, mouseY, amountX, amountY) -> {
        //?}
            // Forward scroll events to the HUD overlay if the cursor is visible
            if (!client.mouseHandler.isMouseGrabbed()) {
                //? if <1.21 {
                return SkinPreviewOverlay.onMouseScrolled(mouseX, mouseY, amount);
                //?} else {
                return SkinPreviewOverlay.onMouseScrolled(mouseX, mouseY, amountY);
                //?}
            }
            return EventResult.pass();
        });

        //? if <1.21 {
        ClientScreenInputEvent.MOUSE_RELEASED_PRE.register((client, screen, mouseX, mouseY, button) -> {
            com.quickskin.mod.client.gui.widget.PlayerWidget activeWidget =
                com.quickskin.mod.client.gui.widget.PlayerWidget.getActiveInteractionWidget();
        //?} else {
        // Debug screen toggle (F3)
            //? if <1.21.9 {
        ClientScreenInputEvent.KEY_PRESSED_PRE.register((client, screen, keyCode, scanCode, modifiers) -> {
            //?} else {
        ClientScreenInputEvent.KEY_PRESSED_PRE.register((client, screen, keyEvent) -> {
            //?}
            // This event is for screen key presses
            // Keybinds are handled separately in KeybindRegistry
            return EventResult.pass();
        });
        //?}

            //? if <1.21 {
            if (activeWidget != null && activeWidget.isInteracting()) {
                boolean handled = activeWidget.mouseReleased(mouseX, mouseY, button);
                if (handled) {
                    return EventResult.interruptTrue(); // Consume the event
                }
            }
            //?} else {
        // Raw input (for global keybinds outside of screens)
            //? if <1.21.9 {
        ClientRawInputEvent.KEY_PRESSED.register((client, keyCode, scanCode, action, modifiers) -> {
            //?} else {
        ClientRawInputEvent.KEY_PRESSED.register((client, action, keyEvent) -> {
            //?}
            // Keybinds will be registered separately
            // This is for raw key detection if needed
            //?}
            return EventResult.pass();
        });

        // HUD render (for potential skin preview overlay)
        ClientGuiEvent.RENDER_HUD.register((guiGraphics, tickDelta) -> {
            // Get the setting from the client configuration
            boolean showOverlay = com.quickskin.mod.config.ClientConfig.getInstance().showSkinPreviewOverlay;
            if (showOverlay) {
                //? if <1.21 {
                com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay.render(guiGraphics, tickDelta);
                //?} else {
                com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay.render(guiGraphics, tickDelta.getGameTimeDeltaPartialTick(false));
                //?}
            }
            if (getCurrentScreen(Minecraft.getInstance()) == null) {
                com.quickskin.mod.client.compat.CPMCompatIntegration.onRenderedFrameBoundary();
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

    /** Connects service/domain changes to rendering and presentation adapters. */
    private static void registerInternalListeners() {
        InternalEventBus eventBus = InternalEventBus.getInstance();
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                PlayerAppearanceUpdateEvent.class,
                ClientEvents::onPlayerAppearanceUpdated));
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                SkinTexturesReloadedEvent.class,
                ClientEvents::onSkinTexturesReloaded));
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                ServerConfigSyncEvent.class,
                ClientEvents::onServerConfigSynced));
    }

    private static void onPlayerAppearanceUpdated(PlayerAppearanceUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null
                || !event.playerId().equals(minecraft.player.getUUID())) {
            return;
        }

        // Preview rendering observes domain changes without coupling the service to a concrete UI.
        com.quickskin.mod.client.rendering.PlayerModelRenderer.clearCachedPlayer();
    }

    private static void onSkinTexturesReloaded(SkinTexturesReloadedEvent event) {
        if (event.reason() != SkinTexturesReloadedEvent.Reason.TRANSPARENCY_POLICY) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Screen screen = getCurrentScreen(minecraft);
        if (screen instanceof PlayerSkinMenuScreen skinMenu) {
            skinMenu.refreshSkinList();
        }
    }

    private static void onServerConfigSynced(ServerConfigSyncEvent event) {
        // Disallowed transparency invalidates any alpha result captured before the server policy arrived.
        if (!event.isAllowTransparentSkins()) {
            com.quickskin.mod.common.util.TextureAlphaDetector.clearCache();
        }
        com.quickskin.mod.client.rendering.PlayerModelRenderer.clearCachedPlayer();
    }

    private static Screen getCurrentScreen(Minecraft minecraft) {
        //? if <26.2 {
        return minecraft.screen;
        //?} else {
        return minecraft.gui.screen();
        //?}
    }

    private static void resetSessionUiState() {
        tickCounter = 0;
        playerWidget = null;
        sharedAnimation = "idle";
        animationToggleButton = null;
        animationButtons.clear();
        isAnimationDropdownOpen = false;
        isLeftDraggingOverlay = false;
        isRightDraggingOverlay = false;
    }

    /** Unregisters internal service listeners during explicit client shutdown. */
    public static synchronized void close() {
        closed = true;
        if (playerOwnSkinTask != null) {
            playerOwnSkinTask.cancel(true);
            playerOwnSkinTask = null;
        }
        for (InternalEventBus.Subscription subscription : INTERNAL_SUBSCRIPTIONS) {
            try {
                subscription.close();
            } catch (RuntimeException error) {
                QuickSkin.LOGGER.warn("Failed to unregister a QuickSkin internal event listener", error);
            }
        }
        INTERNAL_SUBSCRIPTIONS.clear();
        resetSessionUiState();
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
     * Ensure player's own skin exists in the list
     * Downloads it from Mojang if not present
     * Can be called at any time (even before joining a world)
     *
     * <p>Idempotent and cheap to call repeatedly: it runs at most one bootstrap per client
     * session. Client entry points do not agree on when the session user becomes readable -
     * FML constructs mods before {@link Minecraft} exists, so the very first attempt has no
     * user to look up - therefore the attempt stays pending instead of being consumed, and the
     * client tick retries it as soon as the session is available.
     */
    private static void ensurePlayerOwnSkinExists() {
        if (playerOwnSkinBootstrapped || closed) {
            return;
        }
        startPlayerOwnSkinBootstrap();
    }

    private static synchronized void startPlayerOwnSkinBootstrap() {
        if (playerOwnSkinBootstrapped || closed) {
            return;
        }

        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.enablePlayerOwnSkinSystem) {
            playerOwnSkinBootstrapped = true;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getUser() == null) {
            // No session yet; leave the bootstrap pending for the next client tick.
            return;
        }

        String playerName = minecraft.getUser().getName();
        playerOwnSkinBootstrapped = true;

        // Check if we already have the player's skin hash and it exists
        if (!config.playerOwnSkinHash.isEmpty()) {
            AssetMetadata existingMetadata = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
            if (existingMetadata != null) {
                // Player's skin already exists
                return;
            }
        }

        // Download player's own skin (async, won't block startup)
        playerOwnSkinTask = com.quickskin.mod.client.services.MojangApiService.getInstance()
                .fetchSkinByUsername(playerName)
                .thenAccept(skinData -> {
                    if (!closed && minecraft != null) {
                        minecraft.execute(() -> {
                            if (!closed && skinData != null) {
                                handlePlayerOwnSkinFetched(skinData);
                            }
                        });
                    }
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                            && throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (!(cause instanceof java.util.concurrent.CancellationException)) {
                        QuickSkin.LOGGER.warn("Could not download the local player's Mojang skin", throwable);
                    }
                    return null;
                });
    }

    /**
     * Handle the fetched player's own skin data
     * Smart mode: checks if skin already exists before saving a duplicate
     */
    private static void handlePlayerOwnSkinFetched(com.quickskin.mod.client.services.MojangApiService.MojangSkinData skinData) {
        try {
            // Process the image to get its final form before hashing and saving.
            // This ensures the hash we check against is the same as the one that will be generated from the saved file.
            BufferedImage image = skinData.image;

            // Convert legacy 64x32 skins to modern 64x64 format
            if (image.getHeight() == image.getWidth() / 2) {
                image = com.quickskin.mod.common.util.HDTextureProcessor.convertLegacyToModern(image);
            }

            // Apply transparency settings if needed
            if (com.quickskin.mod.config.ClientConfig.getInstance().shouldDisableSkinTransparency()) {
                image = com.quickskin.mod.common.util.HDTextureProcessor.removeTransparency(image);
            }

            // Convert the (potentially modified) image to a byte array to compute its definitive hash.
            byte[] processedImageBytes = com.quickskin.mod.common.util.HDTextureProcessor.imageToPng(image);
            if (processedImageBytes == null) {
                return;
            }

            String finalHash = com.quickskin.mod.common.util.HashUtil.computeAssetContentId(
                    processedImageBytes, "skin");
            if (finalHash == null) {
                return;
            }

            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata existingMetadata = assetManager.getMetadata(finalHash);

            if (existingMetadata == null) {
                java.nio.file.Path saved = com.quickskin.mod.client.gui.util.SkinImporter
                        .saveSkinImage(image, skinData.username);
                if (saved == null) return;

                // Reload assets to recognize the new file.
                assetManager.reload();
                if (assetManager.getMetadata(finalHash) == null) {
                    QuickSkin.LOGGER.warn("Downloaded Mojang skin was saved with an unexpected content hash");
                    return;
                }
            }

            // Now that the skin is guaranteed to be in the asset manager, set its hash in the config.
            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            config.playerOwnSkinHash = finalHash;

            if (config.activeSkinHash.isEmpty() && config.activeCpmModelHash.isEmpty()) {
                config.activeSkinHash = finalHash;

                // Apply it to the player if they're in a world.
                net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
                if (player != null) {
                    AssetMetadata metadata = assetManager.getMetadata(finalHash);
                    if (metadata != null) {
                        String skinId = "local_skin:" + finalHash;
                        String modelType = assetManager.getSkinModelPreference(finalHash);

                        com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                                .applySkin(player.getUUID(), skinId, modelType);
                    }
                }
            }

            config.save();

        } catch (Exception e) {
            QuickSkin.LOGGER.error("Could not import the local player's Mojang skin", e);
        }
    }

    /**
     * Restore saved skin and cape from config when player joins world
     */
    private static void restoreSavedAppearance(LocalPlayer player) {
    //? if <1.21 {
        boolean isReplay = com.quickskin.mod.client.compat.ReplayModHelper.isInReplay();
        if (isReplay) {
            com.quickskin.mod.client.compat.ReplayModHelper.startReplayPlayerWatcher();
            return;
        }
        restoreSavedAppearanceToPlayer(player.getUUID());
    }
    private static void restoreSavedAppearanceToPlayer(java.util.UUID targetPlayerId) {
    //?}
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        com.quickskin.mod.client.services.LocalAssetManager assetManager =
                com.quickskin.mod.client.services.LocalAssetManager.getInstance();
        //? if >=1.21 {

        String skinId = null;
        String modelType = null;
        String capeId = null;
        //?}

        // Check if there's a saved skin
        if (!config.activeSkinHash.isEmpty()) {
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                //? if <1.21 {
                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(targetPlayerId, skinId, modelType);
                //?} else {
                // Prepare the saved skin with the saved model type preference for this skin
                skinId = "local_skin:" + metadata.hash();
                modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                //?}
            }
        } else if (!config.playerOwnSkinHash.isEmpty() && config.activeCpmModelHash.isEmpty()) {
            // No skin selected, but player's own skin exists - auto-select it
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.playerOwnSkinHash);

            if (metadata != null) {
                // Auto-select and apply the player's own skin
                config.activeSkinHash = config.playerOwnSkinHash;
                config.save();

                //? if <1.21 {
                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.playerOwnSkinHash);
                //?} else {
                skinId = "local_skin:" + metadata.hash();
                modelType = assetManager.getSkinModelPreference(config.playerOwnSkinHash);
                //?}

                // If auto mode, use the detected model from the skin
                if ("auto".equals(modelType)) {
                    modelType = metadata.skinModel();
                }
                //? if <1.21 {
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(targetPlayerId, skinId, modelType);
                //?}
            }
        }

        // Check if there's a saved cape
        if (!config.activeCapeHash.isEmpty()) {
            //? if <1.21 {
            String capeId = config.activeCapeHash;
            //?} else {
            capeId = config.activeCapeHash;
        }
            //?}

        //? if >=1.21 {
        // Apply both skin and cape together in a single call to avoid multiple syncs
        if (skinId != null || capeId != null) {
        //?}
            com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                    //? if <1.21 {
                    .applyCape(targetPlayerId, capeId);
                    //?} else {
                    .applyLook(player.getUUID(), skinId, capeId, modelType);
                    //?}
        }
    }

    /**
     * Auto-select player's own skin if no skin is currently selected
     * Called during initialization to ensure base skin is always selected
     */
    public static void autoSelectPlayerOwnSkin() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        if (config.activeSkinHash.isEmpty() && config.activeCpmModelHash.isEmpty() && !config.playerOwnSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(config.playerOwnSkinHash);

            if (metadata != null) {
                // Auto-select the player's own skin
                config.activeSkinHash = config.playerOwnSkinHash;
                config.save();
            }
        }
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
