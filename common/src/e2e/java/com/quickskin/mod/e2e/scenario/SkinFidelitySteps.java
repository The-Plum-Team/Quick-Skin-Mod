package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.gui.panel.SkinListPanel;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.screen.SettingsScreen;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.gui.widget.SkinListWidget;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.ModelService;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.common.util.SkinModelDetector;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.DefaultSkinEvidenceView;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Skin-fidelity block of the {@code full} scenario, inserted directly after {@code model_classic}.
 *
 * <p>Every checkpoint imports a purpose-built fixture through the production
 * {@link SkinImporter}, applies it through {@link PlayerAppearanceService}, and then reads the
 * decoded bytes {@link LocalAssetManager#loadTexture} actually registers for the renderer, so each
 * assertion records pixel facts about what the screenshot shows rather than the harness's intent:
 * slim auto-detection, legacy 64x32 conversion, HD 128x128 at native resolution, the HD catalog
 * label, folder-drop normalization, base-layer transparency in third and first person, the real
 * Disable Skin Transparency checkbox, and the return to the plaid classic skin.</p>
 */
final class SkinFidelitySteps {

    /** Colour {@code SkinEntry} draws the model label with for an HD entry. */
    static final int HD_LABEL_COLOR = 0xFF55FF55;
    /** Colour {@code SkinEntry} draws the model label with for a standard entry. */
    static final int STANDARD_LABEL_COLOR = 0xFFAAAAAA;
    /** The exact label text {@code SkinEntry} renders for a classic 128x128 skin. */
    static final String HD_CLASSIC_LABEL = "Classic \u2022 HD_128";

    private static final String ODD_SKIN_FILE = "qs_e2e_odd96.png";
    private static final String ODD_CAPE_FILE = "qs_e2e_cape_odd96.png";
    private static final String BAD_RATIO_CAPE_FILE = "qs_e2e_cape_badratio.png";

    private final FullScenario owner;

    volatile String slimSkinHash;
    volatile String legacySkinHash;
    volatile String hdSkinHash;
    volatile String transparentSkinHash;

    private volatile Path oddSkinTarget;
    private volatile Path oddCapeTarget;
    private volatile Path badRatioTarget;
    private volatile String normalizationSetupFailure;

    /** 0 = settings screen opening, 1 = closed and persisted, 2 = rear view held. */
    private final AtomicInteger disablePhase = new AtomicInteger();
    private volatile boolean transparencyCheckboxPressed;
    private volatile String disableFailure;

    SkinFidelitySteps(FullScenario owner) {
        this.owner = owner;
    }

    /** Steps inserted right after {@code model_classic}, in this exact order. */
    List<Step> build(Minecraft mc, UUID uuid, PlayerAppearanceService svc, String prefix, String suffix) {
        List<Step> steps = new ArrayList<>();

        // 3c. slim auto-detect --------------------------------------------------------------------
        steps.add(Step.of("slim_skin_auto_detect")
                .action(() -> {
                    owner.prepareModelEvidenceView(mc);
                    slimSkinHash = null;
                    try {
                        AssetMetadata meta = SkinImporter.importSkin(TestAssets.makeSlimSkin());
                        if (meta == null) {
                            E2ELog.warn("slim skin import returned null");
                            return;
                        }
                        slimSkinHash = meta.hash();
                        svc.applySkin(uuid, "local_skin:" + slimSkinHash, "auto");
                        E2ELog.info("applied slim fixture local_skin:" + slimSkinHash + " model=auto");
                    } catch (Exception e) {
                        E2ELog.error("slim_skin_auto_detect action failed", e);
                    }
                })
                .minTicks(30)
                .ready(() -> owner.holdModelEvidenceView(mc, "slim")
                        && slimSkinHash != null
                        && svc.getSkinLocation(uuid) != null)
                .settleTicks(12)
                .timeoutTicks(300)
                .screenshot(prefix + "full_03c_slim_auto" + suffix)
                .assertion(() -> {
                    if (slimSkinHash == null) return Step.Result.fail("slim skin import failed (no hash)");
                    AssetMetadata meta = LocalAssetManager.getInstance().getMetadata(slimSkinHash);
                    if (meta == null) return Step.Result.fail("slim skin is not catalogued: " + slimSkinHash);
                    if (!"slim".equals(meta.skinModel())) {
                        return Step.Result.fail("catalog skinModel=" + meta.skinModel() + " expected slim");
                    }
                    BufferedImage decoded = decodeFull(slimSkinHash);
                    if (decoded == null) return Step.Result.fail("loadTexture(FULL) returned no PNG");
                    String detected = SkinModelDetector.detectSkinModel(decoded);
                    if (!"slim".equals(detected)) {
                        return Step.Result.fail("SkinModelDetector on registered bytes=" + detected);
                    }
                    String opaque = firstNonTransparent(decoded, 54, 20, 2, 12);
                    if (opaque == null) opaque = firstNonTransparent(decoded, 46, 52, 2, 12);
                    if (opaque != null) {
                        return Step.Result.fail("slim detector column is not transparent at " + opaque);
                    }

                    PlayerAppearance app = svc.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no appearance");
                    String expectedId = "local_skin:" + slimSkinHash;
                    if (!expectedId.equals(app.getSkinId())) {
                        return Step.Result.fail("skinId=" + app.getSkinId() + " expected " + expectedId);
                    }
                    String override = ModelService.getInstance().getModelOverride(uuid);
                    if (!"auto".equals(override)) {
                        return Step.Result.fail("model override=" + override + " expected auto");
                    }
                    if (!"slim".equals(app.getModel()) || !"slim".equals(svc.getModelName(uuid))) {
                        return Step.Result.fail("auto did not resolve to slim: stored=" + app.getModel()
                                + " modelName=" + svc.getModelName(uuid));
                    }
                    String rendered = VanillaShim.playerModel(mc.player);
                    if (!"slim".equals(rendered)) {
                        return Step.Result.fail("renderer model=" + rendered + " expected slim");
                    }
                    String fov = checkEvidenceFov(mc);
                    if (fov != null) return Step.Result.fail(fov);
                    String route = rendererRouteMismatch(mc, svc, uuid);
                    if (route != null) return Step.Result.fail(route);
                    return Step.Result.pass("local_skin:" + slimSkinHash + " requested model=auto; catalog "
                            + "skinModel=slim, detector on registered 64x64 bytes=slim (columns x54-55/y20-31 "
                            + "and x46-47/y52-63 all alpha 0), stored model=slim, renderer model=slim, "
                            + "close rear FOV=" + FullScenario.MODEL_EVIDENCE_FOV + "; "
                            + rendererRouteNote(mc, svc, uuid));
                }));

        // 3d. legacy 64x32 conversion ------------------------------------------------------------
        steps.add(Step.of("legacy_skin_apply")
                .action(() -> {
                    owner.prepareModelEvidenceView(mc);
                    legacySkinHash = null;
                    try {
                        AssetMetadata meta = SkinImporter.importSkin(TestAssets.makeLegacySkin());
                        if (meta == null) {
                            E2ELog.warn("legacy skin import returned null");
                            return;
                        }
                        legacySkinHash = meta.hash();
                        svc.applySkin(uuid, "local_skin:" + legacySkinHash, "classic");
                        E2ELog.info("applied legacy fixture local_skin:" + legacySkinHash);
                    } catch (Exception e) {
                        E2ELog.error("legacy_skin_apply action failed", e);
                    }
                })
                .minTicks(30)
                .ready(() -> owner.holdModelEvidenceView(mc, "classic")
                        && legacySkinHash != null
                        && svc.getSkinLocation(uuid) != null)
                .settleTicks(12)
                .timeoutTicks(300)
                .screenshot(prefix + "full_03d_legacy_skin" + suffix)
                .assertion(() -> {
                    if (legacySkinHash == null) return Step.Result.fail("legacy skin import failed (no hash)");
                    AssetMetadata meta = LocalAssetManager.getInstance().getMetadata(legacySkinHash);
                    if (meta == null) return Step.Result.fail("legacy skin is not catalogued: " + legacySkinHash);
                    if (meta.resolution() != SkinResolution.STANDARD) {
                        return Step.Result.fail("catalog resolution=" + meta.resolution()
                                + " expected STANDARD (64x64) after legacy conversion");
                    }
                    BufferedImage decoded = decodeFull(legacySkinHash);
                    if (decoded == null) return Step.Result.fail("loadTexture(FULL) returned no PNG");
                    if (decoded.getWidth() != 64 || decoded.getHeight() != 64) {
                        return Step.Result.fail("registered bytes are " + decoded.getWidth() + "x"
                                + decoded.getHeight() + ", expected 64x64");
                    }
                    int limb = 0xFF000000 | TestAssets.LEGACY_LIMB_RGB;
                    int[][] limbProbes = {
                            {45, 55}, // left arm back  (mirrored from the right arm)
                            {37, 55}, // left arm front (mirrored from the right arm)
                            {21, 55}, // left leg front (mirrored from the right leg)
                            {53, 25}, // right arm back (untouched source limb)
                            {5, 23},  // right leg front (untouched source limb)
                    };
                    StringBuilder limbFacts = new StringBuilder();
                    for (int[] p : limbProbes) {
                        int argb = decoded.getRGB(p[0], p[1]);
                        limbFacts.append(pixelFact(p[0], p[1], argb)).append(' ');
                        if (argb != limb) {
                            return Step.Result.fail("limb pixel " + pixelFact(p[0], p[1], argb)
                                    + " expected opaque " + hex(limb));
                        }
                    }
                    String hat = firstNonTransparent(decoded, 32, 0, 32, 16);
                    if (hat != null) {
                        return Step.Result.fail("all-black legacy hat layer was not cleared: " + hat);
                    }
                    int plaidTorso = TestAssets.loadPlaidSkinImage().getRGB(35, 24);
                    int torso = decoded.getRGB(35, 24);
                    if (torso != plaidTorso) {
                        return Step.Result.fail("torso back " + pixelFact(35, 24, torso)
                                + " lost the plaid palette " + hex(plaidTorso));
                    }
                    String rendered = VanillaShim.playerModel(mc.player);
                    if (!"classic".equals(rendered)) {
                        return Step.Result.fail("renderer model=" + rendered + " expected classic");
                    }
                    String fov = checkEvidenceFov(mc);
                    if (fov != null) return Step.Result.fail(fov);
                    String route = rendererRouteMismatch(mc, svc, uuid);
                    if (route != null) return Step.Result.fail(route);
                    return Step.Result.pass("64x32 legacy import catalogued as STANDARD 64x64; registered bytes "
                            + "carry " + hex(limb) + " on both arms and both legs [" + limbFacts.toString().trim()
                            + "], hat layer x32-63/y0-15 fully transparent, torso back "
                            + pixelFact(35, 24, torso) + " keeps the plaid palette; renderer model=classic, "
                            + "close rear FOV=" + FullScenario.MODEL_EVIDENCE_FOV + "; "
                            + rendererRouteNote(mc, svc, uuid));
                }));

        // 3e. folder-dropped odd sizes are normalized in place (no capture) ----------------------
        steps.add(Step.of("folder_asset_normalization")
                .action(() -> {
                    owner.enterWorldView(mc);
                    normalizationSetupFailure = null;
                    oddSkinTarget = null;
                    oddCapeTarget = null;
                    badRatioTarget = null;
                    try {
                        LocalAssetManager assets = LocalAssetManager.getInstance();
                        Path skins = assets.getSkinsDirectory();
                        Path capes = assets.getCapesDirectory();
                        if (skins == null || capes == null) {
                            normalizationSetupFailure = "LocalAssetManager directories are null";
                            return;
                        }
                        Files.createDirectories(skins);
                        Files.createDirectories(capes);
                        Path oddSkin = skins.resolve(ODD_SKIN_FILE);
                        Path oddCape = capes.resolve(ODD_CAPE_FILE);
                        Path badRatio = capes.resolve(BAD_RATIO_CAPE_FILE);
                        Files.copy(TestAssets.makeOddSizedSkin(), oddSkin, StandardCopyOption.REPLACE_EXISTING);
                        Files.copy(TestAssets.makeOddSizedCape(), oddCape, StandardCopyOption.REPLACE_EXISTING);
                        Files.copy(TestAssets.makeNonCapeRatioImage(), badRatio,
                                StandardCopyOption.REPLACE_EXISTING);
                        oddSkinTarget = oddSkin;
                        oddCapeTarget = oddCape;
                        badRatioTarget = badRatio;
                        assets.reload();
                        E2ELog.info("dropped odd-sized assets and reloaded: " + oddSkin + ", " + oddCape
                                + ", " + badRatio);
                    } catch (Exception e) {
                        normalizationSetupFailure = "could not stage odd-sized assets: " + e;
                        E2ELog.error("folder_asset_normalization action failed", e);
                    }
                })
                .minTicks(10)
                .ready(() -> {
                    if (normalizationSetupFailure != null) return true;
                    LocalAssetManager assets = LocalAssetManager.getInstance();
                    return oddSkinTarget != null && oddCapeTarget != null
                            && findCatalogued(assets, oddSkinTarget, "skin") != null
                            && findCatalogued(assets, oddCapeTarget, "cape") != null;
                })
                .timeoutTicks(300)
                .assertion(() -> {
                    if (normalizationSetupFailure != null) return Step.Result.fail(normalizationSetupFailure);
                    LocalAssetManager assets = LocalAssetManager.getInstance();

                    // (a) the 96x96 skin snapped to a valid resolution and the file itself was rewritten
                    AssetMetadata skinMeta = findCatalogued(assets, oddSkinTarget, "skin");
                    if (skinMeta == null) return Step.Result.fail("96x96 skin was not catalogued: " + oddSkinTarget);
                    SkinResolution skinRes = skinMeta.resolution();
                    if (skinRes == null || SkinResolution.fromDimensions(skinRes.getWidth(), skinRes.getHeight()) != skinRes) {
                        return Step.Result.fail("odd skin catalogued with an invalid resolution: " + skinRes);
                    }
                    BufferedImage skinFile = SafeImageReader.readPng(oddSkinTarget);
                    if (skinFile.getWidth() != skinRes.getWidth() || skinFile.getHeight() != skinRes.getHeight()) {
                        return Step.Result.fail("odd skin file is " + skinFile.getWidth() + "x" + skinFile.getHeight()
                                + " but catalogued as " + skinRes.name() + " " + skinRes.getWidth() + "x"
                                + skinRes.getHeight());
                    }
                    if (skinFile.getWidth() == 96 && skinFile.getHeight() == 96) {
                        return Step.Result.fail("96x96 skin was catalogued without being normalized on disk");
                    }
                    Path skinSource = assets.getSourcePath(skinMeta.hash());
                    if (skinSource == null || !sameFile(skinSource, oddSkinTarget)) {
                        return Step.Result.fail("odd skin source path=" + skinSource + " expected " + oddSkinTarget);
                    }

                    // (b) the 96x48 2:1 cape snapped to a valid cape resolution in place
                    AssetMetadata capeMeta = findCatalogued(assets, oddCapeTarget, "cape");
                    if (capeMeta == null) return Step.Result.fail("96x48 cape was not catalogued: " + oddCapeTarget);
                    SkinResolution capeRes = capeMeta.resolution();
                    if (capeRes == null || capeRes.getWidth() != capeRes.getHeight() * 2
                            || SkinResolution.fromDimensions(capeRes.getWidth(), capeRes.getHeight()) != capeRes) {
                        return Step.Result.fail("odd cape catalogued with an invalid cape resolution: " + capeRes);
                    }
                    BufferedImage capeFile = SafeImageReader.readPng(oddCapeTarget);
                    if (capeFile.getWidth() != capeRes.getWidth() || capeFile.getHeight() != capeRes.getHeight()) {
                        return Step.Result.fail("odd cape file is " + capeFile.getWidth() + "x" + capeFile.getHeight()
                                + " but catalogued as " + capeRes.name() + " " + capeRes.getWidth() + "x"
                                + capeRes.getHeight());
                    }
                    if (capeFile.getWidth() == 96 && capeFile.getHeight() == 48) {
                        return Step.Result.fail("96x48 cape was catalogued without being normalized on disk");
                    }
                    if (capeMeta.isAnimated() || capeMeta.frameCount() != 1) {
                        return Step.Result.fail("96x48 cape was misread as animated: frames=" + capeMeta.frameCount());
                    }
                    Path capeSource = assets.getSourcePath(capeMeta.hash());
                    if (capeSource == null || !sameFile(capeSource, oddCapeTarget)) {
                        return Step.Result.fail("odd cape source path=" + capeSource + " expected " + oddCapeTarget);
                    }

                    // (c) the 100x60 non-2:1 image is ignored by the cape scan
                    AssetMetadata badMeta = findCatalogued(assets, badRatioTarget, "cape");
                    if (badMeta == null) badMeta = findCatalogued(assets, badRatioTarget, "skin");
                    if (badMeta != null) {
                        return Step.Result.fail("100x60 non-cape image was catalogued as " + badMeta.type()
                                + " " + badMeta.resolution() + " hash=" + badMeta.hash());
                    }
                    String badFileState;
                    if (Files.exists(badRatioTarget)) {
                        BufferedImage badFile = SafeImageReader.readPng(badRatioTarget);
                        badFileState = "left untouched on disk at " + badFile.getWidth() + "x" + badFile.getHeight();
                    } else {
                        badFileState = "deleted from disk by the scan";
                    }

                    return Step.Result.pass("96x96 " + ODD_SKIN_FILE + " catalogued as " + skinRes.name()
                            + " and rewritten on disk to " + skinFile.getWidth() + "x" + skinFile.getHeight()
                            + " (hash=" + skinMeta.hash() + "); 96x48 " + ODD_CAPE_FILE + " catalogued as "
                            + capeRes.name() + " and rewritten in place to " + capeFile.getWidth() + "x"
                            + capeFile.getHeight() + " (hash=" + capeMeta.hash() + "); 100x60 "
                            + BAD_RATIO_CAPE_FILE + " not catalogued, " + badFileState);
                }));

        // 3h. base-layer transparency in the rear view ---------------------------------------------
        steps.add(Step.of("base_layer_transparency")
                .action(() -> {
                    transparentSkinHash = null;
                    try {
                        ClientConfig config = ClientConfig.getInstance();
                        if (config.disableSkinTransparency) {
                            config.disableSkinTransparency = false;
                            config.save();
                            svc.reloadSkinsForTransparencyChange();
                        }
                        owner.prepareModelEvidenceView(mc);
                        AssetMetadata meta = SkinImporter.importSkin(TestAssets.makeTransparentSkin());
                        if (meta == null) {
                            E2ELog.warn("transparent skin import returned null");
                            return;
                        }
                        transparentSkinHash = meta.hash();
                        svc.applySkin(uuid, "local_skin:" + transparentSkinHash, "classic");
                        E2ELog.info("applied transparent fixture local_skin:" + transparentSkinHash);
                    } catch (Exception e) {
                        E2ELog.error("transparent_skin_apply action failed", e);
                    }
                })
                .minTicks(30)
                .ready(() -> {
                    boolean held = owner.holdModelEvidenceView(mc, "classic");
                    if (!held || transparentSkinHash == null) return false;
                    Object location = svc.getSkinLocation(uuid);
                    return location != null
                            && Boolean.TRUE.equals(cachedTransparency(location))
                            && TextureAlphaDetector.hasTransparency(svc.getSkinLocation(uuid));
                })
                .settleTicks(12)
                .timeoutTicks(400)
                .screenshot(prefix + "full_03d_base_layer_transparency" + suffix)
                .assertion(() -> {
                    if (transparentSkinHash == null) return Step.Result.fail("transparent skin import failed");
                    if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
                        return Step.Result.fail("transparency is disabled by client or server policy");
                    }
                    BufferedImage decoded = decodeFull(transparentSkinHash);
                    if (decoded == null) return Step.Result.fail("loadTexture(FULL) returned no PNG");
                    int wx = TestAssets.TRANSPARENT_SKIN_WINDOW_X + 1;
                    int wy = TestAssets.TRANSPARENT_SKIN_WINDOW_Y + 1;
                    int window = decoded.getRGB(wx, wy);
                    if (alpha(window) != 0) {
                        return Step.Result.fail("torso window " + pixelFact(wx, wy, window) + " expected alpha 0");
                    }
                    int sx = TestAssets.TRANSLUCENT_SLEEVE_PROBE_X;
                    int sy = TestAssets.TRANSLUCENT_SLEEVE_PROBE_Y;
                    int sleeve = decoded.getRGB(sx, sy);
                    if (alpha(sleeve) != TestAssets.TRANSLUCENT_SLEEVE_ALPHA) {
                        return Step.Result.fail("sleeve " + pixelFact(sx, sy, sleeve) + " expected alpha "
                                + TestAssets.TRANSLUCENT_SLEEVE_ALPHA);
                    }
                    if (!TextureAlphaDetector.hasTransparentPixels(decoded)) {
                        return Step.Result.fail("hasTransparentPixels is false on the registered bytes");
                    }
                    Object location = svc.getSkinLocation(uuid);
                    if (location == null) return Step.Result.fail("skin location did not resolve");
                    if (!TextureAlphaDetector.hasTransparency(svc.getSkinLocation(uuid))
                            || !Boolean.TRUE.equals(cachedTransparency(location))) {
                        return Step.Result.fail("alpha detector cache for " + location + " is "
                                + cachedTransparency(location) + ", expected true");
                    }
                    PlayerAppearance app = svc.getAppearance(uuid);
                    String expectedId = "local_skin:" + transparentSkinHash;
                    if (app == null || !expectedId.equals(app.getSkinId())) {
                        return Step.Result.fail("skinId=" + (app == null ? null : app.getSkinId())
                                + " expected " + expectedId);
                    }
                    String rendered = VanillaShim.playerModel(mc.player);
                    if (!"classic".equals(rendered)) {
                        return Step.Result.fail("renderer model=" + rendered + " expected classic");
                    }
                    String fov = checkEvidenceFov(mc);
                    if (fov != null) return Step.Result.fail(fov);
                    String route = rendererRouteMismatch(mc, svc, uuid);
                    if (route != null) return Step.Result.fail(route);
                    return Step.Result.pass(expectedId + " with transparency allowed "
                            + "(shouldDisableSkinTransparency=false): registered bytes torso window "
                            + pixelFact(wx, wy, window) + " alpha 0, sleeve " + pixelFact(sx, sy, sleeve)
                            + " alpha " + alpha(sleeve) + ", hasTransparentPixels=true, alpha detector cache["
                            + location + "]=true; renderer model=classic, close rear FOV="
                            + FullScenario.MODEL_EVIDENCE_FOV + "; " + rendererRouteNote(mc, svc, uuid));
                }));

        // 3i. the same translucent sleeve in first person ----------------------------------------
        steps.add(Step.of("base_layer_transparency_first_person")
                .action(() -> {
                    owner.restoreModelEvidenceView(mc);
                    DefaultSkinEvidenceView.enterFirstPerson(mc);
                    owner.pinRearEvidenceView(mc);
                })
                .minTicks(30)
                .ready(() -> {
                    if (mc.player == null || mc.options == null || transparentSkinHash == null) return false;
                    VanillaShim.setScreen(mc, null);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.keyShift.setDown(false);
                    mc.player.setShiftKeyDown(false);
                    owner.pinRearEvidenceView(mc);
                    Object location = svc.getSkinLocation(uuid);
                    return mc.options.getCameraType() == CameraType.FIRST_PERSON
                            && location != null
                            && TextureAlphaDetector.hasTransparency(svc.getSkinLocation(uuid))
                            && Boolean.TRUE.equals(cachedTransparency(location));
                })
                .settleTicks(12)
                .timeoutTicks(300)
                .screenshot(prefix + "full_03e_base_layer_transparency_first_person" + suffix)
                .assertion(() -> {
                    if (transparentSkinHash == null) return Step.Result.fail("transparent skin was never imported");
                    if (mc.options == null || mc.options.getCameraType() != CameraType.FIRST_PERSON) {
                        return Step.Result.fail("camera is not first person: "
                                + (mc.options == null ? null : mc.options.getCameraType()));
                    }
                    if (VanillaShim.currentScreen(mc) != null) {
                        return Step.Result.fail("a screen is open over the world: " + FullScenario.screenName(mc));
                    }
                    PlayerAppearance app = svc.getAppearance(uuid);
                    String expectedId = "local_skin:" + transparentSkinHash;
                    if (app == null || !expectedId.equals(app.getSkinId())) {
                        return Step.Result.fail("skinId=" + (app == null ? null : app.getSkinId())
                                + " expected " + expectedId);
                    }
                    Object location = svc.getSkinLocation(uuid);
                    if (location == null) return Step.Result.fail("skin location did not resolve");
                    if (!TextureAlphaDetector.hasTransparency(svc.getSkinLocation(uuid))
                            || !Boolean.TRUE.equals(cachedTransparency(location))) {
                        return Step.Result.fail("alpha detector cache for " + location + " is "
                                + cachedTransparency(location) + ", expected true for the first-person arm");
                    }
                    BufferedImage decoded = decodeFull(transparentSkinHash);
                    if (decoded == null) return Step.Result.fail("loadTexture(FULL) returned no PNG");
                    int sx = TestAssets.TRANSLUCENT_SLEEVE_PROBE_X;
                    int sy = TestAssets.TRANSLUCENT_SLEEVE_PROBE_Y;
                    int sleeve = decoded.getRGB(sx, sy);
                    if (alpha(sleeve) != TestAssets.TRANSLUCENT_SLEEVE_ALPHA) {
                        return Step.Result.fail("sleeve " + pixelFact(sx, sy, sleeve) + " expected alpha "
                                + TestAssets.TRANSLUCENT_SLEEVE_ALPHA);
                    }
                    if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
                        return Step.Result.fail("transparency became disabled before the first-person capture");
                    }
                    String route = rendererRouteMismatch(mc, svc, uuid);
                    if (route != null) return Step.Result.fail(route);
                    return Step.Result.pass("first-person camera with " + expectedId + ": registered right-arm "
                            + "sleeve " + pixelFact(sx, sy, sleeve) + " alpha " + alpha(sleeve)
                            + ", alpha detector cache[" + location + "]=true so the arm mixin renders "
                            + "translucently, FOV restored to " + VanillaShim.fieldOfView(mc) + "; "
                            + rendererRouteNote(mc, svc, uuid));
                }));

        // 3j. Disable Skin Transparency through the real settings checkbox ------------------------
        steps.add(Step.of("transparency_disabled")
                .action(() -> {
                    disablePhase.set(0);
                    transparencyCheckboxPressed = false;
                    disableFailure = null;
                    VanillaShim.setScreen(mc, new SettingsScreen(null));
                })
                .minTicks(25)
                .ready(() -> {
                    if (disableFailure != null) return true;
                    if (transparentSkinHash == null) {
                        disableFailure = "transparent skin was never imported";
                        return true;
                    }
                    int phase = disablePhase.get();
                    if (phase == 0) {
                        Screen screen = VanillaShim.currentScreen(mc);
                        if (!(screen instanceof SettingsScreen settings)) return false;
                        Object cbObj = FullScenario.screenField(settings, "disableSkinTransparencyCheckbox");
                        if (!(cbObj instanceof Checkbox checkbox)) return false;
                        if (checkbox.selected()) {
                            if (!transparencyCheckboxPressed) {
                                disableFailure = "Disable Skin Transparency was already selected before the press";
                                return true;
                            }
                            // onClose persists the checkbox into ClientConfig, flags the reload, and
                            // hands the screen back to Minecraft; Screen.removed() then runs the
                            // production reloadSkinsForTransparencyChange() on this same tick.
                            settings.onClose();
                            disablePhase.set(1);
                            return false;
                        }
                        if (!VanillaShim.press(checkbox)) {
                            disableFailure = "could not press the Disable Skin Transparency checkbox";
                            return true;
                        }
                        transparencyCheckboxPressed = true;
                        return false;
                    }
                    if (phase == 1) {
                        if (VanillaShim.currentScreen(mc) instanceof SettingsScreen) return false;
                        if (!ClientConfig.getInstance().disableSkinTransparency) {
                            disableFailure = "SettingsScreen.onClose did not persist disableSkinTransparency=true";
                            return true;
                        }
                        owner.prepareModelEvidenceView(mc);
                        disablePhase.set(2);
                        return false;
                    }
                    boolean held = owner.holdModelEvidenceView(mc, "classic");
                    if (!held) return false;
                    Object location = svc.getSkinLocation(uuid);
                    if (location == null || cachedTransparency(location) == null) return false;
                    BufferedImage decoded = decodeFull(transparentSkinHash);
                    if (decoded == null) return false;
                    int window = decoded.getRGB(TestAssets.TRANSPARENT_SKIN_WINDOW_X + 1,
                            TestAssets.TRANSPARENT_SKIN_WINDOW_Y + 1);
                    int sleeve = decoded.getRGB(TestAssets.TRANSLUCENT_SLEEVE_PROBE_X,
                            TestAssets.TRANSLUCENT_SLEEVE_PROBE_Y);
                    return alpha(window) == 255 && rgb(window) == 0 && alpha(sleeve) == 255;
                })
                .settleTicks(20)
                .timeoutTicks(400)
                .screenshot(prefix + "full_03f_transparency_disabled" + suffix)
                .assertion(() -> {
                    if (disableFailure != null) return Step.Result.fail(disableFailure);
                    ClientConfig config = ClientConfig.getInstance();
                    if (!config.disableSkinTransparency || !config.shouldDisableSkinTransparency()) {
                        return Step.Result.fail("disableSkinTransparency=" + config.disableSkinTransparency
                                + " shouldDisable=" + config.shouldDisableSkinTransparency());
                    }
                    if (VanillaShim.currentScreen(mc) != null) {
                        return Step.Result.fail("settings screen still open: " + FullScenario.screenName(mc));
                    }
                    BufferedImage decoded = decodeFull(transparentSkinHash);
                    if (decoded == null) return Step.Result.fail("loadTexture(FULL) returned no PNG");
                    int wx = TestAssets.TRANSPARENT_SKIN_WINDOW_X + 1;
                    int wy = TestAssets.TRANSPARENT_SKIN_WINDOW_Y + 1;
                    int window = decoded.getRGB(wx, wy);
                    if (alpha(window) != 255 || rgb(window) != 0) {
                        return Step.Result.fail("torso window " + pixelFact(wx, wy, window)
                                + " expected opaque black ff000000");
                    }
                    int sx = TestAssets.TRANSLUCENT_SLEEVE_PROBE_X;
                    int sy = TestAssets.TRANSLUCENT_SLEEVE_PROBE_Y;
                    int sleeve = decoded.getRGB(sx, sy);
                    if (alpha(sleeve) != 255) {
                        return Step.Result.fail("sleeve " + pixelFact(sx, sy, sleeve) + " expected alpha 255");
                    }
                    String baseLeak = firstTranslucentBasePixel(decoded);
                    if (baseLeak != null) {
                        return Step.Result.fail("base layer still carries alpha below 255 at " + baseLeak);
                    }
                    int overlayTranslucent = countTranslucentOverlayPixels(decoded);
                    Object location = svc.getSkinLocation(uuid);
                    if (location == null) return Step.Result.fail("skin location did not resolve after reload");
                    Boolean cached = cachedTransparency(location);
                    if (cached == null) {
                        return Step.Result.fail("alpha detector cache has no entry for the re-registered "
                                + location);
                    }
                    String gpu = registeredWindowPixel(mc, svc.getSkinLocation(uuid), wx, wy);
                    if (gpu != null && gpu.startsWith("!")) return Step.Result.fail(gpu.substring(1));
                    PlayerAppearance app = svc.getAppearance(uuid);
                    String expectedId = "local_skin:" + transparentSkinHash;
                    if (app == null || !expectedId.equals(app.getSkinId())) {
                        return Step.Result.fail("skinId=" + (app == null ? null : app.getSkinId())
                                + " expected " + expectedId);
                    }
                    String rendered = VanillaShim.playerModel(mc.player);
                    if (!"classic".equals(rendered)) {
                        return Step.Result.fail("renderer model=" + rendered + " expected classic");
                    }
                    String fov = checkEvidenceFov(mc);
                    if (fov != null) return Step.Result.fail(fov);
                    String route = rendererRouteMismatch(mc, svc, uuid);
                    if (route != null) return Step.Result.fail(route);
                    return Step.Result.pass("real Disable Skin Transparency checkbox pressed, "
                            + "SettingsScreen.onClose persisted disableSkinTransparency=true and its removed() "
                            + "ran reloadSkinsForTransparencyChange: registered bytes torso window "
                            + pixelFact(wx, wy, window) + " opaque black, sleeve " + pixelFact(sx, sy, sleeve)
                            + " alpha 255, no base-layer pixel below alpha 255 (" + overlayTranslucent
                            + " overlay-layer pixels keep transparency by design, so alpha detector cache["
                            + location + "]=" + cached + "); "
                            + (gpu == null ? "GPU texture pixel unavailable" : gpu)
                            + "; renderer model=classic, close rear FOV=" + FullScenario.MODEL_EVIDENCE_FOV
                            + "; " + rendererRouteNote(mc, svc, uuid));
                }));

        // 3k. back to the plaid classic skin with transparency allowed (no capture) --------------
        steps.add(Step.of("restore_classic_skin")
                .action(() -> {
                    try {
                        ClientConfig config = ClientConfig.getInstance();
                        config.disableSkinTransparency = false;
                        config.save();
                        svc.reloadSkinsForTransparencyChange();
                        if (owner.skinHash != null) {
                            svc.applySkin(uuid, "local_skin:" + owner.skinHash, "classic");
                        }
                        owner.restoreModelEvidenceView(mc);
                        owner.enterWorldView(mc);
                    } catch (Exception e) {
                        E2ELog.error("restore_classic_skin action failed", e);
                    }
                })
                .minTicks(30)
                .ready(() -> {
                    if (owner.skinHash == null || mc.player == null) return false;
                    Object expected = LocalAssetManager.getInstance()
                            .getTextureLocation(owner.skinHash, TextureQuality.FULL);
                    Object service = svc.getSkinLocation(uuid);
                    return expected != null && service != null
                            && expected.toString().equals(service.toString())
                            && rendererRouteMismatch(mc, svc, uuid) == null
                            && "classic".equals(VanillaShim.playerModel(mc.player));
                })
                .timeoutTicks(300)
                .assertion(() -> {
                    if (owner.skinHash == null) return Step.Result.fail("plaid skin hash unavailable");
                    ClientConfig config = ClientConfig.getInstance();
                    if (config.disableSkinTransparency || config.shouldDisableSkinTransparency()) {
                        return Step.Result.fail("transparency still disabled: client="
                                + config.disableSkinTransparency + " shouldDisable="
                                + config.shouldDisableSkinTransparency());
                    }
                    Object expected = LocalAssetManager.getInstance()
                            .getTextureLocation(owner.skinHash, TextureQuality.FULL);
                    if (expected == null) return Step.Result.fail("plaid skin texture location unavailable");
                    Object service = svc.getSkinLocation(uuid);
                    if (service == null || !expected.toString().equals(service.toString())) {
                        return Step.Result.fail("service location=" + service + " expected " + expected);
                    }
                    String route = rendererRouteMismatch(mc, svc, uuid);
                    if (route != null) return Step.Result.fail(route);
                    PlayerAppearance app = svc.getAppearance(uuid);
                    String expectedId = "local_skin:" + owner.skinHash;
                    if (app == null || !expectedId.equals(app.getSkinId()) || !"classic".equals(app.getModel())) {
                        return Step.Result.fail("appearance=" + app + " expected " + expectedId + " classic");
                    }
                    String rendered = VanillaShim.playerModel(mc.player);
                    if (!"classic".equals(rendered)) {
                        return Step.Result.fail("renderer model=" + rendered + " expected classic");
                    }
                    if (owner.modelEvidenceOriginalFov != null) {
                        return Step.Result.fail("model evidence FOV was not restored: original="
                                + owner.modelEvidenceOriginalFov + " current=" + VanillaShim.fieldOfView(mc));
                    }
                    return Step.Result.pass("restored " + expectedId + " classic with transparency allowed "
                            + "(shouldDisableSkinTransparency=false); service location " + service
                            + " equals the local FULL texture location, renderer model=classic, FOV="
                            + VanillaShim.fieldOfView(mc) + "; " + rendererRouteNote(mc, svc, uuid));
                }));

        return steps;
    }

    // ===== helpers ==============================================================================

    /** The PNG bytes {@link LocalAssetManager} registers for the renderer, decoded. */
    static BufferedImage decodeFull(String hash) {
        try {
            byte[] png = LocalAssetManager.getInstance().loadTexture(hash, TextureQuality.FULL);
            return png == null ? null : SafeImageReader.readPng(png);
        } catch (Exception e) {
            E2ELog.warn("decodeFull(" + hash + "): " + e);
            return null;
        }
    }

    static int alpha(int argb) { return (argb >>> 24) & 0xFF; }

    static int rgb(int argb) { return argb & 0xFFFFFF; }

    static String hex(int argb) { return String.format(Locale.ROOT, "%08x", argb); }

    static String pixelFact(int x, int y, int argb) { return "(" + x + "," + y + ")=" + hex(argb); }

    /** First pixel in the box whose alpha is not 0, as a printable fact, or {@code null}. */
    static String firstNonTransparent(BufferedImage img, int x0, int y0, int w, int h) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int argb = img.getRGB(x, y);
                if (alpha(argb) != 0) return pixelFact(x, y, argb);
            }
        }
        return null;
    }

    /** Mirrors {@code HDTextureProcessor.isOverlayLayerPixel} at the image's own scale. */
    static boolean isOverlayLayerPixel(int px, int py, int scale) {
        int x = px / scale;
        int y = py / scale;
        return (x >= 32 && x < 64 && y < 16)
                || (x >= 16 && x < 40 && y >= 32 && y < 48)
                || (x >= 40 && x < 56 && y >= 32 && y < 48)
                || (x >= 48 && x < 64 && y >= 48 && y < 64)
                || (x < 16 && y >= 32 && y < 48)
                || (x < 16 && y >= 48 && y < 64);
    }

    /** First base-layer pixel with alpha below 255, or {@code null} when the base is fully opaque. */
    static String firstTranslucentBasePixel(BufferedImage img) {
        int scale = Math.max(1, img.getWidth() / 64);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (isOverlayLayerPixel(x, y, scale)) continue;
                int argb = img.getRGB(x, y);
                if (alpha(argb) != 255) return pixelFact(x, y, argb);
            }
        }
        return null;
    }

    static int countTranslucentOverlayPixels(BufferedImage img) {
        int scale = Math.max(1, img.getWidth() / 64);
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (isOverlayLayerPixel(x, y, scale) && alpha(img.getRGB(x, y)) != 255) count++;
            }
        }
        return count;
    }

    static String checkEvidenceFov(Minecraft mc) {
        Integer fov = VanillaShim.fieldOfView(mc);
        if (!Integer.valueOf(FullScenario.MODEL_EVIDENCE_FOV).equals(fov)) {
            return "model evidence FOV=" + fov + " expected " + FullScenario.MODEL_EVIDENCE_FOV;
        }
        return null;
    }

    /**
     * Renderer-facing skin texture versus the service location. CPM replaces the renderer's
     * skin location with its own http texture while Quick Skin still owns the service location,
     * so that lane records the substitution instead of demanding equality.
     */
    static String rendererRouteMismatch(Minecraft mc, PlayerAppearanceService svc, UUID uuid) {
        if (mc.player == null) return "player is null";
        Object service = svc.getSkinLocation(uuid);
        if (service == null) return "service skin location did not resolve";
        if (CPMCompatIntegration.isAvailable()) return null;
        String rendered = VanillaShim.skinTexture(mc.player);
        if (!service.toString().equals(rendered)) {
            return "renderer skin texture=" + rendered + " expected service location " + service;
        }
        return null;
    }

    static String rendererRouteNote(Minecraft mc, PlayerAppearanceService svc, UUID uuid) {
        Object service = svc.getSkinLocation(uuid);
        String rendered = mc.player == null ? null : VanillaShim.skinTexture(mc.player);
        if (CPMCompatIntegration.isAvailable()) {
            return "service location=" + service + " (CPM lane owns renderer texture " + rendered + ")";
        }
        return "renderer skin texture=" + rendered + " equals service location";
    }

    /** Model-label text exactly as {@code SkinEntry.renderContent} composes it from metadata. */
    static String catalogModelLabel(AssetMetadata meta) {
        String model = meta.skinModel() == null ? null : meta.skinModel().toLowerCase(Locale.ROOT);
        String text = "slim".equals(model) ? "Slim" : "Classic";
        if (meta.resolution().isHD()) text += " \u2022 " + meta.resolution().name();
        return text;
    }

    /** Model-label colour exactly as {@code SkinEntry.renderContent} selects it from metadata. */
    static int catalogModelColor(AssetMetadata meta) {
        return meta.resolution().isHD() ? HD_LABEL_COLOR : STANDARD_LABEL_COLOR;
    }

    static AssetMetadata findCatalogued(LocalAssetManager assets, Path file, String type) {
        if (file == null) return null;
        List<AssetMetadata> list = assets.getAssetsByType(type);
        if (list == null) return null;
        for (AssetMetadata meta : list) {
            if (meta != null && sameFile(meta.path(), file)) return meta;
        }
        return null;
    }

    static boolean sameFile(Path a, Path b) {
        if (a == null || b == null) return false;
        try {
            if (Files.exists(a) && Files.exists(b)) return Files.isSameFile(a, b);
        } catch (Exception ignored) {
            // fall through to the lexical comparison
        }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /**
     * The alpha-detector cache entry for a texture location, or {@code null} when absent.
     * {@code hasTransparency} answers {@code true} for both "cached true" and "not analysed", so the
     * private map is the only way to tell that the registration actually seeded the entry.
     */
    static Boolean cachedTransparency(Object location) {
        if (location == null) return null;
        try {
            Field f = TextureAlphaDetector.class.getDeclaredField("transparencyCache");
            f.setAccessible(true);
            Object map = f.get(null);
            if (!(map instanceof Map<?, ?> cache)) return null;
            Object value = cache.get(location);
            return value instanceof Boolean b ? b : null;
        } catch (Throwable t) {
            E2ELog.warn("transparencyCache: " + t);
            return null;
        }
    }

    /**
     * The registered texture behind a location, looked up by name so this file never spells the
     * location type that moves across versions. {@code TextureManager.getTexture(location)} is
     * {@code method_4619} on Fabric's intermediary runtime and {@code m_118506_} on Forge SRG.
     */
    static AbstractTexture registeredTexture(Minecraft mc, Object location) {
        if (location == null || mc == null) return null;
        try {
            Object manager = mc.getTextureManager();
            if (manager == null) return null;
            for (Method m : manager.getClass().getMethods()) {
                if ((m.getName().equals("getTexture") || m.getName().equals("method_4619")
                        || m.getName().equals("m_118506_"))
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(location)) {
                    m.setAccessible(true);
                    Object texture = m.invoke(manager, location);
                    return texture instanceof AbstractTexture t ? t : null;
                }
            }
            E2ELog.warn("TextureManager.getTexture(location) not found");
            return null;
        } catch (Throwable t) {
            E2ELog.warn("registeredTexture: " + t);
            return null;
        }
    }

    /**
     * Dimensions of the registered {@link DynamicTexture}. Returns a printable fact, a
     * {@code "!"}-prefixed failure when the texture is reachable but not 128x128, or {@code null}
     * when the texture API is not reachable in this runtime.
     */
    static String registeredTextureDimensions(Minecraft mc, Object location) {
        try {
            if (location == null) return null;
            AbstractTexture texture = registeredTexture(mc, location);
            if (!(texture instanceof DynamicTexture dynamic)) return null;
            NativeImage pixels = dynamic.getPixels();
            if (pixels == null) return null;
            int w = pixels.getWidth();
            int h = pixels.getHeight();
            if (w != 128 || h != 128) {
                return "!registered DynamicTexture " + location + " is " + w + "x" + h + ", expected 128x128";
            }
            return "registered DynamicTexture " + location + " is " + w + "x" + h;
        } catch (Throwable t) {
            E2ELog.warn("registeredTextureDimensions: " + t);
            return null;
        }
    }

    /**
     * Alpha of one pixel in the registered {@link DynamicTexture}. Returns a printable fact, a
     * {@code "!"}-prefixed failure when the GPU-side pixel is still translucent, or {@code null}
     * when the texture API is not reachable in this runtime.
     */
    static String registeredWindowPixel(Minecraft mc, Object location, int x, int y) {
        try {
            if (location == null) return null;
            AbstractTexture texture = registeredTexture(mc, location);
            if (!(texture instanceof DynamicTexture dynamic)) return null;
            NativeImage pixels = dynamic.getPixels();
            if (pixels == null || x >= pixels.getWidth() || y >= pixels.getHeight()) return null;
            // The accessor is renamed per era and its channel order is not stable across those
            // renames, so compare the packed value: opaque black is 0xFF000000 in every order.
            Integer packed = VanillaShim.nativeImagePixel(pixels, x, y);
            if (packed == null) return null;
            if (packed != 0xFF000000) {
                return "!registered DynamicTexture " + location + " window (" + x + "," + y
                        + ") is 0x" + String.format("%08X", packed) + ", expected opaque black";
            }
            return "registered DynamicTexture " + location + " window (" + x + "," + y
                    + ") is opaque black on the GPU-side image";
        } catch (Throwable t) {
            E2ELog.warn("registeredWindowPixel: " + t);
            return null;
        }
    }
}
