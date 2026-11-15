package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.client.compat.CpmModelPreview;
import com.quickskin.mod.common.util.ModelMesh;
import com.quickskin.mod.platform.MinecraftCompat;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <1.21.11 {
import net.minecraft.client.model.PlayerModel;
//?} else {
import net.minecraft.client.model.player.PlayerModel;
//?}
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
//? if <1.21.9 {
import net.minecraft.client.renderer.MultiBufferSource;
//?} else {
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
//?}
//? if <1.21.11 {
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A CPM model's geometry, captured once so the skin list and the favourites fan can show small
 * pictures of the model itself instead of the icon its author saved in the file.
 *
 * <p>CPM draws a model by writing its vertices where a vanilla model would write its own. Here
 * a model of Quick Skin's own, standing at rest, is bound to the CPM model
 * ({@link CpmModelPreview}) and drawn into a recorder instead of the screen: the vertices it
 * writes, with the pixels of CPM's texture, are the mesh
 * ({@link com.quickskin.mod.common.util.ModelMeshRasterizer} draws the pictures from it, off the
 * render thread). Nothing reaches the screen and no CPM class is named.
 *
 * <p>A capture needs CPM to have built the model, which takes a moment after it is first asked
 * for: {@link #mesh} answers null until then, and for good when CPM cannot draw the model, and
 * the caller keeps showing the icon.
 */
@Environment(EnvType.CLIENT)
public final class CpmModelCapture {
    private static final int MAX_MESHES = 8;
    /** A texture is read at most this many pixels large; a larger one is read every few texels. */
    private static final int MAX_TEXTURE_PIXELS = 1024 * 1024;
    private static final long ATTEMPT_INTERVAL_NANOS = 150_000_000L;
    private static final long GIVE_UP_NANOS = 8_000_000_000L;
    private static final long RETRY_AFTER_GIVING_UP_NANOS = 60_000_000_000L;

    /** One model's capture: its mesh once CPM drew it, or when it was last tried. */
    private static final class Slot {
        ModelMesh mesh;
        long firstAttemptNanos;
        long lastAttemptNanos;
        boolean gaveUp;
    }

    private static final Map<String, Slot> SLOTS = new LinkedHashMap<>(16, 0.75f, true);
    //? if <1.21.2 {
    private static PlayerModel<?> captureModel;
    //?} else {
    private static PlayerModel captureModel;
    //?}

    private CpmModelCapture() {
    }

    /**
     * The mesh of the model in {@code file}, catalogued as {@code hash}, or null while CPM has
     * not drawn it yet or cannot. Render thread only; cheap to call every frame.
     */
    public static ModelMesh mesh(String hash, Path file) {
        if (hash == null || file == null || !CpmModelPreview.isAvailable()) {
            return null;
        }
        Slot slot = SLOTS.get(hash);
        if (slot == null) {
            slot = new Slot();
            SLOTS.put(hash, slot);
            Iterator<String> eldest = SLOTS.keySet().iterator();
            while (SLOTS.size() > MAX_MESHES && eldest.hasNext()) {
                eldest.next();
                eldest.remove();
            }
        }
        if (slot.mesh != null) {
            return slot.mesh;
        }
        long now = System.nanoTime();
        if (slot.gaveUp) {
            if (now - slot.lastAttemptNanos < RETRY_AFTER_GIVING_UP_NANOS) {
                return null;
            }
            slot.gaveUp = false;
            slot.firstAttemptNanos = 0L;
        }
        if (slot.firstAttemptNanos != 0L && now - slot.lastAttemptNanos < ATTEMPT_INTERVAL_NANOS) {
            return null;
        }
        if (slot.firstAttemptNanos == 0L) {
            slot.firstAttemptNanos = now;
        }
        slot.lastAttemptNanos = now;
        try {
            slot.mesh = capture(file);
        } catch (RuntimeException | LinkageError error) {
            CpmModelPreview.unbind();
            slot.gaveUp = true;
            QuickSkinInfo.LOGGER.debug("Unable to capture the CPM model {}", file.getFileName(), error);
            return null;
        }
        if (slot.mesh == null && !CpmModelPreview.isLoading(file) && now - slot.firstAttemptNanos > GIVE_UP_NANOS) {
            slot.gaveUp = true;
        }
        return slot.mesh;
    }

    /** Forgets every captured mesh, as when the catalogue changes. */
    public static void clear() {
        SLOTS.clear();
    }

    /** Forgets one model's mesh, as when its file is replaced or deleted. */
    public static void forget(String hash) {
        SLOTS.remove(hash);
    }

    private static ModelMesh capture(Path file) {
        ensureModel();
        ModelMesh.Builder builder = new ModelMesh.Builder();
        List<Recorder> recorders = new ArrayList<>();
        //? if <1.21.9 {
        ResourceLocation[] skin = new ResourceLocation[1];
        MultiBufferSource source = renderType -> recorder(builder, recorders, renderType, skin[0]);
        ResourceLocation texture = CpmModelPreview.bind(
                file, captureModel, source, CpmModelPreview.Pose.STANDING, fallbackSkin());
        if (texture == null) {
            return null;
        }
        skin[0] = texture;
        try {
            Recorder body = new Recorder(builder, ModelMesh.Shading.TEXTURED);
            recorders.add(body);
            //? if <1.21 {
            captureModel.renderToBuffer(new PoseStack(), body, 15728880, OverlayTexture.NO_OVERLAY,
                    1.0F, 1.0F, 1.0F, 1.0F);
            //?} else {
            captureModel.renderToBuffer(new PoseStack(), body, 15728880, OverlayTexture.NO_OVERLAY, -1);
            //?}
        } finally {
            // CPM writes its glowing and plain-coloured parts when the model is handed back
            CpmModelPreview.unbind();
        }
        //?} else {
        //? if <1.21.11 {
        ResourceLocation texture = CpmModelPreview.bind(
                file, captureModel, CpmModelPreview.Pose.STANDING, fallbackSkin());
        //?} else {
        Identifier texture = CpmModelPreview.bind(
                file, captureModel, CpmModelPreview.Pose.STANDING, fallbackSkin());
        //?}
        if (texture == null) {
            return null;
        }
        List<Runnable> draws = new ArrayList<>();
        try {
            //? if <1.21.11 {
            RenderType skinType = RenderType.entityTranslucent(texture);
            Model.Simple model = new Model.Simple(captureModel.root(), RenderType::entityTranslucent);
            //?} else {
            RenderType skinType = RenderTypes.entityTranslucent(texture);
            Model.Simple model = new Model.Simple(captureModel.root(), RenderTypes::entityTranslucent);
            //?}
            CpmModelPreview.submit(collector(builder, recorders, draws, skinType, texture), model,
                    new PoseStack(), skinType, 15728880, OverlayTexture.NO_OVERLAY);
        } finally {
            CpmModelPreview.unbind();
        }
        for (Runnable draw : draws) {
            draw.run();
        }
        //?}
        for (Recorder recorder : recorders) {
            recorder.finish();
        }
        if (builder.quadCount() == 0) {
            return null;
        }
        int[][] pixels = new int[1][];
        int[] size = new int[2];
        readTexture(texture, pixels, size);
        ModelMesh mesh = builder.build(pixels[0], size[0], size[1]);
        if (mesh.textureWidth() == 0 && hasTexturedQuads(mesh)) {
            // A model that wears the player's skin has no texture of its own to draw a picture
            // with: its icon stays
            throw new IllegalStateException("the model's texture cannot be read");
        }
        return mesh;
    }

    private static boolean hasTexturedQuads(ModelMesh mesh) {
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            ModelMesh.Shading shading = mesh.shading(quad);
            if (shading == ModelMesh.Shading.TEXTURED || shading == ModelMesh.Shading.TEXTURED_UNLIT) {
                return true;
            }
        }
        return false;
    }

    /**
     * The texture CPM binds a model without one of its own to: such a model wears the player's
     * skin, and here any valid texture does, since the capture then has no pixels to draw with.
     */
    //? if <1.21 {
    private static ResourceLocation fallbackSkin() {
        return new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
    }
    //?} else if <1.21.11 {
    private static ResourceLocation fallbackSkin() {
        return ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
    }
    //?} else {
    private static Identifier fallbackSkin() {
        return Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
    }
    //?}

    private static void ensureModel() {
        if (captureModel != null) {
            return;
        }
        // Baked from vanilla's layer definition like the previews' own models, never posed
        ModelPart root = LayerDefinition.create(
                PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot();
        //? if <1.21.2 {
        captureModel = new PlayerModel<>(root, false);
        //?} else {
        captureModel = new PlayerModel(root, false);
        //?}
        // Before 1.21.2 a model draws itself as a baby until it is told the player is grown
        MinecraftCompat.INSTANCE.setYoung(captureModel, false);
    }

    /**
     * The recorder for a render type CPM asks for: the skin's own type is the textured body;
     * its glowing types are drawn at full brightness; a type that samples another texture
     * (CPM's plain white, for parts coloured by their vertices) keeps the vertex colour alone;
     * outlines are left out.
     */
    //? if <1.21.11 {
    private static VertexConsumer recorder(ModelMesh.Builder builder, List<Recorder> recorders,
                                           Object renderType, ResourceLocation skin) {
    //?} else {
    private static VertexConsumer recorder(ModelMesh.Builder builder, List<Recorder> recorders,
                                           Object renderType, Identifier skin) {
    //?}
        String name = String.valueOf(renderType).toLowerCase(Locale.ROOT);
        Recorder recorder;
        if (name.contains("lines")) {
            recorder = new Recorder(null, ModelMesh.Shading.COLOR);
        } else {
            boolean glowing = name.contains("eyes") || name.contains("glow");
            boolean skinTextured = skin != null && name.contains(skin.toString().toLowerCase(Locale.ROOT));
            ModelMesh.Shading shading = skinTextured
                    ? (glowing ? ModelMesh.Shading.TEXTURED_UNLIT : ModelMesh.Shading.TEXTURED)
                    : (glowing ? ModelMesh.Shading.COLOR_UNLIT : ModelMesh.Shading.COLOR);
            recorder = new Recorder(builder, shading);
        }
        recorders.add(recorder);
        return recorder;
    }

    //? if >=1.21.9 {
    /**
     * A collector that keeps each model CPM submits, to be drawn into a recorder once the submit
     * is over ({@code draws}). Its methods are told apart by their parameter types, which no
     * mapping renames.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    //? if <1.21.11 {
    private static OrderedSubmitNodeCollector collector(ModelMesh.Builder builder, List<Recorder> recorders,
                                                        List<Runnable> draws, RenderType skinType,
                                                        ResourceLocation skin) {
    //?} else {
    private static OrderedSubmitNodeCollector collector(ModelMesh.Builder builder, List<Recorder> recorders,
                                                        List<Runnable> draws, RenderType skinType,
                                                        Identifier skin) {
    //?}
        return (OrderedSubmitNodeCollector) java.lang.reflect.Proxy.newProxyInstance(
                SubmitNodeCollector.class.getClassLoader(), new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getParameterCount()) {
                            case 0 -> method.getReturnType() == int.class
                                    ? (Object) System.identityHashCode(proxy) : "QuickSkinCpmCapture";
                            default -> proxy == args[0];
                        };
                    }
                    if (method.isDefault()) {
                        return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    if (OrderedSubmitNodeCollector.class.isAssignableFrom(method.getReturnType())) {
                        return proxy;
                    }
                    Class<?>[] types = method.getParameterTypes();
                    if (args == null || types.length == 0 || args[0] == null) {
                        return null;
                    }
                    PoseStack poseStack = null;
                    Object renderType = null;
                    for (Object arg : args) {
                        if (arg instanceof PoseStack stack) {
                            poseStack = stack;
                        } else if (arg instanceof RenderType) {
                            renderType = arg;
                        }
                    }
                    if (poseStack == null) {
                        return null;
                    }
                    VertexConsumer consumer;
                    if (renderType == null || renderType == skinType) {
                        Recorder body = new Recorder(builder, ModelMesh.Shading.TEXTURED);
                        recorders.add(body);
                        consumer = body;
                    } else {
                        consumer = recorder(builder, recorders, renderType, skin);
                    }
                    // As a collector does, keep the pose of now and draw later: CPM submits a
                    // part before it has finished recording the part's geometry
                    PoseStack placed = new PoseStack();
                    placed.last().pose().set(poseStack.last().pose());
                    placed.last().normal().set(poseStack.last().normal());
                    Object first = args[0];
                    Object state = args.length > 1 ? args[1] : null;
                    if (Model.class.isAssignableFrom(types[0])) {
                        draws.add(() -> {
                            Model model = (Model) first;
                            model.setupAnim(state);
                            model.renderToBuffer(placed, consumer, 15728880, OverlayTexture.NO_OVERLAY, -1);
                        });
                    } else if (ModelPart.class.isAssignableFrom(types[0])) {
                        draws.add(() -> ((ModelPart) first).render(placed, consumer, 15728880,
                                OverlayTexture.NO_OVERLAY));
                    }
                    return null;
                });
    }
    //?}

    /** CPM's texture as ARGB pixels, read from the dynamic texture the game holds for it. */
    //? if <1.21.11 {
    private static void readTexture(ResourceLocation texture, int[][] pixels, int[] size) {
    //?} else {
    private static void readTexture(Identifier texture, int[][] pixels, int[] size) {
    //?}
        if (!(Minecraft.getInstance().getTextureManager().getTexture(texture) instanceof DynamicTexture dynamic)) {
            return;
        }
        NativeImage image = dynamic.getPixels();
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            return;
        }
        // A very large sheet is read every few texels: the pictures are small
        int step = 1;
        while ((long) (image.getWidth() / step) * (image.getHeight() / step) > MAX_TEXTURE_PIXELS) {
            step++;
        }
        int width = Math.max(1, image.getWidth() / step);
        int height = Math.max(1, image.getHeight() / step);
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // MinecraftCompat reads ABGR
                int abgr = MinecraftCompat.INSTANCE.getPixel(image, x * step, y * step);
                argb[y * width + x] = (abgr & 0xFF00FF00) | (abgr & 0xFF) << 16 | (abgr >> 16) & 0xFF;
            }
        }
        pixels[0] = argb;
        size[0] = width;
        size[1] = height;
    }

    /**
     * Keeps the quads a draw gives it, four vertices at a time, instead of drawing them. A vertex
     * is whole when the next one starts or the draw ends; each recorder hands over whole quads,
     * so recorders of different render types may be written to in turn.
     */
    private static final class Recorder implements VertexConsumer {
        private final ModelMesh.Builder builder;
        private final ModelMesh.Shading shading;
        private final float[] quad = new float[20];
        private final int[] colors = new int[4];
        private int vertex;
        private boolean open;

        /** {@code builder} is null for a recorder that drops what it is given. */
        Recorder(ModelMesh.Builder builder, ModelMesh.Shading shading) {
            this.builder = builder;
            this.shading = shading;
        }

        private void begin(float x, float y, float z) {
            close();
            open = true;
            quad[vertex * 5] = x;
            quad[vertex * 5 + 1] = y;
            quad[vertex * 5 + 2] = z;
            quad[vertex * 5 + 3] = 0.0F;
            quad[vertex * 5 + 4] = 0.0F;
            colors[vertex] = 0xFFFFFFFF;
        }

        private void close() {
            if (!open) {
                return;
            }
            open = false;
            vertex++;
            if (vertex < 4) {
                return;
            }
            vertex = 0;
            if (builder == null) {
                return;
            }
            for (int i = 0; i < 4; i++) {
                builder.vertex(quad[i * 5], quad[i * 5 + 1], quad[i * 5 + 2], quad[i * 5 + 3], quad[i * 5 + 4],
                        colors[i], shading);
            }
        }

        /** The draw ended: the last vertex is whole. */
        void finish() {
            close();
        }

        private void tint(int r, int g, int b, int a) {
            if (open) {
                colors[vertex] = (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
            }
        }

        private void texel(float u, float v) {
            if (open) {
                quad[vertex * 5 + 3] = u;
                quad[vertex * 5 + 4] = v;
            }
        }

//? if <1.21 {
        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            begin((float) x, (float) y, (float) z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            tint(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            texel(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
            close();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
//?} else {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            begin(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            tint(r, g, b, a);
            return this;
        }

//? if >=1.21.11 {
        @Override
        public VertexConsumer setColor(int color) {
            if (open) {
                colors[vertex] = color;
            }
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

//?}
//? if >=26.3 {
        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

//?}
        @Override
        public VertexConsumer setUv(float u, float v) {
            texel(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
//?}
    }
}
