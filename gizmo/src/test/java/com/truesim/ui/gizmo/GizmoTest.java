package com.truesim.ui.gizmo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.truesim.common.math.Transform;
import com.truesim.scene.core.Mobility;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.scene.SceneRig;
import com.truesim.ui.style.tss.Tss;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Gizmos over a scene (design 8, 12): drags solved in doubles far from the origin, snapping, numeric entry, pivots
 * for several nodes, spaces, cancel restoring the start, the undo hooks, the handles' picking with a pixel tolerance.
 */
class GizmoTest {

    static final double FAR = 1e7;

    /** A rig whose camera looks at {@code (cx, cy, cz)} from 10 m in front (+Z) and 4 m above. */
    static SceneRig rig(double cx, double cy, double cz) {
        SceneRig r = new SceneRig(Tss.defaultTheme(), null, 1000, 800);
        double pitch = Math.atan2(4, 10);
        r.view.pose(cx, cy + 4, cz + 10, -Math.sin(pitch / 2), 0, 0, Math.cos(pitch / 2));
        return r;
    }

    static float[] screen(SceneRig r, double x, double y, double z) {
        float[] out = new float[3];
        assertThat(r.view.project(x, y, z, out)).isTrue();
        return out;
    }

    /** Where handle {@code a}'s arrow is on the screen: 60% along it. */
    static float[] arrow(SceneRig r, Gizmo g, int a) {
        double l = g.size() * 0.6;
        return screen(r, g.origin(0) + (a == 0 ? l : 0), g.origin(1) + (a == 1 ? l : 0), g.origin(2) + (a == 2 ? l : 0));
    }

    record Hooks(List<String> calls) implements GizmoListener {
        @Override
        public void begin(GizmoTransforms initial) {
            calls.add("begin " + initial.count());
        }

        @Override
        public void update(GizmoDelta delta) {
            if (!calls.get(calls.size() - 1).equals("update")) {
                calls.add("update");
            }
        }

        @Override
        public void commit(GizmoTransforms initial, GizmoTransforms finals) {
            calls.add("commit " + finals.count());
        }

        @Override
        public void cancel(GizmoTransforms initial) {
            calls.add("cancel");
        }
    }

