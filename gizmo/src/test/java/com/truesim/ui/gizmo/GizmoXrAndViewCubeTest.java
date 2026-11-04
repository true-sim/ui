package com.truesim.ui.gizmo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.truesim.common.math.Quat;
import com.truesim.common.math.Transform;
import com.truesim.common.math.Vec3;
import com.truesim.scene.core.Mobility;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.scene.SceneRig;
import com.truesim.ui.style.tss.Tss;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** XR manipulation with recorded controller poses (design 8, 12), and the view cube. */
class GizmoXrAndViewCubeTest {

    @Test
    void anXrRayPicksAndDragsAHandleWithAnAngularTolerance() {
        SceneRig r = new SceneRig(Tss.defaultTheme(), null, 800, 600);
        r.picking.mainView(null); // a headset: no screen view
        long node = r.box(0, 1, -2, 0.2, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, null, null).head(0, 1.6, 0);
        g.select(node);
        r.frames(2);
        r.ray(3, 0.2, 1.2, 0, 0, 0, -1, 0).frame(); // lays the gizmo out for the ray
        double l = g.size();
        assertThat(l).isPositive();
        // recorded: the right controller at (0.2, 1.2, 0) aims at 60% of the X arrow, then sweeps right
        double[][] aims = {{0.6 * l, 1, -2}, {0.6 * l + 0.3, 1, -2}, {0.6 * l + 0.6, 1, -2}};
        for (int i = 0; i < aims.length; i++) {
            double[] a = aims[i];
            r.ray(3, 0.2, 1.2, 0, a[0] - 0.2, a[1] - 1.2, a[2], i == 0 ? 0 : Pointer.PRIMARY).frame();
            if (i == 0) {
                assertThat(g.hovered()).isEqualTo(GizmoHandle.TRANSLATE_X);
                r.ray(3, 0.2, 1.2, 0, a[0] - 0.2, a[1] - 1.2, a[2], Pointer.PRIMARY).frame();
                assertThat(g.dragging()).isTrue();
            }
        }
        r.ray(3, 0.2, 1.2, 0, aims[2][0] - 0.2, -0.2, -2, 0).frame();
        assertThat(g.dragging()).isFalse();
        assertThat(r.scene.local(node).translation().x()).isCloseTo(0.6, offset(1e-6));
        assertThat(r.scene.local(node).translation().y()).isEqualTo(1);
    }

    @Test
    void aGrabbedSelectionFollowsTheHandAndTwoHandsScaleAndTurnIt() {
        SceneRig r = new SceneRig(Tss.defaultTheme(), null, 800, 600);
        long node = r.box(0, 1, -1, 0.25, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null);
        List<String> calls = new ArrayList<>();
        g.listener(new GizmoTest.Hooks(calls));
        g.select(node);
        GizmoGrab grab = new GizmoGrab(g, r.scene);
        r.frames(2);
        // recorded: the right hand reaches into the box, grips, moves 0.5 m right turning 90 degrees about y, lets go
        double s = Math.sin(Math.PI / 4);
        double c = Math.cos(Math.PI / 4);
        double[][] right = {{0.4, 1, -1, 0, 0, 0, 1, 0}, {0.1, 1, -1, 0, 0, 0, 1, 1}, {0.3, 1, -1, 0, s / 2, 0, c, 1},
            {0.6, 1, -1, 0, s, 0, c, 1}, {0.6, 1, -1, 0, s, 0, c, 0}};
        for (double[] p : right) {
            grab.hand(1, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7] > 0, false);
            grab.hand(0, -3, 0, 0, 0, 0, 0, 1, false, false);
            grab.update();
            r.frame();
        }
        Transform t = r.scene.local(node);
        // the box turned about the hand (at x 0.1) and moved with it
        Vec3 expected = new Quat(0, s, 0, c).normalize().rotate(new Vec3(-0.1, 0, 0)).add(new Vec3(0.6, 1, -1));
        assertThat(t.translation().x()).isCloseTo(expected.x(), offset(1e-9));
        assertThat(t.translation().z()).isCloseTo(expected.z(), offset(1e-9));
        assertThat(t.rotation().y()).isCloseTo(s, offset(1e-9));
        assertThat(calls).containsExactly("begin 1", "update", "commit 1");
        // both hands in the box, then pulled apart to twice the distance: twice the size
        Vec3 at = t.translation();
        calls.clear();
        double[][] spread = {{0.1, 0.1}, {0.1, 0.1}, {0.15, 0.15}, {0.2, 0.2}};
        for (int i = 0; i < spread.length; i++) {
            boolean gripping = i > 0;
            grab.hand(0, at.x() - spread[i][0], at.y(), at.z(), 0, 0, 0, 1, gripping, false);
            grab.hand(1, at.x() + spread[i][1], at.y(), at.z(), 0, 0, 0, 1, gripping, false);
            grab.update();
            r.frame();
        }
        assertThat(grab.hands()).isEqualTo(2);
        assertThat(grab.delta().value()).isCloseTo(2, offset(1e-9));
        assertThat(r.scene.local(node).scale().x()).isCloseTo(2, offset(1e-9));
        grab.cancel();
        assertThat(r.scene.local(node).scale().x()).isEqualTo(1);
        assertThat(grab.grabbing()).isFalse();
        assertThat(calls).containsExactly("begin 1", "update", "cancel");
    }

    @Test
    void theViewCubeAlignsTheCameraToAFaceEdgeOrCornerAndOrbitsOnADrag() {
        double[] q = new double[4];
        for (int[] d : new int[][] {{0, 0, 1}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {-1, 1, 1}, {1, 0, -1}}) {
            ViewCube.align(d[0], d[1], d[2], q);
            Vec3 forward = new Quat(q[0], q[1], q[2], q[3]).rotate(new Vec3(0, 0, -1));
            double l = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            assertThat(forward.x()).isCloseTo(-d[0] / l, offset(1e-9));
            assertThat(forward.y()).isCloseTo(-d[1] / l, offset(1e-9));
            assertThat(forward.z()).isCloseTo(-d[2] / l, offset(1e-9));
        }
        SceneRig r = new SceneRig(Tss.defaultTheme(), null, 800, 600);
        ViewCube cube = new ViewCube(r.ui, r.view, null);
        r.ui.root().add(cube.element());
        SceneRig.place(r.ui, cube.element(), 600, 20, 120, 120);
        List<String> asked = new ArrayList<>();
        cube.listener(new ViewCube.Listener() {
            @Override
            public void align(double qx, double qy, double qz, double qw, int dx, int dy, int dz) {
                asked.add("align " + dx + " " + dy + " " + dz);
            }

            @Override
            public void orbit(double yaw, double pitch) {
                asked.add("orbit");
            }
        });
        r.frames(2);
        r.mouse(650, 70, 0).mouse(660, 80, 0); // the centre: the face towards the viewer (+z)
        int[] hovered = new int[3];
        assertThat(cube.hovered(hovered)).isTrue();
        assertThat(hovered).containsExactly(0, 0, 1);
        r.click(660, 80);
        r.mouse(660, 80, Pointer.PRIMARY).mouse(680, 80, Pointer.PRIMARY).mouse(700, 90, Pointer.PRIMARY)
            .mouse(700, 90, 0);
        assertThat(asked).startsWith("align 0 0 1").contains("orbit");
        r.mouse(10, 10, 0);
        assertThat(cube.hovered(hovered)).isFalse();
        cube.view(r.view).dispose();
    }
}
