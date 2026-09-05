package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.ButtonFactory;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.gui.widget.RotateButton;
import com.quickskin.mod.common.data.AssetMetadata;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Panel that manages the player preview widget and model type selection buttons
 */
public class PlayerPreviewPanel extends AbstractWidget {

    @Nullable
    private PlayerWidget playerWidget;

    private Button autoModelButton;
    private Button classicModelButton;
    private Button slimModelButton;
    private Button rotateButton;
    private Button animationToggleButton;
    private final java.util.List<Button> animationButtons = new java.util.ArrayList<>();
    private boolean isAnimationDropdownOpen = false;

    private String currentModelType = "classic";

    @Nullable
    private Consumer<String> modelTypeChangeCallback;

    @Nullable
    private AssetMetadata currentMetadata;
    @Nullable
    //? if <1.21.11 {
    private net.minecraft.resources.ResourceLocation cpmIconLocation = null;
    //?} else {
    private net.minecraft.resources.Identifier cpmIconLocation = null;
    //?}
    private boolean isCpmModel = false;
    private long skinChangedAt = 0;

    public PlayerPreviewPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    /**
     * Set the callback to be called when model type changes
     */
    public void setModelTypeChangeCallback(Consumer<String> callback) {
        this.modelTypeChangeCallback = callback;
    }

    /**
     * Set the current model type (without triggering callback)
     * Used for initialization
     */
    public void setCurrentModelType(String modelType) {
        this.currentModelType = modelType;
        updateModelButtonStates();
    }

    /**
     * Initialize the player widget
     */
    public void initPlayerWidget(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen) {
        // Calculate player widget dimensions
        int widgetSize = Math.min(120, Math.min(height, width));
        int playerWidgetX = getX() + 20;
        int playerWidgetY = getY();

        // Create player widget
        playerWidget = new PlayerWidget(
                playerWidgetX,
                playerWidgetY,
                widgetSize,
                widgetSize,
                null, // Will use default Steve skin
                null, // No cape initially
                null, // No cape ID initially
                "classic" // Default model type
        );
        playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.SKIN_MENU);

        // Restore shared animation state from title screen
        String savedAnimation = com.quickskin.mod.event.ClientEvents.getSharedAnimation();
        if (savedAnimation != null && !savedAnimation.isEmpty()) {
            playerWidget.setAnimation(savedAnimation);
        }

