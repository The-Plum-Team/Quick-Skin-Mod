package com.quickskin.mod.event;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.importing.PlayerOwnSkinBootstrap;
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

/**
 * Client-side event handlers
 * Uses Architectury's event system for cross-platform compatibility
 */
@Environment(EnvType.CLIENT)
public class ClientEvents {

    private static final java.util.List<InternalEventBus.Subscription> INTERNAL_SUBSCRIPTIONS =
            new java.util.ArrayList<>();
    private static boolean initialized;

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
        PlayerOwnSkinBootstrap.initialize();
        initialized = true;

        registerInternalListeners();

        CapeTransparencyEvents.register();

        // Client tick (fires every game tick, ~20 times per second)
        ClientTickEvent.CLIENT_POST.register(client -> {
            // The session user is not readable from every platform's client entry point, so retry
            // the own-skin bootstrap from the first tick that runs. It disarms itself once started.
            PlayerOwnSkinBootstrap.ensurePlayerOwnSkinExists();

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
        PlayerOwnSkinBootstrap.ensurePlayerOwnSkinExists();

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
        PlayerOwnSkinBootstrap.close();
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

    /** Restores only a live game subject; the Replay integration owns replay subjects. */
    private static void restoreSavedAppearance(LocalPlayer player) {
        //? if <1.21 {
        if (com.quickskin.mod.client.compat.ReplayModHelper.isInReplay()) {
            com.quickskin.mod.client.compat.ReplayModHelper.startReplayPlayerWatcher();
            return;
        }
        //?}
        restoreSavedAppearanceToPlayer(player.getUUID());
    }

    private static void restoreSavedAppearanceToPlayer(java.util.UUID targetPlayerId) {
        com.quickskin.mod.client.services.SavedAppearanceRestorer.restore(targetPlayerId);
    }

    public static void autoSelectPlayerOwnSkin() {
        PlayerOwnSkinBootstrap.autoSelectPlayerOwnSkin();
    }
}
