package com.quickskin.mod.event;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.event.InternalEventBus;
import com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent;
import com.quickskin.mod.common.event.ServerConfigSyncEvent;
import com.quickskin.mod.common.event.SkinTexturesReloadedEvent;
import com.quickskin.mod.runtime.ClientRuntime;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.client.ClientRawInputEvent;
import dev.architectury.event.events.client.ClientScreenInputEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;

import java.awt.image.BufferedImage;

/**
 * Client-side event handlers
 * Uses Architectury's event system for cross-platform compatibility
 */
@Environment(EnvType.CLIENT)
public class ClientEvents {

    private static final java.util.List<InternalEventBus.Subscription> INTERNAL_SUBSCRIPTIONS =
            new java.util.ArrayList<>();
    private static boolean initialized;
    private static volatile boolean closed;
    private static java.util.concurrent.CompletableFuture<?> playerOwnSkinTask;
    private static volatile boolean playerOwnSkinBootstrapped;

    public static String getSharedAnimation() {
        return com.quickskin.mod.client.services.PreviewAnimationState.get();
    }

    public static void setSharedAnimation(String animation) {
        com.quickskin.mod.client.services.PreviewAnimationState.set(animation);
    }

    /**
     * Initializes client event listeners
     * Called from QuickSkinClient.init()
     */
    public static void init() {
        init(ClientRuntime.getInstance());
    }

