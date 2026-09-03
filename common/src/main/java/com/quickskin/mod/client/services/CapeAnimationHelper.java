package com.quickskin.mod.client.services;

import com.quickskin.mod.client.storage.ClientAnimationMetadataCache;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.networking.ClientNetworkHandler;
import com.quickskin.mod.networking.NetworkSecurity;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for cape animation ID derivation and frame resolution.
 * Centralizes the pattern of converting capeId -> animationId so that
 * all callers use consistent logic.
 */
public final class CapeAnimationHelper {

    private CapeAnimationHelper() {}

    /**
     * Derives the animation ID from a cape ID string.
     * <ul>
     *   <li>{@code "local_cape:hash"} &rarr; {@code "cape_hash"}</li>
     *   <li>{@code "known:id"}        &rarr; {@code "cape_known_id"}</li>
     * </ul>
     *
     * @param capeId The cape ID (e.g. "local_cape:abc123" or "known:rickroll")
     * @return The animation ID, or {@code null} if the capeId format is unrecognized
     */
    @Nullable
    public static String deriveAnimationId(@Nullable String capeId) {
        return CapeAnimationIds.deriveAnimationId(capeId);
    }

    /** Marks a cape as rendered and starts a bounded network-animation activation if needed. */
    public static void markCapeVisible(@Nullable String capeId) {
        String animationId = deriveAnimationId(capeId);
        if (animationId == null) return;
        AnimatedTextureManager manager = AnimatedTextureManager.getInstance();
        manager.markAnimationVisible(animationId);
        String hash = CapeAnimationIds.localHash(capeId);
        if (hash == null) return;
        boolean cachedNetworkCape = NetworkSecurity.isValidContentId(hash)
                && NetworkTextureCache.getInstance().markTextureInUse(hash, "cape");
        boolean networkAnimation = cachedNetworkCape
                && ClientAnimationMetadataCache.getInstance().hasMetadata(hash);
        if (networkAnimation && manager.shouldRequestActivation(animationId)) {
            ClientNetworkHandler.onTextureStored("cape", hash);
        }
    }

    /**
     * Resolves the current animation frame for an animated cape, or returns the
     * atlas location unchanged for non-animated capes.
     * <p>
     * This allows any code that reads the cape texture (including third-party mods
     * like WaveyCapes) to get the correct current frame instead of the full atlas.
     *
     * @param atlasLocation The atlas Identifier (all frames stacked)
     * @param capeId        The cape ID string
     * @return The current frame Identifier if animated, {@code atlasLocation} for a static cape,
     *         or {@code null} while a network first-frame fallback is prepared
     */
    @Nullable
    //? if <1.21.11 {
    public static ResourceLocation resolveCurrentFrame(ResourceLocation atlasLocation, @Nullable String capeId) {
    //?} else {
    public static Identifier resolveCurrentFrame(Identifier atlasLocation, @Nullable String capeId) {
    //?}
        return resolveFrame(atlasLocation, capeId, false);
    }

    /** Resolves a cape from an actual render path and updates visibility-based slot priority. */
    @Nullable
    //? if <1.21.11 {
    public static ResourceLocation resolveVisibleFrame(ResourceLocation atlasLocation, @Nullable String capeId) {
    //?} else {
    public static Identifier resolveVisibleFrame(Identifier atlasLocation, @Nullable String capeId) {
    //?}
        return resolveFrame(atlasLocation, capeId, true);
    }

    @Nullable
    //? if <1.21.11 {
    private static ResourceLocation resolveFrame(
            ResourceLocation atlasLocation, @Nullable String capeId, boolean visible) {
    //?} else {
    private static Identifier resolveFrame(
            Identifier atlasLocation, @Nullable String capeId, boolean visible) {
    //?}
        if (atlasLocation == null) {
            return null;
        }

        String animationId = deriveAnimationId(capeId);
        AnimatedTextureManager atm = AnimatedTextureManager.getInstance();
        if (visible && animationId != null) {
            markCapeVisible(capeId);
        }
        String hash = CapeAnimationIds.localHash(capeId);
        if (animationId != null && hash != null) {
            boolean networkAnimation = NetworkSecurity.isValidContentId(hash)
                    && ClientAnimationMetadataCache.getInstance().hasMetadata(hash)
                    && (visible
                            ? NetworkTextureCache.getInstance().markTextureInUse(hash, "cape")
                            : NetworkTextureCache.getInstance().containsTexture(hash, "cape"));
            if (networkAnimation) {
                if (visible && atm.shouldRequestActivation(animationId)) {
                    ClientNetworkHandler.onTextureStored("cape", hash);
                }
                return atm.getCurrentFrameTexture(animationId);
            }
        }

        if (animationId != null) {
        //? if <1.21.11 {
            ResourceLocation currentFrame = atm.getCurrentFrameTexture(animationId);
        //?} else {
            Identifier currentFrame = atm.getCurrentFrameTexture(animationId);
        //?}
            if (currentFrame != null) return currentFrame;
        }

        // Compatibility fallback for callers that only know the atlas location.
        return atm.getAnimationFrame(atlasLocation).orElse(atlasLocation);
    }
}
