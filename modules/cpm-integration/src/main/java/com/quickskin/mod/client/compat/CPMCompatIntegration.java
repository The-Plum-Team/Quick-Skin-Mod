package com.quickskin.mod.client.compat;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.api.CpmAssetAccess;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <1.21.4 {
import net.minecraft.client.renderer.texture.HttpTexture;
//?}
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional compatibility bridge for Customizable Player Models (CPM).
 *
 * <p>All third-party lookups are guarded by loader/resource detection and are
 * cached after activation. A missing optional CPM handle disables only the
 * operation that needs that handle; model discovery and parsing remain usable.</p>
 */
@Environment(EnvType.CLIENT)
public final class CPMCompatIntegration {
    private static volatile CpmAssetAccess assetAccess;

    /** Bound by the client composition root before catalog scanning or packet registration. */
    public static synchronized void configureAssets(CpmAssetAccess access) {
        if (assetAccess != null) throw new IllegalStateException("CPM asset access is already configured");
        assetAccess = java.util.Objects.requireNonNull(access, "access");
    }

    static CpmAssetAccess assets() {
        return java.util.Objects.requireNonNull(assetAccess, "CPM asset access has not been configured");
    }

    static boolean assetsConfigured() {
        return assetAccess != null;
    }

    private static final Logger CPMLOG = LoggerFactory.getLogger("QuickSkin-CPM");
    private static final String CPM_CLIENT_RESOURCE = "com/tom/cpm/client/CustomPlayerModelsClient.class";
    private static final long STALE_RENDER_DEPTH_NANOS = 2_000_000_000L;
    private static final long FIRST_PERSON_MODEL_PROBE_TTL_NANOS = 500_000_000L;
    private static final long PLAYER_MODEL_PROBE_TTL_NANOS = 100_000_000L;
    private static final int MAX_PLAYER_MODEL_PROBES = 256;
    private static final int MAX_MODEL_TEXT_BYTES = 1_048_576;
    private static final int MAX_MODEL_ICON_BYTES = 16_777_216;
    /** CPM reads and lazily creates these roots from its parallel model-loading pool. */
    private static final String[] BACKGROUND_CONFIG_ROOTS = {
            "globalSettings",
            "friendSettings",
            "serverSettings",
            "safetyProfiles",
            "friendList",
            "blockedList"
    };

    private static volatile boolean checked;
    private static volatile boolean modAvailable;
    private static volatile long nextRuntimeReflectionRetryNanos;
    private static volatile long nextConfigReflectionRetryNanos;
    private static volatile boolean backgroundConfigPrepared;
    private static volatile boolean cachedFirstPersonModelActive;
    private static volatile long nextFirstPersonModelProbeNanos;

    // Independently optional, cached reflection handles.
    private static Object loaderInstance;
    private static Method clearCacheMethod;
    private static Object configInstance;
    private static Method configGetStringMethod;
    private static Method configSetStringMethod;
    private static Method configClearValueMethod;
    private static Method configSaveMethod;
    private static Object minecraftClientAccess;
    private static Method getDefinitionLoaderMethod;
    private static Method getCurrentClientPlayerMethod;
    private static Method getServerSideStatusMethod;
    private static Method sendSkinUpdateMethod;
    private static Method executeNextFrameMethod;
    private static Method getModelDefinitionMethod;
    private static Method getPlayerUuidMethod;
    private static Method getLoadedPlayerMethod;
    private static Method getModelDefinition0Method;
    private static Method doRenderMethod;
    private static Method getServerModelMethod;
    private static java.lang.reflect.Field forcedSkinField;

    private static final AtomicBoolean cacheUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean cacheInvocationFailedLogged = new AtomicBoolean();
    private static final AtomicBoolean loaderReflectionUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean runtimeReflectionUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean serverReflectionUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean configUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean configReflectionUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean backgroundConfigPreparedLogged = new AtomicBoolean();
    private static final AtomicBoolean degradedBridgeLogged = new AtomicBoolean();
    private static final AtomicBoolean renderHookObservedLogged = new AtomicBoolean();
    private static final AtomicBoolean staleRenderDepthLogged = new AtomicBoolean();
    private static final AtomicBoolean localModelProbeFailedLogged = new AtomicBoolean();
    private static final AtomicBoolean playerModelProbeFailedLogged = new AtomicBoolean();
    private static final AtomicBoolean serverModelLookupFailedLogged = new AtomicBoolean();
    private static final AtomicBoolean cacheInvalidationQueued = new AtomicBoolean();
    private static final AtomicBoolean cacheSchedulingFailedLogged = new AtomicBoolean();
    private static final AtomicBoolean skinModeResetQueued = new AtomicBoolean();
    private static final AtomicBoolean skinModeResetApplied = new AtomicBoolean();
    private static final AtomicInteger skinModeResetFrameBoundaries = new AtomicInteger();
    private static final AtomicLong firstPersonRenderTypePreservations = new AtomicLong();
    private static final AtomicBoolean staleSubmissionDroppedLogged = new AtomicBoolean();
    private static final AtomicBoolean deferredResetLogged = new AtomicBoolean();
    private static volatile boolean localModelProbeDisabled;
    private static volatile boolean playerModelProbeDisabled;
    private static volatile boolean cacheInvalidationDisabled;

    /** Render activity is bracketed by the optional CPM-targeting mixin. */
    private static final ThreadLocal<Integer> renderDepth = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Long> renderDepthTouchedAt = ThreadLocal.withInitial(() -> 0L);

    /** Short-lived answers of {@link #isWearingCpmModel}, per player. */
    private static final Map<java.util.UUID, PlayerModelProbe> playerModelProbes = new ConcurrentHashMap<>();

    private record PlayerModelProbe(boolean wearing, long expiresAtNanos) {
    }

    //? if <1.21.4 {
    private static final Map<String, ResourceLocation> httpTextureCache = new ConcurrentHashMap<>();
    //?}

    private CPMCompatIntegration() {
    }

    /** Returns whether CPM is installed without resolving CPM classes when absent. */
    public static boolean isAvailable() {
        if (!checked) {
            synchronized (CPMCompatIntegration.class) {
                if (!checked) {
                    checkAvailability();
                }
            }
        }
        return modAvailable;
    }

    public static CpmCapabilities.Capabilities getCapabilities() {
        return CpmCapabilities.current();
    }

    /**
     * Retries the early config preparation after every mod entry point has initialized. Client
     * ticks begin before a player can join a world and start CPM's parallel definition loader.
     */
    public static void prepareForBackgroundModelLoading() {
        if (!isAvailable() || backgroundConfigPrepared) {
            return;
        }
        hasConfigHandles();
    }

    private static void checkAvailability() {
        boolean loaderReported = PlatformHelper.isModLoaded("cpm");
        boolean resourcePresent = loaderReported || classFileExists(CPM_CLIENT_RESOURCE);
        modAvailable = resourcePresent;
        checked = true;

        if (!resourcePresent) {
            return;
        }

        // Quick Skin initializes its asset catalog before a world can start CPM's parallel model
        // loader. Materialize the config roots that loader otherwise creates lazily so selecting a
        // model cannot structurally mutate CPM's TreeMap at the same time as a safety check.
        initializeConfigReflection();

        CpmCapabilities.Band band = CpmCapabilities.currentBand();
        CpmCapabilities.Capabilities capabilities = CpmCapabilities.current();
        CPMLOG.info(
                "CPM integration activating for Minecraft {}: modelWorkflow={}, embeddedPngBridge={}, "
                        + "entityPreview={}, renderPipeline={}, loaderReported={}",
                band.displayName(),
                capabilities.modelWorkflow(),
                capabilities.embeddedPngBridge(),
                capabilities.entityPreview(),
                capabilities.renderPipeline(),
                loaderReported
        );
        if (capabilities.embeddedPngBridge() == CpmCapabilities.Availability.DEGRADED) {
            CPMLOG.warn(
                    "CPM embedded-PNG bridging is DEGRADED on Minecraft {}; explicit .cpmmodel "
                            + "discovery, import, selection, persistence, and entity preview remain AVAILABLE",
                    band.displayName()
            );
            degradedBridgeLogged.set(true);
        }
    }

    private static boolean classFileExists(String classFilePath) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null && contextLoader.getResource(classFilePath) != null) {
            return true;
        }
        ClassLoader ownLoader = CPMCompatIntegration.class.getClassLoader();
        return ownLoader != null && ownLoader.getResource(classFilePath) != null;
    }

    private static void initializeLoaderReflection() {
        try {
            if (minecraftClientAccess == null || getDefinitionLoaderMethod == null) {
                throw new IllegalStateException("CPM MinecraftClientAccess is not initialized yet");
            }
            loaderInstance = getDefinitionLoaderMethod.invoke(minecraftClientAccess);
            if (loaderInstance == null) {
                throw new IllegalStateException("CPM ModelDefinitionLoader is null");
            }
            clearCacheMethod = loaderInstance.getClass().getMethod("clearCache");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            loaderInstance = null;
            clearCacheMethod = null;
            if (loaderReflectionUnavailableLogged.compareAndSet(false, true)) {
                CPMLOG.warn(
                        "CPM model-cache reflection is not ready; model selection still works and QuickSkin will retry "
                                + "the cache/activity bridge lazily",
                        e
                );
            }
            return;
        }
        try {
            // Erased ModelDefinitionLoader<GP>.getLoadedPlayer(GP): a cache lookup that never loads.
            getLoadedPlayerMethod = loaderInstance.getClass().getMethod("getLoadedPlayer", Object.class);
            getModelDefinition0Method = Class.forName("com.tom.cpm.shared.config.Player")
                    .getMethod("getModelDefinition0");
            doRenderMethod = Class.forName("com.tom.cpm.shared.definition.ModelDefinition")
                    .getMethod("doRender");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getLoadedPlayerMethod = null;
            getModelDefinition0Method = null;
            doRenderMethod = null;
            playerModelProbeDisabled = true;
            if (playerModelProbeFailedLogged.compareAndSet(false, true)) {
                CPMLOG.warn("CPM player-model lookup is unavailable; Quick Skin skins keep their renderer override", e);
            }
        }
        try {
            // Erased ModelDefinitionLoader<GP>.getModel(GP): the model data CPM's server sent.
            getServerModelMethod = loaderInstance.getClass().getMethod("getModel", Object.class);
            forcedSkinField = Class.forName("com.tom.cpm.shared.config.Player").getField("forcedSkin");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getServerModelMethod = null;
            forcedSkinField = null;
            if (serverModelLookupFailedLogged.compareAndSet(false, true)) {
                CPMLOG.warn("CPM server-model lookup is unavailable; a model the server assigns is not "
                        + "counted as a look choice", e);
            }
        }
    }

    private static void initializeConfigReflection() {
        backgroundConfigPrepared = false;
        try {
            Class<?> modConfigClass = Class.forName("com.tom.cpm.shared.config.ModConfig");
            Method getCommonConfig = modConfigClass.getMethod("getCommonConfig");
            configInstance = getCommonConfig.invoke(null);
            if (configInstance == null) {
                throw new IllegalStateException("CPM common config is null");
            }
            Class<?> configClass = configInstance.getClass();
            configGetStringMethod = configClass.getMethod("getString", String.class, String.class);
            configSetStringMethod = configClass.getMethod("setString", String.class, String.class);
            configClearValueMethod = configClass.getMethod("clearValue", String.class);
            configSaveMethod = configClass.getMethod("save");
            Method configGetEntryMethod = configClass.getMethod("getEntry", String.class);
            for (String root : BACKGROUND_CONFIG_ROOTS) {
                configGetEntryMethod.invoke(configInstance, root);
            }
            backgroundConfigPrepared = true;
            if (backgroundConfigPreparedLogged.compareAndSet(false, true)) {
                CPMLOG.info("Prepared CPM safety config before background model loading");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            configInstance = null;
            configGetStringMethod = null;
            configSetStringMethod = null;
            configClearValueMethod = null;
            configSaveMethod = null;
            backgroundConfigPrepared = false;
            if (configReflectionUnavailableLogged.compareAndSet(false, true)) {
                CPMLOG.warn(
                        "CPM selectedModel config bridge is not ready; QuickSkin will retry lazily before selection",
                        e
                );
            }
        }
    }

    private static void initializeNetworkReflection() {
        Class<?> accessClass;
        try {
            accessClass = Class.forName("com.tom.cpm.shared.MinecraftClientAccess");
            Method get = accessClass.getMethod("get");
            minecraftClientAccess = get.invoke(null);
            if (minecraftClientAccess == null) {
                throw new IllegalStateException("MinecraftClientAccess.get() returned null");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            minecraftClientAccess = null;
            getDefinitionLoaderMethod = null;
            getCurrentClientPlayerMethod = null;
            getServerSideStatusMethod = null;
            sendSkinUpdateMethod = null;
            executeNextFrameMethod = null;
            getModelDefinitionMethod = null;
            getPlayerUuidMethod = null;
            if (runtimeReflectionUnavailableLogged.compareAndSet(false, true)) {
                CPMLOG.warn(
                        "CPM client access is not ready; local model selection remains enabled and QuickSkin "
                                + "will retry lazily",
                        e
                );
            }
            return;
        }

        try {
            getDefinitionLoaderMethod = accessClass.getMethod("getDefinitionLoader");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getDefinitionLoaderMethod = null;
            if (loaderReflectionUnavailableLogged.compareAndSet(false, true)) {
                CPMLOG.warn("CPM definition-loader accessor is unavailable; cache refresh is degraded", e);
            }
        }

        try {
            getServerSideStatusMethod = accessClass.getMethod("getServerSideStatus");
            sendSkinUpdateMethod = accessClass.getMethod("sendSkinUpdate");
            executeNextFrameMethod = accessClass.getMethod("executeNextFrame", Runnable.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getServerSideStatusMethod = null;
            sendSkinUpdateMethod = null;
            executeNextFrameMethod = null;
            if (serverReflectionUnavailableLogged.compareAndSet(false, true)) {
                CPMLOG.warn("CPM server-notification accessor is unavailable; local selection remains enabled", e);
            }
        }

        try {
            getCurrentClientPlayerMethod = accessClass.getMethod("getCurrentClientPlayer");
            Class<?> playerClass = Class.forName("com.tom.cpm.shared.config.Player");
            getModelDefinitionMethod = playerClass.getMethod("getModelDefinition");
            getPlayerUuidMethod = playerClass.getMethod("getUUID");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getCurrentClientPlayerMethod = null;
            getModelDefinitionMethod = null;
            getPlayerUuidMethod = null;
            localModelProbeDisabled = true;
            if (localModelProbeFailedLogged.compareAndSet(false, true)) {
                CPMLOG.warn(
                        "CPM local-model activity accessor is unavailable; selection and cache refresh remain enabled",
                        e
                );
            }
        }
    }

    private static void ensureRuntimeHandles() {
        if (!modAvailable || (minecraftClientAccess != null && loaderInstance != null)) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextRuntimeReflectionRetryNanos) {
            return;
        }
        synchronized (CPMCompatIntegration.class) {
            now = System.nanoTime();
            if (now < nextRuntimeReflectionRetryNanos
                    || (minecraftClientAccess != null && loaderInstance != null)) {
                return;
            }
            nextRuntimeReflectionRetryNanos = now + 1_000_000_000L;
            initializeNetworkReflection();
            initializeLoaderReflection();
        }
    }

    /**
     * QuickSkin defers appearance overrides while CPM owns the UI or while an extracted model is
     * crossing the model-to-skin frame boundary. The latter keeps the old model paired with its
     * old texture until CPM can replace both together on the next frame.
     */
    public static boolean shouldDeferToCPM() {
        return isAvailable() && (isCPMScreenOpen() || skinModeResetQueued.get());
    }

    /** True only while Fabric's deferred CPM model is crossing into ordinary skin mode. */
    public static boolean isSkinModeResetInProgress() {
        return isAvailable() && skinModeResetQueued.get();
    }

    /**
     * Returns whether CPM's extracted player submission belongs to the model that was just reset.
     * The optional collector mixin drops that one stale submission; the ordinary player is
     * extracted again on the following frame with Quick Skin's selected texture.
     */
    public static boolean shouldSuppressStaleSubmission() {
        boolean suppress = isAvailable()
                && skinModeResetQueued.get()
                && skinModeResetApplied.get();
        if (suppress) {
            if (staleSubmissionDroppedLogged.compareAndSet(false, true)) {
                CPMLOG.info("Discarded CPM's stale extracted player submission during skin-mode reset");
            }
        }
        return suppress;
    }

    /**
     * Releases the transition only after two render callbacks following CPM's reset. On the
     * extracted pipeline the HUD callback still precedes execution of the world frame graph, so
     * the first boundary deliberately keeps the guard alive while that stale graph submits. The
     * second boundary occurs after a fresh extraction and can safely expose Quick Skin's texture.
     */
    public static void onRenderedFrameBoundary() {
        if (!skinModeResetQueued.get() || !skinModeResetApplied.get()
                || cacheInvalidationQueued.get()) {
            return;
        }
        if (skinModeResetFrameBoundaries.incrementAndGet() < 2) {
            return;
        }
        skinModeResetFrameBoundaries.set(0);
        skinModeResetApplied.set(false);
        skinModeResetQueued.set(false);
    }

    private static boolean isCPMScreenOpen() {
        try {
            //? if <26.2 {
            net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
            //?} else {
            net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().gui.screen();
            //?}
            return screen != null && screen.getClass().getName().startsWith("com.tom.cpm");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Called by the optional CPM mixin at player/hand render pre callbacks. */
    public static void onCpmRenderStart() {
        int depth = renderDepth.get();
        renderDepth.set(depth + 1);
        renderDepthTouchedAt.set(System.nanoTime());
        if (renderHookObservedLogged.compareAndSet(false, true)) {
            CPMLOG.info("CPM render-depth compatibility hook is active");
        }
    }

    /** Called by the optional CPM mixin at the matching player/hand post callbacks. */
    public static void onCpmRenderEnd() {
        int depth = renderDepth.get();
        if (depth <= 1) {
            renderDepth.remove();
            renderDepthTouchedAt.remove();
            return;
        } else {
            renderDepth.set(depth - 1);
        }
        touchOrClearRenderTimestamp();
    }

    private static void touchOrClearRenderTimestamp() {
        if (renderDepth.get() > 0) {
            renderDepthTouchedAt.set(System.nanoTime());
        } else {
            renderDepthTouchedAt.remove();
        }
    }

    /** True only inside a CPM-bracketed player or first-person hand render. */
    public static boolean isCPMActivelyRendering() {
        if (!isAvailable()) {
            return false;
        }
        int depth = renderDepth.get();
        if (depth <= 0) {
            return false;
        }
        long age = System.nanoTime() - renderDepthTouchedAt.get();
        if (age > STALE_RENDER_DEPTH_NANOS) {
            renderDepth.remove();
            renderDepthTouchedAt.remove();
            if (staleRenderDepthLogged.compareAndSet(false, true)) {
                CPMLOG.warn("Discarded a stale CPM render-depth signal after an unmatched third-party callback");
            }
            return false;
        }
        return true;
    }

    /**
     * Returns whether CPM owns the first-person hand texture for this frame.
     *
     * <p>The render-depth hook is the cheapest signal, but older CPM builds route their stable
     * outer hand bridge through an overloaded inner method. Keep an explicit Quick Skin selection
     * and a bounded CPM definition-cache probe as fail-closed fallbacks so our transparency mixin
     * never replaces CPM's model texture with the ordinary player-skin texture.</p>
     */
    public static boolean shouldPreserveFirstPersonHandRenderType() {
        if (!isAvailable()) {
            return false;
        }

        boolean preserve = shouldDeferToCPM() || isCPMActivelyRendering();
        ClientConfig config = ClientConfig.getInstance();
        if (!preserve && config.activeCpmModelHash != null
                && !config.activeCpmModelHash.isEmpty()) {
            preserve = true;
        }

        if (!preserve) {
            long now = System.nanoTime();
            if (now >= nextFirstPersonModelProbeNanos) {
                cachedFirstPersonModelActive = isLocalPlayerWearingCpmModel();
                nextFirstPersonModelProbeNanos = now + FIRST_PERSON_MODEL_PROBE_TTL_NANOS;
            }
            preserve = cachedFirstPersonModelActive;
        }

        if (preserve) {
            firstPersonRenderTypePreservations.incrementAndGet();
        }
        return preserve;
    }

    /** Monotonic E2E-visible proof that the actual hand-buffer redirect deferred to CPM. */
    public static long firstPersonRenderTypePreservationCount() {
        return firstPersonRenderTypePreservations.get();
    }

    /** Invalidates CPM's definition cache so it recreates player/model state. */
    public static void invalidatePlayerCache() {
        if (!isAvailable()) {
            return;
        }
        if (cacheInvalidationDisabled) {
            return;
        }
        ensureRuntimeHandles();
        if (loaderInstance == null || clearCacheMethod == null) {
            if (cacheUnavailableLogged.compareAndSet(false, true)) {
                CPMLOG.warn("Cannot invalidate CPM's model cache because the optional cache handle is unavailable");
            }
            return;
        }
        try {
            clearCacheMethod.invoke(loaderInstance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            cacheInvalidationDisabled = true;
            if (cacheInvocationFailedLogged.compareAndSet(false, true)) {
                CPMLOG.warn(
                        "CPM cache invalidation failed and has been disabled; model selection remains available",
                        e
                );
            }
        }
    }

    /**
     * The bytes of a network skin arrived after the file-backed bridge had looked for its file.
     * CPM loads a player once and keeps "no model" while the skin file is missing, so make it load
     * its players again on the next frame; it then reads the file and finds a model embedded in
     * the skin. The bridge also serves rendering and the tab list, so this runs for nearly every
     * remote skin that arrives late while CPM is installed: once per skin and connection, with
     * calls in one frame coalesced. The texture cache calls it on the client thread, after the
     * texture is stored. A no-op without CPM and where CPM does not read Quick Skin's file
     * (1.21.4+).
     */
    public static void onMissedNetworkSkinStored(String hash) {
        if (!isAvailable() || !CpmCapabilities.current().supportsHttpTextureBridge()) {
            return;
        }
        CPMLOG.info("Network skin {} arrived after CPM looked for its file; reloading CPM players", hash);
        schedulePlayerCacheInvalidation();
    }

    /**
     * Refreshes CPM after its current extracted/render-state frame has finished. Clearing the
     * definition loader synchronously can leave CPM's already-built renderer pointing at a model
     * whose render types were just discarded (notably Fabric 26.1/26.1.1). CPM exposes this
     * one-frame scheduler for the same lifecycle boundary, so coalesce repeated skin updates onto
     * it and retain the synchronous path only as a compatibility fallback.
     */
    private static void schedulePlayerCacheInvalidation() {
        if (!isAvailable()) {
            return;
        }
        ensureRuntimeHandles();
        if (minecraftClientAccess == null || executeNextFrameMethod == null) {
            invalidatePlayerCache();
            return;
        }
        if (!cacheInvalidationQueued.compareAndSet(false, true)) {
            return;
        }
        Runnable refresh = () -> {
            cacheInvalidationQueued.set(false);
            invalidatePlayerCache();
        };
        try {
            executeNextFrameMethod.invoke(minecraftClientAccess, refresh);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            cacheInvalidationQueued.set(false);
            if (cacheSchedulingFailedLogged.compareAndSet(false, true)) {
                CPMLOG.warn("CPM next-frame cache refresh is unavailable; refreshing immediately", e);
            }
            invalidatePlayerCache();
        }
    }

    /**
     * Makes CPM recreate its cached player definitions after a Quick Skin appearance change, so
     * it reads the skin again. It never resets CPM to skin mode: a model CPM has for a player,
     * whether chosen in Quick Skin or in CPM's own screen, set by the server or embedded in the
     * skin, stays CPM's. Only choosing a Quick Skin skin resets CPM, through
     * {@link CpmModelWorkflow#activateSkin}; the local player's reset it queued refreshes CPM.
     */
    public static void forceReRegisterSkins(java.util.UUID playerId) {
        java.util.UUID localUuid = getLocalPlayerUuid();
        boolean localPlayer = localUuid != null && localUuid.equals(playerId);
        if (!localPlayer || !skinModeResetQueued.get()) {
            schedulePlayerCacheInvalidation();
        }

        // 1.20.1 also needs vanilla PlayerInfo to invoke registerSkins again so CPM can read a
        // newly installed file-backed texture or discard one when the UUID default is restored.
        //? if <1.21 {
        if (!isAvailable()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            net.minecraft.client.multiplayer.PlayerInfo playerInfo = mc.player.connection.getPlayerInfo(playerId);
            if (playerInfo != null) {
                ((QuickSkinPlayerInfoAccess) playerInfo).quickskin$forceReRegisterSkins();
            }
        }
        //?}
    }

    /** Clears CPM's selectedModel key and conditionally notifies a CPM server, as CPM's Reset does. */
    public static boolean resetToSkinMode() {
        if (!isAvailable()) {
            return false;
        }
        if (!hasConfigHandles()) {
            logConfigUnavailable();
            return false;
        }
        if (usesFabricDeferredPipeline() && executeNextFrameMethod != null) {
            return scheduleSkinModeReset();
        }
        return performSkinModeReset();
    }

    /**
     * Fabric's render-state and collector pipelines have already materialized the current CPM
     * model by the time a Quick Skin action runs. Changing CPM's selected model in that same frame
     * can invalidate the render-type table underneath the deferred nodes. Execute the complete
     * transition at CPM's next-frame boundary so both the old definition and its render types
     * survive the current submission.
     */
    private static boolean scheduleSkinModeReset() {
        if (!skinModeResetQueued.compareAndSet(false, true)) {
            return true;
        }
        skinModeResetApplied.set(false);
        skinModeResetFrameBoundaries.set(0);
        Runnable reset = () -> {
            if (performSkinModeReset()) {
                skinModeResetApplied.set(true);
            } else {
                skinModeResetQueued.set(false);
            }
        };
        try {
            executeNextFrameMethod.invoke(minecraftClientAccess, reset);
            if (deferredResetLogged.compareAndSet(false, true)) {
                CPMLOG.info("CPM skin-mode resets wait for the next extracted frame");
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (cacheSchedulingFailedLogged.compareAndSet(false, true)) {
                CPMLOG.warn("CPM next-frame skin-mode reset is unavailable; resetting immediately", e);
            }
            boolean resetApplied = performSkinModeReset();
            if (resetApplied) {
                skinModeResetApplied.set(true);
            } else {
                skinModeResetQueued.set(false);
            }
            return resetApplied;
        }
    }

    private static boolean usesFabricDeferredPipeline() {
        return "Fabric".equalsIgnoreCase(PlatformHelper.getPlatformName())
                && CpmCapabilities.current().usesDeferredRendering();
    }

    private static boolean performSkinModeReset() {
        try {
            String selectedModel = (String) configGetStringMethod.invoke(
                    configInstance, "selectedModel", (Object) null);
            if (selectedModel == null) {
                // CPM has no model of its own selected, but the server may have assigned one
                // (/cpm setskin). Ask CPM's server to drop it, as CPM's own Reset does; CPM's
                // server keeps a forced model, and CpmLook then keeps it the look.
                if (localServerModel() == null || !notifyServerIfInstalled("resetToSkinMode")) {
                    schedulePlayerCacheInvalidation();
                }
                CpmLook.onQuickSkinReset();
                return true;
            }
            configClearValueMethod.invoke(configInstance, "selectedModel");
            configSaveMethod.invoke(configInstance);
            CPMLOG.info("CPM reset to skin mode from selectedModel={}", selectedModel);
            if (!notifyServerIfInstalled("resetToSkinMode")) {
                schedulePlayerCacheInvalidation();
            }
            CpmLook.onQuickSkinReset();
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            CPMLOG.warn("Failed to reset CPM to skin mode", e);
            return false;
        }
    }

    /** Whether CPM's own selection (common config {@code selectedModel}) can be read now. */
    static boolean canReadSelectedModel() {
        return isAvailable() && hasConfigHandles();
    }

    /** CPM's selectedModel, or null when none is selected or it cannot be read. */
    static String selectedModel() {
        if (!canReadSelectedModel()) {
            return null;
        }
        try {
            return (String) configGetStringMethod.invoke(configInstance, "selectedModel", (Object) null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Selects a model path relative to CPM's player_models directory. */
    public static boolean selectModel(String modelFileName) {
        if (!isAvailable()) {
            return false;
        }
        String normalizedName = normalizeRelativeModelName(modelFileName);
        if (normalizedName == null) {
            CPMLOG.warn("Rejected invalid CPM model path: {}", modelFileName);
            return false;
        }
        if (!hasConfigHandles()) {
            logConfigUnavailable();
            return false;
        }
        try {
            configSetStringMethod.invoke(configInstance, "selectedModel", normalizedName);
            configSaveMethod.invoke(configInstance);
            CPMLOG.info("Selected CPM model {}", normalizedName);
            if (!notifyServerIfInstalled("selectModel")) {
                schedulePlayerCacheInvalidation();
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            CPMLOG.warn("Failed to select CPM model {}", normalizedName, e);
            return false;
        }
    }

    private static boolean hasConfigHandles() {
        boolean ready = configInstance != null
                && configGetStringMethod != null
                && configSetStringMethod != null
                && configClearValueMethod != null
                && configSaveMethod != null
                && backgroundConfigPrepared;
        if (ready || !modAvailable) {
            return ready;
        }

        long now = System.nanoTime();
        if (now < nextConfigReflectionRetryNanos) {
            return false;
        }
        synchronized (CPMCompatIntegration.class) {
            now = System.nanoTime();
            if (now >= nextConfigReflectionRetryNanos) {
                nextConfigReflectionRetryNanos = now + 1_000_000_000L;
                initializeConfigReflection();
            }
            return configInstance != null
                    && configGetStringMethod != null
                    && configSetStringMethod != null
                    && configClearValueMethod != null
                    && configSaveMethod != null
                    && backgroundConfigPrepared;
        }
    }

    private static void logConfigUnavailable() {
        if (configUnavailableLogged.compareAndSet(false, true)) {
            CPMLOG.warn("CPM selectedModel operation skipped because the optional config bridge is unavailable");
        }
    }

    static String normalizeRelativeModelName(String modelFileName) {
        if (modelFileName == null || modelFileName.isBlank()) {
            return null;
        }
        try {
            String platformName = modelFileName.replace('/', File.separatorChar).replace('\\', File.separatorChar);
            Path normalized = Path.of(platformName).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..") || normalized.getNameCount() == 0) {
                return null;
            }
            String result = normalized.toString().replace('\\', '/');
            return result.toLowerCase(Locale.ROOT).endsWith(".cpmmodel") ? result : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean notifyServerIfInstalled(String operation) {
        ensureRuntimeHandles();
        if (minecraftClientAccess == null
                || getServerSideStatusMethod == null
                || sendSkinUpdateMethod == null) {
            return false;
        }
        try {
            Object status = getServerSideStatusMethod.invoke(minecraftClientAccess);
            if (status != null && "INSTALLED".equals(status.toString())) {
                sendSkinUpdateMethod.invoke(minecraftClientAccess);
                CPMLOG.info("{} sent CPM skin update to the server", operation);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            CPMLOG.warn("{} could not query/notify CPM server status", operation, e);
        }
        return false;
    }

    private static java.util.UUID getLocalPlayerUuid() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.player != null) {
                return minecraft.player.getUUID();
            }
            if (minecraft != null && minecraft.getUser() != null) {
                return minecraft.getUser().getProfileId();
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /**
     * Returns whether CPM draws a model for this player, whatever its source: chosen in Quick
     * Skin or in CPM's own screen, set by the server, or embedded in the skin. CPM binds the
     * model's texture from the tail of the renderer's texture lookup, so Quick Skin must not
     * return earlier there for this player. Reads the entry CPM already has for the player (a
     * cache lookup, which only renews the entry's access time as CPM's own render does) and the
     * condition of CPM's render gate ({@code Player.getModelDefinition()}) on its definition,
     * {@code ModelDefinition.doRender()}, without the gate's side effect of starting to resolve a
     * new definition. It loads and resolves nothing. The answer is kept per player for 100 ms,
     * because the renderer asks several times per frame. False without CPM, before CPM has the
     * player, or when a CPM handle is missing.
     */
    public static boolean isWearingCpmModel(java.util.UUID playerId) {
        if (playerId == null || !isAvailable() || playerModelProbeDisabled) {
            return false;
        }
        long now = System.nanoTime();
        PlayerModelProbe cached = playerModelProbes.get(playerId);
        if (cached != null && now - cached.expiresAtNanos() < 0) {
            return cached.wearing();
        }
        boolean wearing = probeCpmModel(playerId);
        if (playerModelProbes.size() >= MAX_PLAYER_MODEL_PROBES) {
            playerModelProbes.clear();
        }
        playerModelProbes.put(playerId, new PlayerModelProbe(wearing, now + PLAYER_MODEL_PROBE_TTL_NANOS));
        return wearing;
    }

    private static boolean probeCpmModel(java.util.UUID playerId) {
        ensureRuntimeHandles();
        if (loaderInstance == null || getLoadedPlayerMethod == null
                || getModelDefinition0Method == null || doRenderMethod == null) {
            return false;
        }
        Object profile = gameProfileOf(playerId);
        if (profile == null) {
            return false;
        }
        try {
            Object player = getLoadedPlayerMethod.invoke(loaderInstance, profile);
            Object definition = player != null ? getModelDefinition0Method.invoke(player) : null;
            return definition != null && Boolean.TRUE.equals(doRenderMethod.invoke(definition));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            playerModelProbeDisabled = true;
            if (playerModelProbeFailedLogged.compareAndSet(false, true)) {
                CPMLOG.warn("CPM player-model probe failed and has been disabled; Quick Skin skins keep "
                        + "their renderer override", e);
            }
            return false;
        }
    }

    /** CPM keys its players by the game profile's UUID; any profile with that UUID finds the entry. */
    private static Object gameProfileOf(java.util.UUID playerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        net.minecraft.client.multiplayer.ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            net.minecraft.client.multiplayer.PlayerInfo info = connection.getPlayerInfo(playerId);
            if (info != null) {
                return info.getProfile();
            }
        }
        if (minecraft.level != null) {
            net.minecraft.world.entity.player.Player player = minecraft.level.getPlayerByUUID(playerId);
            if (player != null) {
                return player.getGameProfile();
            }
        }
        return null;
    }

    /**
     * The model data CPM's server sent for the local player ({@code ModelDefinitionLoader.getModel}):
     * the echo of the player's own selection, or a model the server assigned with /cpm setskin.
     * Null when there is none, outside a world, or when the handle is missing.
     */
    static byte[] localServerModel() {
        Object profile = localGameProfile();
        Method getServerModel = getServerModelMethod;
        if (profile == null || getServerModel == null) {
            return null;
        }
        try {
            return getServerModel.invoke(loaderInstance, profile) instanceof byte[] data ? data : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getServerModelMethod = null;
            if (serverModelLookupFailedLogged.compareAndSet(false, true)) {
                CPMLOG.warn("CPM server-model lookup failed and has been disabled", e);
            }
            return null;
        }
    }

    /** Whether CPM marked the local player's server model as forced (/cpm setskin -f). */
    static boolean isLocalServerModelForced() {
        Object profile = localGameProfile();
        java.lang.reflect.Field forced = forcedSkinField;
        if (profile == null || forced == null || getLoadedPlayerMethod == null) {
            return false;
        }
        try {
            Object player = getLoadedPlayerMethod.invoke(loaderInstance, profile);
            return player != null && forced.getBoolean(player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static Object localGameProfile() {
        if (!isAvailable()) {
            return null;
        }
        ensureRuntimeHandles();
        Minecraft minecraft = Minecraft.getInstance();
        if (loaderInstance == null || minecraft == null || minecraft.player == null) {
            return null;
        }
        return gameProfileOf(minecraft.player.getUUID());
    }

    /**
     * A network skin's bytes arrived. Where CPM reads Quick Skin skins (the embedded-PNG bridge),
     * it may have loaded that player before the file existed and kept "no model"; refresh it so a
     * model embedded in the skin is found.
     */
    public static void onNetworkSkinStored() {
        if (isAvailable() && CpmCapabilities.current().supportsHttpTextureBridge()) {
            schedulePlayerCacheInvalidation();
        }
    }

    /**
     * Queries CPM's definition cache for the local player's currently loaded
     * model. This covers both explicit model files and old-band embedded data.
     */
    public static boolean isLocalPlayerWearingCpmModel() {
        if (!isAvailable()) {
            return false;
        }
        ensureRuntimeHandles();
        if (localModelProbeDisabled
                || minecraftClientAccess == null
                || getCurrentClientPlayerMethod == null
                || getModelDefinitionMethod == null
                || getPlayerUuidMethod == null) {
            return false;
        }
        java.util.UUID localUuid = getLocalPlayerUuid();
        if (localUuid == null) {
            return false;
        }
        try {
            Object player = getCurrentClientPlayerMethod.invoke(minecraftClientAccess);
            if (player == null || !localUuid.equals(getPlayerUuidMethod.invoke(player))) {
                return false;
            }
            return getModelDefinitionMethod.invoke(player) != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            localModelProbeDisabled = true;
            if (localModelProbeFailedLogged.compareAndSet(false, true)) {
                CPMLOG.warn(
                        "CPM local-model activity probe failed and has been disabled; explicit model selection remains available",
                        e
                );
            }
        }
        return false;
    }

    public static Path getCPMModelsDirectory() {
        return PlatformHelper.getGameDirectory().resolve("player_models");
    }

    /**
     * Returns a file-backed texture for CPM's legacy embedded-PNG reader.
     * Modern bands expose the same bridge method but intentionally return null
     * with an actionable one-time degraded-capability log.
     */
    //? if <1.21.11 {
    public static ResourceLocation getOrRegisterHttpTexture(String hash) {
    //?} else {
    public static Identifier getOrRegisterHttpTexture(String hash) {
    //?}
        if (!isAvailable() || hash == null || hash.isEmpty()) {
            return null;
        }
        if (!CpmCapabilities.current().supportsHttpTextureBridge()) {
            logDegradedEmbeddedBridge();
            return null;
        }

        //? if <1.21.4 {
        ResourceLocation cached = httpTextureCache.get(hash);
        if (cached != null) {
            if (Minecraft.getInstance().getTextureManager().getTexture(cached, null) != null) {
                return cached;
            }
            httpTextureCache.remove(hash);
        }

        Path sourcePath = assets().localSource(hash);
        if (sourcePath == null || !Files.exists(sourcePath)) {
            sourcePath = assets().networkSkinFile(hash);
        }
        if (sourcePath == null || !Files.exists(sourcePath)) {
            return null;
        }

        File skinFile = sourcePath.toFile();
        //? if <1.21 {
        ResourceLocation location = new ResourceLocation(QuickSkinInfo.MOD_ID, "cpm_bridge/" + hash);
        ResourceLocation fallback = new ResourceLocation("textures/entity/player/wide/steve.png");
        //?} else {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "cpm_bridge/" + hash);
        ResourceLocation fallback = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");
        //?}
        HttpTexture httpTexture = new HttpTexture(
                skinFile,
                "file:///" + skinFile.getAbsolutePath().replace('\\', '/'),
                fallback,
                true,
                () -> {
                }
        );
        Minecraft.getInstance().getTextureManager().register(location, httpTexture);
        httpTextureCache.put(hash, location);
        return location;
        //?} else {
        return null;
        //?}
    }

    private static final int MAX_RESOLVED_SKIN_FILES = 256;
    /** Positive {@code hash -> file:///} resolutions handed to CPM's skin-information override. */
    private static final Map<String, String> resolvedSkinFileUrls = new ConcurrentHashMap<>();

    /**
     * Resolves the {@code file:///} URL CPM reads for a Quick Skin content id. The skin-information
     * override runs on the skull render path once per rendered player head and frame, so a
     * positive resolution is cached per hash instead of checking the disk every call. Entries
     * leave through {@link #evictHttpTextureCache} and {@link #clearHttpTextureCache}, which
     * already fire when the local catalog entry or the network texture behind the hash changes.
     * Returns null while no readable file exists; that miss is not cached.
     */
    public static String resolveSkinFileUrl(String hash) {
        if (!isAvailable() || hash == null || hash.isEmpty()) {
            return null;
        }
        String cached = resolvedSkinFileUrls.get(hash);
        if (cached != null) {
            return cached;
        }
        Path sourcePath = assets().localSource(hash);
        if (sourcePath == null || !Files.exists(sourcePath)) {
            sourcePath = assets().networkSkinFile(hash);
        }
        if (sourcePath == null || !Files.exists(sourcePath)) {
            return null;
        }
        String url = "file:///" + sourcePath.toFile().getAbsolutePath().replace('\\', '/');
        if (resolvedSkinFileUrls.size() >= MAX_RESOLVED_SKIN_FILES) {
            resolvedSkinFileUrls.clear();
        }
        resolvedSkinFileUrls.put(hash, url);
        CPMLOG.debug("Resolved CPM skin file for {} -> {}", hash, url);
        return url;
    }

    /** Releases a legacy bridge texture, or logs the modern degraded no-op once. */
    public static void evictHttpTextureCache(String hash) {
        if (hash != null) {
            resolvedSkinFileUrls.remove(hash);
        }
        if (!CpmCapabilities.current().supportsHttpTextureBridge()) {
            if (isAvailable()) {
                logDegradedEmbeddedBridge();
            }
            return;
        }
        //? if <1.21.4 {
        if (hash != null) {
            ResourceLocation old = httpTextureCache.remove(hash);
            if (old != null) {
                try {
                    Minecraft.getInstance().getTextureManager().release(old);
                } catch (RuntimeException e) {
                    CPMLOG.warn("Failed to release CPM bridge texture {}", old, e);
                }
            }
        }
        //?}
    }

    /**
     * Releases every connection-owned legacy CPM bridge texture, and forgets the per-player
     * answers of {@link #isWearingCpmModel}.
     */
    public static void clearHttpTextureCache() {
        resolvedSkinFileUrls.clear();
        playerModelProbes.clear();
        //? if <1.21.4 {
        for (ResourceLocation location : httpTextureCache.values()) {
            try {
                Minecraft.getInstance().getTextureManager().release(location);
            } catch (RuntimeException error) {
                CPMLOG.warn("Failed to release CPM bridge texture {}", location, error);
            }
        }
        httpTextureCache.clear();
        //?}
    }

    private static void logDegradedEmbeddedBridge() {
        if (degradedBridgeLogged.compareAndSet(false, true)) {
            CPMLOG.warn(
                    "CPM embedded-PNG HttpTexture bridging is unavailable on Minecraft {}; "
                            + "explicit .cpmmodel models remain fully supported",
                    CpmCapabilities.currentBand().displayName()
            );
        }
    }

    /** Parsed display data from a standalone .cpmmodel file. */
    public static final class CpmModelInfo {
        public final String name;
        public final String description;
        public final byte[] iconPngBytes;

        public CpmModelInfo(String name, String description, byte[] iconPngBytes) {
            this.name = name;
            this.description = description;
            this.iconPngBytes = iconPngBytes;
        }
    }

    /** Parses CPM's stable 0x53 model-file header without loading CPM classes. */
    public static CpmModelInfo parseCpmModelInfo(Path path) {
        try (InputStream stream = Files.newInputStream(path);
             DataInputStream input = new DataInputStream(stream)) {
            if (input.read() != 0x53) {
                return null;
            }
            String name = readVarIntUtf(input);
            String description = readVarIntUtf(input);
            skipFully(input, readVarInt(input));

            int overflowLength = readVarInt(input);
            if (overflowLength > 0) {
                skipFully(input, overflowLength);
                int linkLength = input.read();
                if (linkLength < 0) {
                    throw new IOException("Unexpected EOF in CPM link block");
                }
                if (linkLength > 0) {
                    skipFully(input, linkLength);
                }
            }

            int iconLength = readVarInt(input);
            byte[] icon = null;
            if (iconLength > 0) {
                if (iconLength > MAX_MODEL_ICON_BYTES) {
                    throw new IOException("CPM icon block is unreasonably large: " + iconLength);
                }
                icon = new byte[iconLength];
                input.readFully(icon);
            }
            return new CpmModelInfo(
                    name != null ? name : path.getFileName().toString(),
                    description != null ? description : "",
                    icon
            );
        } catch (Exception e) {
            String fileName = path.getFileName().toString();
            String fallbackName = fileName.toLowerCase(Locale.ROOT).endsWith(".cpmmodel")
                    ? fileName.substring(0, fileName.length() - 9)
                    : fileName;
            return new CpmModelInfo(fallbackName, "", null);
        }
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        int result = 0;
        int shift = 0;
        byte value;
        do {
            value = input.readByte();
            result |= (value & 0x7f) << (shift * 7);
            shift++;
            if (shift > 5) {
                throw new IOException("CPM VarInt is too large");
            }
        } while ((value & 0x80) != 0);
        if (result < 0) {
            throw new IOException("Negative CPM block length");
        }
        return result;
    }

    private static String readVarIntUtf(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length == 0) {
            return "";
        }
        if (length > MAX_MODEL_TEXT_BYTES) {
            throw new IOException("CPM text block is unreasonably large: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void skipFully(DataInputStream input, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= (int) skipped;
            } else {
                input.readByte();
                remaining--;
            }
        }
    }
}
