package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.ButtonFactory;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.gui.widget.RotationArcWidget;
import com.quickskin.mod.common.data.AssetMetadata;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The player model in the middle of the skin menu, with the turntable arc under its feet and, on
 * the title screen, the animation selector.
 *
 * <p>The panel only previews: it shows whatever skin and arm width the menu has pending and never
 * applies anything to the player. Its own rectangle is the model's box; it draws a CPM model's icon
 * there on the title screen, where no player entity exists for CPM to draw on.
 */
public class PlayerPreviewPanel extends AbstractWidget {

    @Nullable
    private PlayerWidget playerWidget;

    @Nullable
    private RotationArcWidget rotationArc;
    private Button animationToggleButton;
    private final java.util.List<Button> animationButtons = new java.util.ArrayList<>();
    private boolean isAnimationDropdownOpen = false;

    private String currentModelType = "classic";

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
    private boolean lastCpmWearing = false;

    public PlayerPreviewPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
        // The panel only draws; clicks fall through to the controls and the model behind it.
        this.active = false;
    }

    /**
     * Creates the model with its feet at {@code (centerX, feetY)}, no larger than {@code scaleCap}.
     */
    public void initPlayerWidget(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen,
                                 int centerX, int feetY, float scaleCap) {
        playerWidget = new PlayerWidget(
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                null, // Will use default Steve skin
                null, // No cape initially
                null, // No cape ID initially
                "classic" // Default model type
        );
        playerWidget.setContext(PlayerWidget.WidgetContext.SKIN_MENU);
        playerWidget.setCustomReferencePoint(centerX, feetY);
        playerWidget.setScaleCap(scaleCap);

        // Restore shared animation state from title screen
        String savedAnimation = com.quickskin.mod.client.services.PreviewAnimationState.get();
        if (savedAnimation != null && !savedAnimation.isEmpty()) {
            playerWidget.setAnimation(savedAnimation);
        }

        screen.registerWidget(playerWidget);
        screen.registerWidget(this);
    }

    /**
     * Makes this panel's rectangle the model's box: {@code scale} wide-ish around {@code centerX},
     * standing on {@code feetY}. The CPM icon is drawn in it, and a drag that starts in it turns
     * the model.
     */
    public void setModelBox(int centerX, int feetY, float scale) {
        int boxWidth = (int) (scale * 1.2f);
        int boxHeight = (int) (scale * 1.95f);
        setX(centerX - boxWidth / 2);
        setY(feetY - boxHeight);
        this.width = boxWidth;
        this.height = boxHeight;
    }

    /** Whether a point lies in the model's box. */
    public boolean isOverModelBox(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= getY() && mouseY < getY() + getHeight();
    }

    /**
     * Creates the turntable ring around the model's feet and, on the title screen, the animation
     * selector beside its right end, which opens upward. With {@code ringIntro} the ring plays its
     * appearing animation.
     */
    public void initControls(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen,
                             int centerX, int ringCenterY, int ringRadiusX, boolean ringIntro) {
        int size = 20;
        rotationArc = new RotationArcWidget(centerX, ringCenterY, ringRadiusX, ringIntro);
        screen.registerWidget(rotationArc);
        //? if <1.21 {
        if (playerWidget != null) {
            playerWidget.clearPriorityWidgets();
        }
        //?}

        animationButtons.clear();
        isAnimationDropdownOpen = false;

        // Animations only show on the title screen, where the model is not the live player.
        boolean isInGame = net.minecraft.client.Minecraft.getInstance().player != null;
        if (isInGame) {
            return;
        }
        int toggleX = centerX + ringRadiusX + 6;
        int y = ringCenterY - size / 2;
        animationToggleButton = ButtonFactory.createStyled(
                toggleX, y, size, size,
                Component.literal(">"),
                button -> toggleAnimationDropdown()
        );
        screen.registerWidget(animationToggleButton);
        //? if <1.21 {
        if (playerWidget != null) {
            playerWidget.addPriorityWidget(animationToggleButton);
        }
        //?}

        java.util.List<String> availableAnimations = getAvailableAnimations();
        for (int i = 0; i < availableAnimations.size(); i++) {
            final String animName = availableAnimations.get(i);
            Button animButton = ButtonFactory.createStyled(
                    toggleX, y - (i + 1) * 22, size, size,
                    Component.literal(String.valueOf(i + 1)),
                    button -> {
                        if (playerWidget != null) {
                            playerWidget.setAnimation(animName);
                            // Save animation state for persistence across all screens
                            com.quickskin.mod.client.services.PreviewAnimationState.set(animName);
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

    /** The turntable arc under the model, once {@link #initControls} has created it. */
    @Nullable
    public RotationArcWidget getRotationArc() {
        return rotationArc;
    }

    /**
     * Keeps the model from taking clicks meant for a control drawn over it (before 1.21 the model
     * itself takes clicks to rotate).
     */
    public void addPriorityControl(AbstractWidget control) {
        //? if <1.21 {
        if (playerWidget != null) {
            playerWidget.addPriorityWidget(control);
        }
        //?}
    }

    private void toggleAnimationDropdown() {
        isAnimationDropdownOpen = !isAnimationDropdownOpen;
        if (animationToggleButton != null) {
            animationToggleButton.setMessage(Component.literal(isAnimationDropdownOpen ? "×" : ">"));
        }
        for (Button button : animationButtons) {
            button.visible = isAnimationDropdownOpen;
            button.active = isAnimationDropdownOpen;
        }
    }

    /** Vanilla Minecraft animation states, in button order. */
    private java.util.List<String> getAvailableAnimations() {
        java.util.List<String> animations = new java.util.ArrayList<>();
        animations.add("idle");   // Button 1: Idle pose
        animations.add("walk");   // Button 2: Walking pose
        animations.add("sit");    // Button 3: Sitting pose
        return animations;
    }

    /**
     * Shows a skin, or a CPM model's icon on the title screen, with the given arm width.
     */
    //? if <1.21.11 {
    public void previewSkin(AssetMetadata metadata, net.minecraft.resources.ResourceLocation skinLocation,
    //?} else {
    public void previewSkin(AssetMetadata metadata, net.minecraft.resources.Identifier skinLocation,
    //?}
                            @Nullable String modelType) {
        if (playerWidget == null || metadata == null) {
            return;
        }
        this.currentMetadata = metadata;
        this.isCpmModel = metadata.isCpmModel();
        this.skinChangedAt = System.currentTimeMillis();
        this.lastCpmWearing = false;
        if (modelType != null) {
            this.currentModelType = modelType;
        }
        boolean isInGame = net.minecraft.client.Minecraft.getInstance().player != null;
        if (isCpmModel && !isInGame) {
            cpmIconLocation = skinLocation;
            playerWidget.visible = false;
            return;
        }
        cpmIconLocation = null;
        playerWidget.visible = true;
        playerWidget.setSkin(skinLocation);
        applyModelTypeToPreview();
    }

    /** Changes the previewed arm width: "auto", "classic" or "slim". */
    public void previewModelType(String modelType) {
        this.currentModelType = modelType;
        applyModelTypeToPreview();
    }

    private void applyModelTypeToPreview() {
        if (playerWidget == null) {
            return;
        }
        if ("auto".equals(currentModelType) && currentMetadata != null) {
            // Use the pre-detected model from metadata
            playerWidget.setModelType(currentMetadata.skinModel());
        } else {
            playerWidget.setModelType(currentModelType);
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
    public PlayerWidget getPlayerWidget() {
        return playerWidget;
    }

    /** The previewed arm width: "auto", "classic" or "slim". */
    public String getCurrentModelType() {
        return currentModelType;
    }

    /**
     * Whether a CPM model owns the previewed look, so the arm width cannot be chosen: either a CPM
     * model is previewed, or CPM is drawing one on the live player.
     */
    public boolean isCpmLook() {
        return isCpmModel || lastCpmWearing;
    }

    /** The arm width the skin menu offers for a model type: "slim", or "classic" for anything else. */
    public static String normalizedModelType(@Nullable String modelType) {
        return modelType != null && "slim".equals(modelType.toLowerCase(Locale.ROOT)) ? "slim" : "classic";
    }

    private void updateCpmWearingState() {
        long elapsed = System.currentTimeMillis() - skinChangedAt;
        lastCpmWearing = elapsed < 2000
                ? isCpmModel
                : com.quickskin.mod.client.compat.CPMCompatIntegration.isLocalPlayerWearingCpmModel();
    }

    @Override
    //? if <26.1 {
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        updateCpmWearingState();
        if (cpmIconLocation == null) {
            return;
        }
        int iconSize = Math.min(getWidth(), getHeight()) - 16;
        int iconX = getX() + (getWidth() - iconSize) / 2;
        int iconY = getY() + (getHeight() - iconSize) / 2;
        //? if <1.21.5 {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        //?}
        GuiCompat.blit(graphics, cpmIconLocation, iconX, iconY, iconSize, iconSize,
                0, 0, 64, 64, 64, 64);
        //? if <1.21.5 {
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        //?}
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // No narration needed for container panel
    }
}
