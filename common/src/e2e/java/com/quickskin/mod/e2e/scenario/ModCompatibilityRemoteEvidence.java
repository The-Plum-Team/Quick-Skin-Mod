package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.compat.EarsCompatIntegration;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.CompatibilityProbe;
import com.quickskin.mod.e2e.DefaultSkinEvidenceView;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.networking.NetworkSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

/** Shared renderer-truthful assertions and camera control for optional-mod remote evidence. */
final class ModCompatibilityRemoteEvidence {
    private static final String OBSERVER_READY_SKIN_ID =
            "quickskin_e2e_observer_ready";
    /** Bob's one acknowledgement that CPM showed Alice's embedded-model skin as required. */
    private static final String OBSERVER_EMBEDDED_CPM_SKIN_ID =
            "quickskin_e2e_observer_saw_embedded_cpm";
    private static final double VANTAGE_DISTANCE = 5.0;
    private static final double VANTAGE_SIDE = 1.5;
    private static final float SUBJECT_REAR_YAW = 180.0f;

    private volatile CompatibilityProbe.Result probe =
            new CompatibilityProbe.Result(false, "probe not executed");
    private volatile boolean remoteBaselineObserved;
    private volatile Object remoteBaselineDefinition;
    /** Alice's Quick Skin skin id when Bob latched her protected CPM baseline. */
    private volatile String remoteBaselineSkinId;
    private volatile String embeddedHash;
    private volatile String embeddedProof;
    /** Bob sent his acknowledgement; {@link #embeddedAcknowledged} waits for the server's ack. */
    private volatile boolean embeddedAckSent;
    private volatile boolean embeddedAcknowledged;
    /** A definite failure of the embedded phase, which ends Bob's wait at once. */
    private volatile String embeddedFailure;
    private CpmEmbeddedSkinProof.Band embeddedBand;
    private final CpmEmbeddedSkinProof.WaitLog embeddedWait =
            new CpmEmbeddedSkinProof.WaitLog("observe_remote_applied (embedded CPM skin)");
    private String lastWaitReason;
    private int waitReasonLogs;

    private double targetX;
    private double targetY;
    private double targetZ;
    private boolean vantageSet;

    Step integrationStep(String modId) {
        return Step.of("integration_active")
                .action(() -> {
                    probe = CompatibilityProbe.verifyConfiguredIntegration();
                    if (!supportsRemoteEvidence(modId)) {
                        probe = new CompatibilityProbe.Result(false,
                                "remote compatibility evidence is not contracted for " + modId);
                    }
                    E2ELog.info("remote compatibility probe "
                            + (probe.active() ? "PASS" : "FAIL") + ": " + probe.detail());
                })
                .minTicks(1)
                .ready(() -> true)
                .assertion(() -> probe.active()
                        ? Step.Result.pass(probe.detail())
                        : Step.Result.fail(probe.detail()));
    }

    Step confirmObserver(UUID observerId) {
        return Step.of("confirm_self")
                .action(() -> {
                    try {
                        disableAutomaticOwnSkin();
                        PlayerAppearanceService.getInstance()
                                .applyLook(observerId, "", "", "classic");
                        NetworkSyncService.getInstance()
                                .syncAppearance(
                                        observerId,
                                        OBSERVER_READY_SKIN_ID,
                                        "",
                                        "classic");
                        E2ELog.info(
                                "Bob sent the post-join compatibility observer confirmation");
                    } catch (Throwable failure) {
                        E2ELog.error("remote compatibility confirm_self failed", failure);
                    }
                })
                .minTicks(10)
                .ready(() -> NetworkSyncService.getInstance()
                        .isLatestAppearanceAcknowledged(
                                observerId, OBSERVER_READY_SKIN_ID))
                .timeoutTicks(400)
                .assertion(() -> NetworkSyncService.getInstance()
                        .isLatestAppearanceAcknowledged(
                                observerId, OBSERVER_READY_SKIN_ID)
                        ? Step.Result.pass(
                                "server acknowledged the post-join observer confirmation")
                        : Step.Result.fail(
                                "server did not acknowledge the post-join observer confirmation"));
    }

    boolean integrationActive() {
        return probe.active();
    }

    String integrationDetail() {
        return probe.detail();
    }

    void markRemoteBaselineObserved() {
        remoteBaselineObserved = true;
    }

    boolean remoteBaselineObserved() {
        return remoteBaselineObserved;
    }

