package com.truesim.ui.gizmo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.truesim.scene.core.Mobility;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.scene.SceneRig;
import org.junit.jupiter.api.Test;

/**
 * Every gizmo operation by a command (the engine's {@code ui.gizmo} actions): the bounds mode and the next mode, the
 * space backwards, the pivot forwards and backwards, all ignored while dragging.
 */
class GizmoCommandsTest {

    @Test
    void theBoundsCommandShowsTheBoxWhoseFaceScalesAndTheNextModeCyclesEveryMode() {
        SceneRig r = GizmoTest.rig(0, 0, 0);
        long node = r.box(0, 0, 0, 0.5, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null);
        g.select(node);
        r.frames(2);
        r.pointers.press(UiCommand.GIZMO_BOUNDS);
        r.frames(2);
        assertThat(g.mode()).isEqualTo(GizmoMode.BOUNDS);
        // the +X face moves out by half a metre, the -X face stays
        float[] face = GizmoTest.screen(r, 0.5, 0, 0);
        r.mouse(face[0], face[1], 0);
        assertThat(g.hovered()).isEqualTo(GizmoHandle.BOUNDS_FACE);
        float[] out = GizmoTest.screen(r, 1, 0, 0);
        r.mouse(face[0], face[1], Pointer.PRIMARY).mouse((face[0] + out[0]) / 2, face[1], Pointer.PRIMARY)
            .mouse(out[0], out[1], Pointer.PRIMARY);
        assertThat(g.dragging()).isTrue();
        r.pointers.press(UiCommand.GIZMO_TRANSLATE); // ignored while dragging
        r.frame();
        assertThat(g.mode()).isEqualTo(GizmoMode.BOUNDS);
        r.mouse(out[0], out[1], 0).frame();
        double min = r.scene.local(node).translation().x() - 0.5 * r.scene.local(node).scale().x();
        assertThat(min).isCloseTo(-0.5, offset(1e-6));
        assertThat(r.scene.local(node).scale().x()).isCloseTo(1.5, offset(0.05));

        GizmoMode[] cycle = {GizmoMode.UNIVERSAL, GizmoMode.TRANSLATE, GizmoMode.ROTATE, GizmoMode.SCALE,
            GizmoMode.BOUNDS};
        for (GizmoMode expected : cycle) {
            r.pointers.press(UiCommand.GIZMO_NEXT_MODE);
            r.frame();
            assertThat(g.mode()).isEqualTo(expected);
        }
    }

    @Test
    void pivotCommandsCycleEveryPivotBothWaysAndMoveTheGizmo() {
        double far = GizmoTest.FAR;
        SceneRig r = GizmoTest.rig(far, 0, 0);
        long a = r.box(far - 2, 0, 0, 0.5, Mobility.DYNAMIC);
        long b = r.box(far + 1, 0, 0, 1, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null).mode(GizmoMode.ROTATE).cursor(far, 0, 5);
        g.select(new long[] {a, b}, 2, a);
        r.frames(2);
        assertThat(g.pivot()).isEqualTo(GizmoPivot.MEDIAN);
        GizmoPivot[] forwards = {GizmoPivot.INDIVIDUAL, GizmoPivot.ACTIVE, GizmoPivot.CURSOR,
            GizmoPivot.BOUNDS_CENTER, GizmoPivot.MEDIAN};
        double[] originX = {far - 0.5, far - 2, far, far - 0.25, far - 0.5};
        for (int i = 0; i < forwards.length; i++) {
            r.pointers.press(UiCommand.GIZMO_NEXT_PIVOT);
            r.frame();
            assertThat(g.pivot()).isEqualTo(forwards[i]);
            assertThat(g.origin(0)).as("%s", forwards[i]).isCloseTo(originX[i], offset(1e-9));
            assertThat(g.origin(2)).isCloseTo(forwards[i] == GizmoPivot.CURSOR ? 5 : 0, offset(1e-9));
        }
        for (int i = forwards.length - 2; i >= 0; i--) {
            r.pointers.press(UiCommand.GIZMO_PREVIOUS_PIVOT);
            r.frame();
            assertThat(g.pivot()).isEqualTo(forwards[i]);
        }
        r.pointers.press(UiCommand.GIZMO_PREVIOUS_PIVOT);
        r.frame();
        assertThat(g.pivot()).isEqualTo(GizmoPivot.MEDIAN);

        // the active pivot, then a drag: the pivot commands wait until it ends
        g.pivot(GizmoPivot.ACTIVE);
        r.frames(2);
        double radius = g.size() * 0.9;
        float[] at = GizmoTest.screen(r, far - 2 + radius * Math.sqrt(0.5), 0, radius * Math.sqrt(0.5));
        r.mouse(at[0], at[1], 0).mouse(at[0], at[1], Pointer.PRIMARY);
        assertThat(g.dragging()).isTrue();
        r.pointers.press(UiCommand.GIZMO_NEXT_PIVOT);
        r.frame();
        r.pointers.press(UiCommand.GIZMO_PREVIOUS_SPACE);
        r.frame();
        assertThat(g.pivot()).isEqualTo(GizmoPivot.ACTIVE);
        assertThat(g.space()).isEqualTo(GizmoSpace.WORLD);
        r.pointers.press(UiCommand.GIZMO_CANCEL);
        r.frame();
        r.mouse(at[0], at[1], 0);
        assertThat(g.dragging()).isFalse();
    }

    @Test
    void theSpaceCyclesBackwards() {
        SceneRig r = GizmoTest.rig(0, 0, 0);
        long node = r.box(0, 0, 0, 0.5, Mobility.DYNAMIC);
        Gizmo g = new Gizmo(r.picking, r.view, null);
        g.select(node);
        r.frame();
        GizmoSpace[] backwards = {GizmoSpace.LOCAL, GizmoSpace.VIEW, GizmoSpace.PARENT, GizmoSpace.WORLD};
        for (GizmoSpace expected : backwards) {
            r.pointers.press(UiCommand.GIZMO_PREVIOUS_SPACE);
            r.frame();
            assertThat(g.space()).isEqualTo(expected);
        }
        r.pointers.press(UiCommand.GIZMO_TOGGLE_SPACE);
        r.frame();
        assertThat(g.space()).isEqualTo(GizmoSpace.PARENT);
    }
}
