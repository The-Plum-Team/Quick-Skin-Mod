package com.quickskin.mod.e2e.neoforge;

import com.quickskin.mod.e2e.E2EHarness;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Dev-only NeoForge entry point for the E2E harness, registered as a separate mod
 * ({@code quick_skin_e2e}) backed by the {@code e2e} source set. Added only to E2E client run configs;
 * never in the published jar.
 *
 * <p>Uses constructor {@link IEventBus} injection + a {@code FMLClientSetupEvent} listener rather than
 * {@code @EventBusSubscriber}, because the annotation's attributes drift across NeoForge versions
 * (the {@code bus = Bus.MOD} attribute present on 1.21.x was dropped on 26.x). The constructor form is
 * stable across both, so this single source compiles for every enabled NeoForge version. The harness
 * itself drives the cross-loader Architectury {@code ClientTickEvent}, so there is no NeoForge-specific
 * logic beyond bootstrapping on client setup.</p>
 */
@Mod(E2ENeoForge.MODID)
public class E2ENeoForge {

    public static final String MODID = "quick_skin_e2e";

    public E2ENeoForge(IEventBus modEventBus) {
        modEventBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(E2EHarness::start));
    }
}
