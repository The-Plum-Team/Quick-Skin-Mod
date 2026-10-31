package com.quickskin.mod.client.rendering;

import com.quickskin.mod.common.data.SkinResolution;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import java.util.concurrent.ThreadLocalRandom;

/**
 * Data holder for player preview rendering
 * Replaces GeoPlayerEntity - stores all info needed to render a player model in GUI
 */
@Environment(EnvType.CLIENT)
public class PreviewPlayerData {

    //? if <1.21.11 {
    private ResourceLocation skinLocation;
    private ResourceLocation capeLocation;
    //?} else {
    private Identifier skinLocation;
    private Identifier capeLocation;
    //?}
    private String modelType; // "classic" or "slim"
    private String capeId;
    private boolean capeAuthoritative;
    private boolean heldGunVisible;
    private boolean equipmentVisible;
    private SkinResolution resolution;
    private float yRotation; // Y-axis rotation in degrees
    private float headYaw; // Head yaw for looking around
    private float headPitch; // Head pitch for looking up/down

    // Animation state
    private String currentAnimation; // For future animation support
    private boolean showcaseIdle;
    private boolean ownPose;
    private boolean elytraPreview;
    private final float animationProgress;

    // The move the skin menu's model makes when another skin is picked
    private boolean drawn;
    private SkinSwapAnimation.Variant skinSwapVariant;
    private long skinSwapStartNanos;
    private SkinSwapAnimation.Pose skinSwapPose = SkinSwapAnimation.Pose.REST;
    private IdleShuffle idles = new IdleShuffle();
    // The jump onto the block when the Sitting pose is picked, or off it when it is left
    private SitJump.Move sitJump;
    private long sitJumpStartNanos;
    private SitJump.Pose sitJumpPose;
    // How far the model is walking as it eases into the walk or out of it
    private final GaitBlend gait = new GaitBlend();
    private float walkAmount;

    public PreviewPlayerData() {
        this.modelType = "classic";
        this.resolution = SkinResolution.STANDARD;
        this.yRotation = 0.0f;
        this.headYaw = 0.0f;
        this.headPitch = 0.0f;
        this.currentAnimation = "idle";
        this.animationProgress = 0.0f;
    }

    /**
     * Check if this is a slim model
     */
    public boolean isSlim() {
        return "slim".equals(modelType);
    }

    /**
     * Check if HD skin
     */
    public boolean isHD() {
        return resolution != null && resolution.isHD();
    }

    /**
     * Get scale factor for HD skins
     */
    public int getScaleFactor() {
        return resolution != null ? resolution.getScale() : 1;
    }

    // Getters and setters

