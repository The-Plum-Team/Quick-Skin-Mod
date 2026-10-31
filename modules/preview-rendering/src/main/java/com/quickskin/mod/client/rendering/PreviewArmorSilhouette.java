package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.common.util.SafeImageReader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
//? if <1.21.9 {
import net.minecraft.client.renderer.MultiBufferSource;
//?} else {
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
//?}
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The exact outline of one armour piece on the skin menu's model (ADR 0017), whatever draws it:
 * vanilla armour, an elytra, a worn head or a mod's own armour model.
 *
 * <p>The piece's layers draw it once more, alone, into a sink instead of the screen: before 1.21.9
 * through a vertex consumer that keeps the quads it is given, from 1.21.9 through a collector
 * that poses each submitted model and draws it into that consumer. The quads arrive already in GUI
 * coordinates, since the draw starts from the transform the preview put its model on screen with.
 * They are filled pixel by pixel, keeping only the texels of their texture that the cutout armour
 * draw keeps, which gives the piece's silhouette; the enchantment glint and armour trims, which
 * only repaint the armour's own surface, are left out. The silhouette decides what the mouse is
 * over, and with the ring of pixels around it, in the order a walk round it meets them, gives the
 * see-through ghost of the hovered piece its tint and faint edge.
 */
@Environment(EnvType.CLIENT)
public final class PreviewArmorSilhouette {
    private static final Pattern TEXTURE = Pattern.compile("([a-z0-9_.-]+):([a-z0-9_./-]+\\.png)");
    private static final int MAX_SIDE = 2048;
    private static final int MAX_TEXTURES = 32;
    /** The alpha below which a cutout draw discards a texel. */
    private static final int MIN_ALPHA = 26;
    /** Not a piece: the player's own model, which can hide a piece behind it. */
    private static final int BODY = 0;
    /** Not a piece either: what the player holds, which can hide a piece behind it too. */
    private static final int HELD = 16;
    /** How far behind the model's surface, in GUI units of depth, a piece still counts as on top. */
    private static final float DEPTH_TOLERANCE = 0.01F;
    private static final Map<String, BufferedImage> TEXTURES = new LinkedHashMap<>(16, 0.75F, true);
    private static final BufferedImage OPAQUE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    /** Silhouettes of this frame's preview, by piece. */
    private static final Map<Integer, PreviewArmorSilhouette> FRAME = new HashMap<>();
    private static int frame = -1;
    private static boolean warned;

    private final int x0;
    private final int y0;
    private final int width;
    private final int height;
    private final BitSet mask;
    /** How near the viewer the piece is at each filled pixel; larger is nearer. */
    private final float[] depth;
    /** The ring round the silhouette, {x, y} pairs in walking order. */
    private final int[] ring;

    private PreviewArmorSilhouette(int x0, int y0, int width, int height, BitSet mask, float[] depth, int[] ring) {
        this.x0 = x0;
        this.y0 = y0;
        this.width = width;
        this.height = height;
        this.mask = mask;
        this.depth = depth;
        this.ring = ring;
    }

    /** How near the viewer the piece is at a GUI point, or NaN where it does not cover it. */
    float depthAt(double x, double y) {
        int px = (int) Math.floor(x) - x0;
        int py = (int) Math.floor(y) - y0;
        if (px < 0 || py < 0 || px >= width || py >= height || !mask.get(py * width + px)) {
            return Float.NaN;
        }
        return depth[py * width + px];
    }

    /**
     * The worn piece the viewer sees at a GUI point among {@code pieces} ({@code HiddenArmor}
     * bits, the player wearing each): the one nearest the viewer there, unless the player's own
     * model or what they hold is nearer, as the chest is for an elytra seen through it. 0 for none.
     */
    public static int pieceAt(double x, double y, int[] pieces) {
        int best = 0;
        float nearest = Float.NEGATIVE_INFINITY;
        for (int piece : pieces) {
            PreviewArmorSilhouette silhouette = of(piece);
            float z = silhouette == null ? Float.NaN : silhouette.depthAt(x, y);
            if (!Float.isNaN(z) && z > nearest) {
                nearest = z;
                best = piece;
            }
        }
        if (best == 0) {
            return 0;
        }
        // The armour sits outside the skin, so it wins ties within a fraction of a model pixel
        return nearer(of(BODY), x, y, nearest) || nearer(of(HELD), x, y, nearest) ? 0 : best;
    }

