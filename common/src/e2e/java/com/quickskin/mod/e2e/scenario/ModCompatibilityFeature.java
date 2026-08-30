package com.quickskin.mod.e2e.scenario;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.compat.CpmModelWorkflow;
import com.quickskin.mod.client.compat.CustomNPCsIntegration;
import com.quickskin.mod.client.compat.EarsCompatIntegration;
import com.quickskin.mod.client.compat.EssentialCompatIntegration;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.util.GuiScaleManager;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.gui.widget.IconActionButton;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.SkinLayers3DIntegration;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.DefaultSkinEvidenceView;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.networking.NetworkSyncService;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * One real optional-mod workflow behind the two stable public compatibility checkpoints.
 *
 * <p>The IDs remain stable so previously reviewed pixel-identical evidence can still be reused,
 * while the selected implementation changes the action, readiness proof, assertion, and scene for
 * the exact lock-selected mod. No driver is allowed to reduce its proof to class presence.</p>
 */
interface ModCompatibilityFeature {
    void prepareBaseline();

    boolean baselineReady();

    Step.Result assertBaseline();

    void applyQuickSkinFeature();

    boolean quickSkinFeatureReady();

    Step.Result assertQuickSkinFeature();

    default int baselineMinTicks() {
        return 40;
    }

    default int baselineSettleTicks() {
        return 20;
    }

    default int baselineTimeoutTicks() {
        return 500;
    }

    default int appliedMinTicks() {
        return 40;
    }

    default int appliedSettleTicks() {
        return 20;
    }

    default int appliedTimeoutTicks() {
        return 600;
    }

    static void prepareBeforeWorldJoin(String modId) {
        if ("replaymod".equals(modId)) {
            ReplayModFeature.protectStartupRecordingBeforeWorldJoin();
        }
    }

    static ModCompatibilityFeature create(String modId, Minecraft minecraft) {
        return switch (modId) {
            case "cpm" -> new CpmFeature(minecraft);
            case "ears" -> new EarsFeature(minecraft);
            case "skin-layers-3d" -> new SkinLayersFeature(minecraft);
            case "customnpcs" -> new CustomNpcsFeature(minecraft);
            case "essential" -> new EssentialFeature(minecraft);
            case "replaymod" -> new ReplayModFeature(minecraft);
            default -> new UnsupportedFeature(minecraft, modId);
        };
    }

    abstract class BaseFeature implements ModCompatibilityFeature {
        final Minecraft minecraft;
        final UUID playerId;
        final PlayerAppearanceService appearances;
        volatile String skinHash;
        volatile String failure;

        BaseFeature(Minecraft minecraft) {
            this.minecraft = minecraft;
            this.playerId = minecraft.player.getUUID();
            this.appearances = PlayerAppearanceService.getInstance();
        }

        final AssetMetadata importAndApply(Path fixture) {
            try {
                AssetMetadata metadata = SkinImporter.importSkin(fixture);
                if (metadata == null) {
                    failure = "Quick Skin rejected fixture " + fixture;
                    return null;
                }
                skinHash = metadata.hash();
                CpmModelWorkflow.activateSkin(skinHash);
                appearances.applySkin(playerId, "local_skin:" + skinHash, "auto");
                return metadata;
            } catch (Exception exception) {
                failure = "fixture import/apply failed: " + concise(exception);
                E2ELog.error(failure, exception);
                return null;
            }
        }

        final boolean activeSkinReady() {
            if (skinHash == null || minecraft.player == null) return false;
            PlayerAppearance state = appearances.getAppearance(playerId);
            return state != null
                    && ("local_skin:" + skinHash).equals(state.getSkinId())
                    && appearances.getSkinLocation(playerId) != null
                    && String.valueOf(appearances.getSkinLocation(playerId))
                    .equals(VanillaShim.skinTexture(minecraft.player));
        }

        final boolean holdFullBody() {
            return DefaultSkinEvidenceView.hold(minecraft, false);
        }

        final Step.Result activeSkinAssertion(String integrationProof) {
            if (failure != null) return Step.Result.fail(failure);
            if (!activeSkinReady()) {
                return Step.Result.fail("Quick Skin fixture did not reach renderer-facing state");
            }
            return Step.Result.pass(integrationProof + "; skinId=local_skin:" + skinHash
                    + "; rendererTexture=" + VanillaShim.skinTexture(minecraft.player));
        }

        static String concise(Throwable failure) {
            Throwable current = failure;
            if (current instanceof InvocationTargetException invocation
                    && invocation.getCause() != null) {
                current = invocation.getCause();
            }
            return current.getClass().getSimpleName() + ": " + current.getMessage();
        }
    }

    final class UnsupportedFeature extends BaseFeature {
        private final String modId;

        UnsupportedFeature(Minecraft minecraft, String modId) {
            super(minecraft);
            this.modId = modId;
        }

        @Override
        public void prepareBaseline() {
            failure = "unsupported compatibility feature id: " + modId;
        }

        @Override
        public boolean baselineReady() {
            return true;
        }

        @Override
        public Step.Result assertBaseline() {
            return Step.Result.fail(failure);
        }

        @Override
        public void applyQuickSkinFeature() {
        }

        @Override
        public boolean quickSkinFeatureReady() {
            return true;
        }

        @Override
        public Step.Result assertQuickSkinFeature() {
            return Step.Result.fail(failure);
        }
    }

    final class CpmFeature extends BaseFeature {
        private volatile AssetMetadata model;
        private volatile boolean modelActivated;

        CpmFeature(Minecraft minecraft) {
            super(minecraft);
        }

        @Override
        public void prepareBaseline() {
            VanillaShim.setFieldOfView(minecraft, 55);
            try {
                Path fixture = TestAssets.makeCpmModel();
                model = SkinImporter.importCpmModel(fixture);
                modelActivated = model != null && CpmModelWorkflow.activateModel(model);
                if (!modelActivated) failure = "Quick Skin could not import and activate CPM model";
            } catch (Exception exception) {
                failure = "CPM model fixture preparation failed: " + concise(exception);
                E2ELog.error(failure, exception);
            }
            holdFullBody();
        }

        @Override
        public boolean baselineReady() {
            return failure == null
                    && modelActivated
                    && model != null
                    && model.hash().equals(ClientConfig.getInstance().activeCpmModelHash)
                    && CPMCompatIntegration.isLocalPlayerWearingCpmModel()
                    && holdFullBody();
        }

        @Override
        public Step.Result assertBaseline() {
            if (failure != null) return Step.Result.fail(failure);
            if (model == null || !model.isCpmModel() || !modelActivated) {
                return Step.Result.fail("protected .cpmmodel fixture was not active");
            }
            CPMCompatIntegration.CpmModelInfo info =
                    CPMCompatIntegration.parseCpmModelInfo(model.path());
            if (info == null || info.name == null || info.name.trim().isEmpty()) {
                return Step.Result.fail("CPM model metadata did not round-trip through Quick Skin");
            }
            if (!CPMCompatIntegration.isLocalPlayerWearingCpmModel()) {
                return Step.Result.fail("CPM definition cache does not report the selected model");
            }
            return Step.Result.pass("Quick Skin imported, selected and rendered protected complex "
                    + "CPM fixture " + model.hash());
        }

