package com.terraformersmc.modmenu.api;

/**
 * Compile-only copy of the one method of Mod Menu's API that Quick Skin implements. Never packaged:
 * at runtime the interface is Mod Menu's own, and without Mod Menu nothing loads the entry point.
 * Mod Menu has kept this interface and package since 2.0 (Minecraft 1.16).
 */
public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> null;
    }
}