    /** Registers platform callbacks and binds them to the client-owned session runtime. */
    public static synchronized void init(ClientRuntime clientRuntime) {
        if (initialized) {
            return;
        }
        if (clientRuntime == null) {
            throw new IllegalArgumentException("clientRuntime cannot be null");
        }
        closed = false;
        initialized = true;

        registerInternalListeners();

        CapeTransparencyEvents.register();

        // Client tick (fires every game tick, ~20 times per second)
        ClientTickEvent.CLIENT_POST.register(client -> {
            // The session user is not readable from every platform's client entry point, so retry
            // the own-skin bootstrap from the first tick that runs. It disarms itself once started.
            ensurePlayerOwnSkinExists();

            com.quickskin.mod.client.compat.CPMCompatIntegration
                    .prepareForBackgroundModelLoading();
            // This also ensures the singleton instance is created.
            com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                    .tickWorkingSet();
            AnimatedTextureManager.getInstance().tick();
            com.quickskin.mod.networking.ClientNetworkHandler.tick();
            com.quickskin.mod.networking.NetworkSyncService.getInstance().tick();
            //? if >=1.21 {
            com.quickskin.mod.client.compat.ReplayModHelper.tick();
            //?}
        });

        // Download player's own skin on startup (async, won't block). Platforms whose client entry
        // point runs before Minecraft exists retry this from the client tick registered above.
        ensurePlayerOwnSkinExists();

        // Player joins world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> {
            clientRuntime.beginSession(
                    player != null ? player.getUUID() : null,
                    player != null ? player.connection : Minecraft.getInstance().getConnection());
            resetSessionUiState();

            //? if <1.21 {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.hasSingleplayerServer()) {
                com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
                com.quickskin.mod.config.ClientConfig.getInstance().applyServerOverride(serverConfig);
            }
            //?}
            // Restore saved skin and model type from config
            restoreSavedAppearance(player);
        });

        // Player quits world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> {
            boolean ended = clientRuntime.endSession(
                    player != null ? player.getUUID() : null,
                    player != null ? player.connection : null);
            if (!ended) {
                return;
            }
            resetSessionUiState();

            // Re-register appearance for Essential's title screen player model
            com.quickskin.mod.client.compat.EssentialCompatIntegration.registerMenuAppearance();
        });

        // Respawn event (player dies and respawns)
        ClientPlayerEvent.CLIENT_PLAYER_RESPAWN.register((oldPlayer, newPlayer) -> {

            // Re-apply appearance after respawn
            restoreSavedAppearance(newPlayer);
        });

        com.quickskin.mod.client.gui.integration.MenuIntegration.init(parent ->
                com.quickskin.mod.client.gui.GuiCompat.openScreen(new PlayerSkinMenuScreen(parent)));

        com.quickskin.mod.client.gui.overlay.HudPreviewIntegration.init();

        //? if <1.21 {
        ClientScreenInputEvent.MOUSE_RELEASED_PRE.register((client, screen, mouseX, mouseY, button) -> {
            com.quickskin.mod.client.gui.widget.PlayerWidget activeWidget =
                com.quickskin.mod.client.gui.widget.PlayerWidget.getActiveInteractionWidget();
        //?} else {
        // Debug screen toggle (F3)
            //? if <1.21.9 {
        ClientScreenInputEvent.KEY_PRESSED_PRE.register((client, screen, keyCode, scanCode, modifiers) -> {
            //?} else {
        ClientScreenInputEvent.KEY_PRESSED_PRE.register((client, screen, keyEvent) -> {
            //?}
            // This event is for screen key presses
            // Keybinds are handled separately in KeybindRegistry
            return EventResult.pass();
        });
        //?}

            //? if <1.21 {
            if (activeWidget != null && activeWidget.isInteracting()) {
                boolean handled = activeWidget.mouseReleased(mouseX, mouseY, button);
                if (handled) {
                    return EventResult.interruptTrue(); // Consume the event
                }
            }
            //?} else {
        // Raw input (for global keybinds outside of screens)
            //? if <1.21.9 {
        ClientRawInputEvent.KEY_PRESSED.register((client, keyCode, scanCode, action, modifiers) -> {
            //?} else {
        ClientRawInputEvent.KEY_PRESSED.register((client, action, keyEvent) -> {
            //?}
            // Keybinds will be registered separately
            // This is for raw key detection if needed
            //?}
            return EventResult.pass();
        });

        // CPM cache invalidation must happen after HUD extraction, including hidden HUDs.
        ClientGuiEvent.RENDER_HUD.register((guiGraphics, tickDelta) -> {
            if (getCurrentScreen(Minecraft.getInstance()) == null) {
                com.quickskin.mod.client.compat.CPMCompatIntegration.onRenderedFrameBoundary();
            }
        });

    }

    /** Connects service/domain changes to rendering and presentation adapters. */
    private static void registerInternalListeners() {
        InternalEventBus eventBus = InternalEventBus.getInstance();
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                PlayerAppearanceUpdateEvent.class,
                ClientEvents::onPlayerAppearanceUpdated));
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                SkinTexturesReloadedEvent.class,
                ClientEvents::onSkinTexturesReloaded));
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                ServerConfigSyncEvent.class,
                ClientEvents::onServerConfigSynced));
        //? if >=1.21 {
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                com.quickskin.mod.common.event.NetworkAppearanceAppliedEvent.class,
                event -> com.quickskin.mod.client.compat.ReplayModHelper.noteNetworkAppearance(event.playerId())));
        //?}
    }

    private static void onPlayerAppearanceUpdated(PlayerAppearanceUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null
                || !event.playerId().equals(minecraft.player.getUUID())) {
            return;
        }

        // Preview rendering observes domain changes without coupling the service to a concrete UI.
        com.quickskin.mod.client.rendering.PlayerModelRenderer.clearCachedPlayer();
    }

    private static void onSkinTexturesReloaded(SkinTexturesReloadedEvent event) {
        if (event.reason() != SkinTexturesReloadedEvent.Reason.TRANSPARENCY_POLICY) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Screen screen = getCurrentScreen(minecraft);
        if (screen instanceof PlayerSkinMenuScreen skinMenu) {
            skinMenu.refreshSkinList();
        }
    }

    private static void onServerConfigSynced(ServerConfigSyncEvent event) {
        // Disallowed transparency invalidates any alpha result captured before the server policy arrived.
        if (!event.isAllowTransparentSkins()) {
            com.quickskin.mod.common.util.TextureAlphaDetector.clearCache();
        }
        com.quickskin.mod.client.rendering.PlayerModelRenderer.clearCachedPlayer();
    }

    private static Screen getCurrentScreen(Minecraft minecraft) {
        //? if <26.2 {
        return minecraft.screen;
        //?} else {
        return minecraft.gui.screen();
        //?}
    }

    private static void resetSessionUiState() {
        com.quickskin.mod.client.gui.integration.MenuIntegration.resetSessionState();
        com.quickskin.mod.client.gui.overlay.HudPreviewIntegration.resetSessionState();
    }

    /** Unregisters internal service listeners during explicit client shutdown. */
    public static synchronized void close() {
        closed = true;
        if (playerOwnSkinTask != null) {
            playerOwnSkinTask.cancel(true);
            playerOwnSkinTask = null;
        }
        for (InternalEventBus.Subscription subscription : INTERNAL_SUBSCRIPTIONS) {
            try {
                subscription.close();
            } catch (RuntimeException error) {
                QuickSkinInfo.LOGGER.warn("Failed to unregister a QuickSkin internal event listener", error);
            }
        }
        INTERNAL_SUBSCRIPTIONS.clear();
        resetSessionUiState();
    }

    /**
     * Ensure player's own skin exists in the list
     * Downloads it from Mojang if not present
     * Can be called at any time (even before joining a world)
     *
     * <p>Idempotent and cheap to call repeatedly: it runs at most one bootstrap per client
     * session. Client entry points do not agree on when the session user becomes readable -
     * FML constructs mods before {@link Minecraft} exists, so the very first attempt has no
     * user to look up - therefore the attempt stays pending instead of being consumed, and the
     * client tick retries it as soon as the session is available.
     */
    private static void ensurePlayerOwnSkinExists() {
        if (playerOwnSkinBootstrapped || closed) {
            return;
        }
        startPlayerOwnSkinBootstrap();
    }

    private static synchronized void startPlayerOwnSkinBootstrap() {
        if (playerOwnSkinBootstrapped || closed) {
            return;
        }

        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.enablePlayerOwnSkinSystem) {
            playerOwnSkinBootstrapped = true;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getUser() == null) {
            // No session yet; leave the bootstrap pending for the next client tick.
            return;
        }

        String playerName = minecraft.getUser().getName();
        playerOwnSkinBootstrapped = true;

        // Check if we already have the player's skin hash and it exists
        if (!config.playerOwnSkinHash.isEmpty()) {
            AssetMetadata existingMetadata = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
            if (existingMetadata != null) {
                // Player's skin already exists
                return;
            }
        }

        // Download player's own skin (async, won't block startup)
        playerOwnSkinTask = com.quickskin.mod.client.services.MojangApiService.getInstance()
                .fetchSkinByUsername(playerName)
                .thenAccept(skinData -> {
                    if (!closed && minecraft != null) {
                        minecraft.execute(() -> {
                            if (!closed && skinData != null) {
                                handlePlayerOwnSkinFetched(skinData);
                            }
                        });
                    }
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                            && throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (!(cause instanceof java.util.concurrent.CancellationException)) {
                        QuickSkinInfo.LOGGER.warn("Could not download the local player's Mojang skin", throwable);
                    }
                    return null;
                });
    }

    /**
     * Handle the fetched player's own skin data
     * Smart mode: checks if skin already exists before saving a duplicate
     */
    private static void handlePlayerOwnSkinFetched(com.quickskin.mod.client.services.MojangApiService.MojangSkinData skinData) {
        try {
            // Process the image to get its final form before hashing and saving.
            // This ensures the hash we check against is the same as the one that will be generated from the saved file.
            BufferedImage image = skinData.image;

            // Convert legacy 64x32 skins to modern 64x64 format
            if (image.getHeight() == image.getWidth() / 2) {
                image = com.quickskin.mod.common.util.HDTextureProcessor.convertLegacyToModern(image);
            }

            // Apply transparency settings if needed
            if (com.quickskin.mod.config.ClientConfig.getInstance().shouldDisableSkinTransparency()) {
                image = com.quickskin.mod.common.util.HDTextureProcessor.removeTransparency(image);
            }

            // Convert the (potentially modified) image to a byte array to compute its definitive hash.
            byte[] processedImageBytes = com.quickskin.mod.common.util.HDTextureProcessor.imageToPng(image);
            if (processedImageBytes == null) {
                return;
            }

            String finalHash = com.quickskin.mod.common.util.HashUtil.computeAssetContentId(
                    processedImageBytes, "skin");
            if (finalHash == null) {
                return;
            }

            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata existingMetadata = assetManager.getMetadata(finalHash);

            if (existingMetadata == null) {
                java.nio.file.Path saved = com.quickskin.mod.client.gui.util.SkinImporter
                        .saveSkinImage(image, skinData.username);
                if (saved == null) return;

                // Reload assets to recognize the new file.
                assetManager.reload();
                if (assetManager.getMetadata(finalHash) == null) {
                    QuickSkinInfo.LOGGER.warn("Downloaded Mojang skin was saved with an unexpected content hash");
                    return;
                }
            }

            // Now that the skin is guaranteed to be in the asset manager, set its hash in the config.
            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            config.playerOwnSkinHash = finalHash;

            if (config.activeSkinHash.isEmpty() && config.activeCpmModelHash.isEmpty()) {
                config.activeSkinHash = finalHash;

                // Apply it to the player if they're in a world.
                net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
                if (player != null) {
                    AssetMetadata metadata = assetManager.getMetadata(finalHash);
                    if (metadata != null) {
                        String skinId = "local_skin:" + finalHash;
                        String modelType = assetManager.getSkinModelPreference(finalHash);

                        com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                                .applySkin(player.getUUID(), skinId, modelType);
                    }
                }
            }

            config.save();

        } catch (Exception e) {
            QuickSkinInfo.LOGGER.error("Could not import the local player's Mojang skin", e);
        }
    }

    /**
     * Restore saved skin and cape from config when player joins world
     */
    private static void restoreSavedAppearance(LocalPlayer player) {
    //? if <1.21 {
        boolean isReplay = com.quickskin.mod.client.compat.ReplayModHelper.isInReplay();
        if (isReplay) {
            com.quickskin.mod.client.compat.ReplayModHelper.startReplayPlayerWatcher();
            return;
        }
        restoreSavedAppearanceToPlayer(player.getUUID());
    }
    private static void restoreSavedAppearanceToPlayer(java.util.UUID targetPlayerId) {
    //?}
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        com.quickskin.mod.client.services.LocalAssetManager assetManager =
                com.quickskin.mod.client.services.LocalAssetManager.getInstance();
        //? if >=1.21 {

        String skinId = null;
        String modelType = null;
        String capeId = null;
        //?}

        // Check if there's a saved skin
        if (!config.activeSkinHash.isEmpty()) {
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                //? if <1.21 {
                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(targetPlayerId, skinId, modelType);
                //?} else {
                // Prepare the saved skin with the saved model type preference for this skin
                skinId = "local_skin:" + metadata.hash();
                modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                //?}
            }
        } else if (!config.playerOwnSkinHash.isEmpty() && config.activeCpmModelHash.isEmpty()) {
            // No skin selected, but player's own skin exists - auto-select it
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.playerOwnSkinHash);

            if (metadata != null) {
                // Auto-select and apply the player's own skin
                config.activeSkinHash = config.playerOwnSkinHash;
                config.save();

                //? if <1.21 {
                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.playerOwnSkinHash);
                //?} else {
                skinId = "local_skin:" + metadata.hash();
                modelType = assetManager.getSkinModelPreference(config.playerOwnSkinHash);
                //?}

                // If auto mode, use the detected model from the skin
                if ("auto".equals(modelType)) {
                    modelType = metadata.skinModel();
                }
                //? if <1.21 {
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(targetPlayerId, skinId, modelType);
                //?}
            }
        }

        // Check if there's a saved cape
        if (!config.activeCapeHash.isEmpty()) {
            //? if <1.21 {
            String capeId = config.activeCapeHash;
            //?} else {
            capeId = config.activeCapeHash;
        }
            //?}

        //? if >=1.21 {
        // Apply both skin and cape together in a single call to avoid multiple syncs
        if (skinId != null || capeId != null) {
        //?}
            com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                    //? if <1.21 {
                    .applyCape(targetPlayerId, capeId);
                    //?} else {
                    .applyLook(player.getUUID(), skinId, capeId, modelType);
                    //?}
        }
    }

    /**
     * Auto-select player's own skin if no skin is currently selected
     * Called during initialization to ensure base skin is always selected
     */
    public static void autoSelectPlayerOwnSkin() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        if (config.activeSkinHash.isEmpty() && config.activeCpmModelHash.isEmpty() && !config.playerOwnSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(config.playerOwnSkinHash);

            if (metadata != null) {
                // Auto-select the player's own skin
                config.activeSkinHash = config.playerOwnSkinHash;
                config.save();
            }
        }
    }

}