    Step.Result checkRemoteState(
            Minecraft minecraft, String modId, boolean applied) {
        AbstractClientPlayer subject = findOther(minecraft);
        if (subject == null) return Step.Result.fail("Alice is not present on Bob's client");
        if ("ears".equals(modId)) {
            return applied
                    ? checkRemoteEarsApplied(minecraft, subject)
                    : checkRemoteEarsBaseline(minecraft, subject);
        }
        if ("cpm".equals(modId)) {
            return applied
                    ? checkRemoteCpmApplied(subject)
                    : checkRemoteCpmBaseline(subject);
        }
        return Step.Result.fail("unsupported remote compatibility mod " + modId);
    }

    /**
     * Whether Bob can capture a remote checkpoint: at the rear vantage, seeing Alice's asserted
     * state, with the terrain around her compiled by his renderer. Each changed waiting reason is
     * logged, up to a bound, so a step timeout still records which condition never held.
     */
    boolean observerReady(String step, Minecraft minecraft, String modId, boolean applied) {
        String reason = null;
        if (!atVantage(minecraft)) {
            reason = "not yet at the rear vantage with Alice in view";
        } else {
            Step.Result state = checkRemoteState(minecraft, modId, applied);
            if (!state.pass()) {
                reason = state.message();
            } else {
                Step.Result rendered = checkSubjectRendered(minecraft);
                if (!rendered.pass()) reason = rendered.message();
            }
        }
        noteWait(step, reason);
        return reason == null;
    }

    private void noteWait(String step, String reason) {
        if (reason != null && !reason.equals(lastWaitReason) && waitReasonLogs < 32) {
            waitReasonLogs++;
            E2ELog.info(step + " waiting: " + reason);
        }
        lastWaitReason = reason;
    }

    /**
     * Bob's intermediate CPM phase of {@code observe_remote_applied}, before Alice's plaid reset:
     * the bytes Bob received for Alice's embedded-model skin must hash to its id and equal the
     * bundled fixture texel for texel, and on the file-backed bridge band CPM must have loaded
     * Alice's model from them. Bob then acknowledges once, which releases Alice's reset. Other
     * mods have no such phase.
     */
    boolean observeEmbeddedCpm(Minecraft minecraft, String modId, UUID observerId) {
        if (!"cpm".equals(modId) || embeddedAcknowledged || embeddedFailure != null) return true;
        if (embeddedAckSent) {
            if (NetworkSyncService.getInstance().isLatestAppearanceAcknowledged(
                    observerId, OBSERVER_EMBEDDED_CPM_SKIN_ID)) {
                embeddedAcknowledged = true;
                E2ELog.info("server acknowledged Bob's embedded CPM confirmation");
                return true;
            }
            embeddedWait.note("the server has not acknowledged Bob's confirmation yet");
            return false;
        }
        String reason = embeddedCpmWaitReason(minecraft);
        embeddedWait.note(reason);
        if (embeddedFailure != null) {
            E2ELog.info("observe_remote_applied (embedded CPM skin) failed: " + embeddedFailure);
            return true;
        }
        if (reason != null) return false;
        try {
            NetworkSyncService.getInstance().syncAppearance(
                    observerId, OBSERVER_EMBEDDED_CPM_SKIN_ID, "", "slim");
            embeddedAckSent = true;
            E2ELog.info("Bob confirmed Alice's embedded CPM skin: " + embeddedProof);
        } catch (Throwable failure) {
            E2ELog.error("failed to acknowledge the embedded CPM skin", failure);
        }
        return false;
    }

    /** Whether Bob's embedded CPM phase already failed, so the step stops waiting. */
    boolean embeddedCpmFailed() {
        return embeddedFailure != null;
    }

