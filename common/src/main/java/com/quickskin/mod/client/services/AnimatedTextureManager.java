package com.quickskin.mod.client.services;

import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.storage.ClientAnimationMetadataCache;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.util.CapeElytraSilhouette;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.TextureTransferLimits;
import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Manages animated textures (capes, future skin animations).
 * Tracks animation state and provides current frame for rendering.
 *
 * Optimized architecture: uses a SINGLE DynamicTexture per animation instead of
 * one per frame. The atlas pixel data is kept in RAM (NativeImage) and only the
 * current frame is uploaded to the GPU when it changes. This reduces VRAM usage
 * from O(frames) to O(1) per animation.
 */
@Environment(EnvType.CLIENT)
public class AnimatedTextureManager {

    private static AnimatedTextureManager instance;

    // Limits to prevent VRAM/RAM exhaustion from HD animated capes
    private static final int MAX_ANIM_FRAME_WIDTH = 512;
    private static final int MAX_ANIM_FRAME_HEIGHT = 256;
    private static final int MAX_ANIM_FRAMES = 256;
    private static final int MAX_ANIMATIONS = 32;
    private static final long MAX_RETAINED_ATLAS_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_STATIC_FALLBACK_PIXELS =
            TextureTransferLimits.MAX_CLIENT_CACHE_PIXELS;
    private static final long VISIBILITY_GRACE_TICKS = 20L;
    private static final long ACTIVATION_RETRY_TICKS = 20L;
    private static final long MAX_FRAME_PIXELS_PER_TICK =
            4L * MAX_ANIM_FRAME_WIDTH * MAX_ANIM_FRAME_HEIGHT;

    /**
     * Animation state for a single animated texture.
     * Uses ONE GPU texture that is updated in-place when the frame changes.
     */
    private static class AnimationState {
        //? if <1.21.11 {
        final ResourceLocation originalAtlasLocation; // Atlas location from LocalAssetManager (for reverse lookup)
        final ResourceLocation frameTextureLocation;   // Single GPU texture location for this animation
        //?} else {
        final Identifier originalAtlasLocation; // Atlas location from LocalAssetManager (for reverse lookup)
        final Identifier frameTextureLocation;   // Single GPU texture location for this animation
        //?}
        final AnimationMetadata metadata;
        final long startTime;

        private final NativeImage atlasPixels;      // Full atlas in RAM (native memory, NOT on GPU)
        private final DynamicTexture frameTexture;  // Single GPU texture (wraps framePixels)
        private final int frameWidth;
        private final int frameHeight;
        private int currentFrame = 0;
        private float speedMultiplier;

