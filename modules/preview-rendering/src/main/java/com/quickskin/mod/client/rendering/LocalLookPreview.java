package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.services.CpmLookArbiter;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * The Quick Skin skin a preview of the local player's own look shows: the HUD overlay and the
 * title and pause menu previews. The latest look choice between Quick Skin and CPM decides it, as
 * it decides the world player's look.
 *
 * <p>In a world those previews draw the live player, so a CPM model that is the look is drawn by
 * CPM there; their own skin data only feeds the fallback without an entity. On the title screen
 * there is no entity and CPM draws nothing, so the preview draws that data. While a CPM model is
 * the look, the saved Quick Skin skin is only the skin to return to and no preview shows it: in a
 * world the data is the live player's own skin, on the title screen the player's own skin as
 * Quick Skin imported it, which is what players without CPM see under the model.</p>
 */
@Environment(EnvType.CLIENT)
public final class LocalLookPreview {
    private LocalLookPreview() {
    }

    /**
     * The skin hash to preview, or an empty string for the caller's own vanilla skin.
     *
     * @param inWorld whether the local player exists (false on the title screen)
     */
    public static String skinHash(boolean inWorld) {
        ClientConfig config = ClientConfig.getInstance();
        return skinHash(config.activeSkinHash, config.playerOwnSkinHash,
                CpmLookArbiter.cpmModelIsTheLook(), inWorld);
    }

    static String skinHash(String savedSkinHash, String ownSkinHash, boolean cpmModelIsTheLook,
                           boolean inWorld) {
        if (!cpmModelIsTheLook) {
            return orEmpty(savedSkinHash);
        }
        return inWorld ? "" : orEmpty(ownSkinHash);
    }

    private static String orEmpty(String hash) {
        return hash != null ? hash : "";
    }
}
