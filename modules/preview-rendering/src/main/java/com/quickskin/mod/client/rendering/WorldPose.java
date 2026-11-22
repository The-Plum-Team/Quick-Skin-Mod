package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.common.data.RemoteImmersivePoses;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
// The body chain's imports (beforeModel, pose, hangFromBody), one block per version family
//? if <1.20.2 {
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
//?} else if <1.21.2 {
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
//?} else if <1.21.6 {
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.Entity;
//?} else if <1.21.9 {
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.Entity;
//?} else if <26.1 {
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
//? if <1.21.11 {
import net.minecraft.client.model.PlayerCapeModel;
import net.minecraft.client.model.PlayerModel;
//?} else {
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
//?}
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
//?} else {
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
//?}
// The sit-clearance chain's imports (clearSitBlock, clearSitBlockWithWings); a type both chains
// import on one version is imported twice, which Java allows
//? if <1.20.2 {
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
//?} else if <1.21.2 {
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
//?} else if <1.21.6 {
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.joml.Matrix3f;
import org.joml.Vector3f;
//?} else if <1.21.9 {
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import org.joml.Matrix3f;
import org.joml.Vector3f;
//?} else if <26.1 {
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.joml.Vector3f;
//?} else {
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.joml.Matrix3f;
import org.joml.Vector3f;
//?}
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The skin menu's poses, played by the player in the world while the menu floats round them (the
 * immersive view): the idle, the walk in place, the sit on its block with the jumps onto it and
 * off it, and the move the model makes when another skin is picked.
 *
 * <p>The menu's own model is not drawn there, so the player's draw in the world takes its place:
 * right before the game poses the player's model, the draw is bound to the player and the pose they
 * play ({@link #bind}), the whole body is turned to face the camera and given the move's lift and
 * scale, and the block is drawn under it ({@code beforeModel}); once the game has posed the model,
 * the menu's pose replaces the game's ({@code pose}), and the cape and the elytra hang from the
 * body it moved ({@code hangFromBody}), tilted back over the block's edge while the player sits
 * ({@code clearSitBlock}, {@code clearSitBlockWithWings}). Nothing of the player changes: only the
 * drawing of them, and only in the world. A player who is sneaking, swimming, gliding, riding or
 * asleep keeps the game's own pose, and so does a CPM model, which animates itself.
 *
 * <p>Other Quick Skin players see the pose too (ADR 0019): the server relays what the posing
 * player's client says it plays ({@link RemoteImmersivePoses}), and this client plays it on that
 * player with an animation state of its own per player, on its own clock: the same pose, the
 * jumps onto the block and off it when the pose changes, the skin-change moves, and the body
 * turned the way the posing player's client turns it, easing round as the menu opens and closes.
 * A player this client cannot see, being invisible, shows neither the pose nor the block.
 *
 * <p>A draw is named by what the game's renderer is handed ({@code who}): the entity before
 * 1.21.2, its render state from then. {@link #bind} ties that to the player and their pose for the
 * draw going on, and every later hook of the draw finds it again by the same {@code who}: while the
 * draw's own call runs ({@link #drawing}), or, from 1.21.9 where the game poses the model it
 * collected again after that call returned, among the draws kept for that ({@link #keep},
 * {@link #kept}, {@link #bound}; from 26.1 also the posing draws of the whole world pass,
 * {@code passBound}). The posing itself ({@link #transform}, {@link #startPose}, {@link #posed})
 * is shared by every version and handed the binding the entry point found; each version family
 * only adds its entry points, in their own guarded blocks below: the body's, and the sit clearance
 * of what hangs from it. Every version family has them.
 */
public final class WorldPose {
    /** No immersive view of this client poses the local player in the draw going on. */
    public static final int NO_SESSION = Integer.MIN_VALUE;
    /** The feet below the model's root, in the model's own units. */
    private static final float FEET = 1.501f;
    /** How long another player's body takes to turn towards their camera, and back. */
    private static final float REMOTE_OPEN_SECONDS = 0.65f;
    private static final float REMOTE_CLOSE_SECONDS = 0.4f;
    /** How fast another player's body eases to a new way to face, as ImmersiveMode eases the poser's own. */
    private static final float REMOTE_TURN_RATE = 6.0f;
    /** Other players' animation states kept beyond those posing now, before the rest are dropped. */
    private static final int SPARE_REMOTE_STATES = 64;
    /** Draws kept ({@link #keep}) for hooks that run after the draw's own call returned. */
    private static final int BOUND_DRAWS = 16;

    @Nullable
    private static PreviewPlayerData playing;
    private static int playingSession = -1;

    /**
     * One draw of a player who plays a pose: what names the draw ({@code who}), the player, the
     * pose, and the turn it is drawn with. The body the pose left is kept once the model is posed,
     * for what hangs from it in hooks that run later ({@link #poseCape}).
     */
    public static final class Binding {
        private final Object who;
        private final LivingEntity player;
        private final PreviewPlayerData preview;
        /** The clock the pose plays by in this draw. */
        private final long nanos;
        /** The way the body turns towards, and how far it has turned (0 to 1). */
        private final float faceYaw;
        private final float facing;
        @Nullable
        private PosedBody body;

        private Binding(Object who, LivingEntity player, PreviewPlayerData preview, long nanos, float faceYaw,
                        float facing) {
            this.who = who;
            this.player = player;
            this.preview = preview;
            this.nanos = nanos;
            this.faceYaw = faceYaw;
            this.facing = facing;
        }

        public Object who() {
            return who;
        }

        public LivingEntity player() {
            return player;
        }

        public PreviewPlayerData preview() {
            return preview;
        }

        /** The body as the pose left it, or null before the model is posed. */
        @Nullable
        public PosedBody body() {
            return body;
        }
    }

    /** A model part's place, angles and scale, as the menu's pose left the body. */
    public record PosedBody(float x, float y, float z, float xRot, float yRot, float zRot,
                            float xScale, float yScale, float zScale) {
        static PosedBody of(ModelPart part) {
            return new PosedBody(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                    part.xScale, part.yScale, part.zScale);
        }

        /** Gives {@code part} this pose, as {@code ModelPart.copyFrom} would. */
        public void applyTo(ModelPart part) {
            part.x = x;
            part.y = y;
            part.z = z;
            part.xRot = xRot;
            part.yRot = yRot;
            part.zRot = zRot;
            part.xScale = xScale;
            part.yScale = yScale;
            part.zScale = zScale;
        }
    }

    /** The draw going on, or null when it plays no pose. */
    @Nullable
    private static Binding current;
    /** The latest draws kept for later hooks ({@link #keep}), as a ring. */
    private static final Binding[] BOUND = new Binding[BOUND_DRAWS];
    private static int nextBound;

    /** Another player's pose as this client plays it. */
    private static final class Remote {
        final PreviewPlayerData preview = new PreviewPlayerData();
        /** The run of poses this state plays, as {@code RemoteImmersivePoses.Pose.run()} numbers it. */
        long run;
        Object world;
        int pose;
        /** The way the body turns to, the way it faced when it last started to turn there, and when. */
        float yaw;
        float yawFrom;
        long yawFromNanos;
        int swaps;
        boolean closing;
        /** How far the body was turned when it last started to turn, and when. */
        float facingFrom;
        long facingFromNanos;

        Remote() {
            preview.setShowcaseIdle(true);
            preview.setOwnPose(true);
        }

        /** The way the body faces now, easing to the told way as the posing player's own client does. */
        float yaw(long nowNanos) {
            float seconds = Math.max(0.0f, (nowNanos - yawFromNanos) / 1.0e9f);
            float turn = ((yaw - yawFrom) % 360.0f + 540.0f) % 360.0f - 180.0f;
            return yawFrom + turn * (1.0f - (float) Math.exp(-seconds * REMOTE_TURN_RATE));
        }

        float facing(long nowNanos) {
            float seconds = (nowNanos - facingFromNanos) / 1.0e9f;
            float target = closing ? 0.0f : 1.0f;
            float duration = closing ? REMOTE_CLOSE_SECONDS : REMOTE_OPEN_SECONDS;
            float t = Math.min(1.0f, Math.max(0.0f, seconds / duration));
            float eased = t * t * (3.0f - 2.0f * t);
            return facingFrom + (target - facingFrom) * eased;
        }
    }

    private static final Map<UUID, Remote> REMOTE = new HashMap<>();

    private WorldPose() {
    }

    /**
     * Names the preview whose pose the player plays during the immersive view {@code session}.
     * The menu says so every frame it floats; the pose stays through the dialogs it opens.
     */
    public static void play(int session, PreviewPlayerData preview) {
        playing = preview;
        playingSession = session;
    }

    /**
     * The preview whose pose the local player plays during the immersive view {@code session},
     * or null when it plays none, which this client tells the server for other players to see.
     */
    @Nullable
    public static PreviewPlayerData playingPreview(int session) {
        PreviewPlayerData preview = playing;
        return preview != null && session == playingSession && session != NO_SESSION && preview.isOwnPose()
                ? preview : null;
    }

    /** Whether the server says another player plays a pose, so their draws are to be looked at. */
    public static boolean posesOthers() {
        return !RemoteImmersivePoses.getInstance().isEmpty();
    }

    /** The session ended: other players' poses are forgotten. */
    public static void clearOthers() {
        REMOTE.clear();
        forgetBound();
    }

    /**
     * Binds the draw named {@code who} of {@code entity} to the pose that player plays, if any, and
     * makes it the draw going on: for the local player during the immersive view {@code session},
     * the menu's pose, turned towards {@code faceYaw} as far as {@code facing} says (0 to 1); for
     * another player the server says plays a pose, that player's own pose and turn
     * ({@code session} is then {@link #NO_SESSION} unless this client has a view of its own).
     * Answers null, and leaves no draw going on, when the draw plays no pose. The binding is the
     * draw going on until {@link #drawn}; a family whose model is posed after that hands it to
     * {@link #keep} as well.
     */
    @Nullable
    public static Binding bind(int session, Object who, LivingEntity entity, float faceYaw, float facing) {
        current = null;
        forgetBound(who);
        Minecraft minecraft = Minecraft.getInstance();
        Binding binding;
        if (entity == minecraft.player) {
            PreviewPlayerData preview = playing;
            if (preview == null || session == NO_SESSION || session != playingSession || !preview.isOwnPose()
                    || !standsForAPose(entity)) {
                return null;
            }
            binding = new Binding(who, entity, preview, System.nanoTime(), faceYaw, facing);
        } else if (entity instanceof Player) {
            // Cheapest first: only a player the server says plays a pose is looked at further
            UUID playerId = entity.getUUID();
            RemoteImmersivePoses.Pose told = RemoteImmersivePoses.getInstance().pose(playerId, entity.level());
            if (told == null) {
                REMOTE.remove(playerId);
                return null;
            }
            // A player this client cannot see shows no pose and no block where they stand
            if (minecraft.player == null || entity.isInvisibleTo(minecraft.player) || !standsForAPose(entity)
                    || CPMCompatIntegration.isWearingCpmModel(playerId)) {
                return null;
            }
            long now = System.nanoTime();
            Remote remote = remote(playerId, told, entity, now);
            binding = new Binding(who, entity, remote.preview, now, remote.yaw(now), remote.facing(now));
        } else {
            return null;
        }
        current = binding;
        return binding;
    }

    /**
     * Keeps {@code binding} after its draw ended, for hooks that run once the draw's own call has
     * returned and find it by {@link #kept} (from 1.21.9 the game collects the model in
     * {@code submit} and poses and draws it later). Only for a family whose renderer hands each
     * draw a render state of its own: 1.21.2 to 1.21.8 hand one state to every draw of an entity
     * type, which a later draw that is not bound (the inventory's paper doll, a screen preview)
     * would then find, so those families never keep a binding and look up only the draw going on.
     * The 16 latest kept draws are found; a draw kept again replaces its earlier entry.
     */
    public static void keep(@Nullable Binding binding) {
        if (binding == null) {
            return;
        }
        forgetBound(binding.who);
        BOUND[nextBound] = binding;
        nextBound = (nextBound + 1) % BOUND_DRAWS;
    }

    /** Whether the game shows the player standing, as a pose needs. */
    private static boolean standsForAPose(LivingEntity entity) {
        return entity.getPose() == Pose.STANDING && !entity.isPassenger() && !entity.isSleeping()
                && !entity.isFallFlying();
    }

    /** The state that plays another player's pose now, brought up to date with what the server said last. */
    private static Remote remote(UUID playerId, RemoteImmersivePoses.Pose told, LivingEntity entity, long now) {
        Remote remote = REMOTE.get(playerId);
        String animation = RemoteImmersivePoses.animationOf(told.pose());
        boolean closing = (told.flags() & RemoteImmersivePoses.CLOSING) != 0;
        // A change plays from when it was told, so a player first drawn later is not seen catching up
        long at = told.toldNanos() != 0L && told.toldNanos() - now < 0 ? told.toldNanos() : now;
        if (remote == null || remote.run != told.run() || remote.world != told.world()) {
            if (remote == null && REMOTE.size() >= SPARE_REMOTE_STATES) {
                REMOTE.keySet().removeIf(id -> RemoteImmersivePoses.getInstance().pose(id, entity.level()) == null);
            }
            remote = new Remote();
            remote.run = told.run();
            remote.world = told.world();
            remote.pose = told.pose();
            remote.yaw = told.yaw();
            remote.yawFrom = told.yaw();
            remote.yawFromNanos = at;
            remote.swaps = told.swaps();
            // A pose under way when this client heard of it starts turned and seated, with no jump
            boolean settled = (told.flags() & RemoteImmersivePoses.SETTLED) != 0;
            // One heard from its start turns from when it started, however late the player is first drawn
            long started = told.runStartNanos() != 0L && told.runStartNanos() - at <= 0 ? told.runStartNanos() : at;
            remote.facingFrom = settled ? 1.0f : 0.0f;
            remote.facingFromNanos = settled ? at : started;
            if (closing) {
                // and one already closing turns back from where it was when the close was told
                remote.facingFrom = remote.facing(at);
                remote.facingFromNanos = at;
            }
            remote.closing = closing;
            remote.preview.setCurrentAnimation(animation);
            REMOTE.put(playerId, remote);
            return remote;
        }
        if (told.yaw() != remote.yaw) {
            remote.yawFrom = remote.yaw(at);
            remote.yawFromNanos = at;
            remote.yaw = told.yaw();
        }
        if (told.pose() != remote.pose) {
            remote.pose = told.pose();
            remote.preview.pickAnimation(animation, at);
        }
        if (told.swaps() != remote.swaps) {
            remote.swaps = told.swaps();
            SkinSwapAnimation.Variant[] variants = SkinSwapAnimation.Variant.values();
            if (told.swapVariant() < variants.length) {
                remote.preview.playSkinSwap(variants[told.swapVariant()], at);
            }
        }
        if (closing != remote.closing) {
            remote.facingFrom = remote.facing(at);
            remote.facingFromNanos = at;
            remote.closing = closing;
        }
        return remote;
    }

    /**
     * Poses the bound draw in the model's own space, right before the game poses the model: turns
     * the body from {@code bodyYaw} (the way the game drew it this frame) towards the binding's
     * way, lays the skin-change move's turn, lift and scale over it, has {@code drawBlock} draw the
     * block the sit uses (handed the jump going on, or null), and lifts the body for a jump onto
     * it or off it. Every version's entry point comes here, so the posing is never written twice.
     */
    private static void transform(Binding binding, float bodyYaw, PoseStack poseStack,
                                  Consumer<SitJump.Pose> drawBlock) {
        PreviewPlayerData preview = binding.preview;
        SkinSwapAnimation.Pose swap = preview.advanceSkinSwap(binding.nanos);
        preview.advancePoseMoves(binding.nanos);

        // The body faces the camera's resting place, whatever way it stood, and spins with the move
        float turn = Mth.wrapDegrees(binding.faceYaw - bodyYaw) * Mth.clamp(binding.facing, 0.0f, 1.0f)
                + swap.get(SkinSwapAnimation.Whole.TURN);
        rotate(poseStack, Axis.YP.rotationDegrees(turn));

        // The move's lift and scale, about the feet
        float swapScale = swap.get(SkinSwapAnimation.Whole.SCALE);
        poseStack.translate(0.0f, -swap.get(SkinSwapAnimation.Whole.LIFT), 0.0f);
        poseStack.translate(0.0f, FEET, 0.0f);
        poseStack.scale(swapScale, swapScale, swapScale);
        poseStack.translate(0.0f, -FEET, 0.0f);

        SitJump.Pose jump = preview.getSitJumpPose();
        boolean sitting = "sit".equalsIgnoreCase(preview.getCurrentAnimation());
        if (jump != null ? jump.blockShown() : sitting) {
            drawBlock.accept(jump);
        }
        if (jump != null) {
            // The jump lifts the body and squashes it about its feet, while the block stays on the floor
            poseStack.translate(0.0f, -jump.lift(), 0.0f);
            poseStack.translate(0.0f, FEET, 0.0f);
            poseStack.scale(jump.scaleX(), jump.scaleY(), jump.scaleX());
            poseStack.translate(0.0f, -FEET, 0.0f);
        }
    }

    /**
     * Starts replacing the pose the game just gave a player's model with the menu's, for the draw
     * {@code binding} the entry point found (null when the draw plays no pose): puts the six parts
     * back in the stance the model was baked with, which every pose of the menu starts from, and
     * answers the pose to play; null without a binding. The entry point then poses the model with
     * it and calls {@link #posed} with the same binding. The entry point looks the binding up the
     * way its family needs: {@link #drawing} while the draw's own call runs, {@link #kept} (or
     * {@link #bound}) where the model is posed after it.
     */
    @Nullable
    private static PreviewPlayerData startPose(@Nullable Binding binding, ModelPart head, ModelPart body,
                                               ModelPart rightArm, ModelPart leftArm, ModelPart rightLeg,
                                               ModelPart leftLeg) {
        if (binding == null) {
            return null;
        }
        PreviewPlayerData preview = binding.preview;
        head.resetPose();
        body.resetPose();
        rightArm.resetPose();
        leftArm.resetPose();
        rightLeg.resetPose();
        leftLeg.resetPose();
        return preview;
    }

    /** Keeps the body the menu's pose left in the draw {@code binding}, for what hangs from it later. */
    private static void posed(Binding binding, ModelPart body) {
        binding.body = PosedBody.of(body);
    }

    /**
     * Gives {@code capeBody} the body the menu's pose left in the draw {@code binding} (null when
     * the draw plays no pose), so a cape that hangs from a model of its own hangs from the posed
     * body (from 1.21.2 the cape layer's {@code PlayerCapeModel}, whose own posing puts its body
     * back where a body standing straight would be). The hook looks the binding up as the
     * player's model hook does: {@link #drawing} from 1.21.2 to 1.21.8, where the cape model is
     * posed inside the player's draw; {@link #bound} on 1.21.9 to 1.21.11 and {@code passBound}
     * from 26.1, where it is posed again later. Answers whether the body was posed.
     */
    public static boolean poseCape(@Nullable Binding binding, ModelPart capeBody) {
        if (binding == null || binding.body == null) {
            return false;
        }
        binding.body.applyTo(capeBody);
        return true;
    }

//? if <1.20.2 {
    /**
     * Prepares the player's draw in the world, in the model's own space, right before the game
     * poses the model ({@link #bind}, then {@link #transform}), with the block drawn in the
     * player's {@code light}.
     */
    public static void beforeModel(int session, LivingEntity entity, float partialTick, PoseStack poseStack,
                                   MultiBufferSource buffers, int light, float faceYaw, float facing) {
        Binding binding = bind(session, entity, entity, faceYaw, facing);
        if (binding != null) {
            transform(binding, Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot), poseStack,
                    jump -> PlayerModelRenderer.renderGrassBlock(poseStack, buffers, jump, light));
        }
    }

    /**
     * Replaces the pose the game just gave the player's model with the menu's, for the draw
     * prepared: the game poses it inside the draw's own call, so the draw going on is the one.
     */
    public static void pose(Object who, PlayerModel<?> model) {
        Binding binding = drawing(who);
        PreviewPlayerData preview = startPose(binding, model.head, model.body, model.rightArm, model.leftArm,
                model.rightLeg, model.leftLeg);
        if (preview != null) {
            PlayerModelRenderer.setupModelPoseWithAnimation(model, preview, 0, 0, false, 0, 0, Minecraft.getInstance());
            posed(binding, model.body);
        }
    }

    /**
     * Hangs what the game draws behind the player in the model's own space, the cape and the
     * elytra, from the body the menu's pose moved, for the draw prepared. The game hangs them where
     * a body standing straight would be, which the menu's poses lean, lower and turn.
     */
    public static void hangFromBody(Object who, EntityModel<?> model, PoseStack poseStack) {
        if (posing(who) && model instanceof HumanoidModel<?> humanoid) {
            humanoid.body.translateAndRotate(poseStack);
        }
    }
//?} else if <1.21.2 {
    /**
     * Prepares the player's draw in the world, in the model's own space, right before the game
     * poses the model ({@link #bind}, then {@link #transform}), with the block drawn in the
     * player's {@code light}.
     */
    public static void beforeModel(int session, LivingEntity entity, float partialTick, PoseStack poseStack,
                                   MultiBufferSource buffers, int light, float faceYaw, float facing) {
        Binding binding = bind(session, entity, entity, faceYaw, facing);
        if (binding != null) {
            transform(binding, Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot), poseStack,
                    jump -> PlayerModelRenderer.renderGrassBlock(poseStack, buffers, jump, light));
        }
    }

    /**
     * Replaces the pose the game just gave the player's model with the menu's, for the draw
     * prepared: the game poses it inside the draw's own call, so the draw going on is the one.
     */
    public static void pose(Object who, PlayerModel<?> model) {
        Binding binding = drawing(who);
        PreviewPlayerData preview = startPose(binding, model.head, model.body, model.rightArm, model.leftArm,
                model.rightLeg, model.leftLeg);
        if (preview != null) {
            PlayerModelRenderer.setupModelPoseWithAnimation(model, preview, 0, 0, false, 0, 0, Minecraft.getInstance());
            posed(binding, model.body);
        }
    }

    /**
     * Hangs what the game draws behind the player in the model's own space, the cape and the
     * elytra, from the body the menu's pose moved, for the draw prepared. The game hangs them where
     * a body standing straight would be, which the menu's poses lean, lower and turn.
     */
    public static void hangFromBody(Object who, EntityModel<?> model, PoseStack poseStack) {
        if (posing(who) && model instanceof HumanoidModel<?> humanoid) {
            humanoid.body.translateAndRotate(poseStack);
        }
    }
//?} else if <1.21.6 {
    /**
     * Prepares the player's draw in the world, in the model's own space, right before the game
     * poses the model ({@link #bind}, then {@link #transform}), with the block drawn in the
     * player's {@code light}. From 1.21.2 the renderer is handed a render state the game extracted
     * from the player just before: the state names the draw, and the player is found by its id.
     * These versions extract a player's state and draw it at once, so every later hook of the draw
     * finds it as the draw going on ({@link #drawing}); they hand one state to every draw of a
     * player, so a draw is never kept ({@link #keep}).
     */
    public static void beforeModel(int session, LivingEntityRenderState state, PoseStack poseStack,
                                   MultiBufferSource buffers, int light, float faceYaw, float facing) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(state instanceof PlayerRenderState player) || minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(player.id);
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        Binding binding = bind(session, state, living, faceYaw, facing);
        if (binding != null) {
            // The state's body yaw is already the frame's
            transform(binding, state.bodyRot, poseStack,
                    jump -> PlayerModelRenderer.renderGrassBlock(poseStack, buffers, jump, light));
        }
    }

    /**
     * Replaces the pose the game just gave the player's model with the menu's, for the draw
     * prepared: the game poses it inside the draw's own call, so the draw going on is the one.
     */
    public static void pose(Object who, PlayerModel model) {
        Binding binding = drawing(who);
        PreviewPlayerData preview = startPose(binding, model.head, model.body, model.rightArm, model.leftArm,
                model.rightLeg, model.leftLeg);
        if (preview != null) {
            PlayerModelRenderer.setupModelPoseWithAnimation(model, preview, 0, 0, false, 0, 0, Minecraft.getInstance());
            posed(binding, model.body);
        }
    }

    /**
     * Hangs the elytra, which the game draws in the model's own space, from the body the menu's
     * pose moved, for the draw prepared. The game hangs them where a body standing straight would
     * be, which the menu's poses lean, lower and turn. (The cape hangs from a model of its own from
     * 1.21.2: {@link #poseCape}.)
     */
    public static void hangFromBody(Object who, EntityModel<?> model, PoseStack poseStack) {
        if (posing(who) && model instanceof HumanoidModel<?> humanoid) {
            humanoid.body.translateAndRotate(poseStack);
        }
    }
//?} else if <1.21.9 {
    /**
     * Prepares the player's draw in the world, in the model's own space, right before the game
     * poses the model ({@link #bind}, then {@link #transform}), with the block drawn in the
     * player's {@code light}. The renderer is handed the render state {@code state}, which the
     * game fills from the player right before and hands every draw of a player in turn, so the
     * player is looked up by the state's id for this draw alone. Everything the draw poses (the
     * player's model, the cape model, the wings) is posed inside the renderer's call, so the
     * binding is only ever the draw going on, never kept ({@link #keep}).
     */
    public static void beforeModel(int session, PlayerRenderState state, PoseStack poseStack,
                                   MultiBufferSource buffers, int light, float faceYaw, float facing) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(state.id);
        if (!(entity instanceof LivingEntity living)) {
            current = null;
            return;
        }
        Binding binding = bind(session, state, living, faceYaw, facing);
        if (binding != null) {
            transform(binding, state.bodyRot, poseStack,
                    jump -> PlayerModelRenderer.renderGrassBlock(poseStack, buffers, jump, light));
        }
    }

    /**
     * Replaces the pose the game just gave the player's model with the menu's, for the draw
     * prepared: the game poses it inside the draw's own call, so the draw going on is the one.
     */
    public static void pose(Object who, PlayerModel model) {
        Binding binding = drawing(who);
        PreviewPlayerData preview = startPose(binding, model.head, model.body, model.rightArm, model.leftArm,
                model.rightLeg, model.leftLeg);
        if (preview != null) {
            PlayerModelRenderer.setupModelPoseWithAnimation(model, preview, 0, 0, false, 0, 0, Minecraft.getInstance());
            posed(binding, model.body);
        }
    }

    /**
     * Hangs the elytra the game draws behind the player, in the model's own space, from the body
     * the menu's pose moved, for the draw prepared. The cape needs none of this: it hangs from a
     * model of its own, posed from the body ({@link #poseCape}).
     */
    public static void hangFromBody(Object who, EntityModel<?> model, PoseStack poseStack) {
        if (posing(who) && model instanceof HumanoidModel<?> humanoid) {
            humanoid.body.translateAndRotate(poseStack);
        }
    }
//?} else if <26.1 {
    /**
     * Prepares a player's draw in the world, in the model's own space, right before the game sees
     * whether the body shows ({@code LivingEntityRenderer.submit}): binds the draw to the player
     * the render state is of ({@link #bind}), keeps it for the posing the game does once
     * {@code submit} returned ({@link #keep}), and poses it ({@link #transform}), with the block
     * submitted in the player's light. Every submission after this point copies the pose stack, so
     * the model and every layer take the transform.
     */
    public static void beforeModel(int session, AvatarRenderState state, PoseStack poseStack,
                                   SubmitNodeCollector collector, float faceYaw, float facing) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = minecraft.player != null && state.id == minecraft.player.getId() ? minecraft.player
                : minecraft.level != null ? minecraft.level.getEntity(state.id) : null;
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        Binding binding = bind(session, state, living, faceYaw, facing);
        keep(binding);
        if (binding != null) {
            int light = state.lightCoords;
            transform(binding, state.bodyRot, poseStack,
                    jump -> PlayerModelRenderer.submitWorldGrassBlock(poseStack, collector, jump, light));
        }
    }

    /**
     * Replaces the pose the game just gave the player's model with the menu's, for the draw bound
     * to {@code who}: the game poses the model inside {@code submit}, before the layers, and again
     * once {@code submit} returned, when it draws what was submitted, so the draw is looked up
     * either way ({@link #bound}). The cape's model, a player model too, is left to
     * {@link #poseCape}, which hangs it from this body. Vanilla's armour models of a player are
     * player models too, posed here from the same state when they are drawn; an armour model
     * another mod adds as a plain humanoid model takes the pose bound to the state
     * ({@code PreviewEquipment.bindPose}, once a draw), as the skin menu's preview binds its own.
     */
    public static void pose(Object who, PlayerModel model) {
        if (model instanceof PlayerCapeModel) {
            return;
        }
        Binding binding = bound(who);
        PreviewPlayerData preview = startPose(binding, model.head, model.body, model.rightArm, model.leftArm,
                model.rightLeg, model.leftLeg);
        if (preview != null) {
            PlayerModelRenderer.setupModelPoseWithAnimation(model, preview, 0, 0, false, 0, 0, Minecraft.getInstance());
            posed(binding, model.body);
            if (drawing(who) != null) {
                // Once a draw, from the posing inside its submit: the armour models are posed from
                // the state only when they are drawn, after it, and the pose is the same then
                PreviewEquipment.bindPose(who, model);
            }
        }
    }

    /**
     * Hangs the elytra the game draws behind the player in the model's own space from the body the
     * menu's pose moved, for the draw going on ({@code WingsLayer.submit} runs inside the player's
     * {@code submit}, after the model was posed).
     */
    public static void hangFromBody(Object who, EntityModel<?> model, PoseStack poseStack) {
        if (posing(who) && model instanceof HumanoidModel<?> humanoid) {
            humanoid.body.translateAndRotate(poseStack);
        }
    }
//?} else {
    /** The most posing draws one world pass keeps ({@link #passBound}); far more than ever stand in view. */
    private static final int PASS_DRAWS_MAX = 1024;
    /**
     * The posing draws of the world pass going on, by the render state that names each. The game
     * collects every entity of the pass before it draws any, so the draws' models are posed only
     * once the whole pass has been collected: with more players posing than the ring of kept draws
     * holds ({@link #keep}), the first ones would have dropped out of it by then. Emptied as each
     * world pass begins and ends ({@link #forgetPassDraws}).
     */
    private static final Map<Object, Binding> PASS_DRAWS = new java.util.IdentityHashMap<>();

    /** A world pass begins or ends ({@code GameRenderer.renderLevel}): its draws are let go. */
    public static void forgetPassDraws() {
        PASS_DRAWS.clear();
    }

    /**
     * The draw {@code who} names, as {@link #bound} finds it, else the posing draw of that render
     * state in the world pass going on ({@link #PASS_DRAWS}), or null.
     */
    @Nullable
    public static Binding passBound(Object who) {
        Binding binding = bound(who);
        return binding != null || who == null ? binding : PASS_DRAWS.get(who);
    }

    /**
     * Prepares the player's draw in the world in the model's own space, from 26.1, where the
     * renderer is handed a render state ({@code LivingEntityRenderer.submit}, before it asks
     * whether the body is visible): the player is the entity the state was extracted from, the
     * draw is bound by the state and kept, since the game poses the model it collected again when
     * it draws it, after this call returned ({@link #keep}, {@link #passBound}); the block goes
     * into the same collection, in the player's light.
     */
    public static void beforeModel(int session, LivingEntityRenderState state, PoseStack poseStack,
                                   SubmitNodeCollector collector, float faceYaw, float facing) {
        if (!(state instanceof AvatarRenderState avatar)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(avatar.id);
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        Binding binding = bind(session, state, living, faceYaw, facing);
        if (binding == null) {
            PASS_DRAWS.remove(state);
        } else {
            keep(binding);
            if (PASS_DRAWS.size() < PASS_DRAWS_MAX) {
                PASS_DRAWS.put(state, binding);
            }
            transform(binding, state.bodyRot, poseStack,
                    jump -> PlayerModelRenderer.submitGrassBlock(poseStack, collector, jump, state.lightCoords));
        }
    }

    /**
     * Replaces the pose the game just gave the player's model with the menu's, for the draw
     * {@code who} names: the game poses it inside the draw's own call, for the layers, and again
     * when it draws the model it collected, so the draw is the one going on or one of the world
     * pass ({@link #passBound}). The cape's model, a player model too, is left to
     * {@link #poseCape}. Vanilla's armour models of a player are player models too, posed here from
     * the same state when they are drawn; an armour model another mod adds as a plain humanoid
     * model takes the pose bound to the state ({@code PreviewEquipment.bindPose}, once a draw), as
     * the skin menu's preview binds its own.
     */
    public static void pose(Object who, PlayerModel model) {
        Binding binding = passBound(who);
        PreviewPlayerData preview = startPose(binding, model.head, model.body, model.rightArm, model.leftArm,
                model.rightLeg, model.leftLeg);
        if (preview != null) {
            PlayerModelRenderer.setupModelPoseWithAnimation(model, preview, 0, 0, false, 0, 0, Minecraft.getInstance());
            posed(binding, model.body);
            if (drawing(who) != null) {
                // Once a draw, from the posing inside its submit: the armour models are posed from
                // the state only when they are drawn, after it, and the pose is the same then
                PreviewEquipment.bindPose(who, model);
            }
        }
    }

    /**
     * Hangs the elytra from the body the menu's pose moved, for the draw going on: the wings layer
     * hangs them in the model's own space where a body standing straight would be, inside the
     * player's draw. The cape needs none of this from 26.1: it is a part of its own model's body,
     * which {@link #poseCape} poses.
     */
    public static void hangFromBody(Object who, EntityModel<?> model, PoseStack poseStack) {
        if (posing(who) && model instanceof HumanoidModel<?> humanoid) {
            humanoid.body.translateAndRotate(poseStack);
        }
    }
//?}

    // The sit clearance of what hangs from the posed body: a chain of its own, so the port of the
    // cape over the sit block (capesit) and the port of the body's entry points edit different blocks
//? if <1.20.2 {
    /**
     * Tilts the cape hung from the posed body ({@link #hangFromBody}) back about its hinge, so it
     * clears the sit's block as the menu's own cape does (see {@link SitClearance}), for the draw
     * prepared. The game then gives the cape its own angle on top, 6 degrees at rest: more when
     * the player moves, and less, by up to 6 degrees and the walk's bob, while the cape still
     * lags below a player rising, so the tilt is worked out from the least of those.
     */
    public static void clearSitBlock(Object who, EntityModel<?> model, PoseStack poseStack, float partialTick) {
        Binding binding = drawing(who);
        if (binding == null || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        PreviewPlayerData preview = binding.preview;
        float rest = SitClearance.CAPE_REST;
        if (binding.player instanceof Player player) {
            // The game's own lift of the cape (CapeLayer), without the parts that only raise it
            float lag = (float) (Mth.lerp(partialTick, player.yCloakO, player.yCloak)
                    - Mth.lerp(partialTick, player.yo, player.getY())) * 10.0f;
            float lift = Mth.clamp(lag, -6.0f, 32.0f) + Mth.sin(Mth.lerp(partialTick, player.walkDistO, player.walkDist) * 6.0f)
                    * 32.0f * Mth.lerp(partialTick, player.oBob, player.bob);
            rest += Math.min(0.0f, lift);
        }
        tiltAboutHinge(poseStack, SitClearance.capeTilt(preview.getSitJumpPose(), sitBlock(preview), rest, 0.0f,
                SitClearance.CAPE_LENGTH, humanoid.body.x, humanoid.body.y, humanoid.body.z,
                humanoid.body.xRot, humanoid.body.yRot, humanoid.body.zRot));
    }

    /**
     * Tilts the folded elytra hung from the posed body ({@link #hangFromBody}) back over the sit's
     * block, as the menu does ({@link SitClearance#wingsTilt}), for the draw prepared. The game
     * then folds them 15 degrees back, easing there from the angle it last gave them, so the tilt
     * is worked out from the lesser of the two.
     */
    public static void clearSitBlockWithWings(Object who, EntityModel<?> model, PoseStack poseStack) {
        Binding binding = drawing(who);
        if (binding == null || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        float rest = SitClearance.WINGS_REST;
        if (binding.player instanceof AbstractClientPlayer player) {
            rest = Math.min(rest, (float) Math.toDegrees(player.elytraRotX));
        }
        tiltAboutHinge(poseStack, SitClearance.wingsTilt(binding.preview.getSitJumpPose(), sitBlock(binding.preview),
                rest, humanoid.body.x, humanoid.body.y, humanoid.body.z,
                humanoid.body.xRot, humanoid.body.yRot, humanoid.body.zRot));
    }
//?} else if <1.21.2 {
    /**
     * Tilts the cape hung from the posed body ({@link #hangFromBody}) back about its hinge, so it
     * clears the sit's block as the menu's own cape does (see {@link SitClearance}), for the draw
     * prepared. The game then gives the cape its own angle on top, 6 degrees at rest: more when
     * the player moves, and less, by up to 6 degrees and the walk's bob, while the cape still
     * lags below a player rising, so the tilt is worked out from the least of those.
     */
    public static void clearSitBlock(Object who, EntityModel<?> model, PoseStack poseStack, float partialTick) {
        Binding binding = drawing(who);
        if (binding == null || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        PreviewPlayerData preview = binding.preview;
        float rest = SitClearance.CAPE_REST;
        if (binding.player instanceof Player player) {
            // The game's own lift of the cape (CapeLayer), without the parts that only raise it
            float lag = (float) (Mth.lerp(partialTick, player.yCloakO, player.yCloak)
                    - Mth.lerp(partialTick, player.yo, player.getY())) * 10.0f;
            float lift = Mth.clamp(lag, -6.0f, 32.0f) + Mth.sin(Mth.lerp(partialTick, player.walkDistO, player.walkDist) * 6.0f)
                    * 32.0f * Mth.lerp(partialTick, player.oBob, player.bob);
            rest += Math.min(0.0f, lift);
        }
        tiltAboutHinge(poseStack, SitClearance.capeTilt(preview.getSitJumpPose(), sitBlock(preview), rest, 0.0f,
                SitClearance.CAPE_LENGTH, humanoid.body.x, humanoid.body.y, humanoid.body.z,
                humanoid.body.xRot, humanoid.body.yRot, humanoid.body.zRot));
    }

    /**
     * Tilts the folded elytra hung from the posed body ({@link #hangFromBody}) back over the sit's
     * block, as the menu does ({@link SitClearance#wingsTilt}), for the draw prepared. The game
     * then folds them 15 degrees back, easing there from the angle it last gave them, so the tilt
     * is worked out from the lesser of the two.
     */
    public static void clearSitBlockWithWings(Object who, EntityModel<?> model, PoseStack poseStack) {
        Binding binding = drawing(who);
        if (binding == null || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        float rest = SitClearance.WINGS_REST;
        if (binding.player instanceof AbstractClientPlayer player) {
            rest = Math.min(rest, (float) Math.toDegrees(player.elytraRotX));
        }
        tiltAboutHinge(poseStack, SitClearance.wingsTilt(binding.preview.getSitJumpPose(), sitBlock(binding.preview),
                rest, humanoid.body.x, humanoid.body.y, humanoid.body.z,
                humanoid.body.xRot, humanoid.body.yRot, humanoid.body.zRot));
    }
//?} else if <1.21.6 {
    /**
     * Tilts the cape of the cape layer's own model back about its hinge, so it clears the sit's
     * block as the menu's own cape does (see {@link SitClearance}), for the draw {@code binding}
     * the cape hook found, once {@link #poseCape} gave that model's {@code body} the posed body:
     * {@code cape}, the body's child, is turned back past the angle the game just gave it from
     * {@code state}. That angle is 6 degrees at rest: more when the player moves, and less, by up
     * to 6 degrees and the walk's bob ({@code capeFlap}), while the cape still lags below a player
     * rising, so the tilt is worked out from the least of those.
     */
    public static void clearSitBlock(@Nullable Binding binding, ModelPart body, ModelPart cape, PlayerRenderState state) {
        if (binding == null || binding.body == null) {
            return;
        }
        PreviewPlayerData preview = binding.preview;
        float rest = SitClearance.CAPE_REST + Math.min(0.0f, state.capeFlap);
        // The model's cape hangs from the hinge itself, its inner face on it, as the cloak did before 1.21.2
        float tilt = SitClearance.capeTilt(preview.getSitJumpPose(), sitBlock(preview), rest, 0.0f,
                SitClearance.CAPE_LENGTH, body.x, body.y, body.z, body.xRot, body.yRot, body.zRot);
        if (tilt > 0.0f) {
            // Turned back about the body's X axis, before the angles the game gave the cape
            Matrix3f turned = new Matrix3f().rotationX(tilt * Mth.DEG_TO_RAD)
                    .mul(new Matrix3f().rotationZYX(cape.zRot, cape.yRot, cape.xRot));
            Vector3f angles = turned.getEulerAnglesZYX(new Vector3f());
            cape.setRotation(angles.x, angles.y, angles.z);
        }
    }

    /**
     * Tilts the folded elytra hung from the posed body ({@link #hangFromBody}) back over the sit's
     * block, as the menu does ({@link SitClearance#wingsTilt}), for the draw prepared. The game
     * then folds them 15 degrees back, easing there from the angle it last gave them (the render
     * state's), so the tilt is worked out from the lesser of the two.
     */
    public static void clearSitBlockWithWings(Object who, EntityModel<?> model, PoseStack poseStack) {
        Binding binding = drawing(who);
        if (binding == null || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        float rest = SitClearance.WINGS_REST;
        if (who instanceof HumanoidRenderState state) {
            rest = Math.min(rest, (float) Math.toDegrees(state.elytraRotX));
        }
        tiltAboutHinge(poseStack, SitClearance.wingsTilt(binding.preview.getSitJumpPose(), sitBlock(binding.preview),
                rest, humanoid.body.x, humanoid.body.y, humanoid.body.z,
                humanoid.body.xRot, humanoid.body.yRot, humanoid.body.zRot));
    }
//?} else if <1.21.9 {
    /**
     * Tilts the cape of a cape model posed from the draw {@code binding} ({@link #poseCape}) back
     * about its hinge, so it clears the sit's block as the menu's own cape does (see
     * {@link SitClearance}): the cape part hangs from the body 2 pixels behind it, so the tilt
     * turns that part about its own pivot, before its own angle. The game gives the cape 6 degrees
     * at rest and {@code capeFlap} more (the render state's, which is less while the cape still
     * lags below a player rising, by up to 6 degrees and the walk's bob), so the tilt is worked out
     * from the least of those.
     */
    public static void clearSitBlock(@Nullable Binding binding, ModelPart capeBody, float capeFlap) {
        PosedBody body = binding == null ? null : binding.body;
        if (body == null || !sitBlock(binding.preview)) {
            return;
        }
        float tilt = SitClearance.capeTilt(binding.preview.getSitJumpPose(), true,
                SitClearance.CAPE_REST + Math.min(0.0f, capeFlap), 2.0f, SitClearance.CAPE_LENGTH,
                body.x(), body.y(), body.z(), body.xRot(), body.yRot(), body.zRot());
        if (tilt <= 0.0f || !capeBody.hasChild("cape")) {
            return;
        }
        ModelPart cape = capeBody.getChild("cape");
        Vector3f angles = new Matrix3f().rotationX(tilt * Mth.DEG_TO_RAD)
                .mul(new Matrix3f().rotationZYX(cape.zRot, cape.yRot, cape.xRot))
                .getEulerAnglesZYX(new Vector3f());
        cape.setRotation(angles.x, angles.y, angles.z);
    }

    /**
     * Tilts the folded elytra hung from the posed body ({@link #hangFromBody}) back over the sit's
     * block, as the menu does ({@link SitClearance#wingsTilt}), for the draw prepared. The game
     * then folds them back by {@code elytraRotX} (radians, the render state's: 15 degrees at
     * rest, easing there from the angle it last gave them), so the tilt is worked out from the
     * lesser of the two.
     */
    public static void clearSitBlockWithWings(Object who, EntityModel<?> model, PoseStack poseStack, float elytraRotX) {
        Binding binding = drawing(who);
        if (binding == null || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        float rest = Math.min(SitClearance.WINGS_REST, (float) Math.toDegrees(elytraRotX));
        tiltAboutHinge(poseStack, SitClearance.wingsTilt(binding.preview.getSitJumpPose(), sitBlock(binding.preview),
                rest, humanoid.body.x, humanoid.body.y, humanoid.body.z,
                humanoid.body.xRot, humanoid.body.yRot, humanoid.body.zRot));
    }
//?} else if <26.1 {
    /**
     * Tilts the cape of the cape model {@link #poseCape} hung from the posed body back about its
     * hinge, so it clears the sit's block as the menu's own cape does (see {@link SitClearance}),
     * for the draw {@code binding} (the one the cape hook found, kept or going on). The cape is
     * the model's {@code cape} part, hinged where 1.20.1's is, 2 pixels behind the body, and
     * already given the game's own angle, 6 degrees at rest, plus the render state's lean and
     * flap: the tilt is worked out from the least of those and turned in before that angle.
     */
    public static void clearSitBlock(@Nullable Binding binding, ModelPart cape, AvatarRenderState state) {
        if (binding == null || binding.body == null) {
            return;
        }
        PosedBody body = binding.body;
        float rest = SitClearance.CAPE_REST + Math.min(0.0f, state.capeFlap);
        float tilt = SitClearance.capeTilt(binding.preview.getSitJumpPose(), sitBlock(binding.preview), rest, 0.0f,
                SitClearance.CAPE_LENGTH, body.x(), body.y(), body.z(), body.xRot(), body.yRot(), body.zRot());
        if (tilt > 0.0f) {
            // The tilt turns about the body's own x axis, before the angles the cape has
            Vector3f angles = new Quaternionf().rotationX((float) Math.toRadians(tilt))
                    .mul(new Quaternionf().rotationZYX(cape.zRot, cape.yRot, cape.xRot))
                    .getEulerAnglesZYX(new Vector3f());
            cape.xRot = angles.x;
            cape.yRot = angles.y;
            cape.zRot = angles.z;
        }
    }

    /**
     * Tilts the folded elytra hung from the posed body ({@link #hangFromBody}) back over the sit's
     * block, as the menu does ({@link SitClearance#wingsTilt}), for the draw going on. The game
     * then folds them by the render state's angle, 15 degrees standing still, so the tilt is
     * worked out from the lesser of the two.
     */
    public static void clearSitBlockWithWings(Object who, EntityModel<?> model, PoseStack poseStack,
                                              HumanoidRenderState state) {
        Binding binding = drawing(who);
        if (binding == null || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        float rest = Math.min(SitClearance.WINGS_REST, (float) Math.toDegrees(state.elytraRotX));
        tiltAboutHinge(poseStack, SitClearance.wingsTilt(binding.preview.getSitJumpPose(), sitBlock(binding.preview),
                rest, humanoid.body.x, humanoid.body.y, humanoid.body.z,
                humanoid.body.xRot, humanoid.body.yRot, humanoid.body.zRot));
    }
//?} else {
    /**
     * Tilts the cape of the cape model posed for the draw {@code binding} ({@link #poseCape}) back
     * about its hinge, so it clears the sit's block as the menu's own cape does (see
     * {@link SitClearance}). From 26.1 the cape is a part of the cape model's body, hinged where
     * 1.20.1's layer hangs it, so the tilt turns that part before its own turn. The game's own
     * angle of the cape, 6 degrees at rest and less by up to 6 degrees and the walk's bob while it
     * lags below a player rising ({@code capeFlap}), is the angle the tilt is worked out from.
     */
    public static void clearSitBlock(@Nullable Binding binding, ModelPart body, ModelPart cape, AvatarRenderState state) {
        if (binding == null || binding.body == null) {
            return;
        }
        float rest = SitClearance.CAPE_REST + Math.min(0.0f, state.capeFlap);
        float tilt = SitClearance.capeTilt(binding.preview.getSitJumpPose(), sitBlock(binding.preview), rest, 0.0f,
                SitClearance.CAPE_LENGTH, body.x, body.y, body.z, body.xRot, body.yRot, body.zRot);
        if (tilt > 0.0f) {
            Matrix3f turned = new Matrix3f().rotationX((float) Math.toRadians(tilt))
                    .mul(new Matrix3f().rotationZYX(cape.zRot, cape.yRot, cape.xRot));
            Vector3f angles = turned.getEulerAnglesZYX(new Vector3f());
            cape.setRotation(angles.x, angles.y, angles.z);
        }
    }

    /**
     * Tilts the folded elytra hung from the posed body ({@link #hangFromBody}) back over the sit's
     * block, as the menu does ({@link SitClearance#wingsTilt}), for the draw going on. The game
     * folds them back by the render state's angle, easing there, so the tilt is worked out from the
     * lesser of that and 15 degrees.
     */
    public static void clearSitBlockWithWings(Object who, EntityModel<?> model, HumanoidRenderState state,
                                              PoseStack poseStack) {
        Binding binding = drawing(who);
        if (binding == null || !(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        float rest = Math.min(SitClearance.WINGS_REST, (float) Math.toDegrees(state.elytraRotX));
        tiltAboutHinge(poseStack, SitClearance.wingsTilt(binding.preview.getSitJumpPose(), sitBlock(binding.preview),
                rest, humanoid.body.x, humanoid.body.y, humanoid.body.z,
                humanoid.body.xRot, humanoid.body.yRot, humanoid.body.zRot));
    }
//?}

    /** Whether the sit's block is drawn under the player: while sitting, and through the jumps. */
    private static boolean sitBlock(PreviewPlayerData preview) {
        SitJump.Pose jump = preview.getSitJumpPose();
        return jump != null ? jump.blockShown() : "sit".equalsIgnoreCase(preview.getCurrentAnimation());
    }

    /** Turns what hangs from the hinge 2 pixels behind the body back by {@code degrees}. */
    private static void tiltAboutHinge(PoseStack poseStack, float degrees) {
        if (degrees > 0.0f) {
            poseStack.translate(0.0f, 0.0f, 0.125f);
            rotate(poseStack, Axis.XP.rotationDegrees(degrees));
            poseStack.translate(0.0f, 0.0f, -0.125f);
        }
    }

    private static void rotate(PoseStack poseStack, Quaternionf rotation) {
//? if <26.3 {
        poseStack.mulPose(rotation);
//?} else {
        poseStack.rotate(rotation);
//?}
    }

    /**
     * The draw going on if it is {@code who}'s, or null: what a hook that runs inside the draw's
     * own call looks up (every family to 1.21.8, and the hooks of 1.21.9 on that run inside
     * {@code submit}).
     */
    @Nullable
    public static Binding drawing(Object who) {
        Binding binding = current;
        return binding != null && who != null && binding.who == who ? binding : null;
    }

    /**
     * A draw named {@code who} that was kept ({@link #keep}) for hooks that run after its own call
     * returned, or null: from 1.21.9, where the game poses the model it collected later. Only the
     * families that keep their bindings may look here (see {@link #keep}).
     */
    @Nullable
    public static Binding kept(Object who) {
        if (who == null) {
            return null;
        }
        for (Binding kept : BOUND) {
            if (kept != null && kept.who == who) {
                return kept;
            }
        }
        return null;
    }

    /** The draw going on if it is {@code who}'s, else a kept draw of {@code who} ({@link #kept}), or null. */
    @Nullable
    public static Binding bound(Object who) {
        Binding binding = drawing(who);
        return binding != null ? binding : kept(who);
    }

    private static void forgetBound(Object who) {
        for (int i = 0; i < BOUND_DRAWS; i++) {
            if (BOUND[i] != null && BOUND[i].who == who) {
                BOUND[i] = null;
            }
        }
    }

    /**
     * Forgets every kept draw ({@link #keep}), for instance where a new world pass starts: a kept
     * draw is found only by the very object that named it, which the families that keep draws
     * never hand to another draw, so this only lets the old bindings go sooner.
     */
    public static void forgetBound() {
        java.util.Arrays.fill(BOUND, null);
    }

    /**
     * The preview whose pose {@code who} takes in the draw going on, or null when it takes none
     * (a kept draw's is {@code Binding.preview()}).
     */
    @Nullable
    static PreviewPlayerData posedPreview(Object who) {
        Binding binding = drawing(who);
        return binding == null ? null : binding.preview;
    }

    /**
     * Whether the menu's pose replaces the game's in the draw named {@code who} going on (never a
     * kept draw: see {@link #keep}).
     */
    public static boolean posing(Object who) {
        return drawing(who) != null;
    }

    /** The player's draw is over: no other draw of them takes the menu's pose. */
    public static void drawn() {
        current = null;
    }
}
