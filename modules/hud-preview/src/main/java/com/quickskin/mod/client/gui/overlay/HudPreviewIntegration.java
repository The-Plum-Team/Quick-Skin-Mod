package com.quickskin.mod.client.gui.overlay;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientScreenInputEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.glfw.GLFW;

/** Registers the HUD preview's input, tick and render callbacks as one client feature. */
@Environment(EnvType.CLIENT)
public final class HudPreviewIntegration {
    private static boolean initialized;
    private static boolean isLeftDraggingOverlay;
    private static boolean isRightDraggingOverlay;

    private HudPreviewIntegration() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        ClientTickEvent.CLIENT_POST.register(client -> {
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
        });
    }

    public static void resetSessionState() {
        isLeftDraggingOverlay = false;
        isRightDraggingOverlay = false;
    }
}
