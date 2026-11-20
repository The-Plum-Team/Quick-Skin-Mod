package com.quickskin.mod.client.immersive;

//? if >=1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
//? if <26.3 {
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
//? if >=26.2 {
import com.mojang.blaze3d.pipeline.BindGroupLayout;
//?}
//?} else {
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
//?}
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
//?}

/** The terrain pass opens the circle before the unmodified entity and shadow passes draw. */
public final class ModernAperture {
    private ModernAperture() {}

//? if >=1.21.6 {
    private static final Map<RenderPipeline, RenderPipeline> variants = new IdentityHashMap<>();
    private static final int UNIFORM_BYTES = 96;
    private static ImmersiveOpening opening;
    private static GpuBuffer uniforms;
    private static int width, height;
    private static float breath;

    public static void register(RenderPipeline original, RenderPipeline.Builder settings) {
        if (original.getLocation().getNamespace().equals("quickskin")) return;
        //? if <26.3 {
        var fragment = original.getFragmentShader();
        //?} else {
        var fragment = original.getShaders().get(ShaderType.FRAGMENT);
        //?}
        if (fragment == null || !(fragment.toString().equals("minecraft:core/terrain")
                || fragment.toString().equals("sodium:blocks/block_layer_opaque"))) return;
        String path = "aperture/" + original.getLocation().getNamespace() + "/" + original.getLocation().getPath();
        //? if <1.21.11 {
        var id = ResourceLocation.fromNamespaceAndPath("quickskin", path);
        //?} else {
        var id = Identifier.fromNamespaceAndPath("quickskin", path);
        //?}
        var builder = RenderPipeline.builder(settings.buildSnippet()).withLocation(id)
                .withShaderDefine("QUICKSKIN_APERTURE").withCull(false);
        //? if <26.2 {
        builder.withUniform("QuickSkinAperture", UniformType.UNIFORM_BUFFER);
        //?} else {
        builder.withBindGroupLayout(BindGroupLayout.builder().withUniform("QuickSkinAperture", UniformType.UNIFORM_BUFFER).build());
        //?}
        variants.put(original, builder.build());
        ApertureSectionDraw.register(original);
    }

    public static List<RenderPipeline> withTerrainVariants(List<RenderPipeline> pipelines) {
        List<RenderPipeline> result = new ArrayList<>(pipelines);
        result.addAll(variants.values());
        ApertureSectionDraw.addPrograms(result);
        return result;
    }

    public static RenderPipeline variant(RenderPipeline original) {
        // Iris selects its material program by the identity of the original pipeline.
        if (opening() == null || ImmersiveShaderPacks.active()) return original;
        var replacement = variants.get(original);
        if (replacement == null) return original;
        //? if >=26.3 {
        if (RenderSystem.getCompiledPipelineNullable(replacement) == null) return original;
        //?}
        return replacement;
    }

    public static void bind(RenderPass pass) {
        if (!ImmersiveShaderPacks.active() && opening() != null && uniforms != null) pass.setUniform("QuickSkinAperture", uniforms);
    }

    static void bindSection(RenderPass pass) {
        if (uniforms != null) pass.setUniform("QuickSkinAperture", uniforms);
    }

    public static ImmersiveOpening opening() {
        return ImmersiveMode.posesPlayer() && !ImmersiveShaderPacks.shadows() ? opening : null;
    }

    public static void prepare(Matrix4f projection, Matrix4f view) {
        if (ImmersiveMode.posesPlayer() && ImmersiveShaderPacks.shadows()) return;
        opening = null;
        if (variants.isEmpty()) return;
        ImmersiveMode.enableBlockReveal();
        if (!ImmersiveMode.posesPlayer()) {
            if (uniforms != null) {
                ApertureSectionDraw.release();
                uniforms.close();
                uniforms = null;
            }
            return;
        }
        var game = Minecraft.getInstance();
        var player = game.player;
        var level = game.level;
        if (player == null || level == null) return;
        //? if <26.2 {
        var camera = game.gameRenderer.getMainCamera();
        //?} else {
        var camera = game.gameRenderer.mainCamera();
        //?}
        if (!camera.isDetached()) return;
        //? if <1.21.11 {
        Vec3 eye = camera.getPosition();
        //?} else {
        Vec3 eye = camera.position();
        //?}
        float time = game.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 feet = player.getPosition(time);
        width = game.getWindow().getWidth();
        height = game.getWindow().getHeight();
        if (view == null) view = new Matrix4f().rotation(new Quaternionf(camera.rotation()).conjugate());
        var pos = new BlockPos.MutableBlockPos();
        opening = ImmersiveOpening.find(projection, view, width, height, eye, feet, player.getBbHeight(),
                player.getBbWidth(), projection.m22() >= 0, (x, y, z, cell) -> {
                    pos.set(x, y, z);
                    var state = level.getBlockState(pos);
                    if (state.getRenderShape() == RenderShape.INVISIBLE) return false;
                    cell.set(state.getOffset(pos), state.getCollisionShape(level, pos).isEmpty(), state.hasBlockEntity());
                    return true;
                });
        if (opening == null) return;
        ApertureSections.build(opening, eye, feet, view);
        ApertureSectionDraw.upload();
        breath = (float)Math.sin((System.nanoTime() % 6_000_000_000L) * (2.0 * Math.PI / 6_000_000_000L));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.malloc(UNIFORM_BYTES);
            fillUniforms(data);
            if (uniforms == null) {
                uniforms = RenderSystem.getDevice().createBuffer(() -> "Quick Skin terrain aperture",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, data);
            } else {
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(uniforms.slice(), data);
            }
        }
    }

    public static void fillUniforms(ByteBuffer data) {
        opening.fromClip().get(0, data);
        data.putFloat(64, opening.x()).putFloat(68, opening.y()).putFloat(72, opening.radius()).putFloat(76, opening.distance());
        data.putFloat(80, width).putFloat(84, height).putFloat(88, opening.floor()).putFloat(92, breath);
    }
//?}
}
