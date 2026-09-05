package com.quickskin.mod.client.gui.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import dev.architectury.event.EventResult;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.player.LocalPlayer;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.util.Mth;

/**
 * HUD overlay showing a preview of your current skin
 * Displays in the corner of the screen (configurable position)
 */
@Environment(EnvType.CLIENT)
public class SkinPreviewOverlay {

    //? if >=1.21 {
    private static final int PADDING = 10;

    // Overlay position (default: bottom-right)
    private static OverlayPosition position = OverlayPosition.BOTTOM_RIGHT;
    //?}

    // Customization state
    private static boolean isDragging = false;
    private static int dragStartX, dragStartY;
    private static int dragStartOffsetX, dragStartOffsetY;

    private static boolean isRightDragging = false;
    private static int rightDragStartX;
    private static float initialRotationOnDrag;

    // Cached bounds for mouse interaction
    private static int cachedModelCenterX, cachedModelCenterY;
    private static float cachedScale;

    // Cached fields for performance
    //? if <1.21.11 {
    private static ResourceLocation cachedSkinLocation = null;
    //?} else {
    private static Identifier cachedSkinLocation = null;
    //?}
    private static String cachedModelType = "classic";
    private static String lastCheckedSkinHash = null; // Use null to force initial update
    //? if >=1.21 {