    private String embeddedCpmWaitReason(Minecraft minecraft) {
        if (embeddedBand == null) embeddedBand = CpmEmbeddedSkinProof.band();
        if (embeddedBand.mismatch() != null) {
            embeddedFailure = embeddedBand.mismatch();
            return embeddedFailure;
        }
        AbstractClientPlayer subject = findOther(minecraft);
        if (subject == null) return "Alice is not present on Bob's client";
        PlayerAppearance appearance = PlayerAppearanceRepository.getInstance()
                .getAppearance(subject.getUUID());
        String skinId = appearance == null ? null : appearance.getSkinId();
        if (skinId == null || !skinId.startsWith("local_skin:")) {
            return "Alice's skin id is not network-backed: " + skinId;
        }
        String hash = skinId.substring("local_skin:".length());
        NetworkTextureCache cache = NetworkTextureCache.getInstance();
        if (!cache.hasTexture(hash, "skin")) return "Alice's skin bytes are not cached: " + hash;
        CpmEmbeddedSkinProof.Comparison bytes =
                CpmEmbeddedSkinProof.compare(cache.getTextureData(hash, "skin"), hash);
        if (bytes.differing() != 0) {
            String reason = "Alice's skin " + hash + " is not the embedded-model skin: "
                    + bytes.message();
            boolean newSkin = !skinId.equals(remoteBaselineSkinId);
            if (newSkin && (bytes.alteredFixture()
                    || TestAssets.CPM_EMBEDDED_SKIN_CONTENT_ID.equals(hash))) {
                // Alice's new skin is the fixture with changed texels, or carries the fixture's
                // id with other bytes: waiting cannot fix it.
                embeddedFailure = reason;
            }
            return reason;
        }
        String model = "Bob's CPM is not asked to read it (" + embeddedBand.describe() + ")";
        if (embeddedBand.bridge()) {
            String modelReason = CpmEmbeddedSkinProof.modelWaitReason(
                    CpmEmbeddedSkinProof.definition(subject.getUUID()), remoteBaselineDefinition);
            if (modelReason != null) return modelReason;
            model = "Bob's CPM loaded Alice's embedded model ("
                    + CpmEmbeddedSkinProof.FIXTURE_MODEL + "; renderable, no error; "
                    + embeddedBand.describe() + ")";
        }
        embeddedHash = hash;
        embeddedProof = "embedded CPM skin " + hash + " received: " + bytes.message() + "; "
                + model;
        return null;
    }

    /** Alice's side: whether Bob acknowledged her embedded-model skin. */
    boolean observerSawEmbeddedCpm(Minecraft minecraft) {
        AbstractClientPlayer observer = findOther(minecraft);
        if (observer == null) return false;
        PlayerAppearance acknowledgement = PlayerAppearanceRepository.getInstance()
                .getAppearance(observer.getUUID());
        return acknowledgement != null
                && OBSERVER_EMBEDDED_CPM_SKIN_ID.equals(acknowledgement.getSkinId());
    }

    /** The latched remote proof, or a failure when the CPM embedded phase never completed. */
    Step.Result embeddedCpmProof(String modId) {
        if (!"cpm".equals(modId)) return Step.Result.pass("");
        if (embeddedFailure != null) {
            return Step.Result.fail("Bob's embedded CPM check failed: " + embeddedFailure);
        }
        String proof = embeddedProof;
        return embeddedAcknowledged && proof != null
                ? Step.Result.pass(proof)
                : Step.Result.fail("Bob never confirmed Alice's embedded CPM model");
    }

    /**
     * Whether Bob's renderer has compiled the terrain holding Alice. Vanilla draws an entity only
     * once its section is compiled, so an earlier frame shows sky and hotbar without Alice even
     * when every appearance assertion already holds on Bob's client.
     */
    Step.Result checkSubjectRendered(Minecraft minecraft) {
        AbstractClientPlayer subject = findOther(minecraft);
        if (subject == null) return Step.Result.fail("Alice is not present on Bob's client");
        var position = subject.blockPosition();
        if (minecraft.level == null || minecraft.level.getBlockState(position.below()).isAir()) {
            return Step.Result.fail("the terrain below Alice is not loaded on Bob's client");
        }
        if (!VanillaShim.isTerrainRenderReady(minecraft, position)
                || !VanillaShim.isTerrainRenderReady(minecraft, position.below())) {
            return Step.Result.fail("Bob's renderer has not compiled the terrain around Alice");
        }
        return Step.Result.pass("Bob's renderer compiled the terrain around Alice");
    }

    boolean observerAcknowledged(Minecraft minecraft) {
        AbstractClientPlayer observer = findOther(minecraft);
        if (observer == null) return false;
        PlayerAppearance acknowledgement = PlayerAppearanceRepository.getInstance()
                .getAppearance(observer.getUUID());
        return acknowledgement != null && "slim".equals(acknowledgement.getModel());
    }

    boolean observerConfirmed(Minecraft minecraft) {
        AbstractClientPlayer observer = findOther(minecraft);
        if (observer == null) return false;
        PlayerAppearance confirmation = PlayerAppearanceRepository.getInstance()
                .getAppearance(observer.getUUID());
        return confirmation != null
                && OBSERVER_READY_SKIN_ID.equals(confirmation.getSkinId());
    }

