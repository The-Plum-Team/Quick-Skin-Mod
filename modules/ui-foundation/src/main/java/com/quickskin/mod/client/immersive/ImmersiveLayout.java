package com.quickskin.mod.client.immersive;

import java.util.List;

/**
 * How a screen stands round the player in the immersive view, in the screen's own GUI units.
 *
 * <p>The player's feet are drawn at {@code (feetX, feetY)} and one block of the world covers
 * {@code pixelsPerBlock} units there, so whatever the screen draws round that spot stays round the
 * player. Each {@link Panel} is a part of the screen that swings away from the flat screen on a
 * hinge along one of its edges.
 */
public record ImmersiveLayout(float feetX, float feetY, float pixelsPerBlock, List<Panel> panels) {
    public ImmersiveLayout {
        panels = List.copyOf(panels);
    }

    /** The edge of a panel that stays on the flat screen. */
    public enum Hinge { LEFT, RIGHT, TOP, BOTTOM }

    /**
     * A rectangle of the screen and how far it swings round its hinge: away from the viewer for
     * positive {@code degrees}, towards the viewer for negative ones. The hinge is a line of the
     * flat screen, at {@code hingeAt} across (for a left or right hinge) or down (for a top or
     * bottom one); it need not be an edge of the rectangle, so that something drawn beside a
     * panel, such as a menu opened from it, can stand in that panel's plane.
     */
    public record Panel(float left, float top, float right, float bottom, Hinge hinge, float hingeAt,
                        float degrees) {
        /** A panel hinged along its own edge. */
        public Panel(float left, float top, float right, float bottom, Hinge hinge, float degrees) {
            this(left, top, right, bottom, hinge, switch (hinge) {
                case LEFT -> left;
                case RIGHT -> right;
                case TOP -> top;
                case BOTTOM -> bottom;
            }, degrees);
        }

        public boolean contains(float x, float y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }
}
