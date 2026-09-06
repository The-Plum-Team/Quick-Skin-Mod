package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the 3D player preview has to be submitted for it to end up in front of the host screen.
 *
 * <p>These are the properties the widget and the post-screen overlay pass rely on and cannot check
 * for themselves: that the older, depth-buffered lanes are left completely alone, that exactly the
 * two vanilla screens the mod injects into defer on the newer painter's-order lanes, and that the
 * rule stays total when a new surface or pipeline is added.
 */
class PreviewCompositeOrderTest {

    /** The screens the mod injects a widget into but does not draw itself. */
    private static final Set<PreviewCompositeOrder.Surface> VANILLA_HOSTED = EnumSet.of(
            PreviewCompositeOrder.Surface.TITLE_SCREEN,
            PreviewCompositeOrder.Surface.PAUSE_MENU);

    // --- the depth-buffered lanes must not move at all ---

    /**
     * On 1.20.1 and 1.21.1 the preview is drawn at GUI z = +50 with depth testing on, so it already
     * covers whatever the host screen paints afterwards regardless of submission order. Nothing may
     * defer there: deferring would change when those lanes draw, for no visual gain.
     */
    @Test
    void theDepthOrderedPipelineNeverDefers() {
        for (PreviewCompositeOrder.Surface surface : PreviewCompositeOrder.Surface.values()) {
            assertSame(PreviewCompositeOrder.Submission.WITH_THE_WIDGET,
                    PreviewCompositeOrder.submissionFor(
                            PreviewCompositeOrder.Pipeline.DEPTH_ORDERED, surface),
                    "depth-ordered " + surface + " must stay inline");
            assertFalse(PreviewCompositeOrder.defersToHostScreenOverlay(
                            PreviewCompositeOrder.Pipeline.DEPTH_ORDERED, surface),
                    "depth-ordered " + surface + " must not defer");
        }
    }

    // --- the painter's-order lanes defer exactly the vanilla-hosted screens ---

    /**
     * The splash bug itself: on the painter's pipeline {@code TitleScreen.render} paints the logo,
     * the splash and the version string after {@code super.render(...)}, so a preview left in the
     * renderable pass is overdrawn. This is the case the fix exists for.
     */
    @Test
    void theTitleScreenDefersOnThePaintersPipeline() {
        assertSame(PreviewCompositeOrder.Submission.AFTER_THE_HOST_SCREEN,
                PreviewCompositeOrder.submissionFor(
                        PreviewCompositeOrder.Pipeline.PAINTERS_ORDERED,
                        PreviewCompositeOrder.Surface.TITLE_SCREEN));
    }

    /** The pause menu is the other vanilla screen the mod injects into, and follows the same rule. */
    @Test
    void thePauseMenuDefersOnThePaintersPipeline() {
        assertSame(PreviewCompositeOrder.Submission.AFTER_THE_HOST_SCREEN,
                PreviewCompositeOrder.submissionFor(
                        PreviewCompositeOrder.Pipeline.PAINTERS_ORDERED,
                        PreviewCompositeOrder.Surface.PAUSE_MENU));
    }

    /**
     * The mod's own screens must not defer. They call {@code super.render(...)} themselves and place
     * their modals, dropdowns, grids and tooltips around it deliberately; lifting the preview into a
     * later stratum would put the model over the mod's own foreground.
     */
    @Test
    void modOwnedScreensStayInlineOnEveryPipeline() {
        for (PreviewCompositeOrder.Pipeline pipeline : PreviewCompositeOrder.Pipeline.values()) {
            for (PreviewCompositeOrder.Surface surface : PreviewCompositeOrder.Surface.values()) {
                if (VANILLA_HOSTED.contains(surface)) {
                    continue;
                }
                assertSame(PreviewCompositeOrder.Submission.WITH_THE_WIDGET,
                        PreviewCompositeOrder.submissionFor(pipeline, surface),
                        pipeline + " / " + surface + " must stay inline");
            }
        }
    }

    /**
     * The set that defers is exactly the set of vanilla-hosted screens - no more, no less. This is
     * the property that makes the fix an ordering rule rather than a title-screen special case.
     */
    @Test
    void deferringIsExactlyTheVanillaHostedSet() {
        Set<PreviewCompositeOrder.Surface> deferring =
                EnumSet.noneOf(PreviewCompositeOrder.Surface.class);
        for (PreviewCompositeOrder.Surface surface : PreviewCompositeOrder.Surface.values()) {
            if (PreviewCompositeOrder.defersToHostScreenOverlay(
                    PreviewCompositeOrder.Pipeline.PAINTERS_ORDERED, surface)) {
                deferring.add(surface);
            }
        }
        assertEquals(VANILLA_HOSTED, deferring);
    }