    void stepTowardVantage(Minecraft minecraft) {
        try {
            DefaultSkinEvidenceView.enterFirstPerson(minecraft);
            AbstractClientPlayer subject = findOther(minecraft);
            if (subject == null || minecraft.player == null) return;
            DefaultSkinEvidenceView.pinStandingPose(subject, SUBJECT_REAR_YAW);
            if (!vantageSet) {
                double radians = Math.toRadians(subject.getYRot());
                double forwardX = -Math.sin(radians);
                double forwardZ = Math.cos(radians);
                targetX = subject.getX() - forwardX * VANTAGE_DISTANCE
                        + forwardZ * VANTAGE_SIDE;
                targetY = subject.getY();
                targetZ = subject.getZ() - forwardZ * VANTAGE_DISTANCE
                        - forwardX * VANTAGE_SIDE;
                vantageSet = true;
            }

            double currentX = minecraft.player.getX();
            double currentZ = minecraft.player.getZ();
            double deltaX = targetX - currentX;
            double deltaZ = targetZ - currentZ;
            double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            double step = 0.25;
            double nextX = distance > step
                    ? currentX + deltaX / distance * step : targetX;
            double nextZ = distance > step
                    ? currentZ + deltaZ / distance * step : targetZ;
            minecraft.player.setDeltaMovement(0, 0, 0);
            minecraft.player.setPos(nextX, targetY, nextZ);

            double subjectX = subject.getX() - nextX;
            double subjectZ = subject.getZ() - nextZ;
            double horizontal = Math.sqrt(subjectX * subjectX + subjectZ * subjectZ);
            double torsoY = subject.getY() + 1.0
                    - (targetY + minecraft.player.getEyeHeight());
            float yaw = (float) Math.toDegrees(Math.atan2(-subjectX, subjectZ));
            float pitch = (float) -Math.toDegrees(
                    Math.atan2(torsoY, horizontal < 0.01 ? 0.01 : horizontal));
            minecraft.player.setYRot(yaw);
            minecraft.player.yRotO = yaw;
            minecraft.player.setXRot(pitch);
            minecraft.player.xRotO = pitch;
            minecraft.player.setYHeadRot(yaw);
            minecraft.player.yHeadRotO = yaw;
            minecraft.player.setYBodyRot(yaw);
            minecraft.player.yBodyRotO = yaw;
            DefaultSkinEvidenceView.pinStandingMotion(minecraft.player);
        } catch (Throwable ignored) {
        }
    }

    boolean atVantage(Minecraft minecraft) {
        if (!vantageSet || minecraft.player == null || findOther(minecraft) == null) {
            return false;
        }
        return Math.hypot(
                minecraft.player.getX() - targetX,
                minecraft.player.getZ() - targetZ) < 0.4
                && checkRearComposition(minecraft).pass();
    }

    Step.Result checkRearComposition(Minecraft minecraft) {
        if (minecraft.player == null) {
            return Step.Result.fail("rear-view observer is unavailable");
        }
        AbstractClientPlayer subject = findOther(minecraft);
        if (subject == null) return Step.Result.fail("rear-view subject is unavailable");
        return DefaultSkinEvidenceView.checkRearView(
                subject, minecraft.player, SUBJECT_REAR_YAW);
    }

    static void disableAutomaticOwnSkin() {
        ClientConfig config = ClientConfig.getInstance();
        config.enablePlayerOwnSkinSystem = false;
        config.activeSkinHash = "";
        config.playerOwnSkinHash = "";
        config.activeCapeHash = "";
    }

    static String selectedMod() {
        return System.getProperty("quickskin.e2e.compatibility", "").trim();
    }

    static boolean lateJoinUsesAppliedState(String modId) {
        return switch (modId) {
            case "cpm" -> false;
            case "ears" -> true;
            default -> throw new IllegalArgumentException(
                    "late-join compatibility evidence is not contracted for " + modId);
        };
    }

