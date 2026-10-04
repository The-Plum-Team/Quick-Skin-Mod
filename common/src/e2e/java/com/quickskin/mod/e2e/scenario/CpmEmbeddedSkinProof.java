package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.compat.CpmCapabilities;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Checks for a skin that carries a CPM model in its pixels, imported through Quick Skin.
 *
 * <p>The CPM compatibility apply steps run these checks in an intermediate phase that takes no
 * screenshot: the bytes Quick Skin stored (locally) or received (remotely) must hash to their
 * content id and equal the bundled fixture texel for texel, and where CPM reads Quick Skin's skin
 * file (the file-backed bridge, 1.20.1 to 1.21.3) CPM must load the model from them. The harness
 * decides that band from the runtime version and requires the product's own capability to agree,
 * so a capability regression fails the step instead of silently skipping the model check. Only
 * APIs that already existed in Quick Skin 3.0.1 are used, so the same harness proves an older
 * product wrong.</p>
 */
final class CpmEmbeddedSkinProof {
    static final int SIZE = 64;
    private static final int MAX_STORED_SKIN_BYTES = 1024 * 1024;
    private static volatile int[] expectedArgb;

    private CpmEmbeddedSkinProof() {
    }

    /** The bridge band: whether CPM is expected to read the embedded model from Quick Skin's file. */
    record Band(boolean bridge, String version, String mismatch) {
        String describe() {
            return bridge
                    ? "file-backed CPM bridge band " + version
                    : "bridge degraded by design on " + version + ": bytes only";
        }
    }

    /**
     * The harness rule (1.20.1 to 1.21.3) cross-checked with
     * {@code CpmCapabilities.current().supportsHttpTextureBridge()}.
     */
    static Band band() {
        String version = runtimeVersion();
        Boolean harness = harnessBridgeBand(version);
        boolean product;
        try {
            product = CpmCapabilities.current().supportsHttpTextureBridge();
        } catch (RuntimeException | LinkageError failure) {
            return new Band(false, version,
                    "CPM capability matrix is unreadable: " + concise(failure));
        }
        if (harness == null) {
            return new Band(false, version, "unrecognised runtime version '" + version + "'");
        }
        if (harness != product) {
            return new Band(false, version, "the harness expects the CPM skin-file bridge to be "
                    + (harness ? "available" : "degraded") + " on " + version
                    + " but CpmCapabilities says supportsHttpTextureBridge=" + product);
        }
        return new Band(product, version, null);
    }

    static String runtimeVersion() {
        String version = System.getProperty("quickskin.e2e.version", "")
                .trim()
                .replace('_', '.');
        return version.startsWith("v") ? version.substring(1) : version;
    }

