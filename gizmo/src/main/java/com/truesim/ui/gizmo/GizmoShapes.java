package com.truesim.ui.gizmo;

import static com.truesim.ui.gizmo.GizmoMath.dot;

/**
 * Where a gizmo's handles are in the world for one frame (design 8): its origin, its axes, its size (world units, from
 * a screen size or an angular size) and the mode's handles laid out as fractions of that size; and the pointer ray
 * against those analytic shapes (segments, rings, squares, spheres) with a tolerance in world units, the nearest
 * miss winning, small handles before large ones. Shared by picking and drawing, so what is drawn is what is hit.
 */
final class GizmoShapes {

    /** Ring segments for picking and drawing. */
    static final int RING_SEGMENTS = 48;
    /** Below this |cos| between a plane handle's normal and the view direction, the square is edge-on: hidden. */
    static final double PLANE_EDGE_ON = 0.12;
    /** Above this |cos| between an axis and the view direction, the axis points at the viewer: hidden. */
    static final double AXIS_END_ON = 0.985;

    final double[] origin = new double[3];
    final double[] axes = {0, 0, 0, 1};
    final double[][] axis = new double[3][3];
    final double[] forward = {0, 0, -1};
    final double[] right = {1, 0, 0};
    final double[] up = {0, 1, 0};
    double size = 1;
    double boundsHandle = 0.05;
    GizmoMode mode = GizmoMode.TRANSLATE;
    final double[] boxMin = new double[3];
    final double[] boxMax = new double[3];

    // the last pick
    GizmoHandle handle = GizmoHandle.NONE;
    int part;
    double miss;

    /** Sets the axes from rotation {@code (x, y, z, w)}. */
    void axes(double x, double y, double z, double w) {
        axes[0] = x;
        axes[1] = y;
        axes[2] = z;
        axes[3] = w;
        GizmoMath.normalize4(axes, 0);
        for (int a = 0; a < 3; a++) {
            for (int c = 0; c < 3; c++) {
                axis[a][c] = GizmoMath.rotate(axes, 0, a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0, c);
            }
        }
    }

    // ---------------------------------------------------------------- layout, in sizes

    double arrowStart() {
        return 0.2;
    }

    double arrowEnd() {
        return 1;
    }

    /** Where translate arrows' tips start. */
    double tipStart() {
        return 0.82;
    }

    double ringRadius() {
        return mode == GizmoMode.UNIVERSAL ? 0.72 : 0.9;
    }

    double viewRingRadius() {
        return 1.1;
    }

    /** Where scale cubes sit along their axes. */
    double cubeAt() {
        return mode == GizmoMode.UNIVERSAL ? 0.5 : 0.9;
    }

    double cubeHalf() {
        return 0.065;
    }

    double centerRadius() {
        return 0.1;
    }

    double planeMin() {
        return 0.28;
    }

    double planeMax() {
        return 0.48;
    }

    /** Whether the mode shows {@code h}, and the view lets it be seen (axes end-on and planes edge-on are hidden). */
    boolean shows(GizmoHandle h) {
        boolean byMode = switch (h) {
            case TRANSLATE_X, TRANSLATE_Y, TRANSLATE_Z -> mode == GizmoMode.TRANSLATE || mode == GizmoMode.UNIVERSAL;
            case TRANSLATE_XY, TRANSLATE_YZ, TRANSLATE_ZX, TRANSLATE_SCREEN -> mode == GizmoMode.TRANSLATE;
            case ROTATE_X, ROTATE_Y, ROTATE_Z -> mode == GizmoMode.ROTATE || mode == GizmoMode.UNIVERSAL;
            case ROTATE_VIEW, ROTATE_TRACKBALL -> mode == GizmoMode.ROTATE;
            case SCALE_X, SCALE_Y, SCALE_Z -> mode == GizmoMode.SCALE || mode == GizmoMode.UNIVERSAL;
            case SCALE_UNIFORM -> mode == GizmoMode.SCALE;
            case BOUNDS_CORNER, BOUNDS_FACE -> mode == GizmoMode.BOUNDS;
            default -> false;
        };
        if (!byMode) {
            return false;
        }
        if (h == GizmoHandle.TRANSLATE_X || h == GizmoHandle.TRANSLATE_Y || h == GizmoHandle.TRANSLATE_Z
            || h == GizmoHandle.SCALE_X || h == GizmoHandle.SCALE_Y || h == GizmoHandle.SCALE_Z) {
            double[] a = axis[h.axis()];
            return Math.abs(dot(a[0], a[1], a[2], forward[0], forward[1], forward[2])) < AXIS_END_ON;
        }
        if (h.plane()) {
            double[] n = axis[h.axis()];
            return Math.abs(dot(n[0], n[1], n[2], forward[0], forward[1], forward[2])) > PLANE_EDGE_ON;
        }
        return true;
    }

