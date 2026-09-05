package com.quickskin.mod.event;

import com.quickskin.mod.common.util.TextureAlphaDetector;
import dev.architectury.registry.ReloadListenerRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
//? if <1.21.2 {
import net.minecraft.util.profiling.ProfilerFiller;
//?}

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Environment(EnvType.CLIENT)
public class CapeTransparencyEvents implements PreparableReloadListener {

    //? if <1.21.11 {
        //? if <1.21 {
    public static final ResourceLocation LISTENER_ID = new ResourceLocation("quickskin", "cape_transparency_cache_clearer");
        //?} else {
    public static final ResourceLocation LISTENER_ID = ResourceLocation.fromNamespaceAndPath("quickskin", "cape_transparency_cache_clearer");
        //?}
    //?} else {
    public static final Identifier LISTENER_ID = Identifier.fromNamespaceAndPath("quickskin", "cape_transparency_cache_clearer");
    //?}

    public static void register() {
        ReloadListenerRegistry.register(PackType.CLIENT_RESOURCES, new CapeTransparencyEvents(), LISTENER_ID);
    }

    @Override
    //? if <1.21.2 {
    public final CompletableFuture<Void> reload(PreparationBarrier preparationBarrier, ResourceManager resourceManager, ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler, Executor backgroundExecutor, Executor gameExecutor) {
    //?} else if <1.21.11 {
    public final CompletableFuture<Void> reload(PreparationBarrier preparationBarrier, ResourceManager resourceManager, Executor backgroundExecutor, Executor gameExecutor) {
    //?} else {
    public final CompletableFuture<Void> reload(PreparableReloadListener.SharedState sharedState, Executor backgroundExecutor, PreparableReloadListener.PreparationBarrier preparationBarrier, Executor gameExecutor) {
    //?}
        return preparationBarrier.wait(null).thenRunAsync(() -> {
            TextureAlphaDetector.clearCache();
            com.quickskin.mod.client.services.LocalAssetManager.getInstance().reload();
        }, gameExecutor);
    }
}
