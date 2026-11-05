package com.truesim.ui.gizmo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.truesim.scene.core.Mobility;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.scene.SceneRig;
import com.truesim.ui.style.tss.Tss;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * A gizmo's handles as 3D geometry (XR, design 8): triangles in world units about the gizmo's origin, sized and
 * coloured as the 2D handles, ring backs faded, nothing when hidden, no allocation once grown.
 */
class GizmoMeshTest {

    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    record Rig(SceneRig r, Gizmo g, long node) {
    }

    static Rig xr(String theme) {
        SceneRig r = new SceneRig(theme == null ? Tss.defaultTheme() : Tss.testTheme(), null, 800, 600);
        r.picking.mainView(null); // a headset: no screen view
        long node = r.box(0.5, 1.2, -2, 0.2, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, null, null).head(0, 1.6, 0);
        g.select(node);
        r.frames(2);
        return new Rig(r, g, node);
    }

    static float x(MemorySegment m, int v, int c) {
        return m.get(F32, (long) v * GizmoMesh.VERTEX_BYTES + 4L * c);
    }

    static long color(MemorySegment m, int v) {
        return m.get(U64, (long) v * GizmoMesh.VERTEX_BYTES + 24);
    }

    @Test
    void translateHandlesAreTrianglesAboutTheOriginSizedByAngularSize() {
        Rig rig = xr(null);
        try (GizmoMesh mesh = new GizmoMesh(16)) { // grows from a tiny start
            assertThat(rig.g.mesh(mesh)).isTrue();
            assertThat(mesh.vertexCount() % 3).isZero();
            assertThat(mesh.vertexCount()).isGreaterThan(100);
            assertThat(mesh.bytes()).isEqualTo((long) mesh.vertexCount() * GizmoMesh.VERTEX_BYTES);
            assertThat(mesh.originX()).isCloseTo(0.5, offset(1e-9));
            assertThat(mesh.originY()).isCloseTo(1.2, offset(1e-9));
            assertThat(mesh.originZ()).isCloseTo(-2, offset(1e-9));
            double size = rig.g.size();
            // angular size: the XR default 9 degrees seen from the head 2.06 m away
            double distance = Math.sqrt(0.25 + 0.16 + 4);
            assertThat(size).isCloseTo(distance * Math.tan(Math.toRadians(4.5)) * 2, offset(1e-9));
            MemorySegment m = mesh.vertices();
            double maxX = 0;
            double reach = 0;
            for (int v = 0; v < mesh.vertexCount(); v++) {
                double nx = x(m, v, 3);
                double ny = x(m, v, 4);
                double nz = x(m, v, 5);
                assertThat(Math.sqrt(nx * nx + ny * ny + nz * nz)).isCloseTo(1, offset(1e-4));
                maxX = Math.max(maxX, x(m, v, 0));
                reach = Math.max(reach, Math.sqrt(x(m, v, 0) * x(m, v, 0) + x(m, v, 1) * x(m, v, 1)
                    + x(m, v, 2) * x(m, v, 2)));
            }
            assertThat(maxX).as("the X arrow's tip at its full length").isCloseTo(size, offset(size * 1e-4));
            assertThat(reach).isLessThan(size * 1.01);
            // the X arrow is coloured as its 2D part: red dominates the vertex farthest along +x
            int tip = farthest(m, mesh.vertexCount(), 0);
            long c = color(m, tip);
            assertThat(Rgba.r(c)).isGreaterThan(Rgba.g(c)).isGreaterThan(0.2f);
            assertThat(Rgba.a(c)).isEqualTo(1);
            assertThat(mesh.occludedOpacity()).isCloseTo(0.28f, offset(1e-6f));
            long version = mesh.version();
            rig.g.mesh(mesh);
            assertThat(mesh.version()).isGreaterThan(version);
        }
    }

    static int farthest(MemorySegment m, int count, int axis) {
        int best = 0;
        for (int v = 1; v < count; v++) {
            if (x(m, v, axis) > x(m, best, axis)) {
                best = v;
            }
        }
        return best;
    }

    @Test
    void everyModeBuildsAndRingBacksTakeTheOccludedOpacity() {
        Rig rig = xr("test");
        try (GizmoMesh mesh = new GizmoMesh()) {
            int[] counts = new int[GizmoMode.values().length];
            for (GizmoMode mode : GizmoMode.values()) {
                rig.g.mode(mode);
                rig.r.frame();
                assertThat(rig.g.mesh(mesh)).as(mode.name()).isTrue();
                assertThat(mesh.vertexCount() % 3).isZero();
                counts[mode.ordinal()] = mesh.vertexCount();
            }
            assertThat(counts).doesNotHaveDuplicates();
            rig.g.mode(GizmoMode.ROTATE);
            rig.r.frame();
            rig.g.mesh(mesh);
            // the test theme's occluded opacity (0.6) on the back halves of the axis rings, seen from the head
            float fade = mesh.occludedOpacity();
            assertThat(fade).isCloseTo(0.6f, offset(1e-6f));
            MemorySegment m = mesh.vertices();
            int front = 0;
            int back = 0;
            for (int v = 0; v < mesh.vertexCount(); v++) {
                float a = Rgba.a(color(m, v));
                // forward from the head (0, 1.6, 0) to the gizmo (0.5, 1.2, -2): a back vertex lies farther along it
                if (Math.abs(a - fade) < 1e-3f) {
                    back++;
                    double along = (x(m, v, 0) * 0.5 - x(m, v, 1) * 0.4 - x(m, v, 2) * 2) / Math.sqrt(4.41);
                    assertThat(along).as("behind the origin seen from the head").isGreaterThan(-rig.g.size() * 0.1); // half a chord and the tube
                } else if (a > 0.99f) {
                    front++;
                }
            }
            assertThat(back).isPositive();
            assertThat(front).isPositive();
            rig.g.visible(false);
            assertThat(rig.g.mesh(mesh)).isFalse();
            assertThat(mesh.vertexCount()).isZero();
        }
    }

    @Test
    void aScreenGizmoBuildsThroughItsViewAndNothingWithoutASelection() {
        SceneRig r = new SceneRig(Tss.defaultTheme(), null, 800, 600);
        long node = r.box(0, 0, -6, 0.5, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null);
        r.frames(2);
        try (GizmoMesh mesh = new GizmoMesh()) {
            assertThat(g.mesh(mesh)).as("nothing selected").isFalse();
            g.select(node);
            r.frame();
            assertThat(g.mesh(mesh)).isTrue();
            assertThat(mesh.originZ()).isCloseTo(-6, offset(1e-9));
            g.mode(GizmoMode.UNIVERSAL);
            assertThat(g.mesh(mesh)).isTrue();
        }
        g.dispose();
    }

    @Test
    void buildingEachFrameAllocatesNothing() {
        Rig rig = xr(null);
        GizmoMesh mesh = new GizmoMesh();
        GizmoMode[] modes = GizmoMode.values();
        int[] step = {0};
        Runnable frame = () -> {
            int i = step[0]++;
            rig.g.head(0.01 * (i % 20), 1.6, 0); // the head moves: the size follows
            if (i % 30 == 0) {
                rig.g.mode(modes[i / 30 % modes.length]);
            }
            rig.g.mesh(mesh);
        };
        AllocationAssertions.assertAllocationFree(frame, 4_000, 1_000, 6);
        mesh.close();
        mesh.close();
    }
}
