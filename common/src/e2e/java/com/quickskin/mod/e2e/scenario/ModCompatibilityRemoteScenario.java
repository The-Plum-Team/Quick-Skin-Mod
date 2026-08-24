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
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import com.quickskin.mod.networking.NetworkSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Proves that the selected optional integration changes what a second client renders for Alice.
 * Alice coordinates the two states without screenshots; Bob captures both from one fixed rear
 * camera after checking the real remote Ears or CPM state on his own client.
 */
public final class ModCompatibilityRemoteScenario implements Scenario {
    private static final double VANTAGE_DISTANCE = 5.0;
    private static final double VANTAGE_SIDE = 1.5;
    private static final float SUBJECT_REAR_YAW = 180.0f;

    private volatile CompatibilityProbe.Result probe =
            new CompatibilityProbe.Result(false, "probe not executed");
    private volatile boolean sawRemoteBaseline;

    private double targetX;
    private double targetY;
    private double targetZ;
    private boolean vantageSet;

    @Override
    public ScenarioId id() {
        return ScenarioId.MOD_COMPATIBILITY_REMOTE;
    }

    @Override
    public List<Step> build(Minecraft minecraft) {
        String role = System.getProperty("quickskin.e2e.role", "client_a");
        return "client_b".equals(role)
                ? buildObserver(minecraft)
                : buildSubject(minecraft);
    }

    private List<Step> buildSubject(Minecraft minecraft) {
        String modId = selectedMod();
        ModCompatibilityFeature feature = ModCompatibilityFeature.create(modId, minecraft);
        List<Step> steps = new ArrayList<>();
        steps.add(integrationStep(modId));

        steps.add(Step.of("prepare_remote_baseline")
                .action(() -> {
                    disableAutomaticOwnSkin();
                    feature.prepareBaseline();
                })
                .minTicks(feature.baselineMinTicks())
                .ready(() -> probe.active() && feature.baselineReady())
                .settleTicks(feature.baselineSettleTicks())
                .timeoutTicks(feature.baselineTimeoutTicks())
                .assertion(() -> probe.active()
                        ? feature.assertBaseline()
                        : Step.Result.fail(probe.detail())));

        steps.add(Step.of("await_observer_baseline")
                .action(() -> E2ELog.info(
                        "Alice is holding the optional-mod baseline until Bob acknowledges it"))
                .minTicks(10)
                .ready(() -> observerAcknowledged(minecraft))
                .timeoutTicks(20 * 150)
                .assertion(() -> observerAcknowledged(minecraft)
                        ? Step.Result.pass("Bob acknowledged the remotely rendered baseline")
                        : Step.Result.fail("Bob never acknowledged the remote baseline")));

        steps.add(Step.of("apply_remote_change")
                .action(feature::applyQuickSkinFeature)
                .minTicks(feature.appliedMinTicks())
                .ready(() -> probe.active() && feature.quickSkinFeatureReady())
                .settleTicks(feature.appliedSettleTicks())
                .timeoutTicks(feature.appliedTimeoutTicks())
                .assertion(() -> {
                    CompatibilityProbe.Result after =
                            CompatibilityProbe.verifyConfiguredIntegration();
                    if (!after.active()) return Step.Result.fail(after.detail());
                    return feature.assertQuickSkinFeature();
                }));
        return steps;
    }

    private List<Step> buildObserver(Minecraft minecraft) {
        String version = System.getProperty("quickskin.e2e.version", "v1_20_1");
        String role = System.getProperty("quickskin.e2e.role", "client_b");
        String modId = selectedMod();
        UUID observerId = minecraft.player.getUUID();
        List<Step> steps = new ArrayList<>();
        steps.add(integrationStep(modId));

        steps.add(Step.of("confirm_self")
                .action(() -> {
                    try {
                        disableAutomaticOwnSkin();
                        PlayerAppearanceService.getInstance()
                                .applyLook(observerId, "", "", "classic");
                        NetworkSyncService.getInstance()
                                .syncAppearance(observerId, "", "", "classic");
                        E2ELog.info("Bob confirmed the compatibility observation session");
                    } catch (Throwable failure) {
                        E2ELog.error("remote compatibility confirm_self failed", failure);
                    }
                })
                .minTicks(10)
                .ready(() -> minecraft.getConnection() != null)
                .timeoutTicks(200)
                .assertion(() -> minecraft.getConnection() != null
                        ? Step.Result.pass("connected; sent observer confirmation")
                        : Step.Result.fail("no server connection")));

        steps.add(Step.of("observe_remote_baseline")
                .action(() -> stepTowardVantage(minecraft))
                .minTicks(5)
                .ready(() -> {
                    stepTowardVantage(minecraft);
                    return atVantage(minecraft)
                            && checkRemoteState(minecraft, modId, false).pass();
                })
                .settleTicks(20)
                .timeoutTicks(20 * 90)
                .screenshot(version + "_compat_remote_01_baseline_" + role + ".png")
                .assertion(() -> acknowledgeRemoteBaseline(
                        minecraft, observerId, modId)));

        steps.add(Step.of("observe_remote_applied")
                .action(() -> stepTowardVantage(minecraft))
                .minTicks(5)
                .ready(() -> {
                    stepTowardVantage(minecraft);
                    return atVantage(minecraft)
                            && checkRemoteState(minecraft, modId, true).pass();
                })
                .settleTicks(20)
                .timeoutTicks(20 * 90)
                .screenshot(version + "_compat_remote_02_applied_" + role + ".png")
                .assertion(() -> {
                    if (!sawRemoteBaseline) {
                        return Step.Result.fail(
                                "no asserted remote baseline was captured before the change");
                    }
                    Step.Result state = checkRemoteState(minecraft, modId, true);
                    if (!state.pass()) return state;
                    Step.Result rear = checkRearComposition(minecraft);
                    if (!rear.pass()) return rear;
                    return Step.Result.pass("remote optional-mod transition witnessed: "
                            + state.message() + "; " + rear.message());
                }));
        return steps;
    }

