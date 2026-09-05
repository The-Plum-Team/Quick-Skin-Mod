package com.quickskin.mod.client.rendering;

import com.quickskin.mod.common.data.SkinResolution;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

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
    private SkinResolution resolution;
    private float yRotation; // Y-axis rotation in degrees
    private float headYaw; // Head yaw for looking around
    private float headPitch; // Head pitch for looking up/down

    // Animation state
    private String currentAnimation; // For future animation support
    private final float animationProgress;

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
        }
        this.skinLocation = skinLocation;
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

    public void setCurrentAnimation(String currentAnimation) {
        this.currentAnimation = currentAnimation;
    }

    public float getAnimationProgress() {
        return animationProgress;
    }
}