    private Step.Result checkRemoteEarsBaseline(
            Minecraft minecraft, AbstractClientPlayer subject) {
        Step.Result skin = checkRemoteQuickSkin(subject);
        if (!skin.pass()) return skin;
        Object cached = EarsCompatIntegration.getFeatures(
                PlayerAppearanceService.getInstance().getSkinLocation(subject.getUUID()));
        if (cached != null && !EarsCompatIntegration.isDisabledResult(cached)) {
            return Step.Result.fail("plain remote Ears control enabled features: " + cached);
        }
        try {
            Object renderer = minecraft.getEntityRenderDispatcher().getRenderer(subject);
            Object rendered = rendererFeatures(renderer, subject);
            if (!EarsCompatIntegration.isDisabledResult(rendered)) {
                return Step.Result.fail(
                        "Ears renderer enabled geometry for the plain remote control: " + rendered);
            }
            if (!earsRendererLayerAttached(renderer)) {
                return Step.Result.fail("Ears feature layer is not attached to Alice's renderer");
            }
        } catch (ReflectiveOperationException failure) {
            return Step.Result.fail("could not inspect remote Ears baseline: "
                    + concise(failure));
        }
        return Step.Result.pass("Alice's plain Quick Skin texture reached Bob; Ears' remote "
                + "renderer layer is attached with features disabled; " + skin.message());
    }

    private Step.Result checkRemoteEarsApplied(
            Minecraft minecraft, AbstractClientPlayer subject) {
        Step.Result skin = checkRemoteQuickSkin(subject);
        if (!skin.pass()) return skin;
        Object cached = EarsCompatIntegration.getFeatures(
                PlayerAppearanceService.getInstance().getSkinLocation(subject.getUUID()));
        if (!expectedEarsFeatures(cached)) {
            return Step.Result.fail("Bob's Ears cache lacks Alice's TALL/BACK features: " + cached);
        }
        try {
            Object renderer = minecraft.getEntityRenderDispatcher().getRenderer(subject);
            Object rendered = rendererFeatures(renderer, subject);
            if (!expectedEarsFeatures(rendered)) {
                return Step.Result.fail(
                        "Ears renderer lookup returned wrong remote features: " + rendered);
            }
            if (!earsRendererLayerAttached(renderer)) {
                return Step.Result.fail("Ears feature layer is not attached to Alice's renderer");
            }
            Class<?> featuresClass = Class.forName(
                    "com.unascribed.ears.api.features.EarsFeatures");
            Object stored = featuresClass.getMethod("getById", UUID.class)
                    .invoke(null, subject.getUUID());
            if (!expectedEarsFeatures(stored)) {
                return Step.Result.fail(
                        "Ears public storage lacks Alice's remote TALL/BACK features: " + stored);
            }
        } catch (ReflectiveOperationException failure) {
            return Step.Result.fail("could not inspect remote Ears renderer state: "
                    + concise(failure));
        }
        return Step.Result.pass("Bob renders Alice's network skin through Ears with TALL ears "
                + "and a BACK tail; cache, public storage, and renderer lookup agree; "
                + skin.message());
    }

    private Step.Result checkRemoteCpmBaseline(AbstractClientPlayer subject) {
        CpmRemoteState state = inspectRemoteCpm(subject.getUUID());
        if (!state.inspected()) return Step.Result.fail(state.detail());
        if (!state.profilePresent()) {
            return Step.Result.fail("CPM has not discovered Alice on Bob's client");
        }
        if (!state.playerLoaded() || !state.definitionPresent()
                || !state.renderable() || state.errorPresent()) {
            return Step.Result.fail("CPM has not loaded a healthy renderable model for Alice: "
                    + state.detail());
        }
        remoteBaselineDefinition = state.definition();
        PlayerAppearance appearance = PlayerAppearanceRepository.getInstance()
                .getAppearance(subject.getUUID());
        remoteBaselineSkinId = appearance == null ? null : appearance.getSkinId();
        return Step.Result.pass("Bob's CPM definition loader has a healthy renderable model "
                + "for remote Alice: " + state.detail());
    }

    private Step.Result checkRemoteCpmApplied(AbstractClientPlayer subject) {
        if (!remoteBaselineObserved) {
            return Step.Result.fail("CPM remote model baseline was not latched");
        }
        if (!embeddedAcknowledged || embeddedHash == null) {
            return Step.Result.fail("Bob has not confirmed Alice's embedded CPM skin yet");
        }
        Step.Result skin = checkRemoteQuickSkin(subject);
        if (!skin.pass()) return skin;
        PlayerAppearance current = PlayerAppearanceRepository.getInstance()
                .getAppearance(subject.getUUID());
        if (current == null
                || ("local_skin:" + embeddedHash).equals(current.getSkinId())) {
            return Step.Result.fail("Alice still wears the embedded CPM skin, not the plaid reset");
        }
        CpmRemoteState state = inspectRemoteCpm(subject.getUUID());
        if (!state.inspected()) return Step.Result.fail(state.detail());
        if (state.errorPresent()) {
            return Step.Result.fail("CPM replaced Alice's model with an error: " + state.detail());
        }
        if (state.definitionPresent() && state.renderable()) {
            return Step.Result.fail("CPM still renders Alice's model after the skin reset: "
                    + state.detail());
        }
        return Step.Result.pass("Bob witnessed CPM release Alice's remote model and resolve "
                + "Quick Skin's normal network texture; " + state.detail() + "; "
                + skin.message());
    }

