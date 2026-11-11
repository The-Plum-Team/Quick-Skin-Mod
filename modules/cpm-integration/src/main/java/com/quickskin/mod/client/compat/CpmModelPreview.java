package com.quickskin.mod.client.compat;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.common.util.BoundedFileReader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.Model;
//? if <1.21.9 {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
//? if <1.21.11 {
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Draws a CPM model file on one of Quick Skin's own preview models, for the previews that have no
 * player for CPM to draw on: the title screen and every menu opened from it.
 *
 * <p>CPM draws a model by replacing the parts of a vanilla model while it is bound, which needs
 * no world. {@link #bind} asks CPM to bind a model file to the preview's model; the caller draws
 * the model with the texture it returns and then calls {@link #unbind}. The model keeps the pose
 * the preview gave it, with CPM's own animations on top.
 *
 * <p>There are two ways in, both by reflection. CPM's client API offers exactly this for other
 * mods' models ({@code IClientAPI.createPlayerRenderer}) and is used wherever a CPM build has it.
 * It cannot draw a model that has no texture of its own (one made to wear the player's skin),
 * and CPM's builds for 26.3 leave it switched off; then the same few calls the API makes are made
 * on CPM's render manager directly, with the preview's skin as the texture. Nothing here is
 * required: without CPM, on a CPM build that offers neither way, while a model is still loading,
 * or when CPM refuses or fails to load it, {@link #bind} answers null and the preview draws what
 * it drew before.
 *
 * <p>CPM loads a model for a game profile and applies its safety limits (cubes, texture size) to
 * every profile but the local account's. A model file the player picks is their own model, so it
 * is loaded for the local account's profile, as CPM's own model list does.
 */
@Environment(EnvType.CLIENT)
public final class CpmModelPreview {
    private static final Logger LOG = LoggerFactory.getLogger("QuickSkin-CPM");
    /** A model file is CPM's export for a skin slot or a server: far below this. */
    private static final int MAX_MODEL_FILE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_MODELS = 16;
    private static final long API_RETRY_NANOS = 1_000_000_000L;
    private static final int MAX_API_ATTEMPTS = 30;
    private static final long FAILED_MODEL_RETRY_NANOS = 10_000_000_000L;
    private static final long LOADING_GRACE_NANOS = 5_000_000_000L;
    private static final long ASKED_AGAIN_NANOS = 1_000_000_000L;
    /** The key CPM's render manager keeps a model bound for another mod under. */
    private static final String API_HOLDER = "api";

    /** The pose the preview draws, for CPM's own pose animations. */
    public record Pose(boolean walking, boolean sitting) {
        public static final Pose STANDING = new Pose(false, false);
    }

    /** How a model is bound now. */
    private enum Bound {
        NOTHING,
        THROUGH_API,
        DIRECTLY
    }

    private static final class Handles {
        Object clientApi;
        Object clientAccess;
        Object playerMode;
        Class<?> localModelType;
        Method loadModel;
        Method getPlayerIdObject;
        Method localStartRender;
        Method localUniqueKey;
        Method localGameProfile;
        /** From 1.21.9: CPM's collector, which wraps the collector a model is submitted to. */
        Constructor<?> collector;
        /** CPM's renderer for other mods' models, or null on a CPM build that has it switched off. */
        Object renderer;
        Object rendererState;
        Method setLocalModel;
        Method setRenderModel;
        Method getDefaultTexture;
        Method preRender;
        Method postRender;
        /** CPM's render manager itself, looked up when the renderer cannot draw a model. */
        Direct direct;
        boolean directUnavailable;
        Throwable directFailure;
        // Optional: the state CPM's animations read
        Field moveAmountZ;
        Field riding;
        Field inGui;
    }

    /** The calls CPM's renderer makes, for making them without it. */
    private static final class Direct {
        Object renderManager;
        Object loader;
        Object animationEngine;
        Object state;
        Object skinSheet;
        Method loadPlayer;
        Method getModelDefinition;
        Method bindModel;
        Method bindSkin;
        Method flushBatch;
        Method unbindModel;
        Method prepareAnimations;
        Method textureOf;
        Constructor<?> texture;
        Field persistentState;
        Field animationMode;
        // Optional
        Field encodedState;
        Field itemTransforms;
        Field previousPose;
    }

    /** One model file and what CPM made of it. */
    private static final class Entry {
        final long size;
        final long modified;
        volatile Object localModel;
        volatile boolean failed;
        volatile long failedAtNanos;
        /** When CPM was first asked to draw it, or last drew it: it may still be loading it. */
        volatile long awaitedSinceNanos;
        volatile long lastAskedNanos;
        /** CPM's renderer could not draw it: it is bound on the render manager directly. */
        volatile boolean direct;

        Entry(long size, long modified) {
            this.size = size;
            this.modified = modified;
        }
    }

    private static final Map<Path, Entry> ENTRIES = new LinkedHashMap<>(16, 0.75f, true);
    private static final AtomicBoolean unavailableLogged = new AtomicBoolean();
    private static final AtomicBoolean bindFailedLogged = new AtomicBoolean();

    private static volatile Handles handles;
    private static volatile boolean unavailable;
    private static long nextApiAttemptNanos;
    private static int apiAttempts;
    private static Bound bound = Bound.NOTHING;
    private static Object boundModel;

    private CpmModelPreview() {
    }

    /**
     * Whether CPM can draw model files on preview models here. False without CPM and on a CPM
     * build that offers no way to. While CPM is still starting it answers false and asks again
     * later.
     */
    public static boolean isAvailable() {
        return handles() != null;
    }

    /**
     * Binds the model of {@code file} to {@code model}, which must already hold the pose to draw.
     * Render thread only.
     *
     * @param buffers the buffer source the model is drawn into; unused from 1.21.9, where CPM
     *                records its geometry for the collector the model is submitted to
     * @param skin    the texture a model without one of its own is drawn with: the preview's skin
     * @return the texture to draw {@code model} with, after which {@link #unbind} must be called;
     *         or null when nothing was bound and the caller draws its ordinary preview
     */
    //? if <1.21.9 {
    public static ResourceLocation bind(Path file, Model model, MultiBufferSource buffers, Pose pose,
                                        ResourceLocation skin) {
    //?} else if <1.21.11 {
    public static ResourceLocation bind(Path file, Model model, Pose pose, ResourceLocation skin) {
        Object buffers = null;
    //?} else {
    public static Identifier bind(Path file, Model model, Pose pose, Identifier skin) {
        Object buffers = null;
    //?}
        Handles h = handles();
        if (h == null || file == null || model == null || bound != Bound.NOTHING) {
            return null;
        }
        Object localModel = localModel(h, file);
        Entry entry = entry(file);
        if (localModel == null || entry == null) {
            return null;
        }
        Pose drawn = pose != null ? pose : Pose.STANDING;
        Object texture = null;
        if (h.renderer != null && !entry.direct) {
            try {
                texture = bindThroughApi(h, localModel, model, buffers, drawn);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                unbind();
                // CPM's renderer cannot draw a model without a texture of its own, among others:
                // from here on this model is bound on the render manager, with the preview's skin
                if (direct(h) == null) {
                    fail(file, cause(error));
                    return null;
                }
                entry.direct = true;
            }
        }
        if (h.renderer == null || entry.direct) {
            Direct d = direct(h);
            if (d == null) {
                return null;
            }
            try {
                texture = bindDirectly(h, d, localModel, model, buffers, drawn, skin);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                unbind();
                fail(file, cause(error));
                return null;
            }
        }
        //? if <1.21.11 {
        if (texture instanceof ResourceLocation location) {
        //?} else {
        if (texture instanceof Identifier location) {
        //?}
            awaited(entry, true);
            return location;
        }
        // CPM has not finished loading the model, or it refused it: nothing stays bound
        awaited(entry, false);
        unbind();
        return null;
    }

    private static Object bindThroughApi(Handles h, Object localModel, Object model, Object buffers, Pose pose)
            throws ReflectiveOperationException {
        h.setLocalModel.invoke(h.renderer, localModel);
        h.setRenderModel.invoke(h.renderer, model);
        applyPose(h, h.rendererState, pose);
        bound = Bound.THROUGH_API;
        boundModel = model;
        h.preRender.invoke(h.renderer, buffers, h.playerMode);
        return h.getDefaultTexture.invoke(h.renderer);
    }

    /** What CPM's renderer does in its {@code preRender}, with a texture of the caller's choosing. */
    private static Object bindDirectly(Handles h, Direct d, Object localModel, Object model, Object buffers,
                                       Pose pose, Object skin) throws ReflectiveOperationException {
        h.localStartRender.invoke(localModel);
        Object profile = h.localGameProfile.invoke(localModel);
        Object player = d.loadPlayer.invoke(d.loader, profile, h.localUniqueKey.invoke(localModel));
        Object definition = player != null ? d.getModelDefinition.invoke(player) : null;
        if (definition == null) {
            return null;
        }
        d.animationMode.set(d.state, h.playerMode);
        if (d.encodedState != null) {
            d.encodedState.setInt(d.state, 0);
        }
        applyPose(h, d.state, pose);
        Object persistent = d.persistentState.get(player);
        if (d.previousPose != null && persistent != null) {
            d.previousPose.set(persistent, null);
        }
        if (d.itemTransforms != null) {
            Object transforms = d.itemTransforms.get(definition);
            if (transforms instanceof Map<?, ?> map) {
                map.clear();
            } else if (transforms instanceof Collection<?> collection) {
                collection.clear();
            }
        }
        bound = Bound.DIRECTLY;
        boundModel = model;
        d.bindModel.invoke(d.renderManager, model, API_HOLDER, buffers, definition, player, d.state);
        d.prepareAnimations.invoke(d.animationEngine, persistent, d.state, definition);
        Object texture = d.texture.newInstance(skin);
        d.bindSkin.invoke(d.renderManager, model, texture, d.skinSheet);
        return d.textureOf.invoke(texture);
    }

    //? if >=1.21.9 {
    /**
     * Submits the bound model, as {@code model} whose root is the bound model's root, to
     * {@code collector}. From 1.21.9 CPM's parts draw only through CPM's own collector, which
     * records their geometry and hands it on to the one it wraps; a plain collector would draw
     * nothing. Call between {@link #bind} and {@link #unbind}.
     *
     * @return whether the model was submitted
     */
    public static boolean submit(
            net.minecraft.client.renderer.OrderedSubmitNodeCollector collector,
            Model<? super net.minecraft.util.Unit> model,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            RenderType renderType,
            int light,
            int overlay) {
        Handles h = handles;
        if (bound == Bound.NOTHING || h == null || h.collector == null) {
            return false;
        }
        try {
            net.minecraft.client.renderer.OrderedSubmitNodeCollector cpmCollector =
                    (net.minecraft.client.renderer.OrderedSubmitNodeCollector) h.collector.newInstance(collector);
            //? if <26.3 {
            cpmCollector.submitModel(model, net.minecraft.util.Unit.INSTANCE, poseStack, renderType,
                    light, overlay, 0, null);
            //?} else {
            cpmCollector.submitModel(model, net.minecraft.util.Unit.INSTANCE, poseStack, renderType,
                    light, overlay, -1, null, 0);
            //?}
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (bindFailedLogged.compareAndSet(false, true)) {
                LOG.warn("CPM could not submit a preview model; the preview keeps its ordinary look", error);
            }
            return false;
        }
    }
    //?}

    /** Hands the preview model back as it was. Safe to call when nothing is bound. */
    public static void unbind() {
        Bound how = bound;
        Object model = boundModel;
        bound = Bound.NOTHING;
        boundModel = null;
        Handles h = handles;
        if (how == Bound.NOTHING || h == null) {
            return;
        }
        try {
            if (how == Bound.THROUGH_API) {
                h.postRender.invoke(h.renderer);
            } else {
                Direct d = h.direct;
                d.flushBatch.invoke(d.renderManager, model, API_HOLDER);
                d.unbindModel.invoke(d.renderManager, model);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            // A model that cannot be handed back would keep CPM's parts: stop binding for good
            unavailable = true;
            handles = null;
            LOG.warn("CPM could not release a preview model; CPM models are no longer drawn in previews",
                    cause(error));
        }
    }

    /** Whether a preview model is bound now, between {@link #bind} and {@link #unbind}. */
    public static boolean isBound() {
        return bound != Bound.NOTHING;
    }

    /**
     * Whether {@code file} is expected to draw soon although {@link #bind} answers null for it:
     * the model is still being read, which this starts for a file not asked for before, or CPM is
     * still building it. CPM does not say whether it is building a model or has refused it, so a
     * model that has not drawn within {@link #LOADING_GRACE_NANOS} of being asked for counts as
     * one CPM does not draw.
     */
    public static boolean isLoading(Path file) {
        Handles h = handles();
        if (file == null || h == null) {
            return false;
        }
        localModel(h, file);
        Entry entry = entry(file);
        if (entry == null || entry.failed) {
            return false;
        }
        long since = entry.awaitedSinceNanos;
        return since == 0L || System.nanoTime() - since < LOADING_GRACE_NANOS;
    }

    /** Forgets every loaded model file, as when the catalogue is rescanned. */
    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }

    private static Entry entry(Path file) {
        synchronized (ENTRIES) {
            return ENTRIES.get(file);
        }
    }

    /** Notes that CPM drew the model now, or was asked to and did not. */
    private static void awaited(Entry entry, boolean drawn) {
        long now = System.nanoTime();
        // CPM drops a model nothing has drawn for a while and builds it again when asked: a
        // model asked for after a pause is awaited afresh
        if (drawn || entry.awaitedSinceNanos == 0L || now - entry.lastAskedNanos > ASKED_AGAIN_NANOS) {
            entry.awaitedSinceNanos = now;
        }
        entry.lastAskedNanos = now;
    }

    private static void applyPose(Handles h, Object state, Pose pose) throws IllegalAccessException {
        if (state == null) {
            return;
        }
        if (h.moveAmountZ != null) {
            h.moveAmountZ.setFloat(state, pose.walking() ? 0.5f : 0.0f);
        }
        if (h.riding != null) {
            h.riding.setBoolean(state, pose.sitting());
        }
        if (h.inGui != null) {
            h.inGui.setBoolean(state, true);
        }
    }

    private static Throwable cause(Throwable error) {
        return error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
    }

    private static void fail(Path file, Throwable cause) {
        Entry entry = entry(file);
        if (entry != null) {
            entry.failed = true;
            entry.failedAtNanos = System.nanoTime();
        }
        if (bindFailedLogged.compareAndSet(false, true)) {
            LOG.warn("CPM could not draw the model {} in a preview; the preview keeps its ordinary look",
                    file.getFileName(), cause);
        }
    }

    /** The model CPM parsed from {@code file}, or null while it is read or after it failed. */
    private static Object localModel(Handles h, Path file) {
        long size;
        long modified;
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                return null;
            }
            size = attributes.size();
            modified = attributes.lastModifiedTime().toMillis();
        } catch (IOException | RuntimeException error) {
            return null;
        }
        Entry entry;
        synchronized (ENTRIES) {
            entry = ENTRIES.get(file);
            boolean stale = entry != null && (entry.size != size || entry.modified != modified);
            boolean retry = entry != null && entry.failed
                    && System.nanoTime() - entry.failedAtNanos > FAILED_MODEL_RETRY_NANOS;
            if (entry == null || stale || retry) {
                entry = new Entry(size, modified);
                ENTRIES.put(file, entry);
                Iterator<Path> eldest = ENTRIES.keySet().iterator();
                while (ENTRIES.size() > MAX_MODELS && eldest.hasNext()) {
                    eldest.next();
                    eldest.remove();
                }
                Entry loading = entry;
                ClientIoExecutor.runAsync(() -> load(h, file, loading));
            }
        }
        return entry.failed ? null : entry.localModel;
    }

    /** Worker thread: reads the file and lets CPM parse its container. */
    private static void load(Handles h, Path file, Entry entry) {
        try {
            byte[] bytes = BoundedFileReader.readBytes(file, MAX_MODEL_FILE_BYTES);
            Object parsed;
            try (InputStream input = new ByteArrayInputStream(bytes)) {
                parsed = h.loadModel.invoke(h.clientApi, file.getFileName().toString(), input);
            }
            if (parsed == null) {
                throw new IOException("CPM parsed no model");
            }
            entry.localModel = asLocalAccountModel(h, parsed);
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError error) {
            entry.failed = true;
            entry.failedAtNanos = System.nanoTime();
            LOG.warn("CPM could not read the model file {} for a preview", file.getFileName(), cause(error));
        }
    }

    /**
     * CPM's loaded model, answering the local account's profile instead of the throwaway profile
     * CPM generates for it, so the player's own model is not held to the limits for strangers'.
     */
    private static Object asLocalAccountModel(Handles h, Object parsed) {
        return Proxy.newProxyInstance(h.localModelType.getClassLoader(), new Class<?>[] {h.localModelType},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getGameProfile": {
                            Object profile = null;
                            try {
                                profile = h.getPlayerIdObject.invoke(h.clientAccess);
                            } catch (ReflectiveOperationException | RuntimeException ignored) {
                            }
                            return profile != null ? profile : h.localGameProfile.invoke(parsed);
                        }
                        case "startRender":
                            return h.localStartRender.invoke(parsed);
                        case "getUniqueKey":
                            return h.localUniqueKey.invoke(parsed);
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == arguments[0];
                        default:
                            return "QuickSkinCpmModel";
                    }
                });
    }

    private static Handles handles() {
        Handles resolved = handles;
        if (resolved != null || unavailable) {
            return resolved;
        }
        if (!CPMCompatIntegration.isAvailable()) {
            unavailable = true;
            return null;
        }
        synchronized (CpmModelPreview.class) {
            if (handles != null || unavailable) {
                return handles;
            }
            long now = System.nanoTime();
            if (apiAttempts > 0 && now - nextApiAttemptNanos < 0) {
                return null;
            }
            apiAttempts++;
            nextApiAttemptNanos = now + API_RETRY_NANOS;
            try {
                resolved = resolve();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                giveUp("CPM offers no way to draw its models on other mods' models here", cause(error));
                return null;
            }
            if (resolved == null && apiAttempts >= MAX_API_ATTEMPTS) {
                giveUp("CPM's client API did not start", null);
            }
            handles = resolved;
            return resolved;
        }
    }

    private static void giveUp(String reason, Throwable error) {
        unavailable = true;
        if (unavailableLogged.compareAndSet(false, true)) {
            LOG.warn("{}; CPM models keep their icon in previews outside a world", reason, error);
        }
    }

    /** Null while CPM's API has not started yet; throws when this CPM build has no way in. */
    private static Handles resolve() throws ReflectiveOperationException {
        Class<?> commonAccessType = Class.forName("com.tom.cpm.shared.MinecraftCommonAccess");
        Object commonAccess = commonAccessType.getMethod("get").invoke(null);
        if (commonAccess == null) {
            return null;
        }
        Object apiManager = commonAccessType.getMethod("getApi").invoke(commonAccess);
        if (apiManager == null) {
            return null;
        }
        Handles h = new Handles();
        h.clientApi = apiManager.getClass().getMethod("clientApi").invoke(apiManager);
        Class<?> clientAccessType = Class.forName("com.tom.cpm.shared.MinecraftClientAccess");
        h.clientAccess = clientAccessType.getMethod("get").invoke(null);
        if (h.clientApi == null || h.clientAccess == null) {
            return null;
        }
        h.getPlayerIdObject = clientAccessType.getMethod("getPlayerIDObject");

        Class<?> apiType = Class.forName("com.tom.cpm.api.IClientAPI");
        h.localModelType = Class.forName("com.tom.cpm.api.IClientAPI$LocalModel");
        h.loadModel = apiType.getMethod("loadModel", String.class, InputStream.class);
        h.localStartRender = h.localModelType.getMethod("startRender");
        h.localUniqueKey = h.localModelType.getMethod("getUniqueKey");
        h.localGameProfile = h.localModelType.getMethod("getGameProfile");
        Class<?> modeType = Class.forName("com.tom.cpm.shared.animation.AnimationEngine$AnimationMode");
        h.playerMode = enumConstant(modeType, "PLAYER");
        //? if >=1.21.9 {
        h.collector = Class.forName("com.tom.cpm.client.CPMOrderedSubmitNodeCollector")
                .getConstructor(net.minecraft.client.renderer.OrderedSubmitNodeCollector.class);
        //?}
        Class<?> stateType = Class.forName("com.tom.cpm.shared.animation.AnimationState");
        h.moveAmountZ = optionalField(stateType, "moveAmountZ", float.class);
        h.riding = optionalField(stateType, "riding", boolean.class);
        h.inGui = optionalField(stateType, "inGui", boolean.class);

        //? if <1.21.9 {
        Class<?> buffersType = MultiBufferSource.class;
        //?} else {
        Class<?> buffersType = Void.class;
        //?}
        //? if <1.21.11 {
        Class<?> textureType = ResourceLocation.class;
        Function<ResourceLocation, RenderType> renderType = RenderType::entityTranslucent;
        //?} else {
        Class<?> textureType = Identifier.class;
        Function<Identifier, RenderType> renderType = RenderTypes::entityTranslucent;
        //?}
        // CPM answers null when its platform registered no renderer for these classes
        h.renderer = apiType.getMethod("createPlayerRenderer",
                        Class.class, Class.class, Class.class, Class.class, Class.class)
                .invoke(h.clientApi, Model.class, textureType, RenderType.class, buffersType, GameProfile.class);
        if (h.renderer != null) {
            Class<?> rendererType = Class.forName("com.tom.cpm.api.IClientAPI$PlayerRenderer");
            h.setLocalModel = rendererType.getMethod("setLocalModel", h.localModelType);
            h.setRenderModel = rendererType.getMethod("setRenderModel", Object.class);
            h.getDefaultTexture = rendererType.getMethod("getDefaultTexture");
            h.preRender = rendererType.getMethod("preRender", Object.class, modeType);
            h.postRender = rendererType.getMethod("postRender");
            rendererType.getMethod("setRenderType", Function.class).invoke(h.renderer, renderType);
            h.rendererState = rendererType.getMethod("getAnimationState").invoke(h.renderer);
            prepareAnimationState(h.rendererState);
            LOG.info("CPM models are drawn in previews outside a world");
        } else if (direct(h) != null) {
            LOG.info("CPM models are drawn in previews outside a world, on CPM's render manager "
                    + "(this CPM build offers no renderer for other mods' models)");
        } else {
            NoSuchMethodException none = new NoSuchMethodException(
                    "no renderer for other mods' models, and its render manager cannot be used");
            none.initCause(h.directFailure);
            throw none;
        }
        return h;
    }

    /** CPM's render manager and the calls on it, or null when this CPM build names them otherwise. */
    private static Direct direct(Handles h) {
        if (h.direct != null || h.directUnavailable) {
            return h.direct;
        }
        try {
            Direct d = new Direct();
            Class<?> clientAccessType = Class.forName("com.tom.cpm.shared.MinecraftClientAccess");
            d.renderManager = clientAccessType.getMethod("getPlayerRenderManager").invoke(h.clientAccess);
            d.loader = clientAccessType.getMethod("getDefinitionLoader").invoke(h.clientAccess);
            if (d.renderManager == null || d.loader == null) {
                throw new NoSuchFieldException("CPM's render manager is not there");
            }
            Class<?> managerType = Class.forName("com.tom.cpm.shared.model.render.ModelRenderManager");
            Class<?> playerType = Class.forName("com.tom.cpm.shared.config.Player");
            Class<?> definitionType = Class.forName("com.tom.cpm.shared.definition.ModelDefinition");
            Class<?> stateType = Class.forName("com.tom.cpm.shared.animation.AnimationState");
            Class<?> sheetType = Class.forName("com.tom.cpm.shared.model.TextureSheetType");
            d.loadPlayer = d.loader.getClass().getMethod("loadPlayer", Object.class, String.class);
            d.getModelDefinition = playerType.getMethod("getModelDefinition");
            d.persistentState = playerType.getField("persistentState");
            d.bindModel = managerType.getMethod("bindModel",
                    Object.class, String.class, Object.class, definitionType, playerType, stateType);
            d.bindSkin = managerType.getMethod("bindSkin", Object.class, Object.class, sheetType);
            d.flushBatch = managerType.getMethod("flushBatch", Object.class, String.class);
            d.unbindModel = managerType.getMethod("unbindModel", Object.class);
            d.animationEngine = managerType.getMethod("getAnimationEngine").invoke(d.renderManager);
            d.prepareAnimations = d.animationEngine.getClass().getMethod("prepareAnimations",
                    d.persistentState.getType(), stateType, definitionType);
            d.skinSheet = enumConstant(sheetType, "SKIN");
            Class<?> textureHandler = Class.forName("com.tom.cpm.client.ModelTexture");
            //? if <1.21.11 {
            d.texture = textureHandler.getConstructor(ResourceLocation.class);
            //?} else {
            d.texture = textureHandler.getConstructor(Identifier.class);
            //?}
            d.textureOf = textureHandler.getMethod("getTexture");
            d.state = stateType.getConstructor().newInstance();
            d.animationMode = stateType.getField("animationMode");
            d.encodedState = optionalField(stateType, "encodedState", int.class);
            d.previousPose = optionalField(d.persistentState.getType(), "prevPose", null);
            d.itemTransforms = optionalField(definitionType, "itemTransforms", null);
            prepareAnimationState(d.state);
            h.direct = d;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            h.directUnavailable = true;
            h.directFailure = cause(error);
        }
        return h.direct;
    }

    /**
     * CPM's animations read the state its server syncs, which nothing sets for a model drawn
     * outside a world. A CPM build without that state animates without it.
     */
    private static void prepareAnimationState(Object state) {
        if (state == null) {
            return;
        }
        try {
            Field syncState = state.getClass().getField("syncState");
            if (syncState.get(state) == null) {
                syncState.set(state, syncState.getType().getConstructor().newInstance());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    /** A public field by name, of {@code fieldType} when one is given; null when there is none. */
    private static Field optionalField(Class<?> type, String name, Class<?> fieldType) {
        try {
            Field field = type.getField(name);
            return fieldType == null || field.getType() == fieldType ? field : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Object enumConstant(Class<?> type, String name) throws NoSuchFieldException {
        Object[] constants = type.getEnumConstants();
        if (constants != null) {
            for (Object constant : constants) {
                if (name.equals(((Enum<?>) constant).name())) {
                    return constant;
                }
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
