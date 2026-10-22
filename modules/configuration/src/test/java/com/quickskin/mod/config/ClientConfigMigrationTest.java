package com.quickskin.mod.config;

import com.quickskin.mod.common.data.BackgroundStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigMigrationTest {

    @Test
    void newSettingsUseTheBlurredVanillaBackground() {
        assertEquals(BackgroundStyle.VANILLA_BLUR, ClientConfig.parse("{}").getMenuBackgroundStyle());
    }

    @Test
    void settingsFromBeforeVersionOneMoveFromStarsToTheBlurredBackgroundOnce() {
        ClientConfig config = ClientConfig.parse("{\"menuBackgroundStyle\": \"opaque_stars\"}");

        assertTrue(config.migrate());
        assertEquals(BackgroundStyle.VANILLA_BLUR, config.getMenuBackgroundStyle());
        assertEquals(ClientConfig.CURRENT_CONFIG_VERSION, config.configVersion);

        // A player who picks the stars again after the migration keeps them.
        config.menuBackgroundStyle = BackgroundStyle.OPAQUE_STARS.getId();
        assertFalse(config.migrate());
        assertEquals(BackgroundStyle.OPAQUE_STARS, config.getMenuBackgroundStyle());
    }

    @Test
    void currentSettingsKeepTheirStarredBackground() {
        ClientConfig config = ClientConfig.parse("{\"configVersion\": "
                + ClientConfig.CURRENT_CONFIG_VERSION + ", \"menuBackgroundStyle\": \"opaque_stars\"}");

        assertFalse(config.migrate());
        assertEquals(BackgroundStyle.OPAQUE_STARS, config.getMenuBackgroundStyle());
    }

    @Test
    void anUnknownOrMissingStyleFallsBackToTheBlurredBackground() {
        assertEquals(BackgroundStyle.VANILLA_BLUR,
                ClientConfig.parse("{\"menuBackgroundStyle\": \"retired\"}").getMenuBackgroundStyle());
        assertEquals(BackgroundStyle.VANILLA_BLUR,
                ClientConfig.parse("{\"menuBackgroundStyle\": null}").getMenuBackgroundStyle());
    }
}
