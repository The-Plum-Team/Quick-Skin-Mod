package com.quickskin.mod.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix3x2f;

import java.util.List;

/** Projects a baked block face's atlas coordinates onto a cross-section of its material. */
final class ImmersiveSectionMaterial {
    private static final RandomSource RANDOM = RandomSource.create();
    private final Direction face;
    private final TextureAtlasSprite sprite;
    private final Matrix3x2f uv;
    private final float red, green, blue;
    private final int light;

    private ImmersiveSectionMaterial(Direction face, TextureAtlasSprite sprite, Matrix3x2f uv,
                                     int tint, float shade, int light) {
        this.face = face;
        this.sprite = sprite;
        this.uv = uv;
        red = (tint >> 16 & 255) * shade / 255.0f;
        green = (tint >> 8 & 255) * shade / 255.0f;
        blue = (tint & 255) * shade / 255.0f;
        this.light = light;
    }

    static ImmersiveSectionMaterial of(ClientLevel level, BlockPos pos, BlockState state, Direction face, int playerLight) {
        Minecraft game = Minecraft.getInstance();
        var model = game.getBlockRenderer().getBlockModel(state);
        ModelData data = level.getModelDataManager().getAt(pos);
        data = model.getModelData(level, pos, state, data == null ? ModelData.EMPTY : data);
        long seed = state.getSeed(pos);
        RANDOM.setSeed(seed);
        var layers = model.getRenderTypes(state, RANDOM, data);
        if (!layers.contains(RenderType.solid()) && !layers.contains(RenderType.cutout())
                && !layers.contains(RenderType.cutoutMipped())) return null;
        BakedQuad quad = null;
        for (RenderType layer : layers) {
            if (layer != RenderType.solid() && layer != RenderType.cutout() && layer != RenderType.cutoutMipped()) continue;
            RANDOM.setSeed(seed);
            quad = baseFace(model.getQuads(state, face, RANDOM, data, layer), face);
            if (quad == null) {
                RANDOM.setSeed(seed);
                quad = baseFace(model.getQuads(state, null, RANDOM, data, layer), face);
            }
            if (quad != null) break;
        }
        TextureAtlasSprite sprite = quad == null ? model.getParticleIcon(data) : quad.getSprite();
        var uv = mapping(quad, sprite, face);
        int tint = quad != null && quad.isTinted()
                ? game.getBlockColors().getColor(state, level, pos, quad.getTintIndex()) : -1;
        float shade = level.getShade(face, quad == null || quad.isShade());
        int nearby = LevelRenderer.getLightColor(level, state, pos.relative(face));
        int light = LightTexture.pack(Math.max(LightTexture.block(nearby), LightTexture.block(playerLight)),
                Math.max(LightTexture.sky(nearby), LightTexture.sky(playerLight)));
        return new ImmersiveSectionMaterial(face, sprite, uv, tint, shade, light);
    }

    private static BakedQuad baseFace(List<BakedQuad> quads, Direction face) {
        BakedQuad tinted = null;
        for (BakedQuad quad : quads) {
            if (quad.getDirection() != face) continue;
            // A grass-block side can include an additional biome-tinted, partly transparent overlay.
            if (!quad.isTinted()) return quad;
            if (tinted == null) tinted = quad;
        }
        return tinted;
    }

    private static Matrix3x2f mapping(BakedQuad quad, TextureAtlasSprite sprite, Direction face) {
        if (quad != null) {
            int[] vertices = quad.getVertices();
            int uAxis = face.getAxis() == Direction.Axis.X ? 2 : 0;
            int vAxis = face.getAxis() == Direction.Axis.Y ? 2 : 1;
            Matrix3x2f plane = basis(vertices, uAxis, vAxis);
            if (Math.abs(plane.determinant()) > 1.0e-7f) return basis(vertices, 4, 5).mul(plane.invert());
        }
        boolean flipU = face == Direction.NORTH || face == Direction.EAST;
        boolean flipV = face != Direction.UP;
        float u0 = flipU ? sprite.getU1() : sprite.getU0();
        float v0 = flipV ? sprite.getV1() : sprite.getV0();
        return new Matrix3x2f((flipU ? sprite.getU0() : sprite.getU1()) - u0, 0, 0,
                (flipV ? sprite.getV0() : sprite.getV1()) - v0, u0, v0);
    }

    /** Block vertices 0, 1 and 3 give the origin and two adjacent edges in either face space or UV space. */
    private static Matrix3x2f basis(int[] vertices, int a, int b) {
        int stride = vertices.length / 4;
        float a0 = Float.intBitsToFloat(vertices[a]);
        float b0 = Float.intBitsToFloat(vertices[b]);
        return new Matrix3x2f(Float.intBitsToFloat(vertices[stride + a]) - a0, Float.intBitsToFloat(vertices[stride + b]) - b0,
                Float.intBitsToFloat(vertices[3 * stride + a]) - a0, Float.intBitsToFloat(vertices[3 * stride + b]) - b0, a0, b0);
    }

    void vertex(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z) {
        float a = face.getAxis() == Direction.Axis.X ? z : x;
        float b = face.getAxis() == Direction.Axis.Y ? z : y;
        float u = Mth.clamp(uv.m00() * a + uv.m10() * b + uv.m20(), sprite.getU0(), sprite.getU1());
        float v = Mth.clamp(uv.m01() * a + uv.m11() * b + uv.m21(), sprite.getV0(), sprite.getV1());
        out.vertex(pose.pose(), x, y, z).color(red, green, blue, 1.0f).uv(u, v).uv2(light)
                .normal(pose.normal(), face.getStepX(), face.getStepY(), face.getStepZ()).endVertex();
    }
}