    @Test
    void draggingTheXArrowTenThousandKilometresOutMovesTheNodeExactlyAlongX() {
        SceneRig r = rig(FAR, FAR, FAR);
        long crate = r.box(FAR, FAR, FAR, 0.5, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null);
        List<String> calls = new ArrayList<>();
        g.listener(new Hooks(calls));
        g.select(crate);
        r.frames(2);
        float[] at = arrow(r, g, 0);
        r.mouse(at[0], at[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.TRANSLATE_X);
        r.mouse(at[0], at[1], Pointer.PRIMARY);
        assertThat(g.dragging()).isTrue();
        float[] to = screen(r, FAR + 3, FAR, FAR);
        float[] from = at;
        r.mouse((from[0] + to[0]) / 2, (from[1] + to[1]) / 2, Pointer.PRIMARY);
        r.mouse(to[0] + (from[0] - arrow(r, g, 0)[0]) * 0, to[1], Pointer.PRIMARY);
        r.mouse(to[0], to[1], 0);
        assertThat(g.dragging()).isFalse();
        Transform t = r.scene.local(crate);
        assertThat(t.translation().y()).isEqualTo(FAR); // exactly: the drag moved along x only
        assertThat(t.translation().z()).isEqualTo(FAR);
        // the pointer went from 60% of the arrow (at the gizmo's size) to 3 m out: the node moved by the difference
        assertThat(t.translation().x() - FAR).isCloseTo(3 - g.size() * 0.6, offset(0.02));
        assertThat(g.delta().value()).isCloseTo(t.translation().x() - FAR, offset(1e-6));
        assertThat(calls).containsExactly("begin 1", "update", "commit 1");
    }

    @Test
    void snappingMovesByIncrementsWhileTheSnapModifierIsHeld() {
        SceneRig r = rig(0, 0, 0);
        long node = r.box(0.2, 0, 0, 0.5, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null);
        g.select(node);
        r.frames(2);
        float[] at = arrow(r, g, 0);
        r.pointers.modifiers(0, Pointer.GIZMO_SNAP);
        r.mouse(at[0], at[1], 0).mouse(at[0], at[1], Pointer.PRIMARY);
        float[] to = screen(r, 2.4, 0, 0);
        r.mouse(to[0], to[1], Pointer.PRIMARY).mouse(to[0], to[1], Pointer.PRIMARY);
        double moved = r.scene.local(node).translation().x() - 0.2;
        assertThat(moved / Gizmo.SNAP_TRANSLATE.get()).isCloseTo(Math.rint(moved / 0.5), offset(1e-9));
        assertThat(g.delta().snapped()).isTrue();
        r.mouse(to[0], to[1], 0);
    }

    @Test
    void aDragMovesEveryNodeOfABigSelection() {
        // 64 nodes were the most a drag moved: of 400 rocks selected, the rest stayed (the engine's UI gallery)
        SceneRig r = rig(0, 0, 0);
        int n = 200;
        long[] nodes = new long[n];
        for (int i = 0; i < n; i++) {
            nodes[i] = r.box(-2 + (i % 20) * 0.2, 0, -(i / 20) * 0.2, 0.05, Mobility.DYNAMIC);
        }
        Gizmo g = new Gizmo(r.picking, r.view, null);
        List<String> calls = new ArrayList<>();
        g.listener(new Hooks(calls));
        g.select(nodes, n, nodes[0]);
        r.frames(2);
        double[] x0 = new double[n];
        for (int i = 0; i < n; i++) {
            x0[i] = r.scene.local(nodes[i]).translation().x();
        }
        float[] at = arrow(r, g, 0);
        r.mouse(at[0], at[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.TRANSLATE_X);
        r.mouse(at[0], at[1], Pointer.PRIMARY);
        r.mouse(at[0] + 40, at[1], Pointer.PRIMARY);
        r.mouse(at[0] + 40, at[1], 0);
        double dx = r.scene.local(nodes[0]).translation().x() - x0[0];
        assertThat(dx).isPositive();
        for (int i = 0; i < n; i++) {
            assertThat(r.scene.local(nodes[i]).translation().x() - x0[i]).as("node %d", i).isCloseTo(dx,
                offset(1e-9));
        }
        assertThat(calls).containsExactly("begin " + n, "update", "commit " + n);
    }

    @Test
    void typedValuesSetTheDragAndCancelRestoresTheStart() {
        SceneRig r = rig(0, 0, 0);
        long a = r.box(-1, 0, 0, 0.5, Mobility.DYNAMIC);
        long b = r.box(1, 0, 0, 0.5, Mobility.DYNAMIC);
        r.scene.setRotation(b, 0, Math.sin(0.3), 0, Math.cos(0.3));
        Gizmo g = new Gizmo(r.picking, r.view, null).mode(GizmoMode.ROTATE);
        List<String> calls = new ArrayList<>();
        g.listener(new Hooks(calls));
        g.select(new long[] {a, b}, 2, b);
        r.frames(2);
        Transform a0 = r.scene.local(a);
        Transform b0 = r.scene.local(b);
        // the Y ring: its front, nearest the camera
        double radius = g.size() * 0.9;
        float[] at = screen(r, radius * Math.sqrt(0.5), 0, radius * Math.sqrt(0.5)); // on the Y ring only
        r.mouse(at[0], at[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.ROTATE_Y);
        r.mouse(at[0], at[1], Pointer.PRIMARY);
        r.pointers.type("90");
        r.frame();
        assertThat(g.typed().toString()).isEqualTo("90");
        // the median pivot: a at -1 turns to (0, 0, 1) about y, b to (0, 0, -1)
        assertThat(r.scene.local(a).translation().z()).isCloseTo(1, offset(1e-9));
        assertThat(r.scene.local(b).translation().z()).isCloseTo(-1, offset(1e-9));
        r.pointers.press(UiCommand.DELETE_BACK);
        r.frame();
        assertThat(g.typed().toString()).isEqualTo("9");
        r.pointers.press(UiCommand.CANCEL);
        r.frame();
        assertThat(g.dragging()).isFalse();
        assertThat(r.scene.local(a)).isEqualTo(a0);
        assertThat(r.scene.local(b)).isEqualTo(b0);
        assertThat(calls).containsExactly("begin 2", "update", "cancel");
        r.mouse(at[0], at[1], 0);
    }

    @Test
    void pivotsTurnSeveralNodesAboutTheMedianTheirOwnOriginsTheActiveOneTheCursorOrTheBoundsCentre() {
        for (GizmoPivot pivot : GizmoPivot.values()) {
            SceneRig r = rig(FAR, 0, 0);
            long a = r.box(FAR - 2, 0, 0, 0.5, Mobility.DYNAMIC);
            long b = r.box(FAR + 1, 0, 0, 1, Mobility.DYNAMIC);
            Gizmo g = new Gizmo(r.picking, r.view, null).mode(GizmoMode.ROTATE).pivot(pivot).cursor(FAR, 0, 5);
            g.select(new long[] {a, b}, 2, a);
            r.frames(2);
            double cx = switch (pivot) {
                case MEDIAN -> FAR - 0.5;
                case ACTIVE -> FAR - 2;
                case CURSOR -> FAR;
                case BOUNDS_CENTER -> FAR - 0.25; // from -2.5 to +2
                case INDIVIDUAL -> FAR - 0.5; // shown at the median
            };
            double cz = pivot == GizmoPivot.CURSOR ? 5 : 0;
            assertThat(g.origin(0)).as("%s", pivot).isCloseTo(cx, offset(1e-9));
            double radius = g.size() * 0.9;
            float[] at = screen(r, cx + radius * Math.sqrt(0.5), 0, cz + radius * Math.sqrt(0.5));
            r.mouse(at[0], at[1], 0).mouse(at[0], at[1], Pointer.PRIMARY);
            assertThat(g.dragging()).as("%s", pivot).isTrue();
            r.pointers.type("180");
            r.frame();
            double ax = r.scene.local(a).translation().x();
            double bx = r.scene.local(b).translation().x();
            if (pivot == GizmoPivot.INDIVIDUAL) {
                assertThat(ax).isEqualTo(FAR - 2);
                assertThat(bx).isEqualTo(FAR + 1);
            } else {
                assertThat(ax).as("%s", pivot).isCloseTo(2 * cx - (FAR - 2), offset(1e-8));
                assertThat(bx).as("%s", pivot).isCloseTo(2 * cx - (FAR + 1), offset(1e-8));
                assertThat(r.scene.local(a).translation().z()).isCloseTo(2 * cz, offset(1e-8));
            }
            // every node turned half round about y
            assertThat(Math.abs(r.scene.local(a).rotation().y())).as("%s", pivot).isCloseTo(1, offset(1e-9));
            r.pointers.press(UiCommand.ACCEPT);
            r.frame();
            assertThat(g.dragging()).isFalse();
        }
    }

    @Test
    void scalingAlongAnAxisUniformlyAndByABoundsFace() {
        SceneRig r = rig(0, 0, 0);
        long node = r.box(0, 0, 0, 0.5, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null).mode(GizmoMode.SCALE);
        g.select(node);
        r.frames(2);
        double l = g.size();
        float[] at = screen(r, l * 0.9, 0, 0); // the X cube
        r.mouse(at[0], at[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.SCALE_X);
        r.mouse(at[0], at[1], Pointer.PRIMARY);
        r.pointers.type("2");
        r.frame();
        r.pointers.press(UiCommand.ACCEPT);
        r.frame();
        assertThat(r.scene.local(node).scale().x()).isCloseTo(2, offset(1e-12));
        assertThat(r.scene.local(node).scale().y()).isEqualTo(1);
        r.mouse(at[0], at[1], 0).frame();
        // uniform: the centre, dragged outwards from a point beside it
        float[] centre = screen(r, 0, 0, 0);
        r.mouse(centre[0] + 4, centre[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.SCALE_UNIFORM);
        r.mouse(centre[0] + 4, centre[1], Pointer.PRIMARY).mouse(centre[0] + 10, centre[1], Pointer.PRIMARY)
            .mouse(centre[0] + 12, centre[1], Pointer.PRIMARY).mouse(centre[0] + 12, centre[1], 0);
        assertThat(r.scene.local(node).scale().y()).isCloseTo(3, offset(1e-6));
        // bounds: the +X face moves out by a metre, the -X face stays
        r.frame();
        g.mode(GizmoMode.BOUNDS);
        r.frames(2);
        double minX = -0.5 * 2 * 3;
        double maxX = 0.5 * 2 * 3;
        float[] face = screen(r, maxX, 0, 0);
        r.mouse(face[0], face[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.BOUNDS_FACE);
        assertThat(g.handlePart()).isEqualTo(1);
        float[] out = screen(r, maxX + 1, 0, 0);
        r.mouse(face[0], face[1], Pointer.PRIMARY).mouse((face[0] + out[0]) / 2, face[1], Pointer.PRIMARY)
            .mouse(out[0], out[1], Pointer.PRIMARY).mouse(out[0], out[1], 0);
        r.frame();
        double newMin = r.scene.local(node).translation().x() - 0.5 * r.scene.local(node).scale().x();
        assertThat(newMin).isCloseTo(minX, offset(1e-6));
        assertThat(r.scene.local(node).scale().x()).isCloseTo(6 * (6 + 1) / 6.0, offset(0.05));
    }

    @Test
    void spacesGiveTheAxesOfTheNodeItsParentTheWorldOrTheView() {
        SceneRig r = rig(0, 0, 0);
        long parent = r.scene.create();
        r.scene.setRotation(parent, 0, 0, Math.sin(Math.PI / 8), Math.cos(Math.PI / 8)); // 45 degrees about z
        long node = r.scene.create(parent);
        r.scene.setLocalBounds(node, -0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        r.scene.setRotation(node, 0, Math.sin(Math.PI / 4), 0, Math.cos(Math.PI / 4)); // 90 about y
        Gizmo g = new Gizmo(r.picking, r.view, null);
        g.select(node);
        assertThat(g.space()).isEqualTo(GizmoSpace.WORLD);
        r.frames(2);
        for (GizmoSpace space : GizmoSpace.values()) {
            g.space(space);
            r.frame();
        }
        r.pointers.press(UiCommand.GIZMO_TOGGLE_SPACE);
        r.frame();
        assertThat(g.space()).isEqualTo(GizmoSpace.LOCAL); // from view, the last: round to local
        // translate along local X: the node's x axis is the world's -z turned 45 degrees about z... move by typing
        float[] at = arrowAlong(r, g, node);
        r.mouse(at[0], at[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.TRANSLATE_X);
        r.mouse(at[0], at[1], Pointer.PRIMARY);
        r.pointers.type("2");
        r.frame();
        r.pointers.press(UiCommand.ACCEPT);
        r.frame();
        r.mouse(at[0], at[1], 0);
        // in the parent's space the node moved along its own x (the parent's -z): 2 units
        assertThat(r.scene.local(node).translation().z()).isCloseTo(-2, offset(1e-9));
        assertThat(r.scene.local(node).translation().x()).isCloseTo(0, offset(1e-9));
        for (UiCommand c : new UiCommand[] {UiCommand.GIZMO_ROTATE, UiCommand.GIZMO_SCALE, UiCommand.GIZMO_UNIVERSAL,
            UiCommand.GIZMO_TRANSLATE}) {
            r.pointers.press(c);
            r.frame();
        }
        assertThat(g.mode()).isEqualTo(GizmoMode.TRANSLATE);
    }

    /** Where the X arrow of a local-space gizmo on {@code node} is on the screen. */
    static float[] arrowAlong(SceneRig r, Gizmo g, long node) {
        Transform w = r.scene.world(node);
        var x = w.rotation().rotate(new com.truesim.common.math.Vec3(1, 0, 0));
        double l = g.size() * 0.6;
        return screen(r, g.origin(0) + x.x() * l, g.origin(1) + x.y() * l, g.origin(2) + x.z() * l);
    }

    @Test
    void theTrackballAndTheViewRingTurnByTheScreenAndARightClickCancels() {
        SceneRig r = rig(0, 0, 0);
        long node = r.box(0, 0, 0, 0.5, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null).mode(GizmoMode.ROTATE);
        g.select(node);
        r.frames(2);
        float[] c = screen(r, 0, 0, 0);
        r.mouse(c[0] + 40, c[1] - 50, 0); // inside the rings, near none
        assertThat(g.hovered()).isEqualTo(GizmoHandle.ROTATE_TRACKBALL);
        r.mouse(c[0] + 40, c[1] - 50, Pointer.PRIMARY).mouse(c[0] + 50, c[1] - 50, Pointer.PRIMARY)
            .mouse(c[0] + 60, c[1] - 50, Pointer.PRIMARY);
        assertThat(r.scene.local(node).rotation().w()).isLessThan(1);
        r.mouse(c[0] + 60, c[1] - 50, Pointer.PRIMARY | Pointer.SECONDARY);
        assertThat(g.dragging()).isFalse();
        assertThat(r.scene.local(node).rotation().w()).isEqualTo(1);
        r.mouse(c[0] + 60, c[1] - 50, 0);
        // the view ring: outside the axis rings
        double radius = g.size() * 1.1;
        float[] ring = screen(r, r.view.right(0) * radius, r.view.right(1) * radius, r.view.right(2) * radius);
        r.mouse(ring[0], ring[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.ROTATE_VIEW);
        r.mouse(ring[0], ring[1], Pointer.PRIMARY).mouse(ring[0], ring[1] - 30, Pointer.PRIMARY)
            .mouse(ring[0], ring[1] - 60, Pointer.PRIMARY).mouse(ring[0], ring[1] - 60, 0);
        assertThat(g.delta().value()).isPositive(); // up on the right side: anticlockwise, seen from the viewer
    }

    @Test
    void handlesArePickedWithAPixelToleranceAndNotFarFromThem() {
        SceneRig r = rig(0, 0, 0);
        long node = r.box(0, 0, 0, 0.5, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null);
        g.select(node);
        r.frames(2);
        float[] at = arrow(r, g, 1);
        r.mouse(at[0] + 4, at[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.TRANSLATE_Y);
        r.mouse(at[0] + 30, at[1], 0);
        assertThat(g.hovered()).isNotEqualTo(GizmoHandle.TRANSLATE_Y);
        g.visible(false);
        r.mouse(at[0], at[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.NONE);
        g.visible(true).clear();
        r.mouse(at[0] + 1, at[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.NONE);
        assertThat(g.count()).isZero();
        g.dispose();
        assertThat(GizmoSpace.VIEW.next()).isEqualTo(GizmoSpace.LOCAL);
    }
}
