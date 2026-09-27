package com.quickskin.mod.networking.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientChannelDiscoveryTest {

    @Test
    void helloChannelIsDecisiveOnTheFirstObservation() {
        ClientChannelDiscovery discovery = new ClientChannelDiscovery();

        assertEquals(ClientChannelDiscovery.Decision.HELLO, discovery.observe(true, true));
        assertTrue(discovery.decided());
    }

    @Test
    void helloAdvertisedLongAfterJoinIsStillAccepted() {
        ClientChannelDiscovery discovery = new ClientChannelDiscovery();

        // One observation per client tick while the channel list waits behind other work
        // (40 seconds behind JEI in a large pack); there is no deadline to miss.
        for (int tick = 0; tick < 20 * 600; tick++) {
            assertEquals(ClientChannelDiscovery.Decision.WAIT, discovery.observe(false, false));
        }
        assertFalse(discovery.decided());
        assertEquals(ClientChannelDiscovery.Decision.HELLO, discovery.observe(true, true));
    }

    @Test
    void aPartiallyFilledListCannotDowngradeAV2Server() {
        ClientChannelDiscovery discovery = new ClientChannelDiscovery();

        // The legacy receivers were copied before the hello receiver on the network thread.
        assertEquals(ClientChannelDiscovery.Decision.WAIT, discovery.observe(false, true));
        assertEquals(ClientChannelDiscovery.Decision.HELLO, discovery.observe(true, true));
    }

    @Test
    void legacyNeedsTwoConsecutiveAgreeingObservations() {
        ClientChannelDiscovery discovery = new ClientChannelDiscovery();

        assertEquals(ClientChannelDiscovery.Decision.WAIT, discovery.observe(false, true));
        assertEquals(ClientChannelDiscovery.Decision.WAIT, discovery.observe(false, false));
        assertEquals(ClientChannelDiscovery.Decision.WAIT, discovery.observe(false, true));
        assertEquals(ClientChannelDiscovery.Decision.LEGACY, discovery.observe(false, true));
    }

    @Test
    void aDecisionIsReportedOnlyOnce() {
        ClientChannelDiscovery discovery = new ClientChannelDiscovery();

        assertEquals(ClientChannelDiscovery.Decision.HELLO, discovery.observe(true, false));
        assertEquals(ClientChannelDiscovery.Decision.WAIT, discovery.observe(true, false));
    }
}
