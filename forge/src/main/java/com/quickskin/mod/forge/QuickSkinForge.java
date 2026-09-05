package com.quickskin.mod.forge;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.platform.QuickSkinInfo;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge entry point for QuickSkin
 * This class is only loaded on Forge
 */
@Mod(QuickSkinInfo.MOD_ID)
public class QuickSkinForge {

    public QuickSkinForge() {
        // CRITICAL: Register Architectury event bus with Forge
        // This is required for Architectury events to work on Forge
        EventBuses.registerModEventBus(
            QuickSkinInfo.MOD_ID,
            FMLJavaModLoadingContext.get().getModEventBus()
        );
    }

    /**
     * Common setup event handler (runs on both client and server)
     * Uses @Mod.EventBusSubscriber to ensure it's called properly
     */
    @Mod.EventBusSubscriber(modid = QuickSkinInfo.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
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
