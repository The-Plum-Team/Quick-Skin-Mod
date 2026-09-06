package com.quickskin.mod.forge;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.QuickSkinClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Forge client entry point for QuickSkin
 * This class is only loaded on Forge clients (not dedicated servers)
 */
@Mod.EventBusSubscriber(modid = QuickSkinInfo.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class QuickSkinForgeClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Initialize client code on the main thread
        event.enqueueWork(() -> {
            QuickSkinClient.init();
        });
    }

    /** Game-bus lifecycle hook; the outer subscriber listens on the mod bus. */
    @Mod.EventBusSubscriber(modid = QuickSkinInfo.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE,
            value = Dist.CLIENT)
    public static final class Shutdown {
        private Shutdown() {}

        @SubscribeEvent
        public static void onGameShuttingDown(GameShuttingDownEvent event) {
            QuickSkinClient.close();
        }
    }
}
