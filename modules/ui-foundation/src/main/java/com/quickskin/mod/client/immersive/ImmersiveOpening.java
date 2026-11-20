package com.quickskin.mod.client.immersive;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Projects the local player and the foreground blocks into an opening in the camera image. */
public record ImmersiveOpening(float x, float y, float radius, float cut, float distance,
                               int left, int bottom, int width, int height, Matrix4f fromClip,
                               Vec3 cameraInGrid, float floor, boolean crossesPlayer, byte[] cells) {
    public static final int SIDE = 16;
    public static final int OCCUPIED = 1;
    public static final int LATE = 2;

    @FunctionalInterface
    public interface BlockSampler {
        /** Returns false for invisible blocks. A true return supplies all three fields in out. */
        boolean sample(int x, int y, int z, Cell out);
    }

    public static final class Cell {
        private Vec3 offset;
        private boolean emptyCollision;
        private boolean drawnLate;

        public void set(Vec3 offset, boolean emptyCollision, boolean drawnLate) {
            this.offset = offset;
            this.emptyCollision = emptyCollision;
            this.drawnLate = drawnLate;
        }
    }

    public static ImmersiveOpening find(Matrix4f projection, Matrix4f view, int width, int height,
                                        Vec3 camera, Vec3 feet, float bodyHeight, float bodyWidth,
                                        boolean zeroToOne, BlockSampler blocks) {
        if (width <= 0 || height <= 0 || !view.isFinite() || !projection.isFinite()) return null;
        Vec3 center = feet.add(0, bodyHeight * 0.5, 0).subtract(camera);
        Vector4f eye = view.transform(new Vector4f((float)center.x, (float)center.y, (float)center.z, 1));
        if (!(eye.z < -0.8f)) return null;
        Vector4f clip = projection.transform(new Vector4f(eye));
        if (!clip.isFinite() || !(clip.w > 0)) return null;
        float x = (clip.x / clip.w + 1) * width * 0.5f;
        float y = (clip.y / clip.w + 1) * height * 0.5f;
        float radius = Math.max(1.24f, bodyHeight * 0.74f) * Math.abs(projection.m11() / clip.w) * height * 0.5f;
        if (!Float.isFinite(radius) || !(radius > 0)) return null;
        int left = Math.max(0, Mth.floor(x - radius) - 1);
        int bottom = Math.max(0, Mth.floor(y - radius) - 1);
        int right = Math.min(width, Mth.ceil(x + radius) + 1);
        int top = Math.min(height, Mth.ceil(y + radius) + 1);
        if (right <= left || top <= bottom) return null;

        int ox = Mth.floor(Math.min(camera.x, feet.x - 1.7) - 1);
        int oy = Mth.floor(Math.min(camera.y, feet.y) - 1);
        int oz = Mth.floor(Math.min(camera.z, feet.z - 1.7) - 1);
        int nx = Mth.floor(Math.max(camera.x, feet.x + 1.7) + 1) - ox + 1;
        int ny = Mth.floor(Math.max(camera.y, feet.y + Math.max(2.3, bodyHeight + 0.4)) + 1) - oy + 1;
        int nz = Mth.floor(Math.max(camera.z, feet.z + 1.7) + 1) - oz + 1;
        if (nx <= 0 || ny <= 0 || nz <= 0 || nx > SIDE || ny > SIDE || nz > SIDE) return null;
        Matrix4f toClip = new Matrix4f(projection).mul(view);
        // Test the opening's sides here and use eye-space distances for depth. That also works
        // for renderers with a reversed or zero-to-one depth range.
        Matrix4f window = new Matrix4f().scaling(width / (2 * radius), height / (2 * radius), 1)
                .m30((width * 0.5f - x) / radius).m31((height * 0.5f - y) / radius).mul(toClip);
        window.m02(0).m12(0).m22(0).m32(0);
        FrustumIntersection sides = new FrustumIntersection(window);
        float bodyDepth = -eye.z;
        float front = bodyDepth - Math.max(0.53f, bodyWidth * 0.8f);
        float halfDepth = (Math.abs(view.m02()) + Math.abs(view.m12()) + Math.abs(view.m22())) * 0.5f;
        byte[] cells = null;
        Cell cell = new Cell();
        float distance = 0;
        for (int iy = 0; iy < ny; iy++) {
            for (int iz = 0; iz < nz; iz++) {
                for (int ix = 0; ix < nx; ix++) {
                    float dx = (float)(ox + ix - camera.x), dy = (float)(oy + iy - camera.y), dz = (float)(oz + iz - camera.z);
                    // A displaced model can spill into an adjacent cell.
                    if (!sides.testAab(dx - 1, dy - 1, dz - 1, dx + 2, dy + 2, dz + 2)
                            || !blocks.sample(ox + ix, oy + iy, oz + iz, cell)) continue;
                    dx += (float)cell.offset.x;
                    dy += (float)cell.offset.y;
                    dz += (float)cell.offset.z;
                    if (oy + iy + cell.offset.y + 1 <= Math.min(feet.y, camera.y) + 0.001
                            || !sides.testAab(dx, dy, dz, dx + 1, dy + 1, dz + 1)) continue;
                    float depth = -(view.m02() * (dx + 0.5f) + view.m12() * (dy + 0.5f)
                            + view.m22() * (dz + 0.5f) + view.m32());
                    if (depth + halfDepth <= 0 || depth - halfDepth >= (cell.emptyCollision ? bodyDepth : front)) continue;
                    distance = Math.max(distance, Math.min(bodyDepth, depth + halfDepth + 0.02f));
                    if (cells == null) cells = new byte[SIDE * SIDE * SIDE];
                    mark(cells, ix, iy, iz, cell);
                }
            }
        }
        if (cells == null) return null;
        Vector4f cutPoint = projection.transform(new Vector4f(eye.x, eye.y, -distance, 1));
        float cut = cutPoint.z / cutPoint.w;
        if (!zeroToOne) cut = (cut + 1) * 0.5f;
        Matrix4f fromClip = toClip.invert();
        if (!(cut > 0 && cut < 1) || !fromClip.isFinite()) return null;
        return new ImmersiveOpening(x, y, radius, cut, distance, left, bottom, right - left, top - bottom,
                fromClip, camera.subtract(ox, oy, oz), (float)(feet.y - camera.y), distance > front, cells);
    }

    private static void mark(byte[] cells, int x, int y, int z, Cell cell) {
        if (cell.drawnLate) cells[x + SIDE * z + SIDE * SIDE * y] |= LATE;
        for (int iy = Math.max(0, y + Mth.floor(cell.offset.y)); iy <= Math.min(SIDE - 1, y + Mth.ceil(cell.offset.y)); iy++) {
            for (int iz = Math.max(0, z + Mth.floor(cell.offset.z)); iz <= Math.min(SIDE - 1, z + Mth.ceil(cell.offset.z)); iz++) {
                for (int ix = Math.max(0, x + Mth.floor(cell.offset.x)); ix <= Math.min(SIDE - 1, x + Mth.ceil(cell.offset.x)); ix++) {
                    cells[ix + SIDE * iz + SIDE * SIDE * iy] |= OCCUPIED;
                }
            }
        }
    }
}
