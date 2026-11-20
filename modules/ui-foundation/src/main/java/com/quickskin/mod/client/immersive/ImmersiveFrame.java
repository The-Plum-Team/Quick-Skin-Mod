package com.quickskin.mod.client.immersive;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * The geometry of one frame of the immersive view: where each point of the screen is drawn, and
 * which point of the screen lies under the mouse.
 *
 * <p>The screen is a flat sheet standing {@code depth} blocks in front of the camera's resting
 * place (the rig), where one GUI unit covers {@code unitsPerPixel} blocks, so at rest the sheet
 * fills the view exactly as the screen would. Panels swing off that sheet on their hinges. While
 * the camera travels to or from the player's eyes it sees the sheet from elsewhere, which
 * {@code rigToCamera} says: the sheet stays where it is in the world.
 *
 * <p>Camera space is x to the right, y up and z towards the viewer; screen space is the GUI's, in
 * the same units as the layout.
 */
public final class ImmersiveFrame {
    /** The flat sheet, as a part of the screen. */
    public static final int BASE = -1;
    /** No part of the screen. */
    public static final int NONE = -2;
    /** Nothing nearer the camera than this is drawn or picked. */
    static final float NEAR = 0.05f;

    private final float width;
    private final float height;
    private final float tanHalfFov;
    private final float aspect;
    private final float depth;
    private final float unitsPerPixel;
    private final float tilt;
    private final Matrix4f rigToCamera;
    private final Matrix4f cameraToRig;
    private final List<ImmersiveLayout.Panel> panels;

