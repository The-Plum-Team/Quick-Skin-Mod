package com.quickskin.mod.client.services;

import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.compat.CustomNPCsIntegration;
import com.quickskin.mod.client.rendering.SkinLayers3DIntegration;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.common.event.InternalEventBus;
import com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent;
import com.quickskin.mod.common.event.SkinTexturesReloadedEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Main coordinator service for player appearance
 * This delegates work to specialized services (SkinService, CapeService, ModelService)
 */
@Environment(EnvType.CLIENT)
public class PlayerAppearanceService implements IPlayerAppearanceService {
    private static volatile PlayerAppearanceService instance;

    private final PlayerAppearanceRepository repository;
    private final ISkinService skinService;
    private final ICapeService capeService;
    private final IModelService modelService;
    private final InternalEventBus eventBus;
    private boolean reloadingTransparency;
    private boolean applyingNetworkUpdate;

    public PlayerAppearanceService(
            PlayerAppearanceRepository repository,
            ISkinService skinService,
            ICapeService capeService,
            IModelService modelService,
            InternalEventBus eventBus
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.skinService = Objects.requireNonNull(skinService, "skinService");
        this.capeService = Objects.requireNonNull(capeService, "capeService");
        this.modelService = Objects.requireNonNull(modelService, "modelService");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    public static PlayerAppearanceService getInstance() {
        if (instance == null) {
            synchronized (PlayerAppearanceService.class) {
                if (instance == null) {
                    instance = new PlayerAppearanceService(
                            PlayerAppearanceRepository.getInstance(),
                            SkinService.getInstance(),
                            CapeService.getInstance(),
                            ModelService.getInstance(),
                            InternalEventBus.getInstance()
                    );
                }
            }
        }
        return instance;
    }

    public static void init() {
        getInstance();
    }

    @Override
    public void applyLook(UUID playerId, @Nullable String skinId, @Nullable String capeId, @Nullable String model) {
        if (playerId == null) {
            return;
        }

        // Get or create appearance
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance == null) {
            appearance = new PlayerAppearance(playerId, "", "", "classic");
            repository.setAppearance(appearance);
        }

        // Update skin
        if (skinId != null) {
            appearance.setSkinId(skinId);

            // Resolve model type
            String requestedModel = model != null ? model : "auto";
            String resolvedModel = modelService.getModelType(playerId, skinId, requestedModel);
            appearance.setModel(resolvedModel);

            // Store the REQUESTED model (not resolved) as override
            // This allows "auto" to re-detect each time instead of locking to the first detection
            modelService.setModelOverride(playerId, requestedModel);

            //? if <1.21.11 {
            ResourceLocation skinLocation = skinService.getSkinLocation(playerId, skinId);
            //?} else {
            // Load skin Identifier
            Identifier skinLocation = skinService.getSkinLocation(playerId, skinId);
            //?}
            if (skinLocation != null) {
                appearance.setSkinLocation(skinLocation);

                // Trigger async transparency analysis for the skin texture
                com.quickskin.mod.common.util.TextureAlphaDetector.analyzeTextureAsync(skinLocation);

                // Notify CustomNPCs integration (if available) to handle any skin cache invalidation
                CustomNPCsIntegration.onSkinApplied(playerId, skinLocation);
            }

            // A cleared skin has no location, but CPM 1.20.1 still needs PlayerInfo to rebuild its
            // cached bridge texture and return to the vanilla skin selected for this UUID.
            CPMCompatIntegration.forceReRegisterSkins(playerId);

            if (skinLocation != null) associateEarsFeatures(playerId, skinLocation);
        } else if (model != null) {
            // Model-only updates should not require re-selecting the current skin.
            String resolvedModel = modelService.getModelType(
                    playerId, appearance.getSkinId(), model);
            appearance.setModel(resolvedModel);
            modelService.setModelOverride(playerId, model);
        }

        // Update cape
        if (capeId != null) {
            // NOTE: We do NOT unregister the old animation when switching capes.
            // Animations are needed for thumbnail rendering in the capes menu, and unregistering
            // them would cause thumbnails to fall back to the full atlas texture, displaying
            // all frames at once. Animations will be cleaned up when appropriate (e.g., when
            // the player disconnects or leaves the world).

            appearance.setCapeId(capeId);
            appearance.setCapeLocation(null);

            // Load the cape texture (static or atlas)
            //? if <1.21.11 {
            ResourceLocation capeLocation = capeService.getCapeLocation(playerId, capeId);
            //?} else {
            Identifier capeLocation = capeService.getCapeLocation(playerId, capeId);
            //?}
            if (capeLocation != null) {
                appearance.setCapeLocation(capeLocation);

                // Trigger async transparency analysis for the cape texture
                com.quickskin.mod.common.util.TextureAlphaDetector.analyzeTextureAsync(capeLocation);

                // If animated, ensure the animation is registered
                if (capeService.isAnimated(capeId)) {
                    String hash = CapeAnimationIds.localHash(capeId);

                    if (hash != null) {
                        String animationId = CapeAnimationIds.deriveAnimationId(capeId);

                        AnimatedTextureManager.getInstance().registerAnimationAsync(
                                animationId, capeId, capeLocation, hash);
                    } else if (capeId.startsWith("known:")) {
                        // Register known cape animation
                        String knownId = capeId.substring("known:".length());
                        capeService.loadKnownCape(knownId);
                    }
                }
            }
        }

        // Refresh player renderer
        refreshPlayerRenderer(playerId);

        PlayerAppearanceUpdateEvent.UpdateType updateType;
        if (skinId != null && capeId != null) {
            updateType = PlayerAppearanceUpdateEvent.UpdateType.FULL;
        } else if (skinId != null) {
            updateType = PlayerAppearanceUpdateEvent.UpdateType.SKIN;
        } else if (capeId != null) {
            updateType = PlayerAppearanceUpdateEvent.UpdateType.CAPE;
        } else if (model != null) {
            updateType = PlayerAppearanceUpdateEvent.UpdateType.MODEL;
        } else {
            updateType = PlayerAppearanceUpdateEvent.UpdateType.FULL;
        }

        eventBus.post(new PlayerAppearanceUpdateEvent(playerId, appearance, updateType));

        // Sync to server if this is the local player
        Minecraft mc = Minecraft.getInstance();
        if (!reloadingTransparency && !applyingNetworkUpdate && mc.player != null
                && playerId.equals(mc.player.getUUID())) {
            com.quickskin.mod.networking.NetworkSyncService.getInstance().syncAppearance(
                playerId,
                appearance.getSkinId(),
                appearance.getCapeId(),
                appearance.getModel()
            );
        }
    }

