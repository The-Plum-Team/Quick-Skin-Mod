package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.TextureQuality;
//? if <26.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//?} else {
//?}
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
//? if <1.21.11 {
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
//?} else if <26.1 {
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else if <26.2 {
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
//?}
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional 3D Skin Layers bridge.
 *
 * <p>Third-party classes are resolved only after their class resource is present. Mesh creation,
 * immediate rendering, deferred ModelPart injection, and player refresh are independent
 * capabilities so drift in one optional surface does not disable the others.</p>
 */
public final class SkinLayers3DIntegration {
    private static final Logger SKIN_LAYERS_LOG = LoggerFactory.getLogger("QuickSkin-SkinLayers3D");
    private static final String BASE_CLASS = "dev.tr7zw.skinlayers.SkinLayersModBase";
    private static final String BASE_RESOURCE = "dev/tr7zw/skinlayers/SkinLayersModBase.class";
    private static final String API_CLASS = "dev.tr7zw.skinlayers.api.SkinLayersAPI";
    private static final String MESH_HELPER_CLASS = "dev.tr7zw.skinlayers.api.MeshHelper";
    private static final String MESH_CLASS = "dev.tr7zw.skinlayers.api.Mesh";
    private static final long CPM_MODEL_PROBE_TTL_NANOS = 500_000_000L;
//? if <26.1 {
    private static final int MAX_MESH_CACHE_ENTRIES = 512;
//?} else if <26.2 {
//?} else {
    private static final int MAX_MESH_CACHE_ENTRIES = 512;
//?}

    private static final Object MESH_INIT_LOCK = new Object();
    private static final Object REFRESH_INIT_LOCK = new Object();
//? if <26.2 {
    private static final Object INJECTED_PREVIEW_INIT_LOCK = new Object();
//?} else {
//?}
//? if <26.1 {
//?} else if <26.2 {
//?} else {
    private static final Object DEFERRED_INIT_LOCK = new Object();

//?}
    private static final ConcurrentMap<MeshCacheKey, PlayerMeshes> MESH_CACHE = new ConcurrentHashMap<>();

    private static volatile CapabilityState meshCapability = CapabilityState.UNCHECKED;
    private static volatile CapabilityState refreshCapability = CapabilityState.UNCHECKED;
//? if <26.2 {
    private static volatile CapabilityState injectedPreviewCapability = CapabilityState.UNCHECKED;
//?} else {
//?}
//? if <26.2 {
//?} else {
    private static volatile CapabilityState deferredCapability = CapabilityState.UNCHECKED;
//?}

    private static Object configInstance;
    private static Object meshHelperInstance;
    private static Method create3DMeshMethod;
    private static boolean create3DMeshSupportsMirror;
//? if <26.2 {
    private static Method meshRenderMethod;
    private static Method meshSetPositionMethod;
//?} else {
//?}

    private static Field headVoxelSizeField;
    private static Field bodyVoxelWidthSizeField;
    private static Field baseVoxelSizeField;
    private static Field enableHatField;
    private static Field enableJacketField;
    private static Field enableLeftSleeveField;
    private static Field enableRightSleeveField;
    private static Field enableLeftPantsField;
    private static Field enableRightPantsField;

    private static Field refreshInstanceField;
    private static Method refreshMethod;
    private static boolean refreshMethodIsStatic;

//? if <26.2 {
    private static Class<?> previewModelPartInjectorClass;
    private static Method setPreviewInjectedMeshMethod;
    private static Object previewHeadOffsetProvider;
    private static Object previewBodyOffsetProvider;
    private static Object previewLeftArmOffsetProvider;
    private static Object previewLeftArmSlimOffsetProvider;
    private static Object previewRightArmOffsetProvider;
    private static Object previewRightArmSlimOffsetProvider;
    private static Object previewLeftLegOffsetProvider;
    private static Object previewRightLegOffsetProvider;

//?} else {
//?}
//? if <26.1 {
//?} else if <26.2 {
//?} else {
    private static Class<?> modelPartInjectorClass;
    private static Method setInjectedMeshMethod;
    private static Object headOffsetProvider;
    private static Object bodyOffsetProvider;
    private static Object leftArmOffsetProvider;
    private static Object leftArmSlimOffsetProvider;
    private static Object rightArmOffsetProvider;
    private static Object rightArmSlimOffsetProvider;
    private static Object leftLegOffsetProvider;
    private static Object rightLegOffsetProvider;

//?}
    private static final AtomicBoolean meshCapabilityLogged = new AtomicBoolean();
    private static final AtomicBoolean meshCapabilityFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean meshCreationFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean renderFailureLogged = new AtomicBoolean();
//? if <26.2 {
    private static final AtomicBoolean immediateRenderSuccessLogged = new AtomicBoolean();
//?} else {
//?}
//? if <26.2 {
    private static final AtomicBoolean injectedPreviewCapabilityLogged = new AtomicBoolean();
    private static final AtomicBoolean injectedPreviewCapabilityFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean injectedPreviewAttachmentFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean injectedPreviewSuccessLogged = new AtomicBoolean();
//?} else {
//?}
    private static final AtomicBoolean configReadFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean refreshCapabilityLogged = new AtomicBoolean();
    private static final AtomicBoolean refreshCapabilityFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean refreshInvocationFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean refreshInstanceMissingLogged = new AtomicBoolean();
    private static final AtomicBoolean cpmSuppressionLogged = new AtomicBoolean();
    private static final AtomicBoolean cpmProbeFailureLogged = new AtomicBoolean();
//? if <26.2 {
//?} else {
    private static final AtomicBoolean deferredCapabilityLogged = new AtomicBoolean();
    private static final AtomicBoolean deferredCapabilityFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean deferredAttachmentFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean deferredAttachmentSuccessLogged = new AtomicBoolean();
//?}

    private static volatile boolean cachedCpmLocalModelActive;
    private static volatile long nextCpmModelProbeNanos;

    private SkinLayers3DIntegration() {
    }

    /** Returns whether the optional mesh/config surface is compatible. */
    public static boolean isAvailable() {
        return ensureMeshCapability();
    }

//? if <1.21.11 {
    /**
     * Replaces the six flat outer parts with the meshes supplied by 3D Skin Layers for one
     * preview draw. This is the same public injection seam used by the mod's compatibility
     * renderer, so every mesh inherits the exact pose of its corresponding outer part.
     */
    public static boolean prepareInjectedPreview(
            PlayerModel<?> model, ResourceLocation skinLocation, boolean thinArms) {
//?} else {
    /**
     * Replaces the six flat outer parts with the meshes supplied by 3D Skin Layers for one
     * preview draw. This is the same public injection seam used by the mod's compatibility
     * renderer, so every mesh inherits the exact pose of its corresponding outer part.
     */
    public static boolean prepareInjectedPreview(
            PlayerModel model, Identifier skinLocation, boolean thinArms) {
//?}
//? if <26.2 {
        if (model == null || !ensureInjectedPreviewCapability()) {
            return false;
        }

        ModelPart[] overlayParts = {
                model.hat,
                model.jacket,
                model.leftSleeve,
                model.rightSleeve,
                model.leftPants,
                model.rightPants
        };
        try {
            validatePreviewOverlayParts(overlayParts);
            clearPreviewOverlayParts(overlayParts);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            bestEffortClearPreviewOverlayParts(overlayParts);
            logInjectedPreviewAttachmentFailure(
                    "3D Skin Layers could not reset Quick Skin's preview overlay parts", exception);
            return false;
        }

        if (skinLocation == null || shouldSuppressManualLayers()) {
            return false;
        }
        PlayerMeshes meshes = getOrCreateMeshes(skinLocation, thinArms);
        if (meshes == null || !meshes.isValid()) {
            return false;
        }

        Object[] providers = {
                previewHeadOffsetProvider,
                previewBodyOffsetProvider,
                thinArms ? previewLeftArmSlimOffsetProvider : previewLeftArmOffsetProvider,
                thinArms ? previewRightArmSlimOffsetProvider : previewRightArmOffsetProvider,
                previewLeftLegOffsetProvider,
                previewRightLegOffsetProvider
        };
        Object[] meshValues = {
                meshes.headMesh,
                meshes.torsoMesh,
                meshes.leftArmMesh,
                meshes.rightArmMesh,
                meshes.leftLegMesh,
                meshes.rightLegMesh
        };
        boolean[] enabled = {
                getBooleanConfig(enableHatField),
                getBooleanConfig(enableJacketField),
                getBooleanConfig(enableLeftSleeveField),
                getBooleanConfig(enableRightSleeveField),
                getBooleanConfig(enableLeftPantsField),
                getBooleanConfig(enableRightPantsField)
        };

        try {
            for (int index = 0; index < overlayParts.length; index++) {
                if (providers[index] == null || meshValues[index] == null) {
                    throw new IllegalStateException(
                            "3D Skin Layers returned a null mesh/provider for overlay " + index);
                }
                if (enabled[index]) {
                    setPreviewInjectedMeshMethod.invoke(
                            overlayParts[index], meshValues[index], providers[index]);
                }
            }
            if (injectedPreviewSuccessLogged.compareAndSet(false, true)) {
                SKIN_LAYERS_LOG.info(
                        "3D Skin Layers preview bridge replaced Quick Skin's six flat outer parts "
                                + "with pose-aligned injected meshes");
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            bestEffortClearPreviewOverlayParts(overlayParts);
            logInjectedPreviewAttachmentFailure(
                    "3D Skin Layers rejected Quick Skin's preview mesh attachment; "
                            + "the normal flat overlay remains active",
                    exception);
            return false;
        }
//?} else {
        return prepareDeferredPreview(model, skinLocation, thinArms);
//?}
    }

//? if <1.21.11 {
    /** Clears the temporary injection after the synchronous preview draw completes. */
    public static void clearInjectedPreview(PlayerModel<?> model) {
//?} else {
    /** Clears the temporary injection after the synchronous preview draw completes. */
    public static void clearInjectedPreview(PlayerModel model) {
//?}
//? if <26.2 {
        if (model == null || injectedPreviewCapability != CapabilityState.AVAILABLE) {
            return;
        }
        ModelPart[] overlayParts = {
                model.hat,
                model.jacket,
                model.leftSleeve,
                model.rightSleeve,
                model.leftPants,
                model.rightPants
        };
        try {
            validatePreviewOverlayParts(overlayParts);
            clearPreviewOverlayParts(overlayParts);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            bestEffortClearPreviewOverlayParts(overlayParts);
            logInjectedPreviewAttachmentFailure(
                    "3D Skin Layers preview overlay cleanup failed", exception);
        }
//?} else {
        if (model != null) {
            clearDeferredMeshes(model.root());
        }
//?}
    }

//? if <1.21.11 {
    /** Renders the six manual-preview overlay layers into an immediate buffer. */
    public static void render3DLayers(PoseStack poseStack, MultiBufferSource bufferSource,
                                      int light, int overlay,
                                      PlayerModel<?> model, ResourceLocation skinLocation,
                                      boolean thinArms) {
        if (poseStack == null || bufferSource == null || model == null || skinLocation == null
                || shouldSuppressManualLayers() || !ensureMeshCapability()) {
            return;
//?} else if <26.1 {
    /** Renders the six manual-preview overlay layers into an immediate buffer. */
    public static void render3DLayers(PoseStack poseStack, MultiBufferSource bufferSource,
                                      int light, int overlay,
                                      PlayerModel model, Identifier skinLocation,
                                      boolean thinArms) {
        if (poseStack == null || bufferSource == null || model == null || skinLocation == null
                || shouldSuppressManualLayers() || !ensureMeshCapability()) {
            return;
//?} else if <26.2 {
    public static void render3DLayers(PoseStack poseStack, MultiBufferSource bufferSource,
                                      int light, int overlay, PlayerModel model,
                                      Identifier skinLocation, boolean thinArms) {
        if (poseStack == null || bufferSource == null || model == null || skinLocation == null
                || shouldSuppressManualLayers() || !ensureMeshCapability()) {
            return;
//?} else {

    /** Convenience wrapper for callers that retain the full QuickSkin-owned player model. */
    public static boolean prepareDeferredPreview(PlayerModel model, Identifier skinLocation, boolean thinArms) {
        return model != null && attachDeferredMeshes(model.root(), skinLocation, thinArms);
    }

    /**
     * Installs meshes on the six overlay parts of a QuickSkin-owned model tree. ModelPart state is
     * intentionally left installed until the next submission because 26.2 executes collector nodes
     * after GuiSkinRenderer.renderToTexture returns.
     */
    public static boolean attachDeferredMeshes(ModelPart root, Identifier skinLocation, boolean thinArms) {
        if (root == null || !ensureDeferredCapability()) {
            return false;
        }

        ModelPart[] overlayParts = null;
        try {
            overlayParts = resolveOverlayParts(root);
            validateInjectableParts(overlayParts);
            clearDeferredParts(overlayParts);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            bestEffortClearDeferredParts(overlayParts);
            logDeferredAttachmentFailure(
                    "3D Skin Layers could not validate/clear QuickSkin's 26.2 overlay ModelParts; "
                            + "the deferred preview adapter is disabled for this submission",
                    e
            );
            return false;
        }

        if (skinLocation == null || shouldSuppressManualLayers()) {
            return false;
//?}
        }

        PlayerMeshes meshes = getOrCreateMeshes(skinLocation, thinArms);
        if (meshes == null || !meshes.isValid()) {
//? if <26.2 {
            return;
//?} else {
            return false;
//?}
        }

//? if <1.21.11 {
        try {
            VertexConsumer vertices = bufferSource.getBuffer(
                    RenderType.entityTranslucent(skinLocation, true)
            );

            if (getBooleanConfig(enableHatField)) {
                renderHeadLayer(poseStack, vertices, light, overlay, model, meshes.headMesh);
//?} else if <26.1 {
        try {
            VertexConsumer vertices = bufferSource.getBuffer(
                    RenderTypes.entityTranslucent(skinLocation)
            );

            if (getBooleanConfig(enableHatField)) {
                renderHeadLayer(poseStack, vertices, light, overlay, model, meshes.headMesh);
//?} else if <26.2 {
        try {
            VertexConsumer vertices = bufferSource.getBuffer(RenderTypes.entityTranslucent(skinLocation));
            if (getBooleanConfig(enableHatField)) {
                renderHeadLayer(poseStack, vertices, light, overlay, model, meshes.headMesh);
//?} else {
        Object[] providers = {
                headOffsetProvider,
                bodyOffsetProvider,
                thinArms ? leftArmSlimOffsetProvider : leftArmOffsetProvider,
                thinArms ? rightArmSlimOffsetProvider : rightArmOffsetProvider,
                leftLegOffsetProvider,
                rightLegOffsetProvider
        };
        Object[] meshValues = {
                meshes.headMesh,
                meshes.torsoMesh,
                meshes.leftArmMesh,
                meshes.rightArmMesh,
                meshes.leftLegMesh,
                meshes.rightLegMesh
        };
        boolean[] enabled = {
                getBooleanConfig(enableHatField),
                getBooleanConfig(enableJacketField),
                getBooleanConfig(enableLeftSleeveField),
                getBooleanConfig(enableRightSleeveField),
                getBooleanConfig(enableLeftPantsField),
                getBooleanConfig(enableRightPantsField)
        };

        try {
            for (int i = 0; i < overlayParts.length; i++) {
                if (providers[i] == null || meshValues[i] == null) {
                    throw new IllegalStateException("3D Skin Layers returned a null mesh/provider for overlay " + i);
                }
//?}
            }
//? if <26.2 {
            if (getBooleanConfig(enableJacketField)) {
                renderBodyLayer(poseStack, vertices, light, overlay, model, meshes.torsoMesh);
//?} else {
            for (int i = 0; i < overlayParts.length; i++) {
                if (enabled[i]) {
                    setInjectedMeshMethod.invoke(overlayParts[i], meshValues[i], providers[i]);
                }
//?}
            }
//? if <26.2 {
            if (getBooleanConfig(enableLeftSleeveField)) {
                renderArmLayer(poseStack, vertices, light, overlay, model.leftArm,
                        meshes.leftArmMesh, false, thinArms);
            }
            if (getBooleanConfig(enableRightSleeveField)) {
                renderArmLayer(poseStack, vertices, light, overlay, model.rightArm,
                        meshes.rightArmMesh, true, thinArms);
            }
            if (getBooleanConfig(enableLeftPantsField)) {
                renderLegLayer(poseStack, vertices, light, overlay, model.leftLeg, meshes.leftLegMesh);
            }
            if (getBooleanConfig(enableRightPantsField)) {
                renderLegLayer(poseStack, vertices, light, overlay, model.rightLeg, meshes.rightLegMesh);
            }
            if (!renderFailureLogged.get() && immediateRenderSuccessLogged.compareAndSet(false, true)) {
//?} else {
            if (deferredAttachmentSuccessLogged.compareAndSet(false, true)) {
//?}
                SKIN_LAYERS_LOG.info(
//? if <26.1 {
                        "3D Skin Layers immediate manual-preview rendering executed successfully"
//?} else if <26.2 {
                        "3D Skin Layers immediate manual-preview rendering executed successfully on the 26.1 backend"
//?} else {
                        "3D Skin Layers 26.2 deferred preview bridge attached successfully; "
                                + "collector execution will render the injected overlay meshes"
//?}
                );
            }
//? if <26.2 {
        } catch (RuntimeException | LinkageError e) {
            logRenderFailure(e);
//?} else {
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            bestEffortClearDeferredParts(overlayParts);
            logDeferredAttachmentFailure(
                    "3D Skin Layers rejected QuickSkin's deferred overlay attachment; normal skin/cape preview remains active",
                    e
            );
            return false;
//?}
        }
    }

//? if <26.1 {
//?} else if <26.2 {
//?} else {
    /** Clears any injected state retained on a QuickSkin-owned model tree. */
    public static void clearDeferredMeshes(ModelPart root) {
        if (root == null || !ensureDeferredCapability()) {
            return;
        }
        ModelPart[] parts = null;
        try {
            parts = resolveOverlayParts(root);
            validateInjectableParts(parts);
            clearDeferredParts(parts);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            bestEffortClearDeferredParts(parts);
            logDeferredAttachmentFailure("3D Skin Layers deferred overlay cleanup failed", e);
        }
    }

    /** Refreshes Skin Layers' entity cache without coupling refresh compatibility to preview compatibility. */
//?}
    public static void refreshPlayer(Player player) {
        if (player == null || !ensureRefreshCapability()) {
            return;
        }
        try {
            Object target = null;
            if (!refreshMethodIsStatic) {
                target = refreshInstanceField.get(null);
                if (target == null) {
                    if (refreshInstanceMissingLogged.compareAndSet(false, true)) {
                        SKIN_LAYERS_LOG.warn(
                                "3D Skin Layers refreshLayers is present but SkinLayersModBase.instance is not initialized yet; "
                                        + "QuickSkin will leave the current third-party cache untouched"
                        );
                    }
                    return;
                }
            }
            refreshMethod.invoke(target, player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (refreshInvocationFailureLogged.compareAndSet(false, true)) {
                SKIN_LAYERS_LOG.warn(
                        "3D Skin Layers player refresh failed; preview mesh support remains enabled and only refresh is degraded",
                        e
                );
            }
        }
    }

    public static void clearCache() {
        MESH_CACHE.clear();
        cachedCpmLocalModelActive = false;
        nextCpmModelProbeNanos = 0L;
    }

    private static boolean ensureMeshCapability() {
        CapabilityState state = meshCapability;
        if (state != CapabilityState.UNCHECKED) {
            return state == CapabilityState.AVAILABLE;
        }
        synchronized (MESH_INIT_LOCK) {
            if (meshCapability != CapabilityState.UNCHECKED) {
                return meshCapability == CapabilityState.AVAILABLE;
            }
            if (!classFileExists(BASE_RESOURCE)) {
                meshCapability = CapabilityState.UNAVAILABLE;
                return false;
            }
            try {
                initializeMeshCapability();
                meshCapability = CapabilityState.AVAILABLE;
                if (meshCapabilityLogged.compareAndSet(false, true)) {
//? if <26.2 {
                    String backend = "injected-model-part";
//?} else {
                    String backend = "deferred-injected-mesh";
//?}
                    SKIN_LAYERS_LOG.info(
                            "3D Skin Layers manual-preview mesh capability ready: backend={}, create3DMesh={} argument(s)",
                            backend,
                            create3DMeshSupportsMirror ? 9 : 8
                    );
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                meshCapability = CapabilityState.UNAVAILABLE;
                if (meshCapabilityFailureLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.warn(
                            "Detected 3D Skin Layers, but its public mesh/config API is incompatible. "
                                    + "QuickSkin's manual 3D-layer preview is disabled; normal skin/cape rendering is unaffected",
                            e
                    );
                }
                return false;
            }
        }
    }

    private static void initializeMeshCapability() throws ReflectiveOperationException {
        Class<?> baseClass = loadClass(BASE_CLASS);
        Class<?> apiClass = loadClass(API_CLASS);
        Class<?> meshHelperClass = loadClass(MESH_HELPER_CLASS);
        Class<?> meshClass = loadClass(MESH_CLASS);

        Object resolvedConfig = baseClass.getField("config").get(null);
        if (resolvedConfig == null) {
            throw new IllegalStateException("SkinLayersModBase.config is null");
        }
//? if <26.1 {
        Class<?> configClass = resolvedConfig.getClass();

//?} else if <26.2 {
//?} else {
        Class<?> configClass = resolvedConfig.getClass();

//?}
        Object resolvedMeshHelper = apiClass.getMethod("getMeshHelper").invoke(null);
        if (resolvedMeshHelper == null || !meshHelperClass.isInstance(resolvedMeshHelper)) {
            throw new IllegalStateException("SkinLayersAPI.getMeshHelper returned an incompatible value");
        }

        Method resolvedCreateMethod;
        boolean supportsMirror;
        try {
            resolvedCreateMethod = meshHelperClass.getMethod(
                    "create3DMesh", NativeImage.class,
                    int.class, int.class, int.class, int.class, int.class,
                    boolean.class, float.class, boolean.class
            );
            supportsMirror = true;
        } catch (NoSuchMethodException missingNineArgumentApi) {
            resolvedCreateMethod = meshHelperClass.getMethod(
                    "create3DMesh", NativeImage.class,
                    int.class, int.class, int.class, int.class, int.class,
                    boolean.class, float.class
            );
            supportsMirror = false;
        }

//? if <26.1 {
//?} else if <26.2 {
        Class<?> configClass = resolvedConfig.getClass();
//?} else {
//?}
        Field resolvedHeadVoxelSize = configClass.getField("headVoxelSize");
        Field resolvedBodyVoxelWidth = configClass.getField("bodyVoxelWidthSize");
        Field resolvedBaseVoxelSize = configClass.getField("baseVoxelSize");
        Field resolvedEnableHat = configClass.getField("enableHat");
        Field resolvedEnableJacket = configClass.getField("enableJacket");
        Field resolvedEnableLeftSleeve = configClass.getField("enableLeftSleeve");
        Field resolvedEnableRightSleeve = configClass.getField("enableRightSleeve");
        Field resolvedEnableLeftPants = configClass.getField("enableLeftPants");
        Field resolvedEnableRightPants = configClass.getField("enableRightPants");
//? if <26.1 {

        Method resolvedRender = meshClass.getMethod(
                "render", ModelPart.class, PoseStack.class, VertexConsumer.class,
                int.class, int.class, float.class, float.class, float.class, float.class
        );
        Method resolvedSetPosition = meshClass.getMethod(
                "setPosition", float.class, float.class, float.class
        );
//?} else if <26.2 {
        Method resolvedRender = meshClass.getMethod(
                "render", ModelPart.class, PoseStack.class, VertexConsumer.class,
                int.class, int.class, float.class, float.class, float.class, float.class
        );
        Method resolvedSetPosition = meshClass.getMethod(
                "setPosition", float.class, float.class, float.class
        );
//?} else {
//?}

        configInstance = resolvedConfig;
        meshHelperInstance = resolvedMeshHelper;
        create3DMeshMethod = resolvedCreateMethod;
        create3DMeshSupportsMirror = supportsMirror;
        headVoxelSizeField = resolvedHeadVoxelSize;
        bodyVoxelWidthSizeField = resolvedBodyVoxelWidth;
        baseVoxelSizeField = resolvedBaseVoxelSize;
        enableHatField = resolvedEnableHat;
        enableJacketField = resolvedEnableJacket;
        enableLeftSleeveField = resolvedEnableLeftSleeve;
        enableRightSleeveField = resolvedEnableRightSleeve;
        enableLeftPantsField = resolvedEnableLeftPants;
        enableRightPantsField = resolvedEnableRightPants;
//? if <26.2 {
        meshRenderMethod = resolvedRender;
        meshSetPositionMethod = resolvedSetPosition;
//?} else {
//?}
    }

//? if <26.2 {
    private static boolean ensureInjectedPreviewCapability() {
        if (!ensureMeshCapability()) {
            return false;
        }
        CapabilityState state = injectedPreviewCapability;
        if (state != CapabilityState.UNCHECKED) {
            return state == CapabilityState.AVAILABLE;
        }
        synchronized (INJECTED_PREVIEW_INIT_LOCK) {
            if (injectedPreviewCapability != CapabilityState.UNCHECKED) {
                return injectedPreviewCapability == CapabilityState.AVAILABLE;
            }
            try {
                Class<?> injectorClass = loadClass(
                        "dev.tr7zw.skinlayers.accessor.ModelPartInjector");
                Class<?> meshClass = loadClass(MESH_CLASS);
                Class<?> offsetProviderClass = loadClass(
                        "dev.tr7zw.skinlayers.api.OffsetProvider");
                Method setter = injectorClass.getMethod(
                        "setInjectedMesh", meshClass, offsetProviderClass);

                Object resolvedHead = readPreviewOffsetProvider(offsetProviderClass, "HEAD");
                Object resolvedBody = readPreviewOffsetProvider(offsetProviderClass, "BODY");
                Object resolvedLeftArm = readPreviewOffsetProvider(
                        offsetProviderClass, "LEFT_ARM");
                Object resolvedLeftArmSlim = readPreviewOffsetProvider(
                        offsetProviderClass, "LEFT_ARM_SLIM");
                Object resolvedRightArm = readPreviewOffsetProvider(
                        offsetProviderClass, "RIGHT_ARM");
                Object resolvedRightArmSlim = readPreviewOffsetProvider(
                        offsetProviderClass, "RIGHT_ARM_SLIM");
                Object resolvedLeftLeg = readPreviewOffsetProvider(
                        offsetProviderClass, "LEFT_LEG");
                Object resolvedRightLeg = readPreviewOffsetProvider(
                        offsetProviderClass, "RIGHT_LEG");

                previewModelPartInjectorClass = injectorClass;
                setPreviewInjectedMeshMethod = setter;
                previewHeadOffsetProvider = resolvedHead;
                previewBodyOffsetProvider = resolvedBody;
                previewLeftArmOffsetProvider = resolvedLeftArm;
                previewLeftArmSlimOffsetProvider = resolvedLeftArmSlim;
                previewRightArmOffsetProvider = resolvedRightArm;
                previewRightArmSlimOffsetProvider = resolvedRightArmSlim;
                previewLeftLegOffsetProvider = resolvedLeftLeg;
                previewRightLegOffsetProvider = resolvedRightLeg;
                injectedPreviewCapability = CapabilityState.AVAILABLE;
                if (injectedPreviewCapabilityLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.info(
                            "3D Skin Layers public ModelPartInjector/OffsetProvider capability "
                                    + "is ready for synchronous previews");
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                injectedPreviewCapability = CapabilityState.UNAVAILABLE;
                if (injectedPreviewCapabilityFailureLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.warn(
                            "Detected 3D Skin Layers, but its injected-ModelPart API is incompatible. "
                                    + "Quick Skin will retain the normal flat outer layer",
                            exception);
                }
                return false;
            }
        }
    }

    private static Object readPreviewOffsetProvider(Class<?> providerClass, String fieldName)
            throws ReflectiveOperationException {
        Object value = providerClass.getField(fieldName).get(null);
        if (value == null || !providerClass.isInstance(value)) {
            throw new IllegalStateException(
                    "OffsetProvider." + fieldName + " is null/incompatible");
        }
        return value;
    }

    private static void validatePreviewOverlayParts(ModelPart[] parts) {
        if (parts == null || parts.length != 6) {
            throw new IllegalStateException("Expected six preview overlay ModelParts");
        }
        for (ModelPart part : parts) {
            if (part == null || !previewModelPartInjectorClass.isInstance(part)) {
                throw new IllegalStateException(
                        "Skin Layers ModelPartMixin is not applied to every preview overlay part");
            }
        }
    }

    private static void clearPreviewOverlayParts(ModelPart[] parts)
            throws ReflectiveOperationException {
        for (ModelPart part : parts) {
            setPreviewInjectedMeshMethod.invoke(part, null, null);
        }
    }

    private static void bestEffortClearPreviewOverlayParts(ModelPart[] parts) {
        if (parts == null || setPreviewInjectedMeshMethod == null
                || previewModelPartInjectorClass == null) {
            return;
        }
        for (ModelPart part : parts) {
            if (part == null || !previewModelPartInjectorClass.isInstance(part)) {
                continue;
            }
            try {
                setPreviewInjectedMeshMethod.invoke(part, null, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
    }

    private static void logInjectedPreviewAttachmentFailure(
            String message, Throwable throwable) {
        if (injectedPreviewAttachmentFailureLogged.compareAndSet(false, true)) {
            SKIN_LAYERS_LOG.warn(message, throwable);
        }
    }

//?} else {
//?}
    private static boolean ensureRefreshCapability() {
        CapabilityState state = refreshCapability;
        if (state != CapabilityState.UNCHECKED) {
            return state == CapabilityState.AVAILABLE;
        }
        synchronized (REFRESH_INIT_LOCK) {
            if (refreshCapability != CapabilityState.UNCHECKED) {
                return refreshCapability == CapabilityState.AVAILABLE;
            }
            if (!classFileExists(BASE_RESOURCE)) {
                refreshCapability = CapabilityState.UNAVAILABLE;
                return false;
            }
            try {
                Class<?> baseClass = loadClass(BASE_CLASS);
                try {
                    Field instanceField = baseClass.getField("instance");
                    Method currentMethod = baseClass.getMethod("refreshLayers", Player.class);
                    if (!Modifier.isStatic(instanceField.getModifiers())
                            || Modifier.isStatic(currentMethod.getModifiers())) {
                        throw new NoSuchMethodException("Expected static instance field and non-static refreshLayers(Player)");
                    }
                    refreshInstanceField = instanceField;
                    refreshMethod = currentMethod;
                    refreshMethodIsStatic = false;
                } catch (NoSuchFieldException | NoSuchMethodException missingCurrentApi) {
                    Method legacyMethod = baseClass.getMethod("refreshPlayer", Player.class);
                    if (!Modifier.isStatic(legacyMethod.getModifiers())) {
                        throw new NoSuchMethodException("Legacy refreshPlayer(Player) is not static");
                    }
                    refreshInstanceField = null;
                    refreshMethod = legacyMethod;
                    refreshMethodIsStatic = true;
                }
                refreshCapability = CapabilityState.AVAILABLE;
                if (refreshCapabilityLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.info(
                            "3D Skin Layers player-refresh capability ready: {}",
                            refreshMethodIsStatic ? "legacy static refreshPlayer" : "instance refreshLayers"
                    );
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                refreshCapability = CapabilityState.UNAVAILABLE;
                if (refreshCapabilityFailureLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.warn(
                            "Detected 3D Skin Layers, but no supported player-refresh API was found. "
                                    + "Only cache refresh is disabled; preview rendering remains independent",
                            e
                    );
                }
                return false;
            }
        }
    }

//? if <1.21.11 {

    private static PlayerMeshes getOrCreateMeshes(ResourceLocation skinLocation, boolean thinArms) {
//?} else if <26.1 {

    private static PlayerMeshes getOrCreateMeshes(Identifier skinLocation, boolean thinArms) {
//?} else if <26.2 {
    private static PlayerMeshes getOrCreateMeshes(Identifier skinLocation, boolean thinArms) {
//?} else {
    private static boolean ensureDeferredCapability() {
        if (!ensureMeshCapability()) {
            return false;
        }
        CapabilityState state = deferredCapability;
        if (state != CapabilityState.UNCHECKED) {
            return state == CapabilityState.AVAILABLE;
        }
        synchronized (DEFERRED_INIT_LOCK) {
            if (deferredCapability != CapabilityState.UNCHECKED) {
                return deferredCapability == CapabilityState.AVAILABLE;
            }
            try {
                Class<?> injectorClass = loadClass("dev.tr7zw.skinlayers.accessor.ModelPartInjector");
                Class<?> meshClass = loadClass(MESH_CLASS);
                Class<?> offsetProviderClass = loadClass("dev.tr7zw.skinlayers.api.OffsetProvider");
                Method setter = injectorClass.getMethod("setInjectedMesh", meshClass, offsetProviderClass);

                Object resolvedHead = readOffsetProvider(offsetProviderClass, "HEAD");
                Object resolvedBody = readOffsetProvider(offsetProviderClass, "BODY");
                Object resolvedLeftArm = readOffsetProvider(offsetProviderClass, "LEFT_ARM");
                Object resolvedLeftArmSlim = readOffsetProvider(offsetProviderClass, "LEFT_ARM_SLIM");
                Object resolvedRightArm = readOffsetProvider(offsetProviderClass, "RIGHT_ARM");
                Object resolvedRightArmSlim = readOffsetProvider(offsetProviderClass, "RIGHT_ARM_SLIM");
                Object resolvedLeftLeg = readOffsetProvider(offsetProviderClass, "LEFT_LEG");
                Object resolvedRightLeg = readOffsetProvider(offsetProviderClass, "RIGHT_LEG");

                modelPartInjectorClass = injectorClass;
                setInjectedMeshMethod = setter;
                headOffsetProvider = resolvedHead;
                bodyOffsetProvider = resolvedBody;
                leftArmOffsetProvider = resolvedLeftArm;
                leftArmSlimOffsetProvider = resolvedLeftArmSlim;
                rightArmOffsetProvider = resolvedRightArm;
                rightArmSlimOffsetProvider = resolvedRightArmSlim;
                leftLegOffsetProvider = resolvedLeftLeg;
                rightLegOffsetProvider = resolvedRightLeg;
                deferredCapability = CapabilityState.AVAILABLE;
                if (deferredCapabilityLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.info(
                            "3D Skin Layers public ModelPartInjector/OffsetProvider capability is ready for 26.2"
                    );
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                deferredCapability = CapabilityState.UNAVAILABLE;
                if (deferredCapabilityFailureLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.warn(
                            "Detected 3D Skin Layers, but its 26.2 injected-ModelPart API is incompatible. "
                                    + "QuickSkin will show the normal flat overlay instead of emitting immediate vertices",
                            e
                    );
                }
                return false;
            }
        }
    }

    private static Object readOffsetProvider(Class<?> providerClass, String fieldName)
            throws ReflectiveOperationException {
        Object value = providerClass.getField(fieldName).get(null);
        if (value == null || !providerClass.isInstance(value)) {
            throw new IllegalStateException("OffsetProvider." + fieldName + " is null/incompatible");
        }
        return value;
    }

    private static ModelPart[] resolveOverlayParts(ModelPart root) {
        ModelPart head = root.getChild("head");
        ModelPart body = root.getChild("body");
        ModelPart leftArm = root.getChild("left_arm");
        ModelPart rightArm = root.getChild("right_arm");
        ModelPart leftLeg = root.getChild("left_leg");
        ModelPart rightLeg = root.getChild("right_leg");
        return new ModelPart[]{
                head.getChild("hat"),
                body.getChild("jacket"),
                leftArm.getChild("left_sleeve"),
                rightArm.getChild("right_sleeve"),
                leftLeg.getChild("left_pants"),
                rightLeg.getChild("right_pants")
        };
    }

    private static void validateInjectableParts(ModelPart[] parts) {
        if (parts == null || parts.length != 6) {
            throw new IllegalStateException("Expected six overlay ModelParts");
        }
        for (ModelPart part : parts) {
            if (part == null || !modelPartInjectorClass.isInstance(part)) {
                throw new IllegalStateException("Skin Layers ModelPartMixin is not applied to every overlay part");
            }
        }
    }

    private static void clearDeferredParts(ModelPart[] parts) throws ReflectiveOperationException {
        for (ModelPart part : parts) {
            setInjectedMeshMethod.invoke(part, null, null);
        }
    }

    private static void bestEffortClearDeferredParts(ModelPart[] parts) {
        if (parts == null || setInjectedMeshMethod == null || modelPartInjectorClass == null) {
            return;
        }
        for (ModelPart part : parts) {
            if (part == null || !modelPartInjectorClass.isInstance(part)) {
                continue;
            }
            try {
                setInjectedMeshMethod.invoke(part, null, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
    }

    private static void logDeferredAttachmentFailure(String message, Throwable throwable) {
        if (deferredAttachmentFailureLogged.compareAndSet(false, true)) {
            SKIN_LAYERS_LOG.warn(message, throwable);
        }
    }

    private static PlayerMeshes getOrCreateMeshes(Identifier skinLocation, boolean thinArms) {
//?}
        if (skinLocation == null || !ensureMeshCapability()) {
            return null;
        }
        MeshCacheKey key = new MeshCacheKey(skinLocation, thinArms);
        PlayerMeshes cached = MESH_CACHE.get(key);
        if (cached != null && cached.isValid()) {
            return cached;
        }
//? if <26.1 {
        if (cached != null) MESH_CACHE.remove(key, cached);

//?} else if <26.2 {
//?} else {
        if (cached != null) MESH_CACHE.remove(key, cached);

//?}
        try {
            NativeImage skin = getSkinTexture(skinLocation);
            if (skin == null || skin.getWidth() != 64 || skin.getHeight() != 64) {
                return null;
            }
            PlayerMeshes created = new PlayerMeshes(
                    createMesh(skin, 8, 8, 8, 32, 0, false, 0.6f),
                    createMesh(skin, 8, 12, 4, 16, 32, true, 0f),
                    createMesh(skin, thinArms ? 3 : 4, 12, 4, 48, 48, true, -2f),
                    createMesh(skin, thinArms ? 3 : 4, 12, 4, 40, 32, true, -2f),
                    createMesh(skin, 4, 12, 4, 0, 48, true, 0f),
                    createMesh(skin, 4, 12, 4, 0, 32, true, 0f)
            );
            if (!created.isValid()) {
                return null;
            }
//? if <26.1 {
            return cacheMeshes(key, created);
//?} else if <26.2 {
            PlayerMeshes raced = MESH_CACHE.putIfAbsent(key, created);
            return raced != null ? raced : created;
//?} else {
            return cacheMeshes(key, created);
//?}
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (meshCreationFailureLogged.compareAndSet(false, true)) {
                SKIN_LAYERS_LOG.warn(
                        "3D Skin Layers mesh creation failed for a 64x64 preview skin; "
                                + "this preview will use the normal flat overlay",
                        e
                );
            }
            return null;
        }
    }

//? if <26.1 {
    private static PlayerMeshes cacheMeshes(MeshCacheKey key, PlayerMeshes created) {
        synchronized (MESH_CACHE) {
            PlayerMeshes existing = MESH_CACHE.get(key);
            if (existing != null && existing.isValid()) return existing;
            if (existing != null) MESH_CACHE.remove(key, existing);
            while (MESH_CACHE.size() >= MAX_MESH_CACHE_ENTRIES) {
                var iterator = MESH_CACHE.keySet().iterator();
                if (!iterator.hasNext()) break;
                MESH_CACHE.remove(iterator.next());
            }
            MESH_CACHE.put(key, created);
            return created;
        }
    }

//?} else if <26.2 {
//?} else {
    private static PlayerMeshes cacheMeshes(MeshCacheKey key, PlayerMeshes created) {
        synchronized (MESH_CACHE) {
            PlayerMeshes existing = MESH_CACHE.get(key);
            if (existing != null && existing.isValid()) return existing;
            if (existing != null) MESH_CACHE.remove(key, existing);
            while (MESH_CACHE.size() >= MAX_MESH_CACHE_ENTRIES) {
                var iterator = MESH_CACHE.keySet().iterator();
                if (!iterator.hasNext()) break;
                MESH_CACHE.remove(iterator.next());
            }
            MESH_CACHE.put(key, created);
            return created;
        }
    }

//?}
    private static Object createMesh(NativeImage skin, int width, int height, int depth,
                                     int textureU, int textureV, boolean topPivot, float rotationOffset)
            throws ReflectiveOperationException {
        if (create3DMeshSupportsMirror) {
            // Current upstream supports explicit mirroring. False matches its own six-part player path.
            return create3DMeshMethod.invoke(
                    meshHelperInstance, skin, width, height, depth,
                    textureU, textureV, topPivot, rotationOffset, false
            );
        }
        return create3DMeshMethod.invoke(
                meshHelperInstance, skin, width, height, depth,
                textureU, textureV, topPivot, rotationOffset
        );
    }

//? if <1.21.11 {
    private static NativeImage getSkinTexture(ResourceLocation skinLocation) {
//?} else {
    private static NativeImage getSkinTexture(Identifier skinLocation) {
//?}
        try {
            Minecraft minecraft = Minecraft.getInstance();
            var resource = minecraft.getResourceManager().getResource(skinLocation);
            if (resource.isPresent()) {
                try (InputStream stream = resource.get().open()) {
                    return NativeImage.read(stream);
                }
            }
        } catch (Exception | LinkageError ignored) {
        }
        try {
            AbstractTexture texture = Minecraft.getInstance()
                    .getTextureManager().getTexture(skinLocation);
            if (texture instanceof DynamicTexture dynamicTexture) {
                NativeImage pixels = dynamicTexture.getPixels();
                if (pixels != null) {
                    return pixels;
                }
            }
            File backingFile = findTextureBackingFile(texture);
            if (backingFile != null && backingFile.isFile()) {
                try (FileInputStream stream = new FileInputStream(backingFile)) {
                    return NativeImage.read(stream);
                }
            }
        } catch (Exception | LinkageError ignored) {
        }
        // A texture-manager miss must not bypass Quick Skin's authoritative local asset store.
        return getQuickSkinLocalTexture(skinLocation);
    }

//? if <1.21.11 {
    private static NativeImage getQuickSkinLocalTexture(ResourceLocation skinLocation) {
//?} else {
    private static NativeImage getQuickSkinLocalTexture(Identifier skinLocation) {
//?}
        if (skinLocation == null
                || !QuickSkin.MOD_ID.equals(skinLocation.getNamespace())) {
            return null;
        }
        String path = skinLocation.getPath();
        String prefix = "local/";
        String suffix = "_" + TextureQuality.FULL.name().toLowerCase(java.util.Locale.ROOT);
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        String hash = path.substring(prefix.length(), path.length() - suffix.length());
        byte[] png = LocalAssetManager.getInstance().loadTexture(hash, TextureQuality.FULL);
        if (png == null || png.length == 0) {
            return null;
        }
        try {
            return NativeImage.read(new ByteArrayInputStream(png));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static File findTextureBackingFile(Object texture) {
        if (texture == null) {
            return null;
        }
        for (Class<?> current = texture.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!File.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    if (!field.canAccess(texture) && !field.trySetAccessible()) {
                        continue;
                    }
                    Object value = field.get(texture);
                    if (value instanceof File file) {
                        return file;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

//? if <1.21.11 {
    private static void renderHeadLayer(PoseStack poseStack, VertexConsumer vertices,
                                        int light, int overlay,
                                        PlayerModel<?> model,
                                        Object mesh) {
        poseStack.pushPose();
        try {
            float voxelSize = getFloatConfig(headVoxelSizeField);
            model.head.translateAndRotate(poseStack);
            poseStack.translate(0, -0.25, 0);
            poseStack.scale(voxelSize, voxelSize, voxelSize);
            poseStack.translate(0, 0.25, 0);
            poseStack.translate(0, -0.04, 0);
            meshRenderMethod.invoke(mesh, model.head, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderBodyLayer(PoseStack poseStack, VertexConsumer vertices,
                                        int light, int overlay,
                                        PlayerModel<?> model,
                                        Object mesh) {
        poseStack.pushPose();
        try {
            model.body.translateAndRotate(poseStack);
            poseStack.scale(
                    getFloatConfig(bodyVoxelWidthSizeField),
                    1.035f,
                    getFloatConfig(baseVoxelSizeField)
            );
            meshSetPositionMethod.invoke(mesh, 0f, -0.2f, 0f);
            meshRenderMethod.invoke(mesh, model.body, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderArmLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, ModelPart arm, Object mesh,
                                       boolean rightArm, boolean thinArms) {
        poseStack.pushPose();
        try {
            float pixelScale = getFloatConfig(baseVoxelSizeField);
            arm.translateAndRotate(poseStack);
            poseStack.scale(pixelScale, 1.035f, pixelScale);
            float x = thinArms ? 0.499f : 0.998f;
            meshSetPositionMethod.invoke(mesh, rightArm ? -x : x, -0.1f, 0f);
            meshRenderMethod.invoke(mesh, arm, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderLegLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, ModelPart leg, Object mesh) {
        poseStack.pushPose();
        try {
            float pixelScale = getFloatConfig(baseVoxelSizeField);
            leg.translateAndRotate(poseStack);
            poseStack.scale(pixelScale, 1.035f, pixelScale);
            meshSetPositionMethod.invoke(mesh, 0f, -0.2f, 0f);
            meshRenderMethod.invoke(mesh, leg, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void logRenderFailure(Throwable throwable) {
        if (renderFailureLogged.compareAndSet(false, true)) {
            SKIN_LAYERS_LOG.warn(
                    "3D Skin Layers immediate preview rendering failed; remaining QuickSkin preview rendering continues",
                    throwable
            );
        }
    }
//?} else if <26.1 {
    private static void renderHeadLayer(PoseStack poseStack, VertexConsumer vertices,
                                        int light, int overlay,
                                        PlayerModel model,
                                        Object mesh) {
        poseStack.pushPose();
        try {
            float voxelSize = getFloatConfig(headVoxelSizeField);
            model.head.translateAndRotate(poseStack);
            poseStack.translate(0, -0.25, 0);
            poseStack.scale(voxelSize, voxelSize, voxelSize);
            poseStack.translate(0, 0.25, 0);
            poseStack.translate(0, -0.04, 0);
            meshRenderMethod.invoke(mesh, model.head, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderBodyLayer(PoseStack poseStack, VertexConsumer vertices,
                                        int light, int overlay,
                                        PlayerModel model,
                                        Object mesh) {
        poseStack.pushPose();
        try {
            model.body.translateAndRotate(poseStack);
            poseStack.scale(
                    getFloatConfig(bodyVoxelWidthSizeField),
                    1.035f,
                    getFloatConfig(baseVoxelSizeField)
            );
            meshSetPositionMethod.invoke(mesh, 0f, -0.2f, 0f);
            meshRenderMethod.invoke(mesh, model.body, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderArmLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, ModelPart arm, Object mesh,
                                       boolean rightArm, boolean thinArms) {
        poseStack.pushPose();
        try {
            float pixelScale = getFloatConfig(baseVoxelSizeField);
            arm.translateAndRotate(poseStack);
            poseStack.scale(pixelScale, 1.035f, pixelScale);
            float x = thinArms ? 0.499f : 0.998f;
            meshSetPositionMethod.invoke(mesh, rightArm ? -x : x, -0.1f, 0f);
            meshRenderMethod.invoke(mesh, arm, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderLegLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, ModelPart leg, Object mesh) {
        poseStack.pushPose();
        try {
            float pixelScale = getFloatConfig(baseVoxelSizeField);
            leg.translateAndRotate(poseStack);
            poseStack.scale(pixelScale, 1.035f, pixelScale);
            meshSetPositionMethod.invoke(mesh, 0f, -0.2f, 0f);
            meshRenderMethod.invoke(mesh, leg, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void logRenderFailure(Throwable throwable) {
        if (renderFailureLogged.compareAndSet(false, true)) {
            SKIN_LAYERS_LOG.warn(
                    "3D Skin Layers immediate preview rendering failed; remaining QuickSkin preview rendering continues",
                    throwable
            );
        }
    }
//?} else if <26.2 {
    private static void renderHeadLayer(PoseStack poseStack, VertexConsumer vertices,
                                        int light, int overlay, PlayerModel model, Object mesh) {
        poseStack.pushPose();
        try {
            float voxelSize = getFloatConfig(headVoxelSizeField);
            model.head.translateAndRotate(poseStack);
            poseStack.translate(0, -0.25, 0);
            poseStack.scale(voxelSize, voxelSize, voxelSize);
            poseStack.translate(0, 0.25, 0);
            poseStack.translate(0, -0.04, 0);
            meshRenderMethod.invoke(mesh, model.head, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderBodyLayer(PoseStack poseStack, VertexConsumer vertices,
                                        int light, int overlay, PlayerModel model, Object mesh) {
        poseStack.pushPose();
        try {
            model.body.translateAndRotate(poseStack);
            poseStack.scale(
                    getFloatConfig(bodyVoxelWidthSizeField),
                    1.035f,
                    getFloatConfig(baseVoxelSizeField)
            );
            meshSetPositionMethod.invoke(mesh, 0f, -0.2f, 0f);
            meshRenderMethod.invoke(mesh, model.body, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderArmLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, ModelPart arm, Object mesh,
                                       boolean rightArm, boolean thinArms) {
        poseStack.pushPose();
        try {
            float pixelScale = getFloatConfig(baseVoxelSizeField);
            arm.translateAndRotate(poseStack);
            poseStack.scale(pixelScale, 1.035f, pixelScale);
            float x = thinArms ? 0.499f : 0.998f;
            meshSetPositionMethod.invoke(mesh, rightArm ? -x : x, -0.1f, 0f);
            meshRenderMethod.invoke(mesh, arm, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderLegLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, ModelPart leg, Object mesh) {
        poseStack.pushPose();
        try {
            float pixelScale = getFloatConfig(baseVoxelSizeField);
            leg.translateAndRotate(poseStack);
            poseStack.scale(pixelScale, 1.035f, pixelScale);
            meshSetPositionMethod.invoke(mesh, 0f, -0.2f, 0f);
            meshRenderMethod.invoke(mesh, leg, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void logRenderFailure(Throwable throwable) {
        if (renderFailureLogged.compareAndSet(false, true)) {
            SKIN_LAYERS_LOG.warn(
                    "3D Skin Layers immediate preview rendering failed; remaining QuickSkin preview rendering continues",
                    throwable
            );
        }
    }
//?} else {
//?}

    private static boolean getBooleanConfig(Field field) {
        try {
            return field != null && field.getBoolean(configInstance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logConfigReadFailure(e);
            return false;
        }
    }

    private static float getFloatConfig(Field field) {
        try {
            return field != null ? field.getFloat(configInstance) : 1.0f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logConfigReadFailure(e);
            return 1.0f;
        }
    }

    private static void logConfigReadFailure(Throwable throwable) {
        if (configReadFailureLogged.compareAndSet(false, true)) {
            SKIN_LAYERS_LOG.warn(
                    "3D Skin Layers config values could not be read; affected manual layers are disabled safely",
                    throwable
            );
        }
    }

    private static boolean shouldSuppressManualLayers() {
        try {
            ClientConfig config = ClientConfig.getInstance();
            String activeCpmHash = config != null ? config.activeCpmModelHash : null;
            if (activeCpmHash != null && !activeCpmHash.isEmpty()) {
                return logCpmSuppression("an explicit .cpmmodel selection is active");
            }
            if (CPMCompatIntegration.shouldDeferToCPM()) {
                return logCpmSuppression("a CPM-owned screen is active");
            }
            if (CPMCompatIntegration.isCPMActivelyRendering()) {
                return logCpmSuppression("CPM is currently rendering a custom player model");
            }
            long now = System.nanoTime();
            if (now >= nextCpmModelProbeNanos) {
                cachedCpmLocalModelActive = CPMCompatIntegration.isLocalPlayerWearingCpmModel();
                nextCpmModelProbeNanos = now + CPM_MODEL_PROBE_TTL_NANOS;
            }
            return cachedCpmLocalModelActive
                    && logCpmSuppression("CPM's local-player model cache reports an active custom model");
        } catch (RuntimeException | LinkageError e) {
            if (cpmProbeFailureLogged.compareAndSet(false, true)) {
                SKIN_LAYERS_LOG.warn(
                        "CPM activity could not be checked safely; suppressing QuickSkin's manual 3D layers to avoid model overlap",
                        e
                );
            }
            return true;
        }
    }

    private static boolean logCpmSuppression(String reason) {
        if (cpmSuppressionLogged.compareAndSet(false, true)) {
            SKIN_LAYERS_LOG.info("Suppressing manual 3D Skin Layers preview because {}", reason);
        }
        return true;
    }

    private static boolean classFileExists(String classFilePath) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null && contextLoader.getResource(classFilePath) != null) {
            return true;
        }
        ClassLoader ownLoader = SkinLayers3DIntegration.class.getClassLoader();
        return ownLoader != null && ownLoader.getResource(classFilePath) != null;
    }

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            try {
                return Class.forName(className, true, contextLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return Class.forName(className, true, SkinLayers3DIntegration.class.getClassLoader());
    }

    private enum CapabilityState {
        UNCHECKED,
        AVAILABLE,
        UNAVAILABLE
    }

//? if <1.21.11 {
    private record MeshCacheKey(ResourceLocation skinLocation, boolean thinArms) {
//?} else {
    private record MeshCacheKey(Identifier skinLocation, boolean thinArms) {
//?}
    }

    private static final class PlayerMeshes {
        private final Object headMesh;
        private final Object torsoMesh;
        private final Object leftArmMesh;
        private final Object rightArmMesh;
        private final Object leftLegMesh;
        private final Object rightLegMesh;

        private PlayerMeshes(Object headMesh, Object torsoMesh,
                             Object leftArmMesh, Object rightArmMesh,
                             Object leftLegMesh, Object rightLegMesh) {
            this.headMesh = headMesh;
            this.torsoMesh = torsoMesh;
            this.leftArmMesh = leftArmMesh;
            this.rightArmMesh = rightArmMesh;
            this.leftLegMesh = leftLegMesh;
            this.rightLegMesh = rightLegMesh;
        }

        private boolean isValid() {
            return headMesh != null && torsoMesh != null
                    && leftArmMesh != null && rightArmMesh != null
                    && leftLegMesh != null && rightLegMesh != null;
        }
    }
}
