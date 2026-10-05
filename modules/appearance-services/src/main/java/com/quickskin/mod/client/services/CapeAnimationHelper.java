package com.quickskin.mod.client.services;

import com.quickskin.mod.client.storage.ClientAnimationMetadataCache;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.client.api.ClientNetworkApi;
import com.quickskin.mod.common.data.AssetMetadata;
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

    /**
     * Marks a cape as rendered and starts a bounded network-animation activation if needed.
     *
     * <p>Marking stamps the cape's animation slot and its network-cache working-set entry with the
     * current client tick, so it is done at most once per {@link CapeRenderKeys#visibilityEpoch()
     * visibility epoch}: the epoch moves on every tick, whenever that state is removed and whenever
     * a cape texture enters the network cache. That covers every cape the renderer draws: a
     * {@code known:} cape, a network cape in the cache, and a {@code local_cape:} cape the cache
     * does not hold but the local catalogue does (the player's own cape, which
     * {@code CapeService} then draws from the catalogue). Only a {@code local_cape:} cape held by
     * neither, a network cape still pending or already evicted, is not recorded as marked, so
     * every frame keeps checking for its arrival exactly as before.</p>
     */
    public static void markCapeVisible(@Nullable String capeId) {
        CapeRenderKey cape = CapeRenderKeys.shared().of(capeId);
        if (cape != null) {
            markVisible(cape);
        }
    }

    private static void markVisible(CapeRenderKey cape) {
        long epoch = CapeRenderKeys.shared().visibilityEpoch();
        if (cape.isVisibilityMarkedIn(epoch)) return;
        AnimatedTextureManager manager = AnimatedTextureManager.getInstance();
        manager.markAnimationVisible(cape.animationId());
        String hash = cape.networkHash();
        if (hash != null) {
            if (NetworkTextureCache.getInstance().markCapeInUse(cape)) {
                boolean networkAnimation =
                        ClientAnimationMetadataCache.getInstance().hasMetadata(cape);
                if (networkAnimation && manager.shouldRequestActivation(cape.animationId())) {
                    ClientNetworkApi.actions().activateStoredTexture("cape", hash);
                }
            } else if (!isCataloguedCape(hash)) {
                // A network cape still pending or already evicted: check it again next frame.
                return;
            }
        }
        cape.recordVisibilityMark(epoch);
    }

    /**
     * Whether {@code CapeService.loadLocalCape} draws this content ID from the local catalogue
     * when the network cache does not hold it, which is how the player's own catalogued cape
     * renders. Such a cape has no working-set entry, so its slot mark is all there is to repeat,
     * and the cache's arrival of the same texture moves the epoch on.
     */
    private static boolean isCataloguedCape(String hash) {
        AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(hash);
        return metadata != null && metadata.isCape();
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

    /**
     * Starts a cape layer's lookup of the texture it is about to draw. Call it right before the
     * lookup and pass the result to {@link #resolveCurrentFrameOnce}.
     */
    public static void beginCapeLookup() {
        LOOKUP_FRAMES.beginLookup();
    }

    /**
     * {@link #resolveCurrentFrame}, except that a texture this thread already resolved for
     * {@code capeId} since {@link #beginCapeLookup()} is returned as it is.
     *
     * <p>The cape layer looks its texture up through the player's appearance, which already
     * resolves the current frame (so that other mods reading the cape texture see a frame rather
     * than the atlas). Resolving that result a second time cannot change it: a frame texture is
     * never registered as an atlas, and nothing on the render thread changes the animation or
     * texture state between the lookup and this call. A texture from anywhere else, such as a
     * preview binding, the title-screen fallback or another mod's override, was not resolved
     * since the lookup began and is resolved as before.</p>
     */
    @Nullable
    //? if <1.21.11 {
    public static ResourceLocation resolveCurrentFrameOnce(
            ResourceLocation texture, @Nullable String capeId) {
    //?} else {
    public static Identifier resolveCurrentFrameOnce(Identifier texture, @Nullable String capeId) {
    //?}
        if (LOOKUP_FRAMES.wasResolved(texture, capeId)) {
            return texture;
        }
        return resolveCurrentFrame(texture, capeId);
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
        //? if <1.21.11 {
        ResourceLocation frame =
        //?} else {
        Identifier frame =
        //?}
                resolveFrame(atlasLocation, CapeRenderKeys.shared().of(capeId), visible);
        if (frame != null) {
            LOOKUP_FRAMES.recordResolved(capeId, frame);
        }
        return frame;
    }

    @Nullable
    //? if <1.21.11 {
    private static ResourceLocation resolveFrame(
            ResourceLocation atlasLocation, @Nullable CapeRenderKey cape, boolean visible) {
    //?} else {
    private static Identifier resolveFrame(
            Identifier atlasLocation, @Nullable CapeRenderKey cape, boolean visible) {
    //?}
        AnimatedTextureManager atm = AnimatedTextureManager.getInstance();
        // A cape ID without an animation ID has no key and can only use the atlas fallback.
        if (cape != null) {
            String animationId = cape.animationId();
            if (visible) {
                markVisible(cape);
            }
            if (cape.networkHash() != null) {
                NetworkTextureCache cache = NetworkTextureCache.getInstance();
                boolean networkAnimation = ClientAnimationMetadataCache.getInstance().hasMetadata(cape)
                        && (visible ? cache.markCapeInUse(cape) : cache.containsCape(cape));
                if (networkAnimation) {
                    if (visible && atm.shouldRequestActivation(animationId)) {
                        ClientNetworkApi.actions().activateStoredTexture("cape", cape.networkHash());
                    }
                    return atm.getCurrentFrameTexture(animationId);
                }
            }

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

    /** The last frame {@link #resolveFrame} returned since the cape layer began its lookup. */
    private static final CapeLookupFrames LOOKUP_FRAMES = new CapeLookupFrames();
}
