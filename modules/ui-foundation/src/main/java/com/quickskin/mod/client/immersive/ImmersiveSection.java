package com.quickskin.mod.client.immersive;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Clips a block's occlusion boxes to the retained side of the camera aperture. */
public final class ImmersiveSection {
    private static final Vec3 X = new Vec3(1, 0, 0);
    private static final Vec3 Y = new Vec3(0, 1, 0);

    // Project the box's camera-facing rectangle onto the plane along its dominant axis.
    // Its other two coordinates already lie inside the box. Clipping the last coordinate
    // to [min, max] leaves exactly the wall's cross-section, including at oblique angles.
    public static List<Vec3> section(AABB box, double offset, Vec3 normal, Direction face) {
        Vec3 center = box.getCenter();
        double centerDistance = center.dot(normal) + offset;
        if (Math.abs(centerDistance) > halfExtent(box, normal)) return List.of();
        Vec3 axis = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 u = face.getAxis() == Direction.Axis.Y ? X : Y;
        Vec3 v = axis.cross(u);
        u = u.scale(halfExtent(box, u));
        v = v.scale(halfExtent(box, v));
        Vec3 along = axis.scale(1.0 / axis.dot(normal));
        var polygon = new ArrayList<Vec3>(4);
        for (Vec3 p : List.of(center.subtract(u).subtract(v), center.add(u).subtract(v),
                center.add(u).add(v), center.subtract(u).add(v))) {
            polygon.add(p.subtract(along.scale(p.dot(normal) + offset)));
        }
        var dim = face.getAxis();
        return clip(clip(polygon, dim, box.min(dim), 1), dim, box.max(dim), -1);
    }

    private static double halfExtent(AABB box, Vec3 dir) {
        return (box.getXsize() * Math.abs(dir.x) + box.getYsize() * Math.abs(dir.y)
                + box.getZsize() * Math.abs(dir.z)) * 0.5;
    }

    private static List<Vec3> clip(List<Vec3> polygon, Direction.Axis axis, double bound, int sign) {
        if (polygon.isEmpty()) return polygon;
        var result = new ArrayList<Vec3>(polygon.size() + 1);
        Vec3 prev = polygon.get(polygon.size() - 1);
        double prevDist = sign * (prev.get(axis) - bound);
        for (Vec3 next : polygon) {
            double dist = sign * (next.get(axis) - bound);
            if ((prevDist < 0) != (dist < 0)) result.add(prev.lerp(next, prevDist / (prevDist - dist)));
            if (dist >= 0) result.add(next);
            prev = next;
            prevDist = dist;
        }
        return result;
    }

    private ImmersiveSection() {}
}
