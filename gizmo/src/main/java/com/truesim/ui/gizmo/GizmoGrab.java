package com.truesim.ui.gizmo;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.ui.core.Tunable;

/**
 * XR manipulation of a {@link Gizmo}'s selection by hand (design 8, XR): a direct grab (a controller's grip held with
 * the hand inside a selected node's box, or at the gizmo's centre) makes the selection follow the hand rigidly; with
 * both hands grabbing, the selection moves with their midpoint, turns with the line between them and scales with
 * their distance (two-handed scale and rotate). Snapping (a held snap action, or a trigger past its threshold)
 * snaps the moves, turns and factors to the gizmo's increments. The gizmo's {@link GizmoListener} gets the same
 * {@code begin}, {@code update}, {@code commit} as for a pointer drag, so undo works the same.
 *
 * <p>Feed the hands' poses every frame ({@link #hand}), then {@link #update()}. Allocation-free.
 */
public final class GizmoGrab {

    /** How far outside a selected node's box (in metres) a hand still grabs it. */
    public static final Tunable GRAB_RADIUS = Tunable.of("ui.gizmo.xrGrabRadius", 0.06f, 0, 1,
        "Metres outside a selected node's box (or from the gizmo's centre) an XR hand still grabs it.");

    private final Gizmo gizmo;
    private final SceneGraph scene;
    private final double[][] pose = new double[2][7];
    private final double[][] start = new double[2][7];
    private final boolean[] grip = new boolean[2];
    private final boolean[] held = new boolean[2];
    private final boolean[] snap = new boolean[2];
    private final GizmoTransforms initial = new GizmoTransforms();
    private final GizmoTransforms finals = new GizmoTransforms();
    private final GizmoDelta delta = new GizmoDelta();
    private final long[] nodes = new long[64];
    private boolean grabbing;
    private int hands;

    /** Hand manipulation of {@code target}'s selection in {@code graph}. */
    @AllowAllocation("one per gizmo")
    public GizmoGrab(Gizmo target, SceneGraph graph) {
        this.gizmo = target;
        this.scene = graph;
    }

    /**
     * Hand {@code index} (0 left, 1 right) is at {@code x, y, z} turned by {@code qx, qy, qz, qw} this frame, its grip
     * held or not, snapping or not.
     */
    public void hand(int index, double x, double y, double z, double qx, double qy, double qz, double qw,
        boolean gripHeld, boolean snapping) {
        double[] h = pose[index];
        h[0] = x;
        h[1] = y;
        h[2] = z;
        h[3] = qx;
        h[4] = qy;
        h[5] = qz;
        h[6] = qw;
        GizmoMath.normalize4(h, 3);
        grip[index] = gripHeld;
        snap[index] = snapping;
    }

    /** Whether a hand grabs the selection now. */
    public boolean grabbing() {
        return grabbing;
    }

    /** How many hands grab. */
    public int hands() {
        return hands;
    }

    /** What the grab did so far. */
    public GizmoDelta delta() {
        return delta;
    }

    /** Takes this frame's hands: grabs, follows, lets go. */
    public void update() {
        for (int i = 0; i < 2; i++) {
            if (grip[i] && !held[i] && reaches(i)) {
                held[i] = true;
                restart();
            } else if (!grip[i] && held[i]) {
                held[i] = false;
                if (held[0] || held[1]) {
                    restart(); // from two hands to one: the remaining hand goes on from here
                }
            }
        }
        int now = (held[0] ? 1 : 0) + (held[1] ? 1 : 0);
        if (now == 0) {
            if (grabbing) {
                grabbing = false;
                finals.capture(scene, nodes, count());
                GizmoListener l = gizmo.listener();
                if (l != null) {
                    l.commit(initial, finals);
                }
            }
            hands = 0;
            return;
        }
        hands = now;
        solve();
        GizmoApply.apply(scene, initial, delta);
        GizmoListener l = gizmo.listener();
        if (l != null) {
            l.update(delta);
        }
    }

    /** Lets go, restoring the selection's transforms from the grab's start. */
    public void cancel() {
        if (!grabbing) {
            return;
        }
        grabbing = false;
        held[0] = false;
        held[1] = false;
        hands = 0;
        initial.restore(scene);
        GizmoListener l = gizmo.listener();
        if (l != null) {
            l.cancel(initial);
        }
    }

    private int count() {
        return Math.min(gizmo.count(), nodes.length);
    }