        //? if <1.21.11 {
        AnimationState(String animationId, ResourceLocation atlasLocation,
        //?} else {
        AnimationState(String animationId, Identifier atlasLocation,
        //?}
                       NativeImage atlasPixels, int frameWidth, int frameHeight,
                       AnimationMetadata metadata, float speedMultiplier) {
            this.originalAtlasLocation = atlasLocation;
            this.metadata = metadata;
            this.startTime = System.currentTimeMillis();
            this.speedMultiplier = speedMultiplier;
            this.atlasPixels = atlasPixels;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;

            // Build the native/GPU resource transactionally so constructor failure cannot leak it.
            NativeImage framePixels = new NativeImage(frameWidth, frameHeight, false);
            DynamicTexture createdTexture = null;
            //? if <1.21.11 {
            ResourceLocation createdLocation = null;
            //?} else {
            Identifier createdLocation = null;
            //?}
            boolean registered = false;
            try {
                copyFrameTo(framePixels, 0);
                //? if <1.21.11 {
                createdTexture = new DynamicTexture(framePixels);
                //?} else {
                createdTexture = new DynamicTexture(
                        () -> "quickskin_anim_" + animationId, framePixels);
                //?}

                String texId = "quickskin/animated/"
                        + animationId.replaceAll("[^a-zA-Z0-9/._-]", "_");
                //? if <1.21.11 {
                createdLocation = Minecraft.getInstance().getTextureManager()
                        .register(texId, createdTexture);
                //?} else {
                createdLocation = Identifier.parse(texId);
                Minecraft.getInstance().getTextureManager().register(
                        createdLocation, createdTexture);
                //?}
                registered = true;
            } catch (RuntimeException | LinkageError error) {
                if (registered && createdLocation != null) {
                    try {
                        Minecraft.getInstance().getTextureManager().release(createdLocation);
                    } catch (RuntimeException ignored) {
                        if (createdTexture != null) createdTexture.close();
                    }
                } else if (createdTexture != null) {
                    createdTexture.close();
                } else {
                    framePixels.close();
                }
                throw error;
            }
            this.frameTexture = createdTexture;
            this.frameTextureLocation = createdLocation;
        }

        /**
         * Copy a specific frame's pixels from the atlas to the target NativeImage.
         */
        private void copyFrameTo(NativeImage target, int frameIndex) {
            int srcY = frameIndex * frameHeight;
            for (int y = 0; y < frameHeight; y++) {
                for (int x = 0; x < frameWidth; x++) {
                    //? if <26.2 {
                    MinecraftCompat.INSTANCE.setPixel(
                            target, x, y, MinecraftCompat.INSTANCE.getPixel(atlasPixels, x, srcY + y));
                    //?} else {
                    MinecraftCompat.INSTANCE.setPixel(target, x, y, MinecraftCompat.INSTANCE.getPixel(atlasPixels, x, srcY + y));
                    //?}
                }
            }
        }

        /**
         * Tick the animation. If the frame changed, copies new frame pixels
         * to the GPU texture and uploads.
         */
        long tick(long availablePixels) {
            if (metadata.frameCount() <= 1 || speedMultiplier == 0.0f) {
                return 0L;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            long adjustedElapsed = (long) (elapsed * speedMultiplier);
            int newFrame = metadata.getFrameAtTime(adjustedElapsed);

            if (newFrame != currentFrame) {
                long updatePixels = (long) frameWidth * frameHeight;
                if (updatePixels > availablePixels) return 0L;
                currentFrame = newFrame;
                // Update the DynamicTexture's backing NativeImage and re-upload to GPU
                NativeImage pixels = frameTexture.getPixels();
                if (pixels != null) {
                    copyFrameTo(pixels, currentFrame);
                    frameTexture.upload();
                    return updatePixels;
                }
            }
            return 0L;
        }

        void setSpeedMultiplier(float speed) {
            this.speedMultiplier = speed;
        }

        //? if <1.21.11 {
        ResourceLocation getCurrentFrameTexture() {
        //?} else {
        Identifier getCurrentFrameTexture() {
        //?}
            return frameTextureLocation;
        }

        void cleanup() {
            try {
                // release() closes the DynamicTexture, freeing framePixels + its GL texture.
                try {
                    Minecraft.getInstance().getTextureManager().release(frameTextureLocation);
                } catch (RuntimeException | LinkageError releaseError) {
                    try {
                        frameTexture.close();
                    } catch (RuntimeException | LinkageError closeError) {
                        releaseError.addSuppressed(closeError);
                    }
                    throw releaseError;
                }
            } finally {
                // Always free the atlas even when the texture manager rejects the release.
                atlasPixels.close();
            }
        }
    }

    /** A true first-frame texture retained when a network atlas has no animation slot. */
    private static class StaticFrameState {
        //? if <1.21.11 {
        final ResourceLocation originalAtlasLocation;
        final ResourceLocation frameTextureLocation;
        //?} else {
        final Identifier originalAtlasLocation;
        final Identifier frameTextureLocation;
        //?}
        final int pixels;
        private final DynamicTexture frameTexture;

        //? if <1.21.11 {
        StaticFrameState(String animationId, ResourceLocation atlasLocation,
        //?} else {
        StaticFrameState(String animationId, Identifier atlasLocation,
        //?}
                         NativeImage firstFramePixels) {
            this.originalAtlasLocation = atlasLocation;
            this.pixels = Math.multiplyExact(
                    firstFramePixels.getWidth(), firstFramePixels.getHeight());
            DynamicTexture createdTexture = null;
            //? if <1.21.11 {
            ResourceLocation createdLocation = null;
            //?} else {
            Identifier createdLocation = null;
            //?}
            boolean registered = false;
            try {
                //? if <1.21.11 {
                createdTexture = new DynamicTexture(firstFramePixels);
                //?} else {
                createdTexture = new DynamicTexture(
                        () -> "quickskin_static_anim_" + animationId, firstFramePixels);
                //?}
                String texId = "quickskin/animated_static/"
                        + animationId.replaceAll("[^a-zA-Z0-9/._-]", "_");
                //? if <1.21.11 {
                createdLocation = Minecraft.getInstance().getTextureManager()
                        .register(texId, createdTexture);
                //?} else {
                createdLocation = Identifier.parse(texId);
                Minecraft.getInstance().getTextureManager().register(
                        createdLocation, createdTexture);
                //?}
                registered = true;
            } catch (RuntimeException | LinkageError error) {
                if (registered && createdLocation != null) {
                    try {
                        Minecraft.getInstance().getTextureManager().release(createdLocation);
                    } catch (RuntimeException ignored) {
                        if (createdTexture != null) createdTexture.close();
                    }
                } else if (createdTexture != null) {
                    createdTexture.close();
                } else {
                    firstFramePixels.close();
                }
                throw error;
            }
            this.frameTexture = createdTexture;
            this.frameTextureLocation = createdLocation;
        }

        void cleanup() {
            try {
                Minecraft.getInstance().getTextureManager().release(frameTextureLocation);
            } catch (RuntimeException | LinkageError releaseError) {
                try {
                    frameTexture.close();
                } catch (RuntimeException | LinkageError closeError) {
                    releaseError.addSuppressed(closeError);
                }
                throw releaseError;
            }
        }
    }

    // Map of animation ID -> animation state
    private final Map<String, AnimationState> animations = new ConcurrentHashMap<>();
    // Reverse lookup: atlas texture location -> animation ID for O(1) getAnimationFrame()
    //? if <1.21.11 {
    private final Map<ResourceLocation, String> atlasToAnimId = new ConcurrentHashMap<>();
    //?} else {
    private final Map<Identifier, String> atlasToAnimId = new ConcurrentHashMap<>();
    //?}
    // Token each async load so cleanup/re-registration cannot let an old session commit later.
    private final Map<String, Long> pendingRegistrations = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, StaticFrameState> staticFirstFrames =
            new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, Long> activationAttempts =
            new LinkedHashMap<>(16, 0.75f, true);
    private final AnimationSlotPolicy slotPolicy = new AnimationSlotPolicy(
            MAX_ANIMATIONS, TextureTransferLimits.MAX_CLIENT_CACHE_ENTRIES,
            VISIBILITY_GRACE_TICKS);
    private final AtomicLong registrationSequence = new AtomicLong();
    private final AtomicLong retainedPendingSourceBytes = new AtomicLong();
    private long retainedAtlasBytes;
    private long retainedStaticFramePixels;
    private int tickCursor;

    private AnimatedTextureManager() {
        // Private constructor for singleton
    }

    public static AnimatedTextureManager getInstance() {
        if (instance == null) {
            instance = new AnimatedTextureManager();
        }
        return instance;
    }

    /**
     * Compatibility entry point. Conversion is always delegated to the bounded worker.
     */
    //? if <1.21.11 {
    public void registerAnimation(String animationId, String capeId, ResourceLocation textureLocation,
    //?} else {
    public void registerAnimation(String animationId, String capeId, Identifier textureLocation,
    //?}
                                  BufferedImage atlasImage, AnimationMetadata metadata) {
        registerAnimationAsync(
                animationId, capeId, textureLocation, atlasImage, metadata);
    }

    /**
     * Register an animated texture asynchronously.
     * Performs disk I/O and pixel conversion on a background thread,
     * then commits the GL resources on the main thread.
     * The static first-frame texture is shown until the animation is ready.
     *
     * @param animationId     Unique animation ID
     * @param capeId          Cape ID for speed settings
     * @param textureLocation Atlas texture location (reverse lookup key)
     * @param hash            Asset hash for loading from LocalAssetManager
     */
    public void registerAnimationAsync(String animationId, String capeId,
                                       //? if <1.21.11 {
                                       ResourceLocation textureLocation, String hash) {
                                       //?} else {
                                       Identifier textureLocation, String hash) {
                                       //?}
        registerAnimationAsyncInternal(animationId, capeId, textureLocation, () ->
                new AnimationSource(
                        LocalAssetManager.getInstance().getSourceImage(hash),
                        LocalAssetManager.getInstance().getAnimationMetadata(hash)), 0L, false);
    }

    //? if <1.21.11 {
    public void registerAnimationAsync(String animationId, String capeId,
                                       ResourceLocation textureLocation, BufferedImage atlasImage,
                                       AnimationMetadata metadata) {
    //?} else {
    public void registerAnimationAsync(String animationId, String capeId,
                                       Identifier textureLocation, BufferedImage atlasImage,
                                       AnimationMetadata metadata) {
    //?}
        if (!isValidAnimationAtlas(atlasImage, metadata)) return;
        AnimationSource source = new AnimationSource(atlasImage, copyMetadata(metadata));
        registerAnimationAsyncInternal(
                animationId, capeId, textureLocation, () -> source,
                decodedBytes(atlasImage), isCachedNetworkAnimation(animationId, capeId));
    }

    private record AnimationSource(BufferedImage atlasImage, AnimationMetadata metadata) {
    }

    private static boolean isCachedNetworkAnimation(String animationId, String capeId) {
        String hash = CapeAnimationIds.localHash(capeId);
        if (hash == null) return false;
        return NetworkSecurity.isValidContentId(hash)
                && java.util.Objects.equals(
                        CapeAnimationIds.deriveAnimationId(capeId), animationId)
                && ClientAnimationMetadataCache.getInstance().hasMetadata(hash)
                && NetworkTextureCache.getInstance().containsTexture(hash, "cape");
    }

    //? if <1.21.11 {
    private void registerAnimationAsyncInternal(
            String animationId, String capeId, ResourceLocation textureLocation,
            Supplier<AnimationSource> sourceSupplier, long retainedSourceBytes,
            boolean retainStaticFirstFrame) {
    //?} else {
    private void registerAnimationAsyncInternal(
            String animationId, String capeId, Identifier textureLocation,
            Supplier<AnimationSource> sourceSupplier, long retainedSourceBytes,
            boolean retainStaticFirstFrame) {
    //?}
        if (animationId == null || animationId.isEmpty() || animationId.length() > 128
                || capeId == null || textureLocation == null || sourceSupplier == null) {
            return;
        }
        if (retainedSourceBytes < 0 || retainedSourceBytes > 64L * 1024L * 1024L
                || (retainedSourceBytes > 0 && !reservePendingSourceBytes(retainedSourceBytes))) {
            return;
        }
        AtomicLong reservedBytes = new AtomicLong(retainedSourceBytes);
        AtomicBoolean reservationReleased = new AtomicBoolean();
        AtomicBoolean mainThreadHandoff = new AtomicBoolean();
        Runnable releaseReservation = () -> {
            if (reservationReleased.compareAndSet(false, true)) {
                retainedPendingSourceBytes.addAndGet(-reservedBytes.get());
            }
        };
        long registrationToken;
        synchronized (this) {
            AnimationSlotPolicy.Admission admission =
                    slotPolicy.plan(animationId, animations.keySet());
            boolean fallbackAlreadyPrepared = staticFirstFrames.containsKey(animationId);
            if (animations.containsKey(animationId)
                    || pendingRegistrations.containsKey(animationId)
                    || pendingRegistrations.size() >= MAX_ANIMATIONS
                    || (!retainStaticFirstFrame
                            && admission.kind() == AnimationSlotPolicy.Kind.STATIC_FALLBACK)
                    || (retainStaticFirstFrame && fallbackAlreadyPrepared
                            && admission.kind() == AnimationSlotPolicy.Kind.STATIC_FALLBACK)) {
                releaseReservation.run();
                return;
            }
            registrationToken = registrationSequence.incrementAndGet();
            pendingRegistrations.put(animationId, registrationToken);
        }

        ClientIoExecutor.runAsync(() -> {
            NativeImage atlasPixels = null;
            NativeImage firstFramePixels = null;
            try {
                if (!Long.valueOf(registrationToken).equals(
                        pendingRegistrations.get(animationId))) return;
                // Background thread: disk I/O + pixel conversion
                AnimationSource source = sourceSupplier.get();
                AnimationMetadata metadata = source != null ? source.metadata() : null;
                BufferedImage atlasImage = source != null ? source.atlasImage() : null;

                if (!isValidAnimationAtlas(atlasImage, metadata)) {
                    pendingRegistrations.remove(animationId, registrationToken);
                    return;
                }
                if (!Long.valueOf(registrationToken).equals(
                        pendingRegistrations.get(animationId))) return;
                if (reservedBytes.get() == 0L) {
                    long loadedSourceBytes = decodedBytes(atlasImage);
                    if (!reservePendingSourceBytes(loadedSourceBytes)) {
                        pendingRegistrations.remove(animationId, registrationToken);
                        return;
                    }
                    reservedBytes.set(loadedSourceBytes);
                }
                metadata = copyMetadata(metadata);

                if (!Long.valueOf(registrationToken).equals(
                        pendingRegistrations.get(animationId))) return;
                atlasPixels = processAtlas(atlasImage, metadata);
                if (atlasPixels == null) {
                    pendingRegistrations.remove(animationId, registrationToken);
                    return;
                }

                if (!Long.valueOf(registrationToken).equals(pendingRegistrations.get(animationId))) {
                    atlasPixels.close();
                    atlasPixels = null;
                    return;
                }

                int effectiveFrameCount = Math.min(metadata.frameCount(), MAX_ANIM_FRAMES);
                int targetWidth = Math.min(atlasImage.getWidth(), MAX_ANIM_FRAME_WIDTH);
                int srcFrameHeight = atlasImage.getHeight() / metadata.frameCount();
                int targetHeight = Math.min(srcFrameHeight, MAX_ANIM_FRAME_HEIGHT);
                if (retainStaticFirstFrame) {
                    firstFramePixels = copyFirstFrame(atlasPixels, targetWidth, targetHeight);
                }

                AnimationMetadata effectiveMeta = metadata;
                if (effectiveFrameCount < metadata.frameCount()) {
                    effectiveMeta = new AnimationMetadata(
                            metadata.frames().subList(0, effectiveFrameCount), effectiveFrameCount);
                }

                float speedMultiplier = ClientConfig.getInstance().getCapeAnimationSpeed(capeId);
                if (!Float.isFinite(speedMultiplier)) speedMultiplier = 1.0f;

                // Capture for lambda
                final NativeImage finalAtlas = atlasPixels;
                final NativeImage finalFirstFrame = firstFramePixels;
                final int fw = targetWidth, fh = targetHeight;
                final AnimationMetadata fm = effectiveMeta;
                final float sm = speedMultiplier;

                // Main thread: GL operations (DynamicTexture creation + register)
                Minecraft.getInstance().execute(() -> {
                    try {
                        // Skip if a sync registration happened while we were loading
                        if (!Long.valueOf(registrationToken).equals(
                                pendingRegistrations.get(animationId))
                                || animations.containsKey(animationId)) {
                            finalAtlas.close();
                            if (finalFirstFrame != null) finalFirstFrame.close();
                            return;
                        }
                        commitAnimation(
                                animationId, textureLocation, finalAtlas, finalFirstFrame,
                                fw, fh, fm, sm, retainStaticFirstFrame);
                    } catch (RuntimeException | LinkageError e) {
                        QuickSkin.LOGGER.warn(
                                "Unable to commit animated texture {}", animationId, e);
                    } finally {
                        pendingRegistrations.remove(animationId, registrationToken);
                        releaseReservation.run();
                    }
                });
                atlasPixels = null; // Ownership transferred to execute() callback
                firstFramePixels = null;
                mainThreadHandoff.set(true);

            } catch (RuntimeException | LinkageError e) {
                if (atlasPixels != null) {
                    atlasPixels.close();
                    atlasPixels = null;
                }
                if (firstFramePixels != null) {
                    firstFramePixels.close();
                    firstFramePixels = null;
                }
                pendingRegistrations.remove(animationId, registrationToken);
            } finally {
                if (!mainThreadHandoff.get() && atlasPixels != null) {
                    atlasPixels.close();
                }
                if (!mainThreadHandoff.get() && firstFramePixels != null) {
                    firstFramePixels.close();
                }
                if (!mainThreadHandoff.get()) releaseReservation.run();
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                releaseReservation.run();
                pendingRegistrations.remove(animationId, registrationToken);
                QuickSkin.LOGGER.warn("Unable to schedule animated texture {}", animationId, error);
            }
        });
    }

    /**
     * Process atlas image: apply resolution/frame limits and convert to NativeImage.
     * Safe to call from any thread.
     */
    private static boolean isValidAnimationAtlas(
            BufferedImage atlasImage, AnimationMetadata metadata) {
        if (atlasImage == null || metadata == null || metadata.frames() == null) return false;
        int frameCount = metadata.frameCount();
        if (frameCount <= 1 || frameCount > MAX_ANIM_FRAMES
                || metadata.frames().size() != frameCount
                || atlasImage.getWidth() < 1 || atlasImage.getHeight() < frameCount
                || atlasImage.getHeight() % frameCount != 0
                || (long) atlasImage.getWidth() * atlasImage.getHeight() * 4L
                        > 64L * 1024L * 1024L) {
            return false;
        }
        boolean[] indexes = new boolean[frameCount];
        for (AnimationMetadata.FrameData frame : metadata.frames()) {
            if (frame == null || frame.delay() < 20 || frame.delay() > 60_000
                    || frame.index() < 0 || frame.index() >= frameCount
                    || indexes[frame.index()]) {
                return false;
            }
            indexes[frame.index()] = true;
        }
        return true;
    }

    private static long decodedBytes(BufferedImage atlasImage) {
        return atlasImage == null ? 0L
                : (long) atlasImage.getWidth() * atlasImage.getHeight() * 4L;
    }

    private boolean reservePendingSourceBytes(long bytes) {
        if (bytes <= 0L || bytes > 64L * 1024L * 1024L) return false;
        long current;
        do {
            current = retainedPendingSourceBytes.get();
            if (current > MAX_RETAINED_ATLAS_BYTES - bytes) return false;
        } while (!retainedPendingSourceBytes.compareAndSet(current, current + bytes));
        return true;
    }

    private static AnimationMetadata copyMetadata(AnimationMetadata metadata) {
        return new AnimationMetadata(List.copyOf(metadata.frames()), metadata.frameCount());
    }

    private static NativeImage processAtlas(BufferedImage atlasImage, AnimationMetadata metadata) {
        int effectiveFrameCount = Math.min(metadata.frameCount(), MAX_ANIM_FRAMES);
        int srcFrameWidth = atlasImage.getWidth();
        int srcFrameHeight = atlasImage.getHeight() / metadata.frameCount();

        atlasImage = CapeElytraSilhouette.maskedCopy(atlasImage, metadata.frameCount());

        boolean needsDownscale = srcFrameWidth > MAX_ANIM_FRAME_WIDTH || srcFrameHeight > MAX_ANIM_FRAME_HEIGHT;
        boolean needsTruncate = effectiveFrameCount < metadata.frameCount();

        int targetWidth = Math.min(srcFrameWidth, MAX_ANIM_FRAME_WIDTH);
        int targetHeight = Math.min(srcFrameHeight, MAX_ANIM_FRAME_HEIGHT);

        if (needsDownscale || needsTruncate) {
            BufferedImage processedAtlas = new BufferedImage(
                    targetWidth, targetHeight * effectiveFrameCount, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = processedAtlas.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < effectiveFrameCount; i++) {
                g.drawImage(atlasImage,
                        0, i * targetHeight, targetWidth, (i + 1) * targetHeight,
                        0, i * srcFrameHeight, srcFrameWidth, (i + 1) * srcFrameHeight,
                        null);
            }
            g.dispose();
            return convertToNativeImage(processedAtlas);
        } else {
            return convertToNativeImage(atlasImage);
        }
    }

    /**
     * Commit a prepared animation to the maps and create GL resources.
     * Must be called on the main/render thread.
     */
    //? if <1.21.11 {
    private synchronized void commitAnimation(String animationId, ResourceLocation textureLocation,
    //?} else {
    private synchronized void commitAnimation(String animationId, Identifier textureLocation,
    //?}
                                 NativeImage atlasPixels, NativeImage firstFramePixels,
                                 int frameWidth, int frameHeight, AnimationMetadata metadata,
                                 float speedMultiplier, boolean retainStaticFirstFrame) {
        if (retainStaticFirstFrame && firstFramePixels != null) {
            commitStaticFirstFrame(animationId, textureLocation, firstFramePixels);
            firstFramePixels = null;
        } else if (firstFramePixels != null) {
            firstFramePixels.close();
            firstFramePixels = null;
        }

        String mappedAnimation = atlasToAnimId.get(textureLocation);
        if (animations.containsKey(animationId)
                || (mappedAnimation != null && !mappedAnimation.equals(animationId))) {
            atlasPixels.close();
            return;
        }

        AnimationSlotPolicy.Admission admission =
                slotPolicy.plan(animationId, animations.keySet());
        if (admission.kind() == AnimationSlotPolicy.Kind.REPLACE_STALE) {
            removeActiveAnimation(admission.victimId());
        } else if (admission.kind() == AnimationSlotPolicy.Kind.STATIC_FALLBACK) {
            atlasPixels.close();
            return;
        }

        long atlasBytes = (long) atlasPixels.getWidth() * atlasPixels.getHeight() * 4L;
        if (animations.size() >= MAX_ANIMATIONS || atlasBytes <= 0
                || retainedAtlasBytes + atlasBytes > MAX_RETAINED_ATLAS_BYTES) {
            atlasPixels.close();
            return;
        }
        AnimationState state;
        try {
            state = new AnimationState(
                    animationId, textureLocation, atlasPixels,
                    frameWidth, frameHeight, metadata, speedMultiplier);
        } catch (RuntimeException | LinkageError error) {
            atlasPixels.close();
            QuickSkin.LOGGER.warn("Unable to create animated texture {}", animationId, error);
            return;
        }
        boolean committed = false;
        try {
            animations.put(animationId, state);
            atlasToAnimId.put(textureLocation, animationId);
            retainedAtlasBytes += atlasBytes;
            committed = true;
        } finally {
            if (!committed) {
                animations.remove(animationId, state);
                if (!staticFirstFrames.containsKey(animationId)) {
                    atlasToAnimId.remove(textureLocation, animationId);
                }
                try {
                    state.cleanup();
                } catch (RuntimeException | LinkageError cleanupError) {
                    QuickSkin.LOGGER.warn("Unable to roll back animated texture {}", animationId,
                            cleanupError);
                }
            }
        }
    }

    //? if <1.21.11 {
    private void commitStaticFirstFrame(
            String animationId, ResourceLocation textureLocation, NativeImage firstFramePixels) {
    //?} else {
    private void commitStaticFirstFrame(
            String animationId, Identifier textureLocation, NativeImage firstFramePixels) {
    //?}
        StaticFrameState existing = staticFirstFrames.get(animationId);
        if (existing != null) {
            firstFramePixels.close();
            return;
        }
        long pixels = (long) firstFramePixels.getWidth() * firstFramePixels.getHeight();
        if (pixels <= 0L || pixels > MAX_STATIC_FALLBACK_PIXELS) {
            firstFramePixels.close();
            return;
        }
        String mappedAnimation = atlasToAnimId.get(textureLocation);
        if (mappedAnimation != null && !mappedAnimation.equals(animationId)) {
            firstFramePixels.close();
            return;
        }
        while (staticFirstFrames.size() >= TextureTransferLimits.MAX_CLIENT_CACHE_ENTRIES
                || retainedStaticFramePixels > MAX_STATIC_FALLBACK_PIXELS - pixels) {
            String victimId = oldestStaticFallbackId();
            if (victimId == null) {
                firstFramePixels.close();
                return;
            }
            removeStaticFirstFrame(victimId);
        }
        try {
            StaticFrameState state = new StaticFrameState(
                    animationId, textureLocation, firstFramePixels);
            staticFirstFrames.put(animationId, state);
            atlasToAnimId.put(textureLocation, animationId);
            retainedStaticFramePixels += state.pixels;
        } catch (RuntimeException | LinkageError error) {
            QuickSkin.LOGGER.warn(
                    "Unable to create static first frame for animation {}", animationId, error);
        }
    }

    @Nullable
    private String oldestStaticFallbackId() {
        String activeFallback = null;
        for (String animationId : staticFirstFrames.keySet()) {
            if (!animations.containsKey(animationId)) return animationId;
            if (activeFallback == null) activeFallback = animationId;
        }
        return activeFallback;
    }

    private void removeActiveAnimation(String animationId) {
        if (animationId == null) return;
        AnimationState removed = animations.remove(animationId);
        if (removed == null) return;
        retainedAtlasBytes = Math.max(0L, retainedAtlasBytes
                - (long) removed.atlasPixels.getWidth() * removed.atlasPixels.getHeight() * 4L);
        if (!staticFirstFrames.containsKey(animationId)) {
            atlasToAnimId.remove(removed.originalAtlasLocation, animationId);
        }
        try {
            removed.cleanup();
        } catch (RuntimeException | LinkageError error) {
            QuickSkin.LOGGER.warn("Unable to release animated texture {}", animationId, error);
        }
    }

    private void removeStaticFirstFrame(String animationId) {
        StaticFrameState removed = staticFirstFrames.remove(animationId);
        if (removed == null) return;
        retainedStaticFramePixels = Math.max(0L,
                retainedStaticFramePixels - removed.pixels);
        if (!animations.containsKey(animationId)) {
            atlasToAnimId.remove(removed.originalAtlasLocation, animationId);
        }
        try {
            removed.cleanup();
        } catch (RuntimeException | LinkageError error) {
            QuickSkin.LOGGER.warn(
                    "Unable to release static animation frame {}", animationId, error);
        }
    }

    /**
     * Clear all animations (for texture cache reload)
     */
    public synchronized void clearAnimations() {
        pendingRegistrations.clear();
        for (AnimationState state : animations.values()) {
            try {
                state.cleanup();
            } catch (RuntimeException | LinkageError error) {
                QuickSkin.LOGGER.warn("Unable to release an animated texture", error);
            }
        }
        animations.clear();
        for (StaticFrameState state : staticFirstFrames.values()) {
            try {
                state.cleanup();
            } catch (RuntimeException | LinkageError error) {
                QuickSkin.LOGGER.warn("Unable to release a static animation frame", error);
            }
        }
        staticFirstFrames.clear();
        atlasToAnimId.clear();
        retainedAtlasBytes = 0;
        retainedStaticFramePixels = 0;
        activationAttempts.clear();
        slotPolicy.clear();
    }

    /**
     * Unregister an animated texture
     */
    public synchronized void unregisterAnimation(String animationId) {
        pendingRegistrations.remove(animationId);
        removeActiveAnimation(animationId);
        removeStaticFirstFrame(animationId);
        activationAttempts.remove(animationId);
        slotPolicy.forget(animationId);
    }

    /**
     * Update the animation speed for a registered animation
     */
    public void setAnimationSpeed(String animationId, float speed) {
        if (!Float.isFinite(speed) || speed < 0.0f) {
            return;
        }
        AnimationState state = animations.get(animationId);
        if (state != null) {
            state.setSpeedMultiplier(speed);
        }
    }

    /**
     * Pin one exact active frame and upload it immediately.
     *
     * <p>This is used by deterministic visual evidence and is also useful to render a paused
     * animation without waiting for wall-clock playback. It deliberately does not alter the atlas,
     * metadata, or configured speed; callers that need the frame to stay pinned set speed to zero.</p>
     *
     * @return true only when the requested frame was installed on the active GPU texture
     */
    public synchronized boolean setAnimationFrame(String animationId, int frame) {
        if (animationId == null) return false;
        AnimationState state = animations.get(animationId);
        if (state == null || frame < 0 || frame >= state.metadata.frameCount()) return false;
        if (state.currentFrame == frame) return true;
        NativeImage pixels = state.frameTexture.getPixels();
        if (pixels == null) return false;
        state.copyFrameTo(pixels, frame);
        state.currentFrame = frame;
        state.frameTexture.upload();
        return true;
    }

    /**
     * Check if an animation is registered or currently being loaded asynchronously.
     * Callers use this to avoid redundant registration attempts.
     */
    public boolean isAnimated(String animationId) {
        return animations.containsKey(animationId) || pendingRegistrations.containsKey(animationId);
    }

    /** Records that a render path is actually using this animation candidate. */
    public synchronized void markAnimationVisible(String animationId) {
        slotPolicy.markVisible(animationId);
    }

    /**
     * Returns true at a bounded retry cadence when a visible network cape needs its first frame
     * or can replace an animation that has been offscreen beyond the grace window.
     */
    public synchronized boolean shouldRequestActivation(String animationId) {
        if (animationId == null || animationId.isEmpty()
                || animations.containsKey(animationId)
                || pendingRegistrations.containsKey(animationId)) return false;
        AnimationSlotPolicy.Admission admission =
                slotPolicy.plan(animationId, animations.keySet());
        if (staticFirstFrames.containsKey(animationId)
                && admission.kind() == AnimationSlotPolicy.Kind.STATIC_FALLBACK) return false;
        long now = slotPolicy.currentTick();
        Long attemptedAt = activationAttempts.get(animationId);
        if (attemptedAt != null && now >= attemptedAt
                && now - attemptedAt < ACTIVATION_RETRY_TICKS) return false;
        activationAttempts.put(animationId, now);
        while (activationAttempts.size() > TextureTransferLimits.MAX_CLIENT_CACHE_ENTRIES) {
            activationAttempts.remove(activationAttempts.keySet().iterator().next());
        }
        return true;
    }

    /**
     * Gets the Identifier for the current frame of an animation.
     * With the optimized architecture, this always returns the same Identifier
     * (the texture is updated in-place via upload()).
     */
    @Nullable
    //? if <1.21.11 {
    public synchronized ResourceLocation getCurrentFrameTexture(String animationId) {
    //?} else {
    public synchronized Identifier getCurrentFrameTexture(String animationId) {
    //?}
        AnimationState state = animations.get(animationId);
        if (state != null) {
            return state.getCurrentFrameTexture();
        }
        StaticFrameState fallback = staticFirstFrames.get(animationId);
        return fallback == null ? null : fallback.frameTextureLocation;
    }

    /**
     * Get the original atlas texture location for an animation
     */
    //? if <1.21.11 {
    public synchronized ResourceLocation getTextureLocation(String animationId) {
    //?} else {
    public synchronized Identifier getTextureLocation(String animationId) {
    //?}
        AnimationState state = animations.get(animationId);
        if (state != null) return state.originalAtlasLocation;
        StaticFrameState fallback = staticFirstFrames.get(animationId);
        return fallback == null ? null : fallback.originalAtlasLocation;
    }

    /**
     * Get animation metadata
     */
    public AnimationMetadata getMetadata(String animationId) {
        AnimationState state = animations.get(animationId);
        if (state == null) {
            return null;
        }
        return state.metadata;
    }

    /**
     * Checks if a given texture atlas corresponds to a running animation, and if so,
     * returns the Identifier of the current animation frame.
     * Uses O(1) reverse lookup instead of iterating all animations.
     */
    //? if <1.21.11 {
    public synchronized Optional<ResourceLocation> getAnimationFrame(ResourceLocation atlasLocation) {
    //?} else {
    public synchronized Optional<Identifier> getAnimationFrame(Identifier atlasLocation) {
    //?}
        if (atlasLocation == null) {
            return Optional.empty();
        }

        // O(1) reverse lookup
        String animId = atlasToAnimId.get(atlasLocation);
        if (animId == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(getCurrentFrameTexture(animId));
    }

    /**
     * Tick all animations (called each game tick).
     * Only uploads to GPU when the frame actually changes.
     */
    public void tick() {
        List<AnimationState> snapshot;
        synchronized (this) {
            slotPolicy.advanceTick();
            snapshot = List.copyOf(animations.values());
        }
        if (snapshot.isEmpty()) {
            tickCursor = 0;
            return;
        }
        int start = Math.floorMod(tickCursor, snapshot.size());
        int visited = 0;
        long remainingPixels = MAX_FRAME_PIXELS_PER_TICK;
        while (visited < snapshot.size() && remainingPixels > 0L) {
            AnimationState state = snapshot.get((start + visited) % snapshot.size());
            remainingPixels -= state.tick(remainingPixels);
            visited++;
        }
        tickCursor = (start + Math.max(1, visited)) % snapshot.size();
    }

    /**
     * Convert a BufferedImage to NativeImage using direct pixel copy.
     * Avoids the expensive PNG encode/decode round-trip.
     * Safe to call from any thread.
     */
    private static NativeImage convertToNativeImage(BufferedImage bufferedImage) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        NativeImage nativeImage = new NativeImage(width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = bufferedImage.getRGB(x, y);
                    // Convert ARGB to ABGR (NativeImage pixel format)
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    MinecraftCompat.INSTANCE.setPixel(nativeImage, x, y, abgr);
                }
            }
            return nativeImage;
        } catch (RuntimeException | LinkageError error) {
            nativeImage.close();
            throw error;
        }
    }

    private static NativeImage copyFirstFrame(
            NativeImage atlasPixels, int frameWidth, int frameHeight) {
        NativeImage firstFrame = new NativeImage(frameWidth, frameHeight, false);
        try {
            for (int y = 0; y < frameHeight; y++) {
                for (int x = 0; x < frameWidth; x++) {
                    MinecraftCompat.INSTANCE.setPixel(firstFrame, x, y,
                            MinecraftCompat.INSTANCE.getPixel(atlasPixels, x, y));
                }
            }
            return firstFrame;
        } catch (RuntimeException | LinkageError error) {
            firstFrame.close();
            throw error;
        }
    }
}
