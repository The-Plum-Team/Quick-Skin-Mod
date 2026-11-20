package com.quickskin.mod.client.immersive;

//? if >=1.21.6 {
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//? if <26.1 {
import net.minecraft.client.renderer.block.model.BakedQuad;
//?} else {
import net.minecraft.client.resources.model.geometry.BakedQuad;
//?}
//? if >=1.21.11 {
import net.minecraft.client.model.geom.builders.UVPair;
//?}
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix3x2f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
//?}

/** Meshes only the surfaces the chunk mesher could not know the camera would uncover. */
final class ApertureSections {
    private ApertureSections() {}
//? if >=1.21.6 {
    private static final RandomSource random = RandomSource.create();
    private static final int STRIDE = 24;
    private static final int[][] EDGES = {{0,1},{2,3},{4,5},{6,7},{0,2},{1,3},{4,6},{5,7},{0,4},{1,5},{2,6},{3,7}};
    static final Surface ground = new Surface(), section = new Surface();

    static final class Surface {
        private ByteBuffer vertices = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());

        int count() { return vertices.position() / STRIDE; }
        ByteBuffer bytes() { return vertices.duplicate().flip(); }

        void vertex(Vec3 p, Material m, Direction face, Vec3 local, int kind) {
            if (vertices.remaining() < STRIDE) {
                ByteBuffer grown = ByteBuffer.allocateDirect(vertices.capacity() * 2).order(ByteOrder.nativeOrder());
                grown.put(vertices.flip());
                vertices = grown;
            }
            float a = (float)(face.getAxis() == Direction.Axis.X ? local.z : local.x);
            float b = (float)(face.getAxis() == Direction.Axis.Y ? local.z : local.y);
            float u = m.uv.m00() * a + m.uv.m10() * b + m.uv.m20();
            float v = m.uv.m01() * a + m.uv.m11() * b + m.uv.m21();
            vertices.putFloat((float)p.x).putFloat((float)p.y).putFloat((float)p.z)
                    .putFloat(Mth.clamp(u, m.sprite.getU0(), m.sprite.getU1()))
                    .putFloat(Mth.clamp(v, m.sprite.getV0(), m.sprite.getV1()))
                    .put((byte)(m.rgb >>> 16)).put((byte)(m.rgb >>> 8)).put((byte)m.rgb).put((byte)kind);
        }