    /** Whether the player's own model, as the skin menu drew it this frame, covers a GUI point. */
    public static boolean bodyCovers(double x, double y) {
        PreviewArmorSilhouette body = of(BODY);
        return body != null && !Float.isNaN(body.depthAt(x, y));
    }

    /** Whether a GUI point lies on the piece or on its outline. */
    public boolean contains(double x, double y) {
        int px = (int) Math.floor(x) - x0;
        int py = (int) Math.floor(y) - y0;
        if (px < -1 || py < -1 || px > width || py > height) {
            return false;
        }
        if (px >= 0 && py >= 0 && px < width && py < height && mask.get(py * width + px)) {
            return true;
        }
        for (int i = 0; i < ring.length; i += 2) {
            if (ring[i] == px + x0 && ring[i + 1] == py + y0) {
                return true;
            }
        }
        return false;
    }

    /** The middle of the box round the piece, {x, y} in GUI coordinates. */
    public float[] center() {
        return new float[]{x0 + width / 2.0F, y0 + height / 2.0F};
    }

    /** The outline's pixels, {x, y} pairs in GUI coordinates, in the order a walk round it meets them. */
    public int[] ring() {
        return ring;
    }

    /**
     * What the viewer sees of a piece: its pixels as runs along rows ({x0, x1, y} triples in GUI
     * coordinates, x1 exclusive) and the outline pixels beside them ({x, y} pairs, in the order of
     * {@link #ring}).
     */
    public record Seen(int[] spans, int[] ring) {
    }

