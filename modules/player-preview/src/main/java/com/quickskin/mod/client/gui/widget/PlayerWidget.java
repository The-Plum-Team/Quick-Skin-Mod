package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.rendering.PreviewRenderBackend;
import com.quickskin.mod.client.rendering.PreviewCompositeOrder;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
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
import org.jetbrains.annotations.Nullable;

/**
 * Widget that displays a 3D rotating player model preview
 * Uses vanilla PlayerModel rendering instead of GeckoLib
 */
@Environment(EnvType.CLIENT)
public class PlayerWidget extends AbstractWidget {

    private final PreviewPlayerData previewData;

    // Rotation state
    private float bodyYaw = 20.0f; // 20 degrees for sideways pose (matching original)
    private float targetYRotation = 20.0f; // Target rotation for smooth animation

    // Display settings
    private float scale = 87.2f; // 10% smaller than previous (96.9 * 0.9 = 87.21)
    private static final float DEFAULT_SCALE = 87.2f; // Default scale value
    private static final float MIN_SCALE = 20.0f; // Minimum scale for resize
    private static final float MAX_SCALE = 200.0f; // Maximum scale for resize
    private static final float SCALE_STEP = 3.0f; // Scale change per scroll tick (smaller for smoother resizing)

    // Pivot point for scaling (at the feet position - where the red crosshair is)
    private static final float PIVOT_OFFSET = 0.0f; // No offset - pivot at feet (crosshair position)

    // Dragging state for repositioning
    private boolean isDragging = false;
    private int dragStartX = 0;
    private int dragStartY = 0;
    private int dragStartOffsetX = 0;
    private int dragStartOffsetY = 0;
//? if <1.21 {

    // Rotation drag state (works when NOT in customization mode)
    private boolean isRotating = false;
    private int rotationDragStartX = 0;
    private float rotationDragStartYaw = 0.0f;
    private float originalYRotation = 20.0f; // Original rotation to return to
    private boolean shouldReturnToOriginal = false;
    private static final float ROTATION_SENSITIVITY = 0.5f; // Degrees per pixel
//?} else {
//?}

    // Cached model bounds for mouse interaction (updated each frame in renderWidget)
    private int cachedModelCenterX = 0;
    private int cachedModelCenterY = 0;
    private float cachedScale = DEFAULT_SCALE;

    // --- Layout-box mode ---
    // Off unless reportModelBoundsForLayout() turns it on; MenuIntegration does that for the title and
    // pause previews only when FancyMenu is present. In this mode the widget's rectangle is the drawn
    // model box, so a layout editor can select, move and resize it, and the model is fitted into
    // whatever rectangle the editor reports.
    private boolean reportsModelBounds = false;
    // False while the host screen is still being built; see getWidth().
    private boolean layoutBoundsPublished = false;
    // The model anchor (centre X, feet Y) without the configured position offsets.
    private int layoutAnchorX = 0;
    private int layoutAnchorFeetY = 0;
    // The natural model box last written to the widget's fields, and its scale.
    private PreviewLayoutBox.Box naturalBox = null;
    private float naturalScale = DEFAULT_SCALE;
    // The screen this preview was injected into; deferral to its overlay pass only applies there.
    // Weak, because MenuIntegration keeps the last preview after its screen has closed.
    private java.lang.ref.WeakReference<net.minecraft.client.gui.screens.Screen> deferralHost = null;

    // Context type for this widget
    public enum WidgetContext {
        TITLE_SCREEN,
        SKIN_MENU,
        CAPE_MENU,
        PAUSE_MENU,
        OTHER
    }
    private WidgetContext context = WidgetContext.OTHER;

    /**
     * How the running Minecraft version decides which GUI draw ends up on top.
     *
     * <p>Vanilla swapped the depth-buffered immediate GUI for the deferred, depth-less
     * {@code GuiRenderState} in 1.21.6, so that is the compatibility boundary regardless of which
     * release lanes currently exist.
     */
//? if <1.21.6 {
    private static final PreviewCompositeOrder.Pipeline GUI_PIPELINE =
            PreviewCompositeOrder.Pipeline.DEPTH_ORDERED;
//?} else {
    private static final PreviewCompositeOrder.Pipeline GUI_PIPELINE =
            PreviewCompositeOrder.Pipeline.PAINTERS_ORDERED;
//?}

    private static PreviewCompositeOrder.Surface surfaceOf(WidgetContext context) {
        if (context == null) {
            return PreviewCompositeOrder.Surface.OTHER;
        }
        switch (context) {
            case TITLE_SCREEN:
                return PreviewCompositeOrder.Surface.TITLE_SCREEN;
            case PAUSE_MENU:
                return PreviewCompositeOrder.Surface.PAUSE_MENU;
            case SKIN_MENU:
                return PreviewCompositeOrder.Surface.SKIN_MENU;
            case CAPE_MENU:
                return PreviewCompositeOrder.Surface.CAPE_MENU;
            default:
                return PreviewCompositeOrder.Surface.OTHER;
        }
    }

    /**
     * True when this preview must be submitted by the post-screen overlay pass instead of inline.
     *
     * <p>Only ever true for a preview injected into a vanilla screen on the painter's-order
     * pipeline; see {@link PreviewCompositeOrder} for the reasoning.
     */
    public boolean defersToScreenOverlay() {
        return PreviewCompositeOrder.defersToHostScreenOverlay(GUI_PIPELINE, surfaceOf(context));
    }

