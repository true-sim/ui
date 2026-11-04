package com.truesim.ui.gizmo;

import com.truesim.common.math.Maths;
import static com.truesim.ui.gizmo.GizmoMath.closestOnLine;
import static com.truesim.ui.gizmo.GizmoMath.dot;
import static com.truesim.ui.gizmo.GizmoMath.rayPlane;
import static com.truesim.ui.gizmo.GizmoMath.snap;

/**
 * Turns pointer rays into a gizmo drag (design 8), in doubles relative to the gizmo's origin, so a drag 10,000 km from
 * the world's origin is as exact as one at it (D37): the ray against the handle's axis line (translate and scale
 * along an axis), its plane (planes, the view plane, rings), or the pointer's screen motion (the trackball; rings seen
 * edge-on, as Blender turns them), then snapping to increments and typed values.
 *
 * <p>{@link #begin} fixes the frame (origin, axes, the view's directions) and the start ray; {@link #drag} solves for
 * each later ray into a {@link GizmoDelta}. Nothing allocates.
 */
public final class GizmoSolver {

    /** Below this |cos| between a ring's axis and the ray, the ring is edge-on and turns by screen motion. */
    static final double EDGE_ON = 0.2;

    // the frame
    private final double[] origin = new double[3];
    private final double[] axes = {0, 0, 0, 1};
    private final double[][] axis = new double[3][3];
    private final double[] forward = new double[3];
    private final double[] right = new double[3];
    private final double[] up = new double[3];
    private double radiusPx = 100;
    private double radiusWorld = 1;
    private final double[] boxMin = new double[3];
    private final double[] boxMax = new double[3];

    // the start
    private GizmoHandle handle = GizmoHandle.NONE;
    private int part;
    private double s0;
    private final double[] p0 = new double[3];
    private float startX;
    private float startY;
    private float centerX;
    private float centerY;
    private boolean screen;
    private final double[] startRay = new double[6];
    private boolean valid;
    private final double[] q = new double[4];

    /**
     * Sets the frame: the gizmo's origin and axes (the rotation of its space), the view's direction, right and up
     * (from the camera, or for an XR ray its aim), and the trackball's radius in pixels and in world units.
     */
    public void frame(double ox, double oy, double oz, double ax, double ay, double az, double aw,
        double fx, double fy, double fz, double rx, double ry, double rz, double ux, double uy, double uz,
        double ballRadiusPx, double ballRadiusWorld) {
        origin[0] = ox;
        origin[1] = oy;
        origin[2] = oz;
        axes[0] = ax;
        axes[1] = ay;
        axes[2] = az;
        axes[3] = aw;
        GizmoMath.normalize4(axes, 0);
        for (int a = 0; a < 3; a++) {
            for (int c = 0; c < 3; c++) {
                axis[a][c] = GizmoMath.rotate(axes, 0, a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0, c);
            }
        }
        set(forward, fx, fy, fz);
        set(right, rx, ry, rz);
        set(up, ux, uy, uz);
        radiusPx = ballRadiusPx;
        radiusWorld = ballRadiusWorld;
    }