    private Step.Result checkRemoteQuickSkin(AbstractClientPlayer subject) {
        UUID subjectId = subject.getUUID();
        PlayerAppearance appearance = PlayerAppearanceRepository.getInstance()
                .getAppearance(subjectId);
        if (appearance == null) {
            return Step.Result.fail("Bob has no Quick Skin appearance for Alice");
        }
        String skinId = appearance.getSkinId();
        if (skinId == null || !skinId.startsWith("local_skin:")) {
            return Step.Result.fail("Alice's remote skin id is not network-backed: " + skinId);
        }
        String hash = skinId.substring("local_skin:".length());
        if (!NetworkTextureCache.getInstance().hasTexture(hash, "skin")) {
            return Step.Result.fail("Alice's skin bytes are not cached on Bob: " + hash);
        }
        String expected = "quickskin:network/skin/" + hash;
        String actual = VanillaShim.skinTexture(subject);
        if (!expected.equals(actual)) {
            return Step.Result.fail(
                    "Alice's renderer texture is " + actual + ", expected " + expected);
        }
        return Step.Result.pass("skinId=" + skinId + "; bytes cached; renderer=" + actual);
    }

    private CpmRemoteState inspectRemoteCpm(UUID subjectId) {
        CpmEmbeddedSkinProof.Definition state = CpmEmbeddedSkinProof.definition(subjectId);
        return new CpmRemoteState(state.inspected(), state.profilePresent(),
                state.playerLoaded(), state.definition() != null, state.renderable(),
                state.error() != null, state.definition(), "Alice: " + state.detail());
    }

    private Object rendererFeatures(Object playerRenderer, AbstractClientPlayer subject)
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
                        || !Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 1) {
                    continue;
                }
                incompatibleLookup = method;
                incompatibleOwner = earsRenderer;
                Object argument = rendererLookupArgument(
                        method.getParameterTypes()[0], playerRenderer, subject);
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
                "no remote renderer argument available for Ears lookup: " + parameter);
    }

    private Object rendererLookupArgument(
            Class<?> expectedType, Object playerRenderer, AbstractClientPlayer subject)
            throws ReflectiveOperationException {
        if (expectedType.isInstance(subject)) return subject;
        for (Method method : playerRenderer.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())
                    || parameters.length != 2
                    || !parameters[0].isInstance(subject)
                    || parameters[1] != float.class
                    || method.getReturnType() == void.class
                    || method.getReturnType().isPrimitive()) {
                continue;
            }
            Object candidate = method.invoke(playerRenderer, subject, 0.0f);
            if (expectedType.isInstance(candidate)) return candidate;
        }
        return null;
    }

    private static boolean earsRendererLayerAttached(Object playerRenderer) {
        for (Class<?> type = playerRenderer.getClass(); type != null;
                type = type.getSuperclass()) {
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

    private static boolean expectedEarsFeatures(Object value) {
        return value != null
                && !EarsCompatIntegration.isDisabledResult(value)
                && "true".equals(publicField(value, "enabled"))
                && "TALL".equals(publicField(value, "earMode"))
                && "BACK".equals(publicField(value, "tailMode"));
    }

    private static String publicField(Object value, String name) {
        try {
            return String.valueOf(value.getClass().getField(name).get(value));
        } catch (ReflectiveOperationException failure) {
            return "<missing>";
        }
    }

    private static boolean supportsRemoteEvidence(String modId) {
        return "ears".equals(modId) || "cpm".equals(modId);
    }

    private static AbstractClientPlayer findOther(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) return null;
        UUID localId = minecraft.player.getUUID();
        for (Player player : minecraft.level.players()) {
            if (player instanceof AbstractClientPlayer clientPlayer
                    && !clientPlayer.getUUID().equals(localId)) {
                return clientPlayer;
            }
        }
        return null;
    }

    private static String concise(Throwable failure) {
        Throwable current = failure;
        if (current instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    private record CpmRemoteState(
            boolean inspected,
            boolean profilePresent,
            boolean playerLoaded,
            boolean definitionPresent,
            boolean renderable,
            boolean errorPresent,
            Object definition,
            String detail) {
    }
}
