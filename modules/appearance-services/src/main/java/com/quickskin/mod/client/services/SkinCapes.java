package com.quickskin.mod.client.services;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.KnownCapes;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.config.ClientConfig;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A skin's own cape: picked from the skin menu's context menu, it is put on whenever the skin is,
 * from the skin menu or the favourites fan. A skin without one keeps whatever cape is worn. The
 * choice lives in the skin's preferences ({@link LocalAssetManager#getSkinCape}); a cape that has
 * since been deleted counts as none, and the choice comes back if the cape is imported again.
 * A skin may also choose what the elytra wear (the cape, vanilla's elytra or another cape); one
 * that does not keeps what they wear.
 */
public final class SkinCapes {

    private SkinCapes() {
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
     * Puts on the cape {@code skinHash} has, as a click on it in the cape menu does: saved as the
     * active cape and, when {@code player} is not null, applied to that player. Returns whether
     * the skin had a cape; one that is already the active cape is not applied again. The skin's
     * elytra choice, if it has one, is put on the same way.
     */
    public static boolean wear(String skinHash, @Nullable UUID player) {
        ClientConfig config = ClientConfig.getInstance();
        String capeId = assigned(skinHash);
        if (capeId != null && !capeId.equals(config.activeCapeHash)) {
            config.activeCapeHash = capeId;
            config.save();
            // Loads the texture and starts an animated cape's animation before the renderer asks for it
            CapeService.getInstance().getCapeLocation(null, capeId);
            if (player != null) {
                PlayerAppearanceService.getInstance().applyCape(player, capeId);
            }
        }
        String elytraId = assignedElytra(skinHash);
        if (elytraId != null && !elytraId.equals(config.activeElytraId)) {
            config.activeElytraId = elytraId;
            config.save();
            if (!elytraId.isEmpty() && !PlayerAppearance.VANILLA_ELYTRA.equals(elytraId)) {
                CapeService.getInstance().getCapeLocation(null, elytraId);
            }
            if (player != null) {
                PlayerAppearanceService.getInstance().applyElytra(player, elytraId);
            }
        }
        return capeId != null;
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