    /** Whether hand {@code i} is inside (or near) a selected node's box or at the gizmo's centre. */
    private boolean reaches(int i) {
        if (gizmo.count() == 0 || gizmo.dragging()) {
            return false;
        }
        double r = GRAB_RADIUS.get();
        double[] h = pose[i];
        for (int k = 0; k < gizmo.count(); k++) {
            long node = gizmo.selected(k);
            if (scene.contains(node) && scene.hasBounds(node)) {
                int s = NodeHandle.index(node);
                if (h[0] >= scene.worldBounds().get(s, 0) - r && h[1] >= scene.worldBounds().get(s, 1) - r
                    && h[2] >= scene.worldBounds().get(s, 2) - r && h[0] <= scene.worldBounds().get(s, 3) + r
                    && h[1] <= scene.worldBounds().get(s, 4) + r && h[2] <= scene.worldBounds().get(s, 5) + r) {
                    return true;
                }
            }
        }
        double reach = Math.max(r, gizmo.size() * 0.15);
        return GizmoMath.length(h[0] - gizmo.origin(0), h[1] - gizmo.origin(1), h[2] - gizmo.origin(2)) <= reach;
    }

    /** The grab starts (again) from the hands' poses now and the nodes' transforms now. */
    private void restart() {
        boolean first = !grabbing;
        int n = count();
        for (int k = 0; k < n; k++) {
            nodes[k] = gizmo.selected(k);
        }
        if (first) {
            initial.capture(scene, nodes, n);
            grabbing = true;
            GizmoListener l = gizmo.listener();
            if (l != null) {
                l.begin(initial);
            }
        } else {
            // world transforms from now on, local transforms of the grab's start for undo
            initial.rebase(scene);
        }
        for (int i = 0; i < 2; i++) {
            System.arraycopy(pose[i], 0, start[i], 0, 7);
        }
    }

    private void solve() {
        delta.reset(GizmoHandle.NONE);
        boolean snapping = snap[0] && held[0] || snap[1] && held[1];
        if (hands == 1) {
            int i = held[0] ? 0 : 1;
            double[] s = start[i];
            double[] h = pose[i];
            // the hand's motion: turn about where it started, then move with it
            delta.pivot[0] = s[0];
            delta.pivot[1] = s[1];
            delta.pivot[2] = s[2];
            delta.translation[0] = h[0] - s[0];
            delta.translation[1] = h[1] - s[1];
            delta.translation[2] = h[2] - s[2];
            GizmoMath.multiply(h[3], h[4], h[5], h[6], -s[3], -s[4], -s[5], s[6], delta.rotation, 0);
            GizmoMath.normalize4(delta.rotation, 0);
        } else {
            double[] a0 = start[0];
            double[] b0 = start[1];
            double[] a1 = pose[0];
            double[] b1 = pose[1];
            delta.pivot[0] = (a0[0] + b0[0]) / 2;
            delta.pivot[1] = (a0[1] + b0[1]) / 2;
            delta.pivot[2] = (a0[2] + b0[2]) / 2;
            delta.translation[0] = (a1[0] + b1[0]) / 2 - delta.pivot[0];
            delta.translation[1] = (a1[1] + b1[1]) / 2 - delta.pivot[1];
            delta.translation[2] = (a1[2] + b1[2]) / 2 - delta.pivot[2];
            double ux = b0[0] - a0[0];
            double uy = b0[1] - a0[1];
            double uz = b0[2] - a0[2];
            double vx = b1[0] - a1[0];
            double vy = b1[1] - a1[1];
            double vz = b1[2] - a1[2];
            double lu = GizmoMath.length(ux, uy, uz);
            double lv = GizmoMath.length(vx, vy, vz);
            double f = lu > GizmoMath.EPSILON ? lv / lu : 1;
            if (snapping) {
                f = 1 + GizmoMath.snap(f - 1, Gizmo.SNAP_SCALE.get());
            }
            delta.scale[0] = f;
            delta.scale[1] = f;
            delta.scale[2] = f;
            // the shortest turn from the start's line between the hands to the current one
            if (lu > GizmoMath.EPSILON && lv > GizmoMath.EPSILON) {
                ux /= lu;
                uy /= lu;
                uz /= lu;
                vx /= lv;
                vy /= lv;
                vz /= lv;
                double cx = uy * vz - uz * vy;
                double cy = uz * vx - ux * vz;
                double cz = ux * vy - uy * vx;
                double cos = GizmoMath.dot(ux, uy, uz, vx, vy, vz);
                double sin = GizmoMath.length(cx, cy, cz);
                double angle = Math.atan2(sin, cos);
                if (snapping) {
                    angle = GizmoMath.snap(angle, Math.toRadians(Gizmo.SNAP_ROTATE.get()));
                }
                if (sin > GizmoMath.EPSILON) {
                    GizmoMath.axisAngle(cx / sin, cy / sin, cz / sin, angle, delta.rotation, 0);
                }
            }
            delta.value = f;
        }
        if (snapping) {
            double step = Gizmo.SNAP_TRANSLATE.get();
            for (int c = 0; c < 3; c++) {
                delta.translation[c] = GizmoMath.snap(delta.translation[c], step);
            }
            delta.snapped = true;
        }
    }
}
