package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A cape plays at the speed the player picked, else at its own default, else at its GIF timing. */
class ClientConfigCapeAnimationSpeedTest {

    @Test
    void theRickrollCapeStartsAtFortyPercent() {
        assertEquals(0.4f, ClientConfig.parse("{}").getCapeAnimationSpeed("known:rickroll"));
    }

    @Test
    void aPickedSpeedWinsOverTheDefault() {
        ClientConfig config = ClientConfig.parse("{}");
        config.setCapeAnimationSpeed("known:rickroll", 1.5f);
        assertEquals(1.5f, config.getCapeAnimationSpeed("known:rickroll"));
    }

    @Test
    void otherCapesPlayAtTheirOwnTiming() {
        assertEquals(1.0f, ClientConfig.parse("{}").getCapeAnimationSpeed("local_cape:abc"));
    }
}
