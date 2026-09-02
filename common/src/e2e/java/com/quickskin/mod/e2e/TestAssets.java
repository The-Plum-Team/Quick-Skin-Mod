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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Creates deterministic test textures and copies protected test fixtures into per-run paths. Skins are
 * handed to {@code SkinImporter.importSkin(Path)}; capes are registered headlessly via
 * {@link #registerLocalCape(Path)} (bypassing the interactive {@code CapeAdjustScreen}).
 */
public final class TestAssets {

    private TestAssets() {}

    /** Classpath location of an optional real skin bundled into the e2e resources (see makeClassicSkin). */
    private static final String BUNDLED_SKIN = "/qs_e2e_test_skin.png";

    /** Environment variable pointing to the protected complex CPM compatibility fixture. */
    private static final String CPM_MODEL_PATH_ENV = "QUICKSKIN_E2E_CPM_MODEL_PATH";

    /** Exact public identity of the reviewed fixture; its bytes are deliberately not versioned. */
    private static final String CPM_MODEL_CONTENT_ID =
            "sha256-2acd67e358456caf86aa0fad54f88b2e2fe0dfd2bc1160638b6f69b1689e1845";

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
     * A byte-distinct copy of the normal plaid skin for ReplayMod acknowledgement evidence.
     * Pixel {@code 0,0} is outside every player-model UV island, so the imported content gets a
     * fresh hash without changing the appearance promised by the compatibility contract.
     */
    public static Path makeReplayAcknowledgedSkin() throws Exception {
        BufferedImage image = bundledSkinCopy();
        int marker = image.getRGB(0, 0) == 0xFF010203 ? 0xFF040506 : 0xFF010203;
        image.setRGB(0, 0, marker);
        Path fixture = deterministicFixture("qs_e2e_replay_ack_skin.png");
        ImageIO.write(image, "png", fixture.toFile());
        return fixture;
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
     * for 3D Skin Layers: every mesh must exist, while each muted voxel colour remains tied to the
     * uniquely coloured body part beneath it.
     */
    public static Path makeSubtleOverlaySkin() throws Exception {
        BufferedImage image = anatomical3DLayerBase();
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        paintSparseChecker(graphics, 40, 8, 8, 8, 0xFF7FA9DF, 0xFF9ABBE6);   // head
        paintSparseChecker(graphics, 20, 36, 8, 12, 0xFF70B88E, 0xFF8BC8A3); // torso
        paintSparseChecker(graphics, 44, 36, 4, 12, 0xFFD17B6D, 0xFFE29A8E); // right arm
        paintSparseChecker(graphics, 52, 52, 4, 12, 0xFFA383CD, 0xFFB99CDB); // left arm
        paintSparseChecker(graphics, 4, 36, 4, 12, 0xFFD1AE60, 0xFFE1C47E);  // right leg
        paintSparseChecker(graphics, 4, 52, 4, 12, 0xFF68B7BA, 0xFF86C9CB);  // left leg
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
        BufferedImage image = anatomical3DLayerBase();
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        paintSparseChecker(graphics, 40, 8, 8, 8, 0xFF00B7FF, 0xFF6BDDFF);   // head
        paintSparseChecker(graphics, 20, 36, 8, 12, 0xFF64FF5A, 0xFFB6FF56); // torso
        paintSparseChecker(graphics, 44, 36, 4, 12, 0xFFFF3B30, 0xFFFF8A3D); // right arm
        paintSparseChecker(graphics, 52, 52, 4, 12, 0xFFB642FF, 0xFFFF4FD8); // left arm
        paintSparseChecker(graphics, 4, 36, 4, 12, 0xFFFFE600, 0xFFFFAE00);  // right leg
        paintSparseChecker(graphics, 4, 52, 4, 12, 0xFF00F5D4, 0xFF00B8FF);  // left leg
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
        // The bundled skin has opaque jacket, sleeve and trouser overlays. Keeping those pixels
        // makes a correctly applied Ears fixture look half-updated because the old outer layer
        // hides the new limb bases. Start from transparent overlays so every authored base island
        // is visible and the screenshot proves one coherent skin transition.
        clearModernSkinOverlay(image);
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        // Saturated landmarks cover every vanilla base island as well as every feature face. This
        // makes stale or partially applied skins visually undeniable at the fixed rear E2E camera.
        paintChecker(graphics, 0, 0, 32, 16, 0xFF00D9FF, 0xFFFF30C8);
        paintChecker(graphics, 16, 16, 24, 16, 0xFFFFA000, 0xFF5CFF3A);
        paintChecker(graphics, 40, 16, 16, 16, 0xFF2D6BFF, 0xFFFF30C8); // right arm
        paintChecker(graphics, 32, 48, 16, 16, 0xFF2D6BFF, 0xFFFF30C8); // left arm
        paintChecker(graphics, 0, 16, 16, 16, 0xFF7CFF00, 0xFFFF7A00); // right leg
        paintChecker(graphics, 16, 48, 16, 16, 0xFF7CFF00, 0xFFFF7A00); // left leg
        // Ears' TALL front faces sample 24..39,0..7, their rear faces sample 56..63,28..43,
        // and a one-segment BACK tail samples 56..63,16..27.
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

    /** Copies the protected complex standalone CPM model into Quick Skin's normal import workflow. */
    public static Path makeCpmModel() throws Exception {
        String configuredPath = System.getenv(CPM_MODEL_PATH_ENV);
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing required CPM model path in " + CPM_MODEL_PATH_ENV);
        }
        Path source = Paths.get(configuredPath);
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("Configured CPM fixture is not a regular file");
        }
        String contentId = HashUtil.computeFileContentId(source);
        if (!CPM_MODEL_CONTENT_ID.equals(contentId)) {
            throw new IllegalStateException(
                    "Configured CPM fixture has unexpected content identity " + contentId);
        }

        Path fixture = deterministicFixture("qs_e2e_complex_model.cpmmodel");
        Files.copy(source, fixture, StandardCopyOption.REPLACE_EXISTING);
        if (!Files.isRegularFile(fixture) || Files.size(fixture) < 16) {
            throw new IllegalStateException("Protected CPM fixture did not create a model file");
        }
        try (InputStream input = Files.newInputStream(fixture)) {
            if (input.read() != 0x53) {
                throw new IllegalStateException("Protected CPM fixture has an invalid model header");
            }
        }
        E2ELog.info("using protected complex CPM fixture " + CPM_MODEL_CONTENT_ID);
        return fixture;
    }

    private static BufferedImage bundledSkinCopy() throws Exception {
        BufferedImage source = ImageIO.read(makeClassicSkin().toFile());
        if (source == null || source.getWidth() != 64 || source.getHeight() != 64) {
            throw new IllegalStateException("E2E skin fixture must be a 64x64 PNG");
        }
        return toArgb(source);
    }

    /**
     * Gives every body part a bright, unique base colour before 3D overlay pixels are added. The
     * ordinary bundled skin is nearly black and disappears against Quick Skin's black menu, which
     * made detached meshes impossible for either a person or the visual reviewer to diagnose.
     */
    private static BufferedImage anatomical3DLayerBase() throws Exception {
        BufferedImage image = bundledSkinCopy();
        clearModernSkinOverlay(image);
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        paintChecker(graphics, 0, 0, 32, 16, 0xFF245D99, 0xFF337BC4);   // head
        paintChecker(graphics, 16, 16, 24, 16, 0xFF2D7048, 0xFF3C925E); // torso
        paintChecker(graphics, 40, 16, 16, 16, 0xFF9A4437, 0xFFC05A48); // right arm
        paintChecker(graphics, 32, 48, 16, 16, 0xFF654092, 0xFF8355B7); // left arm
        paintChecker(graphics, 0, 16, 16, 16, 0xFF8A6C22, 0xFFB18B2D);  // right leg
        paintChecker(graphics, 16, 48, 16, 16, 0xFF237579, 0xFF30999F); // left leg
        graphics.dispose();
        return image;
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

    /** Exactly {@code SkinResolution.HD_256}, so the importer keeps it verbatim instead of resizing. */
    public static final int HD_SKIN_SIZE = 256;

    /** The 1x scale factor of {@link #HD_SKIN_SIZE}, used to place landmarks on the HD grid. */
    public static final int HD_SKIN_SCALE = HD_SKIN_SIZE / 64;

    /**
     * A deliberately fully opaque <b>256x256</b> skin. 256x256 is exactly
     * {@code SkinResolution.HD_256}, so {@code SkinImporter.importSkin} keeps the source resolution
     * instead of snapping it to the nearest valid size — letting "HD skin import preserves source
     * resolution (no downscale)" be asserted on the metadata. Opaque everywhere so the model stays
     * classic and this checkpoint isolates resolution from {@link #makeTransparentLayerSkin()}.
     */
    public static Path makeHdSkin() throws Exception {
        final int size = HD_SKIN_SIZE;
        final int s = HD_SKIN_SCALE;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Src);
        // Deep indigo base so the player reads as one solid custom skin at any distance.
        g.setColor(new Color(0x2A, 0x2F, 0x8A));
        g.fillRect(0, 0, size, size);
        // Clear every outer layer. Minecraft always draws that second layer slightly inflated over
        // the base, so leaving it opaque encases the model and hides every base-layer landmark.
        g.setColor(new Color(0, 0, 0, 0));
        for (int[] overlay : new int[][] {
            {32, 0, 32, 16},   // head
            {16, 32, 24, 16},  // body
            {40, 32, 16, 16},  // right arm
            {0, 32, 16, 16},   // right leg
            {48, 48, 16, 16},  // left arm
            {0, 48, 16, 16},   // left leg
        }) {
            g.fillRect(overlay[0] * s, overlay[1] * s, overlay[2] * s, overlay[3] * s);
        }
        // Head front (8,8)-(16,16) at 1x: bright orange. Not visible from the rear evidence camera,
        // kept only so the fixture is a plausible skin from every angle.
        g.setColor(new Color(0xF0, 0x8A, 0x1E));
        g.fillRect(8 * s, 8 * s, 8 * s, 8 * s);
        // Cyan band on the torso back (32,20)-(40,24) at 1x, the primary rear landmark. Confined
        // to that one face so no unexplained mark appears elsewhere on the model.
        g.setColor(new Color(0x22, 0xCC, 0xCC));
        g.fillRect(32 * s, 20 * s, 8 * s, 4 * s);
        // A fine checker on the torso back (32,24)-(40,32) at 1x, drawn in 2px cells at 4x scale.
        // Half a pixel at the standard resolution, so any downscale destroys it: this is the
        // landmark that proves the HD source really survived import.
        g.setColor(new Color(0x10, 0x10, 0x18));
        for (int y = 0; y < 8 * s; y += 4) {
            for (int x = 0; x < 8 * s; x += 4) {
                g.fillRect(32 * s + x, 24 * s + y, 2, 2);
                g.fillRect(32 * s + x + 2, 24 * s + y + 2, 2, 2);
            }
        }
        g.dispose();
        Path tmp = deterministicFixture("qs_e2e_skin_hd.png");
        ImageIO.write(img, "png", tmp.toFile());
        return tmp;
    }

    /**
     * A 64x64 skin whose <b>outer</b> layer is genuinely transparent and whose outer arm columns are
     * empty, so it exercises the two skin paths every other fixture deliberately avoids:
     * <ul>
     *   <li>{@code SkinModelDetector} reads the empty arm columns and auto-detects <b>slim</b>.</li>
     *   <li>The hat overlay keeps real alpha, so a flattened import would wrap the head in an opaque
     *       block instead of showing a narrow brim over the base face.</li>
     * </ul>
     */
    public static Path makeTransparentLayerSkin() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Src);
        // Opaque base everywhere: the body must stay solid even where the overlay is empty.
        g.setColor(new Color(0x1E, 0x7A, 0x3C));
        g.fillRect(0, 0, 64, 64);
        // Base head front, a warm skin tone that must remain visible through the empty hat layer.
        g.setColor(new Color(0xE8, 0xB4, 0x8A));
        g.fillRect(8, 8, 8, 8);
        // Clear the whole head overlay (32-63, 0-15), then paint a narrow magenta brim across all
        // four side faces (x 32-63, y 13-15) so it is visible from any camera angle. A flattened
        // import would fill this whole region with opaque black and encase the head in a block.
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(32, 0, 32, 16);
        g.setColor(new Color(0xCC, 0x22, 0x99));
        g.fillRect(32, 13, 32, 3);
        // Empty the outer arm columns the detector samples, which is what makes this skin slim.
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(54, 20, 2, 12);
        g.fillRect(46, 52, 2, 12);
        g.dispose();
        Path tmp = deterministicFixture("qs_e2e_skin_transparent.png");
        ImageIO.write(img, "png", tmp.toFile());
        return tmp;
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
