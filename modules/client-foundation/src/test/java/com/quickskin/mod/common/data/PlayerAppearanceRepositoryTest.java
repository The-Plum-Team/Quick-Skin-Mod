package com.quickskin.mod.common.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PlayerAppearanceRepositoryTest {
    private final PlayerAppearanceRepository repository = PlayerAppearanceRepository.getInstance();

    @AfterEach
    void clearRepository() {
        repository.clear();
    }

    @Test
    void nullPlayerIdResolvesToNoAppearanceInsteadOfThrowing() {
        // A name-only GameProfile (a player_head whose SkullOwner is a bare name) reaches the
        // client lookups with a null UUID; the backing ConcurrentHashMap rejects that key.
        repository.setAppearance(
                new PlayerAppearance(UUID.randomUUID(), "local_skin:abc", "", "classic"));

        assertNull(assertDoesNotThrow(() -> repository.getAppearance(null)));
        assertDoesNotThrow(() -> repository.removeAppearance(null));
    }

    @Test
    void knownPlayerIdStillResolvesItsAppearance() {
        UUID playerId = UUID.randomUUID();
        PlayerAppearance appearance =
                new PlayerAppearance(playerId, "local_skin:abc", "", "classic");
        repository.setAppearance(appearance);

        assertSame(appearance, repository.getAppearance(playerId));
        assertNull(repository.getAppearance(UUID.randomUUID()));
    }
}