    public enum OverlayPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }
    //?}

    /**
     * Render the skin preview overlay
     */
    @SuppressWarnings("unused")
    //? if <26.1.2 {
    public static void render(GuiGraphics guiGraphics, float tickDelta) {
    //?} else {
    public static void render(GuiGraphicsExtractor guiGraphics, float tickDelta) {
    //?}
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null) {
            return;
        }
        //? if <1.21 {
        if (mc.screen != null) {
            boolean isChatOpen = mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen;
            if (!isChatOpen) {
                return;
            }
        }
        //?}

        // Update-on-change logic for huge performance gain
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        String activeSkinHash = config.activeSkinHash;

        // Use .equals() for string comparison. lastCheckedSkinHash can be null initially.
        boolean needsUpdate = (lastCheckedSkinHash == null) || !lastCheckedSkinHash.equals(activeSkinHash);

        if (needsUpdate) {
            lastCheckedSkinHash = activeSkinHash; // Update the hash we're tracking

            if (!activeSkinHash.isEmpty()) {
                // Use custom skin
                com.quickskin.mod.client.services.LocalAssetManager assetManager =
                        com.quickskin.mod.client.services.LocalAssetManager.getInstance();
                com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(activeSkinHash);

                if (metadata != null) {
                    cachedSkinLocation = assetManager.getTextureLocation(activeSkinHash,
                            com.quickskin.mod.common.data.TextureQuality.FULL);

                    // Get model type preference for this skin, using cached data from metadata
                    String skinModelPref = assetManager.getSkinModelPreference(activeSkinHash);
                    if ("auto".equals(skinModelPref)) {
                        cachedModelType = metadata.skinModel(); // Use cached model
                    } else {
                        cachedModelType = skinModelPref;
                    }
                }
            } else {
                // Use vanilla skin
                //? if <1.21.9 {
                    //? if <1.21 {
                cachedSkinLocation = player.getSkinTextureLocation();
                cachedModelType = player.getModelName(); // "default" or "slim"
                    //?} else {
                cachedSkinLocation = player.getSkin().texture();
                cachedModelType = player.getSkin().model().id(); // "default" or "slim"
                    //?}
                if ("default".equals(cachedModelType)) {
                    cachedModelType = "classic";
                }
                //?} else {
                cachedSkinLocation = player.getSkin().body().texturePath();
                cachedModelType = player.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM ? "slim" : "classic";
                //?}
            }
        }

        // Fallback if cached skin is somehow still null
        if (cachedSkinLocation == null) {
            //? if <1.21.9 {
                //? if <1.21 {
            cachedSkinLocation = player.getSkinTextureLocation();
                //?} else {
            cachedSkinLocation = player.getSkin().texture();
                //?}
            //?} else {
            cachedSkinLocation = player.getSkin().body().texturePath();
            //?}
            cachedModelType = "classic";
        }

        // Calculate scale from config percentage
        int percentage = config.sizeModelPreviewPercentageHudOverlay;
        if (percentage == 0) percentage = 30; // default
        float percentageAsFloat = (percentage - 1) / 99.0f;
        float scale = 20.0f + percentageAsFloat * (200.0f - 20.0f); // Replicating PlayerWidget's scale logic

        //? if <1.21 {
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        //?} else {
        // Calculate a dynamic preview size based on scale for positioning
        int PREVIEW_SIZE = (int)(scale * 0.8f);
        //?}

        //? if <1.21 {
        int hotbarCenterX = screenWidth / 2;
        int hotbarCenterY = screenHeight - 22; // Hotbar is typically 22 pixels from bottom
        //?} else {
        // Calculate base position
        int baseX = calculateX(mc.getWindow().getGuiScaledWidth(), PREVIEW_SIZE);
        int baseY = calculateY(mc.getWindow().getGuiScaledHeight(), PREVIEW_SIZE);
        //?}

        //? if <1.21 {
        int modelCenterX = hotbarCenterX + config.positionOffsetXHudOverlay;
        int modelCenterY = hotbarCenterY + config.positionOffsetYHudOverlay;
        //?} else {
        // Calculate model center with offsets
        int modelCenterX = baseX + PREVIEW_SIZE / 2 + config.positionOffsetXHudOverlay;
        int modelCenterY = baseY + (int)(PREVIEW_SIZE * 0.85f) + config.positionOffsetYHudOverlay;
        //?}

        // Cache for mouse events
        cachedModelCenterX = modelCenterX;
        cachedModelCenterY = modelCenterY;
        cachedScale = scale;

        // Use the rotation from config
        float rotationAngle = config.hudOverlayRotation;

        // Render player preview using cached data
        renderPlayerPreview(
                guiGraphics,
                modelCenterX,
                modelCenterY,
                scale,
                cachedSkinLocation,  // Use cached value
                cachedModelType,     // Use cached value
                rotationAngle
        );

        // Render border if customization is enabled
        if (config.enablePlayerPreviewCustomization) {
            renderModelBorder(guiGraphics, modelCenterX, modelCenterY, scale);
        }
    }

    //? if <26.1.2 {
    private static void renderModelBorder(GuiGraphics graphics, int centerX, int centerY, float scale) {
    //?} else {
    private static void renderModelBorder(GuiGraphicsExtractor graphics, int centerX, int centerY, float scale) {
    //?}
        int modelHeight = (int)(scale * 2.0f);
        int modelWidth = (int)(modelHeight * 0.6f);
        int left = centerX - modelWidth / 2;
        int right = centerX + modelWidth / 2;
        int top = centerY - modelHeight;

        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        String instructionText = "LMB: move | RMB: rotate | Wheel: resize";
        int textWidth = font.width(instructionText);
        int textX = centerX - textWidth / 2;
        int textY = top - 12;
        int textColor = 0xFFFFFFFF;
        //? if <26.1.2 {
        graphics.drawString(font, instructionText, textX, textY, textColor, true);
        //?} else {
        graphics.text(font, instructionText, textX, textY, textColor, true);
        //?}

        int borderColor = 0xFF00FF00;
        graphics.fill(left, top, right, top + 2, borderColor);
        graphics.fill(left, centerY - 2, right, centerY, borderColor);
        graphics.fill(left, top, left + 2, centerY, borderColor);
        graphics.fill(right - 2, top, right, centerY, borderColor);

        int crosshairSize = 5;
        int crosshairColor = 0xFFFF0000;
        graphics.fill(centerX - crosshairSize, centerY - 1, centerX + crosshairSize, centerY + 1, crosshairColor);
        graphics.fill(centerX - 1, centerY - crosshairSize, centerX + 1, centerY + crosshairSize, crosshairColor);
    }

    //? if >=1.21 {
    /**
     * Calculate X position based on overlay position
     */
    private static int calculateX(int screenWidth, int previewSize) {
        return switch (position) {
            case TOP_LEFT, BOTTOM_LEFT -> PADDING;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - previewSize - PADDING;
        };
    }

    /**
     * Calculate Y position based on overlay position
     */
    private static int calculateY(int screenHeight, int previewSize) {
        return switch (position) {
            case TOP_LEFT, TOP_RIGHT -> PADDING;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - previewSize - PADDING;
        };
    }

    //?}
    /**
     * Render the player model preview
     */
    private static void renderPlayerPreview(
            //? if <26.1.2 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int x,
            int y,
            float scale,
            //? if <1.21.11 {
            ResourceLocation skinTexture,
            //?} else {
            Identifier skinTexture,
            //?}
            String modelType,
            float rotation
    ) {
        // Create preview data
        PreviewPlayerData previewData = new PreviewPlayerData();
        previewData.setSkinLocation(skinTexture);
        previewData.setCapeLocation(null); // No cape for HUD preview
        previewData.setModelType(modelType);

        //? if <1.21.6 {
        PoseStack poseStack = graphics.pose();
        //?} else {
        // 1.21.6+: graphics.pose() is a Matrix3x2fStack; the renderer owns its 3D PoseStack.
        PoseStack poseStack = new PoseStack();
        //?}
        poseStack.pushPose();

        try {
            // Render the player
            PlayerModelRenderer.renderPlayerModel(
                    graphics,
                    x,
                    y,
                    scale,
                    rotation,
                    previewData,
                    0, // mouseX (no mouse tracking in HUD)
                    0, // mouseY
                    false // followMouse
            );
        } finally {
            poseStack.popPose();
        }
    }

    private static boolean isMouseOver(double mouseX, double mouseY) {
        int modelHeight = (int)(cachedScale * 2.0f);
        int modelWidth = (int)(modelHeight * 0.6f);
        int left = cachedModelCenterX - modelWidth / 2;
        int right = cachedModelCenterX + modelWidth / 2;
        int top = cachedModelCenterY - modelHeight;

        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= cachedModelCenterY;
    }

    public static EventResult onMouseClicked(double mouseX, double mouseY, int button) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.showSkinPreviewOverlay || !config.enablePlayerPreviewCustomization || button != 0) {
            isDragging = false;
            return EventResult.pass();
        }

        if (isMouseOver(mouseX, mouseY)) {
            isDragging = true;
            dragStartX = (int)mouseX;
            dragStartY = (int)mouseY;
            dragStartOffsetX = config.positionOffsetXHudOverlay;
            dragStartOffsetY = config.positionOffsetYHudOverlay;
            return EventResult.interruptTrue();
        }
        return EventResult.pass();
    }

    public static EventResult onMouseReleased(double mouseX, double mouseY, int button) {
        if (isDragging && button == 0) {
            isDragging = false;
            com.quickskin.mod.config.ClientConfig.getInstance().save(); // Save on release
            return EventResult.interruptTrue();
        }
        if (isRightDragging && button == 1) {
            isRightDragging = false;
            com.quickskin.mod.config.ClientConfig.getInstance().save(); // Save on release
            return EventResult.interruptTrue();
        }
        return EventResult.pass();
    }

    public static EventResult onMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (isDragging && button == 0) {
            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            int newOffsetX = dragStartOffsetX + (int)(mouseX - dragStartX);
            int newOffsetY = dragStartOffsetY + (int)(mouseY - dragStartY);
            config.positionOffsetXHudOverlay = newOffsetX;
            config.positionOffsetYHudOverlay = newOffsetY;
            return EventResult.interruptTrue();
        }
        if (isRightDragging && button == 1) {
            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            float rotationDelta = (float)(mouseX - rightDragStartX) * 0.5f; // Sensitivity
            config.hudOverlayRotation = initialRotationOnDrag - rotationDelta; // Changed from + to -
            return EventResult.interruptTrue();
        }
        return EventResult.pass();
    }

    public static EventResult onRightMouseClicked(double mouseX, double mouseY, int button) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.showSkinPreviewOverlay || !config.enablePlayerPreviewCustomization || button != 1) {
            isRightDragging = false;
            return EventResult.pass();
        }

        if (isMouseOver(mouseX, mouseY)) {
            isRightDragging = true;
            rightDragStartX = (int)mouseX;
            initialRotationOnDrag = config.hudOverlayRotation;
            return EventResult.interruptTrue();
        }
        return EventResult.pass();
    }

    public static EventResult onMouseScrolled(double mouseX, double mouseY, double delta) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.showSkinPreviewOverlay || !config.enablePlayerPreviewCustomization) {
            return EventResult.pass();
        }

        if (isMouseOver(mouseX, mouseY)) {
            int oldPercentage = config.sizeModelPreviewPercentageHudOverlay;
            if (oldPercentage == 0) oldPercentage = 30;

            int newPercentage = oldPercentage + (int)Math.round(delta * 5); // 5% per scroll tick
            newPercentage = Mth.clamp(newPercentage, 1, 100);

            if (newPercentage != oldPercentage) {
                config.sizeModelPreviewPercentageHudOverlay = newPercentage;
                config.save();
            }
            return EventResult.interruptTrue();
        }
        return EventResult.pass();
    }
    //? if >=1.21 {

    /**
     * Set the overlay position
     */
    public static void setPosition(OverlayPosition newPosition) {
        position = newPosition;
    }

    /**
     * Get the current overlay position
     */
    public static OverlayPosition getPosition() {
        return position;
    }
    //?}
}