    //? if <1.21.11 {
    public ResourceLocation getSkinLocation() {
    //?} else {
    public Identifier getSkinLocation() {
    //?}
        return skinLocation;
    }

    //? if <1.21.11 {
    public void setSkinLocation(ResourceLocation skinLocation) {
    //?} else {
    public void setSkinLocation(Identifier skinLocation) {
    //?}
        // Clear 3D mesh cache if skin is changing
        if (this.skinLocation != null && !this.skinLocation.equals(skinLocation)) {
            SkinLayers3DIntegration.clearCache();
            startSkinSwap();
        }
        this.skinLocation = skinLocation;
    }

    /**
     * Starts the skin-change move on the skin menu's model, and another idle under it. The skin a
     * menu is opened with is set before its first draw and starts nothing, and a pick made while a move plays lets that move
     * finish, so browsing quickly never stacks moves.
     */
    private void startSkinSwap() {
        if (!showcaseIdle || !drawn || skinSwapVariant != null) {
            return;
        }
        skinSwapVariant = SkinSwapAnimation.next(ThreadLocalRandom.current()::nextInt);
        skinSwapStartNanos = System.nanoTime();
        idles.shuffle();
    }

    /**
     * Samples the skin-change move for the draw starting at {@code nowNanos}, which then reads it
     * through {@link #getSkinSwapPose()}. Also marks the preview as drawn.
     */
    public SkinSwapAnimation.Pose advanceSkinSwap(long nowNanos) {
        drawn = true;
        if (skinSwapVariant == null) {
            skinSwapPose = SkinSwapAnimation.Pose.REST;
            return skinSwapPose;
        }
        float seconds = (nowNanos - skinSwapStartNanos) / 1.0e9f;
        if (seconds >= SkinSwapAnimation.duration(skinSwapVariant)) {
            skinSwapVariant = null;
            skinSwapPose = SkinSwapAnimation.Pose.REST;
        } else {
            skinSwapPose = SkinSwapAnimation.sample(skinSwapVariant, seconds);
        }
        return skinSwapPose;
    }

    /**
     * Switches to an animation the player picked, with the move that leads into it, which the skin
     * menu plays: picking Sitting from another pose jumps onto the block and picking another pose
     * from Sitting jumps off it ({@link SitJump}); otherwise the model eases into the walk or out
     * of it ({@link GaitBlend}). The jump onto the block plays only while the pose stays Sitting,
     * the jump off it only while it stays another.
     */
    public void pickAnimation(String animation, long nowNanos) {
        boolean wasSitting = "sit".equalsIgnoreCase(currentAnimation);
        boolean sitting = "sit".equalsIgnoreCase(animation);
        boolean walking = "walk".equalsIgnoreCase(animation);
        currentAnimation = animation;
        if (wasSitting != sitting) {
            sitJump = sitting ? SitJump.Move.ONTO : SitJump.Move.OFF;
            sitJumpStartNanos = nowNanos;
        }
        if (wasSitting) {
            // The jump off the block eases into the picked pose itself
            gait.set(walking);
        } else {
            // Into the walk, out of it, or out of it on the way onto the block
            gait.ease(walking, nowNanos);
        }
    }

    /**
     * Samples the jump onto the block or off it and the walk blend for the draw starting at
     * {@code nowNanos}, which then reads them through {@link #getSitJumpPose()} and
     * {@link #getWalkAmount()}.
     */
    public void advancePoseMoves(long nowNanos) {
        float seconds = (nowNanos - sitJumpStartNanos) / 1.0e9f;
        if (sitJump != null && ((sitJump == SitJump.Move.ONTO) != "sit".equalsIgnoreCase(currentAnimation)
                || seconds >= SitJump.duration(sitJump))) {
            sitJump = null;
        }
        sitJumpPose = sitJump != null ? SitJump.sample(sitJump, seconds) : null;
        walkAmount = gait.amount(nowNanos);
        if (sitJump == SitJump.Move.OFF) {
            walkAmount *= sitJumpPose.settled();
        }
    }

    /** The jump onto the block or off it in the current draw, or null when none plays. */
    public SitJump.Pose getSitJumpPose() {
        return sitJumpPose;
    }

    /** How far the model is walking in the current draw, from its idle (0) to the full stride (1). */
    public float getWalkAmount() {
        return walkAmount;
    }

    /**
     * Has this preview play {@code shared}'s idle, which a menu keeps for every preview it builds,
     * so the idle the player was watching carries on when the window is resized or a dialog closes.
     */
    public void shareIdles(IdleShuffle shared) {
        this.idles = shared;
    }

    /** The preview's idle {@code seconds} on the preview clock: one of several, changing with the skin. */
    public SkinSwapAnimation.Pose idlePose(double seconds) {
        return idles.sample(seconds, ThreadLocalRandom.current()::nextInt);
    }

    /** The skin-change offsets of the current draw; {@link SkinSwapAnimation.Pose#REST} when none plays. */
    public SkinSwapAnimation.Pose getSkinSwapPose() {
        return skinSwapPose;
    }

    //? if <1.21.11 {
    public ResourceLocation getCapeLocation() {
    //?} else {
    public Identifier getCapeLocation() {
    //?}
        return capeLocation;
    }

    //? if <1.21.11 {
    public void setCapeLocation(ResourceLocation capeLocation) {
    //?} else {
    public void setCapeLocation(Identifier capeLocation) {
    //?}
        this.capeLocation = capeLocation;
    }

    public String getCapeId() {
        return capeId;
    }

    public void setCapeId(String capeId) {
        this.capeId = capeId;
    }

    /**
     * Whether this preview owns the cape, i.e. an editor pushed a selection into it.
     *
     * <p>The preview renders the real player entity, so without this the cape layer resolves the
     * player's applied cape and the editor's selection is ignored. Only an explicit
     * {@code PlayerWidget.setCape(...)} marks the preview authoritative: widgets that are merely
     * seeded with the current appearance keep deferring to the applied cape, so a panel built with
     * "no cape initially" does not blank a cape the player is actually wearing.
     */
    public boolean isCapeAuthoritative() {
        return capeAuthoritative;
    }

    /** Marks the editor's cape selection - including "no cape" - as authoritative for this preview. */
    public void markCapeAuthoritative() {
        this.capeAuthoritative = true;
    }

    /**
     * Whether this preview keeps a held gun of a gun mod visible instead of hiding it with the rest
     * of the player's equipment.
     *
     * <p>Off for every cosmetic preview: a menu shows the skin and the cape. Only a preview that
     * mirrors the live player in the world asks for it, and the renderer still decides per draw
     * whether the main-hand item qualifies.
     */
    public boolean isHeldGunVisible() {
        return heldGunVisible;
    }

    /** Asks the renderer to keep a held gun of a gun mod visible in this preview. */
    public void markHeldGunVisible() {
        this.heldGunVisible = true;
    }

    /**
     * Whether this preview shows what the live local player wears and holds (armour, elytra, held
     * items, a worn head), as the world does, without the armour pieces the player hides from
     * everyone. The pause menu's and the skin menu's previews do; the cape menus hide it, and so
     * does any preview of a player other than the live local one.
     */
    public boolean isEquipmentVisible() {
        return equipmentVisible;
    }

    public void setEquipmentVisible(boolean equipmentVisible) {
        this.equipmentVisible = equipmentVisible;
    }

    public String getModelType() {
        return modelType;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    public SkinResolution getResolution() {
        return resolution;
    }

    public void setResolution(SkinResolution resolution) {
        this.resolution = resolution;
    }

    public float getYRotation() {
        return yRotation;
    }

    public void setYRotation(float yRotation) {
        this.yRotation = yRotation;
    }

    public float getHeadYaw() {
        return headYaw;
    }

    public void setHeadYaw(float headYaw) {
        this.headYaw = headYaw;
    }

    public float getHeadPitch() {
        return headPitch;
    }

    public void setHeadPitch(float headPitch) {
        this.headPitch = headPitch;
    }

    public String getCurrentAnimation() {
        return currentAnimation;
    }

    /**
     * Whether "idle" uses the showcase stance: facing the viewer squarely, arms and legs a little
     * apart, breathing slowly. The skin menu uses it to present the whole skin.
     */
    public boolean isShowcaseIdle() {
        return showcaseIdle;
    }

    public void setShowcaseIdle(boolean showcaseIdle) {
        this.showcaseIdle = showcaseIdle;
    }

    /**
     * Whether the preview draws its own model in the chosen pose even in a world, instead of the
     * live player doing whatever the player is doing. The skin menu uses it; the pause menu's
     * preview keeps the live player.
     */
    public boolean isOwnPose() {
        return ownPose;
    }

    public void setOwnPose(boolean ownPose) {
        this.ownPose = ownPose;
    }

    /**
     * Whether the preview draws the cape's texture on folded elytra wings instead of on the cape,
     * as vanilla does for a player wearing both. Such a preview draws its own model.
     */
    public boolean isElytraPreview() {
        return elytraPreview;
    }

    public void setElytraPreview(boolean elytraPreview) {
        this.elytraPreview = elytraPreview;
    }

    public void setCurrentAnimation(String currentAnimation) {
        this.currentAnimation = currentAnimation;
        boolean walking = "walk".equalsIgnoreCase(currentAnimation);
        gait.set(walking);
        walkAmount = walking ? 1.0f : 0.0f;
    }

    public float getAnimationProgress() {
        return animationProgress;
    }
}
