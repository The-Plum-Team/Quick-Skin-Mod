package com.quickskin.mod.client.rendering;

//? if <1.20.2 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
//?} else if <1.21.2 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
//?} else if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
//?} else if <1.21.9 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
//?} else if <26.1 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
//? if <1.21.11 {
import net.minecraft.client.model.PlayerModel;
//?} else {
import net.minecraft.client.model.player.PlayerModel;
//?}
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
//?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
//?}
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * The skin menu's armour pieces on the real player in the immersive view (ADR 0017), where the
 * menu's own model is not drawn: the player in the world is what the mouse points at.
 *
 * <p>The world's draw of the local player tells this class, once posed, where each part of it went
 * ({@link #modelPosed}): the player's model space to the camera's, and the camera's space to the
 * GUI. That stands in for the skin menu's preview ({@code PreviewEquipment.rememberWorld}), so the
 * menu's own outline tracing ({@link PreviewArmorSilhouette}) finds the pieces on the player as it
 * finds them on its model, every transform of the draw included: the turn to face the camera, the
 * skin-change move, the sit's jumps, the body's yaw and the game's own flip and scale.
 *
 * <p>The pieces nobody sees lie on the ground round the player's feet as items, drawn in the
 * player's own draw ({@link #drawDrops}) where the skin menu laid them out ({@link #show}); the
 * ghost of a hidden piece is drawn on the player there too ({@link #layersDrawn}). Positions handed
 * in and out are blocks from the player's feet, along the world's axes. Only this client's drawing
 * changes; nothing is sent. A draw is named by what the game's renderer is handed ({@code who}: the
 * entity before 1.21.2, its render state from then), with the player it draws beside it. Each
 * version family fills its own block in each guard chain below (fields, {@code clear},
 * {@code groundAt}, {@code wornPoint}, the entry points with the model copies). Only 1.20.1 and
 * 1.21.1 know a piece another mod takes off for the draw ({@code beforePlayer}); from 1.21.2 such
 * a mod changes the render state as it is filled, and no piece counts as covered. From 1.21.6 the
 * draw's pose stack goes only from the camera to the feet and the projection is kept on the GPU, so
 * the projection is read where the game hands it to the world's draw, and the camera's turn from
 * the model-view matrix (to 1.21.8), the matrices handed to the world's draw (1.21.9 to 1.21.11)
 * or the camera's render state (26.1 on); from 1.21.9 the ghost and the lying items are submitted
 * to the game's collection instead of drawn at once.
 *
 * <p>The hooks run inside the game's draw of the player. Should one of them fail, which other
 * mods' items drawn on the ground could make happen, it is logged once and the class stands down
 * for the rest of the game: the pieces can then no longer be clicked on the player, and the menu
 * and the world's draw go on as they would without it.
 */
@Environment(EnvType.CLIENT)
public final class WorldArmor {
    /** How long a draw of the player is taken for the current one, and how long the drops wait for the menu. */
    private static final long FRESH_NANOS = 250_000_000L;
    /**
     * How far above and below the feet, in blocks, the ground under a lying item is looked for: a
     * block beside the player carries the item on its top, a slab or a step down lets it settle a
     * little lower. Ground further down would lay it out of the view, which rests at the player's
     * height, or under the Confirm button, where it could not be clicked: without ground in that
     * reach (flying, at an edge) the items lie level with the feet.
     */
    private static final double GROUND_ABOVE = 1.25;
    private static final double GROUND_BELOW = 0.6;

    /**
     * An item to draw on the ground, or in the air on its way: its middle at {@code (x, y, z)}
     * blocks from the feet, {@code size} blocks across, turned {@code yaw} degrees about the
     * vertical (0 faces south), drawn darker when {@code dark}, with {@code badge} (or an empty
     * stack) in its lower corner as a badge {@code badgeSize} blocks across.
     */
    public record Drop(ItemStack stack, float x, float y, float z, float size, float yaw, boolean dark,
                       ItemStack badge, float badgeSize) {
    }

    private static List<Drop> drops = List.of();
    private static int dropsSession = -1;
    private static long dropsNanos;

    /** The session, and when, the player was last drawn posed for the menu. */
    private static int placedSession = -1;
    private static long placedNanos;
    /** The feet's space (blocks along the world's axes) to the camera's, and back. */
    private static final Matrix4f FEET_TO_CAMERA = new Matrix4f();
    private static final Matrix4f CAMERA_TO_FEET = new Matrix4f();
    /** The camera's space to GUI coordinates, homogeneous. */
    private static final Matrix4f CAMERA_TO_GUI = new Matrix4f();
    /** Where the feet were in the world in the last draw. */
    private static double feetX;
    private static double feetY;
    private static double feetZ;
    /** Set once a hook failed: from then on nothing here runs. */
    private static boolean failed;
    /** The player's model space to the camera's, as the model was drawn. */
    private static final Matrix4f MODEL_TO_CAMERA = new Matrix4f();
//? if <1.20.2 {
    /** Copies of the player's model as it was posed, one per model type, the outlines are traced from. */
    @Nullable
    private static PlayerModel<?> classicCopy;
    @Nullable
    private static PlayerModel<?> slimCopy;
    @Nullable
    private static PlayerModel<?> placedCopy;
    /** The armour the player wore before other mods' render hooks ran, for the pieces they cover. */
    @Nullable
    private static ItemStack[] armourBefore;
    @Nullable
    private static Object armourOf;
    private static int covered;
    /**
     * The draw of the player going on, from its start, or null: what names it ({@code who}, the
     * entity before 1.21.2, its render state from then) and the player it draws.
     */
    @Nullable
    private static Object drawing;
    @Nullable
    private static LivingEntity drawingEntity;
//?} else if <1.21.2 {
    /** Copies of the player's model as it was posed, one per model type, the outlines are traced from. */
    @Nullable
    private static PlayerModel<?> classicCopy;
    @Nullable
    private static PlayerModel<?> slimCopy;
    @Nullable
    private static PlayerModel<?> placedCopy;
    /** The armour the player wore before other mods' render hooks ran, for the pieces they cover. */
    @Nullable
    private static ItemStack[] armourBefore;
    @Nullable
    private static Object armourOf;
    private static int covered;
    /** The draw of the player going on, from its start, or null: the entity, and the player it draws. */
    @Nullable
    private static Object drawing;
    @Nullable
    private static LivingEntity drawingEntity;
//?} else if <1.21.6 {
    /** Copies of the player's model as it was posed, one per model type, the outlines are traced from. */
    @Nullable
    private static PlayerModel classicCopy;
    @Nullable
    private static PlayerModel slimCopy;
    @Nullable
    private static PlayerModel placedCopy;
    /**
     * The draw of the player going on, from its start, or null: the render state the renderer is
     * handed, and the player it was extracted from. Other mods' changes to what the player shows
     * happen at extraction from these versions, so no piece counts as covered here.
     */
    @Nullable
    private static Object drawing;
    @Nullable
    private static LivingEntity drawingEntity;
//?} else if <1.21.9 {
    /** Copies of the player's model as it was posed, one per model type, the outlines are traced from. */
    @Nullable
    private static PlayerModel classicCopy;
    @Nullable
    private static PlayerModel slimCopy;
    @Nullable
    private static PlayerModel placedCopy;
    /** The draw of the player going on, from its start, or null: its render state and the player. */
    @Nullable
    private static Object drawing;
    @Nullable
    private static LivingEntity drawingEntity;
    /**
     * The world's projection, the camera's space to clip space, as the game hands it to the world's
     * draw ({@link #worldProjection}): the game keeps it only on the GPU.
     */
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static boolean projected;
//?} else if <26.1 {
    /**
     * The camera's rotation and the projection the level renderer was handed for this frame's
     * world ({@link #levelMatrices}): the pose stack the player is submitted with holds neither,
     * only the way from the camera to the feet.
     */
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    /** Copies of the player's model as it was posed, one per model type, the outlines are traced from. */
    @Nullable
    private static PlayerModel classicCopy;
    @Nullable
    private static PlayerModel slimCopy;
    @Nullable
    private static PlayerModel placedCopy;
    /** The submit of the local player going on, from its start, or null: its render state. */
    @Nullable
    private static Object drawing;
//?} else {
    /**
     * The projection the world was drawn with this frame, bobbing and the screen's wobble
     * included ({@link #levelProjection}); until it is told, the camera's own.
     */
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static boolean projectionTold;
    /** Copies of the player's model as it was posed, one per model type, the outlines are traced from. */
    @Nullable
    private static PlayerModel classicCopy;
    @Nullable
    private static PlayerModel slimCopy;
    @Nullable
    private static PlayerModel placedCopy;
    /** The submit of the local player going on, from its start, or null: its render state. */
    @Nullable
    private static Object drawing;
    /** The camera's rotation for the submit going on: its pose stack goes only from the camera to the feet. */
    private static final Matrix4f VIEW = new Matrix4f();
//?}

    private WorldArmor() {
    }

    /**
     * Lays the items the skin menu shows on the ground round the player out for the next draws of
     * the immersive view {@code session}. The menu says so every frame; when it stops, they go.
     */
    public static void show(int session, List<Drop> laid) {
        drops = List.copyOf(laid);
        dropsSession = session;
        dropsNanos = System.nanoTime();
    }

    /** Forgets the items, where the player was drawn and the draw going on, as the menu closes. */
    public static void clear() {
        drops = List.of();
        dropsSession = -1;
        placedSession = -1;
//? if <1.20.2 {
        placedCopy = null;
        armourBefore = null;
        armourOf = null;
        covered = 0;
        drawing = null;
        drawingEntity = null;
//?} else if <1.21.2 {
        placedCopy = null;
        armourBefore = null;
        armourOf = null;
        covered = 0;
        drawing = null;
        drawingEntity = null;
//?} else if <1.21.6 {
        placedCopy = null;
        drawing = null;
        drawingEntity = null;
//?} else if <1.21.9 {
        placedCopy = null;
        drawing = null;
        drawingEntity = null;
//?} else if <26.1 {
        placedCopy = null;
        drawing = null;
//?} else {
        placedCopy = null;
        drawing = null;
//?}
    }

    /** Whether the player was drawn for the menu of the immersive view {@code session} just now. */
    public static boolean placed(int session) {
        return !failed && placedSession == session && System.nanoTime() - placedNanos < FRESH_NANOS;
    }

    /**
     * How high the ground is, in blocks above the feet, at {@code (x, z)} blocks from them along
     * the world's axes: the top of the first block that stops a fall there, looked for from a
     * block above the feet down to a little below them. 0 without a draw, without ground in
     * that reach, or where the search starts inside a block (a wall).
     */
    public static float groundAt(float x, float z) {
//? if <1.20.2 {
        Minecraft minecraft = Minecraft.getInstance();
        if (failed || placedCopy == null || minecraft.level == null) {
            return 0.0f;
        }
        try {
            Vec3 from = new Vec3(feetX + x, feetY + GROUND_ABOVE, feetZ + z);
            Vec3 to = new Vec3(feetX + x, feetY - GROUND_BELOW, feetZ + z);
            BlockHitResult hit = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
                return 0.0f;
            }
            return (float) (hit.getLocation().y - feetY);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
            return 0.0f;
        }
//?} else if <1.21.2 {
        return groundBelow(x, z);
//?} else if <1.21.6 {
        return groundBelow(x, z);
//?} else if <1.21.9 {
        Minecraft minecraft = Minecraft.getInstance();
        if (failed || placedCopy == null || minecraft.level == null) {
            return 0.0f;
        }
        try {
            Vec3 from = new Vec3(feetX + x, feetY + GROUND_ABOVE, feetZ + z);
            Vec3 to = new Vec3(feetX + x, feetY - GROUND_BELOW, feetZ + z);
            BlockHitResult hit = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
                return 0.0f;
            }
            return (float) (hit.getLocation().y - feetY);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
            return 0.0f;
        }
//?} else if <26.1 {
        Minecraft minecraft = Minecraft.getInstance();
        if (failed || placedCopy == null || minecraft.level == null) {
            return 0.0f;
        }
        try {
            Vec3 from = new Vec3(feetX + x, feetY + GROUND_ABOVE, feetZ + z);
            Vec3 to = new Vec3(feetX + x, feetY - GROUND_BELOW, feetZ + z);
            BlockHitResult hit = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
                return 0.0f;
            }
            return (float) (hit.getLocation().y - feetY);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
            return 0.0f;
        }
//?} else {
        Minecraft minecraft = Minecraft.getInstance();
        if (failed || placedCopy == null || minecraft.level == null) {
            return 0.0f;
        }
        try {
            Vec3 from = new Vec3(feetX + x, feetY + GROUND_ABOVE, feetZ + z);
            Vec3 to = new Vec3(feetX + x, feetY - GROUND_BELOW, feetZ + z);
            BlockHitResult hit = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
                return 0.0f;
            }
            return (float) (hit.getLocation().y - feetY);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
            return 0.0f;
        }
//?}
    }

    /** Stands down for the rest of the game after a hook failed, saying so once. */
    private static void fail(Throwable error) {
        if (!failed) {
            failed = true;
            QuickSkinInfo.LOGGER.warn("The immersive skin menu can no longer point at the armour on the player", error);
        }
        clear();
    }

    /**
     * Where a point {@code (x, y, z)} blocks from the player's feet is seen on the GUI in the last
     * draw: {x, y, how many GUI units a block across covers there}; null without a draw or behind
     * the camera.
     */
    @Nullable
    public static float[] toGui(float x, float y, float z) {
        Vector3f camera = FEET_TO_CAMERA.transformPosition(new Vector3f(x, y, z));
        if (camera.z > -0.05f) {
            return null;
        }
        Vector3f middle = PreviewEquipment.toGui(CAMERA_TO_GUI, new Vector3f(camera));
        Vector3f side = PreviewEquipment.toGui(CAMERA_TO_GUI, new Vector3f(camera).add(1.0f, 0.0f, 0.0f));
        return new float[]{middle.x, middle.y, Math.abs(side.x - middle.x)};
    }

    /**
     * Where the player wears a piece ({@code HiddenArmor} bit, a Cosmetic Armor Reworked piece over
     * one, or an accessory), in blocks from the feet, in the last draw: the middle of the head for
     * the helmet, of the chest, of the belt, of the feet; an accessory at the chest. Null without a
     * draw.
     */
    @Nullable
    public static float[] wornPoint(int piece) {
//? if <1.20.2 {
        PlayerModel<?> model = placedCopy;
        if (model == null) {
            return null;
        }
        int slot = CosmeticArmorPreview.slotOf(piece);
        Vector3f point = switch (slot) {
            case 8 -> onPart(model.head, 0.0f, -4.0f, 0.0f);
            case 2 -> onPart(model.body, 0.0f, 11.0f, 0.0f);
            case 1 -> onPart(model.rightLeg, 0.0f, 10.0f, 0.0f).add(onPart(model.leftLeg, 0.0f, 10.0f, 0.0f)).mul(0.5f);
            default -> onPart(model.body, 0.0f, 6.0f, 0.0f);
        };
        CAMERA_TO_FEET.transformPosition(point);
        return new float[]{point.x, point.y, point.z};
//?} else if <1.21.2 {
        return wornOn(placedCopy, piece);
//?} else if <1.21.6 {
        return wornOn(placedCopy, piece);
//?} else if <1.21.9 {
        PlayerModel model = placedCopy;
        if (model == null) {
            return null;
        }
        int slot = CosmeticArmorPreview.slotOf(piece);
        Vector3f point = switch (slot) {
            case 8 -> onPart(model, model.head, 0.0f, -4.0f, 0.0f);
            case 2 -> onPart(model, model.body, 0.0f, 11.0f, 0.0f);
            case 1 -> onPart(model, model.rightLeg, 0.0f, 10.0f, 0.0f)
                    .add(onPart(model, model.leftLeg, 0.0f, 10.0f, 0.0f)).mul(0.5f);
            default -> onPart(model, model.body, 0.0f, 6.0f, 0.0f);
        };
        CAMERA_TO_FEET.transformPosition(point);
        return new float[]{point.x, point.y, point.z};
//?} else if <26.1 {
        PlayerModel model = placedCopy;
        if (model == null) {
            return null;
        }
        int slot = CosmeticArmorPreview.slotOf(piece);
        Vector3f point = switch (slot) {
            case 8 -> onPart(model.head, 0.0f, -4.0f, 0.0f);
            case 2 -> onPart(model.body, 0.0f, 11.0f, 0.0f);
            case 1 -> onPart(model.rightLeg, 0.0f, 10.0f, 0.0f).add(onPart(model.leftLeg, 0.0f, 10.0f, 0.0f)).mul(0.5f);
            default -> onPart(model.body, 0.0f, 6.0f, 0.0f);
        };
        CAMERA_TO_FEET.transformPosition(point);
        return new float[]{point.x, point.y, point.z};
//?} else {
        PlayerModel model = placedCopy;
        if (model == null) {
            return null;
        }
        int slot = CosmeticArmorPreview.slotOf(piece);
        Vector3f point = switch (slot) {
            case 8 -> onPart(model.head, 0.0f, -4.0f, 0.0f);
            case 2 -> onPart(model.body, 0.0f, 11.0f, 0.0f);
            case 1 -> onPart(model.rightLeg, 0.0f, 10.0f, 0.0f).add(onPart(model.leftLeg, 0.0f, 10.0f, 0.0f)).mul(0.5f);
            default -> onPart(model.body, 0.0f, 6.0f, 0.0f);
        };
        CAMERA_TO_FEET.transformPosition(point);
        return new float[]{point.x, point.y, point.z};
//?}
    }

