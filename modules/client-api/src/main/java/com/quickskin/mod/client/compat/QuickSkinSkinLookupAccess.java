package com.quickskin.mod.client.compat;

/**
 * Duck interface added to PlayerInfo on Minecraft 1.21 and later. PlayerInfo keeps the skin its
 * first lookup resolved, and Quick Skin's SkinManager hook bakes an active Quick Skin skin into
 * that result. Rebuilding the lookup after the skin is withdrawn returns the player's own vanilla
 * skin.
 */
public interface QuickSkinSkinLookupAccess {
    void quickskin$refreshSkinLookup();
}