    @Override
    public void applySkin(UUID playerId, String skinId, @Nullable String model) {
        applyLook(playerId, skinId, null, model);
    }

    @Override
    public void applyCape(UUID playerId, String capeId) {
        applyLook(playerId, null, capeId, null);
    }

    @Override
    @Nullable
    public PlayerAppearance getAppearance(UUID playerId) {
        return repository.getAppearance(playerId);
    }

    @Override
    public void refreshPlayerRenderer(UUID playerId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.levelRenderer != null) {
            AbstractClientPlayer player = (AbstractClientPlayer) mc.level.getPlayerByUUID(playerId);
            if (player != null) {
                //? if <26.2 {
                BlockPos pos = player.blockPosition();
                BlockState state = mc.level.getBlockState(pos);
                mc.levelRenderer.setBlockDirty(pos, state, state);
                //?}

                // Always refresh SkinLayers3D compatibility
                refreshSkinLayers3D(player);
            }
        }
    }

    private void refreshSkinLayers3D(AbstractClientPlayer player) {
        SkinLayers3DIntegration.refreshPlayer(player);
    }

    public boolean hasActiveSkin(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null && appearance.getSkinId() != null && !appearance.getSkinId().isEmpty();
    }

    public boolean hasActiveCape(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null && appearance.getCapeId() != null && !appearance.getCapeId().isEmpty();
    }

    /**
     * Get the cape ID for a player (e.g., "local_cape:hash" or "known:rickroll")
     * @param playerId The player's UUID
     * @return The cape ID string, or null if no cape is set
     */
    @Nullable
    public String getCapeId(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance == null) {
            return null;
        }
        String capeId = appearance.getCapeId();
        return (capeId != null && !capeId.isEmpty()) ? capeId : null;
    }
    public boolean hasModelOverride(UUID playerId) {
        return modelService.hasModelOverride(playerId);
    }

    @Nullable
    //? if <1.21.11 {
    public ResourceLocation getSkinLocation(UUID playerId) {
    //?} else {
    public Identifier getSkinLocation(UUID playerId) {
    //?}
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance == null) {
            return null;
        }

        // If the location is already cached, return it.
        if (appearance.getSkinLocation() != null) {
            return appearance.getSkinLocation();
        }

        // SLOW PATH - LOG THIS!

        // If not cached, try to resolve it now.
        // This handles the race condition where SYNC_APPEARANCE arrives before SEND_TEXTURE
        if (appearance.getSkinId() != null && !appearance.getSkinId().isEmpty()) {
            //? if <1.21.11 {
            ResourceLocation location = skinService.getSkinLocation(playerId, appearance.getSkinId());
            //?} else {
            Identifier location = skinService.getSkinLocation(playerId, appearance.getSkinId());
            //?}
            if (location != null) {
                appearance.setSkinLocation(location); // Cache it for next time

                // Trigger async transparency analysis for the skin texture
                com.quickskin.mod.common.util.TextureAlphaDetector.analyzeTextureAsync(location);

                // Network appearances normally arrive before their texture bytes. Ears metadata is
                // parsed only when those bytes are registered, so associate it at this late
                // resolution point as well as on the immediate local-asset path.
                associateEarsFeatures(playerId, location);

                return location;
            }
        }

        return null;
    }

    //? if <1.21.11 {
    private void associateEarsFeatures(UUID playerId, ResourceLocation skinLocation) {
    //?} else {
    private void associateEarsFeatures(UUID playerId, Identifier skinLocation) {
    //?}
        if (!com.quickskin.mod.client.compat.EarsCompatIntegration.isAvailable()) return;
        String username = getPlayerUsername(playerId);
        com.quickskin.mod.client.compat.EarsCompatIntegration
                .associateWithPlayer(skinLocation, playerId, username);
    }

    @Nullable
    //? if <1.21.11 {
    public ResourceLocation getCapeLocation(UUID playerId) {
    //?} else {
    public Identifier getCapeLocation(UUID playerId) {
    //?}
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance == null) {
            return null;
        }

        // If the location is already cached, return it.
        if (appearance.getCapeLocation() != null) {
            // Resolve animation frame at source level so any mod reading
            // capeTexture (e.g. WaveyCapes) gets the current frame, not the atlas.
            return CapeAnimationHelper.resolveCurrentFrame(
                    appearance.getCapeLocation(), appearance.getCapeId());
        }

        // If not cached, try to resolve it now.
        if (appearance.getCapeId() != null && !appearance.getCapeId().isEmpty()) {
            //? if <1.21.11 {
            ResourceLocation location = capeService.getCapeLocation(playerId, appearance.getCapeId());
            //?} else {
            Identifier location = capeService.getCapeLocation(playerId, appearance.getCapeId());
            //?}
            if (location != null) {
                appearance.setCapeLocation(location); // Cache it for next time

                // Trigger async transparency analysis for the cape texture
                com.quickskin.mod.common.util.TextureAlphaDetector.analyzeTextureAsync(location);

                return CapeAnimationHelper.resolveCurrentFrame(location, appearance.getCapeId());
            }
        }

        return null;
    }

    /** Marks only renderer-confirmed skin use as part of the protected network working set. */
    public void markSkinVisible(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance != null && appearance.getSkinId() != null
                && appearance.getSkinId().startsWith("local_skin:")) {
            com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                    .markTextureInUse(
                            appearance.getSkinId().substring("local_skin:".length()), "skin");
        }
    }

    /** Applies an authoritative S2C update without reflecting it back to the server. */
    public void applyLookFromNetwork(
            UUID playerId, @Nullable String skinId,
            @Nullable String capeId, @Nullable String model) {
        boolean previous = applyingNetworkUpdate;
        applyingNetworkUpdate = true;
        try {
            applyLook(playerId, skinId, capeId, model);
        } finally {
            applyingNetworkUpdate = previous;
        }
    }

    @Nullable
    public String getModelName(UUID playerId) {
        String override = modelService.getModelOverride(playerId);
        if (override != null) {
            if ("auto".equals(override != null ? override.toLowerCase(Locale.ROOT) : null)) {
                PlayerAppearance appearance = repository.getAppearance(playerId);
                if (appearance != null) {
                    String skinId = appearance.getSkinId();
                    return modelService.getModelType(playerId, skinId, "auto");
                }
            }
            return override;
        }

        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null ? appearance.getModel() : null;
    }

    @Nullable
    private String getPlayerUsername(UUID playerId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            net.minecraft.world.entity.player.Player player = mc.level.getPlayerByUUID(playerId);
            if (player != null) {
                //? if <1.21.9 {
                return player.getGameProfile().getName();
                //?} else {
                return player.getGameProfile().name();
                //?}
            }
        }
        return null;
    }

    /**
     * Reloads all player skin textures to apply transparency setting changes.
     * This method is granular and only affects skins, leaving capes untouched.
     */
    public void reloadSkinsForTransparencyChange() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        // Clear texture alpha detection cache since transparency settings changed
        com.quickskin.mod.common.util.TextureAlphaDetector.clearCache();

        // Clear ONLY skin textures from local cache (not capes!)
        LocalAssetManager.getInstance().clearSkinTextureCache();

        // Reprocess network skin textures with new transparency setting (reprocesses from original data)
        com.quickskin.mod.client.storage.NetworkTextureCache.getInstance().reprocessSkins();

        // Presentation adapters observe this event; the service does not depend on a concrete screen.
        eventBus.post(new SkinTexturesReloadedEvent(
                SkinTexturesReloadedEvent.Reason.TRANSPARENCY_POLICY));

        // Re-apply player appearances without echoing uploads back to the server.
        reloadingTransparency = true;
        try {
            if (mc.level != null && mc.level.players() != null) {
                java.util.List<net.minecraft.world.entity.player.Player> players =
                        new java.util.ArrayList<>(mc.level.players());

                for (net.minecraft.world.entity.player.Player player : players) {
                    if (player != null) {
                        com.quickskin.mod.common.data.PlayerAppearance appearance =
                                getAppearance(player.getUUID());
                        if (appearance != null) {
                            appearance.setSkinLocation(null);
                            applyLook(player.getUUID(), appearance.getSkinId(),
                                    appearance.getCapeId(), appearance.getModel());
                        }
                    }
                }
            }
        } finally {
            reloadingTransparency = false;
        }
    }
}
