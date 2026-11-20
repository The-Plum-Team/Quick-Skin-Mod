package com.quickskin.mod.networking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.quickskin.mod.networking.ImmersivePoseRelay.Outcome.REFUSED;
import static com.quickskin.mod.networking.ImmersivePoseRelay.Outcome.RELAY;
import static com.quickskin.mod.networking.ImmersivePoseRelay.Outcome.UNCHANGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server relays a pose change once, drops what is out of order or too frequent, and ends stale poses. */
class ImmersivePoseRelayTest {
    private static final UUID PLAYER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final ImmersivePose IDLE = new ImmersivePose(ImmersivePose.IDLE, 30.0f, 0, 0, 0);
    private static final ImmersivePose SIT = new ImmersivePose(ImmersivePose.SIT, 30.0f, 0, 0, 0);
    private static final long SECOND = 1_000_000_000L;

    @Test
    void aChangeIsRelayedAndARepeatIsNot() {
        ImmersivePoseRelay relay = new ImmersivePoseRelay();
        Object connection = new Object();

        assertEquals(RELAY, relay.accept(PLAYER, connection, 7, IDLE, 0L), "the first sequence may be any");
        assertEquals(UNCHANGED, relay.accept(PLAYER, connection, 8, IDLE, SECOND), "a keep-alive changes nothing");
        assertEquals(RELAY, relay.accept(PLAYER, connection, 9, SIT, 2 * SECOND));
        assertEquals(SIT, relay.current(PLAYER));
        assertEquals(RELAY, relay.accept(PLAYER, connection, 10, ImmersivePose.OFF, 3 * SECOND));
        assertEquals(UNCHANGED, relay.accept(PLAYER, connection, 11, ImmersivePose.OFF, 4 * SECOND),
                "ending a pose that is not played tells nobody");
        assertFalse(relay.anyActive());
    }

    @Test
    void anOldOrRepeatedSequenceIsRefused() {
        ImmersivePoseRelay relay = new ImmersivePoseRelay();
        Object connection = new Object();

        assertEquals(RELAY, relay.accept(PLAYER, connection, 5, IDLE, 0L));
        assertEquals(REFUSED, relay.accept(PLAYER, connection, 5, SIT, 0L));
        assertEquals(REFUSED, relay.accept(PLAYER, connection, 4, SIT, 0L));
        assertEquals(IDLE, relay.current(PLAYER));
        // The counter goes round: the next one after the largest is still later
        assertEquals(RELAY, relay.accept(OTHER, connection, Integer.MAX_VALUE, IDLE, 0L));
        assertEquals(RELAY, relay.accept(OTHER, connection, Integer.MIN_VALUE, SIT, 0L));
    }

    @Test
    void aNewSessionStartsItsOwnSequence() {
        ImmersivePoseRelay relay = new ImmersivePoseRelay();
        Object first = new Object();
        Object second = new Object();

        assertEquals(RELAY, relay.accept(PLAYER, first, 100, IDLE, 0L));
        assertEquals(UNCHANGED, relay.accept(PLAYER, second, 1, ImmersivePose.OFF, 0L),
                "a new session starts with no pose");
        assertFalse(relay.remove(PLAYER, first), "a replaced session cannot remove its successor");
        assertEquals(RELAY, relay.accept(PLAYER, second, 2, SIT, 0L));
        assertTrue(relay.remove(PLAYER, second));
        assertEquals(ImmersivePose.OFF, relay.current(PLAYER));
    }

    @Test
    void tooManyPosesAreRefusedButEndingOneNeverIs() {
        ImmersivePoseRelay relay = new ImmersivePoseRelay();
        Object connection = new Object();
        int sequence = 0;
        int admitted = 0;
        for (int i = 0; i < 50; i++) {
            ImmersivePose pose = i % 2 == 0 ? IDLE : SIT;
            if (relay.accept(PLAYER, connection, ++sequence, pose, 0L) != REFUSED) admitted++;
        }
        assertEquals((int) ImmersivePoseRelay.BURST, admitted);
        assertEquals(RELAY, relay.accept(PLAYER, connection, ++sequence, ImmersivePose.OFF, 0L),
                "the last word of a closing menu is not lost to the rate");
        assertEquals(REFUSED, relay.accept(PLAYER, connection, ++sequence, IDLE, 0L));
        // The budget comes back with time
        assertEquals(RELAY, relay.accept(PLAYER, connection, ++sequence, IDLE, SECOND));
    }

    @Test
    void malformedPosesAreRefused() {
        ImmersivePoseRelay relay = new ImmersivePoseRelay();
        Object connection = new Object();
        assertEquals(REFUSED, relay.accept(PLAYER, connection, 1,
                new ImmersivePose(ImmersivePose.IDLE, 0.0f, ImmersivePose.SETTLED, 0, 0), 0L),
                "a client cannot claim the server's flag");
        assertEquals(REFUSED, relay.accept(PLAYER, connection, 2, new ImmersivePose(9, 0.0f, 0, 0, 0), 0L));
        assertEquals(REFUSED, relay.accept(PLAYER, null, 3, IDLE, 0L));
        assertEquals(REFUSED, relay.accept(null, connection, 4, IDLE, 0L));
        assertFalse(relay.anyActive());
    }

    @Test
    void aPoseNotRepeatedInTimeEnds() {
        ImmersivePoseRelay relay = new ImmersivePoseRelay();
        Object connection = new Object();
        relay.accept(PLAYER, connection, 1, SIT, 0L);
        relay.accept(OTHER, connection, 1, IDLE, 10 * SECOND);

        assertEquals(List.of(), relay.expire(ImmersivePoseRelay.EXPIRY_NANOS - 1));
        assertEquals(List.of(PLAYER), relay.expire(ImmersivePoseRelay.EXPIRY_NANOS));
        assertEquals(ImmersivePose.OFF, relay.current(PLAYER));
        assertEquals(IDLE, relay.current(OTHER));
        assertEquals(1, relay.active().size());
    }

    @Test
    void theServerMayEndAPose() {
        ImmersivePoseRelay relay = new ImmersivePoseRelay();
        Object connection = new Object();
        relay.accept(PLAYER, connection, 1, SIT, 0L);
        assertTrue(relay.end(PLAYER));
        assertFalse(relay.end(PLAYER));
        assertEquals(REFUSED, relay.accept(PLAYER, connection, 1, SIT, 0L), "its sequence is kept");
        assertEquals(RELAY, relay.accept(PLAYER, connection, 2, SIT, 0L));
        assertEquals(1, relay.size());
    }
}
