package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.platform.Lighting;
//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
//?} else {
//?}
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.CapeAnimationHelper;
//? if <1.21 {
import com.quickskin.mod.platform.MinecraftCompat;
//?} else if <1.21.11 {
import com.quickskin.mod.platform.PlatformHelper;
//?} else {
import com.quickskin.mod.platform.MinecraftCompat;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
//? if <1.21.4 {
import net.minecraft.client.model.PlayerModel;
//?} else if <1.21.11 {
import net.minecraft.client.model.PlayerCapeModel;
import net.minecraft.client.model.PlayerModel;
//?} else if <26.2 {
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
//?} else {
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
//?}
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
//? if <1.21.11 {
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
//?} else if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?}
import net.minecraft.client.renderer.texture.OverlayTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

//? if >=1.21.6 {
import java.util.Map;
    //? if <26.2 {
import java.util.LinkedHashMap;
    //?} else {
import java.util.IdentityHashMap;
    //?}
//?}
import java.util.Locale;

/**
 * Utility for rendering player models in GUI using vanilla Minecraft rendering
 * Replaces GeckoLib-based rendering with vanilla PlayerModel
 */
@Environment(EnvType.CLIENT)
public class PlayerModelRenderer {

    private static final boolean DETERMINISTIC_E2E_RENDER =
            Boolean.getBoolean("quickskin.e2e.enabled");
    private static final int E2E_FIXED_PREVIEW_TICK = 120;
    private static final long E2E_FIXED_ANIMATION_TIME_MS = 6_000L;

    private static long animationTimeMillis() {
        return DETERMINISTIC_E2E_RENDER
                ? E2E_FIXED_ANIMATION_TIME_MS
                : System.currentTimeMillis();
    }

//? if <1.21 {
    private static PlayerModel<?>  classicModel;
    private static PlayerModel<?> slimModel;

    // Animation frequency optimization - update at 30 FPS instead of 60+ FPS
    private static long lastAnimationUpdate = 0;
    private static final long ANIMATION_UPDATE_INTERVAL_MS = 33; // ~30 FPS (1000/30)

    // Lighting optimization - only setup lighting once
    private static boolean lightingSetup = false;

    // Grass block caching - cache is built on first use
    private static boolean grassBlockCacheBuilt = false;
//?} else if <1.21.4 {
    private static PlayerModel<?>  classicModel;
    private static PlayerModel<?> slimModel;
//?} else if <1.21.6 {
    private static PlayerModel classicModel;
    private static PlayerModel slimModel;
    private static PlayerCapeModel capeModel;
//?} else if <26.2 {
    private static PlayerModel  classicModel;
    private static PlayerModel slimModel;
    private static PlayerCapeModel capeModel;

    // Match cape data to the exact value-state used by the PiP cache.
    private static final Map<PreviewCapeKey, PreviewCapeState> PENDING_CAPES =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final int MAX_PENDING_CAPES = 128;

    public record PreviewCapeState(
//? if <1.21.11 {
            ResourceLocation texture, PlayerModel bodyModel, PlayerCapeModel capeModel) {
//?} else {
            Identifier texture, PlayerModel bodyModel, PlayerCapeModel capeModel) {
//?}
    }

    private record PreviewCapeKey(
//? if <1.21.11 {
            PlayerModel model, ResourceLocation skin, float rotationX, float rotationY, float pivotY,
//?} else {
            PlayerModel model, Identifier skin, float rotationX, float rotationY, float pivotY,
//?}
            int x0, int y0, int x1, int y1, float scale) {
    }

    private static void registerPendingCape(
//? if <1.21.11 {
            PlayerModel bodyModel, ResourceLocation skin, float rotationX, float rotationY, float pivotY,
            int x0, int y0, int x1, int y1, float scale,
            ResourceLocation texture, PlayerCapeModel playerCapeModel) {
//?} else {
            PlayerModel bodyModel, Identifier skin, float rotationX, float rotationY, float pivotY,
            int x0, int y0, int x1, int y1, float scale,
            Identifier texture, PlayerCapeModel playerCapeModel) {
//?}
        synchronized (PENDING_CAPES) {
            PreviewCapeKey key = new PreviewCapeKey(
                    bodyModel, skin, rotationX, rotationY, pivotY, x0, y0, x1, y1, scale);
            if (!PENDING_CAPES.containsKey(key) && PENDING_CAPES.size() >= MAX_PENDING_CAPES) {
                var oldest = PENDING_CAPES.keySet().iterator();
                if (oldest.hasNext()) PENDING_CAPES.remove(oldest.next());
            }
            PENDING_CAPES.put(key, new PreviewCapeState(texture, bodyModel, playerCapeModel));
        }
    }

    public static PreviewCapeState consumePendingCape(
//? if <1.21.11 {
            PlayerModel bodyModel, ResourceLocation skin, float rotationX, float rotationY, float pivotY,
//?} else {
            PlayerModel bodyModel, Identifier skin, float rotationX, float rotationY, float pivotY,
//?}
            int x0, int y0, int x1, int y1, float scale) {
        synchronized (PENDING_CAPES) {
            return PENDING_CAPES.remove(new PreviewCapeKey(
                    bodyModel, skin, rotationX, rotationY, pivotY, x0, y0, x1, y1, scale));
        }
    }

    public static void clearPendingCapes() {
        synchronized (PENDING_CAPES) {
            PENDING_CAPES.clear();
        }
    }
//?} else {
    private static PlayerModel  classicModel;
    private static PlayerModel slimModel;
    private static PlayerCapeModel capeModel;

    /**
     * Deferred PiP rendering happens after GUI extraction. Keep cape data keyed by the exact
     * {@link Model.Simple} submitted with each render state so concurrent/re-entrant previews
     * cannot consume one another's cape. Identity semantics are intentional: two submissions may
     * use the same model root and skin while carrying different preview data.
     */
    private static final Map<Model.Simple, PreviewCapeState> PENDING_CAPES = new IdentityHashMap<>();
    private static final int MAX_PENDING_CAPES = 128;

    public record PreviewCapeState(
            Identifier texture,
            PlayerModel bodyModel,
            PlayerCapeModel capeModel
    ) {
    }

    private static void registerPendingCape(
            Model.Simple renderModel,
            Identifier texture,
            PlayerModel bodyModel,
            PlayerCapeModel playerCapeModel
    ) {
        if (texture == null || bodyModel == null || playerCapeModel == null) {
            return;
        }

        synchronized (PENDING_CAPES) {
            if (PENDING_CAPES.size() >= MAX_PENDING_CAPES) {
                var oldest = PENDING_CAPES.keySet().iterator();
                if (oldest.hasNext()) {
                    PENDING_CAPES.remove(oldest.next());
                }
            }
            PENDING_CAPES.put(renderModel, new PreviewCapeState(texture, bodyModel, playerCapeModel));
        }
    }

    public static PreviewCapeState consumePendingCape(Model.Simple renderModel) {
        synchronized (PENDING_CAPES) {
            return PENDING_CAPES.remove(renderModel);
        }
    }

    public static void clearPendingCapes() {
        synchronized (PENDING_CAPES) {
            PENDING_CAPES.clear();
        }
    }
//?}

    /**
     * Initialize models (lazy initialization)
     */
    private static void ensureModelsLoaded() {
        if (classicModel == null) {
            Minecraft mc = Minecraft.getInstance();
            ModelPart classicRoot = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER);
//? if <1.21.4 {
            classicModel = new PlayerModel<>(classicRoot, false);
//?} else {
            classicModel = new PlayerModel(classicRoot, false);
//?}

            ModelPart slimRoot = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM);
//? if <1.21.4 {
            slimModel = new PlayerModel<>(slimRoot, true);
//?} else {
            slimModel = new PlayerModel(slimRoot, true);

            // In MC 1.21.4+, the cape is a separate model (PlayerCapeModel).
            ModelPart capeRoot = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_CAPE);
            capeModel = new PlayerCapeModel(capeRoot);
//?}
        }
//? if <1.21.6 {
//?} else if <26.2 {
    }

    /**
     * Identifies models owned by QuickSkin's cached preview renderer.
     *
     * @return {@code false} for the classic model, {@code true} for the slim model,
     *         or {@code null} when the model belongs to another renderer
     */
    public static Boolean getQuickSkinPreviewThinArms(PlayerModel model) {
        if (model == classicModel) {
            return Boolean.FALSE;
        }
        if (model == slimModel) {
            return Boolean.TRUE;
        }
        return null;
//?} else {
    }

    /**
     * Identifies roots owned by QuickSkin's cached preview models.
     *
     * @return {@code false} for the classic model, {@code true} for the slim model,
     *         or {@code null} when the root belongs to another renderer
     */
    public static Boolean getQuickSkinPreviewThinArms(ModelPart root) {
        if (classicModel != null && root == classicModel.root()) {
            return Boolean.FALSE;
        }
        if (slimModel != null && root == slimModel.root()) {
            return Boolean.TRUE;
        }
        return null;
//?}
    }

    // Cached player entity for rendering (persists even after leaving world)
    private static Player cachedPlayer;

    public static java.util.UUID getCachedPlayerUUID() {
        Player player = cachedPlayer;
        return player != null ? player.getUUID() : null;
    }

    // Preview-scoped cape authority for the entity render path.
    //
    // The preview draws the real player entity, so CapeLayer resolves the cape the player is
    // wearing and ignores whatever the editor selected. Binding the editor's cape against the key
    // CapeLayer is handed makes the selection authoritative for that draw alone; the same player
    // rendered in the world behind the screen carries no binding and keeps its applied cape.
    //
    // The key differs by era because the render timing does. Before 1.21.9 the GUI entity render
    // runs inline inside InventoryScreen.renderEntityInInventory, so the previewed entity is the
    // key and the binding is released in a finally. From 1.21.9 the render is deferred to the
    // picture-in-picture pass, so a flag around the submit call would already be cleared by the
    // time the layer runs; the key is instead the render state, which is allocated fresh per call
    // and threaded unchanged through to CapeLayer.submit. The 1.21.9-1.21.10 band already uses
    // that render-state seam while retaining ResourceLocation texture identifiers.
//? if <1.21.11 {
    private static final PreviewCapeBindings<Object, ResourceLocation> PREVIEW_CAPE_BINDINGS =
            new PreviewCapeBindings<>();

    /** Resolve and release the preview cape bound to {@code renderKey}, for the cape layer. */
    public static PreviewCapeBindings.Resolution<ResourceLocation> consumePreviewCape(Object renderKey) {
        return PREVIEW_CAPE_BINDINGS.consume(renderKey);
    }

    private static void bindPreviewCape(Object renderKey, PreviewPlayerData playerData) {
        if (renderKey == null || !playerData.isCapeAuthoritative()) {
            return;
        }
        ResourceLocation capeAtlas = playerData.getCapeLocation();
        PREVIEW_CAPE_BINDINGS.bind(renderKey, capeAtlas == null
                ? null
                : CapeAnimationHelper.resolveVisibleFrame(capeAtlas, playerData.getCapeId()));
    }
//?} else {
    private static final PreviewCapeBindings<Object, Identifier> PREVIEW_CAPE_BINDINGS =
            new PreviewCapeBindings<>();

    /** Resolve and release the preview cape bound to {@code renderKey}, for the cape layer. */
    public static PreviewCapeBindings.Resolution<Identifier> consumePreviewCape(Object renderKey) {
        return PREVIEW_CAPE_BINDINGS.consume(renderKey);
    }

    private static void bindPreviewCape(Object renderKey, PreviewPlayerData playerData) {
        if (renderKey == null || !playerData.isCapeAuthoritative()) {
            return;
        }
        Identifier capeAtlas = playerData.getCapeLocation();
        PREVIEW_CAPE_BINDINGS.bind(renderKey, capeAtlas == null
                ? null
                : CapeAnimationHelper.resolveVisibleFrame(capeAtlas, playerData.getCapeId()));
    }
