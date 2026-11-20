package com.quickskin.mod.common.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteImmersivePosesTest {
    private static final UUID PLAYER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private final RemoteImmersivePoses poses = RemoteImmersivePoses.getInstance();

    @AfterEach
    void clear() {
        poses.clear();
    }

    @Test
    void aPoseIsPlayedOnlyInTheWorldItWasToldIn() {
        Object world = new Object();
        RemoteImmersivePoses.Pose sit = pose(3, world);
        poses.set(PLAYER, sit);

        assertFalse(poses.isEmpty());
        assertEquals(3, poses.pose(PLAYER, world).pose());
        assertSame(world, poses.pose(PLAYER, world).world());
        assertNull(poses.pose(PLAYER, new Object()), "after a change of world the old pose is not played");
        assertNull(poses.pose(null, world));
    }

    @Test
    void aRunOfPosesKeepsItsNumber() {
        Object world = new Object();
        poses.set(PLAYER, pose(1, world));
        long run = poses.pose(PLAYER, world).run();
        poses.set(PLAYER, pose(3, world));
        assertEquals(run, poses.pose(PLAYER, world).run(), "a change of pose carries the run on");

        poses.set(PLAYER, pose(0, world));
        poses.set(PLAYER, pose(1, world));
        assertNotEquals(run, poses.pose(PLAYER, world).run(), "a pose after an end starts a new run");

        Object next = new Object();
        long before = poses.pose(PLAYER, world).run();
        poses.set(PLAYER, pose(1, next));
        assertNotEquals(before, poses.pose(PLAYER, next).run(), "another world starts a new run");
    }

    @Test
    void aRunRemembersWhenItsFirstPoseWasTold() {
        Object world = new Object();
        poses.set(PLAYER, pose(1, world));
        long started = poses.pose(PLAYER, world).runStartNanos();
        poses.set(PLAYER, pose(3, world));
        RemoteImmersivePoses.Pose changed = poses.pose(PLAYER, world);
        assertEquals(started, changed.runStartNanos(), "a change keeps the run's start");
        assertTrue(changed.toldNanos() - started >= 0, "and is told after it");
    }

    @Test
    void leavingAWorldForgetsThePosesToldThere() {
        Object world = new Object();
        Object next = new Object();
        poses.set(PLAYER, pose(1, world));
        poses.keepOnly(world);
        assertFalse(poses.isEmpty(), "the poses of the world the client is in stay");
        poses.keepOnly(next);
        assertTrue(poses.isEmpty(), "so draws of players stop looking for poses");
        assertNull(poses.pose(PLAYER, world));
    }

    @Test
    void noPoseEndsThePlayersPose() {
        Object world = new Object();
        poses.set(PLAYER, pose(1, world));
        poses.set(PLAYER, pose(0, world));

        assertTrue(poses.isEmpty());
        assertNull(poses.pose(PLAYER, world));
    }

    @Test
    void thePosesStayBounded() {
        Object old = new Object();
        Object world = new Object();
        for (int i = 0; i < RemoteImmersivePoses.MAX_POSES; i++) {
            poses.set(new UUID(1L, i), pose(1, old));
        }
        poses.set(PLAYER, pose(2, world));

        assertEquals(1, poses.size(), "the poses of a world left go first");
        for (int i = 0; i < 2 * RemoteImmersivePoses.MAX_POSES; i++) {
            poses.set(new UUID(2L, i), pose(1, world));
        }
        assertEquals(RemoteImmersivePoses.MAX_POSES, poses.size());
    }

    private static RemoteImmersivePoses.Pose pose(int pose, Object world) {
        return new RemoteImmersivePoses.Pose(pose, 10.0f, 0, 0, 0, world);
    }
}
