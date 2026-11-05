package com.quickskin.mod.common.data;

import com.quickskin.mod.platform.TextureReference;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Represents a player's complete appearance (skin, cape, elytra, model)
 * This is the core data model used throughout the mod
 */
public class PlayerAppearance {
    /** The elytra id that draws vanilla's own elytra even under a cape. */
    public static final String VANILLA_ELYTRA = "minecraft:elytra";

    private final UUID playerId;
    private String skinId;              // e.g., "local_skin:hash" or "username"
    private String capeId;              // e.g., "local_cape:hash", "known:minecon2016", or "username"
    private String elytraId;            // a cape id for the elytra, VANILLA_ELYTRA, or "": the cape's
    private String model;               // "classic", "slim", or "auto"

    // Resolved TextureReferences (cached)
    private TextureReference skinLocation;
    private TextureReference capeLocation;
    private TextureReference elytraLocation;

    public PlayerAppearance(UUID playerId, String skinId, String capeId, String model) {
        this(playerId, skinId, capeId, "", model);
    }

    public PlayerAppearance(UUID playerId, String skinId, String capeId, String elytraId, String model) {
        this.playerId = playerId;
        this.skinId = skinId != null ? skinId : "";
        this.capeId = capeId != null ? capeId : "";
        this.elytraId = elytraId != null ? elytraId : "";
        this.model = model != null ? model : "classic";
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getSkinId() {
        return skinId;
    }

    public void setSkinId(String skinId) {
        this.skinId = skinId;
        this.skinLocation = null; // Invalidate cache
    }

    public String getCapeId() {
        return capeId;
    }

    public void setCapeId(String capeId) {
        this.capeId = capeId;
        this.capeLocation = null; // Invalidate cache
    }

    /**
     * The cape whose texture the elytra wear instead of the cape's, {@link #VANILLA_ELYTRA} for
     * vanilla's elytra, or {@code ""} when the elytra wear the cape like everyone else's.
     */
    public String getElytraId() {
        // Null when read from a file saved before elytra had their own cape
        return elytraId != null ? elytraId : "";
    }

    public void setElytraId(String elytraId) {
        this.elytraId = elytraId != null ? elytraId : "";
        this.elytraLocation = null; // Invalidate cache
    }

    /** Whether the elytra wear something other than the cape. */
    public boolean hasSeparateElytra() {
        String elytra = getElytraId();
        return !elytra.isEmpty() && !elytra.equals(capeId);
    }

    @Nullable
    public TextureReference getElytraLocation() {
        return elytraLocation;
    }

    public void setElytraLocation(TextureReference elytraLocation) {
        this.elytraLocation = elytraLocation;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    @Nullable
    public TextureReference getSkinLocation() {
        return skinLocation;
    }

    public void setSkinLocation(TextureReference skinLocation) {
        this.skinLocation = skinLocation;
    }

    @Nullable
    public TextureReference getCapeLocation() {
        return capeLocation;
    }

    public void setCapeLocation(TextureReference capeLocation) {
        this.capeLocation = capeLocation;
    }

    @Override
    public String toString() {
        return "PlayerAppearance{" +
                "playerId=" + playerId +
                ", skinId='" + skinId + '\'' +
                ", capeId='" + capeId + '\'' +
                ", elytraId='" + elytraId + '\'' +
                ", model='" + model + '\'' +
                '}';
    }
}
