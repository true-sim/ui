package com.truesim.ui.gizmo;

import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Applies a {@link GizmoDelta} to the nodes of a selection through the scene's API (design 8: physics and networking
 * see ordinary changes): each node's world transform at the drag's start, moved, turned or scaled about the pivot
 * (or its own origin), then brought into its parent's space as its local transform. In doubles, with every position
 * taken relative to the pivot first, so nodes 10,000 km from the origin move as exactly as nodes at it.
 *
 * <p>A non-uniform scale along axes the node is turned against cannot be a scale of the node's own: each of its axes
 * is stretched by what the scale does to it (exact when the node lines up with the gizmo's axes, as in local space).
 */
final class GizmoApply {

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;

    private GizmoApply() {
    }

    /** Applies {@code d} to every node of {@code initial} in {@code scene}. */
    static void apply(SceneGraph scene, GizmoTransforms initial, GizmoDelta d) {
        double[] w = initial.worldArray();
        GizmoHandle h = d.handle;
        double qx = d.rotation[0];
        double qy = d.rotation[1];
        double qz = d.rotation[2];
        double qw = d.rotation[3];
        double ax = d.axes[0];
        double ay = d.axes[1];
        double az = d.axes[2];
        double aw = d.axes[3];
        for (int i = 0; i < initial.count(); i++) {
            long node = initial.node(i);
            if (!scene.contains(node)) {
                continue;
            }
            int o = i * GizmoTransforms.STRIDE;
            double tx = w[o];
            double ty = w[o + 1];
            double tz = w[o + 2];
            double rx = w[o + 3];
            double ry = w[o + 4];
            double rz = w[o + 5];
            double rw = w[o + 6];
            double sx = w[o + 7];
            double sy = w[o + 8];
            double sz = w[o + 9];
            double px = d.individual ? tx : d.pivot[0];
            double py = d.individual ? ty : d.pivot[1];
            double pz = d.individual ? tz : d.pivot[2];
            double vx = tx - px;
            double vy = ty - py;
            double vz = tz - pz;
            if (h == GizmoHandle.NONE) {
                // a grab (XR): scaled evenly, turned about the pivot, then moved
                double f = d.scale[0];
                tx = px + d.translation[0] + GizmoMath.rotate(qx, qy, qz, qw, vx * f, vy * f, vz * f, 0);
                ty = py + d.translation[1] + GizmoMath.rotate(qx, qy, qz, qw, vx * f, vy * f, vz * f, 1);
                tz = pz + d.translation[2] + GizmoMath.rotate(qx, qy, qz, qw, vx * f, vy * f, vz * f, 2);
                double nx = qw * rx + qx * rw + qy * rz - qz * ry;
                double ny = qw * ry - qx * rz + qy * rw + qz * rx;
                double nz = qw * rz + qx * ry - qy * rx + qz * rw;
                double nw = qw * rw - qx * rx - qy * ry - qz * rz;
                double l = Math.sqrt(nx * nx + ny * ny + nz * nz + nw * nw);
                rx = nx / l;
                ry = ny / l;
                rz = nz / l;
                rw = nw / l;
                sx *= f;
                sy *= f;
                sz *= f;
            } else if (h.translates()) {
                tx += d.translation[0];
                ty += d.translation[1];
                tz += d.translation[2];
            } else if (h.rotates()) {
                tx = px + GizmoMath.rotate(qx, qy, qz, qw, vx, vy, vz, 0);
                ty = py + GizmoMath.rotate(qx, qy, qz, qw, vx, vy, vz, 1);
                tz = pz + GizmoMath.rotate(qx, qy, qz, qw, vx, vy, vz, 2);
                double nx = qw * rx + qx * rw + qy * rz - qz * ry;
                double ny = qw * ry - qx * rz + qy * rw + qz * rx;
                double nz = qw * rz + qx * ry - qy * rx + qz * rw;
                double nw = qw * rw - qx * rx - qy * ry - qz * rz;
                double l = Math.sqrt(nx * nx + ny * ny + nz * nz + nw * nw);
                rx = nx / l;
                ry = ny / l;
                rz = nz / l;
                rw = nw / l;
            } else if (h.scales()) {
                tx = px + stretch(ax, ay, az, aw, d.scale, vx, vy, vz, 0);
                ty = py + stretch(ax, ay, az, aw, d.scale, vx, vy, vz, 1);
                tz = pz + stretch(ax, ay, az, aw, d.scale, vx, vy, vz, 2);
                sx *= axisStretch(ax, ay, az, aw, d.scale, rx, ry, rz, rw, 1, 0, 0);
                sy *= axisStretch(ax, ay, az, aw, d.scale, rx, ry, rz, rw, 0, 1, 0);
                sz *= axisStretch(ax, ay, az, aw, d.scale, rx, ry, rz, rw, 0, 0, 1);
            }
            setWorld(scene, node, tx, ty, tz, rx, ry, rz, rw, sx, sy, sz);
        }
    }

    /** Component {@code c} of {@code v} scaled by {@code s} along the axes of rotation {@code a}. */
    private static double stretch(double ax, double ay, double az, double aw, double[] s, double vx, double vy,
        double vz, int c) {
        // into the axes' space, scale, back
        double lx = GizmoMath.rotate(-ax, -ay, -az, aw, vx, vy, vz, 0) * s[0];
        double ly = GizmoMath.rotate(-ax, -ay, -az, aw, vx, vy, vz, 1) * s[1];
        double lz = GizmoMath.rotate(-ax, -ay, -az, aw, vx, vy, vz, 2) * s[2];
        return GizmoMath.rotate(ax, ay, az, aw, lx, ly, lz, c);
    }

    /** How much the scale stretches the node's local axis {@code e} (turned by its rotation {@code r}). */
    private static double axisStretch(double ax, double ay, double az, double aw, double[] s, double rx, double ry,
        double rz, double rw, double ex, double ey, double ez) {
        double wx = GizmoMath.rotate(rx, ry, rz, rw, ex, ey, ez, 0);
        double wy = GizmoMath.rotate(rx, ry, rz, rw, ex, ey, ez, 1);
        double wz = GizmoMath.rotate(rx, ry, rz, rw, ex, ey, ez, 2);
        double lx = GizmoMath.rotate(-ax, -ay, -az, aw, wx, wy, wz, 0) * s[0];
        double ly = GizmoMath.rotate(-ax, -ay, -az, aw, wx, wy, wz, 1) * s[1];
        double lz = GizmoMath.rotate(-ax, -ay, -az, aw, wx, wy, wz, 2) * s[2];
        double sign = s[0] * s[1] * s[2] < 0 && Math.abs(lx) + Math.abs(ly) + Math.abs(lz) > 0 ? -1 : 1;
        return sign * GizmoMath.length(lx, ly, lz);
    }

    /** Sets {@code node}'s local transform so its world transform is the given one. */
    static void setWorld(SceneGraph scene, long node, double tx, double ty, double tz, double rx, double ry,
        double rz, double rw, double sx, double sy, double sz) {
        long parent = scene.parent(node);
        if (parent == NodeHandle.NONE) {
            scene.setLocal(node, tx, ty, tz, rx, ry, rz, rw, sx, sy, sz);
            return;
        }
        MemorySegment s = scene.worldTransforms().segment();
        long o = NodeHandle.index(parent) * scene.worldTransforms().stride();
        double ptx = s.get(DOUBLE, o);
        double pty = s.get(DOUBLE, o + 8);
        double ptz = s.get(DOUBLE, o + 16);
        double pqx = s.get(DOUBLE, o + 24);
        double pqy = s.get(DOUBLE, o + 32);
        double pqz = s.get(DOUBLE, o + 40);
        double pqw = s.get(DOUBLE, o + 48);
        double psx = s.get(DOUBLE, o + 56);
        double psy = s.get(DOUBLE, o + 64);
        double psz = s.get(DOUBLE, o + 72);
        double dx = tx - ptx;
        double dy = ty - pty;
        double dz = tz - ptz;
        double lx = GizmoMath.rotate(-pqx, -pqy, -pqz, pqw, dx, dy, dz, 0) / psx;
        double ly = GizmoMath.rotate(-pqx, -pqy, -pqz, pqw, dx, dy, dz, 1) / psy;
        double lz = GizmoMath.rotate(-pqx, -pqy, -pqz, pqw, dx, dy, dz, 2) / psz;
        // parent^-1 * rotation
        double nx = pqw * rx - pqx * rw - pqy * rz + pqz * ry;
        double ny = pqw * ry + pqx * rz - pqy * rw - pqz * rx;
        double nz = pqw * rz - pqx * ry + pqy * rx - pqz * rw;
        double nw = pqw * rw + pqx * rx + pqy * ry + pqz * rz;
        scene.setLocal(node, lx, ly, lz, nx, ny, nz, nw, sx / psx, sy / psy, sz / psz);
    }
}
