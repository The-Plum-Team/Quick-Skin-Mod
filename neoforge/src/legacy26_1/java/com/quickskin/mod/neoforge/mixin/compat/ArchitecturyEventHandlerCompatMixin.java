package com.quickskin.mod.neoforge.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Gives the config plugin one fail-closed transformation point in Architectury's event bridge.
 * The class stays empty because the incompatible event type does not exist in this NeoForge API.
 */
@Pseudo
@Mixin(targets = "dev.architectury.event.forge.EventHandlerImplCommon", remap = false)
abstract class ArchitecturyEventHandlerCompatMixin {
}
