package com.quickskin.mod.server.storage;

import com.quickskin.mod.networking.TextureTransferLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextureChunkAssemblerTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";

    private TextureChunkAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = TextureChunkAssembler.getInstance();
        assembler.clear();
    }

    @AfterEach
    void tearDown() {
        assembler.clear();
    }

    @Test
    void assemblesOutOfOrderChunksInWireOrderAndCopiesInput() {
        UUID player = UUID.randomUUID();
        Object session = new Object();
        byte[] second = {3, 4};

        assertNull(assembler.addChunk(player, session, "skin", HASH, 1, 2, second));
        second[0] = 99;

        assertArrayEquals(
                new byte[] {1, 2, 3, 4},
                assembler.addChunk(player, session, "skin", HASH, 0, 2, new byte[] {1, 2}));
    }

    @Test
    void duplicateChunkDoesNotReplaceTheAcceptedBytes() {
        UUID player = UUID.randomUUID();
        Object session = new Object();

        assertNull(assembler.addChunk(player, session, "cape", HASH, 0, 2, new byte[] {1}));
        assertNull(assembler.addChunk(player, session, "cape", HASH, 0, 2, new byte[] {9}));
        assertArrayEquals(
                new byte[] {1, 2},
                assembler.addChunk(player, session, "cape", HASH, 1, 2, new byte[] {2}));
    }

    @Test
    void totalMismatchDiscardsTheOldAssembly() {
        UUID player = UUID.randomUUID();
        Object session = new Object();

        assertNull(assembler.addChunk(player, session, "skin", HASH, 0, 2, new byte[] {1}));
        assertNull(assembler.addChunk(player, session, "skin", HASH, 1, 3, new byte[] {2}));
        assertNull(assembler.addChunk(player, session, "skin", HASH, 0, 2, new byte[] {7}));
        assertArrayEquals(
                new byte[] {7, 8},
                assembler.addChunk(player, session, "skin", HASH, 1, 2, new byte[] {8}));
    }

    @Test
    void reconnectCannotInheritChunksFromThePreviousSession() {
        UUID player = UUID.randomUUID();
        Object oldSession = new Object();
        Object newSession = new Object();

        assertNull(assembler.addChunk(player, oldSession, "skin", HASH, 0, 2, new byte[] {1}));
        assertNull(assembler.addChunk(player, newSession, "skin", HASH, 1, 2, new byte[] {4}));
        assertArrayEquals(
                new byte[] {3, 4},
                assembler.addChunk(player, newSession, "skin", HASH, 0, 2, new byte[] {3}));
    }

    @Test
    void lateOldDisconnectDoesNotDiscardNewSessionAssembly() {
        UUID player = UUID.randomUUID();
        Object oldSession = new Object();
        Object newSession = new Object();

        assertNull(assembler.addChunk(player, oldSession, "skin", HASH, 0, 2, new byte[] {1}));
        assertNull(assembler.addChunk(player, newSession, "skin", HASH, 0, 2, new byte[] {7}));
        assembler.discardSession(player, oldSession);

        assertArrayEquals(
                new byte[] {7, 8},
                assembler.addChunk(player, newSession, "skin", HASH, 1, 2, new byte[] {8}));
    }

    @Test
    void playersWithTheSameContentIdRemainIsolated() {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        Object firstSession = new Object();
        Object secondSession = new Object();

        assertNull(assembler.addChunk(
                firstPlayer, firstSession, "cape", HASH, 0, 2, new byte[] {1}));
        assertNull(assembler.addChunk(
                secondPlayer, secondSession, "cape", HASH, 0, 2, new byte[] {7}));
        assertArrayEquals(
                new byte[] {1, 2},
                assembler.addChunk(
                        firstPlayer, firstSession, "cape", HASH, 1, 2, new byte[] {2}));
        assertArrayEquals(
                new byte[] {7, 8},
                assembler.addChunk(
                        secondPlayer, secondSession, "cape", HASH, 1, 2, new byte[] {8}));
    }

    @Test
    void rejectsInvalidCountsIndexesTypesHashesAndChunkSizes() {
        UUID player = UUID.randomUUID();
        Object session = new Object();

        assertNull(assembler.addChunk(player, session, "skin", HASH, -1, 1, new byte[] {1}));
        assertNull(assembler.addChunk(player, session, "skin", HASH, 1, 1, new byte[] {1}));
        assertNull(assembler.addChunk(
                player,
                session,
                "skin",
                HASH,
                0,
                TextureTransferLimits.MAX_CHUNKS + 1,
                new byte[] {1}));
        assertNull(assembler.addChunk(player, session, "other", HASH, 0, 1, new byte[] {1}));
        assertNull(assembler.addChunk(player, session, "skin", "not-a-hash", 0, 1, new byte[] {1}));
        assertNull(assembler.addChunk(player, session, "skin", HASH, 0, 1, new byte[0]));
        assertNull(assembler.addChunk(
                player,
                session,
                "skin",
                HASH,
                0,
                1,
                new byte[TextureTransferLimits.MAX_WIRE_CHUNK_BYTES + 1]));
        assertArrayEquals(
                new byte[] {5},
                assembler.addChunk(player, session, "skin", HASH, 0, 1, new byte[] {5}));
    }
}