    /**
     * What the viewer sees of this piece on the skin menu's model: the pixels where neither the
     * model nor one of the pieces {@code drawn} on it ({@code HiddenArmor} bits) is nearer.
     */
    public Seen seen(int[] drawn) {
        PreviewArmorSilhouette body = of(BODY);
        List<PreviewArmorSilhouette> others = new ArrayList<>();
        PreviewArmorSilhouette held = of(HELD);
        if (held != null) {
            others.add(held);
        }
        for (int piece : drawn) {
            PreviewArmorSilhouette other = of(piece);
            if (other != null && other != this) {
                others.add(other);
            }
        }
        BitSet seen = new BitSet(width * height);
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
            double x = i % width + x0 + 0.5;
            double y = i / width + y0 + 0.5;
            boolean hidden = nearer(body, x, y, depth[i]);
            for (int o = 0; o < others.size() && !hidden; o++) {
                hidden = nearer(others.get(o), x, y, depth[i]);
            }
            if (!hidden) {
                seen.set(i);
            }
        }
        int[] spans = new int[seen.cardinality() * 3];
        int n = 0;
        for (int y = 0; y < height; y++) {
            int x = seen.nextSetBit(y * width);
            while (x >= 0 && x < (y + 1) * width) {
                int end = Math.min(seen.nextClearBit(x), (y + 1) * width);
                spans[n++] = x - y * width + x0;
                spans[n++] = end - y * width + x0;
                spans[n++] = y + y0;
                x = seen.nextSetBit(end);
            }
        }
        int[] edge = new int[ring.length];
        int e = 0;
        for (int i = 0; i < ring.length; i += 2) {
            int x = ring[i] - x0;
            int y = ring[i + 1] - y0;
            if (filled(seen, width, height, x - 1, y) || filled(seen, width, height, x + 1, y)
                    || filled(seen, width, height, x, y - 1) || filled(seen, width, height, x, y + 1)) {
                edge[e++] = ring[i];
                edge[e++] = ring[i + 1];
            }
        }
        return new Seen(java.util.Arrays.copyOf(spans, n), java.util.Arrays.copyOf(edge, e));
    }

    /** Whether {@code other} covers a GUI point nearer the viewer than depth {@code z}, past the tolerance. */
    private static boolean nearer(@Nullable PreviewArmorSilhouette other, double x, double y, float z) {
        if (other == null) {
            return false;
        }
        float otherZ = other.depthAt(x, y);
        return !Float.isNaN(otherZ) && otherZ > z + DEPTH_TOLERANCE;
    }

    /**
     * The silhouette of the piece ({@code HiddenArmor} bit) the live player wears, on the skin
     * menu's model as drawn this frame; null when there is no such model or nothing draws there.
     */
    @Nullable
    public static PreviewArmorSilhouette of(int piece) {
        int current = PreviewEquipment.outlineFrame();
        if (current != frame) {
            FRAME.clear();
            frame = current;
        }
        if (FRAME.containsKey(piece)) {
            return FRAME.get(piece);
        }
        PreviewArmorSilhouette silhouette = null;
        try {
            silhouette = capture(piece);
        } catch (RuntimeException | LinkageError error) {
            // A layer that cannot be drawn this way leaves the piece without an outline
            if (!warned) {
                warned = true;
                com.quickskin.mod.platform.QuickSkinInfo.LOGGER.warn("Could not trace an armour piece's outline", error);
            }
            silhouette = null;
        }
        FRAME.put(piece, silhouette);
        return silhouette;
    }

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PreviewArmorSilhouette capture(int piece) {
        Minecraft minecraft = Minecraft.getInstance();
        AbstractClientPlayer player = minecraft.player;
        PreviewEquipment.Placement placement = PreviewEquipment.placement();
        if (player == null || placement == null) {
            return null;
        }
        Object renderer = PreviewEquipment.playerRendererFor(minecraft, placement.slim());
        if (!(renderer instanceof PreviewEquipment.RendererLayers layers) || !(renderer instanceof LivingEntityRenderer)) {
            return null;
        }
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(placement.toGui());
        Sink sink = new Sink();
        if (piece == BODY) {
            // The player's own model as the preview drew it, opaque
            VertexConsumer consumer = sink.opaque();
//? if <1.21 {
            placement.model().renderToBuffer(poseStack, consumer, 15728880, 0, 1.0F, 1.0F, 1.0F, 1.0F);
//?} else {
            placement.model().renderToBuffer(poseStack, consumer, 15728880, 0, -1);
//?}
            return sink.silhouette();
        }
        Runnable giveBack = PreviewEquipment.poseRenderer(placement.model(), renderer);
        boolean held = piece == HELD;
        PreviewEquipment.setCapturing(true);
        try {
//? if <1.21.2 {
            if (CuriosPreview.isAccessory(piece)) {
                CuriosPreview.renderPiece(player, piece, renderer, poseStack, (MultiBufferSource) sink::buffer, 15728880);
                return sink.silhouette();
            }
            Runnable draw = () -> {
                for (Object layer : layers.quickskin$previewLayers()) {
                    if (held ? PreviewEquipment.drawsHeldItems(layer) : PreviewEquipment.drawsWornPiece(layer)) {
                        PreviewEquipment.alongBody(layer, placement.model(), poseStack, () -> ((RenderLayer) layer).render(
                                poseStack, sink::buffer, 15728880, player, 0.0F, 0.0F, 1.0F, player.tickCount, 0.0F, 0.0F));
                    }
                }
            };
            if (held) {
                draw.run();
            } else {
                // Cosmetic Armor Reworked's piece where the player wears one, hidden or not
                CosmeticArmorPreview.wearing(player, piece, () -> PlayerModelRenderer.withOnlyPiece(player, piece, draw));
            }
//?} else {
            Object state = CosmeticArmorPreview.wearing(player, held ? 0 : piece,
                    () -> ((LivingEntityRenderer) renderer).createRenderState(player, 1.0F));
            net.minecraft.client.renderer.entity.state.EntityRenderState renderState =
                    (net.minecraft.client.renderer.entity.state.EntityRenderState) state;
            PlayerModelRenderer.blankPieces(renderState, 15 & ~piece);
//? if <1.21.9 {
            for (Object layer : layers.quickskin$previewLayers()) {
                if (held ? PreviewEquipment.drawsHeldItems(layer) : PreviewEquipment.drawsWornPiece(layer)) {
                    PreviewEquipment.alongBody(layer, placement.model(), poseStack,
                            () -> ((RenderLayer) layer).render(poseStack, sink::buffer, 15728880, renderState, 0.0F, 0.0F));
                }
            }
//?} else {
            PreviewEquipment.bindPose(renderState, placement.model());
            SubmitNodeCollector collector = sink.collector();
            for (Object layer : layers.quickskin$previewLayers()) {
                if (held ? PreviewEquipment.drawsHeldItems(layer) : PreviewEquipment.drawsWornPiece(layer)) {
                    PreviewEquipment.alongBody(layer, placement.model(), poseStack,
                            () -> ((RenderLayer) layer).submit(poseStack, collector, 15728880, renderState, 0.0F, 0.0F));
                }
            }
//?}
//?}
        } finally {
            PreviewEquipment.setCapturing(false);
            giveBack.run();
        }
        return sink.silhouette();
    }

    /** Quads a capture drew, each {x, y, u, v} four times, with the texture they sample. */
    private static final class Sink {
        private final List<float[]> quads = new ArrayList<>();
        private final List<BufferedImage> textures = new ArrayList<>();

        VertexConsumer opaque() {
            return new Capture(this, OPAQUE);
        }

//? if <1.21.9 {
        VertexConsumer buffer(Object renderType) {
            return new Capture(this, texture(renderType));
        }
//?} else {
        VertexConsumer buffer(Object renderType) {
            return new Capture(this, texture(renderType));
        }

        /**
         * A collector that draws each submitted model at once into this sink. Its methods are told
         * apart by their parameter types, which no mapping renames.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        SubmitNodeCollector collector() {
            Object[] self = new Object[1];
            self[0] = java.lang.reflect.Proxy.newProxyInstance(
                    SubmitNodeCollector.class.getClassLoader(), new Class<?>[]{SubmitNodeCollector.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getParameterCount()) {
                                case 0 -> method.getReturnType() == int.class ? System.identityHashCode(proxy) : "QuickSkinArmourOutline";
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
                        PoseStack poseStack = find(args, PoseStack.class);
                        Object renderType = findRenderType(args);
                        if (poseStack == null || types.length == 0) {
                            return null;
                        }
                        if (Model.class.isAssignableFrom(types[0]) && args[0] != null) {
                            Model model = (Model) args[0];
                            model.setupAnim(args[1]);
                            model.renderToBuffer(poseStack, buffer(renderType), 15728880, OverlayTexture.NO_OVERLAY, -1);
                        } else if (ModelPart.class.isAssignableFrom(types[0]) && args[0] != null) {
                            ((ModelPart) args[0]).render(poseStack, buffer(renderType), 15728880, OverlayTexture.NO_OVERLAY);
                        } else if (java.util.Arrays.asList(types).contains(int[].class)) {
                            // An item: its quads, which take tint layers
                            List<?> quads = quadsOf(args);
                            if (quads != null) {
                                VertexConsumer consumer = buffer(renderType);
                                for (Object quad : quads) {
//? if <26.1 {
                                    consumer.putBulkData(poseStack.last(), (net.minecraft.client.renderer.block.model.BakedQuad) quad,
                                            1.0F, 1.0F, 1.0F, 1.0F, 15728880, OverlayTexture.NO_OVERLAY);
//?} else {
                                    consumer.putBakedQuad(poseStack.last(), (net.minecraft.client.resources.model.geometry.BakedQuad) quad,
                                            new com.mojang.blaze3d.vertex.QuadInstance());
//?}
                                }
                            }
                        } else {
                            SubmitNodeCollector.CustomGeometryRenderer geometry =
                                    find(args, SubmitNodeCollector.CustomGeometryRenderer.class);
                            if (geometry != null) {
                                geometry.render(poseStack.last(), buffer(renderType));
                            }
                        }
                        return null;
                    });
            return (SubmitNodeCollector) self[0];
        }

        /** The quads an item submission carries. */
        @Nullable
        private static List<?> quadsOf(Object[] args) {
            for (Object arg : args) {
//? if >=26.3 {
                if (arg instanceof net.minecraft.client.resources.model.geometry.ItemQuads quads) {
                    return quads.all();
                }
//?}
                if (arg instanceof List<?> list) {
                    return list;
                }
            }
            return null;
        }

        @Nullable
        private static <T> T find(Object[] args, Class<T> type) {
            if (args == null) {
                return null;
            }
            for (Object arg : args) {
                if (type.isInstance(arg)) {
                    return type.cast(arg);
                }
            }
            return null;
        }

        @Nullable
        private static Object findRenderType(Object[] args) {
            if (args == null) {
                return null;
            }
            for (Object arg : args) {
//? if <1.21.11 {
                if (arg instanceof net.minecraft.client.renderer.RenderType) {
//?} else {
                if (arg instanceof net.minecraft.client.renderer.rendertype.RenderType) {
//?}
                    return arg;
                }
            }
            return null;
        }
//?}

        void add(float[] quad, @Nullable BufferedImage texture) {
            if (quads.size() < 4096) {
                quads.add(quad);
                textures.add(texture);
            }
        }

        @Nullable
        PreviewArmorSilhouette silhouette() {
            if (quads.isEmpty()) {
                return null;
            }
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[] quad : quads) {
                for (int i = 0; i < 20; i += 5) {
                    minX = Math.min(minX, quad[i]);
                    maxX = Math.max(maxX, quad[i]);
                    minY = Math.min(minY, quad[i + 1]);
                    maxY = Math.max(maxY, quad[i + 1]);
                }
            }
            int x0 = (int) Math.floor(minX) - 1;
            int y0 = (int) Math.floor(minY) - 1;
            int width = (int) Math.ceil(maxX) - x0 + 2;
            int height = (int) Math.ceil(maxY) - y0 + 2;
            if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) {
                return null;
            }
            BitSet mask = new BitSet(width * height);
            float[] depth = new float[width * height];
            java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);
            for (int q = 0; q < quads.size(); q++) {
                float[] quad = quads.get(q);
                fill(mask, depth, x0, y0, width, height, quad, 0, 5, 10, textures.get(q));
                fill(mask, depth, x0, y0, width, height, quad, 0, 10, 15, textures.get(q));
            }
            if (mask.isEmpty()) {
                return null;
            }
            return new PreviewArmorSilhouette(x0, y0, width, height, mask, depth, ring(mask, x0, y0, width, height));
        }
    }

    /** Fills the pixels whose centres a triangle of a quad covers where its texture is kept. */
    private static void fill(BitSet mask, float[] depth, int x0, int y0, int width, int height, float[] quad,
                             int a, int b, int c, @Nullable BufferedImage texture) {
        float ax = quad[a], ay = quad[a + 1], bx = quad[b], by = quad[b + 1], cx = quad[c], cy = quad[c + 1];
        float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(area) < 1.0E-6F) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))) - x0);
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))) - x0);
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))) - y0);
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))) - y0);
        for (int py = minY; py <= maxY; py++) {
            float y = py + y0 + 0.5F;
            for (int px = minX; px <= maxX; px++) {
                float x = px + x0 + 0.5F;
                float wa = ((bx - x) * (cy - y) - (by - y) * (cx - x)) / area;
                float wb = ((cx - x) * (ay - y) - (cy - y) * (ax - x)) / area;
                float wc = 1.0F - wa - wb;
                if (wa < 0 || wb < 0 || wc < 0) {
                    continue;
                }
                if (texture != OPAQUE && texture != null) {
                    float u = wa * quad[a + 2] + wb * quad[b + 2] + wc * quad[c + 2];
                    float v = wa * quad[a + 3] + wb * quad[b + 3] + wc * quad[c + 3];
                    int tx = Math.min(texture.getWidth() - 1, Math.max(0, (int) Math.floor(u * texture.getWidth())));
                    int ty = Math.min(texture.getHeight() - 1, Math.max(0, (int) Math.floor(v * texture.getHeight())));
                    if ((texture.getRGB(tx, ty) >>> 24) < MIN_ALPHA) {
                        continue;
                    }
                }
                mask.set(py * width + px);
                float z = wa * quad[a + 4] + wb * quad[b + 4] + wc * quad[c + 4];
                if (z > depth[py * width + px]) {
                    depth[py * width + px] = z;
                }
            }
        }
    }

    /**
     * The pixels just outside the silhouette (beside a filled pixel, not diagonally), ordered by a
     * walk that always steps to a touching unvisited ring pixel and starts again elsewhere when
     * none is left.
     */
    private static int[] ring(BitSet mask, int x0, int y0, int width, int height) {
        BitSet ring = new BitSet(width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!mask.get(y * width + x) && (filled(mask, width, height, x - 1, y) || filled(mask, width, height, x + 1, y)
                        || filled(mask, width, height, x, y - 1) || filled(mask, width, height, x, y + 1))) {
                    ring.set(y * width + x);
                }
            }
        }
        int[] order = new int[ring.cardinality() * 2];
        int n = 0;
        BitSet left = (BitSet) ring.clone();
        int[][] steps = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {-1, -1}, {1, -1}};
        for (int start = left.nextSetBit(0); start >= 0; start = left.nextSetBit(0)) {
            int x = start % width;
            int y = start / width;
            while (true) {
                left.clear(y * width + x);
                order[n++] = x + x0;
                order[n++] = y + y0;
                boolean moved = false;
                for (int[] step : steps) {
                    int nx = x + step[0];
                    int ny = y + step[1];
                    if (nx >= 0 && ny >= 0 && nx < width && ny < height && left.get(ny * width + nx)) {
                        x = nx;
                        y = ny;
                        moved = true;
                        break;
                    }
                }
                if (!moved) {
                    break;
                }
            }
        }
        return order;
    }

    private static boolean filled(BitSet mask, int width, int height, int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height && mask.get(y * width + x);
    }

    /**
     * The texture a draw samples, read from the resources: null to leave the draw out (the glint
     * and the trims, which repaint the armour's own surface), {@link #OPAQUE} when it cannot be
     * read (an atlas, a texture made at run time), otherwise the image whose alpha decides.
     */
    @Nullable
    private static BufferedImage texture(@Nullable Object renderType) {
        if (renderType == null) {
            return OPAQUE;
        }
        String description = renderType.toString();
        if (description.contains("armor_trims")) {
            return null;
        }
        Matcher matcher = TEXTURE.matcher(description);
        if (!matcher.find()) {
            // Only the glint names no texture of its own; anything else may be shaped by it
            return description.contains("glint") ? null : OPAQUE;
        }
        // Armour drawn with its glint in one pass (26.3) still names its own texture first
        if (matcher.group(2).contains("enchanted_glint") || matcher.group(2).contains("armor_trims")) {
            return null;
        }
        if (matcher.group(2).startsWith("textures/atlas/")) {
            return OPAQUE;
        }
        String key = matcher.group(1) + ":" + matcher.group(2);
        synchronized (TEXTURES) {
            if (TEXTURES.containsKey(key)) {
                return TEXTURES.get(key);
            }
        }
        BufferedImage image = OPAQUE;
//? if <1.21.11 {
        ResourceLocation id = ResourceLocation.tryParse(key);
//?} else {
        Identifier id = Identifier.tryParse(key);
//?}
        if (id != null) {
            var resource = Minecraft.getInstance().getResourceManager().getResource(id);
            if (resource.isPresent()) {
                try (InputStream input = resource.get().open()) {
                    BufferedImage read = SafeImageReader.readPng(input);
                    if (read != null) {
                        image = read;
                    }
                } catch (Exception ignored) {
                    image = OPAQUE;
                }
            }
        }
        synchronized (TEXTURES) {
            TEXTURES.put(key, image);
            while (TEXTURES.size() > MAX_TEXTURES) {
                TEXTURES.remove(TEXTURES.keySet().iterator().next());
            }
        }
        return image;
    }

    /** Forgets the textures read, after a resource reload. */
    public static void clearTextures() {
        synchronized (TEXTURES) {
            TEXTURES.clear();
        }
    }

    /** Keeps the quads a draw gives it, four vertices at a time, instead of drawing them. */
    private static final class Capture implements VertexConsumer {
        private final Sink sink;
        @Nullable
        private final BufferedImage texture;
        private final boolean skip;
        private float[] quad = new float[20];
        private int vertex = -1;

        Capture(Sink sink, @Nullable BufferedImage texture) {
            this.sink = sink;
            this.texture = texture;
            this.skip = texture == null;
        }

        private void begin(float x, float y, float z) {
            vertex++;
            if (vertex == 4) {
                if (!skip) {
                    sink.add(quad, texture);
                }
                quad = new float[20];
                vertex = 0;
            }
            quad[vertex * 5] = x;
            quad[vertex * 5 + 1] = y;
            quad[vertex * 5 + 4] = z;
        }

        private void texel(float u, float v) {
            if (vertex >= 0) {
                quad[vertex * 5 + 2] = u;
                quad[vertex * 5 + 3] = v;
                if (vertex == 3 && !skip) {
                    sink.add(quad, texture);
                    quad = new float[20];
                    vertex = -1;
                }
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
            return this;
        }

//? if >=1.21.11 {
        @Override
        public VertexConsumer setColor(int color) {
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