        @Override
        public void applyQuickSkinFeature() {
            importAndApply(safeFixture(TestAssets::makeClassicSkin, "normal skin"));
            holdFullBody();
        }

        @Override
        public boolean quickSkinFeatureReady() {
            return failure == null
                    && activeSkinReady()
                    && ClientConfig.getInstance().activeCpmModelHash.isEmpty()
                    && !CPMCompatIntegration.isLocalPlayerWearingCpmModel()
                    && holdFullBody();
        }

        @Override
        public Step.Result assertQuickSkinFeature() {
            if (!ClientConfig.getInstance().activeCpmModelHash.isEmpty()) {
                return Step.Result.fail("normal skin left the CPM model hash selected");
            }
            if (CPMCompatIntegration.isLocalPlayerWearingCpmModel()) {
                return Step.Result.fail("CPM kept rendering its model after Quick Skin selected a skin");
            }
            return activeSkinAssertion(
                    "Quick Skin reset CPM to skin mode and restored its normal skin renderer");
        }

        private Path safeFixture(FixtureFactory factory, String label) {
            try {
                return factory.create();
            } catch (Exception exception) {
                failure = label + " fixture failed: " + concise(exception);
                return null;
            }
        }
    }

    final class EarsFeature extends BaseFeature {
        private volatile Object detectedFeatures;

        EarsFeature(Minecraft minecraft) {
            super(minecraft);
        }

        @Override
        public void prepareBaseline() {
            VanillaShim.setFieldOfView(minecraft, 55);
            try {
                importAndApply(TestAssets.makeFlatOverlaySkin());
            } catch (Exception exception) {
                failure = "plain Ears control skin failed: " + concise(exception);
            }
            holdFullBody();
        }

        @Override
        public boolean baselineReady() {
            return failure == null && activeSkinReady() && holdFullBody();
        }

        @Override
        public Step.Result assertBaseline() {
            if (failure != null) return Step.Result.fail(failure);
            Object features = EarsCompatIntegration.getFeatures(
                    appearances.getSkinLocation(playerId));
            if (features != null) {
                return Step.Result.fail("plain control skin unexpectedly enabled Ears features: "
                        + features);
            }
            return activeSkinAssertion(
                    "Ears renderer is loaded and the plain Quick Skin control remains featureless");
        }

        @Override
        public void applyQuickSkinFeature() {
            try {
                importAndApply(TestAssets.makeEarsSkin());
            } catch (Exception exception) {
                failure = "Ears fixture generation failed: " + concise(exception);
                E2ELog.error(failure, exception);
            }
            holdFullBody();
        }

        @Override
        public boolean quickSkinFeatureReady() {
            if (failure != null || !activeSkinReady()) return false;
            detectedFeatures = EarsCompatIntegration.getFeatures(
                    appearances.getSkinLocation(playerId));
            return detectedFeatures != null
                    && "TALL".equals(publicField(detectedFeatures, "earMode"))
                    && "BACK".equals(publicField(detectedFeatures, "tailMode"))
                    && holdFullBody();
        }

        @Override
        public Step.Result assertQuickSkinFeature() {
            if (failure != null) return Step.Result.fail(failure);
            if (detectedFeatures == null || EarsCompatIntegration.isDisabledResult(detectedFeatures)) {
                return Step.Result.fail("Quick Skin did not retain parsed Ears features");
            }
            try {
                Object playerRenderer = minecraft.getEntityRenderDispatcher()
                        .getRenderer(minecraft.player);
                Object rendererFeatures = rendererFeatures(playerRenderer);
                if (EarsCompatIntegration.isDisabledResult(rendererFeatures)) {
                    return Step.Result.fail("Ears renderer lookup did not receive Quick Skin features");
                }
                if (!"true".equals(publicField(rendererFeatures, "enabled"))
                        || !"TALL".equals(publicField(rendererFeatures, "earMode"))
                        || !"BACK".equals(publicField(rendererFeatures, "tailMode"))) {
                    return Step.Result.fail("Ears renderer lookup returned the wrong features: "
                            + rendererFeatures);
                }
                if (!earsRendererLayerAttached(playerRenderer)) {
                    return Step.Result.fail("Ears did not attach its feature renderer to the player");
                }
                Class<?> featuresClass = Class.forName(
                        "com.unascribed.ears.api.features.EarsFeatures");
                Object publicStorage = featuresClass.getMethod("getById", UUID.class)
                        .invoke(null, playerId);
                if (publicStorage == null || EarsCompatIntegration.isDisabledResult(publicStorage)) {
                    return Step.Result.fail("Ears public feature storage lacks the Quick Skin player");
                }
            } catch (ReflectiveOperationException exception) {
                return Step.Result.fail("could not inspect Ears public storage: " + concise(exception));
            }
            return activeSkinAssertion("Quick Skin rendered one complete saturated Ears skin "
                    + "across head, torso, both arms, and both legs; its authored magic pixels "
                    + "parsed " + publicField(detectedFeatures, "earMode") + " ears and a "
                    + publicField(detectedFeatures, "tailMode")
                    + " tail, attached Ears' feature layer, and published them to its renderer");
        }

        /**
         * Exercise the same public lookup the installed Ears renderer calls. Ears accepted the
         * player entity through 1.21.1, then moved the lookup to PlayerRenderState. Build that state
         * through the live player renderer so this remains a renderer-path assertion rather than a
         * version-specific storage check.
         */
        private Object rendererFeatures(Object playerRenderer)
                throws ReflectiveOperationException {
            Method incompatibleLookup = null;
            Class<?> incompatibleOwner = null;
            for (String className : new String[] {
                    "com.unascribed.ears.EarsLayerRenderer",
                    "com.unascribed.ears.EarsMod"
            }) {
                Class<?> earsRenderer;
                try {
                    earsRenderer = Class.forName(className);
                } catch (ClassNotFoundException ignored) {
                    continue;
                }
                for (Method method : earsRenderer.getMethods()) {
                    if (!"getEarsFeatures".equals(method.getName())
                            || !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                            || method.getParameterCount() != 1) {
                        continue;
                    }
                    incompatibleLookup = method;
                    incompatibleOwner = earsRenderer;
                    Object argument = rendererLookupArgument(
                            method.getParameterTypes()[0], playerRenderer);
                    if (argument == null) continue;
                    method.setAccessible(true);
                    return method.invoke(null, argument);
                }
            }
            String parameter = incompatibleLookup == null
                    ? "missing getEarsFeatures"
                    : incompatibleOwner.getName() + ".getEarsFeatures("
                    + incompatibleLookup.getParameterTypes()[0].getName() + ")";
            throw new NoSuchMethodException(
                    "no renderer argument available for Ears lookup: " + parameter);
        }

