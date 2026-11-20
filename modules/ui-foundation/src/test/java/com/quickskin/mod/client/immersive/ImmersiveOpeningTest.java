package com.quickskin.mod.client.immersive;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImmersiveOpeningTest {
    private static final Vec3 CAMERA = new Vec3(0.5, 1.0, 0.5);
    private static final Vec3 FEET = new Vec3(0.5, 0, -2.5);

    private static ImmersiveOpening opening(boolean zeroToOne, ImmersiveOpening.BlockSampler blocks) {
        var projection = new Matrix4f().perspective((float)Math.toRadians(60), 16f / 9, 0.05f, 128f, zeroToOne);
        return ImmersiveOpening.find(projection, new Matrix4f(), 1280, 720, CAMERA, FEET, 1.8f, 0.6f, zeroToOne, blocks);
    }

    @Test
    void projectsTheSameForegroundSurfaceInBothDepthConventions() {
        ImmersiveOpening.BlockSampler wall = (x, y, z, out) -> {
            out.set(Vec3.ZERO, false, false);
            return x == 0 && y == 0 && z == -1;
        };
        var gl = opening(false, wall);
        var gpu = opening(true, wall);
        assertNotNull(gl);
        assertNotNull(gpu);
        assertEquals(640, gl.x(), 0.001f);
        assertEquals(1.52, gl.distance(), 0.001f);
        assertEquals(gl.cut(), gpu.cut(), 0.00001f);
        assertFalse(gl.crossesPlayer());
        assertArrayEquals(gl.cells(), gpu.cells());
    }

    @Test
    void ignoresTheSupportFloorAndWallsBehindThePlayer() {
        assertNull(opening(false, (x, y, z, out) -> {
            out.set(Vec3.ZERO, false, false);
            return y < 0 || z < -3;
        }));
    }

    @Test
    void aDisplacedLateBlockMarksOccupancyInBothCellsButOnlyOneLateCell() {
        var o = opening(false, (x, y, z, out) -> {
            out.set(new Vec3(0.2, 0, 0), true, true);
            return x == 0 && y == 0 && z == -1;
        });
        assertNotNull(o);
        int occupied = 0, late = 0;
        for (byte cell : o.cells()) {
            if ((cell & ImmersiveOpening.OCCUPIED) != 0) occupied++;
            if ((cell & ImmersiveOpening.LATE) != 0) late++;
        }
        assertEquals(2, occupied);
        assertEquals(1, late);
    }

    @Test
    void neverScansAnUnboundedVolume() {
        var farCamera = CAMERA.add(0, 0, 128);
        var projection = new Matrix4f().perspective((float)Math.toRadians(60), 16f / 9, 0.05f, 512);
        assertNull(ImmersiveOpening.find(projection, new Matrix4f(), 1280, 720, farCamera, FEET, 1.8f, 0.6f,
                false, (x, y, z, out) -> { throw new AssertionError("out-of-range terrain scan"); }));
    }
}