//?}

    private static void unbindPreviewCape(Object renderKey) {
        PREVIEW_CAPE_BINDINGS.unbind(renderKey);
    }

    private static void clearPreviewCapes() {
        PREVIEW_CAPE_BINDINGS.clear();
    }

    // Preview-scoped equipment suppression for the entity render path.
    //
    // The preview draws the real player entity, so vanilla's equipment layers resolve whatever the
    // player is actually wearing and draw it over the model being previewed - the wings layer runs
    // after the cape layer and covers the cape the editor is composing, and a chestplate covers the
    // same pixels. PreviewEquipmentPolicy owns the rule; this owns applying it to one draw.
    //
    // What the preview still inherits from the live player, deliberately: pose (crouch, swim, glide
    // pitch), mob effects and invisibility, fire, name tag, shoulder parrots, embedded arrows and
    // bee stingers, and the riptide spin. None of them is equipment, none shares this seam, and
    // none is the reported problem; a shoulder parrot is the only one that touches the cape region.
    // Lighting, the outline colour and the shadow are already normalised at the submit sites below.
    //
    // The seam differs by era because the render timing does, exactly as the preview cape binding
    // above. From 1.21.9 the mod builds the entity render state itself, so the equipment is blanked
    // on that state before it is submitted - which is what vanilla does for its own smithing-table
    // preview. Before that there is no render state: the layers read the entity while the render
    // runs inline, so the scope below answers those reads as empty for the length of that call.
    // Nothing is written to the player: this is a read override, not a mutation.
    private static final PreviewEquipmentPolicy.Scope<Object> PREVIEW_EQUIPMENT_SCOPE =
            new PreviewEquipmentPolicy.Scope<>();

    private static void beginPreviewEquipment(Player player) {
        PREVIEW_EQUIPMENT_SCOPE.begin(player);
    }

    private static void endPreviewEquipment(Player player) {
        PREVIEW_EQUIPMENT_SCOPE.end(player);
    }

    /**
     * Whether {@code player}'s {@code slot} must read as empty because a preview is drawing it.
     *
     * <p>Called from the equipment-read hook before 1.21.9, where the layers read the live entity.
     * The scope is thread confined and identity keyed, so it can only ever answer for the entity
     * being previewed on the thread drawing it; every other caller, including the integrated
     * server's own copy of the player, gets the real equipment.
     */
    public static boolean suppressesPreviewEquipment(Object player, EquipmentSlot slot) {
        return PREVIEW_EQUIPMENT_SCOPE.isActiveFor(player)
                && PreviewEquipmentPolicy.suppresses(previewSlotOf(slot));
    }

    /**
     * The policy slot for a vanilla one, or {@code null} for a slot a player cannot fill.
     *
     * <p>Compared by reference rather than switched on, so a slot an era adds for other entities is
     * left alone instead of failing to compile or falling into the wrong branch.
     */
    private static PreviewEquipmentPolicy.Slot previewSlotOf(EquipmentSlot slot) {
        if (slot == EquipmentSlot.HEAD) return PreviewEquipmentPolicy.Slot.HEAD;
        if (slot == EquipmentSlot.CHEST) return PreviewEquipmentPolicy.Slot.CHEST;
        if (slot == EquipmentSlot.LEGS) return PreviewEquipmentPolicy.Slot.LEGS;
        if (slot == EquipmentSlot.FEET) return PreviewEquipmentPolicy.Slot.FEET;
        if (slot == EquipmentSlot.MAINHAND) return PreviewEquipmentPolicy.Slot.MAIN_HAND;
        if (slot == EquipmentSlot.OFFHAND) return PreviewEquipmentPolicy.Slot.OFF_HAND;
        return null;
    }
