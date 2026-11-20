package com.quickskin.mod.client.immersive;

//? if >=1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//? if <26.3 {
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.shaders.UniformType;
//? if >=26.1 {
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
//?}
//? if >=26.2 {
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
//?} else {
import com.mojang.blaze3d.vertex.VertexFormat;
//?}
//?} else {
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
//?}
//? if >=26.2 {
import net.minecraft.client.renderer.BindGroupLayouts;
//?}
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
//?}

/** Draws the floor with depth and the cut's opaque colour with the floor's original depth. */
public final class ApertureSectionDraw {
    private ApertureSectionDraw() {}
//? if >=1.21.6 {
    private static RenderPipeline floors, caps;
    private static GpuBuffer groundBuffer, sectionBuffer;
    private static boolean drawn;

    static void register(RenderPipeline terrain) {
        if (floors != null || !terrain.getLocation().getNamespace().equals("minecraft")) return;
        // Use the terrain's comparison so reversed-Z worlds retain their own depth convention.
        floors = pipeline(terrain, true);
        caps = pipeline(terrain, false);
    }

    static void addPrograms(List<RenderPipeline> list) {
        if (floors != null) {
            list.add(floors);
            list.add(caps);
        }
    }

    private static RenderPipeline pipeline(RenderPipeline terrain, boolean writeDepth) {
        String name = writeDepth ? "floor" : "cut";
        //? if <1.21.11 {
        var id = ResourceLocation.fromNamespaceAndPath("quickskin", "aperture/" + name);
        var shader = ResourceLocation.fromNamespaceAndPath("quickskin", "core/aperture_section_gl");
        //?} else {
        var id = Identifier.fromNamespaceAndPath("quickskin", "aperture/" + name);
        //? if <26.3 {
        var shader = Identifier.fromNamespaceAndPath("quickskin", "core/aperture_section_gl");
        //?} else {
        var shader = Identifier.fromNamespaceAndPath("quickskin", "core/aperture_section_gpu");
        //?}
        //?}
        var build = RenderPipeline.builder().withLocation(id).withVertexShader(shader).withFragmentShader(shader).withCull(false);
        //? if <26.2 {
        build.withSampler("Sampler0").withUniform("QuickSkinAperture", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.TRIANGLES);
        //?} else {
        build.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("QuickSkinAperture", UniformType.UNIFORM_BUFFER).build())
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        //?}
        //? if <26.1 {
        build.withDepthTestFunction(terrain.getDepthTestFunction()).withDepthWrite(writeDepth);
        //?} else {
        build.withDepthStencilState(new DepthStencilState(terrain.getDepthStencilState().depthTest(), writeDepth))
                .withColorTargetState(ColorTargetState.DEFAULT);
        //?}
        return build.build();
    }

    static void upload() {
        drawn = false;
        groundBuffer = upload(ApertureSections.ground, groundBuffer);
        sectionBuffer = upload(ApertureSections.section, sectionBuffer);
    }

    private static GpuBuffer upload(ApertureSections.Surface surface, GpuBuffer buffer) {
        if (surface.count() == 0) return buffer;
        var data = surface.bytes();
        if (buffer == null || buffer.size() < data.remaining()) {
            if (buffer != null) buffer.close();
            buffer = RenderSystem.getDevice().createBuffer(() -> "Quick Skin aperture sections",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, Integer.highestOneBit(data.remaining() - 1) << 1);
        }
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, data.remaining()), data);
        return buffer;
    }

    /** Draw the newly visible solid faces into the world's opaque pass before entities. */
    public static void draw(RenderPass pass) {
        if (ImmersiveShaderPacks.active()) return;
        drawSections(pass);
    }

    /** Alternate terrain renderers own their G-buffer. Complete its visible surfaces only after they finish. */
    public static void finishWorld() {
        if (drawn || ModernAperture.opening() == null || floors == null) return;
        if (ApertureSections.ground.count() == 0 && ApertureSections.section.count() == 0) return;
        var game = Minecraft.getInstance();
        //? if <26.2 {
        var world = game.getMainRenderTarget();
        //?} else {
        var world = game.gameRenderer.mainRenderTarget();
        //?}
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        //? if <26.2 {
        var pass = encoder.createRenderPass(() -> "Quick Skin terrain sections", world.getColorTextureView(),
                java.util.OptionalInt.empty(), world.getDepthTextureView(), OptionalDouble.empty());
        //?} else {
        var pass = encoder.createRenderPass(() -> "Quick Skin terrain sections", world.getColorTextureView(),
                java.util.Optional.empty(), world.getDepthTextureView(), OptionalDouble.empty());
        //?}
        try (pass) {
            drawSections(pass);
        }
    }

    private static void drawSections(RenderPass pass) {
        if (drawn || ModernAperture.opening() == null || floors == null) return;
        if (ApertureSections.ground.count() == 0 && ApertureSections.section.count() == 0) return;
        drawn = true;
        // A fully occluded chunk list can leave the native pass with no atlas bound.
        var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        //? if <1.21.11 {
        pass.bindSampler("Sampler0", atlas.getTextureView());
        //?} else if <26.3 {
        pass.bindTexture("Sampler0", atlas.getTextureView(), atlas.getSampler());
        //?} else {
        pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
        //?}
        draw(pass, floors, groundBuffer, ApertureSections.ground.count());
        draw(pass, caps, sectionBuffer, ApertureSections.section.count());
    }

    private static void draw(RenderPass pass, RenderPipeline pipeline, GpuBuffer vertices, int count) {
        if (count == 0 || vertices == null) return;
        //? if <26.3 {
        pass.setPipeline(pipeline);
        //?} else {
        var program = RenderSystem.getCompiledPipelineNullable(pipeline);
        if (program == null) return;
        pass.setPipeline(program);
        //?}
        ModernAperture.bindSection(pass);
        //? if <26.2 {
        pass.setVertexBuffer(0, vertices);
        pass.draw(0, count);
        //?} else {
        pass.setVertexBuffer(0, vertices.slice());
        pass.draw(count, 1, 0, 0);
        //?}
    }

    static void release() {
        if (groundBuffer != null) groundBuffer.close();
        if (sectionBuffer != null) sectionBuffer.close();
        groundBuffer = null;
        sectionBuffer = null;
    }
//?}
}