    /**
     * Set by the render pass when it deferred this frame's submission, cleared when the overlay pass
     * picks it up.
     *
     * <p>The handoff is a per-frame flag rather than a "draw the widget that belongs to this screen"
     * rule, because the flag states the real condition: there is one frame of preview waiting to be
     * composited. A screen-keyed rule states something weaker that happens to coincide most of the
     * time, and it breaks where they differ - {@code AbstractWidget.render} skips
     * {@code renderWidget} entirely when the widget is not {@code visible}, so a screen-keyed pass
     * would keep compositing a hidden preview from a stale layout, and the mod's modal screens draw
     * their parent inline through {@code GuiCompat.renderParent}, so the screen being rendered is
     * not always the screen the widget belongs to. (No modal parents a vanilla screen today; every
     * one of them is opened from the skin or cape menu, whose previews never defer.)
     */
    private boolean previewSubmissionPending;

    /** Mouse position the deferred frame was rendered with, replayed verbatim on submission. */
    private int pendingMouseX;
    private int pendingMouseY;

    /**
     * Whether a deferred frame is waiting to be composited.
     *
     * <p>Lets the overlay pass decide whether to open a stratum at all, so a frame with nothing to
     * submit costs nothing and leaves the render state exactly as vanilla built it.
     */
    public boolean hasDeferredPreview() {
        return previewSubmissionPending;
    }

