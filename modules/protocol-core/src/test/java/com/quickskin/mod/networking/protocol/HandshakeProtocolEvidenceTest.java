package com.quickskin.mod.networking.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakeProtocolEvidenceTest {

    @Test
    void quickSkinThreeOrNewerWithItsChannelAcceptsTheHello() {
        assertTrue(HandshakeProtocolEvidence.declaresProtocolHello("3.0.0", true));
        assertTrue(HandshakeProtocolEvidence.declaresProtocolHello("3.1.2-beta", true));
        assertTrue(HandshakeProtocolEvidence.declaresProtocolHello("4+build.7", true));
        assertTrue(HandshakeProtocolEvidence.declaresProtocolHello("12.0", true));
    }

    @Test
    void theModVersionAloneIsNotEnough() {
        assertFalse(HandshakeProtocolEvidence.declaresProtocolHello("3.0.0", false));
    }

    @Test
    void legacyAndMissingDeclarationsAreLeftToChannelDiscovery() {
        assertFalse(HandshakeProtocolEvidence.declaresProtocolHello("2.6.2.6", true));
        assertFalse(HandshakeProtocolEvidence.declaresProtocolHello(null, true));
        assertFalse(HandshakeProtocolEvidence.declaresProtocolHello("", true));
    }

    @Test
    void malformedVersionsDeclareNothing() {
        assertEquals(-1, HandshakeProtocolEvidence.majorVersion("v3.0.0"));
        assertEquals(-1, HandshakeProtocolEvidence.majorVersion("3a"));
        assertEquals(-1, HandshakeProtocolEvidence.majorVersion("${version}"));
        assertEquals(-1, HandshakeProtocolEvidence.majorVersion("9999999999.0"));
        assertEquals(-1, HandshakeProtocolEvidence.majorVersion("3".repeat(300)));
        assertEquals(3, HandshakeProtocolEvidence.majorVersion("3"));
    }
}
