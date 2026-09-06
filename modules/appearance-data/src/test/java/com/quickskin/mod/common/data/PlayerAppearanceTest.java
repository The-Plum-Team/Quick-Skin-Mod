package com.quickskin.mod.common.data;

import com.quickskin.mod.platform.TextureReference;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PlayerAppearanceTest {
    @Test
    void changingOneAssetInvalidatesOnlyItsResolvedTexture() {
        PlayerAppearance appearance = new PlayerAppearance(UUID.randomUUID(), "skin-a", "cape-a", "classic");
        TextureReference skin = new Texture("quickskin", "skin/a");
        TextureReference cape = new Texture("quickskin", "cape/a");
        appearance.setSkinLocation(skin);
        appearance.setCapeLocation(cape);

        appearance.setSkinId("skin-b");
        assertNull(appearance.getSkinLocation());
        assertSame(cape, appearance.getCapeLocation());

        appearance.setSkinLocation(skin);
        appearance.setCapeId("cape-b");
        assertSame(skin, appearance.getSkinLocation());
        assertNull(appearance.getCapeLocation());
    }

    private record Texture(String namespace, String path) implements TextureReference {
    }
}