    /** Sets the selection's box in the gizmo's axes, relative to its origin (for bounds handles). */
    public void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boxMin[0] = minX;
        boxMin[1] = minY;
        boxMin[2] = minZ;
        boxMax[0] = maxX;
        boxMax[1] = maxY;
        boxMax[2] = maxZ;
    }

    private static void set(double[] v, double x, double y, double z) {
        double l = GizmoMath.length(x, y, z);
        v[0] = x / l;
        v[1] = y / l;
        v[2] = z / l;
    }

    /**
     * Starts a drag of {@code grabbed} ({@code handlePart}: the bounds corner or face) with the ray from {@code o}
     * along {@code d} (world); {@code x, y} is the pointer and {@code cx, cy} the gizmo's origin on the screen (NaN for
     * an XR ray). Returns false when the ray cannot grab the handle (parallel to its line or plane).
     */
    public boolean begin(GizmoHandle grabbed, int handlePart, double ox, double oy, double oz, double dx, double dy,
        double dz, float x, float y, float cx, float cy) {
        handle = grabbed;
        part = handlePart;
        startX = x;
        startY = y;
        centerX = cx;
        centerY = cy;
        screen = !Float.isNaN(x);
        double rx = ox - origin[0];
        double ry = oy - origin[1];
        double rz = oz - origin[2];
        valid = point(rx, ry, rz, dx, dy, dz, p0) || grabbed.rotates() && grabbed != GizmoHandle.ROTATE_TRACKBALL;
        s0 = p0[0]; // the line parameter, for line handles
        startRay[0] = rx;
        startRay[1] = ry;
        startRay[2] = rz;
        startRay[3] = dx;
        startRay[4] = dy;
        startRay[5] = dz;
        return valid;
    }

    /** The handle being dragged. */
    public GizmoHandle handle() {
        return handle;
    }

    /**
     * Solves the drag for the ray from {@code o} along {@code d} and pointer {@code x, y}, into {@code delta}
     * (snapped to the increments when {@code snapping}); a ray that grabs nothing leaves it as it was. Returns
     * whether the delta changed.
     */
    public boolean drag(double ox, double oy, double oz, double dx, double dy, double dz, float x, float y,
        boolean snapping, double translateStep, double rotateStep, double scaleStep, GizmoDelta delta) {
        if (!valid) {
            return false;
        }
        double rx = ox - origin[0];
        double ry = oy - origin[1];
        double rz = oz - origin[2];
        delta.reset(handle);
        System.arraycopy(axes, 0, delta.axes, 0, 4);
        System.arraycopy(origin, 0, delta.pivot, 0, 3);
        delta.snapped = snapping;
        double[] p1 = q; // scratch: the line parameter or the plane point
        if (handle.rotates()) {
            return rotate(rx, ry, rz, dx, dy, dz, x, y, snapping ? rotateStep : 0, delta);
        }
        if (handle == GizmoHandle.SCALE_UNIFORM) {
            return uniform(rx, ry, rz, dx, dy, dz, x, y, snapping ? scaleStep : 0, delta);
        }
        if (!point(rx, ry, rz, dx, dy, dz, p1)) {
            return false;
        }
        switch (handle) {
            case TRANSLATE_X, TRANSLATE_Y, TRANSLATE_Z -> {
                double s = snap(p1[0] - s0, snapping ? translateStep : 0);
                double[] a = axis[handle.axis()];
                delta.translation[0] = a[0] * s;
                delta.translation[1] = a[1] * s;
                delta.translation[2] = a[2] * s;
                delta.value = s;
            }
            case TRANSLATE_XY, TRANSLATE_YZ, TRANSLATE_ZX, TRANSLATE_SCREEN -> {
                double mx = p1[0] - p0[0];
                double my = p1[1] - p0[1];
                double mz = p1[2] - p0[2];
                if (snapping && translateStep > 0) {
                    // snap along the gizmo's axes
                    double sx = snap(dot(mx, my, mz, axis[0][0], axis[0][1], axis[0][2]), translateStep);
                    double sy = snap(dot(mx, my, mz, axis[1][0], axis[1][1], axis[1][2]), translateStep);
                    double sz = snap(dot(mx, my, mz, axis[2][0], axis[2][1], axis[2][2]), translateStep);
                    mx = axis[0][0] * sx + axis[1][0] * sy + axis[2][0] * sz;
                    my = axis[0][1] * sx + axis[1][1] * sy + axis[2][1] * sz;
                    mz = axis[0][2] * sx + axis[1][2] * sy + axis[2][2] * sz;
                }
                delta.translation[0] = mx;
                delta.translation[1] = my;
                delta.translation[2] = mz;
                delta.value = GizmoMath.length(mx, my, mz);
            }
            case SCALE_X, SCALE_Y, SCALE_Z -> {
                if (Math.abs(s0) < GizmoMath.EPSILON) {
                    return false;
                }
                double f = snapFactor(p1[0] / s0, snapping ? scaleStep : 0);
                delta.scale[handle.axis()] = f;
                delta.value = f;
            }
            case BOUNDS_FACE -> bounds(p1, snapping ? translateStep : 0, delta);
            case BOUNDS_CORNER -> bounds(p1, snapping ? translateStep : 0, delta);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** A typed value replaces the drag's (Blender): the distance, degrees or factor along the handle. */
    public boolean typed(double value, GizmoDelta delta) {
        delta.reset(handle);
        System.arraycopy(axes, 0, delta.axes, 0, 4);
        System.arraycopy(origin, 0, delta.pivot, 0, 3);
        delta.typed = true;
        delta.value = value;
        switch (handle) {
            case TRANSLATE_X, TRANSLATE_Y, TRANSLATE_Z -> {
                double[] a = axis[handle.axis()];
                delta.translation[0] = a[0] * value;
                delta.translation[1] = a[1] * value;
                delta.translation[2] = a[2] * value;
            }
            case ROTATE_X, ROTATE_Y, ROTATE_Z -> {
                double[] a = axis[handle.axis()];
                GizmoMath.axisAngle(a[0], a[1], a[2], Maths.toRadians(value), delta.rotation, 0);
            }
            case ROTATE_VIEW -> GizmoMath.axisAngle(-forward[0], -forward[1], -forward[2], Maths.toRadians(value),
                delta.rotation, 0);
            case SCALE_X, SCALE_Y, SCALE_Z -> delta.scale[handle.axis()] = value;
            case SCALE_UNIFORM -> {
                delta.scale[0] = value;
                delta.scale[1] = value;
                delta.scale[2] = value;
            }
            default -> {
                return false; // planes, the trackball, bounds: two or more numbers, not one
            }
        }
        return true;
    }

    /**
     * Where the ray meets the handle's line (its parameter into {@code out[0]}) or plane (the point, relative to the
     * origin, into {@code out[0..2]}); false if it cannot.
     */
    private boolean point(double rx, double ry, double rz, double dx, double dy, double dz, double[] out) {
        switch (handle) {
            case TRANSLATE_X, TRANSLATE_Y, TRANSLATE_Z, SCALE_X, SCALE_Y, SCALE_Z -> {
                double[] a = axis[handle.axis()];
                double s = closestOnLine(a[0], a[1], a[2], rx, ry, rz, dx, dy, dz);
                out[0] = s;
                return !Double.isNaN(s);
            }
            case TRANSLATE_XY, TRANSLATE_YZ, TRANSLATE_ZX -> {
                return plane(axis[handle.axis()], 0, rx, ry, rz, dx, dy, dz, out);
            }
            case BOUNDS_FACE -> {
                int a = part / 2;
                double[] n = axis[a];
                // along the face's axis, through the face's centre
                double s = closestOnLine(n[0], n[1], n[2], rx, ry, rz, dx, dy, dz);
                out[0] = s;
                return !Double.isNaN(s);
            }
            case BOUNDS_CORNER -> {
                double offset = 0;
                for (int a = 0; a < 3; a++) {
                    double c = (part >> a & 1) != 0 ? boxMax[a] : boxMin[a];
                    offset += c * dot(axis[a][0], axis[a][1], axis[a][2], forward[0], forward[1], forward[2]);
                }
                return plane(forward, offset, rx, ry, rz, dx, dy, dz, out);
            }
            default -> {
                // the screen centre, rings, the uniform scale centre: the view plane through the origin
                return plane(forward, 0, rx, ry, rz, dx, dy, dz, out);
            }
        }
    }

    /** The ray against the plane {@code n . p = offset}: the point into {@code out}; false if parallel or behind. */
    private static boolean plane(double[] n, double offset, double rx, double ry, double rz, double dx, double dy,
        double dz, double[] out) {
        double t = rayPlane(n[0], n[1], n[2], rx - n[0] * offset, ry - n[1] * offset, rz - n[2] * offset, dx, dy, dz);
        if (Double.isNaN(t) || t < 0) {
            return false;
        }
        out[0] = rx + dx * t;
        out[1] = ry + dy * t;
        out[2] = rz + dz * t;
        return true;
    }

    private boolean rotate(double rx, double ry, double rz, double dx, double dy, double dz, float x, float y,
        double step, GizmoDelta delta) {
        double nx;
        double ny;
        double nz;
        if (handle == GizmoHandle.ROTATE_TRACKBALL) {
            double mx;
            double my;
            if (screen) {
                mx = (x - startX) / radiusPx;
                my = (y - startY) / radiusPx;
            } else {
                // an XR ray: its motion on the view plane, in ball radii
                double[] p1 = q;
                if (!plane(forward, 0, rx, ry, rz, dx, dy, dz, p1) || !valid) {
                    return false;
                }
                mx = dot(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2], right[0], right[1], right[2]) / radiusWorld;
                my = -dot(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2], up[0], up[1], up[2]) / radiusWorld;
            }
            double angle = Math.sqrt(mx * mx + my * my);
            if (angle < GizmoMath.EPSILON) {
                delta.value = 0;
                return true;
            }
            // dragging right turns the front of the ball right (about up); dragging down turns it down (about right)
            nx = (up[0] * mx + right[0] * my) / angle;
            ny = (up[1] * mx + right[1] * my) / angle;
            nz = (up[2] * mx + right[2] * my) / angle;
            angle = snap(angle, step);
            GizmoMath.axisAngle(nx, ny, nz, angle, delta.rotation, 0);
            delta.value = Maths.toDegrees(angle);
            return true;
        }
        if (handle == GizmoHandle.ROTATE_VIEW) {
            nx = -forward[0];
            ny = -forward[1];
            nz = -forward[2];
        } else {
            double[] a = axis[handle.axis()];
            nx = a[0];
            ny = a[1];
            nz = a[2];
        }
        double angle;
        double cos = Math.abs(dot(nx, ny, nz, dx, dy, dz)) / GizmoMath.length(dx, dy, dz);
        if (cos < EDGE_ON && screen) {
            // edge-on: the pointer's angle about the gizmo's centre on the screen; the screen's y points down
            double ax = startX - centerX;
            double ay = startY - centerY;
            double bx = x - centerX;
            double by = y - centerY;
            double screenAngle = Maths.atan2(ax * by - ay * bx, ax * bx + ay * by);
            double towards = -dot(nx, ny, nz, forward[0], forward[1], forward[2]);
            angle = towards >= 0 ? -screenAngle : screenAngle;
        } else {
            // the start and the current ray on the ring's plane: the angle between them about its axis
            double[] r = startRay;
            double t0 = rayPlane(nx, ny, nz, r[0], r[1], r[2], r[3], r[4], r[5]);
            double t1 = rayPlane(nx, ny, nz, rx, ry, rz, dx, dy, dz);
            if (Double.isNaN(t0) || Double.isNaN(t1) || t0 < 0 || t1 < 0) {
                return false;
            }
            double sx = r[0] + r[3] * t0;
            double sy = r[1] + r[4] * t0;
            double sz = r[2] + r[5] * t0;
            double ex = rx + dx * t1;
            double ey = ry + dy * t1;
            double ez = rz + dz * t1;
            double cx = sy * ez - sz * ey;
            double cy = sz * ex - sx * ez;
            double cz = sx * ey - sy * ex;
            angle = Maths.atan2(dot(nx, ny, nz, cx, cy, cz), dot(sx, sy, sz, ex, ey, ez));
        }
        angle = snap(angle, step);
        GizmoMath.axisAngle(nx, ny, nz, angle, delta.rotation, 0);
        delta.value = Maths.toDegrees(angle);
        return true;
    }

    private boolean uniform(double rx, double ry, double rz, double dx, double dy, double dz, float x, float y,
        double step, GizmoDelta delta) {
        double f;
        if (screen) {
            double a = Maths.hypot(startX - centerX, startY - centerY);
            double b = Maths.hypot(x - centerX, y - centerY);
            if (a < 1) {
                // grabbed at the very centre: the distance moved right and up, against the ball's radius
                f = 1 + ((x - startX) - (y - startY)) / radiusPx;
            } else {
                f = b / a;
            }
        } else {
            double[] p1 = q;
            if (!plane(forward, 0, rx, ry, rz, dx, dy, dz, p1)) {
                return false;
            }
            double a = GizmoMath.length(p0[0], p0[1], p0[2]);
            double b = GizmoMath.length(p1[0], p1[1], p1[2]);
            f = a < GizmoMath.EPSILON ? 1 : b / a;
        }
        f = Math.max(f, 1e-6);
        f = snapFactor(f, step);
        delta.scale[0] = f;
        delta.scale[1] = f;
        delta.scale[2] = f;
        delta.value = f;
        return true;
    }

    private static double snapFactor(double f, double step) {
        return step > 0 ? 1 + snap(f - 1, step) : f;
    }

    /** A bounds drag: the grabbed faces move, the opposite ones stay; a scale about the fixed side. */
    private void bounds(double[] p1, double step, GizmoDelta delta) {
        for (int a = 0; a < 3; a++) {
            boolean moves;
            boolean max;
            double moved;
            if (handle == GizmoHandle.BOUNDS_FACE) {
                moves = part / 2 == a;
                max = (part & 1) != 0;
                moved = p1[0] - s0;
            } else {
                moves = true;
                max = (part >> a & 1) != 0;
                double[] n = axis[a];
                moved = dot(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2], n[0], n[1], n[2]);
            }
            double fixed = max ? boxMin[a] : boxMax[a];
            double size = boxMax[a] - boxMin[a];
            double f = 1;
            if (moves && size > GizmoMath.EPSILON) {
                moved = snap(moved, step);
                f = (size + (max ? moved : -moved)) / size;
            }
            delta.scale[a] = f;
            // the pivot is the fixed side, along this axis
            double[] n = axis[a];
            delta.pivot[0] += n[0] * fixed;
            delta.pivot[1] += n[1] * fixed;
            delta.pivot[2] += n[2] * fixed;
            if (moves) {
                delta.value = f;
            }
        }
    }
}