    /** World point of a bounds corner ({@code c}: bits 0 to 2 the max side of x, y, z) into {@code out}. */
    void corner(int c, double[] out) {
        double x = (c & 1) != 0 ? boxMax[0] : boxMin[0];
        double y = (c & 2) != 0 ? boxMax[1] : boxMin[1];
        double z = (c & 4) != 0 ? boxMax[2] : boxMin[2];
        point(x, y, z, out);
    }

    /** World point of the centre of bounds face {@code f} (axis * 2, + 1 for the max side) into {@code out}. */
    void face(int f, double[] out) {
        int a = f / 2;
        double x = a == 0 ? ((f & 1) != 0 ? boxMax[0] : boxMin[0]) : (boxMin[0] + boxMax[0]) / 2;
        double y = a == 1 ? ((f & 1) != 0 ? boxMax[1] : boxMin[1]) : (boxMin[1] + boxMax[1]) / 2;
        double z = a == 2 ? ((f & 1) != 0 ? boxMax[2] : boxMin[2]) : (boxMin[2] + boxMax[2]) / 2;
        point(x, y, z, out);
    }

    /** The world point at {@code x, y, z} in the gizmo's axes from its origin, into {@code out[0..2]}. */
    void point(double x, double y, double z, double[] out) {
        for (int c = 0; c < 3; c++) {
            out[c] = origin[c] + axis[0][c] * x + axis[1][c] * y + axis[2][c] * z;
        }
    }

    /** Point {@code i} of {@link #RING_SEGMENTS} on the ring about {@code n} (with radius {@code r} sizes), into out. */
    void ringPoint(double[] n, int i, double r, double[] out) {
        // two unit vectors across n
        double ux;
        double uy;
        double uz;
        if (Math.abs(n[0]) < 0.9) {
            ux = 0;
            uy = n[2];
            uz = -n[1];
        } else {
            ux = -n[2];
            uy = 0;
            uz = n[0];
        }
        double l = GizmoMath.length(ux, uy, uz);
        ux /= l;
        uy /= l;
        uz /= l;
        double vx = n[1] * uz - n[2] * uy;
        double vy = n[2] * ux - n[0] * uz;
        double vz = n[0] * uy - n[1] * ux;
        double angle = 2 * Math.PI * i / RING_SEGMENTS;
        double cos = Math.cos(angle) * r * size;
        double sin = Math.sin(angle) * r * size;
        out[0] = origin[0] + ux * cos + vx * sin;
        out[1] = origin[1] + uy * cos + vy * sin;
        out[2] = origin[2] + uz * cos + vz * sin;
    }

    /** The normal of ring {@code h}: its axis, or towards the viewer for the view ring. */
    double[] ringNormal(GizmoHandle h, double[] scratch) {
        if (h == GizmoHandle.ROTATE_VIEW) {
            scratch[0] = -forward[0];
            scratch[1] = -forward[1];
            scratch[2] = -forward[2];
            return scratch;
        }
        return axis[h.axis()];
    }

    // ---------------------------------------------------------------- picking

    private final double[] a = new double[3];
    private final double[] b = new double[3];
    private final double[] n = new double[3];

