package com.truesim.ui.gizmo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.truesim.common.math.Quat;
import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.ui.scene.SceneView;
import org.junit.jupiter.api.Test;

/**
 * Drag solving on its own, 10,000 km from the origin (D37): every handle with screen pointers and with XR rays, snapping,
 * typed values, rays that cannot grab; and the arithmetic under it.
 */
class GizmoSolverTest {

    static final double O = 1e7;

    /** A solver at (O, O, O), world axes, seen from +z (looking -z), the ball 100 px or 1 unit. */
    static GizmoSolver solver() {
        GizmoSolver s = new GizmoSolver();
        s.frame(O, O, O, 0, 0, 0, 1, 0, 0, -1, 1, 0, 0, 0, 1, 0, 100, 1);
        s.box(-1, -1, -1, 1, 1, 1);
        return s;
    }

    /** A ray from 10 units in front of the gizmo, through gizmo-relative point {@code (x, y, z)}. */
    static boolean begin(GizmoSolver s, GizmoHandle h, int part, double x, double y, double z, float px, float py) {
        return s.begin(h, part, O, O, O + 10, x, y, z - 10, px, py, 500, 400);
    }

    static boolean drag(GizmoSolver s, double x, double y, double z, float px, float py, boolean snap, GizmoDelta d) {
        return s.drag(O, O, O + 10, x, y, z - 10, px, py, snap, 0.5, StrictMath.toRadians(15), 0.25, d);
    }

    @Test
    void translationsAlongAxesPlanesAndTheViewPlane() {
        GizmoDelta d = new GizmoDelta();
        GizmoSolver s = solver();
        assertThat(begin(s, GizmoHandle.TRANSLATE_X, 0, 0.5, 0, 0, 0, 0)).isTrue();
        assertThat(s.handle()).isEqualTo(GizmoHandle.TRANSLATE_X);
        assertThat(drag(s, 1.7, 0, 0, 0, 0, false, d)).isTrue();
        assertThat(d.translation(0)).isCloseTo(1.2, offset(1e-9));
        assertThat(d.translation(1)).isZero();
        assertThat(drag(s, 1.7, 0, 0, 0, 0, true, d)).isTrue();
        assertThat(d.translation(0)).isEqualTo(1);
        assertThat(d.snapped()).isTrue();
        assertThat(begin(s, GizmoHandle.TRANSLATE_XY, 0, 0.3, 0.3, 0, 0, 0)).isTrue();
        drag(s, 1.0, 0.9, 0, 0, 0, false, d);
        assertThat(d.translation(0)).isCloseTo(0.7, offset(1e-9));
        assertThat(d.translation(1)).isCloseTo(0.6, offset(1e-9));
        assertThat(d.value()).isCloseTo(Math.hypot(0.7, 0.6), offset(1e-9));
        drag(s, 1.0, 0.9, 0, 0, 0, true, d);
        assertThat(d.translation(0)).isEqualTo(0.5);
        assertThat(d.translation(1)).isEqualTo(0.5);
        assertThat(begin(s, GizmoHandle.TRANSLATE_SCREEN, 0, 0, 0, 0, 0, 0)).isTrue();
        drag(s, 0.2, -0.1, 0, 0, 0, false, d);
        assertThat(d.translation(2)).isZero();
        // a ray along the X axis cannot grab its line; a ray in the XY plane cannot grab its square
        assertThat(s.begin(GizmoHandle.TRANSLATE_Z, 0, O, O, O + 10, 0, 0, -1, 0, 0, 0, 0)).isFalse();
        assertThat(drag(s, 1, 1, 0, 0, 0, false, d)).isFalse();
        assertThat(s.begin(GizmoHandle.TRANSLATE_XY, 0, O, O, O + 10, 1, 0, 0, 0, 0, 0, 0)).isFalse();
        assertThat(begin(s, GizmoHandle.TRANSLATE_XY, 0, 0.3, 0.3, 0, 0, 0)).isTrue();
        assertThat(s.drag(O, O, O + 10, 1, 0, 0, 0, 0, false, 0, 0, 0, d)).isFalse(); // parallel now: unchanged
    }

