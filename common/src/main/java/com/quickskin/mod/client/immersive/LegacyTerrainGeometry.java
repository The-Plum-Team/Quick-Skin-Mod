package com.quickskin.mod.client.immersive;

//? if <1.21.6 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

//? if <1.21 {
import com.mojang.blaze3d.vertex.BufferBuilder;
//?} else {
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
//?}
//?}

/** Reconstructs the real material at the camera near plane, the support floor and the cut plane. */
final class LegacyTerrainGeometry {
    //? if <1.21.6 {
    private static final RandomSource random = RandomSource.create();
    private static final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(
            //? if <1.21 {
            new BufferBuilder(8192)
            //?} else {
            new ByteBufferBuilder(8192)
            //?}
    );

    static Vec3 offset(BlockState state, ClientLevel level, BlockPos pos) {
        //? if <1.21.2 {
        return state.getOffset(level, pos);
        //?} else {
        return state.getOffset(pos);
        //?}
    }

    private static VoxelShape shape(BlockState state, ClientLevel level, BlockPos pos) {
        //? if <1.21.2 {
        return state.getOcclusionShape(level, pos);
        //?} else {
        return state.getOcclusionShape();
        //?}
    }

    private static boolean visible(BlockState state, ClientLevel level, BlockPos pos, Direction face) {
        //? if <1.21.2 {
        return Block.shouldRenderFace(state, level, pos, face, pos.relative(face));
        //?} else {
        return Block.shouldRenderFace(state, level.getBlockState(pos.relative(face)), face);
        //?}
    }

    private static List<BakedQuad> faces(BlockState state, BlockPos pos, Direction side) {
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        random.setSeed(state.getSeed(pos));
        //? if <1.21.5 {
        return model.getQuads(state, side, random);
        //?} else {
        List<BakedQuad> result = new ArrayList<>();
        for (var part : model.collectParts(random)) result.addAll(part.getQuads(side));
        return result;
        //?}
    }

    private static int[] vertices(BakedQuad quad) {
        //? if <1.21.5 {
        return quad.getVertices();
        //?} else {
        return quad.vertices();
        //?}
    }

    private static Direction face(BakedQuad quad) {
        //? if <1.21.5 {
        return quad.getDirection();
        //?} else {
        return quad.direction();
        //?}
    }

    private static boolean solid(RenderType layer) {
        return layer == RenderType.solid() || layer == RenderType.cutout() || layer == RenderType.cutoutMipped();
    }

    private static int playerLight(ClientLevel level, float time) {
        var player = Minecraft.getInstance().player;
        return player == null ? 0 : LevelRenderer.getLightColor(level, BlockPos.containing(player.getLightProbePosition(time)));
    }

