package com.quickskin.mod.client.services;

import com.quickskin.mod.client.api.ClientNetworkActions;
import com.quickskin.mod.client.api.ClientNetworkApi;
import com.quickskin.mod.config.AccountSkinPreferences;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.jetbrains.annotations.Nullable;

/**
 * The armour pieces the local player hides from everyone (ADR 0017): kept per launcher account in
 * the client configuration and handed to the server the player is on, which leaves them out of
 * what other players see. One bit per piece, as {@code HiddenArmor} defines them; a Cosmetic Armor
 * Reworked piece over an armour piece four bits up ({@link #cosmetic}).
 */
@Environment(EnvType.CLIENT)
public final class LocalHiddenArmor {
    /** The pieces, as {@code HiddenArmor}'s bits. */
    public static final int FEET = 1;
    public static final int LEGS = 1 << 1;
    public static final int CHEST = 1 << 2;
    public static final int HEAD = 1 << 3;
    private static final int ALL = FEET | LEGS | CHEST | HEAD;
    /** Every cosmetic piece. */
    public static final int COSMETICS = ALL << 4;

    /** The cosmetic piece over the armour piece {@code piece}. */
    public static int cosmetic(int piece) {
        return (piece & ALL) << 4;
    }

    /** The armour piece of a cosmetic or armour piece: the slot both are in. */
    public static int slot(int piece) {
        return (piece | piece >> 4) & ALL;
    }

    /** Whether {@code piece} is a cosmetic piece. */
    public static boolean isCosmetic(int piece) {
        return piece != 0 && (piece & COSMETICS) == piece;
    }

    private LocalHiddenArmor() {
    }

    /** The pieces this account hides. */
    public static int hidden() {
        return ClientConfig.getInstance().accountHiddenArmor.hidden(accountKey());
    }

    /** Hides a shown piece, or shows a hidden one, and tells the connected server. */
    public static void toggle(int piece) {
        set(hidden() ^ piece);
    }

    /** Hides exactly the pieces {@code hidden} names, and tells the connected server. */
    public static void set(int hidden) {
        String key = accountKey();
        if (key == null) return;
        ClientConfig config = ClientConfig.getInstance();
        int pieces = hidden & (ALL | COSMETICS);
        if (config.accountHiddenArmor.set(key, pieces)) config.save();
        ClientNetworkApi.actions().updateHiddenArmor(pieces);
    }

    /** Whether hiding armour works where the player is now, and if not, why. */
    public static ClientNetworkActions.ArmorHiding hiding() {
        return ClientNetworkApi.actions().armorHiding();
    }

    /** The pieces nobody sees now: those the player hides, where the server hides them. */
    public static int inEffect() {
        return ClientNetworkApi.actions().hiddenArmorInEffect();
    }

    /** Whether the server the player is on hides Cosmetic Armor Reworked pieces too. */
    public static boolean hidesCosmetics() {
        return ClientNetworkApi.actions().hidesCosmeticArmor();
    }

    @Nullable
    private static String accountKey() {
        User user = Minecraft.getInstance().getUser();
        return user == null ? null : AccountSkinPreferences.key(user.getProfileId(), user.getName());
    }
}
