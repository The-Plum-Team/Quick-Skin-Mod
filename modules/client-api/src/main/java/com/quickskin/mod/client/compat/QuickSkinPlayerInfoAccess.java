package com.quickskin.mod.client.compat;

/**
 * Duck interface added to PlayerInfo via mixin.
 * Allows external code to force re-registration of skin textures,
 * which is needed for CPM compatibility when skins change mid-game.
 */
public interface QuickSkinPlayerInfoAccess {
    void quickskin$forceReRegisterSkins();
}
