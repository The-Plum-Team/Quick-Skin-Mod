package com.quickskin.mod.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameCallbacksTest {
    @Test
    void runsTheAction() {
        AtomicInteger runs = new AtomicInteger();

        GameCallbacks.guarded("count", runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    @Test
    void keepsAFailureOfQuickSkinFromTheGame() {
        assertDoesNotThrow(() -> GameCallbacks.guarded("fail", () -> {
            throw new IllegalStateException("a Quick Skin failure");
        }));
        assertDoesNotThrow(() -> GameCallbacks.guarded("link", () -> {
            throw new NoClassDefFoundError("an optional mod's class");
        }));
    }

    @Test
    void doesNotHideAFailureOfTheVirtualMachine() {
        assertThrows(OutOfMemoryError.class, () -> GameCallbacks.guarded("allocate", () -> {
            throw new OutOfMemoryError("not Quick Skin's to swallow");
        }));
    }
}
