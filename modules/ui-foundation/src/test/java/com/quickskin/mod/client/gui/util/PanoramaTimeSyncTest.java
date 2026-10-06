package com.quickskin.mod.client.gui.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanoramaTimeSyncTest {

    /** System.nanoTime() / 1e6 on a Windows machine that had been up for 10.3 days (issue #2050). */
    private static final long TEN_DAYS_OF_UPTIME_MILLIS = 890_662_838L;

    @Test
    void everyFrameMovesThePanoramaAfterDaysOfUptime() {
        // A clock counted from boot, as Very Many Players installs, must still turn the panorama on
        // every 60 FPS frame. Converting the raw reading to a float first rounded it to 64 ms here.
        for (long origin : new long[] {TEN_DAYS_OF_UPTIME_MILLIS, 1L << 40, Long.MAX_VALUE - 10_000L}) {
            Set<Float> angles = new HashSet<>();
            long previousMillis = origin;
            float previous = PanoramaTimeSync.spinAt(origin);
            for (int frame = 1; frame <= 64; frame++) {
                long millis = previousMillis + (frame % 3 == 0 ? 17L : 16L);
                float angle = PanoramaTimeSync.spinAt(millis);
                assertEquals((millis - previousMillis) / 1000.0, forwardDegrees(previous, angle), 1.0e-4,
                    "frame " + frame + " from " + origin);
                angles.add(angle);
                previousMillis = millis;
                previous = angle;
            }
            assertEquals(64, angles.size(), "distinct panorama angles from " + origin);
        }
    }

    @Test
    void turnsOneDegreePerSecond() {
        assertEquals(1.0, forwardDegrees(
            PanoramaTimeSync.spinAt(TEN_DAYS_OF_UPTIME_MILLIS),
            PanoramaTimeSync.spinAt(TEN_DAYS_OF_UPTIME_MILLIS + 1_000L)), 1.0e-4);
        assertEquals(0.0F, PanoramaTimeSync.spinAt(0L));
        assertEquals(90.0F, PanoramaTimeSync.spinAt(90_000L));
    }

    @Test
    void staysWithinOneTurnAndContinuousWhereItWraps() {
        for (long turn : new long[] {0L, 1L, 2_474L, -3L}) {
            long wrap = turn * PanoramaTimeSync.MILLIS_PER_TURN;
            float before = PanoramaTimeSync.spinAt(wrap - 1L);
            float after = PanoramaTimeSync.spinAt(wrap);
            assertEquals(0.0F, after, "wrap at turn " + turn);
            assertEquals(0.001, forwardDegrees(before, after), 1.0e-4, "wrap at turn " + turn);
        }
        for (long millis : new long[] {Long.MIN_VALUE, -1L, 359_999L, Long.MAX_VALUE}) {
            float angle = PanoramaTimeSync.spinAt(millis);
            assertTrue(angle >= 0.0F && angle < 360.0F, millis + " -> " + angle);
        }
    }

    private static double forwardDegrees(float from, float to) {
        double step = (double) to - from;
        return step < 0.0 ? step + 360.0 : step;
    }
}