    /**
     * Take the deferred frame, if there is one.
     *
     * <p>Returns {@code false} and does nothing when the preview was drawn inline, when the widget
     * did not render this frame, or when a previous overlay pass already consumed it - so it is safe
     * to call from every screen's post-render hook.
     */
//? if <26.1 {
    public boolean submitDeferredPreview(GuiGraphics graphics) {
//?} else {
    public boolean submitDeferredPreview(GuiGraphicsExtractor graphics) {
//?}
        if (!previewSubmissionPending) {
            return false;
        }
        previewSubmissionPending = false;
        submitPreview(graphics, pendingMouseX, pendingMouseY);
        return true;
    }

    // Button references for positioning (like the original mod)
    private net.minecraft.client.gui.components.Button autoButton = null;
    private net.minecraft.client.gui.components.Button classicButton = null;
    private net.minecraft.client.gui.components.Button slimButton = null;

    // Custom reference point (alternative to button positioning)
    private Integer customCenterX = null;
    private Integer customCenterY = null;

//? if <1.21 {
    // Priority areas (buttons that should take precedence over model interaction)
    private java.util.List<net.minecraft.client.gui.components.AbstractWidget> priorityWidgets = new java.util.ArrayList<>();

    // Static reference to track which PlayerWidget is currently being interacted with
    private static PlayerWidget activeInteractionWidget = null;

//?} else {
//?}
    // Default offset from button center
    private static final double DEFAULT_OFFSET_FROM_BUTTON_Y = -15.0; // Moved up 5px from -15.0

    /**
     * Creates a new player widget
     * @param x X position
     * @param y Y position
     * @param width Widget width
     * @param height Widget height
     * @param skinLocation Initial skin texture (can be null)
     * @param capeLocation Initial cape texture (can be null)
     * @param capeId ID of the cape, for animations (can be null)
     * @param modelType "slim" or "classic"
     */
    public PlayerWidget(int x, int y, int width, int height,
//? if <1.21.11 {
                        @Nullable ResourceLocation skinLocation,
                        @Nullable ResourceLocation capeLocation,
//?} else {
                        @Nullable Identifier skinLocation,
                        @Nullable Identifier capeLocation,
//?}
                        @Nullable String capeId,
                        String modelType) {
        super(x, y, width, height, Component.empty());

        this.previewData = new PreviewPlayerData();
        this.previewData.setSkinLocation(
//? if <1.21 {
                skinLocation != null ? skinLocation : new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png")
//?} else if <1.21.11 {
                skinLocation != null ? skinLocation : ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png")
//?} else {
                skinLocation != null ? skinLocation : Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png")
//?}
        );
        this.previewData.setCapeLocation(capeLocation);
        this.previewData.setCapeId(capeId);
        this.previewData.setModelType(modelType != null ? modelType : "classic");

        // Ensure widget is visible and active
        this.visible = true;
        this.active = true;
    }

    /**
     * Get the X position for model rendering (dynamically calculated)
     * Uses custom reference first, then model buttons if available, otherwise widget center
     * Applies saved position offset from config
     */
    private int getModelCenterX() {
        // Get saved offset from config
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        int offsetX = getPositionOffsetXFromConfig(config);

        // Priority 1: Custom reference point
        if (customCenterX != null) {
            return customCenterX + offsetX;
        }
        // Priority 2: In skin menu: Center of all three model buttons (Auto, Wide, Slim)
        if (autoButton != null && slimButton != null) {
            // Calculate center of the entire button group
            int leftEdge = autoButton.getX();
            int rightEdge = slimButton.getX() + slimButton.getWidth();
            int middleX = (leftEdge + rightEdge) / 2;
            return middleX + offsetX;
        }
        // Priority 3: Fallback: if only classic/slim buttons exist
        else if (classicButton != null && slimButton != null) {
            int classicCenterX = classicButton.getX() + classicButton.getWidth() / 2;
            int slimCenterX = slimButton.getX() + slimButton.getWidth() / 2;
            int middleX = (classicCenterX + slimCenterX) / 2;
            return middleX + offsetX;
        }
        // Priority 4: Fallback to widget center if no reference
        return getX() + this.width / 2 + offsetX;
    }

    /**
     * Get the base Y position for model rendering (without scale adjustments)
     * Uses custom reference first, then Classic/Slim buttons if available, otherwise widget center
     * Applies saved position offset from config
     */
    private int getBaseModelCenterY() {
        // Get saved offset from config
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        int offsetY = getPositionOffsetYFromConfig(config);

        // Priority 1: Custom reference point
        if (customCenterY != null) {
            return customCenterY + offsetY;
        }
        // Priority 2: In skin menu: Classic button Y coordinate (Classic and Slim are on same Y)
        if (classicButton != null) {
            int buttonCenterY = classicButton.getY() + classicButton.getHeight() / 2;
            return (int)(buttonCenterY + DEFAULT_OFFSET_FROM_BUTTON_Y) + offsetY;
        }
        // Priority 3: Fallback to widget center if no reference
        return getY() + this.height / 2 + 10 + offsetY; // Offset down slightly
    }

    /**
     * Get the Y position for model rendering (adjusted for current scale with pivot point)
     * The pivot point is below the model's feet, so scaling keeps that point fixed
     */
    private int getModelCenterY() {
        int baseY = getBaseModelCenterY();

        // Calculate the pivot point Y (at default scale, it's PIVOT_OFFSET below the model center)
        // This point remains fixed regardless of scale
        float pivotY = baseY + PIVOT_OFFSET;

        // Calculate where the model center should be based on current scale
        // As scale increases, model moves up (away from pivot)
        // As scale decreases, model moves down (toward pivot)
        float scaleRatio = scale / DEFAULT_SCALE;
        float offsetFromPivot = PIVOT_OFFSET * scaleRatio;

        return (int)(pivotY - offsetFromPivot);
    }

    /**
     * Set button references for positioning
     */
    public void setModelButtons(net.minecraft.client.gui.components.Button auto,
                                net.minecraft.client.gui.components.Button classic,
                                net.minecraft.client.gui.components.Button slim) {
        this.autoButton = auto;
        this.classicButton = classic;
        this.slimButton = slim;
    }

    /**
     * Set custom reference point for positioning (alternative to button references)
     * @param centerX X coordinate of the reference point
     * @param centerY Y coordinate of the reference point
     */
    public void setCustomReferencePoint(int centerX, int centerY) {
        this.customCenterX = centerX;
        this.customCenterY = centerY;
    }

    /**
     * Report the drawn model box as this widget's rectangle, for layout editors such as FancyMenu.
     *
     * <p>Call once, after {@link #setContext} and before the widget is added to {@code host}. The
     * model keeps the anchor the widget's constructor rectangle gives it; from here on the widget's
     * position and size fields hold the model box around that anchor instead of that rectangle.
     * The size getters still report 0x0 until {@link #publishLayoutBounds()}.
     */
    public void reportModelBoundsForLayout(net.minecraft.client.gui.screens.Screen host) {
        // The same anchor getModelCenterX()/getBaseModelCenterY() derive from the widget rectangle.
        layoutAnchorX = getX() + this.width / 2;
        layoutAnchorFeetY = getY() + this.height / 2 + 10;
        deferralHost = new java.lang.ref.WeakReference<>(host);
        reportsModelBounds = true;
        syncNaturalBounds();
    }

    /**
     * Start reporting the real size, once the host screen has been built (its first rendered frame).
     *
     * <p>Mods that rearrange their own buttons around other widgets measure them while the screen is
     * being initialised; In-Game Account Switcher does. Reporting 0x0 until then keeps this large
     * decorative widget from pushing them away, while a layout editor, which reads the size when it
     * renders, still sees the model box.
     */
    public void publishLayoutBounds() {
        if (reportsModelBounds) {
            layoutBoundsPublished = true;
        }
    }

    /**
     * Write the natural model box (configured scale and offsets) to the widget's fields.
     *
     * <p>Writes only when the box changed, so it does not fight a layout editor that overrides the
     * size fields while the configuration stays the same.
     */
    private void syncNaturalBounds() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        float scaleForConfig = PreviewLayoutBox.scaleForPercentage(getSliderPercentageFromConfig(config));
        PreviewLayoutBox.Box box = PreviewLayoutBox.natural(
                layoutAnchorX + getPositionOffsetXFromConfig(config),
                layoutAnchorFeetY + getPositionOffsetYFromConfig(config),
                scaleForConfig);
        naturalScale = scaleForConfig;
        if (!box.equals(naturalBox)) {
            setX(box.x());
            setY(box.y());
            this.width = box.width();
            this.height = box.height();
            naturalBox = box;
        }
    }

    /** True while a layout editor places this preview somewhere other than its natural box. */
    private boolean layoutManagesPosition() {
        return reportsModelBounds && naturalBox != null
                && (getX() != naturalBox.x() || getY() != naturalBox.y());
    }