    private Step integrationStep(String modId) {
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

    private Step.Result acknowledgeRemoteBaseline(
            Minecraft minecraft, UUID observerId, String modId) {
        Step.Result state = checkRemoteState(minecraft, modId, false);
        if (!state.pass()) return state;
        Step.Result rear = checkRearComposition(minecraft);
        if (!rear.pass()) return rear;
        try {
            NetworkSyncService.getInstance()
                    .syncAppearance(observerId, "", "", "slim");
            sawRemoteBaseline = true;
            E2ELog.info("Bob acknowledged the captured optional-mod baseline");
            return Step.Result.pass("remote optional-mod baseline captured: "
                    + state.message() + "; acknowledgement sent; " + rear.message());
        } catch (Throwable failure) {
            E2ELog.error("failed to acknowledge the remote compatibility baseline", failure);
            return Step.Result.fail("baseline captured but acknowledgement failed: " + failure);
        }
    }

    private Step.Result checkRemoteState(
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
        return Step.Result.pass("Bob's CPM definition loader has a healthy renderable model "
                + "for remote Alice: " + state.detail());
    }

    private Step.Result checkRemoteCpmApplied(AbstractClientPlayer subject) {
        if (!sawRemoteBaseline) {
            return Step.Result.fail("CPM remote model baseline was not latched");
        }
        Step.Result skin = checkRemoteQuickSkin(subject);
        if (!skin.pass()) return skin;
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
        try {
            Class<?> accessClass = Class.forName("com.tom.cpm.shared.MinecraftClientAccess");
            Object access = accessClass.getMethod("get").invoke(null);
            if (access == null) return CpmRemoteState.failed("CPM client access is null");
            Object loader = accessClass.getMethod("getDefinitionLoader").invoke(access);
            if (loader == null) return CpmRemoteState.failed("CPM definition loader is null");
            Object playersValue = accessClass.getMethod("getPlayers").invoke(access);
            if (!(playersValue instanceof Iterable<?> players)) {
                return CpmRemoteState.failed("CPM client players are not iterable");
            }
            Method getUuid = loader.getClass().getMethod("getGP_UUID", Object.class);
            Method getLoadedPlayer = loader.getClass().getMethod("getLoadedPlayer", Object.class);
            for (Object gamePlayer : players) {
                if (gamePlayer == null || !subjectId.equals(getUuid.invoke(loader, gamePlayer))) {
                    continue;
                }
                Object loadedPlayer = getLoadedPlayer.invoke(loader, gamePlayer);
                if (loadedPlayer == null) {
                    return new CpmRemoteState(true, true, false, false,
                            false, false, "Alice profile present; loaded CPM player absent");
                }
                Object definition = loadedPlayer.getClass()
                        .getMethod("getModelDefinition").invoke(loadedPlayer);
                if (definition == null) {
                    return new CpmRemoteState(true, true, true, false,
                            false, false, "Alice CPM player loaded; model definition absent");
                }
                Object error = definition.getClass().getMethod("getError").invoke(definition);
                boolean renderable = Boolean.TRUE.equals(
                        definition.getClass().getMethod("doRender").invoke(definition));
                return new CpmRemoteState(true, true, true, true,
                        renderable, error != null,
                        "definition=" + definition.getClass().getName()
                                + "; renderable=" + renderable + "; error=" + error);
            }
            return new CpmRemoteState(true, false, false, false,
                    false, false, "CPM client player list does not contain Alice");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return CpmRemoteState.failed(
                    "remote CPM inspection failed: " + concise(failure));
        }
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

    private boolean observerAcknowledged(Minecraft minecraft) {
        AbstractClientPlayer observer = findOther(minecraft);
        if (observer == null) return false;
        PlayerAppearance acknowledgement = PlayerAppearanceRepository.getInstance()
                .getAppearance(observer.getUUID());
        return acknowledgement != null && "slim".equals(acknowledgement.getModel());
    }

    private static void disableAutomaticOwnSkin() {
        ClientConfig config = ClientConfig.getInstance();
        config.enablePlayerOwnSkinSystem = false;
        config.activeSkinHash = "";
        config.playerOwnSkinHash = "";
        config.activeCapeHash = "";
    }

    private static String selectedMod() {
        return System.getProperty("quickskin.e2e.compatibility", "").trim();
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

    private void stepTowardVantage(Minecraft minecraft) {
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

    private boolean atVantage(Minecraft minecraft) {
        if (!vantageSet || minecraft.player == null || findOther(minecraft) == null) {
            return false;
        }
        return Math.hypot(
                minecraft.player.getX() - targetX,
                minecraft.player.getZ() - targetZ) < 0.4
                && checkRearComposition(minecraft).pass();
    }

    private Step.Result checkRearComposition(Minecraft minecraft) {
        if (minecraft.player == null) {
            return Step.Result.fail("rear-view observer is unavailable");
        }
        AbstractClientPlayer subject = findOther(minecraft);
        if (subject == null) return Step.Result.fail("rear-view subject is unavailable");
        return DefaultSkinEvidenceView.checkRearView(
                subject, minecraft.player, SUBJECT_REAR_YAW);
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
            String detail) {
        private static CpmRemoteState failed(String detail) {
            return new CpmRemoteState(
                    false, false, false, false, false, false, detail);
        }
    }
}
