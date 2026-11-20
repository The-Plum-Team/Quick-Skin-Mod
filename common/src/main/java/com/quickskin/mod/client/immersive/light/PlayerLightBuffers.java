package com.quickskin.mod.client.immersive.light;

//? if <1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Vector3f;
//? if <1.21 {
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//?} else {
import com.mojang.blaze3d.vertex.VertexFormatElement;
//?}
//?}

/** Applies the player's local light to the direct vertex stream without changing the world's buffers. */
public final class PlayerLightBuffers {
    private PlayerLightBuffers() {}

    //? if <1.21.9 {
    public static MultiBufferSource wrap(PoseStack view, MultiBufferSource buffers) {
        PlayerFaceLight light = PlayerFaceLight.forView(view);
        if (light == null) return buffers;
        return type -> {
            VertexConsumer target = buffers.getBuffer(type);
            if (type.mode() != VertexFormat.Mode.QUADS) return target;
            //? if <1.21 {
            if (!type.format().getElements().contains(DefaultVertexFormat.ELEMENT_NORMAL)) return target;
            //?} else {
            if (!type.format().contains(VertexFormatElement.NORMAL)) return target;
            //?}
            return new FaceVertices(target, light);
        };
    }

    private static final class FaceVertices implements VertexConsumer {
        private final VertexConsumer target;
        private final PlayerFaceLight light;
        private final Vector3f normal = new Vector3f();

        FaceVertices(VertexConsumer target, PlayerFaceLight light) {
            this.target = target;
            this.light = light;
        }

        //? if <1.21 {
        @Override public VertexConsumer vertex(double x, double y, double z) {
            target.vertex(x, y, z);
            return this;
        }

        @Override public VertexConsumer color(int red, int green, int blue, int alpha) {
            target.color(red, green, blue, alpha);
            return this;
        }

        @Override public VertexConsumer uv(float u, float v) {
            target.uv(u, v);
            return this;
        }

        @Override public VertexConsumer overlayCoords(int u, int v) {
            target.overlayCoords(u, v);
            return this;
        }

        @Override public VertexConsumer uv2(int u, int v) {
            target.uv2(u, v);
            return this;
        }

        @Override public VertexConsumer normal(float x, float y, float z) {
            light.apply(x, y, z, normal);
            target.normal(normal.x, normal.y, normal.z);
            return this;
        }

        @Override public void endVertex() {
            target.endVertex();
        }

        @Override public void defaultColor(int r, int g, int b, int a) {
            target.defaultColor(r, g, b, a);
        }

        @Override public void unsetDefaultColor() {
            target.unsetDefaultColor();
        }

        @Override public void vertex(float x, float y, float z, float r, float g, float b, float a,
                                     float u, float v, int overlay, int packedLight, float nx, float ny, float nz) {
            light.apply(nx, ny, nz, normal);
            target.vertex(x, y, z, r, g, b, a, u, v, overlay, packedLight, normal.x, normal.y, normal.z);
        }
        //?} else {
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            target.addVertex(x, y, z);
            return this;
        }

        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            target.setColor(red, green, blue, alpha);
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) {
            target.setUv(u, v);
            return this;
        }

        @Override public VertexConsumer setUv1(int u, int v) {
            target.setUv1(u, v);
            return this;
        }

        @Override public VertexConsumer setUv2(int u, int v) {
            target.setUv2(u, v);
            return this;
        }

        @Override public VertexConsumer setNormal(float x, float y, float z) {
            light.apply(x, y, z, normal);
            target.setNormal(normal.x, normal.y, normal.z);
            return this;
        }

        @Override public void addVertex(float x, float y, float z, int color, float u, float v,
                                        int overlay, int packedLight, float nx, float ny, float nz) {
            light.apply(nx, ny, nz, normal);
            target.addVertex(x, y, z, color, u, v, overlay, packedLight, normal.x, normal.y, normal.z);
        }
        //?}
    }
    //?}
}
