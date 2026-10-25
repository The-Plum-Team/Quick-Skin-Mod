package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
//? if <1.21.2 {
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.world.entity.EquipmentSlot;
//?} else {
import net.minecraft.client.renderer.entity.layers.WingsLayer;
//?}
//? if <1.21.9 {
import net.minecraft.client.renderer.MultiBufferSource;
//?} else {
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
//?}
//? if <1.21.11 {
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws what the live local player wears and holds on the skin menu's own posed model, as the
 * world shows it (ADR 0017): armour, elytra, a worn head and the held items, without the armour
 * pieces the player hides from everyone.
 *
 * <p>The skin menu draws its own model, so it can pose it and show a skin before it is chosen.
 * Rather than rebuilding vanilla's equipment layers, this borrows the ones the player renderer
 * of the previewed model type already holds (through {@link RendererLayers}, which a mixin adds),
 * so modded armour models a loader patches in come along. Those layers take their pose from the
 * renderer's own model, which the draw first poses like the preview's. From 1.21.9 armour models
 * pose themselves from the render state when the submitted features are drawn, so the preview's
 * pose is bound to that state for {@link #applyBoundPose}, which a mixin calls at the end of
 * {@code HumanoidModel.setupAnim}.
 *
 * <p>Nothing is written to the player: before 1.21.2 the layers read the entity and skip the
 * pieces the player hides, and from 1.21.2 they read a render state extracted from it, from which
 * the extraction already left the hidden pieces out.
 *
 * <p>The piece under the skin menu's mouse is drawn see-through, a ghost of itself that shows the
 * skin beneath ({@link #setGhost}): the draw leaves it out, and once the rest of the preview is
 * drawn, the cape included, its layers draw it alone again, every texture swapped for a
 * see-through draw of the same texture at the ghost's alpha. A hidden piece is drawn so too.
 */
@Environment(EnvType.CLIENT)
public final class PreviewEquipment {
    /** Full brightness, as the preview's model is drawn. */
    private static final int LIGHT = 15728880;

    /** The layers a living-entity renderer draws after its model; a mixin implements it. */
    public interface RendererLayers {
        List<?> quickskin$previewLayers();
    }

    /** The player renderer of each model type; a mixin implements it on the render dispatcher. */
    public interface PlayerRenderers {
        Map<?, ?> quickskin$playerRenderers();
    }

    /**
     * What one draw needs: the renderer whose layers draw, what they draw from (the player before
     * 1.21.2, a render state extracted from it after), and whether the player's elytra takes the
     * cape's place.
     */
    public record Worn(Object renderer, Object subject, boolean wings) {
    }

    /** Later than anything else the preview submits, so the ghost is drawn over the cape. */
    private static final int GHOST_ORDER = 64;
    /**
     * The texture a draw binds first, as its description names it: {@code Optional[ns:path]}
     * before 1.21.11, {@code location=ns:path} after. A texture made at run time, such as a Quick
     * Skin cape on an elytra, has no file name, so none is required.
     */
    private static final Pattern GHOST_TEXTURE = Pattern.compile("(?:Optional\\[|location=)([a-z0-9_.-]+):([a-z0-9_./-]+)");
    /** See-through draws of the textures ghosts have used, by texture. */
    private static final Map<String, Object> GHOST_TYPES = new HashMap<>();

    /** The piece the skin menu draws see-through ({@code HiddenArmor} bit, 0 for none), and its alpha. */
    private static int ghostPiece;
    private static float ghostAlpha = 1.0F;
    /** True while a preview's own draw runs, which then leaves the ghost's piece out (before 1.21.2). */
    private static boolean leavingOutGhost;

    /** Poses bound to the render states of preview draws from 1.21.9, until they are collected. */
    private static final Map<Object, PartPose[]> BOUND_POSES = new WeakHashMap<>();

    /** Where the skin menu's model last landed on screen, for {@link #outline}. */
    private static HumanoidModel<?> outlineModel;
    private static final Matrix4f OUTLINE_MATRIX = new Matrix4f();
    private static boolean outlineSlim;
    /** Counts the skin menu's draws, so outlines computed for one frame are not reused in the next. */
    private static int outlineFrame;
    /** True while an outline capture draws a piece, which then shows even if it is hidden. */
    private static boolean capturing;

    /** The skin menu's model as drawn this frame: the model, its transform to GUI space, its arms. */
    record Placement(HumanoidModel<?> model, Matrix4f toGui, boolean slim) {
    }

    /**
     * The boxes, in model pixels of the part they hang from, that an armour piece covers: the head
     * slot's helmet, the chest's body and the upper arms, the leggings' belt and legs, the boots'
     * lower legs; an elytra hangs behind the body. Each row is part (0 head, 1 body, 2 right arm,
     * 3 left arm, 4 right leg, 5 left leg) and the box's two corners.
     */
    private static final float[][] HEAD_BOXES = {{0, -5, -9, -5, 5, 1, 5}};
    private static final float[][] CHEST_BOXES = {
            {1, -5, -1, -3, 5, 13, 3}, {2, -4, -3, -3, 2, 4, 3}, {3, -2, -3, -3, 4, 4, 3}};
    private static final float[][] SLIM_CHEST_BOXES = {
            {1, -5, -1, -3, 5, 13, 3}, {2, -3, -3, -3, 2, 4, 3}, {3, -2, -3, -3, 3, 4, 3}};
    private static final float[][] ELYTRA_BOXES = {{1, -6, -1, 1, 6, 21, 4}};
    private static final float[][] LEGS_BOXES = {
            {1, -4.5F, 7, -2.5F, 4.5F, 12.5F, 2.5F},
            {4, -2.5F, -0.5F, -2.5F, 2.5F, 10, 2.5F}, {5, -2.5F, -0.5F, -2.5F, 2.5F, 10, 2.5F}};
    private static final float[][] FEET_BOXES = {{4, -3, 6, -3, 3, 13, 3}, {5, -3, 6, -3, 3, 13, 3}};

    private PreviewEquipment() {
    }

    /**
     * Remembers where a skin menu preview drew its model this frame: {@code toGui} takes model
     * space (before the model's own parts) to GUI coordinates.
     */
    static void rememberPreview(HumanoidModel<?> model, Matrix4f toGui, boolean slim) {
        outlineModel = model;
        OUTLINE_MATRIX.set(toGui);
        outlineSlim = slim;
        outlineFrame++;
    }

    /** Forgets the remembered preview, so a closed menu leaves no outline to draw and no ghost. */
    public static void forgetPreview() {
        outlineModel = null;
        outlineFrame++;
        ghostPiece = 0;
        ghostAlpha = 1.0F;
        PreviewArmorSilhouette.clearTextures();
    }

    /**
     * Has the skin menu's model show {@code piece} ({@code HiddenArmor} bit), shown or hidden, as
     * a see-through ghost at {@code alpha}; 0 shows every piece as it is.
     */
    public static void setGhost(int piece, float alpha) {
        ghostPiece = piece;
        ghostAlpha = Math.max(0.0F, Math.min(1.0F, alpha));
    }

    /** Whether a preview's own draw, running now, leaves {@code slot} out for the ghost to draw. */
    static boolean leavesOutGhost(net.minecraft.world.entity.EquipmentSlot slot) {
        if (!leavingOutGhost) {
            return false;
        }
        int piece = ghostPiece;
        return slot == net.minecraft.world.entity.EquipmentSlot.HEAD ? piece == 8
                : slot == net.minecraft.world.entity.EquipmentSlot.CHEST ? piece == 4
                : slot == net.minecraft.world.entity.EquipmentSlot.LEGS ? piece == 2
                : slot == net.minecraft.world.entity.EquipmentSlot.FEET && piece == 1;
    }

    /** Whether a ghost is to be drawn now. */
    private static boolean ghostShown() {
        return ghostPiece != 0 && ghostAlpha > 0.004F;
    }

    /**
     * The see-through draw of the texture a draw samples: null for the enchantment glint, which a
     * ghost goes without, and the draw itself when it names no texture to swap.
     */
    @Nullable
    private static Object ghostType(Object renderType) {
        String description = renderType.toString();
        Matcher matcher = GHOST_TEXTURE.matcher(description);
        if (!matcher.find()) {
            return description.contains("glint") ? null : renderType;
        }
        if (matcher.group(2).contains("enchanted_glint")) {
            return null;
        }
        String key = matcher.group(1) + ":" + matcher.group(2);
        synchronized (GHOST_TYPES) {
            Object known = GHOST_TYPES.get(key);
            if (known != null) {
                return known;
            }
        }
//? if <1.21.11 {
        ResourceLocation id = ResourceLocation.tryParse(key);
        if (id == null) {
            return renderType;
        }
        Object ghost = RenderType.entityTranslucent(id);
//?} else {
        Identifier id = Identifier.tryParse(key);
        if (id == null) {
            return renderType;
        }
        Object ghost = RenderTypes.entityTranslucent(id);
//?}
        synchronized (GHOST_TYPES) {
            GHOST_TYPES.put(key, ghost);
        }
        return ghost;
    }

    /** {@code color} (ARGB) with its alpha scaled by {@code alpha}. */
    private static int withAlpha(int color, float alpha) {
        return Math.round(((color >>> 24) & 0xFF) * alpha) << 24 | (color & 0xFFFFFF);
    }

    @Nullable
    static Placement placement() {
        HumanoidModel<?> model = outlineModel;
        return model == null ? null : new Placement(model, new Matrix4f(OUTLINE_MATRIX), outlineSlim);
    }

    static int outlineFrame() {
        return outlineFrame;
    }

    static boolean isCapturing() {
        return capturing;
    }

    static void setCapturing(boolean value) {
        capturing = value;
    }

    /** The player renderer of a model type, whose layers draw the live player's equipment. */
    @Nullable
    static Object playerRendererFor(Minecraft minecraft, boolean slim) {
        return playerRenderer(minecraft, slim);
    }

    /** Poses that renderer's model like the preview's, as a draw of the equipment does first. */
    static void poseRenderer(HumanoidModel<?> preview, Object renderer) {
        copyPose(preview, renderer);
    }

    /** Whether a layer draws something worn in an armour slot: armour, an elytra or a worn head. */
    static boolean drawsWornPiece(Object layer) {
        return drawsEquipment(layer) && !(layer instanceof ItemInHandLayer<?, ?>);
    }

    /** Whether a layer draws what the player holds. */
    static boolean drawsHeldItems(Object layer) {
        return layer instanceof ItemInHandLayer<?, ?>;
    }

    /** From 1.21.9, has armour posed from {@code state} take the preview's pose. */
    static void bindPose(Object state, HumanoidModel<?> preview) {
        synchronized (BOUND_POSES) {
            BOUND_POSES.put(state, storePose(preview));
        }
    }

    /**
     * The outline an armour piece ({@code HiddenArmor} bit) would have on the skin menu's model,
     * as one convex polygon of GUI coordinates per box it covers ({x0, y0, x1, y1, ...}); empty
     * when no preview was drawn. Where the polygons overlap, the caller draws only their union's
     * edge. {@code elytra} outlines the chest piece as folded wings.
     */
    public static List<float[]> outline(int piece, boolean elytra) {
        List<float[]> polygons = new ArrayList<>();
        HumanoidModel<?> model = outlineModel;
        if (model == null) {
            return polygons;
        }
        float[][] boxes = switch (piece) {
            case 8 -> HEAD_BOXES;
            case 4 -> elytra ? ELYTRA_BOXES : outlineSlim ? SLIM_CHEST_BOXES : CHEST_BOXES;
            case 2 -> LEGS_BOXES;
            case 1 -> FEET_BOXES;
            default -> new float[0][];
        };
        ModelPart[] parts = {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
        Matrix4f base = new Matrix4f(OUTLINE_MATRIX);
//? if >=1.21.2 {
        applyPart(base, model.root());
//?}
        for (float[] box : boxes) {
            Matrix4f transform = applyPart(new Matrix4f(base), parts[(int) box[0]]);
            float[] corners = new float[16];
            Vector3f point = new Vector3f();
            for (int i = 0; i < 8; i++) {
                point.set((i & 1) == 0 ? box[1] : box[4], (i & 2) == 0 ? box[2] : box[5], (i & 4) == 0 ? box[3] : box[6]);
                point.mul(1.0F / 16.0F);
                transform.transformPosition(point);
                corners[i * 2] = point.x;
                corners[i * 2 + 1] = point.y;
            }
            polygons.add(convexHull(corners));
        }
        return polygons;
    }

    /** Applies a model part's own placement, as {@code ModelPart.translateAndRotate} does. */
    private static Matrix4f applyPart(Matrix4f matrix, ModelPart part) {
        matrix.translate(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);
        if (part.xRot != 0.0F || part.yRot != 0.0F || part.zRot != 0.0F) {
            matrix.rotateZYX(part.zRot, part.yRot, part.xRot);
        }
        if (part.xScale != 1.0F || part.yScale != 1.0F || part.zScale != 1.0F) {
            matrix.scale(part.xScale, part.yScale, part.zScale);
        }
        return matrix;
    }

    /** The convex hull of points {x0, y0, x1, y1, ...}, counter-clockwise (monotone chain). */
    static float[] convexHull(float[] points) {
        int n = points.length / 2;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> points[a * 2] != points[b * 2]
                ? Float.compare(points[a * 2], points[b * 2]) : Float.compare(points[a * 2 + 1], points[b * 2 + 1]));
        int[] hull = new int[2 * n];
        int k = 0;
        for (int idx = 0; idx < n; idx++) {
            int i = order[idx];
            while (k >= 2 && cross(points, hull[k - 2], hull[k - 1], i) <= 0) k--;
            hull[k++] = i;
        }
        for (int idx = n - 2, lower = k + 1; idx >= 0; idx--) {
            int i = order[idx];
            while (k >= lower && cross(points, hull[k - 2], hull[k - 1], i) <= 0) k--;
            hull[k++] = i;
        }
        k--;
        float[] result = new float[k * 2];
        for (int i = 0; i < k; i++) {
            result[i * 2] = points[hull[i] * 2];
            result[i * 2 + 1] = points[hull[i] * 2 + 1];
        }
        return result;
    }

    private static float cross(float[] p, int o, int a, int b) {
        return (p[a * 2] - p[o * 2]) * (p[b * 2 + 1] - p[o * 2 + 1])
                - (p[a * 2 + 1] - p[o * 2 + 1]) * (p[b * 2] - p[o * 2]);
    }

    /**
     * What the live local player wears, for a preview of the {@code slim} or classic model that
     * asked to show it; null when there is nothing to draw it from.
     */
    @Nullable
    static Worn prepare(PreviewPlayerData playerData, boolean slim) {
        Minecraft minecraft = Minecraft.getInstance();
        AbstractClientPlayer player = minecraft.player;
        if (player == null || !playerData.isEquipmentVisible() || playerData.isElytraPreview()) {
            return null;
        }
        Object renderer = playerRenderer(minecraft, slim);
        if (!(renderer instanceof LivingEntityRenderer) || !(renderer instanceof RendererLayers)) {
            return null;
        }
//? if <1.21.2 {
        boolean wings = (com.quickskin.mod.client.services.LocalHiddenArmor.inEffect() & 4) == 0
                && player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        return new Worn(renderer, player, wings);
//?} else {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object state = ((LivingEntityRenderer) renderer).createRenderState(player, 1.0F);
        return new Worn(renderer, state, wearsWings(state));
//?}
    }

//? if >=1.21.2 {
    private static boolean wearsWings(Object state) {
        if (!(state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState humanoid)) {
            return false;
        }
//? if <1.21.4 {
        ItemStack chest = humanoid.chestItem;
//?} else {
        ItemStack chest = humanoid.chestEquipment;
//?}
        return chest.is(Items.ELYTRA);
    }
//?}

    /** The player renderer of the previewed model type, or the live player's own. */
    @Nullable
    private static Object playerRenderer(Minecraft minecraft, boolean slim) {
        Object dispatcher = minecraft.getEntityRenderDispatcher();
        if (dispatcher instanceof PlayerRenderers renderers) {
            for (Map.Entry<?, ?> entry : renderers.quickskin$playerRenderers().entrySet()) {
                if (isSlimKey(entry.getKey()) == slim) {
                    return entry.getValue();
                }
            }
        }
        return minecraft.getEntityRenderDispatcher().getRenderer(minecraft.player);
    }

    /** Whether a key of the dispatcher's player renderers names the slim model. */
    private static boolean isSlimKey(Object key) {
//? if <1.21.2 {
        return "slim".equals(key);
//?} else if <1.21.9 {
        return key == net.minecraft.client.resources.PlayerSkin.Model.SLIM;
//?} else {
        return key == net.minecraft.world.entity.player.PlayerModelType.SLIM;
//?}
    }

    /**
     * The layers through which accessory mods draw what the player wears in-world, such as
     * Artifacts' gloves: Curios (Forge and NeoForge), Trinkets (Fabric) and Accessories. They are
     * named by class only, so none is a dependency, and one that fails is left out for the rest
     * of the session.
     */
    private static final Set<String> ACCESSORY_LAYERS = Set.of(
            "top.theillusivec4.curios.client.render.CuriosLayer",
            "dev.emi.trinkets.TrinketFeatureRenderer",
            "io.wispforest.accessories.client.AccessoriesRenderLayer");
    private static final Set<Class<?>> FAILED_ACCESSORY_LAYERS = ConcurrentHashMap.newKeySet();

    /** Whether a layer draws an accessory mod's worn items, and has not failed. */
    static boolean drawsAccessories(Object layer) {
        Class<?> type = layer.getClass();
        return ACCESSORY_LAYERS.contains(type.getName()) && !FAILED_ACCESSORY_LAYERS.contains(type);
    }

    /**
     * Draws an accessory mod's layer, which is not Quick Skin's to vouch for: should it fail, the
     * preview goes on without it.
     */
    private static void drawAccessories(Object layer, PoseStack poseStack, Runnable draw) {
        poseStack.pushPose();
        try {
            draw.run();
        } catch (RuntimeException | LinkageError error) {
            if (FAILED_ACCESSORY_LAYERS.add(layer.getClass())) {
                QuickSkinInfo.LOGGER.warn("The preview leaves out {}, which failed to draw",
                        layer.getClass().getName(), error);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Whether a layer draws equipment: armour, held items, a worn head or the elytra. */
    private static boolean drawsEquipment(Object layer) {
        return layer instanceof HumanoidArmorLayer<?, ?, ?>
                || layer instanceof ItemInHandLayer<?, ?>
                || layer instanceof CustomHeadLayer<?, ?>
//? if <1.21.2 {
                || layer instanceof ElytraLayer<?, ?>;
//?} else {
                || layer instanceof WingsLayer<?, ?>;
//?}
    }

    /**
     * Poses the renderer's own model, which the layers follow, like the preview's model. Before
     * 1.21.2 the layers also take the model's state (child, riding, crouching, arm poses), which
     * the last draw through that renderer left, so all of it comes from the preview's model:
     * a renderer left in its child state drew the armour at a child's size, the helmet at the belly.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyPose(HumanoidModel<?> preview, Object renderer) {
        if (!(((LivingEntityRenderer) renderer).getModel() instanceof HumanoidModel<?> target)) {
            return;
        }
//? if <1.21.2 {
        ((HumanoidModel) preview).copyPropertiesTo((HumanoidModel) target);
//?}
        loadPose(target, storePose(preview));
    }

    private static ModelPart[] parts(HumanoidModel<?> model) {
        return new ModelPart[]{model.head, model.hat, model.body, model.rightArm, model.leftArm,
                model.rightLeg, model.leftLeg};
    }

    private static PartPose[] storePose(HumanoidModel<?> model) {
        ModelPart[] parts = parts(model);
        PartPose[] pose = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) {
            pose[i] = parts[i].storePose();
        }
        return pose;
    }

    private static void loadPose(HumanoidModel<?> model, PartPose[] pose) {
        ModelPart[] parts = parts(model);
        for (int i = 0; i < parts.length; i++) {
            parts[i].loadPose(pose[i]);
        }
    }

//? if <1.21.9 {
    /**
     * Draws the equipment over a preview model just drawn with {@code poseStack}: inline before
     * 1.21.6, and from the GUI skin renderer's mixin through the shared buffers until 1.21.9.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void render(Worn worn, HumanoidModel<?> preview, PoseStack poseStack, MultiBufferSource buffers) {
        copyPose(preview, worn.renderer());
        List<?> layers = ((RendererLayers) worn.renderer()).quickskin$previewLayers();
//? if <1.21.2 {
        // The layers skip the pieces the player hides themselves (LocalArmorLayerMixin), and the
        // ghost's piece, which renderGhost draws
        AbstractClientPlayer player = (AbstractClientPlayer) worn.subject();
        leavingOutGhost = ghostShown();
        try {
            for (Object layer : layers) {
                if (drawsEquipment(layer)) {
                    ((RenderLayer) layer).render(poseStack, buffers, LIGHT, player,
                            0.0F, 0.0F, 1.0F, player.tickCount, 0.0F, 0.0F);
                } else if (CuriosPreview.drawsInstead(layer)) {
                    // Curios' accessories one by one, so the ghost's one is left out
                    int leftOut = ghostShown() ? ghostPiece : 0;
                    drawAccessories(layer, poseStack, () -> CuriosPreview.render(player, worn.renderer(), poseStack,
                            buffers, LIGHT, leftOut));
                } else if (drawsAccessories(layer)) {
                    drawAccessories(layer, poseStack, () -> ((RenderLayer) layer).render(poseStack, buffers,
                            LIGHT, player, 0.0F, 0.0F, 1.0F, player.tickCount, 0.0F, 0.0F));
                }
            }
        } finally {
            leavingOutGhost = false;
        }
//?} else {
        if (ghostShown()) {
            // renderGhost draws it
            PlayerModelRenderer.blankPieces(
                    (net.minecraft.client.renderer.entity.state.EntityRenderState) worn.subject(), ghostPiece);
        }
        for (Object layer : layers) {
            if (drawsEquipment(layer)) {
                ((RenderLayer) layer).render(poseStack, buffers, LIGHT,
                        (net.minecraft.client.renderer.entity.state.EntityRenderState) worn.subject(), 0.0F, 0.0F);
            } else if (drawsAccessories(layer)) {
                drawAccessories(layer, poseStack, () -> ((RenderLayer) layer).render(poseStack, buffers, LIGHT,
                        (net.minecraft.client.renderer.entity.state.EntityRenderState) worn.subject(), 0.0F, 0.0F));
            }
        }
//?}
    }

    /**
     * Draws the skin menu's ghost over a preview drawn with {@code poseStack} through {@link
     * #render}: after the rest of the preview, the cape included, so the ghost lies over it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void renderGhost(@Nullable Worn worn, HumanoidModel<?> preview, PoseStack poseStack,
                                   MultiBufferSource buffers) {
        if (worn == null || !ghostShown()) {
            return;
        }
        int piece = ghostPiece;
        float alpha = ghostAlpha;
        MultiBufferSource ghostBuffers = renderType -> {
            Object ghost = ghostType(renderType);
            return new SeeThrough(ghost == null ? null : buffers.getBuffer((RenderType) ghost), alpha);
        };
        copyPose(preview, worn.renderer());
        List<?> layers = ((RendererLayers) worn.renderer()).quickskin$previewLayers();
        // A hidden piece is drawn as well
        capturing = true;
        try {
//? if <1.21.2 {
            AbstractClientPlayer player = (AbstractClientPlayer) worn.subject();
            if (CuriosPreview.isAccessory(piece)) {
                CuriosPreview.renderPiece(player, piece, worn.renderer(), poseStack, ghostBuffers, LIGHT);
                return;
            }
            PlayerModelRenderer.withOnlyPiece(player, piece, () -> {
                for (Object layer : layers) {
                    if (drawsWornPiece(layer)) {
                        ((RenderLayer) layer).render(poseStack, ghostBuffers, LIGHT, player,
                                0.0F, 0.0F, 1.0F, player.tickCount, 0.0F, 0.0F);
                    }
                }
            });
//?} else {
            Object state = ((LivingEntityRenderer) worn.renderer()).createRenderState(Minecraft.getInstance().player, 1.0F);
            net.minecraft.client.renderer.entity.state.EntityRenderState ghostState =
                    (net.minecraft.client.renderer.entity.state.EntityRenderState) state;
            PlayerModelRenderer.blankPieces(ghostState, 15 & ~piece);
            for (Object layer : layers) {
                if (drawsWornPiece(layer)) {
                    ((RenderLayer) layer).render(poseStack, ghostBuffers, LIGHT, ghostState, 0.0F, 0.0F);
                }
            }
//?}
        } finally {
            capturing = false;
        }
    }

    /** Passes a draw on with its alpha scaled, or drops it when it has no target. */
    private static final class SeeThrough implements VertexConsumer {
        @Nullable
        private final VertexConsumer target;
        private final float alpha;

        SeeThrough(@Nullable VertexConsumer target, float alpha) {
            this.target = target;
            this.alpha = alpha;
        }

//? if <1.21 {
        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            if (target != null) target.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            if (target != null) target.color(r, g, b, Math.round(a * alpha));
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            if (target != null) target.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            if (target != null) target.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            if (target != null) target.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            if (target != null) target.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            if (target != null) target.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            if (target != null) target.defaultColor(r, g, b, Math.round(a * alpha));
        }

        @Override
        public void unsetDefaultColor() {
            if (target != null) target.unsetDefaultColor();
        }

        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v,
                           int overlay, int light, float nx, float ny, float nz) {
            if (target != null) target.vertex(x, y, z, r, g, b, a * alpha, u, v, overlay, light, nx, ny, nz);
        }
//?} else {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (target != null) target.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            if (target != null) target.setColor(r, g, b, Math.round(a * alpha));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (target != null) target.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            if (target != null) target.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            if (target != null) target.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (target != null) target.setNormal(x, y, z);
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                              float nx, float ny, float nz) {
            if (target != null) target.addVertex(x, y, z, withAlpha(color, alpha), u, v, overlay, light, nx, ny, nz);
        }
//?}
    }

//?} else {
    /**
     * Submits the equipment over a preview model drawn with {@code poseStack}; armour models take
     * the preview's pose when the collector draws them.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void submit(Worn worn, HumanoidModel<?> preview, PoseStack poseStack, SubmitNodeCollector collector) {
        copyPose(preview, worn.renderer());
        synchronized (BOUND_POSES) {
            BOUND_POSES.put(worn.subject(), storePose(preview));
        }
        if (ghostShown()) {
            // submitGhost draws it
            PlayerModelRenderer.blankPieces(
                    (net.minecraft.client.renderer.entity.state.EntityRenderState) worn.subject(), ghostPiece);
        }
        for (Object layer : ((RendererLayers) worn.renderer()).quickskin$previewLayers()) {
            if (drawsEquipment(layer)) {
                ((RenderLayer) layer).submit(poseStack, collector, LIGHT,
                        (net.minecraft.client.renderer.entity.state.EntityRenderState) worn.subject(), 0.0F, 0.0F);
            } else if (drawsAccessories(layer)) {
                drawAccessories(layer, poseStack, () -> ((RenderLayer) layer).submit(poseStack, collector, LIGHT,
                        (net.minecraft.client.renderer.entity.state.EntityRenderState) worn.subject(), 0.0F, 0.0F));
            }
        }
    }

    /**
     * Submits the skin menu's ghost over a preview submitted with {@code poseStack} through {@link
     * #submit}, in an order after everything else the preview submits, the cape included; false
     * when there is none to draw.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean submitGhost(@Nullable Worn worn, HumanoidModel<?> preview, PoseStack poseStack,
                                      SubmitNodeCollector collector) {
        if (worn == null || !ghostShown()) {
            return false;
        }
        int piece = ghostPiece;
        copyPose(preview, worn.renderer());
        Object state;
        // A hidden piece is drawn as well
        capturing = true;
        try {
            state = ((LivingEntityRenderer) worn.renderer()).createRenderState(Minecraft.getInstance().player, 1.0F);
        } finally {
            capturing = false;
        }
        net.minecraft.client.renderer.entity.state.EntityRenderState ghostState =
                (net.minecraft.client.renderer.entity.state.EntityRenderState) state;
        PlayerModelRenderer.blankPieces(ghostState, 15 & ~piece);
        synchronized (BOUND_POSES) {
            BOUND_POSES.put(state, storePose(preview));
        }
        SubmitNodeCollector ghost = ghostCollector(collector, GHOST_ORDER, ghostAlpha);
        for (Object layer : ((RendererLayers) worn.renderer()).quickskin$previewLayers()) {
            if (drawsWornPiece(layer)) {
                ((RenderLayer) layer).submit(poseStack, ghost, LIGHT, ghostState, 0.0F, 0.0F);
            }
        }
        return true;
    }

    /**
     * A collector that passes what is submitted on to {@code collector} at {@code order} or later,
     * models drawn see-through at {@code alpha}: their texture's see-through draw, and their tint,
     * the third int after the draw in every era's signature, with its alpha scaled. The glint is
     * dropped; anything else (an item, custom geometry) is passed on as it is. Methods are told
     * apart by their parameter types, which no mapping renames.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SubmitNodeCollector ghostCollector(SubmitNodeCollector collector, int order, float alpha) {
        OrderedSubmitNodeCollector target = collector.order(order);
        return (SubmitNodeCollector) java.lang.reflect.Proxy.newProxyInstance(
                SubmitNodeCollector.class.getClassLoader(), new Class<?>[]{SubmitNodeCollector.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getParameterCount()) {
                            case 0 -> method.getReturnType() == int.class ? System.identityHashCode(proxy) : "QuickSkinArmourGhost";
                            default -> proxy == args[0];
                        };
                    }
                    if (OrderedSubmitNodeCollector.class.isAssignableFrom(method.getReturnType())) {
                        // A layer's own later order stays later, still after the cape
                        return ghostCollector(collector, order + (Integer) args[0], alpha);
                    }
                    if (method.isDefault()) {
                        return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    Object[] forwarded = args == null ? null : args.clone();
                    if (forwarded != null && !seeThrough(method.getParameterTypes(), forwarded, alpha)) {
                        return null;
                    }
                    try {
                        return method.invoke(method.getDeclaringClass().isInstance(target) ? target : collector, forwarded);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** Makes a model submission see-through in place; false drops it (the glint). */
    private static boolean seeThrough(Class<?>[] types, Object[] args, float alpha) {
        int typeIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof RenderType) {
                typeIndex = i;
                break;
            }
        }
        if (typeIndex < 0) {
            return true;
        }
        Object ghost = ghostType(args[typeIndex]);
        if (ghost == null) {
            return false;
        }
        if (types.length == 0 || !(Model.class.isAssignableFrom(types[0]) || ModelPart.class.isAssignableFrom(types[0]))) {
            return true;
        }
        args[typeIndex] = ghost;
        int ints = 0;
        for (int i = typeIndex + 1; i < types.length; i++) {
            if (types[i] == int.class && ++ints == 3) {
                args[i] = withAlpha((Integer) args[i], alpha);
                break;
            }
        }
        return true;
    }

//?}
    /**
     * Called at the end of {@code HumanoidModel.setupAnim}: an armour model posed from a preview
     * draw's render state takes the preview's pose. Every other model, and every model before
     * 1.21.9, is left as vanilla posed it.
     */
    public static void applyBoundPose(HumanoidModel<?> model, Object state) {
        PartPose[] pose;
        synchronized (BOUND_POSES) {
            if (BOUND_POSES.isEmpty()) {
                return;
            }
            pose = BOUND_POSES.get(state);
        }
        if (pose != null) {
            loadPose(model, pose);
        }
    }
}
