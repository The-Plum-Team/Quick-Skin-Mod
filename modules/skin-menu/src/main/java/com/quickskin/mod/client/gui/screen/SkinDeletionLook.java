package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.common.data.AssetMetadata;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What deleting the saved Quick Skin skin does to the look. While Quick Skin's choice is the look,
 * the skin is worn, and the first listed entry is worn in its place (the own skin, pinned first,
 * when it is listed). While a CPM model is the look, the saved skin is only remembered: the next
 * listed skin is remembered in its place, and neither CPM nor the worn look changes.
 */
final class SkinDeletionLook {
    enum Action {
        /** The saved skin was not deleted: nothing changes. */
        NONE,
        /** Wear {@link Outcome#next()}, as a click on it does. */
        WEAR,
        /** Remember {@link Outcome#next()} (or no skin, when null) without wearing it. */
        REMEMBER
    }

    record Outcome(Action action, @Nullable AssetMetadata next) {
    }

    private SkinDeletionLook() {
    }

    static Outcome decide(boolean savedSkinDeleted, boolean cpmModelIsTheLook, List<AssetMetadata> listed) {
        if (!savedSkinDeleted) {
            return new Outcome(Action.NONE, null);
        }
        AssetMetadata firstSkin = listed.stream().filter(meta -> !meta.isCpmModel()).findFirst().orElse(null);
        if (cpmModelIsTheLook) {
            // Never a CPM model: that would be a look choice the player did not make
            return new Outcome(Action.REMEMBER, firstSkin);
        }
        if (listed.isEmpty()) {
            return new Outcome(Action.NONE, null);
        }
        return new Outcome(Action.WEAR, firstSkin != null ? firstSkin : listed.get(0));
    }
}
