package com.quickskin.mod.client.rendering;

/**
 * Where the 3D player preview has to be handed to the GUI so that it ends up in front of the host
 * screen's flat content.
 *
 * <p>The preview is a 3D draw living inside a 2D screen, and "in front" has meant two completely
 * different things across the supported eras. Up to 1.21.5 the GUI is immediate mode over a real
 * depth buffer: {@code GameRenderer} sets an ortho projection with near/far 1000/21000 and puts the
 * GUI plane at z = -11000, every GUI and text render type depth-tests {@code LEQUAL} and writes
 * depth, and {@code InventoryScreen.renderEntityInInventory} translates the model to GUI z = +50
 * before drawing it. The model therefore occupies 50 units of depth that no later flat draw at z = 0
 * can pass, and it covers everything the screen paints afterwards <em>whenever it was submitted</em>.
 * From 1.21.6 vanilla records into a {@code GuiRenderState} and composites afterwards; all GUI
 * pipelines are built {@code NO_DEPTH_TEST}, so depth arbitrates nothing and the result is a pure
 * painter's algorithm over a list of strata, each a chain of nodes. Two rules decide the outcome
 * there: a submission whose bounds intersect something already present is lifted into a node
 * <em>above</em> it, and within one node every element is emitted before every glyph. Both rules
 * favour whoever submits last, so the same preview that used to win by depth now loses to any text
 * the host screen draws after it.
 *
 * <p>That is the whole bug: on the title screen the preview is an ordinary renderable widget, so it
 * is submitted from {@code super.render(...)}, while {@code TitleScreen.render} paints the logo and
 * the splash immediately afterwards - identical ordering on 1.21.1 and 1.21.11, but only the older
 * pipeline let the model win it.
 *
 * <p>So the ordering rule is a function of two things and nothing else: which compositing model the
 * running version uses, and whether the mod controls the order in which the host screen paints. On
 * a screen the mod writes, the preview is already submitted after everything meant to sit behind it
 * and before everything meant to sit in front of it, so nothing has to move. On a vanilla screen the
 * mod only gets to inject a widget and has no say over what the screen paints afterwards, so on the
 * painter's pipeline the preview has to be submitted again once the host screen has finished.
 *
 * <p>Deliberately free of Minecraft types and of Stonecutter branches: the caller supplies its era
 * as a {@link Pipeline} and its screen as a {@link Surface}, which keeps the entire rule a pure
 * function and unit testable in the loader-independent test source set.
 */
public final class PreviewCompositeOrder {

    /** How the running Minecraft version decides which GUI draw ends up on top. */
    public enum Pipeline {
        /**
         * Up to 1.21.5 - immediate mode arbitrated by a depth buffer. The preview is drawn at GUI
         * z = +50 and depth-rejects later flat content, so submission order does not matter.
         *
         * <p>Live on the 1.20.1 and 1.21.1 lanes.
         */
        DEPTH_ORDERED,
        /**
         * From 1.21.6 - deferred {@code GuiRenderState} recording composited as a painter's
         * algorithm with no depth test at all. Later intersecting submissions win.
         *
         * <p>Used by every supported lane from 1.21.6 onward.
         */
        PAINTERS_ORDERED
    }

    /** The screen a preview is being drawn on, and therefore who controls its paint order. */
    public enum Surface {
        /** Vanilla {@code TitleScreen}; paints the logo, the splash and the version string after its widgets. */
        TITLE_SCREEN,
        /** Vanilla {@code PauseScreen}; paints its own foreground after its widgets. */
        PAUSE_MENU,
        /** The mod's skin menu. */
        SKIN_MENU,
        /** The mod's cape menu and cape adjust screen. */
        CAPE_MENU,
        /** Anything else the mod draws a preview on. */
        OTHER
    }

    /** When the preview has to be handed to the GUI. */
    public enum Submission {
        /**
         * Submit from the widget, in the screen's renderable pass, exactly where it happens today.
         */
        WITH_THE_WIDGET,
        /**
         * Submit again after the host screen's own render has returned, in a fresh stratum.
         *
         * <p>This is what vanilla itself does for the only case it has of a picture-in-picture
         * element that must beat text - {@code DebugScreenOverlay} bumps a stratum after the F3
         * lines and before the profiler pie chart - and it is the only public layering API there is.
         */
        AFTER_THE_HOST_SCREEN
    }

    private PreviewCompositeOrder() {
    }

    /**
     * Whether the mod, rather than the host screen, decides what is painted after the preview.
     *
     * <p>True for the mod's own screens, which call {@code super.render(...)} themselves and place
     * their labels around it deliberately. False for the two vanilla screens the mod injects a
     * widget into, where anything the screen paints after its renderables is out of reach.
     */
    public static boolean modOwnsPaintOrder(Surface surface) {
        switch (surface) {
            case TITLE_SCREEN:
            case PAUSE_MENU:
                return false;
            default:
                return true;
        }
    }

    /**
     * The one ordering rule.
     *
     * <p>A deferred re-submission is needed only where both halves are true: the running pipeline
     * has no depth to fall back on, and the host screen paints content after the mod's widget. Every
     * other combination already puts the preview where it belongs, so it must stay exactly where it
     * is - that is what keeps 1.20.1 and 1.21.1 untouched, and what keeps the mod's own screens from
     * having their previews lifted over their own modals, dropdowns and tooltips.
     *
     * <p>A {@code null} surface is treated as {@link Surface#OTHER}: an unclassified preview is one
     * the mod is drawing itself, so leaving it in place is the safe reading.
     */
    public static Submission submissionFor(Pipeline pipeline, Surface surface) {
        Surface resolved = surface == null ? Surface.OTHER : surface;
        if (pipeline == Pipeline.PAINTERS_ORDERED && !modOwnsPaintOrder(resolved)) {
            return Submission.AFTER_THE_HOST_SCREEN;
        }
        return Submission.WITH_THE_WIDGET;
    }

    /** Shorthand for {@code submissionFor(...) == AFTER_THE_HOST_SCREEN}. */
    public static boolean defersToHostScreenOverlay(Pipeline pipeline, Surface surface) {
        return submissionFor(pipeline, surface) == Submission.AFTER_THE_HOST_SCREEN;
    }

    /**
     * Whether the deferred pass should open a fresh stratum before submitting.
     *
     * <p>Always, when it runs at all. Relying on the intersection rule alone would be a coincidence
     * rather than a contract: {@code findAppropriateNode} has a fast path that simply steps one node
     * up when the previously submitted element's bounds <em>encompass</em> the new ones, so a
     * preview submitted straight after a full-screen element can land below content that is already
     * higher, and within a single node glyphs are emitted after elements regardless of who submitted
     * first. A stratum is appended to a list that is walked in index order, so it is the only
     * placement that does not depend on what the host screen happened to draw last.
     */
    public static boolean opensNewStratum(Submission submission) {
        return submission == Submission.AFTER_THE_HOST_SCREEN;
    }
}