    /**
     * Tests the ray from {@code o} along {@code d} against every shown handle with {@code tolerance} world units of
     * room (at the gizmo's distance); the nearest miss within it is kept in {@link #handle}, {@link #part},
     * {@link #miss}. Returns whether one was hit.
     */
    boolean pick(double ox, double oy, double oz, double dx, double dy, double dz, double tolerance) {
        handle = GizmoHandle.NONE;
        part = 0;
        miss = Double.POSITIVE_INFINITY;
        double dl = GizmoMath.length(dx, dy, dz);
        dx /= dl;
        dy /= dl;
        dz /= dl;
        double reach = 4 * (GizmoMath.length(origin[0] - ox, origin[1] - oy, origin[2] - oz) + size * 2);
        double tol2 = tolerance * tolerance;
        if (mode == GizmoMode.BOUNDS) {
            double r2 = (boundsHandle + tolerance) * (boundsHandle + tolerance);
            for (int c = 0; c < 8; c++) {
                corner(c, a);
                consider(GizmoHandle.BOUNDS_CORNER, c, GizmoMath.pointRaySquared(a[0], a[1], a[2], ox, oy, oz, dx, dy,
                    dz), r2, 0);
            }
            for (int f = 0; f < 6; f++) {
                face(f, a);
                consider(GizmoHandle.BOUNDS_FACE, f, GizmoMath.pointRaySquared(a[0], a[1], a[2], ox, oy, oz, dx, dy,
                    dz), r2, 0.001);
            }
            return handle != GizmoHandle.NONE;
        }
        // centres first: small and in the middle
        double centre2 = GizmoMath.pointRaySquared(origin[0], origin[1], origin[2], ox, oy, oz, dx, dy, dz);
        double cr = centerRadius() * size + tolerance;
        if (shows(GizmoHandle.TRANSLATE_SCREEN)) {
            consider(GizmoHandle.TRANSLATE_SCREEN, 0, centre2, cr * cr, 0);
        }
        if (shows(GizmoHandle.SCALE_UNIFORM)) {
            double ur = 1.2 * centerRadius() * size + tolerance;
            consider(GizmoHandle.SCALE_UNIFORM, 0, centre2, ur * ur, 0);
        }
        for (int i = 0; i < 3; i++) {
            GizmoHandle cube = scaleHandle(i);
            if (shows(cube)) {
                double[] ax = axis[i];
                double at = cubeAt() * size;
                double r = cubeHalf() * size * 1.5 + tolerance;
                consider(cube, 0, GizmoMath.pointRaySquared(origin[0] + ax[0] * at, origin[1] + ax[1] * at,
                    origin[2] + ax[2] * at, ox, oy, oz, dx, dy, dz), r * r, 0.0001);
                if (mode == GizmoMode.SCALE) {
                    segment(i, arrowStart(), cubeAt(), a, b);
                    consider(cube, 0, GizmoMath.segmentRaySquared(a[0], a[1], a[2], b[0], b[1], b[2], ox, oy, oz, dx,
                        dy, dz, reach), tol2, 0.0002);
                }
            }
            GizmoHandle arrow = translateHandle(i);
            if (shows(arrow)) {
                segment(i, arrowStart(), arrowEnd(), a, b);
                consider(arrow, 0, GizmoMath.segmentRaySquared(a[0], a[1], a[2], b[0], b[1], b[2], ox, oy, oz, dx, dy,
                    dz, reach), tol2, 0.0002);
            }
        }
        for (GizmoHandle plane : PLANES) {
            if (shows(plane)) {
                considerPlane(plane, ox, oy, oz, dx, dy, dz, tolerance);
            }
        }
        for (GizmoHandle ring : RINGS) {
            if (shows(ring)) {
                double[] normal = ringNormal(ring, n);
                double r = ring == GizmoHandle.ROTATE_VIEW ? viewRingRadius() : ringRadius();
                double best = Double.POSITIVE_INFINITY;
                ringPoint(normal, 0, r, a);
                for (int s = 1; s <= RING_SEGMENTS; s++) {
                    ringPoint(normal, s % RING_SEGMENTS, r, b);
                    best = Math.min(best, GizmoMath.segmentRaySquared(a[0], a[1], a[2], b[0], b[1], b[2], ox, oy, oz,
                        dx, dy, dz, reach));
                    a[0] = b[0];
                    a[1] = b[1];
                    a[2] = b[2];
                }
                consider(ring, 0, best, tol2, 0.001);
            }
        }
        if (handle == GizmoHandle.NONE && shows(GizmoHandle.ROTATE_TRACKBALL)) {
            double r = ringRadius() * size;
            if (centre2 <= r * r) {
                handle = GizmoHandle.ROTATE_TRACKBALL;
                miss = Math.sqrt(centre2);
            }
        }
        return handle != GizmoHandle.NONE;
    }

