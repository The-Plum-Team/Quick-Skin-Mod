package com.quickskin.mod.forge;

import com.quickskin.mod.client.immersive.ImmersiveOpening;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.pipeline.VertexConsumerWrapper;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.List;

/** Supplies the missing inward-facing terrain where the camera's near plane cuts into a block. */
final class ImmersiveTerrainInterior {
    private static final MultiBufferSource.BufferSource buffers =
            MultiBufferSource.immediate(new BufferBuilder(8192));
    private static final RandomSource random = RandomSource.create();

    static void draw(RenderLevelStageEvent event) {
        Minecraft game = Minecraft.getInstance();
        ClientLevel level = game.level;
        if (level == null || game.player == null) return;
        PoseStack poses = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f fromClip = new Matrix4f(event.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix())
                .mul(poses.last().pose()).invert();
        if (!fromClip.isFinite()) return;

        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f corner = new Vector3f();
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                fromClip.transformProject(corner.set(x, y, -1));
                if (!corner.isFinite()) return;
                min.min(corner);
                max.max(corner);
            }
        }
        // Give an axis-aligned near plane positive thickness for the voxel-shape intersection.
        AABB near = new AABB(min.x, min.y, min.z, max.x, max.y, max.z).move(camera).inflate(0.0001);
        BlockPos first = BlockPos.containing(near.minX, near.minY, near.minZ);
        BlockPos last = BlockPos.containing(near.maxX, near.maxY, near.maxZ);
        // A camera near plane only touches a handful of blocks, even across a block corner.
        if (!withinBlockBudget(first, last, 64)) return;
        var renderer = game.getBlockRenderer();
        BlockPos lightProbe = BlockPos.containing(game.player.getLightProbePosition(event.getPartialTick()));
        int playerLight = LevelRenderer.getLightColor(level, lightProbe);
        int originalCullFace = 0;
        boolean cullEnabled = false;
        try {
            for (BlockPos pos : BlockPos.betweenClosed(first, last)) {
                BlockState state = level.getBlockState(pos);
                if (state.getRenderShape() != RenderShape.MODEL || !state.canOcclude()) continue;
                if (!Shapes.joinIsNotEmpty(state.getOcclusionShape(level, pos),
                        Shapes.create(near.move(-pos.getX(), -pos.getY(), -pos.getZ())), BooleanOp.AND)) continue;
                var model = renderer.getBlockModel(state);
                ModelData data = level.getModelDataManager().getAt(pos);
                data = model.getModelData(level, pos, state, data == null ? ModelData.EMPTY : data);
                random.setSeed(state.getSeed(pos));
                for (RenderType layer : model.getRenderTypes(state, random, data)) {
                    if (layer != RenderType.solid() && layer != RenderType.cutoutMipped()
                            && layer != RenderType.cutout()) continue;
                    if (originalCullFace == 0) {
                        originalCullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
                        cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
                        RenderSystem.enableCull();
                        GL11.glCullFace(GL11.GL_FRONT);
                    }
                    poses.pushPose();
                    try {
                        Vec3 offset = state.getOffset(level, pos);
                        poses.translate(pos.getX() - camera.x + offset.x, pos.getY() - camera.y + offset.y,
                                pos.getZ() - camera.z + offset.z);
                        // Buried faces have no incident light and all their AO neighbors are solid.
                        // Keep the model's tint and directional shading, using the player's light as a floor.
                        var lit = new VertexConsumerWrapper(buffers.getBuffer(layer)) {
                            @Override
                            public VertexConsumer uv2(int block, int sky) {
                                return super.uv2(Math.max(block, playerLight & 0xffff), Math.max(sky, playerLight >>> 16));
                            }
                        };
                        renderer.getModelRenderer().tesselateWithoutAO(level, model, state, pos, poses, lit, false,
                                random, state.getSeed(pos), OverlayTexture.NO_OVERLAY, data, layer);
                    } finally {
                        poses.popPose();
                    }
                }
            }
        } finally {
            if (originalCullFace != 0) {
                try {
                    buffers.endBatch();
                } finally {
                    GL11.glCullFace(originalCullFace);
                    // Forge fires AFTER_SOLID_BLOCKS before it clears the solid layer's render state.
                    RenderType.solid().setupRenderState();
                    if (!cullEnabled) RenderSystem.disableCull();
                }
            }
        }
    }

    /** Bound visits before iterating a volume reconstructed from a renderer-supplied projection. */
    static boolean withinBlockBudget(BlockPos first, BlockPos last, int budget) {
        double width = 1 + Math.abs((double) last.getX() - first.getX());
        double height = 1 + Math.abs((double) last.getY() - first.getY());
        double depth = 1 + Math.abs((double) last.getZ() - first.getZ());
        return width * height * depth <= budget;
    }

    /** Restore the real support surfaces removed by the clipped terrain pass, before the skin is drawn. */
    static void drawGround(LocalPlayer player, Vec3 camera, PoseStack poses, float time, ImmersiveOpening opening) {
        float cut = opening.cut();
        double feet = Mth.lerp(time, player.yOld, player.getY());
        if (camera.y < feet || !(cut > 0 && cut < 1)) return;
        Minecraft game = Minecraft.getInstance();
        ClientLevel level = game.level;
        if (level == null) return;
        var target = game.getMainRenderTarget();
        int[] box = {opening.left(), opening.bottom(), opening.width(), opening.height()};
        // Map the scissored pixels and original depth interval [0, cut] to the full clip cube.
        Matrix4f aperture = new Matrix4f().scaling((float)target.width / box[2], (float)target.height / box[3], 1)
                .m30((float)(target.width - 2 * box[0] - box[2]) / box[2])
                .m31((float)(target.height - 2 * box[1] - box[3]) / box[3])
                .mul(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix()).mul(poses.last().pose());
        var sides = new FrustumIntersection(aperture);
        Matrix4f beam = new Matrix4f().scaling(1, 1, 1 / cut).m32((1 - cut) / cut).mul(aperture);
        var frustum = new FrustumIntersection(beam);
        Matrix4f fromBeam = beam.invert();
        if (!fromBeam.isFinite()) return;
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f point = new Vector3f();
        for (int i = 0; i < 8; i++) {
            fromBeam.transformProject(point.set((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1));
            if (!point.isFinite()) return;
            min.min(point);
            max.max(point);
        }
        int supportRow = Mth.floor(feet - 0.0001);
        boolean partial = feet - supportRow < 0.9999;
        if (min.y + camera.y > feet && !partial) return;
        // A ray entering a full neighbor above the slab reaches the lower section farther away.
        // Similar triangles give that reach. Keep the search local for nearly horizontal rays.
        double reach = partial ? Mth.clamp((camera.y - feet) / Math.max(0.001, camera.y - supportRow - 1), 1, 3) : 1;
        BlockPos first = BlockPos.containing(camera.x + Math.min(min.x, min.x * reach),
                Math.min(supportRow, camera.y + min.y - 0.001), camera.z + Math.min(min.z, min.z * reach));
        BlockPos last = BlockPos.containing(camera.x + Math.max(max.x, max.x * reach),
                Math.min(camera.y + max.y, feet - 0.0001), camera.z + Math.max(max.z, max.z * reach));
        if (!withinBlockBudget(first, last, 16 * 16 * 16)) return;
        var renderer = game.getBlockRenderer();
        try {
            for (BlockPos pos : BlockPos.betweenClosed(first, last)) {
                float x = (float)(pos.getX() - camera.x), y = (float)(pos.getY() - camera.y), z = (float)(pos.getZ() - camera.z);
                boolean clipped = frustum.testAab(x, y, z, x + 1, y + 1, z + 1);
                if (!clipped && (!partial || pos.getY() != supportRow
                        || !sides.testAab(x, y, z, x + 1, y + 1, z + 1))) continue;
                BlockState state = level.getBlockState(pos);
                if (state.getRenderShape() != RenderShape.MODEL || state.getCollisionShape(level, pos).isEmpty()) continue;
                boolean section = partial && pos.getY() == supportRow && state.isSolidRender(level, pos)
                        && state.isCollisionShapeFullBlock(level, pos);
                if (!clipped && !section) continue;
                var model = renderer.getBlockModel(state);
                ModelData data = level.getModelDataManager().getAt(pos);
                data = model.getModelData(level, pos, state, data == null ? ModelData.EMPTY : data);
                long seed = state.getSeed(pos);
                random.setSeed(seed);
                double height = feet - pos.getY() - state.getOffset(level, pos).y;
                var ground = new GroundModel(model, level, pos, height, section);
                for (RenderType layer : model.getRenderTypes(state, random, data)) {
                    if (layer != RenderType.solid() && layer != RenderType.cutoutMipped() && layer != RenderType.cutout()) continue;
                    poses.pushPose();
                    try {
                        poses.translate(x, y, z);
                        renderer.getModelRenderer().tesselateBlock(level, ground, state, pos, poses,
                                buffers.getBuffer(layer), false, random, seed, OverlayTexture.NO_OVERLAY, data, layer);
                    } finally {
                        poses.popPose();
                    }
                }
            }
        } finally {
            buffers.endBatch();
        }
    }

    private static final class GroundModel extends BakedModelWrapper<BakedModel> {
        private final ClientLevel level;
        private final BlockPos pos;
        private final double height;
        private final boolean section;

        GroundModel(BakedModel original, ClientLevel level, BlockPos pos, double height, boolean section) {
            super(original);
            this.level = level;
            this.pos = pos;
            this.height = height;
            this.section = section;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction face, RandomSource random,
                                        ModelData data, RenderType layer) {
            // Use ordinary neighbor occlusion, except for a floor directly under a wall the aperture removed.
            boolean exposedFloor = face == Direction.UP && (section || Math.abs(height - 1.0) < 0.0001);
            if (face != null && !exposedFloor && !Block.shouldRenderFace(state, level, pos, face, pos.relative(face))) {
                return List.of();
            }
            var quads = super.getQuads(state, face, random, data, layer);
            if (height >= 1.0) return quads;
            // Stairs can have both a lower tread and a wall in the very same block.
            var visible = new java.util.ArrayList<BakedQuad>(quads.size());
            for (BakedQuad quad : quads) {
                int[] vertices = quad.getVertices();
                int stride = vertices.length / 4;
                boolean below = true;
                boolean top = section && quad.getDirection() == Direction.UP;
                for (int i = 1; i < vertices.length; i += stride) {
                    float y = Float.intBitsToFloat(vertices[i]);
                    below &= y <= height + 0.0001;
                    top &= Math.abs(y - 1) < 0.0001;
                }
                if (below) visible.add(quad);
                else if (top) {
                    // A full opaque cube contains material throughout this section. Reuse its
                    // top surface with identical atlas coordinates, tint and lighting.
                    int[] cap = vertices.clone();
                    for (int i = 1; i < cap.length; i += stride) cap[i] = Float.floatToRawIntBits((float)height);
                    visible.add(new BakedQuad(cap, quad.getTintIndex(), Direction.UP, quad.getSprite(),
                            quad.isShade(), quad.hasAmbientOcclusion()));
                }
            }
            return visible;
        }
    }

    private ImmersiveTerrainInterior() {}
}
