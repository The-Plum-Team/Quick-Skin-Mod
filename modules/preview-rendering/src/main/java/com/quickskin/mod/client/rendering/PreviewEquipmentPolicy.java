package com.quickskin.mod.client.rendering;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a Quick Skin preview may inherit from the live player, and for how long.
 *
 * <p>The GUI preview renders the <em>real</em> player entity through vanilla's ordinary renderer, so
 * every vanilla render layer runs against live state. That is why the cape editor showed the elytra
 * the player happened to be wearing: the wings layer reads the CHEST slot, and it draws after the
 * cape layer, over the cape being edited. The elytra is only the most visible case - a chestplate
 * covers the same pixels, and a helmet covers the face the skin menu is previewing.
 *
 * <p>The rule this class owns is therefore about the surface, not about the elytra: <em>a cosmetic
 * preview shows the skin and the cape and nothing the player happens to be wearing or holding.</em>
 * Every equipment slot a player can fill is suppressed. Deliberately <em>not</em> covered here,
 * because they are not equipment and share no seam with it: pose, mob effects and invisibility,
 * fire, name tags, shoulder parrots, embedded arrows and bee stingers, and the riptide spin. Those
 * are listed in the class comment of the caller rather than silently ignored.
 *
 * <p>The suppression is a scoped <em>read</em> override, never a write: no inventory, equipment or
 * world state is touched to draw a GUI. Two eras need two seams for the same rule - from 1.21.11 the
 * mod builds the entity render state itself and blanks its equipment fields, and before that the
 * layers read the entity directly, so the read is answered as empty for the duration of the draw.
 * {@link Scope} is that duration.
 *
 * <p>Deliberately free of Minecraft types, so the rule and the scoping stay unit testable in the
 * loader-independent test source set, in the same spirit as {@link PreviewCapeBindings}.
 */
public final class PreviewEquipmentPolicy {

    /**
     * The equipment slots a previewed player can fill.
     *
     * <p>A mod-owned mirror of the vanilla slots rather than the vanilla enum: the vanilla one has
     * gained members across the supported eras (body and saddle slots for animals) that a player
     * cannot use, and this set must not silently grow with it.
     */
    public enum Slot {
        HEAD,
        CHEST,
        LEGS,
        FEET,
        MAIN_HAND,
        OFF_HAND
    }

    /** Every slot: see the class comment for why the rule is not narrowed to the chest. */
    private static final Set<Slot> SUPPRESSED =
            Collections.unmodifiableSet(EnumSet.allOf(Slot.class));

    private PreviewEquipmentPolicy() {
    }

    /** The slots a preview draw must read as empty. Unmodifiable. */
    public static Set<Slot> suppressedSlots() {
        return SUPPRESSED;
    }

    /** Whether {@code slot} must read as empty during a preview draw. */
    public static boolean suppresses(Slot slot) {
        return slot != null && SUPPRESSED.contains(slot);
    }

    /**
     * The window during which one subject's equipment reads as empty.
     *
     * <p>Confined to the thread that opened it and keyed by reference identity, so the suppression
     * can only ever apply to the exact entity being previewed, on the exact thread drawing it. The
     * client renders on one thread while the integrated server ticks its own copy of the player on
     * another, and those are different objects on different threads; both guards must hold, so
     * neither can see this.
     *
     * <p>Re-entrant and balanced: {@link #begin} nests and {@link #end} unwinds, so a caller that
     * opens the scope in a {@code try} and closes it in the matching {@code finally} cannot leave it
     * open, and an unbalanced {@code end} cannot drive it below zero.
     */
    public static final class Scope<E> {

        private static final class Frame<E> {
            private final E subject;
            private int depth = 1;

            private Frame(E subject) {
                this.subject = subject;
            }
        }

        /**
         * Open-scope count across all threads.
         *
         * <p>Read first so the overwhelmingly common answer - no preview is being drawn anywhere -
         * costs a single volatile read, without touching the thread local. Only a genuinely open
         * scope pays for the rest.
         */
        private volatile int open;

        private final ThreadLocal<Frame<E>> frame = new ThreadLocal<>();

        /** Begin suppressing equipment reads for {@code subject} on the calling thread. */
        public void begin(E subject) {
            if (subject == null) {
                return;
            }
            Frame<E> current = frame.get();
            if (current != null && current.subject == subject) {
                current.depth++;
                return;
            }
            if (current != null) {
                // A different subject on the same thread would need a stack to restore; nothing in
                // the mod nests two previews, so refuse rather than silently mis-scope the outer one.
                return;
            }
            frame.set(new Frame<>(subject));
            open++;
        }

        /** End the scope opened by the matching {@link #begin}. Safe to call unbalanced. */
        public void end(E subject) {
            if (subject == null) {
                return;
            }
            Frame<E> current = frame.get();
            if (current == null || current.subject != subject) {
                return;
            }
            if (--current.depth <= 0) {
                frame.remove();
                open--;
            }
        }

        /** Whether {@code subject}'s equipment must read as empty right now, on this thread. */
        public boolean isActiveFor(E subject) {
            if (open == 0 || subject == null) {
                return false;
            }
            Frame<E> current = frame.get();
            return current != null && current.subject == subject;
        }

        /** Open scopes across all threads, for bounding assertions. */
        public int openScopes() {
            return open;
        }
    }
}
