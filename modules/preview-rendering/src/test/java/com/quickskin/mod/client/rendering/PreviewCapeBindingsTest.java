package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewCapeBindingsTest {

    /** Stands in for the era's render key: the previewed entity, or the submitted render state. */
    private static final class RenderKey {
        private final String label;

        RenderKey(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Test
    void anUnboundKeyKeepsTheAppliedCape() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();

        PreviewCapeBindings.Resolution<String> resolution = bindings.consume(new RenderKey("world"));

        assertEquals(PreviewCapeBindings.Decision.WORN, resolution.decision());
        assertNull(resolution.texture());
        assertFalse(resolution.overridesWornCape());
    }

    @Test
    void aBoundKeyPrefersThePreviewCapeOverTheAppliedOne() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();
        RenderKey preview = new RenderKey("preview");

        bindings.bind(preview, "quickskin:cape/selected");
        PreviewCapeBindings.Resolution<String> resolution = bindings.consume(preview);

        assertEquals(PreviewCapeBindings.Decision.PREVIEW, resolution.decision());
        assertEquals("quickskin:cape/selected", resolution.texture());
        assertTrue(resolution.overridesWornCape());
    }

    @Test
    void bindingNoSelectionHidesTheCapeInsteadOfFallingBackToTheWornOne() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();
        RenderKey preview = new RenderKey("preview");

        bindings.bind(preview, null);
        PreviewCapeBindings.Resolution<String> resolution = bindings.consume(preview);

        assertEquals(PreviewCapeBindings.Decision.HIDDEN, resolution.decision());
        assertNull(resolution.texture());
        assertTrue(resolution.overridesWornCape());
    }

    @Test
    void bindingOnePreviewLeavesTheSamePlayerRenderedElsewhereUntouched() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();
        RenderKey previewDraw = new RenderKey("gui");
        RenderKey worldDraw = new RenderKey("world");

        bindings.bind(previewDraw, "quickskin:cape/selected");

        assertEquals(PreviewCapeBindings.Decision.WORN, bindings.peek(worldDraw).decision());
        assertEquals(PreviewCapeBindings.Decision.PREVIEW, bindings.peek(previewDraw).decision());
    }

    @Test
    void keysAreComparedByIdentityNotEquality() {
        PreviewCapeBindings<String, String> bindings = new PreviewCapeBindings<>();
        String bound = new String("same-text");
        String equalButDistinct = new String("same-text");

        bindings.bind(bound, "quickskin:cape/selected");

        assertEquals(PreviewCapeBindings.Decision.PREVIEW, bindings.peek(bound).decision());
        assertEquals(PreviewCapeBindings.Decision.WORN, bindings.peek(equalButDistinct).decision());
    }

    @Test
    void consumingReleasesTheBindingSoLaterDrawsUseTheAppliedCape() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();
        RenderKey preview = new RenderKey("preview");
        bindings.bind(preview, "quickskin:cape/selected");

        assertEquals(PreviewCapeBindings.Decision.PREVIEW, bindings.consume(preview).decision());

        assertEquals(PreviewCapeBindings.Decision.WORN, bindings.consume(preview).decision());
        assertEquals(0, bindings.size());
    }

    @Test
    void peekingLeavesTheBindingInPlace() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();
        RenderKey preview = new RenderKey("preview");
        bindings.bind(preview, "quickskin:cape/selected");

        assertEquals(PreviewCapeBindings.Decision.PREVIEW, bindings.peek(preview).decision());
        assertEquals(1, bindings.size());
        assertEquals(PreviewCapeBindings.Decision.PREVIEW, bindings.peek(preview).decision());
    }

    @Test
    void unbindingRestoresTheAppliedCape() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();
        RenderKey preview = new RenderKey("preview");

        bindings.bind(preview, "quickskin:cape/selected");
        bindings.unbind(preview);

        assertEquals(PreviewCapeBindings.Decision.WORN, bindings.consume(preview).decision());
        assertEquals(0, bindings.size());
    }

    @Test
    void unbindingAnAlreadyConsumedKeyIsHarmless() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();
        RenderKey preview = new RenderKey("preview");

        bindings.bind(preview, "quickskin:cape/selected");
        bindings.consume(preview);
        bindings.unbind(preview);

        assertEquals(0, bindings.size());
    }

    @Test
    void rebindingReplacesTheTextureWithoutGrowingTheMap() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();
        RenderKey preview = new RenderKey("preview");

        bindings.bind(preview, "quickskin:cape/first");
        bindings.bind(preview, "quickskin:cape/second");

        assertEquals(1, bindings.size());
        assertEquals("quickskin:cape/second", bindings.peek(preview).texture());
    }

    @Test
    void abandonedBindingsStayBounded() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>(4);

        for (int i = 0; i < 64; i++) {
            bindings.bind(new RenderKey("dropped-" + i), "quickskin:cape/" + i);
        }

        assertTrue(bindings.size() <= 4, "bindings must stay bounded, was " + bindings.size());
    }

    @Test
    void clearDropsEveryBinding() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();
        RenderKey first = new RenderKey("first");
        RenderKey second = new RenderKey("second");
        bindings.bind(first, "quickskin:cape/a");
        bindings.bind(second, null);

        bindings.clear();

        assertEquals(0, bindings.size());
        assertEquals(PreviewCapeBindings.Decision.WORN, bindings.consume(first).decision());
        assertEquals(PreviewCapeBindings.Decision.WORN, bindings.consume(second).decision());
    }

    @Test
    void nullKeysAreIgnoredRatherThanBound() {
        PreviewCapeBindings<RenderKey, String> bindings = new PreviewCapeBindings<>();

        bindings.bind(null, "quickskin:cape/selected");
        bindings.unbind(null);

        assertEquals(0, bindings.size());
        assertEquals(PreviewCapeBindings.Decision.WORN, bindings.consume(null).decision());
        assertNull(bindings.peek(null).texture());
    }

    @Test
    void aNonPositiveBoundIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PreviewCapeBindings<>(0));
    }
}