        private Object rendererLookupArgument(Class<?> expectedType, Object playerRenderer)
                throws ReflectiveOperationException {
            if (expectedType.isInstance(minecraft.player)) return minecraft.player;

            // 1.21.2+ exposes EntityRenderer.createRenderState(entity, partialTick). Its erased
            // return type is EntityRenderState, so validate the returned instance instead of the
            // declared type. The method name is deliberately irrelevant on intermediary Fabric.
            for (Method method : playerRenderer.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || parameters.length != 2
                        || !parameters[0].isInstance(minecraft.player)
                        || parameters[1] != float.class
                        || method.getReturnType() == void.class
                        || method.getReturnType().isPrimitive()) {
                    continue;
                }
                Object candidate = method.invoke(playerRenderer, minecraft.player, 0.0f);
                if (expectedType.isInstance(candidate)) return candidate;
            }
            return null;
        }

        private boolean earsRendererLayerAttached(Object playerRenderer) {
            for (Class<?> type = playerRenderer.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    boolean directLayer = isEarsRendererClass(field.getType());
                    if (!directLayer && !Iterable.class.isAssignableFrom(field.getType())) continue;
                    try {
                        if (!field.trySetAccessible()) continue;
                        Object value = field.get(playerRenderer);
                        if (isEarsRenderer(value)) return true;
                        if (value instanceof Iterable<?> candidates) {
                            for (Object candidate : candidates) {
                                if (isEarsRenderer(candidate)) return true;
                            }
                        }
                    } catch (IllegalAccessException ignored) {
                    }
                }
            }
            return false;
        }

        private static boolean isEarsRenderer(Object value) {
            return value != null && isEarsRendererClass(value.getClass());
        }

        private static boolean isEarsRendererClass(Class<?> type) {
            String name = type.getName();
            return "com.unascribed.ears.EarsFeatureRenderer".equals(name)
                    || "com.unascribed.ears.EarsLayerRenderer".equals(name);
        }

        private static String publicField(Object value, String name) {
            try {
                return String.valueOf(value.getClass().getField(name).get(value));
            } catch (ReflectiveOperationException exception) {
                return "<missing>";
            }
        }
    }

    final class SkinLayersFeature extends BaseFeature {
        private volatile Object flatLocation;
        private volatile Object raisedLocation;
        private volatile boolean disconnectRequested;
        private volatile int previewReadinessPolls;

        SkinLayersFeature(Minecraft minecraft) {
            super(minecraft);
        }

        @Override
        public void prepareBaseline() {
            previewReadinessPolls = 0;
            try {
                importAndApply(TestAssets.makeSubtleOverlaySkin());
                flatLocation = appearances.getSkinLocation(playerId);
                if (minecraft.getConnection() == null) {
                    failure = "3D Skin Layers fixture has no live connection to close";
                    return;
                }
                disconnectRequested = true;
                minecraft.getConnection().getConnection().disconnect(
                        Component.literal("Quick Skin E2E: open offline 3D preview"));
            } catch (Exception exception) {
                failure = "flat 3D-layer fixture failed: " + concise(exception);
            }
        }

        @Override
        public boolean baselineReady() {
            return failure == null && ensureOfflineMenu() && menuPreviewReady(flatLocation);
        }

        @Override
        public Step.Result assertBaseline() {
            if (failure != null) return Step.Result.fail(failure);
            if (!SkinLayers3DIntegration.isAvailable()) {
                return Step.Result.fail("3D Skin Layers mesh API is unavailable");
            }
            String alignmentFailure = previewOuterLayerAlignmentFailure();
            if (alignmentFailure != null) {
                return Step.Result.fail(alignmentFailure);
            }
            if (!meshCacheContains(flatLocation) || !manualRenderObserved()) {
                return Step.Result.fail(
                        "Quick Skin's control preview did not execute the 3D mesh bridge");
            }
            return Step.Result.pass("Quick Skin menu rendered the subdued-overlay control "
                    + "as pose-aligned meshes through 3D Skin Layers' API at " + flatLocation);
        }

        @Override
        public void applyQuickSkinFeature() {
            previewReadinessPolls = 0;
            try {
                importAndApply(TestAssets.makeRaisedOverlaySkin());
                raisedLocation = appearances.getSkinLocation(playerId);
                VanillaShim.setScreen(minecraft, new PlayerSkinMenuScreen(null));
            } catch (Exception exception) {
                failure = "raised 3D-layer fixture failed: " + concise(exception);
            }
        }

        @Override
        public boolean quickSkinFeatureReady() {
            return failure == null
                    && raisedLocation != null
                    && !raisedLocation.equals(flatLocation)
                    && ensureOfflineMenu()
                    && menuPreviewReady(raisedLocation);
        }

        @Override
        public Step.Result assertQuickSkinFeature() {
            if (failure != null) return Step.Result.fail(failure);
            String alignmentFailure = previewOuterLayerAlignmentFailure();
            if (alignmentFailure != null) {
                return Step.Result.fail(alignmentFailure);
            }
            if (!meshCacheContains(raisedLocation) || !manualRenderObserved()) {
                return Step.Result.fail("raised overlay never reached the third-party mesh renderer");
            }
            return Step.Result.pass("Quick Skin preview created and rendered 3D meshes for the "
                    + "bright raised hat, jacket, sleeves and trouser overlays at " + raisedLocation);
        }

        private boolean menuPreviewReady(Object expectedLocation) {
            Screen current = VanillaShim.currentScreen(minecraft);
            boolean screenReady = current instanceof PlayerSkinMenuScreen;
            boolean scaleReady = VanillaShim.guiScale(minecraft)
                    == GuiScaleManager.getOptimalMenuScale();
            boolean meshReady = meshCacheContains(expectedLocation);
            boolean renderReady = manualRenderObserved();
            PlayerWidget widget = screenReady
                    ? previewWidget((PlayerSkinMenuScreen) current) : null;
            Object widgetTexture = widget == null ? null : previewTexture(widget);
            if (++previewReadinessPolls == 1 || previewReadinessPolls % 100 == 0) {
                E2ELog.info("3D preview readiness: screen="
                        + (current == null ? "<none>" : current.getClass().getName())
                        + ", texture=" + expectedLocation
                        + ", widget=" + (widget == null ? "<missing>" : widget.visible)
                        + ", widgetTexture=" + widgetTexture
                        + ", localTexture=" + localTextureDimensions(expectedLocation)
                        + ", guiScale=" + VanillaShim.guiScale(minecraft)
                        + "/" + GuiScaleManager.getOptimalMenuScale()
                        + ", mesh=" + meshReady + ", rendered=" + renderReady);
            }
            if (!screenReady || expectedLocation == null || !scaleReady
                    || widget == null || !widget.visible
                    || !expectedLocation.equals(widgetTexture)
                    || !meshReady || !renderReady) {
                return false;
            }
            String alignmentFailure = previewOuterLayerAlignmentFailure();
            if (alignmentFailure != null) {
                failure = alignmentFailure;
                E2ELog.warn(alignmentFailure);
                return true;
            }
            return true;
        }

        /**
         * Fail before visual review when a modern child overlay carries its parent's pose locally.
         * Minecraft 1.21.6 moved these parts below their matching body parts, so a copied parent
         * pivot deterministically proves the same detached/splayed geometry that the screenshot
         * contract forbids.
         */
        private static String previewOuterLayerAlignmentFailure() {
            String runtimeVersion = System.getProperty("quickskin.e2e.version", "");
            if (!usesChildOuterLayerHierarchy(runtimeVersion)) {
                return null;
            }
            try {
                Field modelField = PlayerModelRenderer.class.getDeclaredField("classicModel");
                modelField.setAccessible(true);
                Object model = modelField.get(null);
                if (model == null) {
                    return "Quick Skin's classic preview model is unavailable for alignment proof";
                }
                for (String childName : new String[] {
                        "hat", "jacket", "left_sleeve", "right_sleeve",
                        "left_pants", "right_pants"}) {
                    ModelPart part = findModelChild(model, childName);
                    if (part == null) {
                        return "Quick Skin preview has no child ModelPart " + childName;
                    }
                    if (!part.storePose().equals(part.getInitialPose())) {
                        return "Quick Skin preview outer part " + childName
                                + " carries a non-local pose: current=" + part.storePose()
                                + ", baked=" + part.getInitialPose();
                    }
                }
                return null;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return "could not prove Quick Skin preview outer-layer alignment: "
                        + concise(exception);
            }
        }

        /**
         * Finds a baked child by its stable model-tree key. Minecraft member names are remapped in
         * packaged Fabric clients, so string-reflecting fields such as {@code leftSleeve} would
         * inspect development names that do not exist at runtime.
         */
        private static ModelPart findModelChild(Object model, String childName)
                throws IllegalAccessException {
            for (Field field : model.getClass().getFields()) {
                Object value = field.get(model);
                if (!(value instanceof ModelPart parent)) {
                    continue;
                }
                try {
                    return parent.getChild(childName);
                } catch (java.util.NoSuchElementException ignored) {
                }
            }
            return null;
        }

        private static boolean usesChildOuterLayerHierarchy(String runtimeVersion) {
            if (runtimeVersion.startsWith("26.")) {
                return true;
            }
            String prefix = "1.21.";
            if (!runtimeVersion.startsWith(prefix)) {
                return false;
            }
            try {
                return Integer.parseInt(runtimeVersion.substring(prefix.length())) >= 6;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        private boolean ensureOfflineMenu() {
            if (!disconnectRequested || minecraft.player != null || minecraft.level != null
                    || minecraft.getConnection() != null) {
                return false;
            }
            if (!(VanillaShim.currentScreen(minecraft) instanceof PlayerSkinMenuScreen)) {
                VanillaShim.setScreen(minecraft, new PlayerSkinMenuScreen(null));
                return false;
            }
            return true;
        }

        private PlayerWidget previewWidget(PlayerSkinMenuScreen screen) {
            try {
                Field panelField = PlayerSkinMenuScreen.class.getDeclaredField("playerPreviewPanel");
                panelField.setAccessible(true);
                Object panel = panelField.get(screen);
                Method widgetMethod = panel.getClass().getMethod("getPlayerWidget");
                return widgetMethod.invoke(panel) instanceof PlayerWidget widget ? widget : null;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                failure = "could not inspect Quick Skin preview widget: " + concise(exception);
                return null;
            }
        }

        private Object previewTexture(PlayerWidget widget) {
            try {
                Field previewData = PlayerWidget.class.getDeclaredField("previewData");
                previewData.setAccessible(true);
                Object data = previewData.get(widget);
                return data.getClass().getMethod("getSkinLocation").invoke(data);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                failure = "could not inspect Quick Skin preview texture: " + concise(exception);
                return null;
            }
        }

        private static String localTextureDimensions(Object expectedLocation) {
            if (expectedLocation == null) return "<null>";
            Object image = null;
            try {
                Method loader = SkinLayers3DIntegration.class.getDeclaredMethod(
                        "getQuickSkinLocalTexture", expectedLocation.getClass());
                loader.setAccessible(true);
                image = loader.invoke(null, expectedLocation);
                return image instanceof NativeImage nativeImage
                        ? nativeImage.getWidth() + "x" + nativeImage.getHeight()
                        : "<unreadable>";
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return "<probe-failed:" + concise(exception) + ">";
            } finally {
                if (image instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        private static boolean meshCacheContains(Object expectedLocation) {
            if (expectedLocation == null) return false;
            try {
                Field cacheField = SkinLayers3DIntegration.class.getDeclaredField("MESH_CACHE");
                cacheField.setAccessible(true);
                Object value = cacheField.get(null);
                if (!(value instanceof Map<?, ?> cache)) return false;
                for (Object key : cache.keySet()) {
                    Method location = key.getClass().getDeclaredMethod("skinLocation");
                    location.setAccessible(true);
                    if (expectedLocation.equals(location.invoke(key))) return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            return false;
        }

        private static boolean manualRenderObserved() {
            for (String fieldName : new String[] {
                    "injectedPreviewSuccessLogged",
                    "immediateRenderSuccessLogged",
                    "deferredAttachmentSuccessLogged"}) {
                try {
                    Field field = SkinLayers3DIntegration.class.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    if (field.get(null)
                            instanceof java.util.concurrent.atomic.AtomicBoolean observed
                            && observed.get()) {
                        return true;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
            return false;
        }
    }

    final class CustomNpcsFeature extends BaseFeature {
        private volatile Entity npc;

        CustomNpcsFeature(Minecraft minecraft) {
            super(minecraft);
        }

        @Override
        public void prepareBaseline() {
            npc = findCustomNpc();
            holdNpcView();
        }

        @Override
        public boolean baselineReady() {
            npc = findCustomNpc();
            return npc != null && holdNpcView();
        }

        @Override
        public Step.Result assertBaseline() {
            npc = findCustomNpc();
            if (npc == null) {
                return Step.Result.fail("dedicated server supplied no real CustomNPC entity");
            }
            var type = BuiltInRegistries.ENTITY_TYPE.getKey(npc.getType());
            return Step.Result.pass("real server-owned " + type + " entity is rendered beside "
                    + "Quick Skin's untouched local player");
        }

        @Override
        public void applyQuickSkinFeature() {
            try {
                importAndApply(TestAssets.makeClassicSkin());
            } catch (Exception exception) {
                failure = "CustomNPCs skin fixture failed: " + concise(exception);
            }
            holdNpcView();
        }

        @Override
        public boolean quickSkinFeatureReady() {
            npc = findCustomNpc();
            return failure == null && npc != null && activeSkinReady() && holdNpcView();
        }

        @Override
        public Step.Result assertQuickSkinFeature() {
            if (failure != null) return Step.Result.fail(failure);
            npc = findCustomNpc();
            if (npc == null) return Step.Result.fail("CustomNPC disappeared after skin apply");
            var actual = appearances.getSkinLocation(playerId);
            Boolean activeConflict = detectsSkinConflict(actual);
            if (activeConflict == null) return Step.Result.fail(failure);
            if (activeConflict) {
                return Step.Result.fail("CustomNPCs integration rejected Quick Skin's active texture");
            }
            Object foreign = locationLike(
                    actual, "minecraft", "textures/entity/player/wide/steve.png");
            if (foreign == null) return Step.Result.fail(failure);
            Boolean foreignConflict = detectsSkinConflict(foreign);
            if (foreignConflict == null) return Step.Result.fail(failure);
            if (!foreignConflict) {
                return Step.Result.fail("CustomNPCs conflict tracker did not retain the applied skin");
            }
            var type = BuiltInRegistries.ENTITY_TYPE.getKey(npc.getType());
            return activeSkinAssertion("real " + type + " content remains rendered while the "
                    + "CustomNPCs bridge retains Quick Skin's renderer-facing texture");
        }

        private Boolean detectsSkinConflict(Object location) {
            if (location == null) {
                failure = "CustomNPCs conflict probe received no renderer texture";
                return null;
            }
            try {
                for (Method method : CustomNPCsIntegration.class.getMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (!"detectSkinConflict".equals(method.getName())
                            || parameters.length != 2
                            || parameters[0] != UUID.class
                            || !parameters[1].isInstance(location)
                            || method.getReturnType() != boolean.class) {
                        continue;
                    }
                    return (Boolean) method.invoke(null, playerId, location);
                }
                failure = "CustomNPCs conflict probe found no compatible texture signature";
            } catch (ReflectiveOperationException | RuntimeException exception) {
                failure = "CustomNPCs conflict probe failed: " + concise(exception);
            }
            return null;
        }

        private Object locationLike(Object example, String namespace, String path) {
            if (example == null) {
                failure = "CustomNPCs conflict probe cannot derive a foreign texture type";
                return null;
            }
            for (String factory : new String[] {"tryBuild", "fromNamespaceAndPath"}) {
                try {
                    Object location = example.getClass()
                            .getMethod(factory, String.class, String.class)
                            .invoke(null, namespace, path);
                    if (location != null) return location;
                } catch (NoSuchMethodException ignored) {
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    failure = "CustomNPCs foreign texture fixture failed: " + concise(exception);
                    return null;
                }
            }
            try {
                Object location = example.getClass()
                        .getConstructor(String.class, String.class)
                        .newInstance(namespace, path);
                if (location != null) return location;
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException | RuntimeException exception) {
                failure = "CustomNPCs foreign texture fixture failed: " + concise(exception);
                return null;
            }
            failure = "CustomNPCs conflict probe found no texture factory on "
                    + example.getClass().getName();
            return null;
        }

        private Entity findCustomNpc() {
            if (minecraft.level == null) return null;
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                var type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                if (type != null && "customnpcs".equals(type.getNamespace())) return entity;
            }
            return null;
        }

        private boolean holdNpcView() {
            if (minecraft.player == null || minecraft.options == null) return false;
            Entity subject = npc != null ? npc : findCustomNpc();
            if (subject == null) return false;
            VanillaShim.setScreen(minecraft, null);
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            VanillaShim.setFieldOfView(minecraft, 65);
            double dx = subject.getX() - minecraft.player.getX();
            double dz = subject.getZ() - minecraft.player.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance < 1.5 || distance > 10.0) return false;
            float towardNpc = (float) Math.toDegrees(Math.atan2(-dx, dz));
            DefaultSkinEvidenceView.pinStandingPose(minecraft.player, towardNpc - 18.0f);
            return true;
        }
    }

    final class EssentialFeature extends BaseFeature {
        EssentialFeature(Minecraft minecraft) {
            super(minecraft);
        }

        @Override
        public void prepareBaseline() {
            openTitle();
        }

        @Override
        public boolean baselineReady() {
            pinCursorAwayFromEssentialWidgets();
            return failure == null && essentialTitleFailure(false) == null;
        }

        @Override
        public Step.Result assertBaseline() {
            if (failure != null) return Step.Result.fail(failure);
            String problem = essentialTitleFailure(false);
            return problem == null
                    ? Step.Result.pass("Essential owns the title player model; Quick Skin suppresses "
                    + "its duplicate preview and places exactly one action beside Essential's widgets")
                    : Step.Result.fail(problem);
        }

        @Override
        public void applyQuickSkinFeature() {
            try {
                importAndApply(TestAssets.makeClassicSkin());
                EssentialCompatIntegration.registerMenuAppearance();
                openTitle();
            } catch (Exception exception) {
                failure = "Essential skin fixture failed: " + concise(exception);
            }
        }

        @Override
        public boolean quickSkinFeatureReady() {
            pinCursorAwayFromEssentialWidgets();
            return failure == null && essentialTitleFailure(true) == null;
        }

        @Override
        public Step.Result assertQuickSkinFeature() {
            if (failure != null) return Step.Result.fail(failure);
            String problem = essentialTitleFailure(true);
            if (problem != null) return Step.Result.fail(problem);
            return Step.Result.pass("Essential's title model owns the layout while Quick Skin "
                    + "registers local_skin:" + skinHash + " and keeps its single action icon");
        }

        private void openTitle() {
            VanillaShim.setScreen(minecraft, new TitleScreen());
            pinCursorAwayFromEssentialWidgets();
        }

        private void pinCursorAwayFromEssentialWidgets() {
            try {
                Object window = minecraft.getWindow();
                for (String accessor : new String[] {
                        "getWindow", "handle", "method_4490", "m_85439_"
                }) {
                    try {
                        Object value = window.getClass().getMethod(accessor).invoke(window);
                        if (value instanceof Number number) {
                            org.lwjgl.glfw.GLFW.glfwSetCursorPos(
                                    number.longValue(), 1.0, 1.0);
                            return;
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                failure = "Essential cursor probe found no native window handle";
            } catch (ReflectiveOperationException | RuntimeException exception) {
                failure = "Essential cursor probe failed: " + concise(exception);
            }
        }

        private String essentialTitleFailure(boolean requireSkin) {
            Screen screen = VanillaShim.currentScreen(minecraft);
            if (!(screen instanceof TitleScreen)) return "Essential title screen is not open";
            if (EssentialCompatIntegration.findBottomEssentialWidget(screen) == null) {
                return "Essential title widgets were not detected";
            }
            int playerWidgets = 0;
            int quickSkinActions = 0;
            for (GuiEventListener child : screen.children()) {
                if (child instanceof PlayerWidget) playerWidgets++;
                if (child instanceof IconActionButton) quickSkinActions++;
            }
            if (playerWidgets != 0) {
                return "Quick Skin rendered " + playerWidgets
                        + " duplicate PlayerWidget instances beside Essential";
            }
            if (quickSkinActions != 1) {
                return "Essential title screen has " + quickSkinActions
                        + " Quick Skin actions; expected exactly one";
            }
            if (!requireSkin) return null;
            UUID profileId = minecraft.getUser() == null
                    ? null : minecraft.getUser().getProfileId();
            PlayerAppearance appearance = profileId == null
                    ? null : appearances.getAppearance(profileId);
            String expected = "local_skin:" + skinHash;
            return appearance != null && expected.equals(appearance.getSkinId())
                    ? null : "Essential menu appearance did not retain " + expected;
        }
    }

    final class ReplayModFeature extends BaseFeature {
        // ReplayMod freezes Minecraft's global timer at EOF. Keep enough recorded tail for the
        // applied screenshot, Retina re-grabs, and the harness screenshot-flush window.
        private static final long MIN_RECORDING_DURATION_MS = 15_000L;
        private static final long MIN_ACKNOWLEDGED_RECORDING_TAIL_MS = 12_000L;
        private static final int MAX_ACKNOWLEDGED_RECORDING_TAIL_POLLS = 20 * 20;
        private static final long MISSING_RECORDED_PAYLOAD_GRACE_MS = 3_000L;
        private static final Object STARTUP_SCAN_LOCK = new Object();
        private static volatile Path protectedStartupScanPlaceholder;
        private static volatile Path protectedStartupScanMarker;
        private static volatile String startupScanProtectionFailure;

        private volatile Object packetListener;
        private volatile Path replayPath;
        private volatile Path finalizedReplayPath;
        private volatile Path startupScanPlaceholder;
        private volatile Path startupScanMarker;
        private volatile long lastReplaySize = -1L;
        private volatile int stableReplaySizePolls;
        private volatile boolean disconnectRequested;
        private volatile boolean replayStarted;
        private volatile boolean watcherStarted;
        private volatile boolean saveWaitLogged;
        private volatile boolean replayModeLogged;
        private volatile boolean recordedPayloadLogged;
        private volatile boolean serverEchoAcknowledged;
        private volatile long acknowledgedPayloadTimestampMs = -1L;
        private volatile int acknowledgedRecordingTailPolls;
        private volatile int replayPolls;
        private volatile UUID replayTarget;
        private volatile AbstractClientPlayer replayPlayer;

        ReplayModFeature(Minecraft minecraft) {
            super(minecraft);
            packetListener = currentPacketListener();
            replayPath = packetListener == null ? null : reflectedPath(packetListener, "outputPath");
            protectActiveRecordingFromStartupScan();
        }

        @Override
        public void prepareBaseline() {
            VanillaShim.setFieldOfView(minecraft, 55);
            try {
                importAndApply(TestAssets.makeClassicSkin());
            } catch (Exception exception) {
                failure = "ReplayMod skin fixture failed: " + concise(exception);
            }
            packetListener = currentPacketListener();
            replayPath = packetListener == null ? null : reflectedPath(packetListener, "outputPath");
            protectActiveRecordingFromStartupScan();
            holdFullBody();
        }

        @Override
        public boolean baselineReady() {
            packetListener = currentPacketListener();
            if (packetListener != null && replayPath == null) {
                replayPath = reflectedPath(packetListener, "outputPath");
            }
            protectActiveRecordingFromStartupScan();
            return failure == null
                    && activeSkinReady()
                    && packetListener != null
                    && replayPath != null
                    && reflectedLong(packetListener, "getCurrentDuration")
                    >= MIN_RECORDING_DURATION_MS
                    && holdFullBody();
        }

        @Override
        public Step.Result assertBaseline() {
            if (failure != null) return Step.Result.fail(failure);
            long duration = reflectedLong(packetListener, "getCurrentDuration");
            if (packetListener == null || replayPath == null
                    || duration < MIN_RECORDING_DURATION_MS) {
                return Step.Result.fail("ReplayMod did not actively record the Quick Skin session");
            }
            return activeSkinAssertion("ReplayMod is actively recording the real multiplayer "
                    + "connection (duration=" + duration + "ms, file="
                    + replayPath.getFileName() + ")");
        }

        @Override
        public void applyQuickSkinFeature() {
            if (minecraft.getConnection() == null) {
                failure = "cannot finalize ReplayMod recording without a live connection";
                return;
            }
            try {
                importAndApply(TestAssets.makeReplayAcknowledgedSkin());
                E2ELog.info("applied hash-distinct plaid ReplayMod fixture; waiting for recorded server echo");
            } catch (Exception exception) {
                failure = "ReplayMod acknowledged skin fixture failed: " + concise(exception);
                E2ELog.error(failure, exception);
            }
        }

        @Override
        public boolean quickSkinFeatureReady() {
            if (failure != null) return true;
            if (replayPath == null) return false;
            try {
                if (!disconnectRequested) {
                    if (!activeSkinReady()) return false;
                    if (minecraft.getConnection() == null) {
                        failure = "live ReplayMod connection closed before the recorded server echo";
                        return false;
                    }
                    if (!serverEchoAcknowledged) {
                        String expectedSkinId = "local_skin:" + skinHash;
                        if (!NetworkSyncService.getInstance().isLatestAppearanceAcknowledged(
                                playerId, expectedSkinId)) {
                            return false;
                        }
                        serverEchoAcknowledged = true;
                        acknowledgedPayloadTimestampMs =
                                reflectedLong(packetListener, "getCurrentDuration");
                        if (acknowledgedPayloadTimestampMs < 0L) {
                            failure = "could not locate the recorded server-echo timestamp";
                            return true;
                        }
                        E2ELog.info("received exact Quick Skin server echo at recording timestamp "
                                + acknowledgedPayloadTimestampMs
                                + "ms; retaining at least 12s of recorded packets");
                    }
                    long recordedDurationMs =
                            reflectedLong(packetListener, "getCurrentDuration");
                    if (recordedDurationMs < acknowledgedPayloadTimestampMs
                            + MIN_ACKNOWLEDGED_RECORDING_TAIL_MS) {
                        if (++acknowledgedRecordingTailPolls
                                >= MAX_ACKNOWLEDGED_RECORDING_TAIL_POLLS) {
                            failure = "ReplayMod did not retain "
                                    + MIN_ACKNOWLEDGED_RECORDING_TAIL_MS
                                    + "ms of recorded packets after the exact server echo";
                            return true;
                        }
                        return false;
                    }
                    disconnectRequested = true;
                    E2ELog.info("ReplayMod recording close requested for "
                            + replayPath.getFileName() + " with "
                            + (recordedDurationMs - acknowledgedPayloadTimestampMs)
                            + "ms recorded tail");
                    minecraft.getConnection().getConnection().disconnect(
                            Component.literal("Quick Skin E2E: finalize replay"));
                    return false;
                }
                if (!replayStarted) {
                    if (currentPacketListener() != null) {
                        if (!saveWaitLogged) {
                            saveWaitLogged = true;
                            E2ELog.info("waiting for ReplayMod's recording worker to close");
                        }
                        return false;
                    }
                    finalizedReplayPath = locateFinalizedReplay();
                    if (finalizedReplayPath == null) {
                        return false;
                    }
                    long size = Files.size(finalizedReplayPath);
                    if (size <= 0 || size != lastReplaySize) {
                        lastReplaySize = size;
                        stableReplaySizePolls = 0;
                        return false;
                    }
                    if (++stableReplaySizePolls < 12) return false;
                    cleanupStartupScanProtection();
                    VanillaShim.setScreen(minecraft, null);
                    ReplayModBridge.resetReplayEvidenceState();
                    E2ELog.info("starting finalized ReplayMod recording "
                            + finalizedReplayPath.getFileName() + " (" + size + " bytes)");
                    startReplay(finalizedReplayPath);
                    replayStarted = true;
                    return false;
                }

                replayPolls++;
                if (!ReplayModBridge.isInReplay()) return false;
                if (!replayModeLogged) {
                    replayModeLogged = true;
                    E2ELog.info("ReplayMod playback entered CameraEntity mode");
                }
                if (!watcherStarted) {
                    ReplayModBridge.startReplayPlayerWatcher();
                    watcherStarted = true;
                }
                replayTarget = ReplayModBridge.getTargetPlayerUUID();
                replayPlayer = ReplayModBridge.getPlayerByUUID(replayTarget);
                long playbackTimestampMs = currentReplayTimestamp();
                if (playbackTimestampMs < 0L) {
                    failure = "could not read ReplayMod's playback timestamp";
                    return true;
                }
                if (replayPolls == 1 || replayPolls % 20 == 0) {
                    PlayerAppearance observed = replayTarget == null
                            ? null : appearances.getAppearance(replayTarget);
                    E2ELog.info("ReplayMod readiness: playback=" + playbackTimestampMs
                            + "ms, recordedEcho=" + acknowledgedPayloadTimestampMs
                            + "ms, target=" + replayTarget
                            + ", player=" + (replayPlayer != null)
                            + ", intercepted=" + ReplayModBridge.getInterceptedPacketCount()
                            + ", applied=" + ReplayModBridge.hasSkinBeenApplied()
                            + ", skin=" + (observed == null ? null : observed.getSkinId()));
                }
                if (ReplayModBridge.getInterceptedPacketCount() <= 0
                        && acknowledgedPayloadTimestampMs >= 0L
                        && playbackTimestampMs >= acknowledgedPayloadTimestampMs
                        + MISSING_RECORDED_PAYLOAD_GRACE_MS) {
                    failure = "ReplayMod played " + MISSING_RECORDED_PAYLOAD_GRACE_MS
                            + "ms beyond the recorded server echo without traversing the "
                            + "Quick Skin replay mixin";
                    E2ELog.warn(failure);
                    return true;
                }
                if (replayPlayer == null) return false;
                PlayerAppearance replayAppearance = appearances.getAppearance(replayTarget);
                String expected = "local_skin:" + skinHash;
                if (!ReplayModBridge.hasSkinBeenApplied()
                        || ReplayModBridge.getInterceptedPacketCount() <= 0
                        || replayAppearance == null
                        || !expected.equals(replayAppearance.getSkinId())
                        || appearances.getSkinLocation(replayTarget) == null
                        || !String.valueOf(appearances.getSkinLocation(replayTarget))
                        .equals(VanillaShim.skinTexture(replayPlayer))) {
                    return false;
                }
                if (!recordedPayloadLogged) {
                    recordedPayloadLogged = true;
                    E2ELog.info("recorded Quick Skin payload intercepted for " + replayTarget);
                }
                spectateReplayPlayer(replayPlayer);
                minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                DefaultSkinEvidenceView.pinStandingPose(replayPlayer, 180.0f);
                return true;
            } catch (Exception exception) {
                failure = "ReplayMod playback setup failed: " + concise(exception);
                E2ELog.error(failure, exception);
                return false;
            }
        }

        @Override
        public Step.Result assertQuickSkinFeature() {
            if (failure != null) return Step.Result.fail(failure);
            if (!serverEchoAcknowledged) {
                return Step.Result.fail("live recording never received the exact server echo");
            }
            if (!ReplayModBridge.isInReplay() || replayPlayer == null || replayTarget == null) {
                return Step.Result.fail("ReplayMod playback has no recorded player target");
            }
            if (!ReplayModBridge.hasSkinBeenApplied()) {
                return Step.Result.fail("ReplayMod watcher did not apply the saved Quick Skin look");
            }
            int intercepted = ReplayModBridge.getInterceptedPacketCount();
            if (intercepted <= 0) {
                return Step.Result.fail("no recorded Quick Skin payload traversed the replay mixin");
            }
            PlayerAppearance state = appearances.getAppearance(replayTarget);
            String expected = "local_skin:" + skinHash;
            if (state == null || !expected.equals(state.getSkinId())) {
                return Step.Result.fail("recorded player does not retain " + expected);
            }
            String texture = VanillaShim.skinTexture(replayPlayer);
            if (!String.valueOf(appearances.getSkinLocation(replayTarget)).equals(texture)) {
                return Step.Result.fail("replay renderer texture disagrees with Quick Skin state");
            }
            return Step.Result.pass("ReplayMod recorded the exact live server acknowledgement, "
                    + "played the real recording, Quick Skin intercepted " + intercepted
                    + " recorded payload(s), targeted recorded player " + replayTarget
                    + " instead of CameraEntity, and rendered " + texture);
        }

        @Override
        public int appliedMinTicks() {
            return 1;
        }

        @Override
        public int appliedSettleTicks() {
            return 30;
        }

        @Override
        public int appliedTimeoutTicks() {
            return 20 * 90;
        }

        private static Object currentPacketListener() {
            try {
                Class<?> recording = Class.forName("com.replaymod.recording.ReplayModRecording");
                Object instance = recording.getField("instance").get(null);
                if (instance == null) return null;
                Object handler = recording.getMethod("getConnectionEventHandler").invoke(instance);
                return handler == null ? null
                        : handler.getClass().getMethod("getPacketListener").invoke(handler);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }

        static void protectStartupRecordingBeforeWorldJoin() {
            Object listener = currentPacketListener();
            Path outputPath = listener == null ? null : reflectedPath(listener, "outputPath");
            protectStartupRecording(outputPath);
        }

        /**
         * Quick Play can join while ReplayMod's post-startup scan is still pending. ReplayMod then
         * mistakes its own live {@code recording/*.mcpr.tmp} directory for an abandoned recording
         * and moves it out from under the writer. An empty, marked destination makes that one scan
         * skip the live directory; the scan removes the marker itself. This exists only in the
         * disposable E2E profile and is cleaned before playback if the scan already ran.
         */
        private void protectActiveRecordingFromStartupScan() {
            if (failure != null || replayPath == null) return;
            protectStartupRecording(replayPath);
            synchronized (STARTUP_SCAN_LOCK) {
                startupScanPlaceholder = protectedStartupScanPlaceholder;
                startupScanMarker = protectedStartupScanMarker;
                if (failure == null && startupScanProtectionFailure != null) {
                    failure = startupScanProtectionFailure;
                }
            }
        }

        private static void protectStartupRecording(Path outputPath) {
            if (outputPath == null) return;
            synchronized (STARTUP_SCAN_LOCK) {
                if (protectedStartupScanPlaceholder != null
                        || startupScanProtectionFailure != null) {
                    return;
                }
                Path recordingFolder = outputPath.getParent();
                if (recordingFolder == null || recordingFolder.getParent() == null
                        || !"recording".equals(String.valueOf(recordingFolder.getFileName()))) {
                    return;
                }
                Path activeTemporary = outputPath.resolveSibling(
                        outputPath.getFileName() + ".tmp");
                if (!Files.isDirectory(activeTemporary)) return;

                Path replayFolder = recordingFolder.getParent();
                Path placeholder = replayFolder.resolve(activeTemporary.getFileName());
                Path marker = replayFolder.resolve(outputPath.getFileName() + ".no_recover");
                if (Files.exists(placeholder) || Files.exists(marker)) return;
                try {
                    Files.createDirectories(placeholder);
                    Files.createFile(marker);
                    protectedStartupScanPlaceholder = placeholder;
                    protectedStartupScanMarker = marker;
                    E2ELog.info("protected active ReplayMod recording from its startup scan");
                } catch (Exception exception) {
                    try {
                        Files.deleteIfExists(marker);
                        Files.deleteIfExists(placeholder);
                    } catch (Exception ignored) {
                    }
                    startupScanProtectionFailure =
                            "could not protect ReplayMod's active recording: "
                                    + concise(exception);
                }
            }
        }

        private Path locateFinalizedReplay() {
            Path recordingFolder = replayPath.getParent();
            if (recordingFolder != null && recordingFolder.getParent() != null) {
                Path renamed = recordingFolder.getParent().resolve(replayPath.getFileName());
                if (Files.isRegularFile(renamed)) return renamed;
            }
            return Files.isRegularFile(replayPath) ? replayPath : null;
        }

        private void cleanupStartupScanProtection() {
            synchronized (STARTUP_SCAN_LOCK) {
                try {
                    if (startupScanMarker != null) Files.deleteIfExists(startupScanMarker);
                    if (startupScanPlaceholder != null) {
                        Files.deleteIfExists(startupScanPlaceholder);
                    }
                    if (startupScanMarker != null
                            && startupScanMarker.equals(protectedStartupScanMarker)) {
                        protectedStartupScanMarker = null;
                        protectedStartupScanPlaceholder = null;
                    }
                } catch (Exception exception) {
                    E2ELog.warn("could not clean ReplayMod startup-scan guard: "
                            + concise(exception));
                }
            }
        }

        private static Path reflectedPath(Object owner, String fieldName) {
            try {
                Field field = owner.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(owner) instanceof Path path ? path : null;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }

        private static long reflectedLong(Object owner, String methodName) {
            if (owner == null) return -1L;
            try {
                Object value = owner.getClass().getMethod(methodName).invoke(owner);
                return value instanceof Number number ? number.longValue() : -1L;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return -1L;
            }
        }

        private static Object replayHandler() throws ReflectiveOperationException {
            Class<?> replay = Class.forName("com.replaymod.replay.ReplayModReplay");
            Object instance = replay.getField("instance").get(null);
            return instance == null ? null : replay.getMethod("getReplayHandler").invoke(instance);
        }

        private static long currentReplayTimestamp() throws ReflectiveOperationException {
            Object handler = replayHandler();
            if (handler == null) return -1L;
            Object sender = handler.getClass().getMethod("getReplaySender").invoke(handler);
            if (sender == null) return -1L;
            Object timestamp = sender.getClass().getMethod("currentTimeStamp").invoke(sender);
            return timestamp instanceof Number number ? number.longValue() : -1L;
        }

        private static void startReplay(Path path) throws ReflectiveOperationException {
            Class<?> replay = Class.forName("com.replaymod.replay.ReplayModReplay");
            Object instance = replay.getField("instance").get(null);
            if (instance == null) throw new IllegalStateException("ReplayModReplay.instance is null");
            try {
                path = path.toRealPath();
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("finalized replay path is unavailable", exception);
            }
            replay.getMethod("startReplay", java.io.File.class).invoke(instance, path.toFile());
        }

        private static void spectateReplayPlayer(AbstractClientPlayer player)
                throws ReflectiveOperationException {
            Object handler = replayHandler();
            if (handler == null) throw new IllegalStateException("ReplayHandler is null");
            Method spectate = null;
            for (Method method : handler.getClass().getMethods()) {
                if ("spectateEntity".equals(method.getName()) && method.getParameterCount() == 1) {
                    spectate = method;
                    break;
                }
            }
            if (spectate == null) throw new NoSuchMethodException("ReplayHandler.spectateEntity");
            spectate.invoke(handler, player);
        }

        /**
         * ReplayMod is an explicit 1.20.1 compatibility lane, while this shared E2E source must
         * still compile for versions where the production bridge is intentionally absent. Resolve
         * the real bridge only when that lane runs; its public methods remain the tested surface.
         */
        private static final class ReplayModBridge {
            private static final String CLASS_NAME =
                    "com.quickskin.mod.client.compat.ReplayModHelper";

            private ReplayModBridge() {}

            static void resetReplayEvidenceState() {
                invoke("resetReplayEvidenceState", new Class<?>[0]);
            }

            static boolean isInReplay() {
                return Boolean.TRUE.equals(invoke("isInReplay", new Class<?>[0]));
            }

            static void startReplayPlayerWatcher() {
                invoke("startReplayPlayerWatcher", new Class<?>[0]);
            }

            static UUID getTargetPlayerUUID() {
                Object value = invoke("getTargetPlayerUUID", new Class<?>[0]);
                return value instanceof UUID uuid ? uuid : null;
            }

            static AbstractClientPlayer getPlayerByUUID(UUID uuid) {
                Object value = invoke("getPlayerByUUID", new Class<?>[] {UUID.class}, uuid);
                return value instanceof AbstractClientPlayer player ? player : null;
            }

            static int getInterceptedPacketCount() {
                Object value = invoke("getInterceptedPacketCount", new Class<?>[0]);
                return value instanceof Number number ? number.intValue() : 0;
            }

            static boolean hasSkinBeenApplied() {
                return Boolean.TRUE.equals(invoke("hasSkinBeenApplied", new Class<?>[0]));
            }

            private static Object invoke(String name, Class<?>[] parameterTypes, Object... args) {
                try {
                    Class<?> bridge = Class.forName(CLASS_NAME);
                    return bridge.getMethod(name, parameterTypes).invoke(null, args);
                } catch (ReflectiveOperationException | LinkageError exception) {
                    throw new IllegalStateException("ReplayMod bridge method is unavailable: "
                            + name, exception);
                }
            }
        }

    }

    @FunctionalInterface
    interface FixtureFactory {
        Path create() throws Exception;
    }
}
