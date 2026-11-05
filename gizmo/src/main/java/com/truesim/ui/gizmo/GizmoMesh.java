package com.truesim.ui.gizmo;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * A gizmo's handles as true 3D geometry for one frame (design 8, XR): a triangle list in world units, its positions
 * relative to {@link #originX() the gizmo's origin} (kept in doubles, so a renderer makes its matrices relative to its
 * camera and nothing jitters far from the world's origin), with normals and colours. What {@link Gizmo#mesh} builds
 * and a renderer draws in the 3D view, depth tested against the scene for every eye (engine-ui's
 * {@code GizmoRenderer}), where the 2D path ({@link Gizmo#paint}) projects the same handles into a UI layer.
 *
 * <p>Each vertex is {@link #VERTEX_BYTES} bytes, little-endian, as a shader reads it:
 *
 * <pre>
 * bytes  0-11   x, y, z   (floats, world units relative to the origin)
 *       12-23   nx, ny, nz (floats, unit normal; flat parts face the head)
 *       24-31   colour     (ui-core's Rgba: four half floats, linear, straight alpha)
 * </pre>
 *
 * Grows to the largest frame and then allocates nothing.
 */
public final class GizmoMesh implements AutoCloseable {

    /** Bytes per vertex. */
    public static final int VERTEX_BYTES = 32;

    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private Arena arena = Arena.ofShared();
    private MemorySegment memory;
    private int capacity;
    private int count;
    private final double[] origin = new double[3];
    private float occluded = 1;
    private long version;
    private boolean closed;

    /** An empty mesh with room for {@code vertices} vertices (it grows past them). */
    @AllowAllocation("made once")
    public GizmoMesh(int vertices) {
        capacity = Math.max(3, vertices);
        memory = arena.allocate((long) capacity * VERTEX_BYTES, 16);
    }

    /** An empty mesh with room for a universal gizmo's handles. */
    @AllowAllocation("made once")
    public GizmoMesh() {
        this(4096);
    }

    /** Empties it for a frame's handles about {@code x, y, z}. */
    void begin(double x, double y, double z) {
        count = 0;
        origin[0] = x;
        origin[1] = y;
        origin[2] = z;
        version++;
    }

    /** Empties it: nothing is drawn. */
    void clear() {
        count = 0;
        version++;
    }

    void occluded(float opacity) {
        occluded = opacity;
    }

    /** The vertices, {@link #VERTEX_BYTES} each; {@link #vertexCount()} of them are this frame's. */
    public MemorySegment vertices() {
        return memory;
    }

    /** How many vertices it holds: three per triangle. */
    public int vertexCount() {
        return count;
    }

    /** The bytes its vertices take. */
    public long bytes() {
        return (long) count * VERTEX_BYTES;
    }

    /** The gizmo's origin x in the world: the vertices' positions are relative to it. */
    public double originX() {
        return origin[0];
    }

    /** The origin y. */
    public double originY() {
        return origin[1];
    }

    /** The origin z. */
    public double originZ() {
        return origin[2];
    }

    /**
     * How opaque its parts draw where the scene is nearer (the {@code gizmo-occluded} part's opacity): a renderer
     * draws those fragments with their colour times this.
     */
    public float occludedOpacity() {
        return occluded;
    }

    /** Grows with each build: a renderer uploads again when it changed. */
    public long version() {
        return version;
    }

    // ---------------------------------------------------------------- building, world points relative to the origin

    /** One vertex at world point {@code p} with normal {@code n}. */
    void vertex(double px, double py, double pz, double nx, double ny, double nz, long color) {
        if (count == capacity) {
            grow();
        }
        long o = (long) count * VERTEX_BYTES;
        memory.set(F32, o, (float) (px - origin[0]));
        memory.set(F32, o + 4, (float) (py - origin[1]));
        memory.set(F32, o + 8, (float) (pz - origin[2]));
        memory.set(F32, o + 12, (float) nx);
        memory.set(F32, o + 16, (float) ny);
        memory.set(F32, o + 20, (float) nz);
        memory.set(U64, o + 24, color);
        count++;
    }

    @AllowAllocation("grows to the largest frame, then never")
    private void grow() {
        int bigger = capacity * 2;
        Arena next = Arena.ofShared();
        MemorySegment more = next.allocate((long) bigger * VERTEX_BYTES, 16);
        MemorySegment.copy(memory, 0, more, 0, (long) count * VERTEX_BYTES);
        arena.close();
        arena = next;
        memory = more;
        capacity = bigger;
    }

    private final double[] u = new double[3];
    private final double[] v = new double[3];
    private final double[] a = new double[3];
    private final double[] b = new double[3];

    /** Two unit vectors across unit direction {@code d}, into {@link #u} and {@link #v}. */
    private void across(double dx, double dy, double dz) {
        double ux;
        double uy;
        double uz;
        if (Math.abs(dx) < 0.9) {
            ux = 0;
            uy = dz;
            uz = -dy;
        } else {
            ux = -dz;
            uy = 0;
            uz = dx;
        }
        double l = GizmoMath.length(ux, uy, uz);
        u[0] = ux / l;
        u[1] = uy / l;
        u[2] = uz / l;
        v[0] = dy * u[2] - dz * u[1];
        v[1] = dz * u[0] - dx * u[2];
        v[2] = dx * u[1] - dy * u[0];
    }

    /**
     * A cone (or a cylinder when {@code r1 == r0}) from {@code p} (radius {@code r0}) to {@code q} (radius {@code r1})
     * with {@code sides} sides, its ends closed where their radius is above 0.
     */
    void frustum(double[] p, double[] q, double r0, double r1, int sides, long color, boolean caps) {
        double dx = q[0] - p[0];
        double dy = q[1] - p[1];
        double dz = q[2] - p[2];
        double length = GizmoMath.length(dx, dy, dz);
        if (length <= 0) {
            return;
        }
        dx /= length;
        dy /= length;
        dz /= length;
        across(dx, dy, dz);
        double slope = (r0 - r1) / length; // the side normal leans along the axis by it
        double ns = 1 / Math.sqrt(1 + slope * slope);
        for (int i = 0; i < sides; i++) {
            double t0 = 2 * Math.PI * i / sides;
            double t1 = 2 * Math.PI * (i + 1) / sides;
            double c0 = Maths.cos(t0);
            double s0 = Maths.sin(t0);
            double c1 = Maths.cos(t1);
            double s1 = Maths.sin(t1);
            double e0x = u[0] * c0 + v[0] * s0;
            double e0y = u[1] * c0 + v[1] * s0;
            double e0z = u[2] * c0 + v[2] * s0;
            double e1x = u[0] * c1 + v[0] * s1;
            double e1y = u[1] * c1 + v[1] * s1;
            double e1z = u[2] * c1 + v[2] * s1;
            double n0x = (e0x + dx * slope) * ns;
            double n0y = (e0y + dy * slope) * ns;
            double n0z = (e0z + dz * slope) * ns;
            double n1x = (e1x + dx * slope) * ns;
            double n1y = (e1y + dy * slope) * ns;
            double n1z = (e1z + dz * slope) * ns;
            // the side: p0, p1 at the start, q1, q0 at the end
            vertex(p[0] + e0x * r0, p[1] + e0y * r0, p[2] + e0z * r0, n0x, n0y, n0z, color);
            vertex(p[0] + e1x * r0, p[1] + e1y * r0, p[2] + e1z * r0, n1x, n1y, n1z, color);
            vertex(q[0] + e1x * r1, q[1] + e1y * r1, q[2] + e1z * r1, n1x, n1y, n1z, color);
            if (r1 > 0) {
                vertex(p[0] + e0x * r0, p[1] + e0y * r0, p[2] + e0z * r0, n0x, n0y, n0z, color);
                vertex(q[0] + e1x * r1, q[1] + e1y * r1, q[2] + e1z * r1, n1x, n1y, n1z, color);
                vertex(q[0] + e0x * r1, q[1] + e0y * r1, q[2] + e0z * r1, n0x, n0y, n0z, color);
            }
            if (caps && r0 > 0) {
                vertex(p[0], p[1], p[2], -dx, -dy, -dz, color);
                vertex(p[0] + e1x * r0, p[1] + e1y * r0, p[2] + e1z * r0, -dx, -dy, -dz, color);
                vertex(p[0] + e0x * r0, p[1] + e0y * r0, p[2] + e0z * r0, -dx, -dy, -dz, color);
            }
            if (caps && r1 > 0) {
                vertex(q[0], q[1], q[2], dx, dy, dz, color);
                vertex(q[0] + e0x * r1, q[1] + e0y * r1, q[2] + e0z * r1, dx, dy, dz, color);
                vertex(q[0] + e1x * r1, q[1] + e1y * r1, q[2] + e1z * r1, dx, dy, dz, color);
            }
        }
    }

    private static final int[] FACE = {0, 1, 2, 0, 2, 3}; // two triangles of a face's corners in order
    private static final double[] S1 = {-1, 1, 1, -1};
    private static final double[] S2 = {-1, -1, 1, 1};

    /**
     * A box of half size {@code half} about {@code c}, along the axes {@code axis[0..2]} (unit vectors); its faces are
     * drawn from both sides, so their winding does not matter.
     */
    void box(double[] c, double half, double[][] axis, long color) {
        for (int f = 0; f < 6; f++) {
            int k = f / 2;
            double sign = (f & 1) == 0 ? 1 : -1;
            double[] n = axis[k];
            double[] e1 = axis[(k + 1) % 3];
            double[] e2 = axis[(k + 2) % 3];
            for (int corner : FACE) {
                double s1 = S1[corner];
                double s2 = S2[corner];
                double x = c[0] + (n[0] * sign + e1[0] * s1 + e2[0] * s2) * half;
                double y = c[1] + (n[1] * sign + e1[1] * s1 + e2[1] * s2) * half;
                double z = c[2] + (n[2] * sign + e1[2] * s1 + e2[2] * s2) * half;
                vertex(x, y, z, n[0] * sign, n[1] * sign, n[2] * sign, color);
            }
        }
    }

    /** A sphere of radius {@code r} about {@code c}: {@code sides} around, half as many rings. */
    void sphere(double[] c, double r, int sides, long color) {
        int rings = Math.max(2, sides / 2);
        for (int j = 0; j < rings; j++) {
            double la0 = Math.PI * j / rings - Math.PI / 2;
            double la1 = Math.PI * (j + 1) / rings - Math.PI / 2;
            for (int i = 0; i < sides; i++) {
                double lo0 = 2 * Math.PI * i / sides;
                double lo1 = 2 * Math.PI * (i + 1) / sides;
                unit(la0, lo0, a);
                unit(la0, lo1, b);
                double[] p = a;
                sphereVertex(c, r, a, color);
                sphereVertex(c, r, b, color);
                unit(la1, lo1, b);
                sphereVertex(c, r, b, color);
                sphereVertex(c, r, p, color);
                sphereVertex(c, r, b, color);
                unit(la1, lo0, b);
                sphereVertex(c, r, b, color);
            }
        }
    }

    private static void unit(double latitude, double longitude, double[] out) {
        double cl = Maths.cos(latitude);
        out[0] = cl * Maths.cos(longitude);
        out[1] = Maths.sin(latitude);
        out[2] = -cl * Maths.sin(longitude);
    }

    private void sphereVertex(double[] c, double r, double[] n, long color) {
        vertex(c[0] + n[0] * r, c[1] + n[1] * r, c[2] + n[2] * r, n[0], n[1], n[2], color);
    }

    /** A flat quadrilateral of corners {@code p0..p3} (in order) facing {@code n}; drawn from both sides. */
    void quad(double[] p0, double[] p1, double[] p2, double[] p3, double[] n, long color) {
        vertex(p0[0], p0[1], p0[2], n[0], n[1], n[2], color);
        vertex(p1[0], p1[1], p1[2], n[0], n[1], n[2], color);
        vertex(p2[0], p2[1], p2[2], n[0], n[1], n[2], color);
        vertex(p0[0], p0[1], p0[2], n[0], n[1], n[2], color);
        vertex(p2[0], p2[1], p2[2], n[0], n[1], n[2], color);
        vertex(p3[0], p3[1], p3[2], n[0], n[1], n[2], color);
    }

    /** Frees its memory. */
    @Override
    @AllowAllocation("closing")
    public void close() {
        if (!closed) {
            closed = true;
            arena.close();
        }
    }
}
