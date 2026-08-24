package com.quickskin.mod.client.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.LocalAssetManager;
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
//? if <1.21.4 {
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
//?}
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional compatibility bridge for Customizable Player Models (CPM).
 *
 * <p>All third-party lookups are guarded by loader/resource detection and are
 * cached after activation. A missing optional CPM handle disables only the
 * operation that needs that handle; model discovery and parsing remain usable.</p>
 */
@Environment(EnvType.CLIENT)
public final class CPMCompatIntegration {
    private static final Logger CPMLOG = LoggerFactory.getLogger("QuickSkin-CPM");
    private static final String CPM_CLIENT_RESOURCE = "com/tom/cpm/client/CustomPlayerModelsClient.class";
    private static final long STALE_RENDER_DEPTH_NANOS = 2_000_000_000L;
    private static final int MAX_MODEL_TEXT_BYTES = 1_048_576;
    private static final int MAX_MODEL_ICON_BYTES = 16_777_216;

    private static volatile boolean checked;
    private static volatile boolean modAvailable;
    private static volatile long nextRuntimeReflectionRetryNanos;
    private static volatile long nextConfigReflectionRetryNanos;

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

    private static final AtomicBoolean cacheUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean cacheInvocationFailedLogged = new AtomicBoolean();
    private static final AtomicBoolean loaderReflectionUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean runtimeReflectionUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean serverReflectionUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean configUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean configReflectionUnavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean degradedBridgeLogged = new AtomicBoolean();
    private static final AtomicBoolean renderHookObservedLogged = new AtomicBoolean();
    private static final AtomicBoolean staleRenderDepthLogged = new AtomicBoolean();
    private static final AtomicBoolean localModelProbeFailedLogged = new AtomicBoolean();
    private static final AtomicBoolean cacheInvalidationQueued = new AtomicBoolean();
    private static final AtomicBoolean cacheSchedulingFailedLogged = new AtomicBoolean();
    private static final AtomicBoolean skinModeResetQueued = new AtomicBoolean();
    private static final AtomicBoolean skinModeResetApplied = new AtomicBoolean();
    private static final AtomicInteger skinModeResetFrameBoundaries = new AtomicInteger();
    private static final AtomicBoolean staleSubmissionDroppedLogged = new AtomicBoolean();
    private static final AtomicBoolean deferredResetLogged = new AtomicBoolean();
    private static volatile boolean localModelProbeDisabled;
    private static volatile boolean cacheInvalidationDisabled;

    /** Render activity is bracketed by the optional CPM-targeting mixin. */
    private static final ThreadLocal<Integer> renderDepth = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Long> renderDepthTouchedAt = ThreadLocal.withInitial(() -> 0L);

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

    private static void checkAvailability() {
        boolean loaderReported = PlatformHelper.isModLoaded("cpm");
        boolean resourcePresent = loaderReported || classFileExists(CPM_CLIENT_RESOURCE);
        modAvailable = resourcePresent;
        checked = true;

        if (!resourcePresent) {
            return;
        }

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
        }
    }

    private static void initializeConfigReflection() {
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
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            configInstance = null;
            configGetStringMethod = null;
            configSetStringMethod = null;
            configClearValueMethod = null;
            configSaveMethod = null;
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
     * Switches the local player from an explicit model file back to normal skin
     * mode, then forces CPM to recreate its cached definition.
     */
    public static void forceReRegisterSkins(java.util.UUID playerId) {
        java.util.UUID localUuid = getLocalPlayerUuid();
        if (localUuid != null && localUuid.equals(playerId)) {
            resetToSkinMode();
        } else {
            schedulePlayerCacheInvalidation();
        }

        // 1.20.1 also needs vanilla PlayerInfo to invoke registerSkins again so
        // CPM can read the newly installed file-backed texture.
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

    /** Clears CPM's selectedModel key and conditionally notifies a CPM server. */
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
                schedulePlayerCacheInvalidation();
                return true;
            }
            configClearValueMethod.invoke(configInstance, "selectedModel");
            configSaveMethod.invoke(configInstance);
            CPMLOG.info("CPM reset to skin mode from selectedModel={}", selectedModel);
            if (!notifyServerIfInstalled("resetToSkinMode")) {
                schedulePlayerCacheInvalidation();
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            CPMLOG.warn("Failed to reset CPM to skin mode", e);
            return false;
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
                && configSaveMethod != null;
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
                    && configSaveMethod != null;
        }
    }

    private static void logConfigUnavailable() {
        if (configUnavailableLogged.compareAndSet(false, true)) {
            CPMLOG.warn("CPM selectedModel operation skipped because the optional config bridge is unavailable");
        }
    }

    private static String normalizeRelativeModelName(String modelFileName) {
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

        Path sourcePath = LocalAssetManager.getInstance().getSourcePath(hash);
        if (sourcePath == null || !Files.exists(sourcePath)) {
            sourcePath = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                    .getOrCreateTempFile(hash, "skin");
        }
        if (sourcePath == null || !Files.exists(sourcePath)) {
            return null;
        }

        File skinFile = sourcePath.toFile();
        //? if <1.21 {
        ResourceLocation location = new ResourceLocation(QuickSkin.MOD_ID, "cpm_bridge/" + hash);
        ResourceLocation fallback = new ResourceLocation("textures/entity/player/wide/steve.png");
        //?} else {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "cpm_bridge/" + hash);
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

    /** Releases a legacy bridge texture, or logs the modern degraded no-op once. */
    public static void evictHttpTextureCache(String hash) {
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

    /** Releases every connection-owned legacy CPM bridge texture. */
    public static void clearHttpTextureCache() {
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