    /** True while a layout editor gives this preview a size other than its natural box. */
    private boolean layoutManagesSize() {
        return reportsModelBounds && naturalBox != null
                && (super.getWidth() != naturalBox.width() || super.getHeight() != naturalBox.height());
    }

    /**
     * Set the context for this widget (determines slider positioning)
     * @param context The context (TITLE_SCREEN, SKIN_MENU, or OTHER)
     */
    public void setContext(WidgetContext context) {
        this.context = context;
//? if <1.21 {
    }

    /**
     * Register a widget (like a button) that should take priority over model interactions
     * When clicking on these widgets, the player model won't capture the click
     * @param widget The widget to give priority (e.g., rotate button, animation buttons)
     */
    public void addPriorityWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
        if (widget != null && !priorityWidgets.contains(widget)) {
            priorityWidgets.add(widget);
        }
    }

    /**
     * Clear all priority widgets
     */
    public void clearPriorityWidgets() {
        priorityWidgets.clear();
    }

    /**
     * Check if a mouse position is over any priority widget
     */
    private boolean isOverPriorityWidget(double mouseX, double mouseY) {
        for (net.minecraft.client.gui.components.AbstractWidget widget : priorityWidgets) {
            if (widget.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the currently active PlayerWidget (one being dragged/rotated)
     * Used by global event handlers to route mouse release events
     */
    public static PlayerWidget getActiveInteractionWidget() {
        return activeInteractionWidget;
    }

    /**
     * Check if this widget is currently being interacted with
     */
    public boolean isInteracting() {
        return isDragging || isRotating;
//?} else {
//?}
    }

    /**
     * Get the slider percentage from config based on current context
     * When config is 0, returns built-in default for that context
     */
    private int getSliderPercentageFromConfig(com.quickskin.mod.config.ClientConfig config) {
        int configValue;
        int defaultValue;

        switch (context) {
            case TITLE_SCREEN:
                configValue = config.sizeModelPreviewPercentageTitleScreen;
                defaultValue = 30;
                break;
            case SKIN_MENU:
                configValue = config.sizeModelPreviewPercentageSkinMenu;
                defaultValue = 51;
                break;
            case CAPE_MENU:
                configValue = config.sizeModelPreviewPercentageCapeMenu;
                defaultValue = 51;
                break;
            case PAUSE_MENU:
                configValue = config.sizeModelPreviewPercentagePauseMenu;
                defaultValue = 32;
                break;
            default:
                return 50;
        }

        return configValue != 0 ? configValue : defaultValue;
    }

    /**
     * Save the slider percentage to config based on current context
     */
    private void saveSliderPercentageToConfig(int percentage) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        switch (context) {
            case TITLE_SCREEN:
                config.sizeModelPreviewPercentageTitleScreen = percentage;
                break;
            case SKIN_MENU:
                config.sizeModelPreviewPercentageSkinMenu = percentage;
                break;
            case CAPE_MENU:
                config.sizeModelPreviewPercentageCapeMenu = percentage;
                break;
            case PAUSE_MENU:
                config.sizeModelPreviewPercentagePauseMenu = percentage;
                break;
            case OTHER:
                // Don't save for OTHER context
                break;
        }
        config.save();
    }

    /**
     * Get the position X offset from config based on current context
     * Returns the raw config value (defaults are baked into base positions)
     */
    private int getPositionOffsetXFromConfig(com.quickskin.mod.config.ClientConfig config) {
        return switch (context) {
            case TITLE_SCREEN -> config.positionOffsetXTitleScreen;
            case SKIN_MENU -> config.positionOffsetXSkinMenu;
            case CAPE_MENU -> config.positionOffsetXCapeMenu;
            case PAUSE_MENU -> config.positionOffsetXPauseMenu;
            default -> 0;
        };
    }

    /**
     * Get the position Y offset from config based on current context
     * Returns the raw config value (defaults are baked into base positions)
     */
    private int getPositionOffsetYFromConfig(com.quickskin.mod.config.ClientConfig config) {
        return switch (context) {
            case TITLE_SCREEN -> config.positionOffsetYTitleScreen;
            case SKIN_MENU -> config.positionOffsetYSkinMenu;
            case CAPE_MENU -> config.positionOffsetYCapeMenu;
            case PAUSE_MENU -> config.positionOffsetYPauseMenu;
            default -> 0;
        };
    }

    /**
     * Save position offsets to config based on current context
     */
    private void savePositionOffsetsToConfig(int offsetX, int offsetY) {
        // A layout editor owns the position; a saved offset would not move the model.
        if (layoutManagesPosition()) {
            return;
        }
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        switch (context) {
            case TITLE_SCREEN:
                config.positionOffsetXTitleScreen = offsetX;
                config.positionOffsetYTitleScreen = offsetY;
                break;
            case SKIN_MENU:
                config.positionOffsetXSkinMenu = offsetX;
                config.positionOffsetYSkinMenu = offsetY;
                break;
            case CAPE_MENU:
                config.positionOffsetXCapeMenu = offsetX;
                config.positionOffsetYCapeMenu = offsetY;
                break;
            case PAUSE_MENU:
                config.positionOffsetXPauseMenu = offsetX;
                config.positionOffsetYPauseMenu = offsetY;
                break;
            case OTHER:
                // Don't save for OTHER context
                return;
        }
        config.save();
    }

    @Override
//? if <26.1 {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
//?} else {
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
//?}
        // Load and apply scale from config
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        int savedPercentage = getSliderPercentageFromConfig(config);

        // Convert percentage (1-100%) to scale value
        float percentageAsFloat = (savedPercentage - 1) / 99.0f; // Convert 1-100 to 0.0-1.0
        scale = MIN_SCALE + percentageAsFloat * (MAX_SCALE - MIN_SCALE);

        // Ensure cape animation is registered before rendering
        if (previewData.getCapeId() != null && previewData.getCapeLocation() != null) {
            String capeId = previewData.getCapeId();
            String animationId =
                com.quickskin.mod.client.services.CapeAnimationIds.deriveAnimationId(capeId);

            // Check if animation should exist but isn't registered yet
            if (animationId != null) {
                com.quickskin.mod.client.services.AnimatedTextureManager animManager =
                    com.quickskin.mod.client.services.AnimatedTextureManager.getInstance();

                if (!animManager.isAnimated(animationId)) {
                    // Animation not registered yet - try to register it now
                    if (capeId.startsWith("local_cape:")) {
                        String hash = capeId.substring("local_cape:".length());
//? if <26.1 {
                        com.quickskin.mod.common.data.AnimationMetadata metadata =
                            com.quickskin.mod.client.services.LocalAssetManager.getInstance().getAnimationMetadata(hash);
                        java.awt.image.BufferedImage atlasImage =
                            com.quickskin.mod.client.services.LocalAssetManager.getInstance().getSourceImage(hash);

                        if (metadata != null && atlasImage != null) {
                            animManager.registerAnimation(animationId, capeId, previewData.getCapeLocation(), atlasImage, metadata);
                        }
//?} else {
                        animManager.registerAnimationAsync(
                                animationId, capeId, previewData.getCapeLocation(), hash);
//?}
                    } else if (capeId.startsWith("known:")) {
                        String knownId = capeId.substring("known:".length());
                        com.quickskin.mod.client.services.CapeService.getInstance().loadKnownCape(knownId);
                    }
                }
            }
        }

        // Tick animations (updates frame indices for animated capes)
        // This is necessary because ClientTickEvent.CLIENT_POST doesn't fire in menus
        com.quickskin.mod.client.services.AnimatedTextureManager animManager =
            com.quickskin.mod.client.services.AnimatedTextureManager.getInstance();
        animManager.tick();
//? if <1.21 {

        // Handle return-to-original animation when not rotating
        if (shouldReturnToOriginal && !isRotating) {
            // Smoothly return to original rotation
            float diff = originalYRotation - targetYRotation;
            if (Math.abs(diff) > 0.1f) {
                targetYRotation += diff * 0.08f; // Slow smooth return
            } else {
                targetYRotation = originalYRotation;
                shouldReturnToOriginal = false;
            }
        }
//?} else {
//?}

        // Smoothly animate rotation towards target
        if (Math.abs(targetYRotation - bodyYaw) > 0.1f) {
            float diff = targetYRotation - bodyYaw;
            // Smooth interpolation (lerp with factor 0.15)
            bodyYaw += diff * 0.15f;
        } else {
            bodyYaw = targetYRotation;
        }

        // Get current model center (recalculated each frame for correct rotation pivot)
        int modelCenterX;
        int modelCenterY;
        if (reportsModelBounds) {
            // Fit the model into the rectangle a layout editor may have moved or resized. The size
            // comes from AbstractWidget's getters, which the editor overrides; an untouched
            // rectangle gives back exactly the configured anchor and scale.
            syncNaturalBounds();
            PreviewLayoutBox.Placement placement = PreviewLayoutBox.place(
                    naturalBox, naturalScale, getX(), getY(), super.getWidth(), super.getHeight());
            scale = placement.scale();
            modelCenterX = placement.centerX();
            modelCenterY = placement.feetY();
        } else {
            modelCenterX = getModelCenterX();
            modelCenterY = getModelCenterY();
        }

        // Cache these values for mouse interaction
        cachedModelCenterX = modelCenterX;
        cachedModelCenterY = modelCenterY;
        cachedScale = scale;

        // Update preview data
        previewData.setYRotation(bodyYaw);
        previewData.setHeadYaw(0.0f);
        previewData.setHeadPitch(0.0f);

        // On a vanilla screen under the painter's-order GUI pipeline the host paints its own
        // foreground (the title screen's logo, splash and version string) after this renderable
        // pass, so the model is handed to the post-screen overlay pass instead of being submitted
        // here. Everything above is frame state rather than drawing - the rotation lerp, the
        // animation tick, the interaction cache and the preview data - so it keeps running exactly
        // as before, and the mouse position is carried over so the deferred submission is the same
        // draw this pass would have made.
        // In layout-box mode the widget can also be drawn by a layout editor that is the current
        // screen instead of the host; that draw stays inline so it keeps the editor's paint order.
        if (defersToScreenOverlay()
                && (!reportsModelBounds || GuiCompat.currentScreen() == deferralHost.get())) {
            previewSubmissionPending = true;
            pendingMouseX = mouseX;
            pendingMouseY = mouseY;
            return;
        }

        submitPreview(graphics, mouseX, mouseY);
    }

    /**
     * Hand the model, and its optional debug border, to the GUI.
     *
     * <p>Called inline from the renderable pass on every screen the mod itself draws, and from the
     * post-screen overlay pass on the vanilla screens the mod only injects a widget into. See
     * {@link PreviewCompositeOrder} for which of the two applies and why.
     *
     * <p>Reads the frame state cached by the render pass rather than recomputing the layout, so the
     * model is always drawn exactly where the mouse-interaction cache says it is.
     */
//? if <26.1 {
    public void submitPreview(GuiGraphics graphics, int mouseX, int mouseY) {
//?} else {
    public void submitPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
//?}
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        int modelCenterX = cachedModelCenterX;
        int modelCenterY = cachedModelCenterY;
        float previewScale = cachedScale;

        // Render the player model
//? if <1.21.9 {
        // Use the era-specific immediate or picture-in-picture backend.
        PreviewRenderBackend.INSTANCE.renderPlayerModel(
//?} else if <26.1 {
        // Use GuiGraphics directly for vanilla rendering method
        PreviewRenderBackend.INSTANCE.renderPlayerModel(
//?} else {
        // Use GuiGraphicsExtractor directly for vanilla rendering method
        PreviewRenderBackend.INSTANCE.renderPlayerModel(
//?}
                graphics,
                modelCenterX,
                modelCenterY,
                previewScale,
                bodyYaw,
                previewData,
                mouseX,
                mouseY,
                false
        );

        // Render border around actual player model rendering area (only if customization is enabled)
        if (config.enablePlayerPreviewCustomization) {
            renderModelBorder(graphics, modelCenterX, modelCenterY, previewScale);
        }
    }

    /**
     * Render a border around where the player model is actually rendered (for debugging/positioning)
     */
//? if <26.1 {
    private void renderModelBorder(GuiGraphics graphics, int centerX, int centerY, float scale) {
//?} else {
    private void renderModelBorder(GuiGraphicsExtractor graphics, int centerX, int centerY, float scale) {
//?}
        // Approximate the player model bounds
        // A Minecraft player is ~1.8 blocks tall, and with scale, this gives us the visual height
        // The width is approximately 60% of the height for a standing player
        int modelHeight = (int)(scale * 2.0f); // Approximate rendered height
        int modelWidth = (int)(modelHeight * 0.6f); // Approximate rendered width

        // Calculate bounding box (model is centered horizontally, feet at centerY)
        int left = centerX - modelWidth / 2;
        int right = centerX + modelWidth / 2;
        int top = centerY - modelHeight;

        // Draw instructional text above the border
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
//? if <1.21 {

        // Different instructions based on customization mode
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        String instructionText = config.enablePlayerPreviewCustomization
            ? "LMB: move | RMB: rotate | Wheel: resize"
            : "LMB/RMB drag: rotate";

//?} else {
        String instructionText = "Mouse wheel: resize | Left click: move";
//?}
        int textWidth = font.width(instructionText);
        int textX = centerX - textWidth / 2; // Center the text horizontally
        int textY = top - 12; // Position text 12 pixels above the border
        int textColor = 0xFFFFFFFF; // White text

        // Draw text with shadow for better readability
//? if <26.1 {
        graphics.drawString(font, instructionText, textX, textY, textColor, true);
//?} else {
        graphics.text(font, instructionText, textX, textY, textColor, true);
//?}

        // Draw border (2 pixels thick for visibility) in bright green
        int borderColor = 0xFF00FF00; // Bright green

        // Top edge
        graphics.fill(left, top, right, top + 2, borderColor);
        // Bottom edge
        graphics.fill(left, centerY - 2, right, centerY, borderColor);
        // Left edge
        graphics.fill(left, top, left + 2, centerY, borderColor);
        // Right edge
        graphics.fill(right - 2, top, right, centerY, borderColor);

        // Draw center crosshair to show exact model center
        int crosshairSize = 5;
        int crosshairColor = 0xFFFF0000; // Red
        // Horizontal line
        graphics.fill(centerX - crosshairSize, centerY - 1, centerX + crosshairSize, centerY + 1, crosshairColor);
        // Vertical line
        graphics.fill(centerX - 1, centerY - crosshairSize, centerX + 1, centerY + crosshairSize, crosshairColor);
    }

    /**
     * Update the skin texture
     */
//? if <1.21.11 {
    public void setSkin(@Nullable ResourceLocation skinLocation) {
//?} else {
    public void setSkin(@Nullable Identifier skinLocation) {
//?}
        if (skinLocation != null) {
            previewData.setSkinLocation(skinLocation);
        }
    }

    /**
     * Update the cape texture and ID
     */
//? if <1.21.11 {
    public void setCape(@Nullable ResourceLocation capeLocation, @Nullable String capeId) {
//?} else {
    public void setCape(@Nullable Identifier capeLocation, @Nullable String capeId) {
//?}
        previewData.setCapeLocation(capeLocation);
        previewData.setCapeId(capeId);
        // An editor pushed a selection, so this preview owns its cape from here on: the entity
        // render path must draw this cape rather than the one the player currently wears.
        previewData.markCapeAuthoritative();
    }

    /**
     * Update the model type
     */
    public void setModelType(String modelType) {
        previewData.setModelType(modelType);
    }

    /**
     * Toggle rotation - adds 180 degrees to target rotation
     * Allows spamming for continuous spin
     */
    public void toggleRotation() {
        targetYRotation += 180.0f;
//? if <1.21 {
        originalYRotation += 180.0f; // Update the "return-to" position
        shouldReturnToOriginal = false; // Cancel any ongoing return animation
//?} else {
//?}
    }

    /**
     * Get current body yaw (current rotation)
     */
    public float getBodyYaw() {
        return bodyYaw;
    }

    /**
     * Get target rotation (where it's animating towards)
     */
    public float getTargetYRotation() {
        return targetYRotation;
    }

    /**
     * Set rotation state (for restoring after widget recreation)
     */
    public void setRotationState(float bodyYaw, float targetYRotation) {
        this.bodyYaw = bodyYaw;
        this.targetYRotation = targetYRotation;
    }

    /**
     * Set the animation state for the player model
     * @param animation Animation name (idle, walk, run, sneak, sit, jump)
     */
    public void setAnimation(String animation) {
        if (animation != null && !animation.isEmpty()) {
            previewData.setCurrentAnimation(animation);
        }
    }

    /**
     * Get the current animation
     */
    public String getAnimation() {
        return previewData.getCurrentAnimation();
    }

    // --- Layout size override ---
    // Report 0x0 size to the layout system so other mods' overlap detection
    // (e.g. In-Game Account Switcher) won't be pushed away by this large decorative widget.
    // Internal code uses this.width / this.height directly to bypass these overrides.
    // In layout-box mode (FancyMenu present) the real model box is reported from the host screen's
    // first rendered frame on: overlap checks run while the screen is built and still see 0x0, and
    // the layout editor sees the box. The call goes through super so the editor's own size
    // overrides on AbstractWidget apply. AbstractWidget.render does not change the cursor, so a
    // non-zero size has no cursor side effect.
    @Override
    public int getWidth() {
        return reportsModelBounds && layoutBoundsPublished ? super.getWidth() : 0;
    }

    @Override
    public int getHeight() {
        return reportsModelBounds && layoutBoundsPublished ? super.getHeight() : 0;
    }

    @Override
//? if <1.21.9 {
    public void onClick(double mouseX, double mouseY) {
//?} else {
    public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
//?}
        // Do nothing - make widget non-clickable
    }

    @Override
//? if <1.21 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Priority check: If clicking on a priority widget (like rotate/animation buttons),
        // let that widget handle it instead
        if (isOverPriorityWidget(mouseX, mouseY)) {
            return false; // Don't consume the event, let the button handle it
//?} else if <1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Check if customization feature is enabled and left click
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.enablePlayerPreviewCustomization || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
//?} else if <26.1 {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.buttonInfo().button();
        // Check if customization feature is enabled and left click
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.enablePlayerPreviewCustomization || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
//?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
        // Check if customization feature is enabled and left click
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.enablePlayerPreviewCustomization || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
//?}
        }

        // Only handle clicks within the model's interactive area (the green box)
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }

//? if <1.21 {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
//?} else {
        // Start dragging
        isDragging = true;
        dragStartX = (int)mouseX;
        dragStartY = (int)mouseY;
        dragStartOffsetX = getPositionOffsetXFromConfig(config);
        dragStartOffsetY = getPositionOffsetYFromConfig(config);
//?}

//? if <1.21 {
        // Left-click: Position dragging (only in customization mode) OR rotation (always)
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            // In customization mode: left-click drags position
            if (config.enablePlayerPreviewCustomization) {
                isDragging = true;
                dragStartX = (int)mouseX;
                dragStartY = (int)mouseY;
                dragStartOffsetX = getPositionOffsetXFromConfig(config);
                dragStartOffsetY = getPositionOffsetYFromConfig(config);
                activeInteractionWidget = this; // Register as active for global event handling
                return true;
            }
            // In normal mode: left-click rotates
            else {
                isRotating = true;
                rotationDragStartX = (int)mouseX;
                rotationDragStartYaw = targetYRotation;
                shouldReturnToOriginal = false; // Cancel any ongoing return animation
                activeInteractionWidget = this; // Register as active for global event handling
                return true;
            }
        }
        // Right-click: Always rotation dragging (works in both modes)
        else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            isRotating = true;
            rotationDragStartX = (int)mouseX;
            rotationDragStartYaw = targetYRotation;
            shouldReturnToOriginal = false; // Cancel any ongoing return animation
            activeInteractionWidget = this; // Register as active for global event handling
            return true;
        }

        return false;
//?} else {
        return true;
//?}
    }

    @Override
//? if <1.21 {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = false;

        // Handle position dragging release (left-click only)
        if (isDragging && button == InputConstants.MOUSE_BUTTON_LEFT) {
            isDragging = false;
            if (activeInteractionWidget == this) {
                activeInteractionWidget = null; // Clear active widget
            }
            handled = true;
//?} else if <1.21.9 {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!isDragging || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
//?} else if <26.1 {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        int button = event.buttonInfo().button();
        if (!isDragging || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
//?} else {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        int button = GuiCompat.mouseButton(event);
        if (!isDragging || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
//?}
        }

//? if <1.21 {
        // Handle rotation dragging release (left-click in normal mode, or right-click in any mode)
        if (isRotating && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)) {
            isRotating = false;
            shouldReturnToOriginal = true; // Start smooth return to original rotation
            if (activeInteractionWidget == this) {
                activeInteractionWidget = null; // Clear active widget
            }
            handled = true;
        }

        return handled;
//?} else {
        // Stop dragging
        isDragging = false;
        return true;
//?}
    }

    @Override
//? if <1.21 {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Handle position dragging (left-click in customization mode)
        if (isDragging && button == InputConstants.MOUSE_BUTTON_LEFT) {
            // Calculate new offsets based on drag distance
            int deltaX = (int)mouseX - dragStartX;
            int deltaY = (int)mouseY - dragStartY;

            int newOffsetX = dragStartOffsetX + deltaX;
            int newOffsetY = dragStartOffsetY + deltaY;

            // Save the new offsets to config
            savePositionOffsetsToConfig(newOffsetX, newOffsetY);
            return true;
//?} else if <1.21.9 {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!isDragging || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
//?} else if <26.1 {
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.buttonInfo().button();
        if (!isDragging || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
//?} else {
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
        if (!isDragging || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
//?}
        }

//? if <1.21 {
        // Handle rotation dragging (left-click in normal mode, or right-click in any mode)
        if (isRotating && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)) {
            // Calculate rotation based on horizontal mouse movement
            int deltaX = (int)mouseX - rotationDragStartX;
            float rotationDelta = deltaX * ROTATION_SENSITIVITY;
//?} else {
        // Calculate new offsets based on drag distance
        int deltaX = (int)mouseX - dragStartX;
        int deltaY = (int)mouseY - dragStartY;
//?}

//? if <1.21 {
            // Update target rotation
            targetYRotation = rotationDragStartYaw - rotationDelta; // Negative for natural rotation direction
//?} else {
        int newOffsetX = dragStartOffsetX + deltaX;
        int newOffsetY = dragStartOffsetY + deltaY;
//?}

//? if <1.21 {
            return true;
        }
//?} else {
        // Save the new offsets to config
        savePositionOffsetsToConfig(newOffsetX, newOffsetY);
//?}

//? if <1.21 {
        return false;
//?} else {
        return true;
//?}
    }

    @Override
//? if <1.21 {
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
//?} else {
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
//?}
        // Check if customization feature is enabled
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.enablePlayerPreviewCustomization) {
            return false;
        }

        // Only handle scroll events within the model's interactive area (the green box)
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }

        // A layout editor owns the size; let the scroll pass instead of saving a scale it overrides.
        if (layoutManagesSize()) {
            return false;
        }

        // Adjust scale based on scroll direction
        float oldScale = scale;
