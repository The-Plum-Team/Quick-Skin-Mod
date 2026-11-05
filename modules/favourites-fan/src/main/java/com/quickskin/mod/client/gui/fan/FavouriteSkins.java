package com.quickskin.mod.client.gui.fan;

import com.quickskin.mod.client.compat.CpmLook;
import com.quickskin.mod.client.compat.CpmModelWorkflow;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The skins and CPM models marked as favourites, in the skin menu's order, and how the fan puts
 * one on: the same path as a click in the skin menu.
 */
@Environment(EnvType.CLIENT)
final class FavouriteSkins {
    enum Result { WORN, ALREADY_WORN, COOLDOWN, FAILED }

    /** When this client last changed the look on a server with a cooldown, which it learns only after the change. */
    private static long lastChangeMillis;

    private FavouriteSkins() {
    }

    static List<AssetMetadata> list() {
        LocalAssetManager assets = LocalAssetManager.getInstance();
        List<AssetMetadata> favourites = new ArrayList<>();
        for (AssetMetadata metadata : assets.getAllSkins()) {
            if (assets.isFavorite(metadata.hash())) {
                favourites.add(metadata);
            }
        }
        return favourites;
    }

    /** The arm width a skin is worn with: its saved preference, or what its pixels show. */
    static String modelType(AssetMetadata metadata) {
        String preference = LocalAssetManager.getInstance().getSkinModelPreference(metadata.hash());
        if (preference == null || "auto".equalsIgnoreCase(preference)) {
            preference = metadata.skinModel();
        }
        return preference != null && "slim".equals(preference.toLowerCase(Locale.ROOT)) ? "slim" : "classic";
    }

    /** Whether the worn look is this favourite. */
    static boolean isWorn(AssetMetadata metadata) {
        ClientConfig config = ClientConfig.getInstance();
        if (metadata.isCpmModel()) {
            return metadata.hash().equals(config.activeCpmModelHash)
                    && CpmLook.owner() == CpmLook.Owner.QUICK_SKIN_MODEL;
        }
        return metadata.hash().equals(config.activeSkinHash)
                && config.activeCpmModelHash.isEmpty()
                && !CpmLook.owner().withholdsQuickSkinSkin();
    }

    /** Seconds left before the server takes another change, 0 when it takes one now. */
    static long cooldownSeconds(Minecraft minecraft) {
        //? if <26.2 {
        boolean singleplayer = minecraft.isSingleplayer();
        //?} else {
        boolean singleplayer = minecraft.hasSingleplayerServer();
        //?}
        if (singleplayer) {
            return 0;
        }
        CooldownService cooldown = CooldownService.getInstance();
        if (cooldown.isCooldownActive()) {
            return Math.max(1, cooldown.getRemainingCooldownSeconds());
        }
        // The server tells its cooldown only after it accepted a change, so a second pick right
        // after the first would be dropped without a word
        ServerConfig server = ClientConfig.getInstance().getServerOverride();
        if (server != null && server.skinChangeCooldownSeconds > 0 && lastChangeMillis > 0) {
            long left = server.skinChangeCooldownSeconds * 1000L - (System.currentTimeMillis() - lastChangeMillis);
            if (left > 0) {
                return Math.max(1, (left + 999) / 1000);
            }
        }
        return 0;
    }

    /** Puts {@code metadata} on, as a click on it in the skin menu does. */
    static Result wear(Minecraft minecraft, AssetMetadata metadata) {
        if (minecraft.player == null) {
            return Result.FAILED;
        }
        if (isWorn(metadata)) {
            return Result.ALREADY_WORN;
        }
        if (cooldownSeconds(minecraft) > 0) {
            return Result.COOLDOWN;
        }
        UUID player = minecraft.player.getUUID();
        PlayerAppearanceService appearances = PlayerAppearanceService.getInstance();
        if (metadata.isCpmModel()) {
            if (!CpmModelWorkflow.activateModel(metadata)) {
                return Result.FAILED;
            }
            // The model replaces the applied Quick Skin skin, here and on the server; the cape stays
            if (appearances.hasOwnActiveSkin(player)) {
                appearances.applySkin(player, "", null);
            }
        } else {
            CpmModelWorkflow.activateSkin(metadata.hash());
            appearances.applySkin(player, "local_skin:" + metadata.hash(), modelType(metadata));
        }
        lastChangeMillis = System.currentTimeMillis();
        return Result.WORN;
    }

    static void reset() {
        lastChangeMillis = 0;
    }
}