    static void interior(Vec3 camera, PoseStack poses, Matrix4f projection, Matrix4f view, float time) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var inv = new Matrix4f(projection).mul(view).invert();
        if (!inv.isFinite()) return;
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY), max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (int i = 0; i < 4; i++) {
            var corner = inv.transformProject(new Vector3f((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, -1));
            if (!corner.isFinite()) return;
            min.min(corner); max.max(corner);
        }
        AABB near = new AABB(min.x, min.y, min.z, max.x, max.y, max.z).move(camera).inflate(0.0001);
        BlockPos first = BlockPos.containing(near.minX, near.minY, near.minZ), last = BlockPos.containing(near.maxX, near.maxY, near.maxZ);
        if (!bounded(first, last, 64)) return;
        int light = playerLight(level, time);
        try {
            for (BlockPos pos : BlockPos.betweenClosed(first, last)) {
                BlockState state = level.getBlockState(pos);
                RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
                if (state.getRenderShape() != RenderShape.MODEL || !state.canOcclude() || !solid(layer)) continue;
                if (!Shapes.joinIsNotEmpty(shape(state, level, pos),
                        Shapes.create(near.move(-pos.getX(), -pos.getY(), -pos.getZ())), BooleanOp.AND)) continue;
                Vec3 at = Vec3.atLowerCornerOf(pos).subtract(camera).add(offset(state, level, pos));
                for (Direction dir : Direction.values()) {
                    for (var quad : faces(state, pos, dir)) drawFace(quad, state, level, pos, poses, layer, at, light, true, 1, false);
                }
                for (var quad : faces(state, pos, null)) drawFace(quad, state, level, pos, poses, layer, at, light, true, 1, false);
            }
        } finally {
            buffers.endBatch();
        }
    }

    static void ground(Vec3 camera, PoseStack poses, ImmersiveOpening opening, float time) {
        var level = Minecraft.getInstance().level;
        if (level == null || opening.floor() >= 0) return;
        double feet = camera.y + opening.floor();
        int row = Mth.floor(feet - 0.0001);
        double reach = feet - row < 0.9999 ? Mth.clamp((camera.y - feet) / Math.max(0.001, camera.y - row - 1), 1, 3) : 1;
        var corners = corners(opening);
        Vec3 min = Vec3.ZERO, max = Vec3.ZERO;
        for (Vec3 corner : corners) {
            min = new Vec3(Math.min(min.x,corner.x), Math.min(min.y,corner.y), Math.min(min.z,corner.z));
            max = new Vec3(Math.max(max.x,corner.x), Math.max(max.y,corner.y), Math.max(max.z,corner.z));
        }
        var first = BlockPos.containing(camera.x + min.x * reach, Math.min(row, camera.y + min.y - 0.001), camera.z + min.z * reach);
        var last = BlockPos.containing(camera.x + max.x * reach, row, camera.z + max.z * reach);
        if (!bounded(first, last, 4096)) return;
        try {
            for (var pos : BlockPos.betweenClosed(first, last)) {
                var state = level.getBlockState(pos);
                var layer = ItemBlockRenderTypes.getChunkRenderType(state);
                if (state.getRenderShape() != RenderShape.MODEL || !solid(layer) || state.getCollisionShape(level, pos).isEmpty()) continue;
                Vec3 offset = offset(state, level, pos);
                var at = Vec3.atLowerCornerOf(pos).subtract(camera).add(offset);
                double height = feet - pos.getY() - offset.y;
                boolean slice = height < 0.9999 && pos.getY() == row && state.canOcclude() && state.isCollisionShapeFullBlock(level, pos);
                int light = LevelRenderer.getLightColor(level, pos.above());
                for (Direction dir : Direction.values()) {
                    boolean floor = dir == Direction.UP && (slice || Math.abs(height - 1) < 0.0001);
                    if (!floor && !visible(state, level, pos, dir)) continue;
                    for (var quad : faces(state, pos, dir)) drawFace(quad, state, level, pos, poses, layer, at, light, false, height, slice);
                }
                for (var quad : faces(state, pos, null)) drawFace(quad, state, level, pos, poses, layer, at, light, false, height, slice);
            }
        } finally {
            buffers.endBatch();
        }
    }

    private static void drawFace(BakedQuad quad, BlockState state, ClientLevel level, BlockPos pos, PoseStack poses,
                                 RenderType layer, Vec3 at, int light, boolean inward, double height, boolean slice) {
        int[] v = vertices(quad);
        int stride = v.length / 4;
        Direction face = face(quad);
        boolean cap = slice && face == Direction.UP;
        boolean below = true;
        for (int n = 0; n < 4; n++) {
            float y = Float.intBitsToFloat(v[n * stride + 1]);
            cap &= Math.abs(y - 1) < 0.0001;
            below &= y <= height + 0.0001;
        }
        if (!below && !cap) return;
        int rgb = color(quad, state, level, pos, face);
        VertexConsumer out = buffers.getBuffer(layer);
        for (int n = 0; n < 4; n++) {
            int k = (inward ? 3 - n : n) * stride;
            emit(out, poses.last(), (float)at.x + Float.intBitsToFloat(v[k]),
                    (float)(at.y + (cap ? height : Float.intBitsToFloat(v[k+1]))),
                    (float)at.z + Float.intBitsToFloat(v[k+2]), Float.intBitsToFloat(v[k+4]), Float.intBitsToFloat(v[k+5]), rgb, light, face);
        }
    }

    private static int color(BakedQuad quad, BlockState state, ClientLevel level, BlockPos pos, Direction face) {
        //? if <1.21.5 {
        boolean shade = quad == null || quad.isShade();
        int tint = quad == null ? -1 : quad.getTintIndex();
        //?} else {
        boolean shade = quad == null || quad.shade();
        int tint = quad == null ? -1 : quad.tintIndex();
        //?}
        int rgb = tint < 0 ? -1 : Minecraft.getInstance().getBlockColors().getColor(state, level, pos, tint);
        float factor = level.getShade(face, shade);
        return 0xff000000 | ((int)((rgb >> 16 & 255) * factor) << 16) | ((int)((rgb >> 8 & 255) * factor) << 8) | (int)((rgb & 255) * factor);
    }

    private static void emit(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z,
                             float u, float v, int rgb, int light, Direction direction) {
        //? if <1.20.5 {
        out.vertex(pose.pose(), x, y, z).color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, 255)
                .uv(u,v).uv2(light).normal(pose.normal(), direction.getStepX(), direction.getStepY(), direction.getStepZ()).endVertex();
        //?} else if <1.21 {
        out.vertex(pose.pose(), x, y, z).color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, 255)
                .uv(u,v).uv2(light).normal(pose, direction.getStepX(), direction.getStepY(), direction.getStepZ()).endVertex();
        //?} else {
        out.addVertex(pose,x,y,z).setColor(rgb).setUv(u,v).setLight(light)
                .setNormal(pose,direction.getStepX(),direction.getStepY(),direction.getStepZ());
        //?}
    }

    private static boolean bounded(BlockPos first, BlockPos last, int limit) {
        double w = Math.abs((double)last.getX() - first.getX()) + 1;
        double h = Math.abs((double)last.getY() - first.getY()) + 1;
        double d = Math.abs((double)last.getZ() - first.getZ()) + 1;
        return w * h * d <= limit;
    }

    private static Vec3[] corners(ImmersiveOpening o) {
        var target = Minecraft.getInstance().getMainRenderTarget();
        var corners = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            float x = 2f * (o.left() + ((i & 1) == 0 ? 0 : o.width())) / target.width - 1;
            float y = 2f * (o.bottom() + ((i & 2) == 0 ? 0 : o.height())) / target.height - 1;
            var p = o.fromClip().transformProject(new Vector3f(x,y,2*o.cut()-1));
            corners[i] = new Vec3(p.x,p.y,p.z);
        }
        return corners;
    }

    static boolean section(Vec3 camera, PoseStack poses, ImmersiveOpening opening, float time) {
        var level = Minecraft.getInstance().level;
        if (level == null) return false;
        var corners = corners(opening);
        Vec3 normal = corners[1].subtract(corners[0]).cross(corners[2].subtract(corners[0])).normalize();
        if (normal.dot(corners[0]) < 0) normal = normal.scale(-1);
        Vec3 plane = camera.add(corners[0]).add(normal.scale(0.00025));
        AABB bounds = new AABB(corners[0], corners[3]).minmax(new AABB(corners[1], corners[2])).move(camera).inflate(0.0005);
        if (!Double.isFinite(bounds.getSize())) return false;
        var first = BlockPos.containing(bounds.minX,bounds.minY,bounds.minZ);
        var last = BlockPos.containing(bounds.maxX,bounds.maxY,bounds.maxZ);
        if (!bounded(first,last,4096)) return false;
        //? if <1.21.2 {
        Direction face = Direction.getNearest(-normal.x,-normal.y,-normal.z);
        //?} else {
        Direction face = Direction.getApproximateNearest(-normal.x,-normal.y,-normal.z);
        //?}
        boolean queued = false;
        int atPlayer = playerLight(level,time);
        for (BlockPos pos : BlockPos.betweenClosed(first,last)) {
            var state = level.getBlockState(pos);
            if (state.getRenderShape() != RenderShape.MODEL || !state.canOcclude() || !solid(ItemBlockRenderTypes.getChunkRenderType(state))) continue;
            var at = Vec3.atLowerCornerOf(pos);
            double d = at.subtract(plane).dot(normal);
            for (var box : shape(state,level,pos).toAabbs()) {
                var polygon = ImmersiveSection.section(box,d,normal,face);
                if (polygon.size() < 3) continue;
                var faceQuads = faces(state,pos,face);
                BakedQuad quad = null;
                for (var candidate : faceQuads) {
                    if (face(candidate) == face && (quad == null || !candidate.isTinted())) { quad = candidate; if (!quad.isTinted()) break; }
                }
                if (quad == null) for (var candidate : faces(state,pos,null)) { if (face(candidate) == face) { quad = candidate; break; } }
                var material = material(state,quad,face);
                int rgb = color(quad,state,level,pos,face);
                int atFace = LevelRenderer.getLightColor(level, pos.relative(face));
                int light = LightTexture.pack(Math.max(LightTexture.block(atFace),LightTexture.block(atPlayer)),
                        Math.max(LightTexture.sky(atFace),LightTexture.sky(atPlayer)));
                Vec3 base = at.subtract(camera);
                var out = buffers.getBuffer(RenderType.solid());
                for (int i = 1; i + 1 < polygon.size(); i++) {
                    for (Vec3 p : List.of(polygon.get(0),polygon.get(i),polygon.get(i+1),polygon.get(i+1))) {
                        float a = (float)(face.getAxis() == Direction.Axis.X ? p.z : p.x);
                        float b = (float)(face.getAxis() == Direction.Axis.Y ? p.z : p.y);
                        var uv = material.uv();
                        emit(out,poses.last(),(float)(base.x+p.x),(float)(base.y+p.y),(float)(base.z+p.z),
                                Mth.clamp(uv.m00()*a+uv.m10()*b+uv.m20(),material.sprite.getU0(),material.sprite.getU1()),
                                Mth.clamp(uv.m01()*a+uv.m11()*b+uv.m21(),material.sprite.getV0(),material.sprite.getV1()),rgb,light,face);
                    }
                }
                queued = true;
            }
        }
        return queued;
    }

    static void flushSection() { buffers.endBatch(); }

    private record Material(TextureAtlasSprite sprite, Matrix3x2f uv) {}

    private static Material material(BlockState state, BakedQuad quad, Direction face) {
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        //? if <1.21.5 {
        var sprite = quad == null ? model.getParticleIcon() : quad.getSprite();
        //?} else {
        var sprite = quad == null ? model.particleIcon() : quad.sprite();
        //?}
        if (quad != null) {
            var v = vertices(quad);
            var plane = basis(v,face.getAxis() == Direction.Axis.X ? 2 : 0,face.getAxis() == Direction.Axis.Y ? 2 : 1);
            if (Math.abs(plane.determinant()) > 1.0e-7f) return new Material(sprite,basis(v,4,5).mul(plane.invert()));
        }
        boolean flip = face == Direction.NORTH || face == Direction.EAST;
        float u = flip ? sprite.getU1() : sprite.getU0(), v = face == Direction.UP ? sprite.getV0() : sprite.getV1();
        return new Material(sprite, new Matrix3x2f((flip ? sprite.getU0() : sprite.getU1())-u,0,0,
                (face == Direction.UP ? sprite.getV1() : sprite.getV0())-v,u,v));
    }

    private static Matrix3x2f basis(int[] v, int a, int b) {
        int stride = v.length/4;
        float x = Float.intBitsToFloat(v[a]), y = Float.intBitsToFloat(v[b]);
        return new Matrix3x2f(Float.intBitsToFloat(v[stride+a])-x,Float.intBitsToFloat(v[stride+b])-y,
                Float.intBitsToFloat(v[3*stride+a])-x,Float.intBitsToFloat(v[3*stride+b])-y,x,y);
    }
    //?}
    private LegacyTerrainGeometry() {}
}