//? if <1.21 {
        scale += (float)delta * SCALE_STEP;
//?} else {
        scale += (float)deltaY * SCALE_STEP;
//?}

        // Clamp to min/max
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));

        // Only save if scale actually changed
        if (scale != oldScale) {
            // Calculate and save percentage
            float scaleRange = MAX_SCALE - MIN_SCALE;
            float currentScaleOffset = scale - MIN_SCALE;
            int percentage = Math.round((currentScaleOffset / scaleRange) * 99.0f) + 1; // 1-100%

            // Save to config
            saveSliderPercentageToConfig(percentage);
        }

        return true; // Consume the scroll event
    }

    /**
     * Check if mouse is over this widget
     * When customization is enabled, use the model area (green border).
     * Otherwise, use the full widget bounds.
     */
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // An inactive preview takes no input. MenuIntegration deactivates the title/pause preview
        // while it is hidden; every other caller leaves the widget active.
        if (!this.active) {
            return false;
        }
//? if <1.21 {
        // If we're actively dragging or rotating, we need to receive all mouse events
        // regardless of where the cursor is
        if (isDragging || isRotating) {
            return true;
//?} else {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // When customization is enabled, check if mouse is inside the model area (green border)
        if (config.enablePlayerPreviewCustomization) {
            return !isMouseOutsideModelArea(mouseX, mouseY, cachedModelCenterX, cachedModelCenterY, cachedScale);
//?}
        }

//? if <1.21 {
        // Always check if mouse is inside the model area (green border)
        // This ensures consistent interaction whether in customization mode or normal mode
        return !isMouseOutsideModelArea(mouseX, mouseY, cachedModelCenterX, cachedModelCenterY, cachedScale);
//?} else {
        // When customization is disabled, this widget is purely visual and should not
        // intercept mouse events. Returning false allows buttons (rotate, animation)
        // that overlap the widget area to receive clicks properly.
        return false;
//?}
    }

    /**
     * Check if mouse is outside the player model rendering area (the green debug border area)
     */
    private boolean isMouseOutsideModelArea(double mouseX, double mouseY, int centerX, int centerY, float scale) {
        // Calculate the same bounds as renderModelBorder()
        int modelHeight = (int)(scale * 2.0f);
        int modelWidth = (int)(modelHeight * 0.6f);

        int left = centerX - modelWidth / 2;
        int right = centerX + modelWidth / 2;
        int top = centerY - modelHeight;

        return mouseX < left || mouseX > right ||
               mouseY < top || mouseY > centerY;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Add accessibility narration
        narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                Component.translatable("quickskin.preview.narration"));
    }
}
