package com.truesim.ui.gizmo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.truesim.common.math.Quat;
import com.truesim.common.math.Vec3;
import com.truesim.scene.core.Mobility;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.scene.SceneRig;
import org.junit.jupiter.api.Test;

/** Gizmos' less travelled paths: typed numbers, every space, every view cube target, hands letting go. */
class GizmoMoreTest {

    @Test
    void typedNumbersAreParsedAsTheyAreTyped() {
        SceneRig r = GizmoTest.rig(0, 0, 0);
        long node = r.box(0, 0, 0, 0.5, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null);
        g.select(node);
        r.frames(2);
        float[] at = GizmoTest.arrow(r, g, 0);
        r.mouse(at[0], at[1], 0).mouse(at[0], at[1], Pointer.PRIMARY);
        String[][] cases = {{"-", "0"}, {"-1.5", "-1.5"}, {"x2", "2"}, {".", "0"}, {"0.25", "0.25"}};
        for (String[] c : cases) {
            while (g.typed().length() > 0) {
                r.pointers.press(UiCommand.DELETE_BACK);
                r.frame();
            }
            r.pointers.type(c[0]);
            r.frame();
            assertThat(r.scene.local(node).translation().x()).as(c[0]).isCloseTo(Double.parseDouble(c[1]),
                offset(0.6)); // a lone "-" or "." is no number: the drag's own value, near 0
        }
        r.pointers.type("1.2.3"); // two points: no number
        r.frame();
        r.pointers.press(UiCommand.GIZMO_CANCEL);
        r.frame();
        assertThat(r.scene.local(node).translation().x()).isZero();
        r.mouse(at[0], at[1], 0);
        g.commit(); // not dragging: nothing
        g.cancel();
    }

    @Test
    void everySpaceAndPivotLaysTheGizmoOut() {
        SceneRig r = GizmoTest.rig(0, 0, 0);
        long parent = r.scene.create();
        r.scene.setRotation(parent, 0, 0, StrictMath.sin(0.3), StrictMath.cos(0.3));
        long a = r.scene.create(parent);
        r.scene.setTranslation(a, 1, 0, 0); // no bounds
        long b = r.box(-1, 0, 0, 0.5, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null).mode(GizmoMode.BOUNDS);
        g.select(new long[] {a, b, a}, 3, 12345); // the active one: not selected, so the last
        assertThat(g.active()).isEqualTo(a);
        assertThat(g.contains(b)).isTrue();
        assertThat(g.selected(1)).isEqualTo(b);
        for (GizmoSpace space : GizmoSpace.values()) {
            for (GizmoPivot pivot : GizmoPivot.values()) {
                g.space(space).pivot(pivot);
                r.frame();
                assertThat(g.size()).isPositive();
            }
        }
        g.space(GizmoSpace.PARENT).select(new long[] {b}, 1, b); // a parentless node's parent space: the world's
        r.frame();
        g.view(null);
        r.frame();
        g.space(GizmoSpace.VIEW).view(null);
        r.frame();
        assertThat(g.mode()).isEqualTo(GizmoMode.BOUNDS);
        assertThat(g.pivot()).isEqualTo(GizmoPivot.BOUNDS_CENTER);
        assertThat(g.element()).isNotNull();
        assertThat(g.delta()).isNotNull();
    }

    @Test
    void everyViewCubeTargetAlignsTheCameraToLookFromIt() {
        double[] q = new double[4];
        for (int t = 0; t < 27; t++) {
            int dx = t % 3 - 1;
            int dy = t / 3 % 3 - 1;
            int dz = t / 9 - 1;
            if (t == 13) {
                continue;
            }
            ViewCube.align(dx, dy, dz, q);
            Vec3 forward = new Quat(q[0], q[1], q[2], q[3]).rotate(new Vec3(0, 0, -1));
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertThat(forward.x()).as("%d %d %d", dx, dy, dz).isCloseTo(-dx / l, offset(1e-9));
            assertThat(forward.y()).isCloseTo(-dy / l, offset(1e-9));
            assertThat(forward.z()).isCloseTo(-dz / l, offset(1e-9));
        }
        SceneRig r = GizmoTest.rig(0, 0, 0); // looking down at it a little: three faces show
        ViewCube cube = new ViewCube(r.ui, r.view, null);
        r.ui.root().add(cube.element());
        SceneRig.place(r.ui, cube.element(), 0, 0, 120, 120);
        r.frames(2);
        int[] seen = new int[3];
        boolean any = false;
        for (int y = 5; y < 120; y += 6) {
            for (int x = 5; x < 120; x += 6) {
                int target = cube.target(x, y);
                any |= target >= 0;
            }
        }
        assertThat(any).isTrue();
        assertThat(cube.target(1000, 1000)).isEqualTo(-1);
        r.click(60, 60); // no listener: nothing asked
        r.mouse(60, 60, Pointer.PRIMARY).mouse(90, 60, Pointer.PRIMARY).mouse(90, 60, 0);
        assertThat(cube.hovered(seen)).isTrue();
    }

    @Test
    void handsLettingGoOneByOneKeepTheGrabAndSnappingSnapsIt() {
        SceneRig r = GizmoTest.rig(0, 1, -1);
        long node = r.box(0, 1, -1, 0.25, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null);
        g.select(node);
        GizmoGrab grab = new GizmoGrab(g, r.scene);
        r.frames(2);
        // both hands grab, spread with snapping (scale and turn snap), one lets go, the other moves on, lets go
        double[][][] poses = {
            {{-0.1, 1, -1, 1}, {0.1, 1, -1, 1}},
            {{-0.22, 1, -1.05, 1}, {0.22, 1, -0.95, 1}},
            {{-0.22, 1, -1.05, 0}, {0.22, 1, -0.95, 1}},
            {{-3, 0, 0, 0}, {0.6, 1, -0.95, 1}},
            {{-3, 0, 0, 0}, {0.6, 1, -0.95, 0}},
        };
        for (double[][] p : poses) {
            grab.hand(0, p[0][0], p[0][1], p[0][2], 0, 0, 0, 1, p[0][3] > 0, true);
            grab.hand(1, p[1][0], p[1][1], p[1][2], 0, 0, 0, 1, p[1][3] > 0, true);
            grab.update();
            r.frame();
        }
        assertThat(grab.grabbing()).isFalse();
        double s = r.scene.local(node).scale().x();
        assertThat((s - 1) / Gizmo.SNAP_SCALE.get()).isCloseTo(Math.rint((s - 1) / 0.1), offset(1e-6));
        assertThat(r.scene.local(node).translation().x()).isNotEqualTo(0);
        grab.cancel(); // not grabbing: nothing
        // a hand far from the selection grabs nothing; a dragging gizmo is not grabbed
        grab.hand(1, 5, 5, 5, 0, 0, 0, 1, true, false);
        grab.update();
        assertThat(grab.grabbing()).isFalse();
        g.clear();
        grab.hand(1, 0, 1, -1, 0, 0, 0, 1, false, false);
        grab.update();
        grab.hand(1, 0, 1, -1, 0, 0, 0, 1, true, false);
        grab.update();
        assertThat(grab.grabbing()).isFalse();
    }
}