        void polygon(List<Vec3> points, Vec3 block, Material material, Direction face, int kind) {
            Vec3 first = points.getFirst();
            for (int i = 1; i + 1 < points.size(); i++) {
                Vec3 second = points.get(i);
                Vec3 third = points.get(i + 1);
                vertex(block.add(first), material, face, first, kind);
                vertex(block.add(second), material, face, second, kind);
                vertex(block.add(third), material, face, third, kind);
            }
        }
    }

    private record Material(TextureAtlasSprite sprite, Matrix3x2f uv, int rgb) {}

    /** Map face-space coordinates into the actual rotated, flipped or cropped sprite UVs from the model. */
    private static Matrix3x2f mapping(BakedQuad quad, Direction face, TextureAtlasSprite sprite) {
        if (quad != null) {
            int a = face.getAxis() == Direction.Axis.X ? 2 : 0;
            int b = face.getAxis() == Direction.Axis.Y ? 2 : 1;
            var plane = basis(quad, a, b);
            if (Math.abs(plane.determinant()) > 1e-7f) return basis(quad, 3, 4).mul(plane.invert());
        }
        boolean flipU = face == Direction.NORTH || face == Direction.EAST;
        boolean flipV = face != Direction.UP;
        float u0 = flipU ? sprite.getU1() : sprite.getU0();
        float v0 = flipV ? sprite.getV1() : sprite.getV0();
        return new Matrix3x2f((flipU ? sprite.getU0() : sprite.getU1()) - u0, 0, 0,
                (flipV ? sprite.getV0() : sprite.getV1()) - v0, u0, v0);
    }

    private static Matrix3x2f basis(BakedQuad quad, int a, int b) {
        float x = coordinate(quad, 0, a);
        float y = coordinate(quad, 0, b);
        return new Matrix3x2f(coordinate(quad, 1, a) - x, coordinate(quad, 1, b) - y,
                coordinate(quad, 3, a) - x, coordinate(quad, 3, b) - y, x, y);
    }

    private static float coordinate(BakedQuad quad, int vertex, int component) {
        //? if <1.21.11 {
        int[] data = quad.vertices();
        int offset = vertex * (data.length / 4);
        return Float.intBitsToFloat(data[offset + (component < 3 ? component : component + 1)]);
        //?} else {
        if (component < 3) return quad.position(vertex).get(component);
        return component == 3 ? UVPair.unpackU(quad.packedUV(vertex)) : UVPair.unpackV(quad.packedUV(vertex));
        //?}
    }

    private static Material material(BlockState state, BlockPos pos, Direction face, int light) {
        var game = Minecraft.getInstance();
        random.setSeed(state.getSeed(pos));
        //? if <26.1 {
        var model = game.getBlockRenderer().getBlockModel(state);
        var parts = model.collectParts(random);
        var sprite = model.particleIcon();
        //?} else {
        var model = game.getModelManager().getBlockStateModelSet().get(state);
        var parts = new ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
        model.collectParts(random, parts);
        var sprite = model.particleMaterial().sprite();
        //?}
        int tint = -1;
        BakedQuad chosen = null;
        for (var part : parts) {
            var quads = part.getQuads(face);
            if (quads.isEmpty()) continue;
            //? if <26.1 {
            chosen = quads.getFirst();
            var info = chosen;
            //?} else {
            chosen = quads.getFirst();
            var info = chosen.materialInfo();
            //?}
            sprite = info.sprite();
            tint = info.tintIndex();
            break;
        }
        int rgb = 0xffffff;
        if (tint >= 0) {
            //? if <26.1 {
            rgb = game.getBlockColors().getColor(state, game.level, pos, tint);
            //?} else {
            var source = game.getBlockColors().getTintSource(state, tint);
            if (source != null) rgb = source.colorInWorld(state, game.level, pos);
            //?}
        }
        float shade = switch (face.getAxis()) { case Y -> face == Direction.DOWN ? 0.55f : 1f; case X -> 0.65f; case Z -> 0.8f; };
        float brightness = shade * (0.18f + 0.82f * light / 15f);
        int color = Math.round((rgb >> 16 & 255) * brightness) << 16 | Math.round((rgb >> 8 & 255) * brightness) << 8
                | Math.round((rgb & 255) * brightness);
        return new Material(sprite, mapping(chosen, face, sprite), color);
    }

    static void build(ImmersiveOpening opening, Vec3 eye, Vec3 feet, Matrix4f view) {
        ground.vertices.clear();
        section.vertices.clear();
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var origin = BlockPos.containing(eye.subtract(opening.cameraInGrid()).add(0.5, 0.5, 0.5));
        Vec3 normal = new Vec3(-view.m02(), -view.m12(), -view.m22());
        Direction facing = Direction.getApproximateNearest(-normal.x, -normal.y, -normal.z);
        var pos = new BlockPos.MutableBlockPos();
        int floor = Mth.floor(feet.y - 0.0001);
        var playerPos = BlockPos.containing(feet.add(0, 1.0, 0));
        int light = Math.max(level.getBrightness(LightLayer.SKY, playerPos), level.getBrightness(LightLayer.BLOCK, playerPos));
        for (int y=0; y<16; y++) {
            for (int z=0; z<16; z++) {
                for (int x=0; x<16; x++) {
                    pos.set(origin.getX()+x, origin.getY()+y, origin.getZ()+z);
                    boolean support = pos.getY() <= floor && pos.getY() >= floor - 1 && eye.y > feet.y
                            && Math.abs(pos.getX() + 0.5 - feet.x) < 4 && Math.abs(pos.getZ() + 0.5 - feet.z) < 4;
                    boolean near = Math.abs(pos.getX()+0.5-eye.x) < 0.75 && Math.abs(pos.getY()+0.5-eye.y) < 0.75
                            && Math.abs(pos.getZ()+0.5-eye.z) < 0.75;
                    boolean marked = opening.cells()[x + 16*z + 256*y] != 0;
                    if (!near && !marked && !support) continue;
                    Vec3 block = new Vec3(pos.getX()-eye.x,pos.getY()-eye.y,pos.getZ()-eye.z);
                    double plane = opening.distance() + view.m32() + 0.0003 - normal.dot(block);
                    boolean cut = marked && Math.abs(plane - normal.dot(new Vec3(0.5,0.5,0.5)))
                            <= 0.5*(Math.abs(normal.x)+Math.abs(normal.y)+Math.abs(normal.z));
                    if (!near && !cut && !support) continue;
                    var state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    for (AABB box : state.canOcclude() && (near || cut)
                            ? state.getOcclusionShape().toAabbs() : List.<AABB>of()) {
                        if (near && box.move(block).inflate(0.16).contains(Vec3.ZERO)) {
                            for (Direction face : Direction.values())
                                ground.polygon(face(box, face),block,material(state,pos,face,light),face,255);
                        }
                        if (cut) {
                            List<Vec3> polygon = slice(box, normal, plane);
                            if (polygon.size() >= 3) section.polygon(polygon,block,material(state,pos,facing,light),facing,128);
                        }
                    }
                    if (support && (feet.y - pos.getY() < 0.9999 || level.getBlockState(pos.above()).canOcclude())) {
                        for (AABB box : state.getCollisionShape(level,pos).toAabbs()) {
                            double h = Math.min(box.maxY, feet.y - pos.getY());
                            if (h <= box.minY + 0.0001) continue;
                            AABB below = new AABB(box.minX,box.minY,box.minZ,box.maxX,h,box.maxZ);
                            ground.polygon(face(below,Direction.UP),block,material(state,pos,Direction.UP,light),Direction.UP,64);
                        }
                    }
                }
            }
        }
    }

    private static Vec3 corner(AABB b, int i) {
        return new Vec3((i&1)==0?b.minX:b.maxX,(i&2)==0?b.minY:b.maxY,(i&4)==0?b.minZ:b.maxZ);
    }

    private static List<Vec3> face(AABB b, Direction face) {
        int[] c = switch (face) { case DOWN -> new int[]{0,1,5,4}; case UP -> new int[]{2,3,7,6};
            case NORTH -> new int[]{0,1,3,2}; case SOUTH -> new int[]{4,5,7,6};
            case WEST -> new int[]{0,2,6,4}; case EAST -> new int[]{1,3,7,5}; };
        return List.of(corner(b,c[0]),corner(b,c[1]),corner(b,c[2]),corner(b,c[3]));
    }

    /** Sort the cube's plane intersections around their centre to emit one convex fan. */
    private static List<Vec3> slice(AABB box, Vec3 normal, double plane) {
        List<Vec3> polygon = new ArrayList<>(6);
        for (int[] edge : EDGES) {
            Vec3 a = corner(box,edge[0]), b = corner(box,edge[1]);
            double pa = a.dot(normal)-plane, pb = b.dot(normal)-plane;
            if ((pa >= 0) != (pb >= 0)) polygon.add(a.lerp(b, pa / (pa-pb)));
        }
        if (polygon.size() < 3) return polygon;
        Vec3 sum = Vec3.ZERO;
        for (Vec3 p : polygon) sum = sum.add(p);
        Vec3 center = sum.scale(1.0/polygon.size());
        Vec3 u = normal.cross(Math.abs(normal.y)<0.9 ? new Vec3(0,1,0) : new Vec3(1,0,0)).normalize();
        Vec3 v = normal.cross(u);
        polygon.sort(Comparator.comparingDouble(p -> Math.atan2(p.subtract(center).dot(v),p.subtract(center).dot(u))));
        return polygon;
    }
//?}
}