    /** True from 1.20.1 to 1.21.3, false later, null when the version cannot be read. */
    static Boolean harnessBridgeBand(String version) {
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            if (major != 1) return major > 1 ? Boolean.FALSE : null;
            if (minor == 20) return patch >= 1 ? Boolean.TRUE : null;
            if (minor == 21) return patch <= 3;
            return minor > 21 ? Boolean.FALSE : null;
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    /** Reads the file Quick Skin stored for {@code hash} and checks it with {@link #bytesExact}. */
    static Step.Result storedFileExact(Path stored, String hash) {
        try {
            return bytesExact(BoundedFileReader.readBytes(stored, MAX_STORED_SKIN_BYTES), hash);
        } catch (Exception failure) {
            return Step.Result.fail("stored skin " + stored + " is unreadable: " + concise(failure));
        }
    }

    /**
     * Passes when {@code bytes} hash to {@code hash} and decode to exactly the bundled fixture's
     * 64x64 texels, alpha and colour of every texel included.
     */
    static Step.Result bytesExact(byte[] bytes, String hash) {
        if (bytes == null || bytes.length == 0) return Step.Result.fail("no skin bytes");
        String contentId = HashUtil.computeContentId(bytes);
        if (contentId == null || !contentId.equals(hash)) {
            return Step.Result.fail("bytes hash to " + contentId + ", not their id " + hash);
        }
        BufferedImage image;
        int[] expected;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
            expected = expectedArgb();
        } catch (Exception failure) {
            return Step.Result.fail("skin bytes do not decode: " + concise(failure));
        }
        if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE) {
            return Step.Result.fail("skin is not a 64x64 PNG");
        }
        int[] actual = image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        int differing = 0;
        int first = -1;
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != expected[i]) {
                if (first < 0) first = i;
                differing++;
            }
        }
        if (differing > 0) {
            return Step.Result.fail(differing + " texels differ from the bundled embedded-model "
                    + "skin, first (" + (first % SIZE) + "," + (first / SIZE) + ") "
                    + argb(expected[first]) + "->" + argb(actual[first]));
        }
        return Step.Result.pass("sha256 equals the id, " + actual.length + "/" + actual.length
                + " texels equal the bundled embedded-model skin");
    }

    private static int[] expectedArgb() throws Exception {
        int[] cached = expectedArgb;
        if (cached == null) {
            cached = TestAssets.cpmEmbeddedSkinArgb();
            expectedArgb = cached;
        }
        return cached;
    }

    /** What CPM's definition loader currently holds for one player, without loading it. */
    record Definition(
            boolean inspected,
            boolean profilePresent,
            boolean playerLoaded,
            Object definition,
            boolean renderable,
            Object error,
            String detail) {
        boolean healthy() {
            return definition != null && renderable && error == null;
        }

        private static Definition failed(String detail) {
            return new Definition(false, false, false, null, false, null, detail);
        }
    }

    /** CPM's model definition for {@code playerId}; works for the local and remote players. */
    static Definition definition(UUID playerId) {
        try {
            Class<?> accessClass = Class.forName("com.tom.cpm.shared.MinecraftClientAccess");
            Object access = accessClass.getMethod("get").invoke(null);
            if (access == null) return Definition.failed("CPM client access is null");
            Object loader = accessClass.getMethod("getDefinitionLoader").invoke(access);
            if (loader == null) return Definition.failed("CPM definition loader is null");
            Object playersValue = accessClass.getMethod("getPlayers").invoke(access);
            if (!(playersValue instanceof Iterable<?> players)) {
                return Definition.failed("CPM client players are not iterable");
            }
            Method getUuid = loader.getClass().getMethod("getGP_UUID", Object.class);
            for (Object gamePlayer : players) {
                if (gamePlayer == null || !playerId.equals(getUuid.invoke(loader, gamePlayer))) {
                    continue;
                }
                Object loadedPlayer = getOrLoadCpmPlayer(loader, gamePlayer);
                if (loadedPlayer == null) {
                    return new Definition(true, true, false, null, false, null,
                            "profile present; loaded CPM player absent");
                }
                Object definition = loadedPlayer.getClass()
                        .getMethod("getModelDefinition").invoke(loadedPlayer);
                if (definition == null) {
                    return new Definition(true, true, true, null, false, null,
                            "CPM player loaded; model definition absent (no-definition)");
                }
                Object error = definition.getClass().getMethod("getError").invoke(definition);
                boolean renderable = Boolean.TRUE.equals(
                        definition.getClass().getMethod("doRender").invoke(definition));
                return new Definition(true, true, true, definition, renderable, error,
                        "definition=" + definition.getClass().getName()
                                + "; renderable=" + renderable + "; error=" + error);
            }
            return new Definition(true, false, false, null, false, null,
                    "CPM client player list does not contain the player");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return Definition.failed("CPM inspection failed: " + concise(failure));
        }
    }

    private static Object getOrLoadCpmPlayer(Object loader, Object gamePlayer)
            throws ReflectiveOperationException {
        try {
            return loader.getClass().getMethod("getLoadedPlayer", Object.class)
                    .invoke(loader, gamePlayer);
        } catch (NoSuchMethodException unsupported) {
            // CPM 0.6.22 and earlier expose only the public lazy-loading lookup. Later releases
            // added getLoadedPlayer, which remains preferable because it has no loading side effect.
            return loader.getClass().getMethod("loadPlayer", Object.class, String.class)
                    .invoke(loader, gamePlayer, "player");
        }
    }

    /**
     * Why CPM does not yet show the embedded model for a player, or null when it does: a loaded,
     * renderable, error-free definition that is not the protected baseline model's object.
     */
    static String modelWaitReason(Definition state, Object baselineDefinition) {
        if (!state.inspected()) return state.detail();
        if (state.definition() == null) return "CPM has no model yet: " + state.detail();
        if (baselineDefinition != null && state.definition() == baselineDefinition) {
            return "CPM still holds the protected baseline model";
        }
        if (state.error() != null) return "CPM model failed: " + state.detail();
        if (!state.renderable()) return "CPM model is not renderable: " + state.detail();
        return null;
    }

    /** Logs each changed waiting reason, up to a bound, so a timeout names what never held. */
    static final class WaitLog {
        private final String label;
        private String last;
        private int logged;

        WaitLog(String label) {
            this.label = label;
        }

        void note(String reason) {
            if (reason != null && !reason.equals(last) && logged < 32) {
                logged++;
                E2ELog.info(label + " waiting: " + reason);
            }
            last = reason;
        }
    }

    private static String argb(int value) {
        return String.format("%08x", value);
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
