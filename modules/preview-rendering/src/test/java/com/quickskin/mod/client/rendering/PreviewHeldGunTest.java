package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewHeldGunTest {

    /** Stands in for the gun mod's marker interface, which is never on this classpath. */
    private interface Gun {
    }

    private static final class Rifle implements Gun {
    }

    private static final class Sword {
    }

    private static final String GUN_TYPE = Gun.class.getName();

    private static PreviewHeldGun installed(AtomicInteger lookups) {
        return new PreviewHeldGun(() -> true, GUN_TYPE, name -> {
            lookups.incrementAndGet();
            return Class.forName(name, false, PreviewHeldGunTest.class.getClassLoader());
        });
    }

    @Test
    void anItemImplementingTheModsGunTypeIsAGunAndNothingElseIs() {
        PreviewHeldGun guns = installed(new AtomicInteger());

        assertTrue(guns.matches(new Rifle()));
        // The mod's other items share its namespace but not its gun type, and a vanilla item is
        // what every player without the mod holds: both must leave the preview as it was.
        assertFalse(guns.matches(new Sword()));
        assertFalse(guns.matches("minecraft:air"));
        assertFalse(guns.matches(null));
    }

    @Test
    void theTypeIsLookedUpOnce() {
        // The answer is asked once per preview draw; the class lookup must not be.
        AtomicInteger lookups = new AtomicInteger();
        PreviewHeldGun guns = installed(lookups);

        for (int draw = 0; draw < 5; draw++) {
            assertTrue(guns.matches(new Rifle()));
            assertFalse(guns.matches(new Sword()));
        }

        assertEquals(1, lookups.get());
    }

    @Test
    void withoutTheModNothingIsAGunAndNoClassIsLookedUp() {
        AtomicInteger loaderQueries = new AtomicInteger();
        AtomicInteger lookups = new AtomicInteger();
        PreviewHeldGun guns = new PreviewHeldGun(() -> {
            loaderQueries.incrementAndGet();
            return false;
        }, GUN_TYPE, name -> {
            lookups.incrementAndGet();
            return Gun.class;
        });

        assertFalse(guns.matches(new Rifle()));
        assertFalse(guns.matches(new Rifle()));

        assertEquals(0, lookups.get(), "a third-party class was looked up although its mod is absent");
        assertEquals(1, loaderQueries.get());
    }

    @Test
    void aModThatNoLongerHasTheTypeAnswersNoGunInsteadOfThrowing() {
        AtomicInteger lookups = new AtomicInteger();
        PreviewHeldGun renamed = new PreviewHeldGun(() -> true, "com.example.gone.IGun", name -> {
            lookups.incrementAndGet();
            throw new ClassNotFoundException(name);
        });

        assertFalse(renamed.matches(new Rifle()));
        assertFalse(renamed.matches(new Rifle()));
        assertEquals(1, lookups.get(), "a failed lookup must be remembered, not retried every draw");
    }

    @Test
    void aTypeThatCannotBeLinkedAnswersNoGunInsteadOfThrowing() {
        PreviewHeldGun broken = new PreviewHeldGun(() -> true, GUN_TYPE, name -> {
            throw new NoClassDefFoundError(name);
        });
        PreviewHeldGun refused = new PreviewHeldGun(() -> true, GUN_TYPE, name -> {
            throw new SecurityException(name);
        });
        PreviewHeldGun loaderFailure = new PreviewHeldGun(() -> {
            throw new IllegalStateException("mod list not ready");
        }, GUN_TYPE, name -> Gun.class);

        assertFalse(broken.matches(new Rifle()));
        assertFalse(refused.matches(new Rifle()));
        assertFalse(loaderFailure.matches(new Rifle()));
    }
}
