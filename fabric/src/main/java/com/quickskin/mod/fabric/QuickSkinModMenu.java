package com.quickskin.mod.fabric;

import com.quickskin.mod.QuickSkinClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu's entry point: Quick Skin's configuration button opens the skin menu, where the skin is
 * changed and from which the settings open. Mod Menu is optional; Fabric loads this class only for
 * Mod Menu, which brings the interfaces it implements.
 */
public class QuickSkinModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return QuickSkinClient::skinMenu;
    }
}