//? if <1.21.6 {
//?} else {

    /**
     * Blank the equipment the extracted render state copied off the live player.
     *
     * <p>Every equipment layer reads only the render state on this era - proven against the shipped
     * classes: the wings layer returns immediately when the chest stack carries no equippable
     * component, the armour layer reads the same four slot fields, the item-in-hand layer reads the
     * hand item states, and the custom-head layer returns when the head item is empty and no worn
     * head type is set. Blanking those fields therefore removes the elytra, the armour, the held
     * items and a worn head without cancelling a single layer.
     *
     * <p>The armour and the wings share {@code chestEquipment}, so a chestplate necessarily goes
     * with the elytra. That is the intended rule, not a side effect: the preview shows the cape.
     */
    private static void scrubPreviewEquipment(
            net.minecraft.client.renderer.entity.state.EntityRenderState state) {
        if (state instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState living) {
            if (PreviewEquipmentPolicy.suppresses(PreviewEquipmentPolicy.Slot.HEAD)) {
                // The worn-head visuals are extracted from the head slot but kept in their own
                // fields, so clearing the slot below is not enough to retire them.
                living.headItem.clear();
                living.wornHeadType = null;
                living.wornHeadProfile = null;
            }
        }
        if (state instanceof net.minecraft.client.renderer.entity.state.ArmedEntityRenderState armed) {
            if (PreviewEquipmentPolicy.suppresses(PreviewEquipmentPolicy.Slot.MAIN_HAND)
                    || PreviewEquipmentPolicy.suppresses(PreviewEquipmentPolicy.Slot.OFF_HAND)) {
//? if <1.21.11 {
                armed.rightHandItem.clear();
                armed.leftHandItem.clear();
//?} else {
                armed.rightHandItemStack = ItemStack.EMPTY;
                armed.leftHandItemStack = ItemStack.EMPTY;
                armed.rightHandItemState.clear();
                armed.leftHandItemState.clear();
//?}
                // The arm pose is derived from the held item, so it has to follow it down; before
                // 1.21.9 the same thing happens on its own, because the pose is computed from the
                // read the scope above already answers as empty.
                armed.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
                armed.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
            }
        }
        if (state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState humanoid) {
            if (PreviewEquipmentPolicy.suppresses(PreviewEquipmentPolicy.Slot.HEAD)) {
                humanoid.headEquipment = ItemStack.EMPTY;
            }
            if (PreviewEquipmentPolicy.suppresses(PreviewEquipmentPolicy.Slot.CHEST)) {
                humanoid.chestEquipment = ItemStack.EMPTY;
            }
            if (PreviewEquipmentPolicy.suppresses(PreviewEquipmentPolicy.Slot.LEGS)) {
                humanoid.legsEquipment = ItemStack.EMPTY;
            }
            if (PreviewEquipmentPolicy.suppresses(PreviewEquipmentPolicy.Slot.FEET)) {
                humanoid.feetEquipment = ItemStack.EMPTY;
            }
        }
//? if <1.21.9 {
        if (state instanceof net.minecraft.client.renderer.entity.state.PlayerRenderState player) {
            if (PreviewEquipmentPolicy.suppresses(PreviewEquipmentPolicy.Slot.MAIN_HAND)) {
                // Carried-on-head item; the item-in-hand layer draws it from its own baked state.
                player.heldOnHead.clear();
            }
//?} else {
        if (state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar) {
            if (PreviewEquipmentPolicy.suppresses(PreviewEquipmentPolicy.Slot.MAIN_HAND)) {
                // Carried-on-head item; the item-in-hand layer draws it from its own baked state.
                avatar.heldOnHead.clear();
            }
//?}
        }
    }
//?}

    // Previous rotation/position values for smooth lerping in idle animation
    private static float prevHeadRotZ = 0.0f;
    private static float prevRightArmRotX = 0.0f;
    private static float prevRightArmRotZ = 0.0f;
    private static float prevLeftArmRotX = 0.0f;
    private static float prevLeftArmRotZ = 0.0f;
    private static float prevRightLegRotX = 0.0f;
    private static float prevLeftLegRotX = 0.0f;
    private static float prevBodyRotX = 0.0f;

    // Fixed offset for positioning the model to match InventoryScreen rendering
    // The manual rendering uses additional X/Y rotations that shift the model position
    // These offsets compensate for that shift to match the InventoryScreen position
    public static double debugOffsetX = 2.0;
    public static double debugOffsetY = -129.0; // Move up to match InventoryScreen position

    // Interactive debug mode for positioning
    public static boolean debugPositioningMode = false; // Set to true to enable drag-to-position
    private static boolean isDraggingModel = false;
    private static int dragStartX = 0;
    private static int dragStartY = 0;
    private static double dragStartOffsetX = 0;
    private static double dragStartOffsetY = 0;

//? if <1.21 {
//?} else {
    // Animation frequency throttling (30 FPS instead of 60+)
    private static long lastAnimationUpdate = 0;
    private static final long ANIMATION_UPDATE_INTERVAL_MS = 33; // ~30 FPS

//?}
    /**
     * Render a player model in GUI using vanilla InventoryScreen method
     *
     * @param graphics The GuiGraphicsExtractor for rendering (contains PoseStack and buffer)
     * @param x Screen X position (center point)
     * @param y Screen Y position (feet position)
     * @param scale Scale factor (typically 30-50 for GUI)
     * @param yRotation Y-axis rotation in degrees (not used with vanilla method)
     * @param playerData Player data containing skin, model type, etc.
     * @param mouseX Mouse X position (for head tracking)
     * @param mouseY Mouse Y position (for head tracking)
     * @param followMouse Whether the head should follow the mouse
     */
    public static void renderPlayerModel(
//? if <26.1.2 {
            GuiGraphics graphics,
//?} else {
            GuiGraphicsExtractor graphics,
//?}
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        if (playerData.getSkinLocation() == null) {
            return; // No skin to render
        }

        // Get Minecraft instance
        Minecraft mc = Minecraft.getInstance();

        // Note: Shadow management removed - methods not available in Minecraft 1.21 with Mojang mappings
        // Performance impact is minimal in GUI previews

//? if <1.21 {
        // Cache the player when available for use on title screen
        if (mc.player != null) {
            cachedPlayer = mc.player;
        }
//?} else {
        try {
            // Cache the player when available for use on title screen
            if (mc.player != null) {
                cachedPlayer = mc.player;
            }
//?}

//? if <1.21 {
        // Try to use cached player (works even on title screen after playing once)
        Player playerToRender = cachedPlayer;
//?} else {
            // Try to use cached player (works even on title screen after playing once)
            Player playerToRender = cachedPlayer;
//?}

//? if <1.21 {
        // If no cached player exists (fresh game launch), use manual rendering
        if (playerToRender == null) {
            renderPlayerModelManual(graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
            return;
        }
//?} else {
            // If no cached player exists (fresh game launch), use manual rendering
            if (playerToRender == null) {
                renderPlayerModelManual(graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
                return;
            }
//?}

        // Store original rotation
        float originalYRot = playerToRender.getYRot();
        float originalXRot = playerToRender.getXRot();
        float originalYHeadRot = playerToRender.yHeadRot;
        float originalYBodyRot = playerToRender.yBodyRot;
        int originalTickCount = playerToRender.tickCount;

        // Set rotation for preview using the yRotation parameter
        // Convert yRotation to match InventoryScreen orientation (180 + yRotation)
        float targetRotation = 180.0F + yRotation;
        playerToRender.setYRot(targetRotation);
        playerToRender.setXRot(0.0F);
        playerToRender.yHeadRot = targetRotation + playerData.getHeadYaw();
        playerToRender.yBodyRot = targetRotation;

        // Set tickCount for idle animation ONLY when on title screen (no world)
        // When in-game, the entity already has its own natural tickCount from the game loop
        if (DETERMINISTIC_E2E_RENDER) {
            playerToRender.tickCount = E2E_FIXED_PREVIEW_TICK;
        } else if (mc.level == null) {
//? if <1.21 {
            // Title screen: manually set tickCount to enable animation
            playerToRender.tickCount = mc.gui.getGuiTicks();
//?} else if <26.2 {
            // Title screen: use GUI tick counter (more efficient than System.currentTimeMillis())
            playerToRender.tickCount = mc.gui.getGuiTicks();
//?} else {
            // Title screen: use GUI tick counter (more efficient than System.currentTimeMillis())
            playerToRender.tickCount = mc.gui.hud.getGuiTicks();
//?}
        }
        // Otherwise: keep entity's natural tickCount for proper in-game animation

        // Create quaternions for rotation (no mouse tracking)
        // First quaternion: 180-degree flip to orient the model correctly
        Quaternionf quaternionXZ = new Quaternionf().rotationXYZ(0.0F, 0.0F, (float)Math.PI);
        // Second quaternion: empty (no rotation)
        Quaternionf quaternionY = new Quaternionf();

        // Use vanilla InventoryScreen rendering method
        try {
            // Render grass block if sitting animation is active AND we're not in a world
            // When in-game, animations are controlled by the game, so don't render the custom grass block
            if ("sit".equals(playerData.getCurrentAnimation() != null ? playerData.getCurrentAnimation().toLowerCase(Locale.ROOT) : null) && mc.level == null) {
//? if <1.21.6 {
                PoseStack poseStack = graphics.pose();
                MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
//?} else if <26.2 {
                // In 1.21.6+, graphics.pose() returns Matrix3x2fStack; use a 3D PoseStack.
                PoseStack poseStack = new PoseStack();
                MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
//?} else {
                // In 1.21.6+, graphics.pose() returns Matrix3x2fStack; use a 3D PoseStack.
                PoseStack poseStack = new PoseStack();
//?}

                poseStack.pushPose();
                // Match the transformations from InventoryScreen
                poseStack.translate(x, y, 50.0);
                float scaleCasted = (float)(int)scale;
                Matrix4f scaleMatrix = (new Matrix4f()).scaling(scaleCasted, scaleCasted, -scaleCasted);
//? if <1.21 {
                poseStack.mulPoseMatrix(scaleMatrix);
//?} else {
                poseStack.mulPose(scaleMatrix);
//?}
                poseStack.mulPose(quaternionXZ);
                poseStack.mulPose(Axis.YP.rotationDegrees(-targetRotation));
                poseStack.scale(-1.0F, -1.0F, 1.0F);
                poseStack.translate(0.0F, -1.501F, 0.0F);

//? if <26.2 {
                renderGrassBlock(poseStack, bufferSource);
                bufferSource.endBatch();
//?} else {
                renderGrassBlock(poseStack);
//?}
                poseStack.popPose();
            }

//? if <1.21 {
            // The entity render runs inline, so the previewed entity keys the preview cape and the
            // binding is released as soon as the call returns. Equipment suppression is scoped the
            // same way: the layers read the live entity during this call and nowhere else.
            bindPreviewCape(playerToRender, playerData);
            beginPreviewEquipment(playerToRender);
            try {
            InventoryScreen.renderEntityInInventory(
                    graphics,
                    x,
                    y,
                    (int)scale,
//?} else if <1.21.6 {
            // The entity render runs inline, so the previewed entity keys the preview cape and the
            // binding is released as soon as the call returns. Equipment suppression is scoped the
            // same way: the layers read the live entity during this call and nowhere else.
            bindPreviewCape(playerToRender, playerData);
            beginPreviewEquipment(playerToRender);
            try {
            InventoryScreen.renderEntityInInventory(
                    graphics,
                    (float) x,
                    (float) y,
                    (float) scale,
                    new org.joml.Vector3f(0, 0, 0),  // translation offset
//?} else if <26.1.2 {
            ensureModelsLoaded();
            // 1.21.6+: submit the extracted state directly so preview-only state can be scrubbed.
            // to preserve our own rotation (renderEntityInInventoryFollowsMouse overrides rotation).
            int halfWidth = (int)(scale * 0.6f);
            // Shift box center UP by ~bbHeight/2 in screen space to keep feet at y
            // (submitEntityRenderState offsets by bbHeight/2, centering the entity visually)
            int entityHalfHeight = (int)(scale * 0.9f);
            int yCenter = y - entityHalfHeight;
            int topHalf = (int)(scale * 2.0f);
            int bottomHalf = topHalf;
            int x1 = x - halfWidth, y1 = yCenter - topHalf, x2 = x + halfWidth, y2 = yCenter + bottomHalf;

            // Extract render state (replicating InventoryScreen.extractRenderState)
            var dispatcher = mc.getEntityRenderDispatcher();
            var renderer = dispatcher.getRenderer(playerToRender);
            var renderState = renderer.createRenderState(playerToRender, 1.0f);
//? if >=1.21.9 {
            renderState.lightCoords = 15728880; // full bright
            renderState.shadowPieces.clear();
            renderState.outlineColor = 0;
//?}

            // createRenderState already copied the entity's rotation (set at lines 157-161),
            // so we only need to normalize bounding box for scale=1
            if (renderState instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState livingState) {
                livingState.boundingBoxWidth /= livingState.scale;
                livingState.boundingBoxHeight /= livingState.scale;
                livingState.scale = 1.0f;
            }

            // The state was extracted from the live player, so it arrived carrying whatever that
            // player is wearing and holding. A preview shows the skin and the cape, so blank it.
            scrubPreviewEquipment(renderState);

            // The picture-in-picture pass renders this state after the frame is extracted, so the
            // preview cape is keyed by the state itself and released by the cape layer that reads it.
            bindPreviewCape(renderState, playerData);

            // Compute offset: center entity vertically (bbHeight/2 + small offset)
            org.joml.Vector3f offset = new org.joml.Vector3f(0, renderState.boundingBoxHeight / 2.0f + 0.0625f, 0);

            graphics.submitEntityRenderState(
                    renderState,
                    (float)(int) scale,
                    offset,
//?} else if <26.2 {
            ensureModelsLoaded();
            // 1.21.11: renderEntityInInventory removed. We call submitEntityRenderState directly
            // to preserve our own rotation (renderEntityInInventoryFollowsMouse overrides rotation).
            int halfWidth = (int)(scale * 0.6f);
            // Shift box center UP by ~bbHeight/2 in screen space to keep feet at y
            // (submitEntityRenderState offsets by bbHeight/2, centering the entity visually)
            int entityHalfHeight = (int)(scale * 0.9f);
            int yCenter = y - entityHalfHeight;
            int topHalf = (int)(scale * 2.0f);
            int bottomHalf = topHalf;
            int x1 = x - halfWidth, y1 = yCenter - topHalf, x2 = x + halfWidth, y2 = yCenter + bottomHalf;

            // Extract render state (replicating InventoryScreen.extractRenderState)
            var dispatcher = mc.getEntityRenderDispatcher();
            var renderer = dispatcher.getRenderer(playerToRender);
            var renderState = renderer.createRenderState(playerToRender, 1.0f);
            renderState.lightCoords = 15728880; // full bright
            renderState.shadowPieces.clear();
            renderState.outlineColor = 0;

            // createRenderState already copied the entity's rotation (set at lines 157-161),
            // so we only need to normalize bounding box for scale=1
            if (renderState instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState livingState) {
                livingState.boundingBoxWidth /= livingState.scale;
                livingState.boundingBoxHeight /= livingState.scale;
                livingState.scale = 1.0f;
            }

            // The state was extracted from the live player, so it arrived carrying whatever that
            // player is wearing and holding. A preview shows the skin and the cape, so blank it.
            scrubPreviewEquipment(renderState);

            // The picture-in-picture pass renders this state after the frame is extracted, so the
            // preview cape is keyed by the state itself and released by the cape layer that reads it.
            bindPreviewCape(renderState, playerData);

            // Compute offset: center entity vertically (bbHeight/2 + small offset)
            org.joml.Vector3f offset = new org.joml.Vector3f(0, renderState.boundingBoxHeight / 2.0f + 0.0625f, 0);

            graphics.entity(
                    renderState,
                    (float)(int) scale,
                    offset,
//?} else {
            // 1.21.11: renderEntityInInventory removed. We call submitEntityRenderState directly
            // to preserve our own rotation (renderEntityInInventoryFollowsMouse overrides rotation).
            int halfWidth = (int)(scale * 0.6f);
            // Shift box center UP by ~bbHeight/2 in screen space to keep feet at y
            // (submitEntityRenderState offsets by bbHeight/2, centering the entity visually)
            int entityHalfHeight = (int)(scale * 0.9f);
            int yCenter = y - entityHalfHeight;
            int topHalf = (int)(scale * 2.0f);
            int bottomHalf = topHalf;
            int x1 = x - halfWidth, y1 = yCenter - topHalf, x2 = x + halfWidth, y2 = yCenter + bottomHalf;

            // Extract render state (replicating InventoryScreen.extractRenderState)
            var dispatcher = mc.getEntityRenderDispatcher();
            var renderer = dispatcher.getRenderer(playerToRender);
            var renderState = renderer.createRenderState(playerToRender, 1.0f);
            renderState.lightCoords = 15728880; // full bright
            renderState.shadowPieces.clear();
            renderState.outlineColor = 0;

            // createRenderState already copied the entity's rotation (set at lines 157-161),
            // so we only need to normalize bounding box for scale=1
            if (renderState instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState livingState) {
                livingState.boundingBoxWidth /= livingState.scale;
                livingState.boundingBoxHeight /= livingState.scale;
                livingState.scale = 1.0f;
            }

            // The state was extracted from the live player, so it arrived carrying whatever that
            // player is wearing and holding. A preview shows the skin and the cape, so blank it.
            scrubPreviewEquipment(renderState);

            // The picture-in-picture pass renders this state after the frame is extracted, so the
            // preview cape is keyed by the state itself and released by the cape layer that reads it.
            bindPreviewCape(renderState, playerData);

            // Compute offset: center entity vertically (bbHeight/2 + small offset)
            org.joml.Vector3f offset = new org.joml.Vector3f(0, renderState.boundingBoxHeight / 2.0f + 0.0625f, 0);

            graphics.entity(
                    renderState,
                    (float)(int) scale,
                    offset,
//?}
                    quaternionXZ,
                    quaternionY,
//? if <1.21.6 {
                    playerToRender
//?} else {
                    x1, y1, x2, y2
//?}
            );
//? if <1.21.6 {
            } finally {
                endPreviewEquipment(playerToRender);
                unbindPreviewCape(playerToRender);
            }
//?} else {
//?}

            // Note: 3D Skin Layers mod handles entity rendering automatically via entity layers
            // No manual integration needed for entity-based rendering
        } catch (Exception e) {
            // If rendering fails (e.g., entity no longer valid), fall back to manual rendering
            renderPlayerModelManual(graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
        }

//? if <1.21 {
        // Restore original rotation after rendering
        playerToRender.setYRot(originalYRot);
        playerToRender.setXRot(originalXRot);
        playerToRender.yHeadRot = originalYHeadRot;
        playerToRender.yBodyRot = originalYBodyRot;
        if (DETERMINISTIC_E2E_RENDER) {
            playerToRender.tickCount = originalTickCount;
        }
//?} else {
            // Restore original rotation after rendering
            playerToRender.setYRot(originalYRot);
            playerToRender.setXRot(originalXRot);
            playerToRender.yHeadRot = originalYHeadRot;
            playerToRender.yBodyRot = originalYBodyRot;
            if (DETERMINISTIC_E2E_RENDER) {
                playerToRender.tickCount = originalTickCount;
            }
        } finally {
            // Previously managed shadow state here, but methods not available in 1.21
        }
//?}
    }

    /**
     * Manually render player model without requiring a player entity
     * Used on title screen where no world/player exists
     * In 1.21.6+, all GUI 3D rendering must go through the PiP system.
     * Cape rendering is handled by GuiSkinRendererMixin which renders the cape
     * inside renderToTexture(), using the shared buffer source.
     */
    private static void renderPlayerModelManual(
//? if <26.1.2 {
            GuiGraphics graphics,
//?} else {
            GuiGraphicsExtractor graphics,
//?}
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        ensureModelsLoaded();

        // Select model based on type
//? if <1.21.11 {
        PlayerModel model = "slim".equals(playerData.getModelType() != null ? playerData.getModelType().toLowerCase(Locale.ROOT) : null) ? slimModel : classicModel;
//?} else {
        PlayerModel model = "slim".equals(playerData.getModelType() != null ? playerData.getModelType().toLowerCase(Locale.ROOT) : null) ? slimModel : classicModel;
//?}

//? if <1.21 {
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();

        // Match InventoryScreen.renderEntityInInventory() transformations
        poseStack.translate((double)x, (double)y, 50.0);

        // Apply scale (negative Z flips the model to face forward)
        // Cast to int to match InventoryScreen.renderEntityInInventory() behavior
        float scaleCasted = (float)(int)scale;
        Matrix4f scaleMatrix = (new Matrix4f()).scaling(scaleCasted, scaleCasted, -scaleCasted);
        poseStack.mulPoseMatrix(scaleMatrix);

        // Apply rotations - match InventoryScreen's quaternion
        Quaternionf quaternionf = (new Quaternionf()).rotateZ((float)Math.PI);
        poseStack.mulPose(quaternionf);

        // === NOW MATCH LivingEntityRenderer.render() EXACTLY ===

        // Body rotation (without the 180 degree offset)
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRotation));

        // Flip the model (line 82 in LivingEntityRenderer)
        poseStack.scale(-1.0F, -1.0F, 1.0F);

        // CRITICAL: Position the model at feet (line 84 in LivingEntityRenderer)
        poseStack.translate(0.0F, -1.501F, 0.0F);

        // Only setup lighting if not already set (optimization)
        if (!lightingSetup) {
            Lighting.setupForEntityInInventory();
            lightingSetup = true;
        }

        // Get Minecraft instance for tick count
//?} else if <1.21.6 {
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();

        // Match InventoryScreen.renderEntityInInventory() transformations
        poseStack.translate((double)x, (double)y, 50.0);

        // Apply scale (negative Z flips the model to face forward)
        // Cast to int to match InventoryScreen.renderEntityInInventory() behavior
        float scaleCasted = (float)(int)scale;
        Matrix4f scaleMatrix = (new Matrix4f()).scaling(scaleCasted, scaleCasted, -scaleCasted);
        poseStack.mulPose(scaleMatrix);

        // Apply rotations - match InventoryScreen's quaternion
        Quaternionf quaternionf = (new Quaternionf()).rotateZ((float)Math.PI);
        poseStack.mulPose(quaternionf);

        // === NOW MATCH LivingEntityRenderer.render() EXACTLY ===

        // Body rotation (without the 180 degree offset)
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRotation));

        // Flip the model (line 82 in LivingEntityRenderer)
        poseStack.scale(-1.0F, -1.0F, 1.0F);

        // CRITICAL: Position the model at feet (line 84 in LivingEntityRenderer)
        poseStack.translate(0.0F, -1.501F, 0.0F);

        // Lighting.setupForEntityInInventory();
        Lighting.setupForEntityInInventory();

        // Get Minecraft instance for tick count
//?} else {
//?}
        Minecraft mc = Minecraft.getInstance();
//? if <1.21.6 {

        // Get buffer source
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

        // Render grass block if sitting animation is active AND we're not in a world
        // When in-game, animations are controlled by the game, so don't render the custom grass block
        if ("sit".equals(playerData.getCurrentAnimation() != null ? playerData.getCurrentAnimation().toLowerCase(Locale.ROOT) : null) && mc.level == null) {
            renderGrassBlock(poseStack, bufferSource);
        }
//?} else {
//?}

        // Setup model pose with idle animation
        setupModelPoseWithAnimation(model, playerData, mouseX, mouseY, followMouse, x, y, mc);

//? if <1.21 {
        // Render the model with skin texture
        RenderType renderType = RenderType.entityTranslucent(playerData.getSkinLocation());
        var vertexConsumer = bufferSource.getBuffer(renderType);

        // Determine if using slim model
        boolean isSlimModel = "slim".equals(playerData.getModelType() != null ? playerData.getModelType().toLowerCase(Locale.ROOT) : null);

        // Render model
        model.renderToBuffer(
                poseStack,
                vertexConsumer,
                15728880, // Full brightness (light level) - same as InventoryScreen
                OverlayTexture.NO_OVERLAY,
                1.0f, 1.0f, 1.0f, 1.0f // RGBA
        );

        // Render 3D skin layers (if mod is installed)
        // The integration class handles all mod detection and graceful fallback
        SkinLayers3DIntegration.render3DLayers(
                poseStack,
                bufferSource,
                15728880,
                OverlayTexture.NO_OVERLAY,
                model,
                playerData.getSkinLocation(),
                isSlimModel
        );

        // Render cape AFTER model if present
//?} else if <1.21.6 {
        // Render the model with skin texture
        RenderType renderType = RenderType.entityTranslucent(playerData.getSkinLocation());
        var vertexConsumer = bufferSource.getBuffer(renderType);

        // Determine if using slim model
        boolean isSlimModel = "slim".equals(playerData.getModelType() != null ? playerData.getModelType().toLowerCase(Locale.ROOT) : null);

        // Render model
        model.renderToBuffer(
                poseStack,
                vertexConsumer,
                15728880, // Full brightness (light level) - same as InventoryScreen
                OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF  // ARGB color format
        );

        // Render 3D skin layers (if mod is installed)
        // The integration class handles all mod detection and graceful fallback
        SkinLayers3DIntegration.render3DLayers(
                poseStack,
                bufferSource,
                15728880,
                OverlayTexture.NO_OVERLAY,
                model,
                playerData.getSkinLocation(),
                isSlimModel
        );

        // Render cape AFTER model if present
//?} else if <1.21.11 {
        // Set cape data for the GuiSkinRendererMixin to pick up during renderToTexture.
        ResourceLocation capeTexture = null;
//?} else {
        // Set cape data for the GuiSkinRendererMixin to pick up during renderToTexture
        Identifier capeTexture = null;
//?}
        if (playerData.getCapeLocation() != null) {
//? if <1.21.6 {
            ResourceLocation capeAtlasLocation = playerData.getCapeLocation();
//?} else {
            capeTexture = CapeAnimationHelper.resolveVisibleFrame(
                    playerData.getCapeLocation(), playerData.getCapeId());
//?}
            String capeId = playerData.getCapeId();

//? if <1.21.6 {
            ResourceLocation finalCapeTexture = CapeAnimationHelper.resolveVisibleFrame(
                    capeAtlasLocation, capeId);
//?} else {
//?}
//? if <1.21 {

            if (finalCapeTexture != null) {
                // Now render the cape using the final texture
                RenderType capeRenderType = RenderType.entityTranslucent(finalCapeTexture);
                var capeVertexConsumer = bufferSource.getBuffer(capeRenderType);

                poseStack.pushPose();
                // Position the cloak correctly relative to the body
                model.body.translateAndRotate(poseStack);
                poseStack.translate(0.0, 0.0, 0.125); // Move behind the player

                // Add some basic swing/angle to make it look like a cape
                poseStack.mulPose(Axis.XP.rotationDegrees(6.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F)); // The cloak model part is drawn facing backwards

                MinecraftCompat.INSTANCE.renderCloak(
                        model, poseStack, capeVertexConsumer, 15728880, OverlayTexture.NO_OVERLAY);

                poseStack.popPose();
            }
//?} else if <1.21.6 {

            if (finalCapeTexture != null) {
                // Now render the cape using the final texture
                RenderType capeRenderType = RenderType.entityTranslucent(finalCapeTexture);
                var capeVertexConsumer = bufferSource.getBuffer(capeRenderType);

                poseStack.pushPose();
                // Position the cloak correctly relative to the body
                model.body.translateAndRotate(poseStack);
                poseStack.translate(0.0, 0.0, 0.125); // Move behind the player

                // Add some basic swing/angle to make it look like a cape
                poseStack.mulPose(Axis.XP.rotationDegrees(6.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F)); // The cloak model part is drawn facing backwards

                PlatformHelper.renderCloak(model, poseStack, capeVertexConsumer, 15728880, OverlayTexture.NO_OVERLAY);

                poseStack.popPose();
            }
//?} else {
//?}
        }
//? if <1.21.6 {
//?} else if <26.2 {
        // Force PiP cache invalidation when cape changes by adding imperceptible scale nudge
        int capeHash = capeTexture != null ? capeTexture.hashCode() : 0;
        float scaleNudge = 0.000004f * (capeHash & 0x3FFF);
        float rotationXNudge = 0.0000001f * ((capeHash >>> 14) & 0x3FFFF);
//?} else {
        // Include cape identity in the PiP state so changing only the cape invalidates its cache.
        int capeHash = capeTexture != null ? capeTexture.hashCode() : 0;
        float scaleNudge = 0.000001f * (capeHash & 0xFF);
//?}

//? if <1.21.6 {
        // Flush buffers - matches guiGraphics.flush() in InventoryScreen
        bufferSource.endBatch();
//?} else {
        // Submit to PiP system - cape rendering injected by GuiSkinRendererMixin
        int boxHeight = (int)(scale * 2.3f);
        int boxHalfWidth = (int)(scale * 1.0f);
//?}

//? if <1.21 {
        poseStack.popPose();

        // Don't restore lighting - let next render set what it needs (optimization)
//?} else if <1.21.6 {
        poseStack.popPose();

        // Lighting.setupFor3DItems();
        Lighting.setupFor3DItems();
//?} else if <26.1.2 {
        float submittedScale = (float)(int) scale + scaleNudge;
        float submittedRotationY = -45.0f + yRotation;
        graphics.submitSkinRenderState(
                model,
                playerData.getSkinLocation(),
                submittedScale,
                rotationXNudge,
                submittedRotationY,
                -1.0625f,
                x - boxHalfWidth,
                y - boxHeight,
                x + boxHalfWidth,
                y
        );
        registerPendingCape(model, playerData.getSkinLocation(), rotationXNudge,
                submittedRotationY, -1.0625f, x - boxHalfWidth, y - boxHeight,
                x + boxHalfWidth, y, submittedScale, capeTexture, capeModel);
//?} else if <26.2 {
        float submittedScale = (float)(int) scale + scaleNudge;
        float submittedRotationY = -45.0f + yRotation;
        graphics.skin(
                model,
                playerData.getSkinLocation(),
                submittedScale,
                rotationXNudge,
                submittedRotationY,
                -1.0625f,
                x - boxHalfWidth,
                y - boxHeight,
                x + boxHalfWidth,
                y
        );
        registerPendingCape(model, playerData.getSkinLocation(), rotationXNudge,
                submittedRotationY, -1.0625f, x - boxHalfWidth, y - boxHeight,
                x + boxHalfWidth, y, submittedScale, capeTexture, capeModel);
//?} else {
        Model.Simple renderModel = new Model.Simple(
                model.root(), net.minecraft.client.renderer.rendertype.RenderTypes::entityTranslucent);
        graphics.skin(
                renderModel,
                playerData.getSkinLocation(),
                (float)(int) scale + scaleNudge,
                0.0f,
                -45.0f + yRotation,
                -1.0625f,
                x - boxHalfWidth,
                y - boxHeight,
                x + boxHalfWidth,
                y
        );
        registerPendingCape(renderModel, capeTexture, model, capeModel);
//?}
    }

//? if <1.21.6 {
    /**
     * Legacy method for backwards compatibility - forwards to new GuiGraphics version
     */
    @Deprecated
    public static void renderPlayerModel(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        // Create GuiGraphics wrapper
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics graphics = new GuiGraphics(mc, buffer instanceof MultiBufferSource.BufferSource ?
                (MultiBufferSource.BufferSource)buffer : mc.renderBuffers().bufferSource());

        // Forward to new method
        renderPlayerModel(graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
    }
//?} else if <1.21.11 {
    /**
     * Legacy method for backwards compatibility - forwards to the PiP-aware GuiGraphics version.
     */
    @Deprecated
    public static void renderPlayerModel(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics graphics = new GuiGraphics(
                mc, new net.minecraft.client.gui.render.state.GuiRenderState());
        renderPlayerModel(graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
    }
//?} else if <26.1.2 {
    /**
     * Legacy method for backwards compatibility - forwards to new GuiGraphics version
     */
    @Deprecated
    public static void renderPlayerModel(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        // Create GuiGraphics wrapper - 1.21.11: GuiGraphics(Minecraft, GuiRenderState, int width, int height)
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        GuiGraphics graphics = new GuiGraphics(mc, new net.minecraft.client.gui.render.state.GuiRenderState(), window.getGuiScaledWidth(), window.getGuiScaledHeight());

        // Forward to new method
        renderPlayerModel(graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
    }
//?} else if <26.2 {
    /**
     * Legacy method for backwards compatibility - forwards to new GuiGraphicsExtractor version
     */
    @Deprecated
    public static void renderPlayerModel(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        // Create GuiGraphicsExtractor wrapper - 1.21.11: GuiGraphicsExtractor(Minecraft, GuiRenderState, int width, int height)
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(mc, new net.minecraft.client.renderer.state.gui.GuiRenderState(), window.getGuiScaledWidth(), window.getGuiScaledHeight());

        // Forward to new method
        renderPlayerModel(graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
    }
//?} else {
//?}

    /**
     * Smoothly lerp (linear interpolate) between current and target value
     * @param current Current value
     * @param target Target value
     * @param factor Interpolation factor (0-1, higher = faster)
     * @return Interpolated value
     */
    private static float smoothLerp(float current, float target, float factor) {
        return current + (target - current) * factor;
    }

//? if <1.21.11 {
    /**
     * Render cape layer similar to vanilla CapeLayer
     * This is called AFTER the player model is rendered
     */
    private static void renderCapeLayer(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation capeTexture,
            PlayerModel model
    ) {
        poseStack.pushPose();
//?} else if <26.2 {
    /**
     * Render cape layer similar to vanilla CapeLayer
     * This is called AFTER the player model is rendered
     */
    private static void renderCapeLayer(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            Identifier capeTexture,
            PlayerModel model
    ) {
        poseStack.pushPose();
//?} else {
//?}

//? if <1.21 {
        // Apply body transformations (cape is attached to the body)
        model.body.translateAndRotate(poseStack);

        // Position cape at back of shoulders
        poseStack.translate(0.0, 0.0, 0.125);

        // Cape dimensions
        float capeWidth = 10.0f / 16.0f;
        float capeHeight = 16.0f / 16.0f;
        float xOffset = -capeWidth / 2.0f;

        // Get cape render type and buffer
        RenderType capeRenderType = RenderType.entitySolid(capeTexture);
        var capeConsumer = bufferSource.getBuffer(capeRenderType);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        // UV coordinates
        float u0 = 0.0f;
        float v0 = 0.0f;
        float u1 = 10.0f / 64.0f;
        float v1 = 16.0f / 32.0f;

        // Render quad
        capeConsumer.vertex(matrix, xOffset, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();

        capeConsumer.vertex(matrix, xOffset, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();

        capeConsumer.vertex(matrix, xOffset + capeWidth, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();

        capeConsumer.vertex(matrix, xOffset + capeWidth, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();

        poseStack.popPose();
    }

    /**
     * OLD Render cape in manual mode with correct transformations
     * The cape needs special handling because we're in a transformed coordinate space
     */
    private static void renderCapeManualOLD(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation capeLocation,
            float yRotation
    ) {
        poseStack.pushPose();

        // DEBUG: Render a GIANT bright magenta rectangle that's impossible to miss
        // This will help us see exactly where the cape is being rendered
//? if <1.21.6 {
        RenderType debugRenderType = RenderType.gui();
//?} else {
        RenderType debugRenderType = RenderType.debugFilledBox();
//?}
        var debugConsumer = bufferSource.getBuffer(debugRenderType);
        PoseStack.Pose debugPose = poseStack.last();
        Matrix4f debugMatrix = debugPose.pose();

        // Render a huge bright rectangle (5x5 units) centered at origin
        float debugSize = 2.5f;
        // Top-left
        debugConsumer.vertex(debugMatrix, -debugSize, -debugSize, 0.0f)
                .color(255, 0, 255, 255) // Bright magenta
                .uv(0, 0)
                .endVertex();
        // Bottom-left
        debugConsumer.vertex(debugMatrix, -debugSize, debugSize, 0.0f)
                .color(255, 0, 255, 255)
                .uv(0, 1)
                .endVertex();
        // Bottom-right
        debugConsumer.vertex(debugMatrix, debugSize, debugSize, 0.0f)
                .color(255, 0, 255, 255)
                .uv(1, 1)
                .endVertex();
        // Top-right
        debugConsumer.vertex(debugMatrix, debugSize, -debugSize, 0.0f)
                .color(255, 0, 255, 255)
                .uv(1, 0)
                .endVertex();

        // Position cape at the back of the player's body
        // In our transformed space (after XP 180, YP 180), we need to adjust positioning
        // The cape should be slightly behind the body center
        poseStack.translate(0.0, 0.0, -0.125); // Negative Z because of our flipped coordinates

        // Cape dimensions (Minecraft standard: 10x16 pixels on 64x32 texture)
        float capeWidth = 10.0f / 16.0f;  // 0.625 units
        float capeHeight = 16.0f / 16.0f; // 1.0 units
        float xOffset = -capeWidth / 2.0f; // Center the cape

        // Add subtle swing animation
        float capeSwing = (float) Math.sin(animationTimeMillis() / 1000.0) * 0.1f;
        poseStack.mulPose(Axis.XP.rotationDegrees(capeSwing * 10.0f));

        // Get render type and vertex consumer
//? if <1.21.6 {
        RenderType renderType = RenderType.entityTranslucentCull(capeLocation);
//?} else {
        RenderType renderType = RenderType.entityTranslucent(capeLocation);
//?}
        var vertexConsumer = bufferSource.getBuffer(renderType);

        // Get matrices
        PoseStack.Pose pose = poseStack.last();
        Matrix4f positionMatrix = pose.pose();

        // UV coordinates for standard Minecraft cape (64x32 texture)
        float u0 = 0.0f / 64.0f;
        float v0 = 0.0f / 32.0f;
        float u1 = 10.0f / 64.0f;
        float v1 = 16.0f / 32.0f;

        // Render cape quad (4 vertices forming a rectangle)
        // Top-left
        vertexConsumer.vertex(positionMatrix, xOffset, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880) // Full bright
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        // Bottom-left
        vertexConsumer.vertex(positionMatrix, xOffset, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        // Bottom-right
        vertexConsumer.vertex(positionMatrix, xOffset + capeWidth, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        // Top-right
        vertexConsumer.vertex(positionMatrix, xOffset + capeWidth, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        poseStack.popPose();
    }
//?} else if <1.21.11 {
        // Apply body transformations (cape is attached to the body)
        model.body.translateAndRotate(poseStack);

        // Position cape at back of shoulders
        poseStack.translate(0.0, 0.0, 0.125);

        // Cape dimensions
        float capeWidth = 10.0f / 16.0f;
        float capeHeight = 16.0f / 16.0f;
        float xOffset = -capeWidth / 2.0f;

        // Get cape render type and buffer
        RenderType capeRenderType = RenderType.entitySolid(capeTexture);
        var capeConsumer = bufferSource.getBuffer(capeRenderType);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        // UV coordinates
        float u0 = 0.0f;
        float v0 = 0.0f;
        float u1 = 10.0f / 64.0f;
        float v1 = 16.0f / 32.0f;

        // Render quad
        capeConsumer.addVertex(matrix, xOffset, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        capeConsumer.addVertex(matrix, xOffset, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        capeConsumer.addVertex(matrix, xOffset + capeWidth, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        capeConsumer.addVertex(matrix, xOffset + capeWidth, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        poseStack.popPose();
    }

    /**
     * OLD Render cape in manual mode with correct transformations
     * The cape needs special handling because we're in a transformed coordinate space
     */
    private static void renderCapeManualOLD(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation capeLocation,
            float yRotation
    ) {
        poseStack.pushPose();

        // DEBUG: Render a GIANT bright magenta rectangle that's impossible to miss
        // This will help us see exactly where the cape is being rendered
//? if <1.21.6 {
        RenderType debugRenderType = RenderType.gui();
//?} else {
        RenderType debugRenderType = RenderType.debugFilledBox();
//?}
        var debugConsumer = bufferSource.getBuffer(debugRenderType);
        PoseStack.Pose debugPose = poseStack.last();
        Matrix4f debugMatrix = debugPose.pose();

        // Debug rendering commented out for 1.21.1 API compatibility
        // TODO: Update debug rendering to use new VertexConsumer API
        /*
        // Render a huge bright rectangle (5x5 units) centered at origin
        float debugSize = 2.5f;
        // Top-left
        debugConsumer.addVertex(debugMatrix, -debugSize, -debugSize, 0.0f)
                .setColor(255, 0, 255, 255) // Bright magenta
                .setUv(0, 0)

        // Bottom-left
        debugConsumer.addVertex(debugMatrix, -debugSize, debugSize, 0.0f)
                .setColor(255, 0, 255, 255)
                .setUv(0, 1)

        // Bottom-right
        debugConsumer.addVertex(debugMatrix, debugSize, debugSize, 0.0f)
                .setColor(255, 0, 255, 255)
                .setUv(1, 1)

        // Top-right
        debugConsumer.addVertex(debugMatrix, debugSize, -debugSize, 0.0f)
                .setColor(255, 0, 255, 255)
                .setUv(1, 0)

        */

        // Position cape at the back of the player's body
        // In our transformed space (after XP 180, YP 180), we need to adjust positioning
        // The cape should be slightly behind the body center
        poseStack.translate(0.0, 0.0, -0.125); // Negative Z because of our flipped coordinates

        // Cape dimensions (Minecraft standard: 10x16 pixels on 64x32 texture)
        float capeWidth = 10.0f / 16.0f;  // 0.625 units
        float capeHeight = 16.0f / 16.0f; // 1.0 units
        float xOffset = -capeWidth / 2.0f; // Center the cape

        // Add subtle swing animation
        float capeSwing = (float) Math.sin(animationTimeMillis() / 1000.0) * 0.1f;
        poseStack.mulPose(Axis.XP.rotationDegrees(capeSwing * 10.0f));

        // Get render type and vertex consumer
//? if <1.21.6 {
        RenderType renderType = RenderType.entityTranslucentCull(capeLocation);
//?} else {
        RenderType renderType = RenderType.entityTranslucent(capeLocation);
//?}
        var vertexConsumer = bufferSource.getBuffer(renderType);

        // Get matrices
        PoseStack.Pose pose = poseStack.last();
        Matrix4f positionMatrix = pose.pose();

        // UV coordinates for standard Minecraft cape (64x32 texture)
        float u0 = 0.0f / 64.0f;
        float v0 = 0.0f / 32.0f;
        float u1 = 10.0f / 64.0f;
        float v1 = 16.0f / 32.0f;

        // Render cape quad (4 vertices forming a rectangle)
        // Top-left
        vertexConsumer.addVertex(positionMatrix, xOffset, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240) // Full bright
                .setNormal(0.0f, 0.0f, 1.0f);


        // Bottom-left
        vertexConsumer.addVertex(positionMatrix, xOffset, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        // Bottom-right
        vertexConsumer.addVertex(positionMatrix, xOffset + capeWidth, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        // Top-right
        vertexConsumer.addVertex(positionMatrix, xOffset + capeWidth, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        poseStack.popPose();
    }
//?} else if <26.2 {
        // Apply body transformations (cape is attached to the body)
        model.body.translateAndRotate(poseStack);

        // Position cape at back of shoulders
        poseStack.translate(0.0, 0.0, 0.125);

        // Cape dimensions
        float capeWidth = 10.0f / 16.0f;
        float capeHeight = 16.0f / 16.0f;
        float xOffset = -capeWidth / 2.0f;

        // Get cape render type and buffer
        RenderType capeRenderType = RenderTypes.entitySolid(capeTexture);
        var capeConsumer = bufferSource.getBuffer(capeRenderType);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        // UV coordinates
        float u0 = 0.0f;
        float v0 = 0.0f;
        float u1 = 10.0f / 64.0f;
        float v1 = 16.0f / 32.0f;

        // Render quad
        capeConsumer.addVertex(matrix, xOffset, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        capeConsumer.addVertex(matrix, xOffset, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        capeConsumer.addVertex(matrix, xOffset + capeWidth, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        capeConsumer.addVertex(matrix, xOffset + capeWidth, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        poseStack.popPose();
    }

    /**
     * OLD Render cape in manual mode with correct transformations
     * The cape needs special handling because we're in a transformed coordinate space
     */
    private static void renderCapeManualOLD(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            Identifier capeLocation,
            float yRotation
    ) {
        poseStack.pushPose();

        // DEBUG: Render a GIANT bright magenta rectangle that's impossible to miss
        // This will help us see exactly where the cape is being rendered
        // RenderType.gui() removed in 1.21.11 - using debugFilledBox() instead
        RenderType debugRenderType = RenderTypes.debugFilledBox();
        var debugConsumer = bufferSource.getBuffer(debugRenderType);
        PoseStack.Pose debugPose = poseStack.last();
        Matrix4f debugMatrix = debugPose.pose();

        // Debug rendering commented out for 1.21.1 API compatibility
        // TODO: Update debug rendering to use new VertexConsumer API
        /*
        // Render a huge bright rectangle (5x5 units) centered at origin
        float debugSize = 2.5f;
        // Top-left
        debugConsumer.addVertex(debugMatrix, -debugSize, -debugSize, 0.0f)
                .setColor(255, 0, 255, 255) // Bright magenta
                .setUv(0, 0)

        // Bottom-left
        debugConsumer.addVertex(debugMatrix, -debugSize, debugSize, 0.0f)
                .setColor(255, 0, 255, 255)
                .setUv(0, 1)

        // Bottom-right
        debugConsumer.addVertex(debugMatrix, debugSize, debugSize, 0.0f)
                .setColor(255, 0, 255, 255)
                .setUv(1, 1)

        // Top-right
        debugConsumer.addVertex(debugMatrix, debugSize, -debugSize, 0.0f)
                .setColor(255, 0, 255, 255)
                .setUv(1, 0)

        */

        // Position cape at the back of the player's body
        // In our transformed space (after XP 180, YP 180), we need to adjust positioning
        // The cape should be slightly behind the body center
        poseStack.translate(0.0, 0.0, -0.125); // Negative Z because of our flipped coordinates

        // Cape dimensions (Minecraft standard: 10x16 pixels on 64x32 texture)
        float capeWidth = 10.0f / 16.0f;  // 0.625 units
        float capeHeight = 16.0f / 16.0f; // 1.0 units
        float xOffset = -capeWidth / 2.0f; // Center the cape

        // Add subtle swing animation
        float capeSwing = (float) Math.sin(animationTimeMillis() / 1000.0) * 0.1f;
        poseStack.mulPose(Axis.XP.rotationDegrees(capeSwing * 10.0f));

        // Get render type and vertex consumer
        RenderType renderType = RenderTypes.entityTranslucent(capeLocation);
        var vertexConsumer = bufferSource.getBuffer(renderType);

        // Get matrices
        PoseStack.Pose pose = poseStack.last();
        Matrix4f positionMatrix = pose.pose();

        // UV coordinates for standard Minecraft cape (64x32 texture)
        float u0 = 0.0f / 64.0f;
        float v0 = 0.0f / 32.0f;
        float u1 = 10.0f / 64.0f;
        float v1 = 16.0f / 32.0f;

        // Render cape quad (4 vertices forming a rectangle)
        // Top-left
        vertexConsumer.addVertex(positionMatrix, xOffset, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240) // Full bright
                .setNormal(0.0f, 0.0f, 1.0f);


        // Bottom-left
        vertexConsumer.addVertex(positionMatrix, xOffset, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        // Bottom-right
        vertexConsumer.addVertex(positionMatrix, xOffset + capeWidth, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        // Top-right
        vertexConsumer.addVertex(positionMatrix, xOffset + capeWidth, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);


        poseStack.popPose();
    }
//?} else {
//?}

    /**
     * Setup model pose with animation based on current animation state
     * Supports idle, walk, run, sneak, sit, jump animations
     */
    private static void setupModelPoseWithAnimation(
//? if <1.21.11 {
            PlayerModel model,
//?} else {
            PlayerModel model,
//?}
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse,
            int modelCenterX,
            int modelCenterY,
            Minecraft mc
    ) {
        // Get current animation type from preview data
        String animation = playerData.getCurrentAnimation();
        if (animation == null || animation.isEmpty()) {
            animation = "idle";
        }

        // CHECK: Should we update animation this frame? (30 FPS instead of 60+)
        long now = animationTimeMillis();
        boolean shouldUpdate = DETERMINISTIC_E2E_RENDER
                || (now - lastAnimationUpdate) >= ANIMATION_UPDATE_INTERVAL_MS;

        if (!shouldUpdate) {
//? if <1.21.9 {
            // Keep previous pose, just update hat/sleeves to match
            model.hat.copyFrom(model.head);
            model.leftSleeve.copyFrom(model.leftArm);
            model.rightSleeve.copyFrom(model.rightArm);
            model.leftPants.copyFrom(model.leftLeg);
            model.rightPants.copyFrom(model.rightLeg);
            model.jacket.copyFrom(model.body);
//?} else if <1.21.11 {
            // Keep previous pose, just update outer layers to match
            model.hat.loadPose(model.head.storePose());
            model.leftSleeve.loadPose(model.leftArm.storePose());
            model.rightSleeve.loadPose(model.rightArm.storePose());
            model.leftPants.loadPose(model.leftLeg.storePose());
            model.rightPants.loadPose(model.rightLeg.storePose());
            model.jacket.loadPose(model.body.storePose());
//?} else {
            // In MC 1.21.11+, outer layers (sleeves, pants, jacket) are children of their
            // corresponding body parts, so they inherit transforms automatically.
            // Just reset their local rotations to zero to avoid doubling.
            resetOuterLayerRotations(model);
//?}
            return; // EXIT EARLY - saves 40-60% CPU time
        }

        if (!DETERMINISTIC_E2E_RENDER) {
            lastAnimationUpdate = now;
        }

        // Get elapsed time using Minecraft's tick counter
//? if <26.2 {
        int tickCount = DETERMINISTIC_E2E_RENDER
                ? E2E_FIXED_PREVIEW_TICK
                : mc != null ? mc.gui.getGuiTicks() : 0;
//?} else {
        int tickCount = DETERMINISTIC_E2E_RENDER
                ? E2E_FIXED_PREVIEW_TICK
                : mc != null ? mc.gui.hud.getGuiTicks() : 0;
//?}
        float elapsedTime = tickCount / 20.0f; // Convert ticks to seconds
        float t = elapsedTime * 0.8f; // Slower, more relaxed pace

        // Lerp factor for smooth transitions
        float lerpFactor = DETERMINISTIC_E2E_RENDER ? 1.0f : 0.15f;

        // Apply animation based on type
        switch (animation.toLowerCase(Locale.ROOT)) {
            case "walk":
                setupWalkingPose(model, t, lerpFactor);
                break;
            case "sit":
                setupSittingPose(model, t, lerpFactor);
                break;
            case "idle":
            default:
                setupIdlePose(model, t, lerpFactor);
                break;
        }

//? if <1.21.9 {
        // Hat layer (outer layer of head) follows head rotation
        model.hat.copyFrom(model.head);
//?} else if <1.21.11 {
        // ModelPart.copyFrom was replaced by pose snapshots in 1.21.9.
        model.hat.loadPose(model.head.storePose());
//?} else {
        // In MC 1.21.11+, outer layers are children of their body parts and inherit
        // transforms automatically. Reset their local rotations to zero.
        resetOuterLayerRotations(model);
    }
//?}

//? if <1.21.9 {
        // Setup arm rendering
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftPants.copyFrom(model.leftLeg);
        model.rightPants.copyFrom(model.rightLeg);
        model.jacket.copyFrom(model.body);
//?} else if <1.21.11 {
        // ModelPart.copyFrom was replaced by pose snapshots in 1.21.9.
        model.leftSleeve.loadPose(model.leftArm.storePose());
        model.rightSleeve.loadPose(model.rightArm.storePose());
        model.leftPants.loadPose(model.leftLeg.storePose());
        model.rightPants.loadPose(model.rightLeg.storePose());
        model.jacket.loadPose(model.body.storePose());
//?} else {
    /**
     * Reset outer layer rotations to zero.
     * In MC 1.21.11+, outer layers (hat, sleeves, pants, jacket) are children of their
     * corresponding body parts in the model hierarchy, so they inherit parent transforms.
     * Setting their local rotations to zero ensures they stay aligned with the body.
     */
    private static void resetOuterLayerRotations(PlayerModel model) {
        model.hat.xRot = 0;
        model.hat.yRot = 0;
        model.hat.zRot = 0;
        model.leftSleeve.xRot = 0;
        model.leftSleeve.yRot = 0;
        model.leftSleeve.zRot = 0;
        model.rightSleeve.xRot = 0;
        model.rightSleeve.yRot = 0;
        model.rightSleeve.zRot = 0;
        model.leftPants.xRot = 0;
        model.leftPants.yRot = 0;
        model.leftPants.zRot = 0;
        model.rightPants.xRot = 0;
        model.rightPants.yRot = 0;
        model.rightPants.zRot = 0;
        model.jacket.xRot = 0;
        model.jacket.yRot = 0;
        model.jacket.zRot = 0;
//?}
    }

    /**
     * Setup idle pose with subtle bounce animation
     */
//? if <1.21.11 {
    private static void setupIdlePose(PlayerModel model, float t, float lerpFactor) {
//?} else {
    private static void setupIdlePose(PlayerModel model, float t, float lerpFactor) {
//?}
        // Set model state flags
//? if <1.21 {
        MinecraftCompat.INSTANCE.setYoung(model, false);
        MinecraftCompat.INSTANCE.setCrouching(model, false);
        MinecraftCompat.INSTANCE.setRiding(model, false);
        MinecraftCompat.INSTANCE.setAttackTime(model, 0.0f);
//?} else if <1.21.11 {
        PlatformHelper.setYoung(model, false);
        PlatformHelper.setCrouching(model, false);
        PlatformHelper.setRiding(model, false);
        PlatformHelper.setAttackTime(model, 0.0f);
//?} else {
        MinecraftCompat.INSTANCE.setYoung(model, false);
        MinecraftCompat.INSTANCE.setCrouching(model, false);
        MinecraftCompat.INSTANCE.setRiding(model, false);
        MinecraftCompat.INSTANCE.setAttackTime(model, 0.0f);
//?}

        // HEAD: Bouncy up/down with head tilt
        float targetHeadRotZ = (float)Math.sin(t * 1.2) * 0.04f;
        prevHeadRotZ = smoothLerp(prevHeadRotZ, targetHeadRotZ, lerpFactor);

        model.head.xRot = 0.0f;
        model.head.yRot = 0.0f;
        model.head.zRot = prevHeadRotZ;

        // BODY: Bounce effect
        float targetBodyBounce = (float)Math.abs(Math.sin(t * 0.8)) * 0.12f;
        prevBodyRotX = smoothLerp(prevBodyRotX, targetBodyBounce * 0.2f, lerpFactor);

        model.body.xRot = -prevBodyRotX;
        model.body.yRot = 0.0f;
        model.body.zRot = 0.0f;

        // RIGHT ARM: Swing forward/back with rotation
        float targetRightArmRotX = (float)Math.sin(t * 0.8) * 0.12f;
        float targetRightArmRotZ = (float)Math.sin(t) * 0.04f;
        prevRightArmRotX = smoothLerp(prevRightArmRotX, targetRightArmRotX, lerpFactor);
        prevRightArmRotZ = smoothLerp(prevRightArmRotZ, targetRightArmRotZ, lerpFactor);

        model.rightArm.xRot = prevRightArmRotX;
        model.rightArm.yRot = 0.0f;
        model.rightArm.zRot = prevRightArmRotZ;

        // LEFT ARM: Opposite swing
        float targetLeftArmRotX = (float)Math.sin(t * 0.8 + Math.PI) * 0.12f;
        float targetLeftArmRotZ = (float)Math.sin(t + Math.PI) * -0.04f;
        prevLeftArmRotX = smoothLerp(prevLeftArmRotX, targetLeftArmRotX, lerpFactor);
        prevLeftArmRotZ = smoothLerp(prevLeftArmRotZ, targetLeftArmRotZ, lerpFactor);

        model.leftArm.xRot = prevLeftArmRotX;
        model.leftArm.yRot = 0.0f;
        model.leftArm.zRot = prevLeftArmRotZ;

        // RIGHT LEG: Subtle swing
        float targetRightLegRotX = (float)Math.sin(t * 0.5) * 0.05f;
        prevRightLegRotX = smoothLerp(prevRightLegRotX, targetRightLegRotX, lerpFactor);

        model.rightLeg.xRot = prevRightLegRotX;
        model.rightLeg.yRot = 0.0f;
        model.rightLeg.zRot = 0.0f;

        // LEFT LEG: Opposite subtle swing
        float targetLeftLegRotX = (float)Math.sin(t * 0.5 + Math.PI) * 0.05f;
        prevLeftLegRotX = smoothLerp(prevLeftLegRotX, targetLeftLegRotX, lerpFactor);

        model.leftLeg.xRot = prevLeftLegRotX;
        model.leftLeg.yRot = 0.0f;
        model.leftLeg.zRot = 0.0f;
    }

    /**
     * Setup walking pose with natural body movements
     */
//? if <1.21 {
    private static void setupWalkingPose(PlayerModel model, float t, float lerpFactor) {
        MinecraftCompat.INSTANCE.setYoung(model, false);
        MinecraftCompat.INSTANCE.setCrouching(model, false);
        MinecraftCompat.INSTANCE.setRiding(model, false);
        MinecraftCompat.INSTANCE.setAttackTime(model, 0.0f);
//?} else if <1.21.11 {
    private static void setupWalkingPose(PlayerModel model, float t, float lerpFactor) {
        PlatformHelper.setYoung(model, false);
        PlatformHelper.setCrouching(model, false);
        PlatformHelper.setRiding(model, false);
        PlatformHelper.setAttackTime(model, 0.0f);
//?} else {
    private static void setupWalkingPose(PlayerModel model, float t, float lerpFactor) {
        MinecraftCompat.INSTANCE.setYoung(model, false);
        MinecraftCompat.INSTANCE.setCrouching(model, false);
        MinecraftCompat.INSTANCE.setRiding(model, false);
        MinecraftCompat.INSTANCE.setAttackTime(model, 0.0f);
//?}

        // ARMS and LEGS: Swinging motion (arms opposite to legs) - faster animation
        float limbSwing = (float)Math.sin(t * 8.0) * 0.6f;

        // HEAD: Natural bobbing and slight side-to-side movement while walking
        float headBobY = (float)Math.abs(Math.sin(t * 8.0)) * 0.05f; // Up and down bob matching stride
        float headTiltZ = (float)Math.sin(t * 8.0) * 0.03f; // Slight tilt side to side
        model.head.xRot = -headBobY; // Nod slightly with each step
        model.head.yRot = 0.0f;
        model.head.zRot = headTiltZ;

        // BODY: Dynamic movement - sway and lean
        float bodySway = (float)Math.sin(t * 8.0) * 0.04f; // Side-to-side sway
        float bodyBob = (float)Math.abs(Math.sin(t * 8.0)) * 0.02f; // Up/down movement
        model.body.xRot = 0.05f + bodyBob; // Forward lean plus bob
        model.body.yRot = bodySway; // Torso rotation
        model.body.zRot = bodySway * 0.5f; // Slight roll

        // ARMS: Natural swing with slight outward motion
        float armSwingOut = (float)Math.abs(Math.sin(t * 8.0)) * 0.05f;
        model.rightArm.xRot = -limbSwing;
        model.rightArm.yRot = 0.0f;
        model.rightArm.zRot = armSwingOut;

        model.leftArm.xRot = limbSwing;
        model.leftArm.yRot = 0.0f;
        model.leftArm.zRot = -armSwingOut;

        // LEGS: Standard walking motion
        model.rightLeg.xRot = limbSwing;
        model.rightLeg.yRot = 0.0f;
        model.rightLeg.zRot = 0.0f;

        model.leftLeg.xRot = -limbSwing;
        model.leftLeg.yRot = 0.0f;
        model.leftLeg.zRot = 0.0f;
    }

    /**
     * Setup sitting pose with subtle idle movements
     */
//? if <1.21 {
    private static void setupSittingPose(PlayerModel model, float t, float lerpFactor) {
        MinecraftCompat.INSTANCE.setYoung(model, false);
        MinecraftCompat.INSTANCE.setCrouching(model, false);
        MinecraftCompat.INSTANCE.setRiding(model, true); // Enable riding flag for sitting pose
        MinecraftCompat.INSTANCE.setAttackTime(model, 0.0f);
//?} else if <1.21.11 {
    private static void setupSittingPose(PlayerModel model, float t, float lerpFactor) {
        PlatformHelper.setYoung(model, false);
        PlatformHelper.setCrouching(model, false);
        PlatformHelper.setRiding(model, true); // Enable riding flag for sitting pose
        PlatformHelper.setAttackTime(model, 0.0f);
//?} else {
    private static void setupSittingPose(PlayerModel model, float t, float lerpFactor) {
        MinecraftCompat.INSTANCE.setYoung(model, false);
        MinecraftCompat.INSTANCE.setCrouching(model, false);
        MinecraftCompat.INSTANCE.setRiding(model, true); // Enable riding flag for sitting pose
        MinecraftCompat.INSTANCE.setAttackTime(model, 0.0f);
//?}

        // HEAD: Subtle breathing and slight look-around
        float headBob = (float)Math.sin(t * 0.6) * 0.02f;
        float headTilt = (float)Math.sin(t * 0.4) * 0.03f;
        model.head.xRot = headBob;
        model.head.yRot = 0.0f;
        model.head.zRot = headTilt;

        // BODY: Subtle breathing motion
        float breathe = (float)Math.sin(t * 0.5) * 0.01f;
        model.body.xRot = breathe;
        model.body.yRot = 0.0f;
        model.body.zRot = 0.0f;

        // ARMS: Resting on legs with subtle relaxed movement
        float armSway = (float)Math.sin(t * 0.7) * 0.02f;
        model.rightArm.xRot = -0.62f + armSway;
        model.rightArm.yRot = 0.0f;
        model.rightArm.zRot = 0.0f;

        model.leftArm.xRot = -0.62f - armSway;
        model.leftArm.yRot = 0.0f;
        model.leftArm.zRot = 0.0f;

        // LEGS: Bent for sitting with very subtle fidget
        float legFidget = (float)Math.sin(t * 0.3) * 0.01f;
        model.rightLeg.xRot = -1.4f + legFidget;
        model.rightLeg.yRot = 0.31f;
        model.rightLeg.zRot = 0.05f;

        model.leftLeg.xRot = -1.4f - legFidget;
        model.leftLeg.yRot = -0.31f;
        model.leftLeg.zRot = -0.05f;
    }

    /**
     * Render a grass block underneath the player when sitting
     * Positioned at the player's feet
     */
//? if <26.2 {
    private static void renderGrassBlock(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource) {
//?} else {
    private static void renderGrassBlock(PoseStack poseStack) {
//?}
        poseStack.pushPose();

        // Hardcoded position and scale values
        double offsetX = -0.333;
        double offsetY = 1.4;
        double offsetZ = 0.222;
        double scale = 0.575;

        // Position the grass block
        poseStack.translate(offsetX, offsetY, offsetZ);

        // Rotate 180 degrees around X-axis to flip it right-side up
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(180.0f));

        // Scale the block
        poseStack.scale((float)scale, (float)scale, (float)scale);

//? if <1.21 {
        // Build cache on first render
        if (!grassBlockCacheBuilt) {
            grassBlockCacheBuilt = true;
        }

        // Use Minecraft's BlockRenderer to render a grass block properly
        // The actual mesh is cached internally by Minecraft's block renderer
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.renderer.block.BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        // Render the grass block using Minecraft's built-in renderer
        blockRenderer.renderSingleBlock(
            net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),
            poseStack,
            bufferSource,
            15728880, // Full brightness
            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY
        );

//?} else if <26.1.2 {
        // Use Minecraft's BlockRenderer to render a grass block properly
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.renderer.block.BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        // Render the grass block using Minecraft's built-in renderer
        blockRenderer.renderSingleBlock(
            net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),
            poseStack,
            bufferSource,
            15728880, // Full brightness
            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY
        );

//?} else {
//?}
        poseStack.popPose();
    }

//? if <1.21.11 {
    /**
     * Helper method to render a single face of a cube
     */
    private static void renderCubeFace(Matrix4f matrix, PoseStack.Pose pose,
                                       MultiBufferSource.BufferSource bufferSource,
                                       ResourceLocation texture,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float nx, float ny, float nz) {
        RenderType renderType = RenderType.entityCutout(texture);
        var vertexConsumer = bufferSource.getBuffer(renderType);
//?} else if <26.2 {
    /**
     * Helper method to render a single face of a cube
     */
    private static void renderCubeFace(Matrix4f matrix, PoseStack.Pose pose,
                                       MultiBufferSource.BufferSource bufferSource,
                                       Identifier texture,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float nx, float ny, float nz) {
        RenderType renderType = RenderTypes.entityCutout(texture);
        var vertexConsumer = bufferSource.getBuffer(renderType);
//?} else {
//?}

//? if <1.21 {
        // Calculate the 4 corners of the face
        float minX = Math.min(x1, x2);
        float maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2);
        float maxY = Math.max(y1, y2);
        float minZ = Math.min(z1, z2);
        float maxZ = Math.max(z1, z2);

        // Determine which coordinates vary based on the normal
        if (ny != 0) { // Top or bottom face (Y constant)
            float y = y1;
            // Bottom-left
            vertexConsumer.vertex(matrix, minX, y, minZ)
                .color(255, 255, 255, 255)
                .uv(0, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            // Top-left
            vertexConsumer.vertex(matrix, minX, y, maxZ)
                .color(255, 255, 255, 255)
                .uv(0, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            // Top-right
            vertexConsumer.vertex(matrix, maxX, y, maxZ)
                .color(255, 255, 255, 255)
                .uv(1, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            // Bottom-right
            vertexConsumer.vertex(matrix, maxX, y, minZ)
                .color(255, 255, 255, 255)
                .uv(1, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
        } else if (nx != 0) { // Left or right face (X constant)
            float x = x1;
            vertexConsumer.vertex(matrix, x, minY, minZ)
                .color(255, 255, 255, 255)
                .uv(0, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, x, maxY, minZ)
                .color(255, 255, 255, 255)
                .uv(0, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, x, maxY, maxZ)
                .color(255, 255, 255, 255)
                .uv(1, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, x, minY, maxZ)
                .color(255, 255, 255, 255)
                .uv(1, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
        } else { // Front or back face (Z constant)
            float z = z1;
            vertexConsumer.vertex(matrix, minX, minY, z)
                .color(255, 255, 255, 255)
                .uv(0, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, minX, maxY, z)
                .color(255, 255, 255, 255)
                .uv(0, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, maxX, maxY, z)
                .color(255, 255, 255, 255)
                .uv(1, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, maxX, minY, z)
                .color(255, 255, 255, 255)
                .uv(1, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
        }
    }

    /**
     * Render a debug cube at the chest position to show rotation center
     */
    private static void renderDebugCube(PoseStack poseStack, MultiBufferSource buffer, PlayerModel model) {
        poseStack.pushPose();

        // Get chest position from the model's body part
        // The body part is positioned at the chest area
        ModelPart body = model.body;

        // Translate to chest center (body origin is at chest)
        poseStack.translate(0.0, -0.7, 0.0); // Move to chest height

        // Scale the cube to be visible
        poseStack.scale(0.2f, 0.2f, 0.2f);

        // Render a bright colored cube
        RenderType renderType = RenderType.lines();
        var vertexConsumer = buffer.getBuffer(renderType);

        // Draw cube wireframe (bright cyan/magenta for visibility)
        float size = 1.0f;
        Matrix4f matrix = poseStack.last().pose();

        // Draw all 12 edges of a cube
        // Bottom face
        addLine(vertexConsumer, matrix, -size, -size, -size, size, -size, -size, 0, 255, 255); // Cyan
        addLine(vertexConsumer, matrix, size, -size, -size, size, -size, size, 0, 255, 255);
        addLine(vertexConsumer, matrix, size, -size, size, -size, -size, size, 0, 255, 255);
        addLine(vertexConsumer, matrix, -size, -size, size, -size, -size, -size, 0, 255, 255);

        // Top face
        addLine(vertexConsumer, matrix, -size, size, -size, size, size, -size, 255, 0, 255); // Magenta
        addLine(vertexConsumer, matrix, size, size, -size, size, size, size, 255, 0, 255);
        addLine(vertexConsumer, matrix, size, size, size, -size, size, size, 255, 0, 255);
        addLine(vertexConsumer, matrix, -size, size, size, -size, size, -size, 255, 0, 255);

        // Vertical edges
        addLine(vertexConsumer, matrix, -size, -size, -size, -size, size, -size, 255, 255, 0); // Yellow
        addLine(vertexConsumer, matrix, size, -size, -size, size, size, -size, 255, 255, 0);
        addLine(vertexConsumer, matrix, size, -size, size, size, size, size, 255, 255, 0);
        addLine(vertexConsumer, matrix, -size, -size, size, -size, size, size, 255, 255, 0);

        poseStack.popPose();
    }
//?} else if <1.21.11 {
        // Calculate the 4 corners of the face
        float minX = Math.min(x1, x2);
        float maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2);
        float maxY = Math.max(y1, y2);
        float minZ = Math.min(z1, z2);
        float maxZ = Math.max(z1, z2);

        // Determine which coordinates vary based on the normal
        if (ny != 0) { // Top or bottom face (Y constant)
            float y = y1;
            // Bottom-left
            vertexConsumer.addVertex(matrix, minX, y, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            // Top-left
            vertexConsumer.addVertex(matrix, minX, y, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            // Top-right
            vertexConsumer.addVertex(matrix, maxX, y, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            // Bottom-right
            vertexConsumer.addVertex(matrix, maxX, y, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

        } else if (nx != 0) { // Left or right face (X constant)
            float x = x1;
            vertexConsumer.addVertex(matrix, x, minY, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, x, maxY, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, x, maxY, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, x, minY, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

        } else { // Front or back face (Z constant)
            float z = z1;
            vertexConsumer.addVertex(matrix, minX, minY, z)
                .setColor(255, 255, 255, 255)
                .setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, minX, maxY, z)
                .setColor(255, 255, 255, 255)
                .setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, maxX, maxY, z)
                .setColor(255, 255, 255, 255)
                .setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, maxX, minY, z)
                .setColor(255, 255, 255, 255)
                .setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

        }
    }

    /**
     * Render a debug cube at the chest position to show rotation center
     */
    private static void renderDebugCube(PoseStack poseStack, MultiBufferSource buffer, PlayerModel model) {
        poseStack.pushPose();

        // Get chest position from the model's body part
        // The body part is positioned at the chest area
        ModelPart body = model.body;

        // Translate to chest center (body origin is at chest)
        poseStack.translate(0.0, -0.7, 0.0); // Move to chest height

        // Scale the cube to be visible
        poseStack.scale(0.2f, 0.2f, 0.2f);

        // Render a bright colored cube
        RenderType renderType = RenderType.lines();
        var vertexConsumer = buffer.getBuffer(renderType);

        // Draw cube wireframe (bright cyan/magenta for visibility)
        float size = 1.0f;
        Matrix4f matrix = poseStack.last().pose();

        // Draw all 12 edges of a cube
        // Bottom face
        addLine(vertexConsumer, matrix, -size, -size, -size, size, -size, -size, 0, 255, 255); // Cyan
        addLine(vertexConsumer, matrix, size, -size, -size, size, -size, size, 0, 255, 255);
        addLine(vertexConsumer, matrix, size, -size, size, -size, -size, size, 0, 255, 255);
        addLine(vertexConsumer, matrix, -size, -size, size, -size, -size, -size, 0, 255, 255);

        // Top face
        addLine(vertexConsumer, matrix, -size, size, -size, size, size, -size, 255, 0, 255); // Magenta
        addLine(vertexConsumer, matrix, size, size, -size, size, size, size, 255, 0, 255);
        addLine(vertexConsumer, matrix, size, size, size, -size, size, size, 255, 0, 255);
        addLine(vertexConsumer, matrix, -size, size, size, -size, size, -size, 255, 0, 255);

        // Vertical edges
        addLine(vertexConsumer, matrix, -size, -size, -size, -size, size, -size, 255, 255, 0); // Yellow
        addLine(vertexConsumer, matrix, size, -size, -size, size, size, -size, 255, 255, 0);
        addLine(vertexConsumer, matrix, size, -size, size, size, size, size, 255, 255, 0);
        addLine(vertexConsumer, matrix, -size, -size, size, -size, size, size, 255, 255, 0);

        poseStack.popPose();
    }
//?} else if <26.2 {
        // Calculate the 4 corners of the face
        float minX = Math.min(x1, x2);
        float maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2);
        float maxY = Math.max(y1, y2);
        float minZ = Math.min(z1, z2);
        float maxZ = Math.max(z1, z2);

        // Determine which coordinates vary based on the normal
        if (ny != 0) { // Top or bottom face (Y constant)
            float y = y1;
            // Bottom-left
            vertexConsumer.addVertex(matrix, minX, y, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            // Top-left
            vertexConsumer.addVertex(matrix, minX, y, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            // Top-right
            vertexConsumer.addVertex(matrix, maxX, y, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            // Bottom-right
            vertexConsumer.addVertex(matrix, maxX, y, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

        } else if (nx != 0) { // Left or right face (X constant)
            float x = x1;
            vertexConsumer.addVertex(matrix, x, minY, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, x, maxY, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, x, maxY, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, x, minY, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

        } else { // Front or back face (Z constant)
            float z = z1;
            vertexConsumer.addVertex(matrix, minX, minY, z)
                .setColor(255, 255, 255, 255)
                .setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, minX, maxY, z)
                .setColor(255, 255, 255, 255)
                .setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, maxX, maxY, z)
                .setColor(255, 255, 255, 255)
                .setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

            vertexConsumer.addVertex(matrix, maxX, minY, z)
                .setColor(255, 255, 255, 255)
                .setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);

        }
    }

    /**
     * Render a debug cube at the chest position to show rotation center
     */
    private static void renderDebugCube(PoseStack poseStack, MultiBufferSource buffer, PlayerModel model) {
        poseStack.pushPose();

        // Get chest position from the model's body part
        // The body part is positioned at the chest area
        ModelPart body = model.body;

        // Translate to chest center (body origin is at chest)
        poseStack.translate(0.0, -0.7, 0.0); // Move to chest height

        // Scale the cube to be visible
        poseStack.scale(0.2f, 0.2f, 0.2f);

        // Render a bright colored cube
        RenderType renderType = RenderTypes.lines();
        var vertexConsumer = buffer.getBuffer(renderType);

        // Draw cube wireframe (bright cyan/magenta for visibility)
        float size = 1.0f;
        Matrix4f matrix = poseStack.last().pose();

        // Draw all 12 edges of a cube
        // Bottom face
        addLine(vertexConsumer, matrix, -size, -size, -size, size, -size, -size, 0, 255, 255); // Cyan
        addLine(vertexConsumer, matrix, size, -size, -size, size, -size, size, 0, 255, 255);
        addLine(vertexConsumer, matrix, size, -size, size, -size, -size, size, 0, 255, 255);
        addLine(vertexConsumer, matrix, -size, -size, size, -size, -size, -size, 0, 255, 255);

        // Top face
        addLine(vertexConsumer, matrix, -size, size, -size, size, size, -size, 255, 0, 255); // Magenta
        addLine(vertexConsumer, matrix, size, size, -size, size, size, size, 255, 0, 255);
        addLine(vertexConsumer, matrix, size, size, size, -size, size, size, 255, 0, 255);
        addLine(vertexConsumer, matrix, -size, size, size, -size, size, -size, 255, 0, 255);

        // Vertical edges
        addLine(vertexConsumer, matrix, -size, -size, -size, -size, size, -size, 255, 255, 0); // Yellow
        addLine(vertexConsumer, matrix, size, -size, -size, size, size, -size, 255, 255, 0);
        addLine(vertexConsumer, matrix, size, -size, size, size, size, size, 255, 255, 0);
        addLine(vertexConsumer, matrix, -size, -size, size, -size, size, size, 255, 255, 0);

        poseStack.popPose();
    }
//?} else {
//?}

    /**
     * Helper method to add a colored line to the vertex consumer
     * TODO: Update for 1.21.1 VertexConsumer API
     */
    private static void addLine(com.mojang.blaze3d.vertex.VertexConsumer consumer, Matrix4f matrix,
                                float x1, float y1, float z1, float x2, float y2, float z2,
                                int r, int g, int b) {
//? if <1.21 {
        consumer.vertex(matrix, x1, y1, z1).color(r, g, b, 255).normal(1, 0, 0).endVertex();
        consumer.vertex(matrix, x2, y2, z2).color(r, g, b, 255).normal(1, 0, 0).endVertex();
//?} else {
//?}
    }

    /**
     * Setup lighting for the model based on rotation
     * Adds dynamic brightness that changes with rotation angle
     */
    private static void setupLighting(float yRotation) {
        // Normalize rotation to 0-360
        float normalizedRotation = yRotation % 360.0f;
        if (normalizedRotation < 0) {
            normalizedRotation += 360.0f;
        }

        // Calculate brightness based on rotation (front is brightest)
        // Front (0°): 1.425f
        // Side (90°/270°): 1.2f
        // Back (180°): 1.3f
        float brightness;
        if (normalizedRotation < 90.0f) {
            // Front to side
            brightness = 1.425f - (normalizedRotation / 90.0f) * 0.225f;
        } else if (normalizedRotation < 180.0f) {
            // Side to back
            brightness = 1.2f + ((normalizedRotation - 90.0f) / 90.0f) * 0.1f;
        } else if (normalizedRotation < 270.0f) {
            // Back to side
            brightness = 1.3f - ((normalizedRotation - 180.0f) / 90.0f) * 0.1f;
        } else {
            // Side to front
            brightness = 1.2f + ((normalizedRotation - 270.0f) / 90.0f) * 0.225f;
        }

//? if <1.21.6 {
        // Setup shader lights with custom light vector
        // Standard light direction (from top-left-front)
        org.joml.Vector3f lightDirection = new org.joml.Vector3f(0.2f, 1.0f, -0.7f).normalize();

        RenderSystem.setShaderLights(
                new org.joml.Vector3f(lightDirection).mul(brightness),
                new org.joml.Vector3f(lightDirection).mul(brightness * 0.5f)
        );
//?} else if <26.2 {
        // 1.21.6+: RenderSystem.setShaderLights now takes GpuBufferSlice instead of Vector3f.
        // Use Lighting.Entry-based API instead. This method is currently unused.
        Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
//?} else {
        // 1.21.11: RenderSystem.setShaderLights now takes GpuBufferSlice instead of Vector3f.
        // Use Lighting.Entry-based API instead. This method is currently unused.
        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
//?}
    }

//? if <1.21 {
    /**
     * Render a simplified player model (just for testing)
     * Uses default Steve skin
     */
    public static void renderDefaultPlayer(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int x,
            int y,
            float scale,
            float yRotation
    ) {
        PreviewPlayerData defaultData = new PreviewPlayerData();
        defaultData.setSkinLocation(new ResourceLocation("textures/entity/steve.png"));
        defaultData.setModelType("classic");

        renderPlayerModel(poseStack, buffer, x, y, scale, yRotation, defaultData, 0, 0, false);
    }
//?} else if <1.21.11 {
    /**
     * Render a simplified player model (just for testing)
     * Uses default Steve skin
     */
    public static void renderDefaultPlayer(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int x,
            int y,
            float scale,
            float yRotation
    ) {
        PreviewPlayerData defaultData = new PreviewPlayerData();
        defaultData.setSkinLocation(ResourceLocation.withDefaultNamespace("textures/entity/steve.png"));
        defaultData.setModelType("classic");

        renderPlayerModel(poseStack, buffer, x, y, scale, yRotation, defaultData, 0, 0, false);
    }
//?} else if <26.2 {
    /**
     * Render a simplified player model (just for testing)
     * Uses default Steve skin
     */
    public static void renderDefaultPlayer(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int x,
            int y,
            float scale,
            float yRotation
    ) {
        PreviewPlayerData defaultData = new PreviewPlayerData();
        defaultData.setSkinLocation(Identifier.withDefaultNamespace("textures/entity/steve.png"));
        defaultData.setModelType("classic");

        renderPlayerModel(poseStack, buffer, x, y, scale, yRotation, defaultData, 0, 0, false);
    }
//?} else {
//?}

    /**
     * Clear the cached player entity
     * Call this when leaving a world to reset the player rendering state
     */
    public static void clearCachedPlayer() {
//? if <26.2 {
//?} else {
        if (classicModel != null) {
            SkinLayers3DIntegration.clearDeferredMeshes(classicModel.root());
        }
        if (slimModel != null) {
            SkinLayers3DIntegration.clearDeferredMeshes(slimModel.root());
        }
//?}
        cachedPlayer = null;
        clearPreviewCapes();
//? if <1.21 {
    }

    /**
     * Reset lighting state
     * Call this when screen changes to ensure proper lighting setup
     */
    public static void resetLightingState() {
        lightingSetup = false;
    }

    /**
     * Clear grass block cache
     * Call this when leaving world to clear cache
     */
    public static void clearGrassBlockCache() {
        grassBlockCacheBuilt = false;
//?} else if <1.21.6 {
//?} else {
        clearPendingCapes();
//?}
    }

    /**
     * Handle mouse press for debug positioning mode
     * Call this from your screen's mouseClicked method
     */
    public static boolean handleDebugMousePressed(int mouseX, int mouseY, int button) {
        if (!debugPositioningMode || button != 0) {
            return false;
        }

        // Start dragging
        isDraggingModel = true;
        dragStartX = mouseX;
        dragStartY = mouseY;
        dragStartOffsetX = debugOffsetX;
        dragStartOffsetY = debugOffsetY;
        return true;
    }

    /**
     * Handle mouse drag for debug positioning mode
     * Call this from your screen's mouseDragged method
     */
    public static boolean handleDebugMouseDragged(int mouseX, int mouseY, int button) {
        if (!debugPositioningMode || !isDraggingModel || button != 0) {
            return false;
        }

        // Update offsets based on drag distance
        int deltaX = mouseX - dragStartX;
        int deltaY = mouseY - dragStartY;

        debugOffsetX = dragStartOffsetX + deltaX;
        debugOffsetY = dragStartOffsetY + deltaY;

        return true;
    }

    /**
     * Handle mouse release for debug positioning mode
     * Call this from your screen's mouseReleased method
     */
    public static boolean handleDebugMouseReleased(int mouseX, int mouseY, int button) {
        if (!debugPositioningMode || !isDraggingModel || button != 0) {
            return false;
        }

        isDraggingModel = false;

        return true;
    }
}
