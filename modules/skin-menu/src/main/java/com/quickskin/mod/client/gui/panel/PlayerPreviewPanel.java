package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.widget.IconButton;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.gui.widget.PoseList;
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
 * The player model in the middle of the skin menu, with the turntable arc under its feet and the
 * pose selector. The model holds the chosen pose in a world too, rather than copying the player.
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
    @Nullable
    private IconButton poseButton;
    @Nullable
    private PoseList poseList;
    private final java.util.List<PoseList.Row> poseRows = new java.util.ArrayList<>();

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
     * Creates the turntable ring around the model's feet. With {@code ringIntro} the ring plays
     * its appearing animation.
     */
    public void initControls(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen,
                             int centerX, int ringCenterY, int ringRadiusX, boolean ringIntro) {
        rotationArc = new RotationArcWidget(centerX, ringCenterY, ringRadiusX, ringIntro);
        screen.registerWidget(rotationArc);
        //? if <1.21 {
        if (playerWidget != null) {
            playerWidget.clearPriorityWidgets();
        }
        //?}

        poseButton = null;
        poseList = null;
        poseRows.clear();
    }

    /**
     * Adds the pose button at {@code (x, y)}: it opens the list of poses to its right, and its
     * tooltip names the current pose.
     */
    public void initPoseControl(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen,
                                int x, int y, int size, int screenWidth) {
        poseButton = new IconButton(x, y, size, "poses", Component.empty(),
                button -> setPoseListOpen(poseList != null && !poseList.visible));
        screen.registerWidget(poseButton);
        addPriorityControl(poseButton);

        java.util.List<String> poses = getAvailableAnimations();
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        int listWidth = 70;
        for (String pose : poses) {
            listWidth = Math.max(listWidth, font.width(poseName(pose)) + 16);
        }
        int listHeight = poses.size() * PoseList.ROW_HEIGHT + 2 * PoseList.FRAME;
        // Beside the button, centred on it; to its left when the screen has no room on the right
        int listX = x + size + 4;
        if (listX + listWidth > screenWidth - 4) {
            listX = x - 4 - listWidth;
        }
        int listY = y + size / 2 - listHeight / 2;
        poseList = new PoseList(listX, listY, listWidth, poses.size());
        screen.registerWidget(poseList);
        for (int i = 0; i < poses.size(); i++) {
            String pose = poses.get(i);
            PoseList.Row row = new PoseList.Row(listX + PoseList.FRAME, listY + PoseList.FRAME + i * PoseList.ROW_HEIGHT,
                    listWidth - 2 * PoseList.FRAME, poseName(pose), button -> selectPose(pose));
            poseRows.add(row);
            screen.registerWidget(row);
            addPriorityControl(row);
        }
        setPoseListOpen(false);
        updatePoseButton();
    }

    /** Closes the pose list when a click lands outside it and its button. */
    public void closePoseListUnlessOver(double mouseX, double mouseY) {
        if (poseList == null || !poseList.visible || poseButton == null) {
            return;
        }
        // The list itself is inactive, so its bounds are checked here rather than by isMouseOver
        boolean overList = mouseX >= poseList.getX() && mouseX < poseList.getX() + poseList.getWidth()
                && mouseY >= poseList.getY() && mouseY < poseList.getY() + poseList.getHeight();
        if (!overList && !poseButton.isMouseOver(mouseX, mouseY)) {
            setPoseListOpen(false);
        }
    }

    private void selectPose(String pose) {
        if (playerWidget != null) {
            playerWidget.setAnimation(pose);
        }
        // Kept for every preview, across screens
        com.quickskin.mod.client.services.PreviewAnimationState.set(pose);
        updatePoseButton();
        setPoseListOpen(false);
    }

    private void setPoseListOpen(boolean open) {
        if (poseList == null || poseButton == null) {
            return;
        }
        poseList.visible = open;
        poseButton.setSelected(open);
        // Its tooltip would cover the list
        poseButton.setTooltip(open ? null
                : net.minecraft.client.gui.components.Tooltip.create(poseButton.getMessage()));
        String current = currentPose();
        java.util.List<String> poses = getAvailableAnimations();
        for (int i = 0; i < poseRows.size(); i++) {
            PoseList.Row row = poseRows.get(i);
            row.visible = open;
            row.active = open;
            row.setSelected(poses.get(i).equals(current));
        }
    }

    private void updatePoseButton() {
        if (poseButton == null) {
            return;
        }
        String pose = currentPose();
        Component tooltip = Component.translatable("quickskin.preview.pose", poseName(pose));
        poseButton.setMessage(tooltip);
        if (poseList == null || !poseList.visible) {
            poseButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tooltip));
        }
    }

    private static String currentPose() {
        String pose = com.quickskin.mod.client.services.PreviewAnimationState.get();
        return pose == null || pose.isEmpty() ? "idle" : pose;
    }

    private static Component poseName(String pose) {
        return Component.translatable("quickskin.preview.pose." + pose);
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
        if (playerWidget != null) {
            // In a world too the model shows the chosen pose rather than copying what the player
            // does; only a CPM model needs the live player, which CPM draws it on
            playerWidget.setOwnPose(!isCpmLook());
        }
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