    private static final GizmoHandle[] PLANES = {GizmoHandle.TRANSLATE_XY, GizmoHandle.TRANSLATE_YZ,
        GizmoHandle.TRANSLATE_ZX};
    private static final GizmoHandle[] RINGS = {GizmoHandle.ROTATE_X, GizmoHandle.ROTATE_Y, GizmoHandle.ROTATE_Z,
        GizmoHandle.ROTATE_VIEW};

    static GizmoHandle translateHandle(int a) {
        return a == 0 ? GizmoHandle.TRANSLATE_X : a == 1 ? GizmoHandle.TRANSLATE_Y : GizmoHandle.TRANSLATE_Z;
    }

    static GizmoHandle scaleHandle(int a) {
        return a == 0 ? GizmoHandle.SCALE_X : a == 1 ? GizmoHandle.SCALE_Y : GizmoHandle.SCALE_Z;
    }

    static GizmoHandle ringHandle(int a) {
        return a == 0 ? GizmoHandle.ROTATE_X : a == 1 ? GizmoHandle.ROTATE_Y : GizmoHandle.ROTATE_Z;
    }

    /** The world segment of axis {@code i} from {@code from} to {@code to} sizes into {@code p}, {@code q}. */
    void segment(int i, double from, double to, double[] p, double[] q) {
        double[] ax = axis[i];
        for (int c = 0; c < 3; c++) {
            p[c] = origin[c] + ax[c] * from * size;
            q[c] = origin[c] + ax[c] * to * size;
        }
    }

    /** The two in-plane axes of plane handle {@code h}. */
    static int planeU(GizmoHandle h) {
        return (h.axis() + 1) % 3;
    }

    static int planeV(GizmoHandle h) {
        return (h.axis() + 2) % 3;
    }

    private void considerPlane(GizmoHandle h, double ox, double oy, double oz, double dx, double dy, double dz,
        double tolerance) {
        double[] normal = axis[h.axis()];
        double t = GizmoMath.rayPlane(normal[0], normal[1], normal[2], ox - origin[0], oy - origin[1], oz - origin[2],
            dx, dy, dz);
        if (Double.isNaN(t) || t < 0) {
            return;
        }
        double px = ox - origin[0] + dx * t;
        double py = oy - origin[1] + dy * t;
        double pz = oz - origin[2] + dz * t;
        double[] u = axis[planeU(h)];
        double[] v = axis[planeV(h)];
        double pu = dot(px, py, pz, u[0], u[1], u[2]) / size;
        double pv = dot(px, py, pz, v[0], v[1], v[2]) / size;
        double outU = Math.max(0, Math.max(planeMin() - pu, pu - planeMax())) * size;
        double outV = Math.max(0, Math.max(planeMin() - pv, pv - planeMax())) * size;
        double d2 = outU * outU + outV * outV;
        consider(h, 0, d2, tolerance * tolerance, 0.0003);
    }

    /** Keeps {@code h} if its squared miss {@code d2} is within {@code limit2} and nearer than the best so far. */
    private void consider(GizmoHandle h, int handlePart, double d2, double limit2, double bias) {
        if (d2 > limit2) {
            return;
        }
        double m = Math.sqrt(d2) + bias * size;
        if (m < miss) {
            miss = m;
            handle = h;
            part = handlePart;
        }
    }
}
