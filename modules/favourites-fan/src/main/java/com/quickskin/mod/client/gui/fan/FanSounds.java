package com.quickskin.mod.client.gui.fan;

import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.sounds.SoundEvent;

/**
 * The favourites fan's own sounds, from {@code assets/quickskin/sounds.json}. They play only on
 * this client's interface, so they are named, not registered: no loader registry, server or other
 * player needs them, and a missing sound only logs Minecraft's own warning.
 */
@Environment(EnvType.CLIENT)
final class FanSounds {
    /** The cards fanned out over the hotbar. */
    static final SoundEvent OPEN = event("fan.open");
    /** The mouse moves onto another card. */
    static final SoundEvent POINT = event("fan.point");
    /** The mouse wheel turns the page. */
    static final SoundEvent PAGE = event("fan.page");
    /** A click on a card puts its skin on. */
    static final SoundEvent WEAR = event("fan.wear");

    private FanSounds() {
    }

    static void play(SoundEvent sound, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0f, volume));
    }

    private static SoundEvent event(String name) {
        //? if <1.21 {
        return SoundEvent.createVariableRangeEvent(new ResourceLocation(QuickSkinInfo.MOD_ID, name));
        //?} else if <1.21.11 {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, name));
        //?} else {
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, name));
        //?}
    }
}
