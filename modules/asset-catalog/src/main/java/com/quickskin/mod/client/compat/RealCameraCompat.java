package com.quickskin.mod.client.compat;

import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.function.Predicate;

/**
 * Makes Quick Skin skin texture ids recognisable to Real Camera's default bind targets, which
 * only match ids containing {@code minecraft:skins/} or {@code minecraft:textures/entity/player/}.
 */
@Environment(EnvType.CLIENT)
public final class RealCameraCompat {
    static final String VANILLA_SKIN_PREFIX = "minecraft:skins/";
    private static final String NETWORK_SKIN = QuickSkinInfo.MOD_ID + ":network/skin/";
    private static final String LOCAL = QuickSkinInfo.MOD_ID + ":local/";

    private RealCameraCompat() {
    }

    /**
     * Returns the id prefixed with the vanilla skin marker when it names a Quick Skin skin, and
     * the id itself otherwise. The original id stays inside the result, so a bind target a user
     * created for a {@code quickskin:} id keeps matching.
     */
    public static String aliasSkinTextureId(String textureId) {
        return aliasSkinTextureId(textureId, RealCameraCompat::isLocalSkin);
    }

    static String aliasSkinTextureId(String textureId, Predicate<String> localSkin) {
        if (textureId == null || textureId.contains(VANILLA_SKIN_PREFIX)) return textureId;
        if (textureId.contains(NETWORK_SKIN)) return VANILLA_SKIN_PREFIX + textureId;
        // local/ is shared with capes and CPM icons, which must not reach the head bind target.
        // Real Camera may hand over a whole RenderType string, so the id is searched, not parsed.
        int marker = textureId.indexOf(LOCAL);
        if (marker >= 0) {
            int start = marker + LOCAL.length();
            int end = textureId.indexOf('_', start); // content ids and quality names have no '_'
            if (end > start && localSkin.test(textureId.substring(start, end))) {
                return VANILLA_SKIN_PREFIX + textureId;
            }
        }
        return textureId;
    }

    private static boolean isLocalSkin(String contentId) {
        AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(contentId);
        return metadata != null && metadata.isSkin();
    }
}
