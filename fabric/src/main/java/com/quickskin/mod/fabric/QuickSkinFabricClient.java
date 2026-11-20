package com.quickskin.mod.fabric;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.QuickSkinClient;
import com.quickskin.mod.client.compat.AccessoryCapeVisibility;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRenderEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * Fabric client entry point for QuickSkin
 * This class is only loaded on Fabric clients (not dedicated servers)
 */
public class QuickSkinFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        AccessoryCapeVisibility.use(wearer -> LivingEntityFeatureRenderEvents.ALLOW_CAPE_RENDER.invoker().allowCapeRender(wearer));
        QuickSkinClient.init();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> QuickSkinClient.close());
    }
}
