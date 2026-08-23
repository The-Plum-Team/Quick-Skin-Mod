package com.quickskin.mod.e2e;

import com.quickskin.mod.client.gui.util.CapeImportProcessor;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.KnownCapes;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.HashUtil;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates deterministic test textures at runtime so no binary assets need to be committed and the
 * file-upload flow can be exercised. Skins are written to a deterministic per-run fixture and handed to
 * {@code SkinImporter.importSkin(Path)}; capes are registered headlessly via
 * {@link #registerLocalCape(Path)} (bypassing the interactive {@code CapeAdjustScreen}).
 */
public final class TestAssets {

    private TestAssets() {}

    /** Classpath location of an optional real skin bundled into the e2e resources (see makeClassicSkin). */
    private static final String BUNDLED_SKIN = "/qs_e2e_test_skin.png";

    /** The production BMO atlas used to prove that the editor preserves bundled cape UVs. */
    private static final String BUNDLED_BMO_CAPE =
            "/assets/quickskin/textures/capes/bmo.png";

    public static final int BMO_CAPE_WIDTH = 64;
    public static final int BMO_CAPE_HEIGHT = 32;
    public static final int BMO_PADDED_WIDTH = BMO_CAPE_WIDTH * 2;
    public static final int BMO_PADDED_HEIGHT = BMO_CAPE_HEIGHT * 2;
    public static final int BMO_PADDED_X = (BMO_PADDED_WIDTH - BMO_CAPE_WIDTH) / 2;
    public static final int BMO_PADDED_Y = (BMO_PADDED_HEIGHT - BMO_CAPE_HEIGHT) / 2;

    /** Read cap for hashing an installed cape; comfortably above any cape this harness writes. */
    private static final int MAX_CAPE_BYTES = 8 * 1024 * 1024;

    /**
     * The skin used by every scenario. Prefers a real skin bundled at {@link #BUNDLED_SKIN} (a 64x64
     * PNG dropped into {@code common/src/e2e/resources/}) so screenshots show a realistic player; if
     * that resource is absent it falls back to a synthetic, unmistakable magenta skin (opaque
     * everywhere -> auto-detection resolves to classic). Either way the scenarios derive the content
     * hash dynamically from {@code SkinImporter.importSkin}, so all assertions stay valid.
     */
    public static Path makeClassicSkin() throws Exception {
        try (InputStream in = TestAssets.class.getResourceAsStream(BUNDLED_SKIN)) {
            if (in != null) {
                Path tmp = deterministicFixture("qs_e2e_skin_fixture.png");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                E2ELog.info("using bundled real skin " + BUNDLED_SKIN);
                return tmp;
            }
        } catch (Exception e) {
            E2ELog.warn("bundled skin load failed, using synthetic magenta: " + e);
        }
        return makeSyntheticMagentaSkin();
    }

    /** The original synthetic skin: flat magenta + cyan face patch + yellow stripe (fallback). */
    private static Path makeSyntheticMagentaSkin() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Base: a flat, unmistakable magenta so the player stands out against the flat world.
        g.setColor(new Color(0xCC, 0x22, 0x99));
        g.fillRect(0, 0, 64, 64);
        // Head front (8,8..16,16) painted a contrasting cyan as a visual landmark.
        g.setColor(new Color(0x22, 0xCC, 0xCC));
        g.fillRect(8, 8, 8, 8);
        // A yellow stripe across the body for orientation in screenshots.
        g.setColor(new Color(0xEE, 0xDD, 0x22));
        g.fillRect(0, 20, 64, 4);
        g.dispose();
        Path tmp = deterministicFixture("qs_e2e_skin_fixture.png");
        ImageIO.write(img, "png", tmp.toFile());
        return tmp;
    }

    /**
     * A second, deliberately <em>different</em> valid 64x64 skin, for the scenario that copies a file
     * straight into the uploads folder while a menu is open. Its content hash must not collide with
     * {@link #makeClassicSkin()}, or "the menu noticed the new file" could not be told apart from
     * "the menu already had that skin".
     */
    public static Path makeDistinctSkin() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Opaque everywhere so auto-detection resolves to classic, like the primary test skin.
        g.setColor(new Color(0x22, 0x88, 0x44));
        g.fillRect(0, 0, 64, 64);
        g.setColor(new Color(0xFF, 0x99, 0x11));
        g.fillRect(8, 8, 8, 8);
        g.setColor(new Color(0x11, 0x22, 0xEE));
        g.fillRect(0, 40, 64, 6);
        g.dispose();
        Path tmp = deterministicFixture("qs_e2e_external_skin.png");
        ImageIO.write(img, "png", tmp.toFile());
        return tmp;
    }

    /**
     * A normal skin whose six second-layer UV islands are transparent. It is a featureless Ears
     * control and can also serve as a 3D Skin Layers control with no overlay voxels to extrude.
     */
    public static Path makeFlatOverlaySkin() throws Exception {
        BufferedImage image = bundledSkinCopy();
        clearModernSkinOverlay(image);
        Path fixture = deterministicFixture("qs_e2e_flat_overlay_skin.png");
        ImageIO.write(image, "png", fixture.toFile());
        return fixture;
    }

    /**
     * A deliberately subdued but non-empty second layer on all six body parts. It is the control
     * for 3D Skin Layers: every mesh must exist, while the dark gray voxels remain visually easy
     * to distinguish from the saturated paired fixture.
     */
    public static Path makeSubtleOverlaySkin() throws Exception {
        BufferedImage image = bundledSkinCopy();
        clearModernSkinOverlay(image);
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        paintSparseChecker(graphics, 40, 8, 8, 8, 0xFF30343B, 0xFF4A505A);
        paintSparseChecker(graphics, 20, 36, 8, 12, 0xFF30343B, 0xFF4A505A);
        paintSparseChecker(graphics, 44, 36, 4, 12, 0xFF30343B, 0xFF4A505A);
        paintSparseChecker(graphics, 52, 52, 4, 12, 0xFF30343B, 0xFF4A505A);
        paintSparseChecker(graphics, 4, 36, 4, 12, 0xFF30343B, 0xFF4A505A);
        paintSparseChecker(graphics, 4, 52, 4, 12, 0xFF30343B, 0xFF4A505A);
        graphics.dispose();
        Path fixture = deterministicFixture("qs_e2e_subtle_overlay_skin.png");
        ImageIO.write(image, "png", fixture.toFile());
        return fixture;
    }

    /**
     * The same control skin with loud, sparse pixels on every visible second-layer face. A correct
     * 3D Skin Layers bridge turns these pixels into visibly raised voxels in Quick Skin's own menu
     * preview; a flat fallback remains easy to distinguish in the paired capture.
     */
    public static Path makeRaisedOverlaySkin() throws Exception {
        BufferedImage image = bundledSkinCopy();
        clearModernSkinOverlay(image);
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        paintSparseChecker(graphics, 40, 8, 8, 8, 0xFF00E5FF, 0xFFFF2BD6);   // hat front
        paintSparseChecker(graphics, 20, 36, 8, 12, 0xFF7CFF00, 0xFFFF7A00); // jacket front
        paintSparseChecker(graphics, 44, 36, 4, 12, 0xFF00E5FF, 0xFFFF2BD6); // right sleeve
        paintSparseChecker(graphics, 52, 52, 4, 12, 0xFF7CFF00, 0xFFFF7A00); // left sleeve
        paintSparseChecker(graphics, 4, 36, 4, 12, 0xFFFF2BD6, 0xFF00E5FF);  // right trousers
        paintSparseChecker(graphics, 4, 52, 4, 12, 0xFFFF7A00, 0xFF7CFF00);  // left trousers
        graphics.dispose();
        Path fixture = deterministicFixture("qs_e2e_raised_overlay_skin.png");
        ImageIO.write(image, "png", fixture.toFile());
        return fixture;
    }

    /**
     * Build a real Ears v1 skin with Ears' own public builder and writer from the installed JAR.
     * Reflection keeps the optional mod out of the harness compile classpath while still making
     * upstream code author the magic pixels that Quick Skin later parses.
     */
    public static Path makeEarsSkin() throws Exception {
        BufferedImage image = bundledSkinCopy();
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        // Saturated landmarks make the skin and every feature face legible at the fixed rear
        // E2E camera. Ears' TALL front faces sample 24..39,0..7, their rear faces sample
        // 56..63,28..43, and a one-segment BACK tail samples 56..63,16..27. Painting only the
        // vanilla head/torso leaves the rear-facing feature UVs transparent and creates a false
        // runtime pass with no visible geometry.
        paintChecker(graphics, 0, 0, 32, 16, 0xFF00D9FF, 0xFFFF30C8);
        paintChecker(graphics, 16, 16, 24, 16, 0xFFFFA000, 0xFF5CFF3A);
        paintChecker(graphics, 24, 0, 16, 8, 0xFFFFF200, 0xFFFF3B30);
        paintChecker(graphics, 56, 28, 8, 16, 0xFFFFF200, 0xFFFF3B30);
        paintChecker(graphics, 56, 16, 8, 12, 0xFF9D4EDD, 0xFF00F5D4);
        graphics.dispose();

        ClassLoader loader = TestAssets.class.getClassLoader();
        Class<?> featuresClass = Class.forName(
                "com.unascribed.ears.api.features.EarsFeatures", true, loader);
        Object builder = featuresClass.getMethod("builder").invoke(null);
        invokeEnumBuilder(builder, "earMode",
                "com.unascribed.ears.api.features.EarsFeatures$EarMode", "TALL");
        invokeEnumBuilder(builder, "earAnchor",
                "com.unascribed.ears.api.features.EarsFeatures$EarAnchor", "CENTER");
        invokeEnumBuilder(builder, "tailMode",
                "com.unascribed.ears.api.features.EarsFeatures$TailMode", "BACK");
        invokeEnumBuilder(builder, "wingMode",
                "com.unascribed.ears.api.features.EarsFeatures$WingMode", "NONE");
        builder.getClass().getMethod("tailSegments", int.class).invoke(builder, 1);
        builder.getClass().getMethod("tailBend0", float.class).invoke(builder, 35.0f);
        Object features = builder.getClass().getMethod("build").invoke(builder);

        int[] pixels = image.getRGB(0, 0, 64, 64, null, 0, 64);
        Class<?> rawImageClass = Class.forName(
                "com.unascribed.ears.common.RawEarsImage", true, loader);
        Object rawImage = rawImageClass
                .getConstructor(int[].class, int.class, int.class, boolean.class)
                .newInstance(pixels, 64, 64, false);
        Class<?> writableImageClass = Class.forName(
                "com.unascribed.ears.common.WritableEarsImage", true, loader);
        Class<?> writerClass = Class.forName(
                "com.unascribed.ears.common.EarsFeaturesWriterV1", true, loader);
        writerClass.getMethod("write", featuresClass, writableImageClass)
                .invoke(null, features, rawImage);
        image.setRGB(0, 0, 64, 64, pixels, 0, 64);
        requireOpaqueRegion(image, 24, 0, 16, 8, "TALL ear front");
        requireOpaqueRegion(image, 56, 28, 8, 16, "TALL ear back");
        requireOpaqueRegion(image, 56, 16, 8, 12, "BACK tail");

        Path fixture = deterministicFixture("qs_e2e_ears_skin.png");
        ImageIO.write(image, "png", fixture.toFile());
        return fixture;
    }

    /**
     * Export a genuine standalone CPM model through the editor/exporter API supplied by the exact
     * installed CPM version. Two oversized recoloured head cubes make model selection visually
     * undeniable while the outer file still exercises Quick Skin's normal .cpmmodel importer.
     */
    public static Path makeCpmModel() throws Exception {
        ClassLoader loader = TestAssets.class.getClassLoader();
        Class<?> uiClass = Class.forName("com.tom.cpl.gui.UI", true, loader);
        Object ui = Proxy.newProxyInstance(loader, new Class<?>[]{uiClass}, cpmUiHandler());
        Class<?> editorClass = Class.forName("com.tom.cpm.shared.editor.Editor", true, loader);
        Object editor = editorClass.getConstructor().newInstance();
        editorClass.getMethod("setUI", uiClass).invoke(editor, ui);
        editorClass.getMethod("loadDefaultPlayerModel").invoke(editor);

        @SuppressWarnings("unchecked")
        List<Object> roots = (List<Object>) editorClass.getField("elements").get(editor);
        Object head = roots.stream()
                .filter(root -> {
                    try {
                        Object typeData = root.getClass().getField("typeData").get(root);
                        return typeData instanceof Enum<?> value && "HEAD".equals(value.name());
                    } catch (ReflectiveOperationException ignored) {
                        return false;
                    }
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("CPM editor exposed no HEAD root"));
        addCpmCube(editorClass, editor, head, "E2E cyan horn",
                -4.0f, -13.0f, -1.5f, 3.0f, 6.0f, 3.0f, 0x00E5FF);
        addCpmCube(editorClass, editor, head, "E2E magenta horn",
                1.0f, -13.0f, -1.5f, 3.0f, 6.0f, 3.0f, 0xFF2BD6);

        Class<?> descriptionClass = Class.forName(
                "com.tom.cpm.shared.editor.util.ModelDescription", true, loader);
        Object description = descriptionClass.getConstructor().newInstance();
        descriptionClass.getField("name").set(description, "Quick Skin E2E horns");
        descriptionClass.getField("desc").set(
                description, "Generated by the compatibility scenario through CPM's exporter");
        Path fixture = deterministicFixture("qs_e2e_horns.cpmmodel");
        Class<?> exporterClass = Class.forName(
                "com.tom.cpm.shared.editor.Exporter", true, loader);
        exporterClass.getMethod(
                        "exportModel", editorClass, uiClass, java.io.File.class,
                        descriptionClass, boolean.class)
                .invoke(null, editor, ui, fixture.toFile(), description, false);
        if (!Files.isRegularFile(fixture) || Files.size(fixture) < 16) {
            throw new IllegalStateException("CPM exporter did not create a model file");
        }
        try (InputStream input = Files.newInputStream(fixture)) {
            if (input.read() != 0x53) {
                throw new IllegalStateException("CPM exporter wrote an invalid model header");
            }
        }
        return fixture;
    }

    private static BufferedImage bundledSkinCopy() throws Exception {
        BufferedImage source = ImageIO.read(makeClassicSkin().toFile());
        if (source == null || source.getWidth() != 64 || source.getHeight() != 64) {
            throw new IllegalStateException("E2E skin fixture must be a 64x64 PNG");
        }
        return toArgb(source);
    }

    private static void clearModernSkinOverlay(BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Clear);
        graphics.fillRect(32, 0, 32, 16);  // head overlay
        graphics.fillRect(0, 32, 56, 16);  // right leg, body and right arm overlays
        graphics.fillRect(0, 48, 16, 16);  // left leg overlay
        graphics.fillRect(48, 48, 16, 16); // left arm overlay
        graphics.dispose();
    }

    private static void paintChecker(
            Graphics2D graphics, int x, int y, int width, int height, int first, int second) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                graphics.setColor(new Color(((row + column) & 1) == 0 ? first : second, true));
                graphics.fillRect(x + column, y + row, 1, 1);
            }
        }
    }

    /**
     * Paint isolated opaque overlay pixels with transparent gaps between them. A fully opaque
     * checker hides the ordinary skin beneath the outer layer and can look like a broken texture
     * even when 3D Skin Layers rendered it correctly. The gaps keep the intact base model visible
     * while the two colours still make the raised voxels unmistakable.
     */
    private static void paintSparseChecker(
            Graphics2D graphics, int x, int y, int width, int height, int first, int second) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int phase = (row + column) & 3;
                int colour = phase == 0 ? first : phase == 2 ? second : 0x00000000;
                graphics.setColor(new Color(colour, true));
                graphics.fillRect(x + column, y + row, 1, 1);
            }
        }
    }

    private static void requireOpaqueRegion(
            BufferedImage image, int x, int y, int width, int height, String label) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                if (((image.getRGB(x + column, y + row) >>> 24) & 0xFF) != 0xFF) {
                    throw new IllegalStateException(label + " contains transparent feature pixels");
                }
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void invokeEnumBuilder(
            Object builder, String methodName, String enumClassName, String valueName)
            throws Exception {
        Class<?> enumClass = Class.forName(enumClassName, true, TestAssets.class.getClassLoader());
        Object value = Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), valueName);
        builder.getClass().getMethod(methodName, enumClass).invoke(builder, value);
    }

    @SuppressWarnings("unchecked")
    private static void addCpmCube(
            Class<?> editorClass,
            Object editor,
            Object parent,
            String name,
            float offsetX,
            float offsetY,
            float offsetZ,
            float sizeX,
            float sizeY,
            float sizeZ,
            int rgb
    ) throws Exception {
        ClassLoader loader = TestAssets.class.getClassLoader();
        Class<?> elementClass = Class.forName(
                "com.tom.cpm.shared.editor.elements.ModelElement", true, loader);
        Class<?> vectorClass = Class.forName("com.tom.cpl.math.Vec3f", true, loader);
        Object child = elementClass.getConstructor(editorClass).newInstance(editor);
        elementClass.getField("name").set(child, name);
        elementClass.getField("parent").set(child, parent);
        elementClass.getField("texture").setBoolean(child, false);
        elementClass.getField("rgb").setInt(child, rgb);
        elementClass.getField("offset").set(
                child, vectorClass.getConstructor(float.class, float.class, float.class)
                        .newInstance(offsetX, offsetY, offsetZ));
        elementClass.getField("pos").set(
                child, vectorClass.getConstructor(float.class, float.class, float.class)
                        .newInstance(0.0f, 0.0f, 0.0f));
        elementClass.getField("size").set(
                child, vectorClass.getConstructor(float.class, float.class, float.class)
                        .newInstance(sizeX, sizeY, sizeZ));
        elementClass.getField("rotation").set(
                child, vectorClass.getConstructor(float.class, float.class, float.class)
                        .newInstance(0.0f, 0.0f, 0.0f));
        elementClass.getField("scale").set(
                child, vectorClass.getConstructor(float.class, float.class, float.class)
                        .newInstance(1.0f, 1.0f, 1.0f));
        elementClass.getField("meshScale").set(
                child, vectorClass.getConstructor(float.class, float.class, float.class)
                        .newInstance(1.0f, 1.0f, 1.0f));
        List<Object> children = (List<Object>) parent.getClass().getField("children").get(parent);
        children.add(child);
    }

    private static InvocationHandler cpmUiHandler() {
        return (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "QuickSkinE2ECpmUI";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> null;
                };
            }
            if ("i18nFormat".equals(method.getName())) {
                return args != null && args.length > 0 ? String.valueOf(args[0]) : "";
            }
            if ("executeLater".equals(method.getName())
                    && args != null && args.length == 1 && args[0] instanceof Runnable runnable) {
                runnable.run();
                return null;
            }
            if ("onGuiException".equals(method.getName())) {
                Throwable failure = args != null && args.length > 1 && args[1] instanceof Throwable t
                        ? t : new IllegalStateException("CPM exporter reported an unknown failure");
                throw new IllegalStateException("CPM exporter reported a GUI exception", failure);
            }
            return null;
        };
    }

    /**
     * A valid 64x32 opaque cape {@link BufferedImage} (the vanilla cape format; a 2:1 frame ratio so
     * {@code LocalAssetManager} accepts it). Distinctive colors so the cape is obvious in screenshots.
     * Exposed as an image so it can be fed directly to {@code CapeAdjustScreen}.
     */
    public static BufferedImage makeClassicCapeImage() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Base: deep blue covering the whole atlas (incl. the elytra region) so the import does NOT
        // composite the vanilla elytra under it (which would re-encode and change the hash).
        g.setColor(new Color(0x22, 0x33, 0xAA));
        g.fillRect(0, 0, 64, 32);
        // The visible cape "front" is the (1,1)-(10,16) region. Paint it a bright orange landmark.
        g.setColor(new Color(0xEE, 0x88, 0x11));
        g.fillRect(1, 1, 10, 16);
        // A green stripe across the front for orientation.
        g.setColor(new Color(0x22, 0xCC, 0x44));
        g.fillRect(1, 5, 10, 3);
        g.dispose();
        return img;
    }

    /**
     * A 64x32 cape painted one saturated magenta, for the cape-editor elytra probe.
     *
     * <p>Flat on purpose: the probe counts how many of the previewed cape's pixels survive on the
     * model once an elytra is equipped, so the cape has to be a single colour that nothing else on
     * that screen can produce - not the skin, not the vanilla elytra, not the GUI chrome, not the
     * blurred world behind it. Opaque over the whole atlas for the same reason
     * {@link #makeClassicCapeImage()} is: so the import does not composite anything underneath.
     */
    public static BufferedImage makeProbeCapeImage() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xFF, 0x00, 0xFF));
        g.fillRect(0, 0, 64, 32);
        g.dispose();
        return img;
    }

    /** {@link #makeClassicCapeImage()} written to its deterministic E2E PNG file. */
    public static Path makeClassicCape() throws Exception {
        Path tmp = deterministicFixture("qs_e2e_cape.png");
        ImageIO.write(makeClassicCapeImage(), "png", tmp.toFile());
        return tmp;
    }

    /**
     * The exact production BMO cape atlas, loaded from the production mod resource rather than a
     * copied E2E fixture. A changed or missing bundled asset therefore fails the parity scenario
     * instead of silently comparing the editor with a stale duplicate.
     */
    public static BufferedImage makeBundledBmoCapeImage() throws Exception {
        // Forge isolates the E2E automation mod from the production mod's classpath resources.
        // Minecraft's resource manager is the loader-independent source of the texture that the
        // real renderer sees, so this also catches a missing or shadowed shipped asset.
        var texture = KnownCapes.BMO.getTextureLocation();
        var resource = Minecraft.getInstance().getResourceManager().getResource(texture)
                .orElseThrow(() -> new IllegalStateException(
                        "missing bundled BMO cape " + BUNDLED_BMO_CAPE));
        try (InputStream in = resource.open()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null
                    || image.getWidth() != BMO_CAPE_WIDTH
                    || image.getHeight() != BMO_CAPE_HEIGHT) {
                String dimensions = image == null
                        ? "undecodable"
                        : image.getWidth() + "x" + image.getHeight();
                throw new IllegalStateException("bundled BMO cape is " + dimensions
                        + ", expected " + BMO_CAPE_WIDTH + "x" + BMO_CAPE_HEIGHT);
            }
            return toArgb(image);
        }
    }

    /**
     * A deliberately awkward import source: a 128x64 opaque-black canvas with the unchanged BMO
     * 64x32 atlas centred inside it. Pixels inside the BMO rectangle are copied with setRGB so its
     * transparent elytra silhouette remains transparent; only the surrounding padding is black.
     * Zooming this source from its reset 50% fit to 100% about the cape centre must land exactly on
     * the embedded atlas at offset (-32,-16).
     */
    public static BufferedImage makePaddedBmoCapeSourceImage() throws Exception {
        BufferedImage bmo = makeBundledBmoCapeImage();
        BufferedImage padded = new BufferedImage(
                BMO_PADDED_WIDTH, BMO_PADDED_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = padded.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, padded.getWidth(), padded.getHeight());
        graphics.dispose();
        for (int y = 0; y < BMO_CAPE_HEIGHT; y++) {
            for (int x = 0; x < BMO_CAPE_WIDTH; x++) {
                padded.setRGB(BMO_PADDED_X + x, BMO_PADDED_Y + y, bmo.getRGB(x, y));
            }
        }
        return padded;
    }

    /** {@link #makePaddedBmoCapeSourceImage()} written as the non-standard PNG import source. */
    public static Path makePaddedBmoCapeSource() throws Exception {
        Path tmp = deterministicFixture("qs_e2e_bmo_padded.png");
        if (!ImageIO.write(makePaddedBmoCapeSourceImage(), "png", tmp.toFile())) {
            throw new IllegalStateException("no PNG writer for padded BMO cape source");
        }
        return tmp;
    }

    /**
     * A second static 64x32 cape whose visible front is deliberately unlike
     * {@link #makeClassicCapeImage()} - near-white base, red front, black bars instead of deep
     * blue/orange/green - so a screenshot pair can prove the rendered cape actually changed.
     */
    public static BufferedImage makeContrastCapeImage() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Opaque over the whole atlas (incl. the elytra region) for the same reason as the classic one.
        g.setColor(new Color(0xEE, 0xEE, 0xF2));
        g.fillRect(0, 0, 64, 32);
        // The visible cape "front" is the (1,1)-(10,16) region.
        g.setColor(new Color(0xCC, 0x11, 0x22));
        g.fillRect(1, 1, 10, 16);
        g.setColor(new Color(0x11, 0x11, 0x11));
        g.fillRect(1, 3, 10, 3);
        g.fillRect(1, 11, 10, 3);
        g.dispose();
        return img;
    }

    /** X of the fully transparent window {@link #makeTransparentCapeImage()} punches out. */
    public static final int TRANSPARENT_WINDOW_X = 2;
    /** Y of the fully transparent window {@link #makeTransparentCapeImage()} punches out. */
    public static final int TRANSPARENT_WINDOW_Y = 3;
    /**
     * An <em>opaque</em> pixel of {@link #makeTransparentCapeImage()}, inside the visible face and
     * outside the transparent window. The opaque fill must leave it exactly alone, so asserting on
     * it is what stops a fill that painted over the whole cape from passing.
     */
    public static final int OPAQUE_LANDMARK_X = 1;
    /** Y of {@link #OPAQUE_LANDMARK_X}. */
    public static final int OPAQUE_LANDMARK_Y = 1;
    /** The exact ARGB {@link #OPAQUE_LANDMARK_X} must still carry after the fill. */
    public static final int OPAQUE_LANDMARK_ARGB = 0xFFEECC22;

    /**
     * A 64x32 cape whose visible face carries a large <b>fully transparent</b> window, unlike every
     * other cape here. The opaque-fill E2E steps need it: with the toggle off the window renders as
     * a hole, with the toggle on it renders as the chosen fill, and that difference is what the
     * screenshot pair proves. The window sits at ({@link #TRANSPARENT_WINDOW_X},
     * {@link #TRANSPARENT_WINDOW_Y}) inside the (1,1)-(11,17) cape face, well clear of the margins
     * the adjust screen clears, so it survives compositing at 1:1.
     */
    public static BufferedImage makeTransparentCapeImage() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Overwrite rather than blend, so painting with a zero-alpha colour really clears pixels.
        g.setComposite(AlphaComposite.Src);
        // Opaque teal base over the whole atlas so only the punched window is transparent.
        g.setColor(new Color(0x11, 0x88, 0x99));
        g.fillRect(0, 0, 64, 32);
        // A bright border inside the visible face, so the hole reads as a hole and not as a crop.
        g.setColor(new Color(0xEE, 0xCC, 0x22));
        g.fillRect(1, 1, 10, 16);
        // The hole itself: fully transparent, 8x12, inside the visible face.
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(TRANSPARENT_WINDOW_X, TRANSPARENT_WINDOW_Y, 8, 12);
        g.dispose();
        return img;
    }

    /**
     * A two-frame 64x64 animation strip of {@link #makeTransparentCapeImage()}, the second frame
     * shifted so the frames are not identical. Used to check that the opaque fill reaches every
     * frame of a stacked atlas, not just the first.
     */
    public static BufferedImage makeTransparentAnimatedCapeImage() {
        BufferedImage frame = makeTransparentCapeImage();
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(frame, 0, 0, null);
        g.drawImage(frame, 0, 32, null);
        // Make frame 1 differ from frame 0 without touching the transparent window.
        g.setColor(new Color(0xCC, 0x22, 0x22));
        g.fillRect(12, 32 + 1, 9, 16);
        g.dispose();
        return img;
    }

    /** {@link #makeContrastCapeImage()} written to its deterministic E2E PNG file. */
    public static Path makeContrastCape() throws Exception {
        Path tmp = deterministicFixture("qs_e2e_cape_contrast.png");
        ImageIO.write(makeContrastCapeImage(), "png", tmp.toFile());
        return tmp;
    }

    /**
     * A deliberately fully opaque <b>256x128</b> cape PNG. 256x128 is exactly
     * {@code SkinResolution.CAPE_256}
     * (and {@code height % (width/2) == 128 % 128 == 0}, a single static frame), so
     * {@code LocalAssetManager.processPngAsset} keeps it verbatim instead of resizing — letting the
     * "HD cape import preserves source resolution (no downscale)" property be asserted on the metadata.
     */
    public static Path makeHdCape() throws Exception {
        final int w = 256, h = 128;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Deep teal over the whole atlas, including the Elytra cutout. This deliberately malformed
        // input proves that local presentation restores the tapered silhouette without rewriting
        // the content-addressed source.
        g.setColor(new Color(0x11, 0x77, 0x88));
        g.fillRect(0, 0, w, h);
        // The visible cape front scales 4x from the 64x32 layout: (4,4)-(40,64). Bright magenta landmark.
        g.setColor(new Color(0xDD, 0x22, 0xAA));
        g.fillRect(4, 4, 36, 60);
        // A yellow stripe across the front for orientation in screenshots.
        g.setColor(new Color(0xEE, 0xDD, 0x22));
        g.fillRect(4, 20, 36, 10);
        g.dispose();
        Path tmp = deterministicFixture("qs_e2e_cape_hd.png");
        ImageIO.write(img, "png", tmp.toFile());
        return tmp;
    }

    /** Classpath location of the real image the zoom steps adjust. */
    private static final String BUNDLED_ZOOM_SOURCE = "/qs_e2e_zoom_source.png";

    /** The zoom source's dimensions, which several zoom assertions are stated in terms of. */
    public static final int ZOOM_SOURCE_W = 320;
    /** @see #ZOOM_SOURCE_W */
    public static final int ZOOM_SOURCE_H = 180;

    /**
     * The image the zoom steps hand to {@code CapeAdjustScreen}: a real 320&times;180 PNG bundled at
     * {@link #BUNDLED_ZOOM_SOURCE}.
     *
     * <p>Its shape is the point. {@code CapeImportProcessor} marks a 64&times;32 image as the
     * standard format and saves it directly, so in production the adjust screen is only ever opened
     * on an image that is <em>not</em> 64&times;32 &mdash; which is exactly what this is. Being
     * 16:9 rather than the cape's 2:1 also separates the contain fit from the cover fit, so the
     * zoom's legal range is the general case rather than the degenerate one where they coincide;
     * and being larger than 256&times;128 leaves the first three target resolutions selectable, so
     * a scenario can switch resolution and check the zoom rides through it.
     *
     * <p>The content is chosen for the same reason: coarse shapes that stay legible zoomed out, and
     * single-pixel stars and ground lines that only resolve zoomed in, so a screenshot pair shows
     * the cape being rescaled rather than a panel being repainted.
     *
     * <p>Falls back to a synthetic image of the same dimensions if the resource is missing, matching
     * {@link #makeClassicSkin()}; every zoom assertion is written against the dimensions, not
     * against particular pixels, so the scenario stays valid either way.
     */
    public static BufferedImage makeZoomSourceImage() {
        try (InputStream in = TestAssets.class.getResourceAsStream(BUNDLED_ZOOM_SOURCE)) {
            if (in != null) {
                BufferedImage bundled = ImageIO.read(in);
                if (bundled != null
                        && bundled.getWidth() == ZOOM_SOURCE_W
                        && bundled.getHeight() == ZOOM_SOURCE_H) {
                    E2ELog.info("using bundled real zoom source " + BUNDLED_ZOOM_SOURCE);
                    return toArgb(bundled);
                }
                E2ELog.warn("bundled zoom source has unexpected dimensions, using synthetic");
            }
        } catch (Exception e) {
            E2ELog.warn("bundled zoom source load failed, using synthetic: " + e);
        }
        return makeSyntheticZoomSourceImage();
    }

    /** Same dimensions and the same coarse/fine detail mix, drawn rather than loaded. */
    private static BufferedImage makeSyntheticZoomSourceImage() {
        BufferedImage img = new BufferedImage(
                ZOOM_SOURCE_W, ZOOM_SOURCE_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x14, 0x1A, 0x48));
        g.fillRect(0, 0, ZOOM_SOURCE_W, ZOOM_SOURCE_H);
        g.setColor(new Color(0xFF, 0xE2, 0x8C));
        g.fillOval(202, 66, 60, 60);
        g.setColor(new Color(0x26, 0x22, 0x42));
        g.fillPolygon(new int[] {0, 96, 196, 320, 320, 0},
                new int[] {180, 86, 152, 112, 180, 180}, 6);
        // Single-pixel detail, the part that only resolves when zoomed in.
        g.setColor(Color.WHITE);
        for (int i = 0; i < 240; i++) {
            g.fillRect((i * 97) % ZOOM_SOURCE_W, (i * 41) % (ZOOM_SOURCE_H / 2), 1, 1);
        }
        g.setColor(new Color(0xFF, 0x5A, 0x28));
        g.drawRect(0, 0, ZOOM_SOURCE_W - 1, ZOOM_SOURCE_H - 1);
        g.dispose();
        return img;
    }

    /** {@code ImageIO} may hand back any type; the screen's composers expect INT_ARGB. */
    private static BufferedImage toArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage copy = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    /** Classpath location of an optional real animated GIF cape bundled into the e2e resources. */
    private static final String BUNDLED_CAPE_GIF = "/qs_e2e_test_cape.gif";

    /** Exact geometry of every frame in {@link #BUNDLED_CAPE_GIF}. */
    public static final int ANIMATED_CAPE_FRAME_WIDTH = 64;
    public static final int ANIMATED_CAPE_FRAME_HEIGHT = 32;

    /**
     * Validate that the E2E GIF itself is a cape atlas rather than merely an arbitrary animation.
     *
     * <p>The previous fixture used 640x640 square frames. The local-catalog shortcut accepted those
     * bytes without opening the adjustment UI, so Minecraft sampled only a tiny UV corner: the
     * nominal circle became a clipped sliver at the bottom of the rendered cape. Keep this check at
     * the fixture boundary so a visually unsuitable replacement cannot produce convincing logical
     * animation assertions again.</p>
     */
    public static CapeImportProcessor.PreparedCape prepareBundledGifCape() throws Exception {
        Path gif = makeGifCape();
        if (gif == null) return null;
        CapeImportProcessor.PreparedCape prepared = CapeImportProcessor.prepare(gif);
        if (!prepared.gif()
                || !prepared.standardFormat()
                || prepared.atlas().getWidth() != ANIMATED_CAPE_FRAME_WIDTH
                || prepared.atlas().getHeight()
                        != ANIMATED_CAPE_FRAME_HEIGHT * prepared.frameCount()
                || prepared.frameCount() < 2
                || prepared.animationMetadata() == null
                || prepared.animationMetadata().frameCount() != prepared.frameCount()) {
            throw new IllegalStateException(
                    "bundled animated cape must contain at least two 64x32 GIF frames");
        }
        return prepared;
    }

    /**
     * Extract the optional bundled animated GIF cape (dropped into {@code common/src/e2e/resources/})
     * to a temp {@code .gif} file, or {@code null} if none is bundled.
     */
    public static Path makeGifCape() throws Exception {
        try (InputStream in = TestAssets.class.getResourceAsStream(BUNDLED_CAPE_GIF)) {
            if (in == null) return null;
            Path tmp = deterministicFixture("qs_e2e_cape.gif");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    /**
     * Register the bundled GIF as a LOCAL <b>animated</b> cape headlessly. {@code LocalAssetManager}
     * decodes the GIF (via {@code StbGifLoader}) into a vertical frame atlas + {@code AnimationMetadata}
     * keyed by the GIF file's content hash, so this returns the same {@code "local_cape:" + hash} id the
     * rest of the harness uses. Returns {@code null} if no GIF is bundled or registration failed.
     */
    public static String registerBundledGifCape() throws Exception {
        CapeImportProcessor.PreparedCape prepared = prepareBundledGifCape();
        if (prepared == null) {
            E2ELog.warn("no bundled GIF cape at " + BUNDLED_CAPE_GIF);
            return null;
        }
        return registerLocalCapeAs(prepared.source(), "qs_e2e_cape.gif");
    }

    /**
     * Persist an atlas produced by {@code CapeAdjustScreen} through the same adjusted-import
     * boundary used by {@code CapeImportWorkflow}, then return its catalogued local-cape id.
     *
     * <p>The parity source carries BMO's non-empty elytra UVs, so production's optional vanilla
     * elytra fallback is provably inapplicable. Passing {@code null} here keeps this shared harness
     * independent of version-specific Minecraft resource identifiers without changing the bytes
     * that the real workflow would save.</p>
     */
    public static String registerAdjustedCape(
            CapeImportProcessor.PreparedCape prepared,
            BufferedImage adjusted
    ) throws Exception {
        LocalAssetManager mgr = LocalAssetManager.getInstance();
        Path capesDir = mgr.getCapesDirectory();
        Path metadataDir = mgr.getCacheDirectory();
        if (capesDir == null || metadataDir == null) {
            E2ELog.warn("local cape/cache directories are not initialized");
            return null;
        }
        if (CapeImportProcessor.isElytraAreaTransparent(adjusted)) {
            throw new IllegalStateException(
                    "adjusted BMO atlas lost its elytra UVs before persistence");
        }

        Set<String> before = new HashSet<>();
        for (AssetMetadata meta : mgr.getAssetsByType("cape")) {
            before.add(meta.hash());
        }

        Path target = CapeImportProcessor.saveAdjusted(
                prepared, adjusted, capesDir, metadataDir, null);
        mgr.reload();
        String hash = HashUtil.computeAssetHash(
                BoundedFileReader.readBytes(target, MAX_CAPE_BYTES), "cape");
        if (hash != null && mgr.getMetadata(hash) != null) {
            return hash;
        }
        for (AssetMetadata meta : mgr.getAssetsByType("cape")) {
            if (!before.contains(meta.hash())) {
                return meta.hash();
            }
        }
        E2ELog.warn("adjusted cape was not catalogued after save: " + target);
        return null;
    }

    /**
     * Register a cape PNG as a LOCAL cape headlessly: copy the exact bytes into
     * {@code LocalAssetManager}'s capes directory, reload the asset index, and return the SHA-1 content
     * hash (usable as {@code "local_cape:" + hash}). Copying verbatim (rather than going through the
     * interactive {@code PlayerCapeMenuScreen.processDroppedFile}/{@code CapeAdjustScreen}) keeps the
     * on-disk bytes — and therefore the hash — fully deterministic.
     *
     * @return the SHA-1 hex hash, or {@code null} if registration failed (cape not discovered).
     */
    public static String registerLocalCape(Path capePng) throws Exception {
        return registerLocalCapeAs(capePng, "qs_e2e_cape.png");
    }

    /**
     * As {@link #registerLocalCape(Path)} but with an explicit on-disk filename, so multiple distinct
     * capes (e.g. a standard and an HD one) can coexist in the capes directory with different hashes.
     */
    public static String registerLocalCapeAs(Path capePng, String filename) throws Exception {
        LocalAssetManager mgr = LocalAssetManager.getInstance();
        Path capesDir = mgr.getCapesDirectory();
        if (capesDir == null) {
            E2ELog.warn("LocalAssetManager.getCapesDirectory() is null (not initialized yet)");
            return null;
        }
        Files.createDirectories(capesDir);

        Set<String> before = new HashSet<>();
        for (AssetMetadata meta : mgr.getAssetsByType("cape")) {
            before.add(meta.hash());
        }

        Path target = capesDir.resolve(filename);
        Files.copy(capePng, target, StandardCopyOption.REPLACE_EXISTING);
        mgr.reload();

        // Capes are catalogued under the domain-separated cape hash, not the plain file SHA-1
        // (LocalAssetManager.processPngAsset), and the import may rewrite the file in place - so
        // hash the bytes as they are AFTER the reload.
        String hash = HashUtil.computeAssetHash(
                BoundedFileReader.readBytes(target, MAX_CAPE_BYTES), "cape");
        if (hash != null && mgr.getMetadata(hash) != null) {
            return hash;
        }

        // Fallback: whatever entry the catalogue gained is this cape, whichever name it kept.
        for (AssetMetadata meta : mgr.getAssetsByType("cape")) {
            if (!before.contains(meta.hash())) {
                return meta.hash();
            }
        }
        E2ELog.warn("cape not discovered after reload (file=" + filename + " hash=" + hash + ")");
        return null;
    }

    /**
     * Return one stable filename below the disposable packaged-runtime profile.
     *
     * <p>The imported filename is product-visible catalog text, so a random temporary suffix makes
     * otherwise identical screenshots differ. The orchestrator gives every client a fresh game
     * directory; replacing a bounded named fixture there is deterministic and cannot collide with
     * another lane.</p>
     */
    private static Path deterministicFixture(String filename) throws Exception {
        if (filename == null || !filename.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("unsafe E2E fixture filename: " + filename);
        }
        Path runDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path fixtureDirectory = runDirectory.resolve("e2e-fixtures");
        if (Files.isSymbolicLink(fixtureDirectory)) {
            throw new IllegalStateException("E2E fixture directory must not be a symbolic link");
        }
        Files.createDirectories(fixtureDirectory);
        Path fixture = fixtureDirectory.resolve(filename).normalize();
        if (!fixture.getParent().equals(fixtureDirectory)) {
            throw new IllegalArgumentException("E2E fixture escapes its directory: " + filename);
        }
        Files.deleteIfExists(fixture);
        return fixture;
    }
}