    /** {@link PreviewCompositeOrder#modOwnsPaintOrder} is the half of the rule that names them. */
    @Test
    void ownershipMatchesTheVanillaHostedSet() {
        for (PreviewCompositeOrder.Surface surface : PreviewCompositeOrder.Surface.values()) {
            assertEquals(!VANILLA_HOSTED.contains(surface),
                    PreviewCompositeOrder.modOwnsPaintOrder(surface),
                    "ownership of " + surface);
        }
    }

    // --- totality, so a new constant cannot silently fall through ---

    /** Every pipeline/surface pair must produce an answer; none may be null. */
    @Test
    void theRuleIsTotal() {
        int pairs = 0;
        for (PreviewCompositeOrder.Pipeline pipeline : PreviewCompositeOrder.Pipeline.values()) {
            for (PreviewCompositeOrder.Surface surface : PreviewCompositeOrder.Surface.values()) {
                assertNotNull(PreviewCompositeOrder.submissionFor(pipeline, surface),
                        pipeline + " / " + surface);
                pairs++;
            }
        }
        assertEquals(PreviewCompositeOrder.Pipeline.values().length
                * PreviewCompositeOrder.Surface.values().length, pairs);
    }

    /**
     * An unclassified preview - the widget's default context, or a surface the mapping does not
     * recognise - must be left where it is. Drawing something the mod cannot place over content it
     * cannot see is the worse failure of the two.
     */
    @Test
    void anUnknownSurfaceStaysInline() {
        for (PreviewCompositeOrder.Pipeline pipeline : PreviewCompositeOrder.Pipeline.values()) {
            assertSame(PreviewCompositeOrder.Submission.WITH_THE_WIDGET,
                    PreviewCompositeOrder.submissionFor(pipeline, null),
                    pipeline + " with an unknown surface");
            assertSame(PreviewCompositeOrder.Submission.WITH_THE_WIDGET,
                    PreviewCompositeOrder.submissionFor(pipeline,
                            PreviewCompositeOrder.Surface.OTHER),
                    pipeline + " / OTHER");
        }
        assertTrue(PreviewCompositeOrder.modOwnsPaintOrder(PreviewCompositeOrder.Surface.OTHER));
    }

    // --- the stratum bump rides exactly with the deferral ---

    /**
     * The deferred pass always opens a fresh stratum, and the inline pass never does. Relying on the
     * intersection rule alone would be a coincidence: {@code findAppropriateNode} steps only one
     * node up when the previous element's bounds encompass the new ones, and inside a single node
     * glyphs are emitted after elements whoever submitted first.
     */
    @Test
    void theStratumBumpRidesWithTheDeferral() {
        assertTrue(PreviewCompositeOrder.opensNewStratum(
                PreviewCompositeOrder.Submission.AFTER_THE_HOST_SCREEN));
        assertFalse(PreviewCompositeOrder.opensNewStratum(
                PreviewCompositeOrder.Submission.WITH_THE_WIDGET));

        for (PreviewCompositeOrder.Pipeline pipeline : PreviewCompositeOrder.Pipeline.values()) {
            for (PreviewCompositeOrder.Surface surface : PreviewCompositeOrder.Surface.values()) {
                assertEquals(
                        PreviewCompositeOrder.defersToHostScreenOverlay(pipeline, surface),
                        PreviewCompositeOrder.opensNewStratum(
                                PreviewCompositeOrder.submissionFor(pipeline, surface)),
                        pipeline + " / " + surface);
            }
        }
    }

    // --- the rule actually discriminates ---

    /**
     * Both halves of the rule must matter. If either the pipeline or the surface stopped changing
     * the answer, the tests above would still pass while the rule had collapsed into a constant.
     */
    @Test
    void bothInputsChangeTheAnswer() {
        assertEquals(PreviewCompositeOrder.Submission.WITH_THE_WIDGET,
                PreviewCompositeOrder.submissionFor(
                        PreviewCompositeOrder.Pipeline.DEPTH_ORDERED,
                        PreviewCompositeOrder.Surface.TITLE_SCREEN),
                "the pipeline must matter: the same screen stays inline on the older lanes");
        assertEquals(PreviewCompositeOrder.Submission.WITH_THE_WIDGET,
                PreviewCompositeOrder.submissionFor(
                        PreviewCompositeOrder.Pipeline.PAINTERS_ORDERED,
                        PreviewCompositeOrder.Surface.SKIN_MENU),
                "the surface must matter: a mod screen stays inline on the newer lanes");
        assertEquals(PreviewCompositeOrder.Submission.AFTER_THE_HOST_SCREEN,
                PreviewCompositeOrder.submissionFor(
                        PreviewCompositeOrder.Pipeline.PAINTERS_ORDERED,
                        PreviewCompositeOrder.Surface.TITLE_SCREEN),
                "and only the pair of them defers");
        assertFalse(VANILLA_HOSTED.isEmpty(), "at least one surface must defer");
        assertTrue(VANILLA_HOSTED.size() < PreviewCompositeOrder.Surface.values().length,
                "and at least one must not, or the rule is vacuous");
    }
}
