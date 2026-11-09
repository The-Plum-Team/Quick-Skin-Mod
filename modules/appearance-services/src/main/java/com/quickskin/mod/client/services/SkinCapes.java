package com.quickskin.mod.client.services;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.KnownCapes;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.config.ClientConfig;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Capes belong to skins: every skin wears its own cape or none, and putting a skin on (from the
 * skin menu, the favourites fan or a join) puts its cape on. The cape menu picks the cape of the
 * skin worn, or of the skin it was opened for. A skin without a cape wears {@link #NO_CAPE}, which
 * shows no cape at all, not even the account's Mojang cape, also to other players. A CPM model
 * wears no Quick Skin cape ({@link #wearNone}). The choice lives in the skin's preferences
 * ({@link LocalAssetManager#getSkinCape}); a cape that has since been deleted counts as none, and
 * the choice comes back if the cape is imported again. A skin also chooses what the elytra wear
 * (the cape, vanilla's elytra or another cape); without a choice they wear the cape.
 */
public final class SkinCapes {

    /** The cape a skin without one wears: no cape, the account's Mojang cape included. */
    public static final String NO_CAPE = "known:" + KnownCapes.NONE.getId();

    private SkinCapes() {
    }

    /** Whether {@code capeId} means no cape at all. */
    public static boolean isNoCape(@Nullable String capeId) {
        return NO_CAPE.equals(capeId);
    }

    /** The cape {@code skinHash} puts on, or null when it has none or its cape no longer exists. */
    @Nullable
    public static String assigned(String skinHash) {
        String capeId = LocalAssetManager.getInstance().getSkinCape(skinHash);
        return capeId != null && exists(capeId) ? capeId : null;
    }

    /**
     * What the elytra wear with {@code skinHash}: "" the cape, {@code minecraft:elytra} vanilla's,
     * a cape ID, or null when the skin has no choice or its cape no longer exists.
     */
    @Nullable
    public static String assignedElytra(String skinHash) {
        String elytraId = LocalAssetManager.getInstance().getSkinElytra(skinHash);
        if (elytraId == null || elytraId.isEmpty() || PlayerAppearance.VANILLA_ELYTRA.equals(elytraId)) {
            return elytraId;
        }
        return exists(elytraId) ? elytraId : null;
    }

    /** Whether {@code skinHash} puts on a cape or elytra of its own. */
    public static boolean hasOwnLook(String skinHash) {
        return assigned(skinHash) != null || assignedElytra(skinHash) != null;
    }

    /** Gives {@code skinHash} what its elytra wear (see {@link #assignedElytra}), or none with null. */
    public static void assignElytra(String skinHash, @Nullable String elytraId) {
        LocalAssetManager.getInstance().setSkinElytra(skinHash, elytraId);
    }

    /** Gives {@code skinHash} the cape {@code capeId}, or takes its cape away with null. */
    public static void assign(String skinHash, @Nullable String capeId) {
        LocalAssetManager.getInstance().setSkinCape(skinHash, capeId);
    }

    /**
     * Puts on the cape {@code skinHash} has, or {@link #NO_CAPE} when it has none, and what its
     * elytra wear (the cape without a choice): saved as the active cape and, when {@code player} is
     * not null, applied to that player. Returns whether anything changed.
     */
    public static boolean wear(String skinHash, @Nullable UUID player) {
        String capeId = assigned(skinHash);
        String elytraId = assignedElytra(skinHash);
        return put(capeId != null ? capeId : NO_CAPE, elytraId != null ? elytraId : "", player);
    }

    /** Takes Quick Skin's cape off, as a CPM model, which wears no Quick Skin cape, does. */
    public static boolean wearNone(@Nullable UUID player) {
        return put("", "", player);
    }

    /**
     * Gives the skin worn when capes came to belong to skins the cape and elytra worn then, once.
     * The other skins have no cape until one is picked for them.
     */
    public static void migrateWornCapeOnce() {
        ClientConfig config = ClientConfig.getInstance();
        if (config.capesFollowSkins) return;
        String skin = config.activeSkinHash;
        if (skin.isEmpty() || LocalAssetManager.getInstance().getMetadata(skin) == null) return; // retried later
        if (assigned(skin) == null && !config.activeCapeHash.isEmpty() && !isNoCape(config.activeCapeHash)
                && exists(config.activeCapeHash)) {
            assign(skin, config.activeCapeHash);
        }
        if (assignedElytra(skin) == null && !config.activeElytraId.isEmpty()) {
            assignElytra(skin, config.activeElytraId);
        }
        config.capesFollowSkins = true;
        config.save();
    }

    private static boolean put(String capeId, String elytraId, @Nullable UUID player) {
        ClientConfig config = ClientConfig.getInstance();
        boolean changed = false;
        if (!capeId.equals(config.activeCapeHash)) {
            config.activeCapeHash = capeId;
            changed = true;
            if (!capeId.isEmpty() && !isNoCape(capeId)) {
                // Loads the texture and starts an animated cape's animation before the renderer asks for it
                CapeService.getInstance().getCapeLocation(null, capeId);
            }
            if (player != null) {
                PlayerAppearanceService.getInstance().applyCape(player, capeId);
            }
        }
        if (!elytraId.equals(config.activeElytraId)) {
            config.activeElytraId = elytraId;
            changed = true;
            if (!elytraId.isEmpty() && !PlayerAppearance.VANILLA_ELYTRA.equals(elytraId)) {
                CapeService.getInstance().getCapeLocation(null, elytraId);
            }
            if (player != null) {
                PlayerAppearanceService.getInstance().applyElytra(player, elytraId);
            }
        }
        if (changed) config.save();
        return changed;
    }

    private static boolean exists(String capeId) {
        if (capeId.startsWith("local_cape:")) {
            AssetMetadata metadata = LocalAssetManager.getInstance()
                    .getMetadata(capeId.substring("local_cape:".length()));
            return metadata != null && metadata.isCape();
        }
        if (capeId.startsWith("known:")) {
            KnownCapes cape = KnownCapes.getById(capeId.substring("known:".length()));
            return cape != null && !cape.isNoCape();
        }
        return false;
    }
}