        screen.registerWidget(playerWidget);
    }

    /**
     * Initialize the model buttons at the specified position (above cape button)
     */
    public void initModelButtons(
            com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen,
            int modelButtonsX,
            int modelButtonsY,
            int modelButtonsTotalWidth,
            int componentHeight,
            int spacing
    ) {
        // Auto button is smaller (square button for the emoji), Classic/Slim share the rest equally
        int remainingWidth = modelButtonsTotalWidth - componentHeight - spacing;
        int normalModelButtonWidth = (remainingWidth - spacing) / 2;

        // Create model type buttons
        autoModelButton = ButtonFactory.createStyled(
                modelButtonsX, modelButtonsY, componentHeight, componentHeight,
                Component.literal("✨"),
                button -> setModelType("auto")
        );
        screen.registerWidget(autoModelButton);

        classicModelButton = ButtonFactory.createStyled(
                modelButtonsX + componentHeight + spacing, modelButtonsY, normalModelButtonWidth, componentHeight,
                Component.translatable("quickskin.preview.model.wide"),
                button -> setModelType("classic")
        );
        screen.registerWidget(classicModelButton);

        slimModelButton = ButtonFactory.createStyled(
                modelButtonsX + componentHeight + spacing + normalModelButtonWidth + spacing, modelButtonsY, normalModelButtonWidth, componentHeight,
                Component.translatable("quickskin.preview.model.slim"),
                button -> setModelType("slim")
        );
        screen.registerWidget(slimModelButton);

        // Set button references for player widget positioning
        if (playerWidget != null) {
            playerWidget.setModelButtons(autoModelButton, classicModelButton, slimModelButton);
            //? if <1.21 {
            playerWidget.clearPriorityWidgets();
            playerWidget.addPriorityWidget(autoModelButton);
            playerWidget.addPriorityWidget(classicModelButton);
            playerWidget.addPriorityWidget(slimModelButton);
            //?}
        }

        // Update button states to lock the currently selected model button
        updateModelButtonStates();

        // Create rotate button (positioned at left edge above model buttons)
        int rotateButtonSize = 20;
        int rotateButtonY = modelButtonsY - rotateButtonSize - spacing;

        rotateButton = ButtonFactory.createRotate(
                modelButtonsX,
                rotateButtonY,
                rotateButtonSize,
                b -> {
                    if (playerWidget != null) {
                        playerWidget.toggleRotation();
                    }
                }
        );
        rotateButton.setTooltip(Tooltip.create(Component.translatable("quickskin.preview.rotate")));
        screen.registerWidget(rotateButton);

        //? if <1.21 {
        if (playerWidget != null) {
            playerWidget.addPriorityWidget(rotateButton);
        }
        //?}
        // Clear animation buttons from previous init
        animationButtons.clear();
        isAnimationDropdownOpen = false;

        // Only add animation buttons when NOT in-game (title screen only)
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        boolean isInGame = mc.player != null;

        if (!isInGame) {
            // Create animation toggle button (right edge above model buttons)
            int animToggleWidth = 20;
            int animToggleX = modelButtonsX + modelButtonsTotalWidth - animToggleWidth;

            animationToggleButton = ButtonFactory.createStyled(
                    animToggleX, rotateButtonY, animToggleWidth, rotateButtonSize,
                    Component.literal(">"),
                    button -> toggleAnimationDropdown()
            );
            screen.registerWidget(animationToggleButton);
            //? if <1.21 {
            if (playerWidget != null) {
                playerWidget.addPriorityWidget(animationToggleButton);
            }
            //?}

            // Create numbered animation buttons (dropdown)
            java.util.List<String> availableAnimations = getAvailableAnimations();
            for (int i = 0; i < availableAnimations.size(); i++) {
                final String animName = availableAnimations.get(i);
                final int index = i;

                Button animButton = ButtonFactory.createStyled(
                        animToggleX, rotateButtonY - (i + 1) * 22, animToggleWidth, rotateButtonSize,
                        Component.literal(String.valueOf(index + 1)),
                        button -> {
                            // Set the animation on the player widget
                            if (playerWidget != null) {
                                playerWidget.setAnimation(animName);
                                // Save animation state for persistence across all screens
                                com.quickskin.mod.event.ClientEvents.setSharedAnimation(animName);
                            }
                            toggleAnimationDropdown();
                        }
                );

                animButton.visible = false;
                animButton.active = false;
                animationButtons.add(animButton);
                screen.registerWidget(animButton);
                //? if <1.21 {
                if (playerWidget != null) {
                    playerWidget.addPriorityWidget(animButton);
                }
                //?}
            }
        }
    }

    /**
     * Toggle the animation dropdown open/closed
     */
    private void toggleAnimationDropdown() {
        isAnimationDropdownOpen = !isAnimationDropdownOpen;
        updateAnimationDropdownState();
    }

    /**
     * Update animation dropdown button visibility and toggle button text
     */
    private void updateAnimationDropdownState() {
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
    private java.util.List<String> getAvailableAnimations() {
        java.util.List<String> animations = new java.util.ArrayList<>();
        animations.add("idle");   // Button 1: Idle pose
        animations.add("walk");   // Button 2: Walking pose
        animations.add("sit");    // Button 3: Sitting pose
        return animations;
    }

    /**
     * Update the skin displayed in the player preview
     */
    //? if <1.21.11 {
    public void updateSkin(AssetMetadata metadata, net.minecraft.resources.ResourceLocation skinLocation) {
    //?} else {
    public void updateSkin(AssetMetadata metadata, net.minecraft.resources.Identifier skinLocation) {
    //?}
        if (playerWidget != null && metadata != null) {
            this.currentMetadata = metadata;
            this.isCpmModel = metadata.isCpmModel();
            this.skinChangedAt = System.currentTimeMillis();
            this.lastCpmWearing = false;
            boolean isInGame = net.minecraft.client.Minecraft.getInstance().player != null;
            if (isCpmModel && !isInGame) {
                cpmIconLocation = skinLocation;
                playerWidget.visible = false;
                updateModelButtonStates();
                return;
            }
            cpmIconLocation = null;
            playerWidget.visible = true;
            playerWidget.setSkin(skinLocation);

            // Update model type based on current mode
            if ("auto".equals(currentModelType)) {
                // Use the pre-detected model from metadata
                playerWidget.setModelType(metadata.skinModel());
            } else {
                // Use explicitly selected model
                playerWidget.setModelType(currentModelType);
            }
            updateModelButtonStates();
        }
    }

    /**
     * Update the cape displayed in the player preview
     */
    //? if <1.21.11 {
    public void updateCape(@Nullable net.minecraft.resources.ResourceLocation capeLocation, @Nullable String capeId) {
    //?} else {
    public void updateCape(@Nullable net.minecraft.resources.Identifier capeLocation, @Nullable String capeId) {
    //?}
        if (playerWidget != null) {
            playerWidget.setCape(capeLocation, capeId);
        }
    }

    /**
     * Get the player widget (for accessing rotation state)
     */
    @Nullable
    public com.quickskin.mod.client.gui.widget.PlayerWidget getPlayerWidget() {
        return playerWidget;
    }

    //? if >=1.21 {
    /**
     * Detect model type from texture data
     */
    private String detectModelFromTexture(AssetMetadata metadata) {
        try {
            // Load texture and detect model
            byte[] textureData = com.quickskin.mod.client.services.LocalAssetManager.getInstance()
                    .loadTexture(metadata.hash(), com.quickskin.mod.common.data.TextureQuality.PREVIEW);

            if (textureData != null) {
                String detected = com.quickskin.mod.common.util.SkinModelDetector.detectSkinModel(textureData);
                return detected;
            }
        } catch (Exception e) {
            com.quickskin.mod.platform.QuickSkinInfo.LOGGER.debug(
                    "Unable to detect the preview skin model", e);
        }

        // Fallback to metadata if detection fails
        return metadata.skinModel() != null ? metadata.skinModel() : "classic";
    }

    //?}
    /**
     * Set the model type for the player preview
     */
    private void setModelType(String modelType) {
        this.currentModelType = modelType;

        // Update preview widget
        if (playerWidget != null) {
            // If auto mode, use the pre-detected model from metadata
            if ("auto".equals(modelType) && currentMetadata != null) {
                playerWidget.setModelType(currentMetadata.skinModel());
            } else {
                playerWidget.setModelType(modelType);
            }
        }

        // Notify callback to apply to actual player
        if (modelTypeChangeCallback != null) {
            modelTypeChangeCallback.accept(modelType);
        }

        updateModelButtonStates();
    }

    /**
     * Get the current model type
     */
    public String getCurrentModelType() {
        return currentModelType;
    }

    /**
     * Update the active state of model buttons based on current selection
     */
    private void updateModelButtonStates() {
        if (autoModelButton != null && classicModelButton != null && slimModelButton != null) {
            boolean cpmActive = isCpmModel || lastCpmWearing;
            if (cpmActive) {
                autoModelButton.active = false;
                classicModelButton.active = false;
                slimModelButton.active = false;
                return;
            }
            boolean isAuto = "auto".equals(currentModelType != null ? currentModelType.toLowerCase(Locale.ROOT) : null);
            boolean isSlim = "slim".equals(currentModelType != null ? currentModelType.toLowerCase(Locale.ROOT) : null);
            boolean isClassic = "classic".equals(currentModelType != null ? currentModelType.toLowerCase(Locale.ROOT) : null);

            // Button is active (clickable) when it's NOT the current model
            autoModelButton.active = !isAuto;
            classicModelButton.active = !isClassic;
            slimModelButton.active = !isSlim;
        }
    }

    private boolean lastCpmWearing = false;

    private void updateCpmWearingState() {
        long elapsed = System.currentTimeMillis() - skinChangedAt;
        boolean cpmWearing;
        if (elapsed < 2000) {
            cpmWearing = isCpmModel;
        } else {
            cpmWearing = com.quickskin.mod.client.compat.CPMCompatIntegration.isLocalPlayerWearingCpmModel();
        }
        if (cpmWearing != lastCpmWearing) {
            lastCpmWearing = cpmWearing;
            updateModelButtonStates();
        }
    }

    @Override
    //? if <26.1.2 {
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateCpmWearingState();
        if (cpmIconLocation != null) {
            int iconSize = Math.min(getWidth(), getHeight()) - 16;
            int iconX = getX() + (getWidth() - iconSize) / 2;
            int iconY = getY() + (getHeight() - iconSize) / 2;
            //? if <1.21.11 {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            //?}
            GuiCompat.blit(graphics, cpmIconLocation, iconX, iconY, iconSize, iconSize,
                    0, 0, 64, 64, 64, 64);
            //? if <1.21.11 {
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            //?}
        }
    //?} else {
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateCpmWearingState();
        if (cpmIconLocation != null) {
            int iconSize = Math.min(getWidth(), getHeight()) - 16;
            int iconX = getX() + (getWidth() - iconSize) / 2;
            int iconY = getY() + (getHeight() - iconSize) / 2;
            GuiCompat.blit(graphics, cpmIconLocation, iconX, iconY, iconSize, iconSize,
                    0, 0, 64, 64, 64, 64);
        }
    //?}
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // No narration needed for container panel
    }
}
