package com.quickskin.mod.runtime;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.compat.CustomNPCsIntegration;
import com.quickskin.mod.client.compat.EarsCompatIntegration;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.SkinLayers3DIntegration;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.ModelService;
import com.quickskin.mod.client.storage.ClientAnimationMetadataCache;
import com.quickskin.mod.client.storage.LocalAppearanceStorage;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.client.storage.TextureChunkReceiver;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.networking.ClientNetworkHandler;
import com.quickskin.mod.networking.ClientTextureIngressLimiter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Owns client state that must not survive a server connection. */
@Environment(EnvType.CLIENT)
public final class ClientRuntime implements AutoCloseable {
    private static final ClientRuntime INSTANCE = new ClientRuntime(
            LocalAssetManager.getInstance(),
            LocalAppearanceStorage.getInstance(),
            PlayerAppearanceRepository.getInstance(),
            ModelService.getInstance(),
            CooldownService.getInstance(),
            AnimatedTextureManager.getInstance(),
            TextureChunkReceiver.getInstance(),
            NetworkTextureCache.getInstance(),
            ClientAnimationMetadataCache.getInstance()
    );

    private final LocalAssetManager assetManager;
    private final LocalAppearanceStorage appearanceStorage;
    private final PlayerAppearanceRepository appearanceRepository;
    private final ModelService modelService;
    private final CooldownService cooldownService;
    private final AnimatedTextureManager animatedTextures;
    private final TextureChunkReceiver chunkReceiver;
    private final NetworkTextureCache networkTextures;
    private final ClientAnimationMetadataCache animationMetadata;

    private boolean storesInitialized;
    private UUID activePlayerId;
    private Object activeSessionIdentity;

    public ClientRuntime(
            LocalAssetManager assetManager,
            LocalAppearanceStorage appearanceStorage,
            PlayerAppearanceRepository appearanceRepository,
            ModelService modelService,
            CooldownService cooldownService,
            AnimatedTextureManager animatedTextures,
            TextureChunkReceiver chunkReceiver,
            NetworkTextureCache networkTextures,
            ClientAnimationMetadataCache animationMetadata
    ) {
        this.assetManager = Objects.requireNonNull(assetManager, "assetManager");
        this.appearanceStorage = Objects.requireNonNull(appearanceStorage, "appearanceStorage");
        this.appearanceRepository = Objects.requireNonNull(appearanceRepository, "appearanceRepository");
        this.modelService = Objects.requireNonNull(modelService, "modelService");
        this.cooldownService = Objects.requireNonNull(cooldownService, "cooldownService");
        this.animatedTextures = Objects.requireNonNull(animatedTextures, "animatedTextures");
        this.chunkReceiver = Objects.requireNonNull(chunkReceiver, "chunkReceiver");
        this.networkTextures = Objects.requireNonNull(networkTextures, "networkTextures");
        this.animationMetadata = Objects.requireNonNull(animationMetadata, "animationMetadata");
    }

    public static ClientRuntime getInstance() {
        return INSTANCE;
    }

    /**
     * Initializes persistent stores before event registration can launch asynchronous work.
     */
    public synchronized void initializeStores(Path configDirectory) {
        if (storesInitialized) {
            return;
        }
        Objects.requireNonNull(configDirectory, "configDirectory");

        ClientConfig.getInstance();
        appearanceStorage.init(configDirectory);
        assetManager.init();
        storesInitialized = true;
    }

    /** Starts a clean connection session even if the previous disconnect callback was missed. */
    public synchronized void beginSession(UUID localPlayerId, Object sessionIdentity) {
        resetSessionState();
        activePlayerId = localPlayerId;
        activeSessionIdentity = sessionIdentity;
        com.quickskin.mod.networking.NetworkSyncService.getInstance()
                .beginAppearanceSnapshotRequest(localPlayerId, sessionIdentity);
    }

    /** Persists local preferences and releases all connection-owned state. */
    public synchronized boolean endSession(UUID localPlayerId, Object sessionIdentity) {
        if (activeSessionIdentity != null && activeSessionIdentity != sessionIdentity) {
            QuickSkinInfo.LOGGER.warn("Ignoring a stale QuickSkin client-disconnect callback");
            return false;
        }
        if (activePlayerId != null && localPlayerId != null
                && !activePlayerId.equals(localPlayerId)) {
            QuickSkinInfo.LOGGER.warn("Ignoring a mismatched QuickSkin client-disconnect player");
            return false;
        }
        UUID playerIdToSave = localPlayerId != null ? localPlayerId : activePlayerId;
        if (playerIdToSave != null) {
            runCleanup("save local appearance preferences",
                    () -> appearanceStorage.savePlayerPreferences(playerIdToSave));
        }
        resetSessionState();
        activePlayerId = null;
        activeSessionIdentity = null;
        return true;
    }

    private void resetSessionState() {
        runCleanup("clear animated textures", animatedTextures::clearAnimations);
        runCleanup("clear player appearances", appearanceRepository::clear);
        runCleanup("clear model overrides", modelService::clearAll);
        runCleanup("clear client cooldown", cooldownService::clearCooldown);
        runCleanup("clear incomplete texture downloads", chunkReceiver::clear);
        runCleanup("clear network textures", networkTextures::clear);
        runCleanup("clear animation metadata", animationMetadata::clear);
        runCleanup("clear deferred network UI work", ClientNetworkHandler::clearTransientState);
        runCleanup("clear pending appearance uploads",
                com.quickskin.mod.networking.NetworkSyncService.getInstance()::clearSession);
        runCleanup("clear client texture ingress budget",
                ClientTextureIngressLimiter.getInstance()::clear);
        runCleanup("clear server configuration override", ClientConfig.getInstance()::clearServerOverride);
        runCleanup("clear texture alpha analysis", TextureAlphaDetector::clearCache);
        runCleanup("clear 3D skin-layer meshes", SkinLayers3DIntegration::clearCache);
        runCleanup("clear local texture registrations", assetManager::clearTextureCache);
        runCleanup("clear CPM bridge textures", CPMCompatIntegration::clearHttpTextureCache);
        runCleanup("clear Ears feature metadata", EarsCompatIntegration::clearAllFeatures);
        runCleanup("clear CustomNPCs player tracking", CustomNPCsIntegration::clearTrackedSkins);
        runCleanup("clear cached preview player", PlayerModelRenderer::clearCachedPlayer);
    }

    private static void runCleanup(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError error) {
            QuickSkinInfo.LOGGER.warn("Failed to {} while resetting the QuickSkin client session", operation, error);
        }
    }

    @Override
    public synchronized void close() {
        endSession(activePlayerId, activeSessionIdentity);
        ClientIoExecutor.close();
    }
}
