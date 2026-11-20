package com.quickskin.mod.forge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.client.immersive.ImmersiveOpening;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import com.quickskin.mod.client.immersive.ImmersiveSection;
import java.util.List;

/** Draws the exposed section of opaque terrain where the opening's virtual near plane slices it. */
final class ImmersiveTerrainSection {
    private static final BufferBuilder vertices = new BufferBuilder(8192);
    // Sample the retained side of a voxel boundary, just inside the projection's near plane.
    private static final double INSET = 0.00025;

    static void draw(Vec3 camera, PoseStack poses, float partialTick, ImmersiveOpening opening) {
        Minecraft game = Minecraft.getInstance();
        var level = game.level;
        if (level == null || game.player == null) return;
        var target = game.getMainRenderTarget();
        float left = 2f * opening.left() / target.width - 1;
        float right = 2f * (opening.left() + opening.width()) / target.width - 1;
        float bottom = 2f * opening.bottom() / target.height - 1;
        float top = 2f * (opening.bottom() + opening.height()) / target.height - 1;
        Vec3 a = corner(opening, left, bottom), b = corner(opening, right, bottom);
        Vec3 c = corner(opening, left, top), d = corner(opening, right, top);
        Vec3 normal = b.subtract(a).cross(c.subtract(a)).normalize();
        if (normal.dot(a) < 0) normal = normal.scale(-1);
        Vec3 planePoint = camera.add(a).add(normal.scale(INSET));
        AABB bounds = new AABB(a, d).minmax(new AABB(b, c)).move(camera).inflate(2 * INSET);
        if (!Double.isFinite(bounds.getSize())) return;
        BlockPos first = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos last = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
        if (!ImmersiveTerrainInterior.withinBlockBudget(first, last, 16 * 16 * 16)) return;
        Direction face = Direction.getNearest(-normal.x, -normal.y, -normal.z);
        int light = LevelRenderer.getLightColor(level,
                BlockPos.containing(game.player.getLightProbePosition(partialTick)));
        try {
            for (BlockPos pos : BlockPos.betweenClosed(first, last)) {
                var state = level.getBlockState(pos);
                if (!state.canOcclude() || state.getRenderShape() != RenderShape.MODEL) continue;
                double localOffset = Vec3.atLowerCornerOf(pos).subtract(planePoint).dot(normal);
                ImmersiveSectionMaterial material = null;
                for (AABB box : state.getOcclusionShape(level, pos).toAabbs()) {
                    List<Vec3> polygon = ImmersiveSection.section(box, localOffset, normal, face);
                    if (polygon.size() < 3) continue;
                    if (material == null) {
                        material = ImmersiveSectionMaterial.of(level, pos, state, face, light);
                        if (material == null) break;
                    }
                    poses.pushPose();
                    try {
                        poses.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
                        if (!vertices.building()) vertices.begin(RenderType.solid().mode(), RenderType.solid().format());
                        // The terrain buffer expects quads. Each fan triangle has a repeated final vertex.
                        for (int i = 1; i + 1 < polygon.size(); i++) {
                            vertex(material, vertices, poses.last(), polygon.get(0));
                            vertex(material, vertices, poses.last(), polygon.get(i));
                            vertex(material, vertices, poses.last(), polygon.get(i + 1));
                            vertex(material, vertices, poses.last(), polygon.get(i + 1));
                        }
                    } finally {
                        poses.popPose();
                    }
                }
            }
        } finally {
            if (vertices.building()) drawColor();
        }
    }

    private static void drawColor() {
        var layer = RenderType.solid();
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        layer.setupRenderState();
        try {
            RenderSystem.depthMask(false);
            BufferUploader.drawWithShader(vertices.end());
        } finally {
            layer.clearRenderState();
            RenderSystem.depthMask(depthWrite);
        }
    }

    private static Vec3 corner(ImmersiveOpening opening, float x, float y) {
        Vector3f p = opening.fromClip().transformProject(new Vector3f(x, y, 2 * opening.cut() - 1));
        return new Vec3(p.x, p.y, p.z);
    }

    private static void vertex(ImmersiveSectionMaterial material, VertexConsumer out, PoseStack.Pose pose, Vec3 point) {
        material.vertex(out, pose, (float)point.x, (float)point.y, (float)point.z);
    }

    private ImmersiveTerrainSection() {}
}
