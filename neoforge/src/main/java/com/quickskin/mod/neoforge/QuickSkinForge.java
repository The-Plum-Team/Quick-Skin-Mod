package com.quickskin.mod.neoforge;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * NeoForge entry point for QuickSkin
 * This class is only loaded on NeoForge
 */
@Mod(QuickSkinInfo.MOD_ID)
public class QuickSkinForge {

    public QuickSkinForge(net.neoforged.bus.api.IEventBus modEventBus) {
        // Note: Architectury automatically registers events for NeoForge
        // No manual event bus registration needed in 1.21.1
    }

    /**
     * Common setup event handler (runs on both client and server)
     * Uses @EventBusSubscriber to ensure it's called properly
     */
//? if <1.21.6 {
    @EventBusSubscriber(modid = QuickSkinInfo.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
//?} else {
    @EventBusSubscriber(modid = QuickSkinInfo.MOD_ID)
//?}
    public static class CommonSetup {
        @SubscribeEvent
        public static void onCommonSetup(FMLCommonSetupEvent event) {
            // Initialize common code during the common setup phase
            // This ensures networking is registered at the correct time
            event.enqueueWork(() -> {
                QuickSkin.init();
            });
        }
    }
}