//? if <1.20.2 {
    /** A point of a model part, in its own pixels, in the camera's space. */
    private static Vector3f onPart(ModelPart part, float x, float y, float z) {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(MODEL_TO_CAMERA);
        part.translateAndRotate(poseStack);
        return poseStack.last().pose().transformPosition(new Vector3f(x / 16.0f, y / 16.0f, z / 16.0f));
    }

    /**
     * Called as a player's draw starts, before other mods' render hooks run: keeps the armour the
     * local player wears, so that what one of them takes off for the draw counts as covered.
     */
    public static void beforePlayer(boolean posing, LivingEntity entity) {
        if (!failed && posing && entity == Minecraft.getInstance().player) {
            armourBefore = PreviewEquipment.armour(entity);
            armourOf = entity;
        }
    }

    /**
     * Called as the game starts drawing a living entity, the draw named {@code who}, with the draw
     * at the entity's feet: keeps where the feet are seen from, for the local player while the view
     * poses them. The later hooks of the draw pass the same {@code who}.
     */
    public static void beginDraw(boolean posing, Object who, LivingEntity entity, float partialTick, PoseStack poseStack) {
        drawing = null;
        drawingEntity = null;
        if (failed || !posing || entity != Minecraft.getInstance().player) {
            return;
        }
        drawing = who;
        drawingEntity = entity;
        Vec3 feet = entity.getPosition(partialTick);
        feetX = feet.x;
        feetY = feet.y;
        feetZ = feet.z;
        FEET_TO_CAMERA.set(poseStack.last().pose());
        CAMERA_TO_FEET.set(FEET_TO_CAMERA).invert();
        Minecraft minecraft = Minecraft.getInstance();
        float width = minecraft.getWindow().getGuiScaledWidth();
        float height = minecraft.getWindow().getGuiScaledHeight();
        // Clip space to GUI coordinates: x from -1..1 to 0..width, y from 1..-1 to 0..height
        CAMERA_TO_GUI.set(new Matrix4f(
                width / 2.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -height / 2.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                width / 2.0f, height / 2.0f, 0.0f, 1.0f).mul(RenderSystem.getProjectionMatrix()));
        covered = armourBefore != null && armourOf == entity ? PreviewEquipment.emptied(entity, armourBefore) : 0;
        armourBefore = null;
        armourOf = null;
    }

    /**
     * Called once the game has posed the player's model for the draw {@link #beginDraw} started,
     * with the draw in the model's own space: from here on the menu's outlines are traced on it.
     */
    public static void modelPosed(int session, Object who, Object renderer, Object model, PoseStack poseStack) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            boolean slim = PreviewEquipment.isSlimRenderer(minecraft, renderer);
            PlayerModel<?> copy = copyOf(minecraft, slim);
            copyPose(humanoid, copy);
            placedCopy = copy;
            MODEL_TO_CAMERA.set(poseStack.last().pose());
            PreviewEquipment.rememberWorld(copy, MODEL_TO_CAMERA, CAMERA_TO_GUI, slim, covered);
            placedSession = session;
            placedNanos = System.nanoTime();
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /**
     * Called once the game has drawn the player's layers, still in the model's own space: draws the
     * ghost of the piece the menu shows see-through ({@code PreviewEquipment.setGhost}) on them.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void layersDrawn(Object who, Object renderer, Object model, PoseStack poseStack,
                                   MultiBufferSource buffers) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            // On a stack of its own, so a failure cannot leave the game's unbalanced; a ghost of the
            // wings tilts over the sit's block as the world's wings do (WorldPose.clearSitBlockWithWings)
            PreviewPlayerData posed = WorldPose.posedPreview(who);
            PreviewEquipment.renderGhost(new PreviewEquipment.Worn(renderer, drawingEntity, false,
                            posed == null ? null : PlayerModelRenderer.previewSit(posed)), humanoid,
                    own(poseStack), buffers);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /** A pose stack of its own that starts where {@code poseStack} is. */
    private static PoseStack own(PoseStack poseStack) {
        PoseStack own = new PoseStack();
        own.last().pose().set(poseStack.last().pose());
        own.last().normal().set(poseStack.last().normal());
        return own;
    }

    /**
     * Called as the player's draw ends, back at their feet: draws the items the menu lays round
     * them ({@link #show}) in the player's light, and ends the draw.
     */
    public static void drawDrops(int session, Object who, PoseStack poseStack, MultiBufferSource buffers,
                                 int light) {
        if (failed || who != drawing) {
            return;
        }
        drawing = null;
        drawingEntity = null;
        List<Drop> laid = drops;
        if (laid.isEmpty() || dropsSession != session || System.nanoTime() - dropsNanos > FRESH_NANOS) {
            return;
        }
        try {
            drawDrops(laid, own(poseStack), buffers, light);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    private static void drawDrops(List<Drop> laid, PoseStack poseStack, MultiBufferSource buffers, int light) {
        Minecraft minecraft = Minecraft.getInstance();
        int dark = LightTexture.pack(LightTexture.block(light) / 2, LightTexture.sky(light) / 2);
        for (Drop drop : laid) {
            if (drop.stack().isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(drop.x(), drop.y(), drop.z());
            poseStack.mulPose(Axis.YP.rotationDegrees(drop.yaw()));
            poseStack.pushPose();
            poseStack.scale(drop.size(), drop.size(), drop.size());
            // As the skin menu draws them, the way the GUI shows an item
            minecraft.getItemRenderer().renderStatic(drop.stack(), ItemDisplayContext.GUI, drop.dark() ? dark : light,
                    OverlayTexture.NO_OVERLAY, poseStack, buffers, minecraft.level, 0);
            poseStack.popPose();
            if (!drop.badge().isEmpty()) {
                poseStack.translate(drop.size() * 0.45f, -drop.size() * 0.4f, drop.size() * 0.1f);
                poseStack.scale(drop.badgeSize(), drop.badgeSize(), drop.badgeSize());
                minecraft.getItemRenderer().renderStatic(drop.badge(), ItemDisplayContext.GUI, light,
                        OverlayTexture.NO_OVERLAY, poseStack, buffers, minecraft.level, 0);
            }
            poseStack.popPose();
        }
    }

    /** The copy of the player's model of a model type, made once. */
    private static PlayerModel<?> copyOf(Minecraft minecraft, boolean slim) {
        if (slim) {
            if (slimCopy == null) {
                slimCopy = new PlayerModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            }
            return slimCopy;
        }
        if (classicCopy == null) {
            classicCopy = new PlayerModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return classicCopy;
    }

    /**
     * Gives the copy the model's pose and state (crouching, arm poses), for the outlines traced
     * after the world's draw: by then the game may have posed the same model for another player.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyPose(HumanoidModel<?> model, PlayerModel<?> copy) {
        ((HumanoidModel) model).copyPropertiesTo((HumanoidModel) copy);
        ModelPart[] from = {model.head, model.hat, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
        ModelPart[] to = {copy.head, copy.hat, copy.body, copy.rightArm, copy.leftArm, copy.rightLeg, copy.leftLeg};
        for (int i = 0; i < from.length; i++) {
            copyPart(from[i], to[i]);
        }
        if (model instanceof PlayerModel<?> player) {
            ModelPart[] layersFrom = {player.jacket, player.rightSleeve, player.leftSleeve, player.rightPants, player.leftPants};
            ModelPart[] layersTo = {copy.jacket, copy.rightSleeve, copy.leftSleeve, copy.rightPants, copy.leftPants};
            for (int i = 0; i < layersFrom.length; i++) {
                copyPart(layersFrom[i], layersTo[i]);
            }
        }
    }

    private static void copyPart(ModelPart from, ModelPart to) {
        to.loadPose(from.storePose());
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
        to.visible = from.visible;
    }
//?} else if <1.21.2 {
    // 1.21.1 draws the player from the entity, as 1.20.1 does: the same entry points and copies

    /** {@link #groundAt} for a draw of the player. */
    private static float groundBelow(float x, float z) {
        Minecraft minecraft = Minecraft.getInstance();
        if (failed || placedCopy == null || minecraft.level == null) {
            return 0.0f;
        }
        try {
            Vec3 from = new Vec3(feetX + x, feetY + GROUND_ABOVE, feetZ + z);
            Vec3 to = new Vec3(feetX + x, feetY - GROUND_BELOW, feetZ + z);
            BlockHitResult hit = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
                return 0.0f;
            }
            return (float) (hit.getLocation().y - feetY);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
            return 0.0f;
        }
    }

    /** {@link #wornPoint} on the copy of the posed model {@code model}. */
    @Nullable
    private static float[] wornOn(@Nullable PlayerModel<?> model, int piece) {
        if (model == null) {
            return null;
        }
        int slot = CosmeticArmorPreview.slotOf(piece);
        Vector3f point = switch (slot) {
            case 8 -> onPart(model.head, 0.0f, -4.0f, 0.0f);
            case 2 -> onPart(model.body, 0.0f, 11.0f, 0.0f);
            case 1 -> onPart(model.rightLeg, 0.0f, 10.0f, 0.0f).add(onPart(model.leftLeg, 0.0f, 10.0f, 0.0f)).mul(0.5f);
            default -> onPart(model.body, 0.0f, 6.0f, 0.0f);
        };
        CAMERA_TO_FEET.transformPosition(point);
        return new float[]{point.x, point.y, point.z};
    }

    /** A point of a model part, in its own pixels, in the camera's space. */
    private static Vector3f onPart(ModelPart part, float x, float y, float z) {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(MODEL_TO_CAMERA);
        part.translateAndRotate(poseStack);
        return poseStack.last().pose().transformPosition(new Vector3f(x / 16.0f, y / 16.0f, z / 16.0f));
    }

    /**
     * Called as a player's draw starts, before other mods' render hooks run: keeps the armour the
     * local player wears, so that what one of them takes off for the draw counts as covered.
     */
    public static void beforePlayer(boolean posing, LivingEntity entity) {
        if (!failed && posing && entity == Minecraft.getInstance().player) {
            armourBefore = PreviewEquipment.armour(entity);
            armourOf = entity;
        }
    }

    /**
     * Called as the game starts drawing a living entity, the draw named {@code who}, with the draw
     * at the entity's feet: keeps where the feet are seen from, for the local player while the view
     * poses them. The later hooks of the draw pass the same {@code who}.
     */
    public static void beginDraw(boolean posing, Object who, LivingEntity entity, float partialTick, PoseStack poseStack) {
        drawing = null;
        drawingEntity = null;
        if (failed || !posing || entity != Minecraft.getInstance().player) {
            return;
        }
        drawing = who;
        drawingEntity = entity;
        Vec3 feet = entity.getPosition(partialTick);
        feetX = feet.x;
        feetY = feet.y;
        feetZ = feet.z;
        FEET_TO_CAMERA.set(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());
        CAMERA_TO_FEET.set(FEET_TO_CAMERA).invert();
        Minecraft minecraft = Minecraft.getInstance();
        float width = minecraft.getWindow().getGuiScaledWidth();
        float height = minecraft.getWindow().getGuiScaledHeight();
        // Clip space to GUI coordinates: x from -1..1 to 0..width, y from 1..-1 to 0..height
        CAMERA_TO_GUI.set(new Matrix4f(
                width / 2.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -height / 2.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                width / 2.0f, height / 2.0f, 0.0f, 1.0f).mul(RenderSystem.getProjectionMatrix()));
        covered = armourBefore != null && armourOf == entity ? PreviewEquipment.emptied(entity, armourBefore) : 0;
        armourBefore = null;
        armourOf = null;
    }

    /**
     * Called once the game has posed the player's model for the draw {@link #beginDraw} started,
     * with the draw in the model's own space: from here on the menu's outlines are traced on it.
     */
    public static void modelPosed(int session, Object who, Object renderer, Object model, PoseStack poseStack) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            boolean slim = PreviewEquipment.isSlimRenderer(minecraft, renderer);
            PlayerModel<?> copy = copyOf(minecraft, slim);
            copyPose(humanoid, copy);
            placedCopy = copy;
            MODEL_TO_CAMERA.set(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());
            PreviewEquipment.rememberWorld(copy, MODEL_TO_CAMERA, CAMERA_TO_GUI, slim, covered);
            placedSession = session;
            placedNanos = System.nanoTime();
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /**
     * Called once the game has drawn the player's layers, still in the model's own space: draws the
     * ghost of the piece the menu shows see-through ({@code PreviewEquipment.setGhost}) on them.
     */
    public static void layersDrawn(Object who, Object renderer, Object model, PoseStack poseStack,
                                   MultiBufferSource buffers) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            // On a stack of its own, so a failure cannot leave the game's unbalanced; a ghost of the
            // wings tilts over the sit's block as the world's wings do (WorldPose.clearSitBlockWithWings)
            PreviewPlayerData posed = WorldPose.posedPreview(who);
            PreviewEquipment.renderGhost(new PreviewEquipment.Worn(renderer, drawingEntity, false,
                            posed == null ? null : PlayerModelRenderer.previewSit(posed)), humanoid,
                    own(poseStack), buffers);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /** A pose stack of its own that starts where {@code poseStack} is. */
    private static PoseStack own(PoseStack poseStack) {
        PoseStack own = new PoseStack();
        own.last().pose().set(poseStack.last().pose());
        own.last().normal().set(poseStack.last().normal());
        return own;
    }

    /**
     * Called as the player's draw ends, back at their feet: draws the items the menu lays round
     * them ({@link #show}) in the player's light, and ends the draw.
     */
    public static void drawDrops(int session, Object who, PoseStack poseStack, MultiBufferSource buffers,
                                 int light) {
        if (failed || who != drawing) {
            return;
        }
        drawing = null;
        drawingEntity = null;
        List<Drop> laid = drops;
        if (laid.isEmpty() || dropsSession != session || System.nanoTime() - dropsNanos > FRESH_NANOS) {
            return;
        }
        try {
            drawDrops(laid, own(poseStack), buffers, light);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    private static void drawDrops(List<Drop> laid, PoseStack poseStack, MultiBufferSource buffers, int light) {
        Minecraft minecraft = Minecraft.getInstance();
        int dark = LightTexture.pack(LightTexture.block(light) / 2, LightTexture.sky(light) / 2);
        for (Drop drop : laid) {
            if (drop.stack().isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(drop.x(), drop.y(), drop.z());
            poseStack.mulPose(Axis.YP.rotationDegrees(drop.yaw()));
            poseStack.pushPose();
            poseStack.scale(drop.size(), drop.size(), drop.size());
            // As the skin menu draws them, the way the GUI shows an item
            minecraft.getItemRenderer().renderStatic(drop.stack(), ItemDisplayContext.GUI, drop.dark() ? dark : light,
                    OverlayTexture.NO_OVERLAY, poseStack, buffers, minecraft.level, 0);
            poseStack.popPose();
            if (!drop.badge().isEmpty()) {
                poseStack.translate(drop.size() * 0.45f, -drop.size() * 0.4f, drop.size() * 0.1f);
                poseStack.scale(drop.badgeSize(), drop.badgeSize(), drop.badgeSize());
                minecraft.getItemRenderer().renderStatic(drop.badge(), ItemDisplayContext.GUI, light,
                        OverlayTexture.NO_OVERLAY, poseStack, buffers, minecraft.level, 0);
            }
            poseStack.popPose();
        }
    }

    /** The copy of the player's model of a model type, made once. */
    private static PlayerModel<?> copyOf(Minecraft minecraft, boolean slim) {
        if (slim) {
            if (slimCopy == null) {
                slimCopy = new PlayerModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            }
            return slimCopy;
        }
        if (classicCopy == null) {
            classicCopy = new PlayerModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return classicCopy;
    }

    /**
     * Gives the copy the model's pose and state (crouching, arm poses), for the outlines traced
     * after the world's draw: by then the game may have posed the same model for another player.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyPose(HumanoidModel<?> model, PlayerModel<?> copy) {
        ((HumanoidModel) model).copyPropertiesTo((HumanoidModel) copy);
        ModelPart[] from = {model.head, model.hat, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
        ModelPart[] to = {copy.head, copy.hat, copy.body, copy.rightArm, copy.leftArm, copy.rightLeg, copy.leftLeg};
        for (int i = 0; i < from.length; i++) {
            copyPart(from[i], to[i]);
        }
        if (model instanceof PlayerModel<?> player) {
            ModelPart[] layersFrom = {player.jacket, player.rightSleeve, player.leftSleeve, player.rightPants, player.leftPants};
            ModelPart[] layersTo = {copy.jacket, copy.rightSleeve, copy.leftSleeve, copy.rightPants, copy.leftPants};
            for (int i = 0; i < layersFrom.length; i++) {
                copyPart(layersFrom[i], layersTo[i]);
            }
        }
    }

    private static void copyPart(ModelPart from, ModelPart to) {
        to.loadPose(from.storePose());
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
        to.visible = from.visible;
    }
//?} else if <1.21.6 {
    // From 1.21.2 the renderer is handed a render state the game extracted from the player just
    // before, and draws it at once: the state names the draw, the player is found by its id

    /** {@link #groundAt} for a draw of the player. */
    private static float groundBelow(float x, float z) {
        Minecraft minecraft = Minecraft.getInstance();
        if (failed || placedCopy == null || minecraft.level == null) {
            return 0.0f;
        }
        try {
            Vec3 from = new Vec3(feetX + x, feetY + GROUND_ABOVE, feetZ + z);
            Vec3 to = new Vec3(feetX + x, feetY - GROUND_BELOW, feetZ + z);
            BlockHitResult hit = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
                return 0.0f;
            }
            return (float) (hit.getLocation().y - feetY);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
            return 0.0f;
        }
    }

    /** {@link #wornPoint} on the copy of the posed model {@code model}. */
    @Nullable
    private static float[] wornOn(@Nullable PlayerModel model, int piece) {
        if (model == null) {
            return null;
        }
        int slot = CosmeticArmorPreview.slotOf(piece);
        Vector3f point = switch (slot) {
            case 8 -> onPart(model, model.head, 0.0f, -4.0f, 0.0f);
            case 2 -> onPart(model, model.body, 0.0f, 11.0f, 0.0f);
            case 1 -> onPart(model, model.rightLeg, 0.0f, 10.0f, 0.0f)
                    .add(onPart(model, model.leftLeg, 0.0f, 10.0f, 0.0f)).mul(0.5f);
            default -> onPart(model, model.body, 0.0f, 6.0f, 0.0f);
        };
        CAMERA_TO_FEET.transformPosition(point);
        return new float[]{point.x, point.y, point.z};
    }

    /**
     * A point of a model part, in its own pixels, in the camera's space: the parts hang from the
     * model's root from 1.21.2.
     */
    private static Vector3f onPart(PlayerModel model, ModelPart part, float x, float y, float z) {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(MODEL_TO_CAMERA);
        model.root().translateAndRotate(poseStack);
        part.translateAndRotate(poseStack);
        return poseStack.last().pose().transformPosition(new Vector3f(x / 16.0f, y / 16.0f, z / 16.0f));
    }

    /**
     * Called as the game starts drawing a living entity's render state, with the draw at the
     * entity's feet: keeps where the feet are seen from, for the local player while the view poses
     * them. The later hooks of the draw pass the same state.
     */
    public static void beginDraw(boolean posing, LivingEntityRenderState state, PoseStack poseStack) {
        drawing = null;
        drawingEntity = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (failed || !posing || !(state instanceof PlayerRenderState player) || minecraft.player == null
                || player.id != minecraft.player.getId() || minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(player.id);
        if (entity != minecraft.player) {
            return;
        }
        drawing = state;
        drawingEntity = minecraft.player;
        // The state's position is already the frame's
        feetX = state.x;
        feetY = state.y;
        feetZ = state.z;
        FEET_TO_CAMERA.set(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());
        CAMERA_TO_FEET.set(FEET_TO_CAMERA).invert();
        float width = minecraft.getWindow().getGuiScaledWidth();
        float height = minecraft.getWindow().getGuiScaledHeight();
        // Clip space to GUI coordinates: x from -1..1 to 0..width, y from 1..-1 to 0..height
        CAMERA_TO_GUI.set(new Matrix4f(
                width / 2.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -height / 2.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                width / 2.0f, height / 2.0f, 0.0f, 1.0f).mul(RenderSystem.getProjectionMatrix()));
    }

    /**
     * Called once the game has posed the player's model for the draw {@link #beginDraw} started,
     * with the draw in the model's own space: from here on the menu's outlines are traced on it.
     */
    public static void modelPosed(int session, Object who, Object renderer, Object model, PoseStack poseStack) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            boolean slim = PreviewEquipment.isSlimRenderer(minecraft, renderer);
            PlayerModel copy = copyOf(minecraft, slim);
            copyPose(humanoid, copy);
            placedCopy = copy;
            MODEL_TO_CAMERA.set(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());
            PreviewEquipment.rememberWorld(copy, MODEL_TO_CAMERA, CAMERA_TO_GUI, slim, 0);
            placedSession = session;
            placedNanos = System.nanoTime();
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /**
     * Called once the game has drawn the player's layers, still in the model's own space: draws the
     * ghost of the piece the menu shows see-through ({@code PreviewEquipment.setGhost}) on them.
     */
    public static void layersDrawn(Object who, Object renderer, Object model, PoseStack poseStack,
                                   MultiBufferSource buffers) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            // On a stack of its own, so a failure cannot leave the game's unbalanced; a ghost of the
            // wings tilts over the sit's block as the world's wings do (WorldPose.clearSitBlockWithWings).
            // Up to 1.21.8 a renderer extracts every entity into one state of its own, so the ghost's
            // state is the very state this draw is drawing, which the rest of the draw (name tag,
            // leash, the loaders' events, other mods' hooks) still reads: the ghost extracts the
            // player into it again with only its piece, and the state is given back as it was after
            PreviewPlayerData posed = WorldPose.posedPreview(who);
            Object[] kept = keep(who);
            try {
                PreviewEquipment.renderGhost(new PreviewEquipment.Worn(renderer, who, false,
                                posed == null ? null : PlayerModelRenderer.previewSit(posed)), humanoid,
                        own(poseStack), buffers);
            } finally {
                giveBack(who, kept);
            }
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /**
     * The fields of each render state class a draw may change, its superclasses' included: those
     * that can be written back (not final, and public or open to this mod, which leaves out only
     * the private fields other mods add to a state where the game keeps them closed).
     */
    private static final Map<Class<?>, Field[]> STATE_FIELDS = new HashMap<>();

    /** The values of {@code state}'s fields, for {@link #giveBack}. */
    private static Object[] keep(Object state) {
        Field[] fields = stateFields(state.getClass());
        Object[] values = new Object[fields.length];
        try {
            for (int i = 0; i < fields.length; i++) {
                values[i] = fields[i].get(state);
            }
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
        return values;
    }

    /**
     * Writes back the values {@link #keep} kept. The items the state draws in a hand or on the head
     * are objects of the state's own, filled again by the extraction with the same items, and are
     * left as they are.
     */
    private static void giveBack(Object state, Object[] values) {
        Field[] fields = stateFields(state.getClass());
        try {
            for (int i = 0; i < fields.length; i++) {
                fields[i].set(state, values[i]);
            }
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Field[] stateFields(Class<?> type) {
        return STATE_FIELDS.computeIfAbsent(type, start -> {
            List<Field> fields = new ArrayList<>();
            for (Class<?> current = start; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (!Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers) && field.trySetAccessible()) {
                        fields.add(field);
                    }
                }
            }
            return fields.toArray(new Field[0]);
        });
    }

    /** A pose stack of its own that starts where {@code poseStack} is. */
    private static PoseStack own(PoseStack poseStack) {
        PoseStack own = new PoseStack();
        own.last().pose().set(poseStack.last().pose());
        own.last().normal().set(poseStack.last().normal());
        return own;
    }

    /**
     * Called as the player's draw ends, back at their feet: draws the items the menu lays round
     * them ({@link #show}) in the player's light, and ends the draw.
     */
    public static void drawDrops(int session, Object who, PoseStack poseStack, MultiBufferSource buffers,
                                 int light) {
        if (failed || who != drawing) {
            return;
        }
        drawing = null;
        drawingEntity = null;
        List<Drop> laid = drops;
        if (laid.isEmpty() || dropsSession != session || System.nanoTime() - dropsNanos > FRESH_NANOS) {
            return;
        }
        try {
            drawDrops(laid, own(poseStack), buffers, light);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    private static void drawDrops(List<Drop> laid, PoseStack poseStack, MultiBufferSource buffers, int light) {
        Minecraft minecraft = Minecraft.getInstance();
        int dark = LightTexture.pack(LightTexture.block(light) / 2, LightTexture.sky(light) / 2);
        for (Drop drop : laid) {
            if (drop.stack().isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(drop.x(), drop.y(), drop.z());
            poseStack.mulPose(Axis.YP.rotationDegrees(drop.yaw()));
            poseStack.pushPose();
            poseStack.scale(drop.size(), drop.size(), drop.size());
            // As the skin menu draws them, the way the GUI shows an item
            minecraft.getItemRenderer().renderStatic(drop.stack(), ItemDisplayContext.GUI, drop.dark() ? dark : light,
                    OverlayTexture.NO_OVERLAY, poseStack, buffers, minecraft.level, 0);
            poseStack.popPose();
            if (!drop.badge().isEmpty()) {
                poseStack.translate(drop.size() * 0.45f, -drop.size() * 0.4f, drop.size() * 0.1f);
                poseStack.scale(drop.badgeSize(), drop.badgeSize(), drop.badgeSize());
                minecraft.getItemRenderer().renderStatic(drop.badge(), ItemDisplayContext.GUI, light,
                        OverlayTexture.NO_OVERLAY, poseStack, buffers, minecraft.level, 0);
            }
            poseStack.popPose();
        }
    }

    /** The copy of the player's model of a model type, made once. */
    private static PlayerModel copyOf(Minecraft minecraft, boolean slim) {
        if (slim) {
            if (slimCopy == null) {
                slimCopy = new PlayerModel(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            }
            return slimCopy;
        }
        if (classicCopy == null) {
            classicCopy = new PlayerModel(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return classicCopy;
    }

    /**
     * Gives the copy the model's pose, root included, for the outlines traced after the world's
     * draw: by then the game may have posed the same model for another player. The outer skin
     * parts are children of the parts they cover from 1.21.2 and keep their own local poses; only
     * whether they show is copied.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyPose(HumanoidModel<?> model, PlayerModel copy) {
        ((HumanoidModel) model).copyPropertiesTo((HumanoidModel) copy);
        ModelPart[] from = {model.root(), model.head, model.hat, model.body, model.rightArm, model.leftArm,
                model.rightLeg, model.leftLeg};
        ModelPart[] to = {copy.root(), copy.head, copy.hat, copy.body, copy.rightArm, copy.leftArm,
                copy.rightLeg, copy.leftLeg};
        for (int i = 0; i < from.length; i++) {
            copyPart(from[i], to[i]);
        }
        if (model instanceof PlayerModel player) {
            ModelPart[] layersFrom = {player.jacket, player.rightSleeve, player.leftSleeve, player.rightPants, player.leftPants};
            ModelPart[] layersTo = {copy.jacket, copy.rightSleeve, copy.leftSleeve, copy.rightPants, copy.leftPants};
            for (int i = 0; i < layersFrom.length; i++) {
                layersTo[i].visible = layersFrom[i].visible;
            }
        }
    }

    private static void copyPart(ModelPart from, ModelPart to) {
        to.loadPose(from.storePose());
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
        to.visible = from.visible;
    }
//?} else if <1.21.9 {
    /**
     * Keeps the projection the game draws the world with this frame ({@code GameRenderer}
     * hands it to the level renderer), which the GUI coordinates of the player's parts need.
     */
    public static void worldProjection(Matrix4f projection) {
        PROJECTION.set(projection);
        projected = true;
    }

    /** Whether the draw {@code who} is the local player's draw this class follows now. */
    public static boolean isDrawing(Object who) {
        return !failed && who != null && who == drawing;
    }

    /** A point of a model part, in its own pixels, in the camera's space, the model's root first. */
    private static Vector3f onPart(PlayerModel model, ModelPart part, float x, float y, float z) {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(MODEL_TO_CAMERA);
        model.root().translateAndRotate(poseStack);
        part.translateAndRotate(poseStack);
        return poseStack.last().pose().transformPosition(new Vector3f(x / 16.0f, y / 16.0f, z / 16.0f));
    }

    /**
     * Called as the game starts drawing a living entity, the draw of the render state {@code who},
     * with the draw at the entity's feet: keeps where the feet are seen from, for the local player
     * ({@code entity}) while the view poses them. The world's draw puts the camera's turn in the
     * game's model-view matrix and the rest in {@code poseStack}; the state holds where the feet
     * are this frame. The later hooks of the draw pass the same {@code who}. The armour other
     * mods take off for the draw is not known here: the render state was filled before.
     */
    public static void beginDraw(boolean posing, Object who, @Nullable LivingEntity entity, PoseStack poseStack) {
        drawing = null;
        drawingEntity = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (failed || !posing || entity == null || entity != minecraft.player || !projected
                || !(who instanceof PlayerRenderState state)) {
            return;
        }
        drawing = who;
        drawingEntity = entity;
        feetX = state.x;
        feetY = state.y;
        feetZ = state.z;
        FEET_TO_CAMERA.set(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());
        CAMERA_TO_FEET.set(FEET_TO_CAMERA).invert();
        float width = minecraft.getWindow().getGuiScaledWidth();
        float height = minecraft.getWindow().getGuiScaledHeight();
        // Clip space to GUI coordinates: x from -1..1 to 0..width, y from 1..-1 to 0..height
        CAMERA_TO_GUI.set(new Matrix4f(
                width / 2.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -height / 2.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                width / 2.0f, height / 2.0f, 0.0f, 1.0f).mul(PROJECTION));
    }

    /**
     * Called once the game has posed the player's model for the draw {@link #beginDraw} started,
     * with the draw in the model's own space: from here on the menu's outlines are traced on it.
     */
    public static void modelPosed(int session, Object who, Object renderer, Object model, PoseStack poseStack) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            boolean slim = PreviewEquipment.isSlimRenderer(minecraft, renderer);
            PlayerModel copy = copyOf(minecraft, slim);
            copyPose(humanoid, copy);
            placedCopy = copy;
            MODEL_TO_CAMERA.set(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());
            PreviewEquipment.rememberWorld(copy, MODEL_TO_CAMERA, CAMERA_TO_GUI, slim, 0);
            placedSession = session;
            placedNanos = System.nanoTime();
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /**
     * Called once the game has drawn the player's layers, still in the model's own space: draws the
     * ghost of the piece the menu shows see-through ({@code PreviewEquipment.setGhost}) on them.
     * The ghost is drawn from a render state of its own, which the caller hands the renderer for
     * this call: the renderer's own one is the state the world is still drawing.
     */
    public static void layersDrawn(Object who, Object renderer, Object model, PoseStack poseStack,
                                   MultiBufferSource buffers) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            // On a stack of its own, so a failure cannot leave the game's unbalanced; a ghost of the
            // wings tilts over the sit's block as the world's wings do (WorldPose.clearSitBlockWithWings)
            PreviewPlayerData posed = WorldPose.posedPreview(who);
            PreviewEquipment.renderGhost(new PreviewEquipment.Worn(renderer, drawingEntity, false,
                            posed == null ? null : PlayerModelRenderer.previewSit(posed)), humanoid,
                    own(poseStack), buffers);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /** A pose stack of its own that starts where {@code poseStack} is. */
    private static PoseStack own(PoseStack poseStack) {
        PoseStack own = new PoseStack();
        own.last().pose().set(poseStack.last().pose());
        own.last().normal().set(poseStack.last().normal());
        return own;
    }

    /**
     * Called as the player's draw ends, back at their feet: draws the items the menu lays round
     * them ({@link #show}) in the player's light, and ends the draw.
     */
    public static void drawDrops(int session, Object who, PoseStack poseStack, MultiBufferSource buffers,
                                 int light) {
        if (failed || who != drawing) {
            return;
        }
        drawing = null;
        drawingEntity = null;
        List<Drop> laid = drops;
        if (laid.isEmpty() || dropsSession != session || System.nanoTime() - dropsNanos > FRESH_NANOS) {
            return;
        }
        try {
            drawDrops(laid, own(poseStack), buffers, light);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    private static void drawDrops(List<Drop> laid, PoseStack poseStack, MultiBufferSource buffers, int light) {
        Minecraft minecraft = Minecraft.getInstance();
        int dark = LightTexture.pack(LightTexture.block(light) / 2, LightTexture.sky(light) / 2);
        for (Drop drop : laid) {
            if (drop.stack().isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(drop.x(), drop.y(), drop.z());
            poseStack.mulPose(Axis.YP.rotationDegrees(drop.yaw()));
            poseStack.pushPose();
            poseStack.scale(drop.size(), drop.size(), drop.size());
            // As the skin menu draws them, the way the GUI shows an item
            minecraft.getItemRenderer().renderStatic(drop.stack(), ItemDisplayContext.GUI, drop.dark() ? dark : light,
                    OverlayTexture.NO_OVERLAY, poseStack, buffers, minecraft.level, 0);
            poseStack.popPose();
            if (!drop.badge().isEmpty()) {
                poseStack.translate(drop.size() * 0.45f, -drop.size() * 0.4f, drop.size() * 0.1f);
                poseStack.scale(drop.badgeSize(), drop.badgeSize(), drop.badgeSize());
                minecraft.getItemRenderer().renderStatic(drop.badge(), ItemDisplayContext.GUI, light,
                        OverlayTexture.NO_OVERLAY, poseStack, buffers, minecraft.level, 0);
            }
            poseStack.popPose();
        }
    }

    /** The copy of the player's model of a model type, made once. */
    private static PlayerModel copyOf(Minecraft minecraft, boolean slim) {
        if (slim) {
            if (slimCopy == null) {
                slimCopy = new PlayerModel(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            }
            return slimCopy;
        }
        if (classicCopy == null) {
            classicCopy = new PlayerModel(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return classicCopy;
    }

    /**
     * Gives the copy the model's pose, root included, for the outlines traced after the world's
     * draw: by then the game may have posed the same model for another player.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyPose(HumanoidModel<?> model, PlayerModel copy) {
        ((HumanoidModel) model).copyPropertiesTo((HumanoidModel) copy);
        ModelPart[] from = {model.root(), model.head, model.hat, model.body, model.rightArm, model.leftArm,
                model.rightLeg, model.leftLeg};
        ModelPart[] to = {copy.root(), copy.head, copy.hat, copy.body, copy.rightArm, copy.leftArm,
                copy.rightLeg, copy.leftLeg};
        for (int i = 0; i < from.length; i++) {
            copyPart(from[i], to[i]);
        }
        if (model instanceof PlayerModel player) {
            ModelPart[] layersFrom = {player.jacket, player.rightSleeve, player.leftSleeve, player.rightPants, player.leftPants};
            ModelPart[] layersTo = {copy.jacket, copy.rightSleeve, copy.leftSleeve, copy.rightPants, copy.leftPants};
            for (int i = 0; i < layersFrom.length; i++) {
                copyPart(layersFrom[i], layersTo[i]);
            }
        }
    }

    private static void copyPart(ModelPart from, ModelPart to) {
        to.loadPose(from.storePose());
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
        to.visible = from.visible;
    }
//?} else if <26.1 {
    /** A point of a model part, in its own pixels, in the camera's space. */
    private static Vector3f onPart(ModelPart part, float x, float y, float z) {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(MODEL_TO_CAMERA);
        part.translateAndRotate(poseStack);
        return poseStack.last().pose().transformPosition(new Vector3f(x / 16.0f, y / 16.0f, z / 16.0f));
    }

    /**
     * Called as the game hands the level renderer the camera's rotation ({@code view}) and the
     * projection, bobbing included, for this frame's world: the player's submit, which comes
     * after, sees the world through them. From 1.21.6 the projection lives on the GPU only.
     */
    public static void levelMatrices(Matrix4f view, Matrix4f projection) {
        VIEW.set(view);
        PROJECTION.set(projection);
    }

    /**
     * Called as the game starts submitting a living entity, the submit named by its render state
     * {@code who}, with the pose stack at the entity's feet: keeps where the feet are seen from,
     * for the local player while the view poses them. The later hooks of the submit pass the same
     * {@code who}. Other mods' render hooks cover no piece from 1.21.2 (the render state already
     * holds what is drawn), so nothing is kept from before them.
     */
    public static void beginDraw(boolean posing, Object who, PoseStack poseStack) {
        drawing = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (failed || !posing || minecraft.player == null || !(who instanceof AvatarRenderState state)
                || state.id != minecraft.player.getId()) {
            return;
        }
        drawing = who;
        // Where the game drew the feet this frame, between the ticks as the state was extracted
        feetX = state.x;
        feetY = state.y;
        feetZ = state.z;
        FEET_TO_CAMERA.set(VIEW).mul(poseStack.last().pose());
        CAMERA_TO_FEET.set(FEET_TO_CAMERA).invert();
        float width = minecraft.getWindow().getGuiScaledWidth();
        float height = minecraft.getWindow().getGuiScaledHeight();
        // Clip space to GUI coordinates: x from -1..1 to 0..width, y from 1..-1 to 0..height
        CAMERA_TO_GUI.set(new Matrix4f(
                width / 2.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -height / 2.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                width / 2.0f, height / 2.0f, 0.0f, 1.0f).mul(PROJECTION));
    }

    /**
     * Called once the game has posed the player's model inside the submit {@link #beginDraw}
     * started, before the layers, with the pose stack in the model's own space: from here on the
     * menu's outlines are traced on it.
     */
    public static void modelPosed(int session, Object who, Object renderer, Object model, PoseStack poseStack) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            boolean slim = PreviewEquipment.isSlimRenderer(minecraft, renderer);
            PlayerModel copy = copyOf(minecraft, slim);
            copyPose(humanoid, copy);
            placedCopy = copy;
            MODEL_TO_CAMERA.set(VIEW).mul(poseStack.last().pose());
            PreviewEquipment.rememberWorld(copy, MODEL_TO_CAMERA, CAMERA_TO_GUI, slim, 0);
            placedSession = session;
            placedNanos = System.nanoTime();
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /**
     * Called once the game has submitted the player's layers, still in the model's own space:
     * submits the ghost of the piece the menu shows see-through ({@code PreviewEquipment.setGhost})
     * over them, as the skin menu's own model has it.
     */
    public static void layersDrawn(Object who, Object renderer, Object model, PoseStack poseStack,
                                   SubmitNodeCollector collector) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            // On a stack of its own, so a failure cannot leave the game's unbalanced; a ghost of the
            // wings tilts over the sit's block as the world's wings do (WorldPose.clearSitBlockWithWings)
            PreviewPlayerData posed = WorldPose.posedPreview(who);
            PreviewEquipment.submitGhost(new PreviewEquipment.Worn(renderer, who, false,
                            posed == null ? null : PlayerModelRenderer.previewSit(posed)), humanoid,
                    own(poseStack), collector);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /** A pose stack of its own that starts where {@code poseStack} is. */
    private static PoseStack own(PoseStack poseStack) {
        PoseStack own = new PoseStack();
        own.last().pose().set(poseStack.last().pose());
        own.last().normal().set(poseStack.last().normal());
        return own;
    }

    /**
     * Called as the player's submit ends, back at their feet: submits the items the menu lays
     * round them ({@link #show}) in the player's light, and ends the draw.
     */
    public static void drawDrops(int session, Object who, PoseStack poseStack, SubmitNodeCollector collector,
                                 int light) {
        if (failed || who != drawing) {
            return;
        }
        drawing = null;
        List<Drop> laid = drops;
        if (laid.isEmpty() || dropsSession != session || System.nanoTime() - dropsNanos > FRESH_NANOS) {
            return;
        }
        try {
            drawDrops(laid, own(poseStack), collector, light);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    private static void drawDrops(List<Drop> laid, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        Minecraft minecraft = Minecraft.getInstance();
        int dark = LightTexture.pack(LightTexture.block(light) / 2, LightTexture.sky(light) / 2);
        for (Drop drop : laid) {
            if (drop.stack().isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(drop.x(), drop.y(), drop.z());
            poseStack.mulPose(Axis.YP.rotationDegrees(drop.yaw()));
            poseStack.pushPose();
            poseStack.scale(drop.size(), drop.size(), drop.size());
            // As the skin menu draws them, the way the GUI shows an item; the collector keeps the
            // state's quads until it draws them, so every item has a state of its own
            submitItem(minecraft, drop.stack(), poseStack, collector, drop.dark() ? dark : light);
            poseStack.popPose();
            if (!drop.badge().isEmpty()) {
                poseStack.translate(drop.size() * 0.45f, -drop.size() * 0.4f, drop.size() * 0.1f);
                poseStack.scale(drop.badgeSize(), drop.badgeSize(), drop.badgeSize());
                submitItem(minecraft, drop.badge(), poseStack, collector, light);
            }
            poseStack.popPose();
        }
    }

    private static void submitItem(Minecraft minecraft, ItemStack stack, PoseStack poseStack,
                                   SubmitNodeCollector collector, int light) {
        ItemStackRenderState item = new ItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(item, stack, ItemDisplayContext.GUI, minecraft.level, null, 0);
        item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
    }

    /** The copy of the player's model of a model type, made once. */
    private static PlayerModel copyOf(Minecraft minecraft, boolean slim) {
        if (slim) {
            if (slimCopy == null) {
                slimCopy = new PlayerModel(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            }
            return slimCopy;
        }
        if (classicCopy == null) {
            classicCopy = new PlayerModel(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return classicCopy;
    }

    /**
     * Gives the copy the model's pose, for the outlines traced after the world's draw: the game
     * poses the same model again for every player it draws, and once more for this one when it
     * draws what was submitted. Crouching and the arm poses are in the parts' poses from 1.21.2.
     */
    private static void copyPose(HumanoidModel<?> model, PlayerModel copy) {
        copyPart(model.root(), copy.root());
        ModelPart[] from = {model.head, model.hat, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
        ModelPart[] to = {copy.head, copy.hat, copy.body, copy.rightArm, copy.leftArm, copy.rightLeg, copy.leftLeg};
        for (int i = 0; i < from.length; i++) {
            copyPart(from[i], to[i]);
        }
        if (model instanceof PlayerModel player) {
            ModelPart[] layersFrom = {player.jacket, player.rightSleeve, player.leftSleeve, player.rightPants, player.leftPants};
            ModelPart[] layersTo = {copy.jacket, copy.rightSleeve, copy.leftSleeve, copy.rightPants, copy.leftPants};
            for (int i = 0; i < layersFrom.length; i++) {
                copyPart(layersFrom[i], layersTo[i]);
            }
        }
    }

    private static void copyPart(ModelPart from, ModelPart to) {
        to.loadPose(from.storePose());
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
        to.visible = from.visible;
    }
//?} else {
    /** A point of a model part, in its own pixels, in the camera's space. */
    private static Vector3f onPart(ModelPart part, float x, float y, float z) {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(MODEL_TO_CAMERA);
        part.translateAndRotate(poseStack);
        return poseStack.last().pose().transformPosition(new Vector3f(x / 16.0f, y / 16.0f, z / 16.0f));
    }

    /**
     * Called as the game hands the GPU the projection this frame's world is drawn with, the
     * walk's bobbing, the hurt tilt and the screen's wobble included: from 26.1 the camera's state
     * the entities are submitted with holds only the bare projection.
     */
    public static void levelProjection(Matrix4f projection) {
        PROJECTION.set(projection);
        projectionTold = true;
    }

    /**
     * Called as the game starts submitting a living entity, the submit named by its render state
     * {@code who}, with the pose stack at the entity's feet: keeps where the feet are seen from,
     * for the local player while the view poses them. The pose stack goes from the camera to the
     * feet only; the camera's rotation is the {@code camera} state's. The later hooks of the
     * submit pass the same {@code who}. Other mods' render hooks cover no piece from 1.21.2 (the
     * render state already holds what is drawn), so nothing is kept from before them.
     */
    public static void beginDraw(boolean posing, Object who, PoseStack poseStack, CameraRenderState camera) {
        drawing = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (failed || !posing || minecraft.player == null || !(who instanceof AvatarRenderState state)
                || state.id != minecraft.player.getId()) {
            return;
        }
        drawing = who;
        // Where the game drew the feet this frame, between the ticks as the state was extracted
        feetX = state.x;
        feetY = state.y;
        feetZ = state.z;
        VIEW.set(camera.viewRotationMatrix);
        FEET_TO_CAMERA.set(VIEW).mul(poseStack.last().pose());
        CAMERA_TO_FEET.set(FEET_TO_CAMERA).invert();
        float width = minecraft.getWindow().getGuiScaledWidth();
        float height = minecraft.getWindow().getGuiScaledHeight();
        // Clip space to GUI coordinates: x from -1..1 to 0..width, y from 1..-1 to 0..height; the
        // depth is left alone (reversed from 26.2), the outlines keep the camera's own
        CAMERA_TO_GUI.set(new Matrix4f(
                width / 2.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -height / 2.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                width / 2.0f, height / 2.0f, 0.0f, 1.0f).mul(projectionTold ? PROJECTION : camera.projectionMatrix));
    }

    /**
     * Called once the game has posed the player's model inside the submit {@link #beginDraw}
     * started, before the layers, with the pose stack in the model's own space: from here on the
     * menu's outlines are traced on it.
     */
    public static void modelPosed(int session, Object who, Object renderer, Object model, PoseStack poseStack) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            boolean slim = PreviewEquipment.isSlimRenderer(minecraft, renderer);
            PlayerModel copy = copyOf(minecraft, slim);
            copyPose(humanoid, copy);
            placedCopy = copy;
            MODEL_TO_CAMERA.set(VIEW).mul(poseStack.last().pose());
            PreviewEquipment.rememberWorld(copy, MODEL_TO_CAMERA, CAMERA_TO_GUI, slim, 0);
            placedSession = session;
            placedNanos = System.nanoTime();
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /**
     * Called once the game has submitted the player's layers, still in the model's own space:
     * submits the ghost of the piece the menu shows see-through ({@code PreviewEquipment.setGhost})
     * over them, as the skin menu's own model has it.
     */
    public static void layersDrawn(Object who, Object renderer, Object model, PoseStack poseStack,
                                   SubmitNodeCollector collector) {
        if (failed || who != drawing || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        try {
            // On a stack of its own, so a failure cannot leave the game's unbalanced; a ghost of the
            // wings tilts over the sit's block as the world's wings do (WorldPose.clearSitBlockWithWings)
            PreviewPlayerData posed = WorldPose.posedPreview(who);
            PreviewEquipment.submitGhost(new PreviewEquipment.Worn(renderer, who, false,
                            posed == null ? null : PlayerModelRenderer.previewSit(posed)), humanoid,
                    own(poseStack), collector);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /** A pose stack of its own that starts where {@code poseStack} is. */
    private static PoseStack own(PoseStack poseStack) {
        PoseStack own = new PoseStack();
        own.last().pose().set(poseStack.last().pose());
        own.last().normal().set(poseStack.last().normal());
        return own;
    }

    /**
     * Called as the player's submit ends, back at their feet: submits the items the menu lays
     * round them ({@link #show}) in the player's light, and ends the draw.
     */
    public static void drawDrops(int session, Object who, PoseStack poseStack, SubmitNodeCollector collector,
                                 int light) {
        if (failed || who != drawing) {
            return;
        }
        drawing = null;
        List<Drop> laid = drops;
        if (laid.isEmpty() || dropsSession != session || System.nanoTime() - dropsNanos > FRESH_NANOS) {
            return;
        }
        try {
            drawDrops(laid, own(poseStack), collector, light);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    private static void drawDrops(List<Drop> laid, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        Minecraft minecraft = Minecraft.getInstance();
        int dark = LightCoordsUtil.pack(LightCoordsUtil.block(light) / 2, LightCoordsUtil.sky(light) / 2);
        for (Drop drop : laid) {
            if (drop.stack().isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(drop.x(), drop.y(), drop.z());
    //? if <26.3 {
            poseStack.mulPose(Axis.YP.rotationDegrees(drop.yaw()));
    //?} else {
            poseStack.rotate(Axis.YP.rotationDegrees(drop.yaw()));
    //?}
            poseStack.pushPose();
            poseStack.scale(drop.size(), drop.size(), drop.size());
            // As the skin menu draws them, the way the GUI shows an item; the collector keeps the
            // state's quads until it draws them, so every item has a state of its own
            submitItem(minecraft, drop.stack(), poseStack, collector, drop.dark() ? dark : light);
            poseStack.popPose();
            if (!drop.badge().isEmpty()) {
                poseStack.translate(drop.size() * 0.45f, -drop.size() * 0.4f, drop.size() * 0.1f);
                poseStack.scale(drop.badgeSize(), drop.badgeSize(), drop.badgeSize());
                submitItem(minecraft, drop.badge(), poseStack, collector, light);
            }
            poseStack.popPose();
        }
    }

    private static void submitItem(Minecraft minecraft, ItemStack stack, PoseStack poseStack,
                                   SubmitNodeCollector collector, int light) {
        ItemStackRenderState item = new ItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(item, stack, ItemDisplayContext.GUI, minecraft.level, null, 0);
        item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
    }

    /** The copy of the player's model of a model type, made once. */
    private static PlayerModel copyOf(Minecraft minecraft, boolean slim) {
        if (slim) {
            if (slimCopy == null) {
                slimCopy = new PlayerModel(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            }
            return slimCopy;
        }
        if (classicCopy == null) {
            classicCopy = new PlayerModel(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return classicCopy;
    }

    /**
     * Gives the copy the model's pose, for the outlines traced after the world's draw: the game
     * poses the same model again for every player it draws, and once more for this one when it
     * draws what was submitted. Crouching and the arm poses are in the parts' poses from 1.21.2.
     */
    private static void copyPose(HumanoidModel<?> model, PlayerModel copy) {
        copyPart(model.root(), copy.root());
        ModelPart[] from = {model.head, model.hat, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
        ModelPart[] to = {copy.head, copy.hat, copy.body, copy.rightArm, copy.leftArm, copy.rightLeg, copy.leftLeg};
        for (int i = 0; i < from.length; i++) {
            copyPart(from[i], to[i]);
        }
        if (model instanceof PlayerModel player) {
            ModelPart[] layersFrom = {player.jacket, player.rightSleeve, player.leftSleeve, player.rightPants, player.leftPants};
            ModelPart[] layersTo = {copy.jacket, copy.rightSleeve, copy.leftSleeve, copy.rightPants, copy.leftPants};
            for (int i = 0; i < layersFrom.length; i++) {
                copyPart(layersFrom[i], layersTo[i]);
            }
        }
    }

    private static void copyPart(ModelPart from, ModelPart to) {
        to.loadPose(from.storePose());
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
        to.visible = from.visible;
    }
//?}
}
