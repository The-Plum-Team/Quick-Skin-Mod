package com.quickskin.mod.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
//? if >=1.21 {
import net.minecraft.client.player.AbstractClientPlayer;
    //? if <1.21.9 {
import net.minecraft.client.resources.PlayerSkin;
    //?} else {
import net.minecraft.world.entity.player.PlayerSkin;
    //?}
//?}

public class PremiumDetector {
    public static boolean isPremiumAccount() {
        Minecraft mc = Minecraft.getInstance();

        //? if >=1.21 {
        // Try to get from player first (if in a world)
        if (mc.player instanceof AbstractClientPlayer clientPlayer) {
            PlayerSkin skin = clientPlayer.getSkin();
            return skin != null && skin.secure();
        }

        // Fallback: Check User object (works in main menu)
        //?}
        // Premium accounts have valid access tokens
        User user = mc.getUser();
        return user != null &&
               user.getAccessToken() != null &&
               !user.getAccessToken().isEmpty() &&
               !"0".equals(user.getAccessToken()); // Offline accounts have "0" as token
    }
}
