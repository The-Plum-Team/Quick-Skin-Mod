package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeLayerTakeoverTest {

    @AfterEach
    void close() {
        CapeLayerTakeover.end();
    }

    @Test
    void coversOnlyThePlayerOfTheOpenCall() {
        Object wearer = new Object();
        Object bystander = new Object();

        assertFalse(CapeLayerTakeover.covers(wearer));
        CapeLayerTakeover.begin(wearer);

        assertTrue(CapeLayerTakeover.covers(wearer));
        assertFalse(CapeLayerTakeover.covers(bystander));
        assertFalse(CapeLayerTakeover.covers(null));
    }

    @Test
    void aPlayerIsHeldByIdentityNotByEquality() {
        String wearer = new String("Alice");
        CapeLayerTakeover.begin(wearer);

        assertFalse(CapeLayerTakeover.covers(new String("Alice")));
    }

    @Test
    void endsWithTheCall() {
        Object wearer = new Object();
        CapeLayerTakeover.begin(wearer);
        CapeLayerTakeover.end();

        assertFalse(CapeLayerTakeover.covers(wearer));
    }

    @Test
    void theNextCallReplacesOneLeftOpen() {
        Object first = new Object();
        Object second = new Object();
        CapeLayerTakeover.begin(first);
        CapeLayerTakeover.begin(second);

        assertFalse(CapeLayerTakeover.covers(first));
        assertTrue(CapeLayerTakeover.covers(second));
    }

    @Test
    void anotherThreadReadsThePlayerAsItIs() throws InterruptedException {
        Object wearer = new Object();
        CapeLayerTakeover.begin(wearer);
        AtomicBoolean coveredElsewhere = new AtomicBoolean(true);

        Thread other = new Thread(() -> coveredElsewhere.set(CapeLayerTakeover.covers(wearer)));
        other.start();
        other.join();

        assertFalse(coveredElsewhere.get());
        assertTrue(CapeLayerTakeover.covers(wearer));
    }

    @Test
    void openingForNobodyCoversNobody() {
        CapeLayerTakeover.begin(null);

        assertFalse(CapeLayerTakeover.covers(null));
    }
}