    @Test
    void rotationsOnTheirPlaneEdgeOnByTheScreenAndTheTrackball() {
        GizmoDelta d = new GizmoDelta();
        GizmoSolver s = solver();
        // the Z ring faces the viewer: the angle on its plane
        assertThat(begin(s, GizmoHandle.ROTATE_Z, 0, 1, 0, 0, 600, 400)).isTrue();
        drag(s, 0, 1, 0, 500, 300, false, d);
        assertThat(d.value()).isCloseTo(90, offset(1e-9));
        drag(s, 0.966, 0.259, 0, 0, 0, true, d); // 15 degrees, snapped to 15
        assertThat(d.value()).isCloseTo(15, offset(1e-9));
        // the Y ring is edge-on (its plane holds the view direction): by the pointer's angle about the centre
        assertThat(begin(s, GizmoHandle.ROTATE_Y, 0, 1, 0, 0, 600, 400)).isTrue();
        drag(s, 0, 1, 0, 500, 300, false, d);
        assertThat(Math.abs(d.value())).isCloseTo(90, offset(1e-6));
        // an XR ray edge-on cannot use the screen: the ray against the plane, which it does not meet
        assertThat(s.begin(GizmoHandle.ROTATE_Y, 0, O, O, O + 10, 1, 0, -10, Float.NaN, Float.NaN, Float.NaN,
            Float.NaN)).isTrue();
        assertThat(s.drag(O, O, O + 10, 1, 0, -10, Float.NaN, Float.NaN, false, 0, 0, 0, d)).isFalse();
        // the view ring: about the view direction
        assertThat(begin(s, GizmoHandle.ROTATE_VIEW, 0, 1, 0, 0, 600, 400)).isTrue();
        drag(s, 0, 1, 0, 500, 300, false, d);
        assertThat(d.value()).isCloseTo(90, offset(1e-9));
        // the trackball: right turns about up, down about right, by the ball's radius
        assertThat(begin(s, GizmoHandle.ROTATE_TRACKBALL, 0, 0, 0, 0, 500, 400)).isTrue();
        drag(s, 0, 0, 0, 500, 400, false, d);
        assertThat(d.value()).isZero();
        drag(s, 0, 0, 0, 500 + (float) (50 * Math.PI), 400, false, d);
        assertThat(d.value()).isCloseTo(90, offset(1e-3));
        assertThat(d.rotation(1)).isCloseTo(Math.sqrt(0.5), offset(1e-6));
        // an XR trackball: the ray's motion on the view plane, in ball radii
        assertThat(s.begin(GizmoHandle.ROTATE_TRACKBALL, 0, O, O, O + 10, 0, 0, -1, Float.NaN, Float.NaN, Float.NaN,
            Float.NaN)).isTrue();
        assertThat(s.drag(O, O, O + 10, 0.1, 0, -1, Float.NaN, Float.NaN, true, 0, StrictMath.toRadians(15), 0, d))
            .isTrue();
        assertThat(d.value()).isCloseTo(60, offset(1e-6)); // 1 radian, snapped to 60 degrees
        assertThat(s.drag(O, O, O + 10, 1, 0, 0, Float.NaN, Float.NaN, false, 0, 0, 0, d)).isFalse();
    }

    @Test
    void scalesAlongAxesEvenlyAndByBounds() {
        GizmoDelta d = new GizmoDelta();
        GizmoSolver s = solver();
        assertThat(begin(s, GizmoHandle.SCALE_X, 0, 1, 0, 0, 0, 0)).isTrue();
        drag(s, 2.6, 0, 0, 0, 0, false, d);
        assertThat(d.scale(0)).isCloseTo(2.6, offset(1e-9));
        drag(s, 2.6, 0, 0, 0, 0, true, d);
        assertThat(d.scale(0)).isEqualTo(2.5);
        assertThat(begin(s, GizmoHandle.SCALE_Y, 0, 0, 0, 0, 0, 0)).isTrue(); // grabbed at the origin: no ratio
        assertThat(drag(s, 0, 1, 0, 0, 0, false, d)).isFalse();
        // uniform: by the distance from the centre on the screen
        assertThat(begin(s, GizmoHandle.SCALE_UNIFORM, 0, 0, 0, 0, 520, 400)).isTrue();
        drag(s, 0, 0, 0, 560, 400, false, d);
        assertThat(d.scale(2)).isCloseTo(3, offset(1e-9));
        assertThat(begin(s, GizmoHandle.SCALE_UNIFORM, 0, 0, 0, 0, 500, 400)).isTrue(); // at the very centre
        drag(s, 0, 0, 0, 550, 350, true, d);
        assertThat(d.scale(0)).isEqualTo(2);
        // an XR ray: by the distance from the centre on the view plane
        assertThat(s.begin(GizmoHandle.SCALE_UNIFORM, 0, O, O, O + 10, 0.1, 0, -1, Float.NaN, Float.NaN, Float.NaN,
            Float.NaN)).isTrue();
        assertThat(s.drag(O, O, O + 10, 0.3, 0, -1, Float.NaN, Float.NaN, false, 0, 0, 0, d)).isTrue();
        assertThat(d.scale(1)).isCloseTo(3, offset(1e-9));
        assertThat(s.drag(O, O, O + 10, 1, 0, 0, Float.NaN, Float.NaN, false, 0, 0, 0, d)).isFalse();
        assertThat(s.begin(GizmoHandle.SCALE_UNIFORM, 0, O, O, O + 10, 0, 0, -1, Float.NaN, Float.NaN, Float.NaN,
            Float.NaN)).isTrue();
        s.drag(O, O, O + 10, 0.3, 0, -1, Float.NaN, Float.NaN, false, 0, 0, 0, d);
        assertThat(d.scale(0)).isEqualTo(1); // grabbed at the centre: no ratio
        // a bounds face: the +x face 1 out, the -x face stays: twice as wide... 3/2, about x = -1
        assertThat(begin(s, GizmoHandle.BOUNDS_FACE, 1, 1, 0, 0, 0, 0)).isTrue();
        drag(s, 2, 0, 0, 0, 0, false, d);
        assertThat(d.scale(0)).isCloseTo(1.5, offset(1e-9));
        assertThat(d.pivot(0) - O).isCloseTo(-1, offset(1e-9));
        assertThat(d.scale(1)).isEqualTo(1);
        // a corner (min x, max y, min z): moved by (-1, 1) on the view plane through it
        assertThat(begin(s, GizmoHandle.BOUNDS_CORNER, 2, -1, 1, -1, 0, 0)).isTrue();
        drag(s, -2, 2, -1, 0, 0, true, d);
        assertThat(d.scale(0)).isCloseTo(1.5, offset(1e-9));
        assertThat(d.scale(1)).isCloseTo(1.5, offset(1e-9));
        assertThat(d.scale(2)).isEqualTo(1); // along the view: no motion
    }

