package com.quickskin.mod.client.compat;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Compile-time CPM capability matrix for every actively built Minecraft band.
 *
 * <p>The explicit {@code .cpmmodel} workflow is available everywhere. Reading
 * embedded CPM payloads from a QuickSkin-selected PNG is intentionally marked
 * degraded from 1.21.11 onward because current CPM reads the authenticated
 * profile payload instead of Minecraft's registered player texture.</p>
 */
public final class CpmCapabilities {
    public enum Availability {
        AVAILABLE,
        DEGRADED
    }

    public enum Band {
        MC_1_20_1("1.20.1", RenderPipeline.IMMEDIATE),
        MC_1_21_1("1.21.1", RenderPipeline.IMMEDIATE),
        MC_1_21_10("1.21.10", RenderPipeline.RENDER_STATE),
        MC_1_21_11("1.21.11", RenderPipeline.RENDER_STATE),
        MC_26_1_2("26.1.2", RenderPipeline.EXTRACTOR),
        MC_26_2("26.2", RenderPipeline.DEFERRED_COLLECTOR);

        private final String displayName;
        private final RenderPipeline renderPipeline;

        Band(String displayName, RenderPipeline renderPipeline) {
            this.displayName = displayName;
            this.renderPipeline = renderPipeline;
        }

        public String displayName() {
            return displayName;
        }

        public RenderPipeline renderPipeline() {
            return renderPipeline;
        }
    }

    public enum RenderPipeline {
        IMMEDIATE,
        RENDER_STATE,
        EXTRACTOR,
        DEFERRED_COLLECTOR
    }

    public record Capabilities(
            Availability modelWorkflow,
            Availability embeddedPngBridge,
            Availability entityPreview,
            RenderPipeline renderPipeline
    ) {
        public boolean supportsHttpTextureBridge() {
            return embeddedPngBridge == Availability.AVAILABLE;
        }

        public boolean usesDeferredRendering() {
            return renderPipeline != RenderPipeline.IMMEDIATE;
        }
    }

    private static final Map<Band, Capabilities> MATRIX;

    static {
        EnumMap<Band, Capabilities> matrix = new EnumMap<>(Band.class);
        matrix.put(Band.MC_1_20_1, availableWithEmbeddedBridge(Band.MC_1_20_1));
        matrix.put(Band.MC_1_21_1, availableWithEmbeddedBridge(Band.MC_1_21_1));
        matrix.put(Band.MC_1_21_10, availableWithDegradedEmbeddedBridge(Band.MC_1_21_10));
        matrix.put(Band.MC_1_21_11, availableWithDegradedEmbeddedBridge(Band.MC_1_21_11));
        matrix.put(Band.MC_26_1_2, availableWithDegradedEmbeddedBridge(Band.MC_26_1_2));
        matrix.put(Band.MC_26_2, availableWithDegradedEmbeddedBridge(Band.MC_26_2));
        MATRIX = Collections.unmodifiableMap(matrix);
    }

    private CpmCapabilities() {
    }

    public static Map<Band, Capabilities> matrix() {
        return MATRIX;
    }

    public static Capabilities forBand(Band band) {
        return MATRIX.get(band);
    }

    public static Band currentBand() {
        //? if <1.21 {
        return Band.MC_1_20_1;
        //?} else if <1.21.9 {
        return Band.MC_1_21_1;
        //?} else if <1.21.11 {
        return Band.MC_1_21_10;
        //?} else if <26.1.2 {
        return Band.MC_1_21_11;
        //?} else if <26.2 {
        return Band.MC_26_1_2;
        //?} else {
        return Band.MC_26_2;
        //?}
    }

    public static Capabilities current() {
        return forBand(currentBand());
    }

    private static Capabilities availableWithEmbeddedBridge(Band band) {
        return new Capabilities(
                Availability.AVAILABLE,
                Availability.AVAILABLE,
                Availability.AVAILABLE,
                band.renderPipeline()
        );
    }

    private static Capabilities availableWithDegradedEmbeddedBridge(Band band) {
        return new Capabilities(
                Availability.AVAILABLE,
                Availability.DEGRADED,
                Availability.AVAILABLE,
                band.renderPipeline()
        );
    }
}
