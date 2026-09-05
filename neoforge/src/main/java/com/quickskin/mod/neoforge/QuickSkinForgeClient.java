package com.quickskin.mod.neoforge;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.QuickSkinClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/**
 * NeoForge client entry point for QuickSkin
 * This class is only loaded on NeoForge clients (not dedicated servers)
 */
//? if <1.21.11 {
@EventBusSubscriber(modid = QuickSkinInfo.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
//?} else {
@EventBusSubscriber(modid = QuickSkinInfo.MOD_ID, value = Dist.CLIENT)
//?}
public class QuickSkinForgeClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Initialize client code on the main thread
        event.enqueueWork(() -> {
            QuickSkinClient.init();
        });
    }

    // Close client resources from the game-bus lifecycle hook.
//? if <1.21.11 {
    @EventBusSubscriber(modid = QuickSkinInfo.MOD_ID, bus = EventBusSubscriber.Bus.GAME,
            value = Dist.CLIENT)
    public static final class Shutdown {
        private Shutdown() {}

        @SubscribeEvent
        public static void onGameShuttingDown(GameShuttingDownEvent event) {
            QuickSkinClient.close();
        }
    }
//?} else {
    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        QuickSkinClient.close();
    }
//?}
}