    @Test
    void typedValuesReplaceTheDrag() {
        GizmoDelta d = new GizmoDelta();
        GizmoSolver s = solver();
        for (GizmoHandle h : GizmoHandle.values()) {
            if (h == GizmoHandle.NONE) {
                continue;
            }
            begin(s, h, 1, 0.5, 0.4, 0, 0, 0);
            boolean one = s.typed(2, d);
            assertThat(one).as("%s", h).isEqualTo(!h.plane() && h != GizmoHandle.TRANSLATE_SCREEN
                && h != GizmoHandle.ROTATE_TRACKBALL && h != GizmoHandle.BOUNDS_CORNER && h != GizmoHandle.BOUNDS_FACE);
            assertThat(d.typed()).isTrue();
            assertThat(d.value()).isEqualTo(2);
            assertThat(d.handle()).isEqualTo(h);
        }
        begin(s, GizmoHandle.TRANSLATE_Y, 0, 0, 0.5, 0, 0, 0);
        s.typed(-3, d);
        assertThat(d.translation(1)).isEqualTo(-3);
        begin(s, GizmoHandle.ROTATE_X, 0, 0, 1, 0, 0, 0);
        s.typed(180, d);
        assertThat(Math.abs(d.rotation(0))).isCloseTo(1, offset(1e-12));
        assertThat(d.axes(3)).isEqualTo(1);
        assertThat(d.individual()).isFalse();
    }

    @Test
    void theArithmeticUnderneath() {
        assertThat(GizmoMath.closestOnLine(1, 0, 0, 0, 0, 5, 1, 0, 0)).isNaN(); // parallel
        assertThat(GizmoMath.rayPlane(0, 0, 1, 0, 0, 5, 1, 0, 0)).isNaN();
        assertThat(GizmoMath.segmentRaySquared(0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 10)).isCloseTo(1, offset(1e-12));
        assertThat(GizmoMath.segmentRaySquared(0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 10)).isCloseTo(1, offset(1e-12));
        assertThat(GizmoMath.pointRaySquared(0, 0, -1, 0, 0, 0, 0, 0, 1)).isEqualTo(1); // behind the ray's start
        assertThat(GizmoMath.snap(0.74, 0)).isEqualTo(0.74);
        assertThat(GizmoMath.clamp(5, 0, 1)).isEqualTo(1);
        double[] q = {0, 0, 0, 0};
        GizmoMath.normalize4(q, 0);
        assertThat(q[3]).isZero();
        GizmoMath.multiply(0, 0, 0, 1, 0, 1, 0, 0, q, 0);
        assertThat(q[1]).isEqualTo(1);
    }

    @Test
    void rayOcclusionHidesWhatIsBehindOtherNodesButNotTheSelection() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        SpatialIndex index = new SpatialIndex(scene);
        long wall = scene.create();
        scene.setLocalBounds(wall, -1, -1, -0.1, 1, 1, 0.1);
        scene.setTranslation(wall, 0, 0, -5);
        long selected = scene.create();
        scene.setLocalBounds(selected, -0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        scene.setTranslation(selected, 3, 0, -10);
        scene.update();
        index.update();
        RayOcclusion occlusion = new RayOcclusion(index, -1, node -> node == selected);
        SceneView v = new SceneView().perspective(1, 0.1, 100).viewport(0, 0, 100, 100);
        assertThat(occlusion.occluded(v, 0, 0, -10)).isTrue(); // behind the wall
        assertThat(occlusion.occluded(v, 0, 0, -2)).isFalse(); // before it
        assertThat(occlusion.occluded(v, 3, 0, -10)).isFalse(); // in the selection, which does not hide
        SceneView top = new SceneView().orthographic(10, 0.1, 100).viewport(0, 0, 100, 100)
            .pose(0, 0, 50, 0, 0, 0, 1);
        assertThat(occlusion.occluded(top, 0.5, 0, -10)).isTrue();
        assertThat(occlusion.occluded(top, 5, 0, -10)).isFalse();
        Vec3 v1 = new Quat(0, 0, 0, 1).rotate(new Vec3(1, 0, 0));
        assertThat(v1.x()).isEqualTo(1);
    }
}