    /**
     * @param fovDegrees the vertical field of view this frame is drawn with
     * @param tilt how far the panels have swung, from 0 (flat) to 1 (their own angle)
     */
    public ImmersiveFrame(float width, float height, float fovDegrees, float depth, float unitsPerPixel,
                          Matrix4f rigToCamera, List<ImmersiveLayout.Panel> panels, float tilt) {
        this.width = width;
        this.height = height;
        this.tanHalfFov = (float) Math.tan(Math.toRadians(fovDegrees) / 2.0);
        this.aspect = width / height;
        this.depth = depth;
        this.unitsPerPixel = unitsPerPixel;
        this.tilt = tilt;
        this.rigToCamera = new Matrix4f(rigToCamera);
        this.cameraToRig = new Matrix4f(rigToCamera).invert();
        this.panels = List.copyOf(panels);
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public List<ImmersiveLayout.Panel> panels() {
        return panels;
    }

    /** Finds the same surface in this layout after the screen adds or removes a panel. */
    int partFrom(ImmersiveFrame previous, int part) {
        if (part == BASE) return BASE;
        if (part < 0 || part >= previous.panels.size()) return NONE;
        int index = panels.indexOf(previous.panels.get(part));
        return index >= 0 ? index : NONE;
    }

    /** The part of the screen a point of the layout belongs to: its panel, or the flat sheet. */
    public int partAt(float x, float y) {
        for (int index = 0; index < panels.size(); index++) {
            if (panels.get(index).contains(x, y)) return index;
        }
        return BASE;
    }

    /** Where a point of the screen stands before the rig, as part {@code part} holds it. */
    void rigPoint(int part, float x, float y, Vector3f out) {
        float s = unitsPerPixel;
        float rx = (x - width / 2.0f) * s;
        float ry = (height / 2.0f - y) * s;
        float rz = -depth;
        if (part >= 0) {
            ImmersiveLayout.Panel panel = panels.get(part);
            double angle = Math.toRadians(panel.degrees() * tilt);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            switch (panel.hinge()) {
                case LEFT -> {
                    float reach = (x - panel.hingeAt()) * s;
                    rx = (panel.hingeAt() - width / 2.0f) * s + reach * cos;
                    rz = -depth - reach * sin;
                }
                case RIGHT -> {
                    float reach = (panel.hingeAt() - x) * s;
                    rx = (panel.hingeAt() - width / 2.0f) * s - reach * cos;
                    rz = -depth - reach * sin;
                }
                case TOP -> {
                    float reach = (y - panel.hingeAt()) * s;
                    ry = (height / 2.0f - panel.hingeAt()) * s - reach * cos;
                    rz = -depth - reach * sin;
                }
                case BOTTOM -> {
                    float reach = (panel.hingeAt() - y) * s;
                    ry = (height / 2.0f - panel.hingeAt()) * s + reach * cos;
                    rz = -depth - reach * sin;
                }
            }
        }
        out.set(rx, ry, rz);
    }

    /**
     * Where the camera sees a point of the screen held by {@code part}, in screen space.
     *
     * @return false when the point is beside or behind the camera, where nothing is drawn
     */
    public boolean project(int part, float x, float y, float[] out) {
        Vector3f point = new Vector3f();
        rigPoint(part, x, y, point);
        rigToCamera.transformPosition(point);
        float distance = -point.z;
        if (distance < NEAR) return false;
        out[0] = (point.x / (distance * tanHalfFov * aspect) + 1.0f) * 0.5f * width;
        out[1] = (1.0f - point.y / (distance * tanHalfFov)) * 0.5f * height;
        return true;
    }

    /**
     * The point of the screen under a point of the view.
     *
     * @param captured the part a held mouse button started on, which keeps the mouse even beyond
     *                 its edges, or {@link #NONE} to pick the nearest part under the point
     * @return the part found, with its point in {@code out}, or {@link #NONE}
     */
    public int unproject(float sx, float sy, int captured, float[] out) {
        if (captured < NONE || captured >= panels.size()) return NONE;
        Vector3f origin = cameraToRig.transformPosition(new Vector3f());
        Vector3f direction = cameraToRig.transformDirection(new Vector3f(
                (sx / width * 2.0f - 1.0f) * tanHalfFov * aspect,
                (1.0f - sy / height * 2.0f) * tanHalfFov,
                -1.0f));
        if (captured != NONE) {
            return hit(captured, origin, direction, false, out) > 0 ? captured : NONE;
        }
        int best = NONE;
        float bestDistance = Float.MAX_VALUE;
        float[] point = new float[2];
        for (int part = BASE; part < panels.size(); part++) {
            float distance = hit(part, origin, direction, true, point);
            if (distance > 0 && distance < bestDistance) {
                bestDistance = distance;
                best = part;
                out[0] = point[0];
                out[1] = point[1];
            }
        }
        return best;
    }

    /** How far along the ray it meets the part, with the point met in {@code out}; 0 for a miss. */
    private float hit(int part, Vector3f origin, Vector3f direction, boolean bounded, float[] out) {
        float left = part >= 0 ? panels.get(part).left() : 0.0f;
        float top = part >= 0 ? panels.get(part).top() : 0.0f;
        float right = part >= 0 ? panels.get(part).right() : width;
        float bottom = part >= 0 ? panels.get(part).bottom() : height;
        if (right <= left || bottom <= top) return 0.0f;
        Vector3f corner = new Vector3f();
        Vector3f across = new Vector3f();
        Vector3f down = new Vector3f();
        rigPoint(part, left, top, corner);
        rigPoint(part, right, top, across);
        rigPoint(part, left, bottom, down);
        across.sub(corner).div(right - left);
        down.sub(corner).div(bottom - top);

        // origin + t * direction = corner + a * across + b * down, by Cramer's rule
        Vector3f target = new Vector3f(corner).sub(origin);
        Vector3f minusAcross = new Vector3f(across).negate();
        Vector3f minusDown = new Vector3f(down).negate();
        float determinant = determinant(direction, minusAcross, minusDown);
        if (Math.abs(determinant) < 1.0e-9f) return 0.0f;
        float t = determinant(target, minusAcross, minusDown) / determinant;
        if (t <= 0.0f) return 0.0f;
        float a = determinant(direction, target, minusDown) / determinant;
        float b = determinant(direction, minusAcross, target) / determinant;
        float x = left + a;
        float y = top + b;
        if (bounded) {
            if (x < left || x >= right || y < top || y >= bottom) return 0.0f;
            // The flat sheet no longer holds what swung away with a panel
            if (part == BASE && partAt(x, y) != BASE) return 0.0f;
        }
        out[0] = x;
        out[1] = y;
        return t;
    }

    private static float determinant(Vector3f a, Vector3f b, Vector3f c) {
        return a.x * (b.y * c.z - b.z * c.y)
                - b.x * (a.y * c.z - a.z * c.y)
                + c.x * (a.y * b.z - a.z * b.y);
    }
}
