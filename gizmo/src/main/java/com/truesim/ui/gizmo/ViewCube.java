package com.truesim.ui.gizmo;

import com.truesim.common.math.Maths;
import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Painter;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.scene.SceneView;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;

/**
 * The view cube (design 8; ImGuizmo's ViewManipulate): a UI element showing a cube turned as the camera sees the world;
 * clicking one of its 6 faces, 12 edges or 8 corners asks to align the camera to look at the world from there
 * ({@link Listener#align}), dragging it asks to orbit ({@link Listener#orbit}). The engine (or the editor's camera)
 * animates the camera; the cube follows the view each frame.
 *
 * <p>Element type {@code view-cube} (its box, border and background from the theme), with style parts
 * {@code view-cube-face#px #nx #py #ny #pz #nz} (each face's colour), {@code view-cube-edge} (the outline's colour
 * and width) and {@code view-cube-hot} (the hovered face, edge or corner: colour and marker size). Faces are drawn as
 * projected quads, each two triangles. Steady state allocates nothing.
 */
public final class ViewCube implements Painter, Ui.FrameListener {

    /** Radians the view orbits per pixel dragged on the cube. */
    public static final Tunable ORBIT_SPEED = Tunable.of("ui.viewCube.orbitSpeed", 0.01f, 0.001f, 0.1f,
        "Radians the camera orbits per pixel dragged on the view cube.");

    /** What the cube asks of the camera. */
    public interface Listener {

        /**
         * Align the camera to rotation {@code qx, qy, qz, qw}: looking along {@code -(dx, dy, dz)} (the clicked face's,
         * edge's or corner's direction from the centre), the world's up kept up where it can be.
         */
        void align(double qx, double qy, double qz, double qw, int dx, int dy, int dz);

        /** Orbit by {@code yaw} radians about the world's up and {@code pitch} about the camera's right. */
        void orbit(double yaw, double pitch);
    }

    private static final int LISTENED = EventType.MOVE.bit() | EventType.EXIT.bit() | EventType.CLICK.bit()
        | EventType.DRAG_START.bit() | EventType.DRAG.bit() | EventType.PRESS.bit();
    private static final String[] FACES = {"px", "nx", "py", "ny", "pz", "nz"};

    private final Ui ui;
    private SceneView view;
    private final Element element;
    private final Element[] faceParts = new Element[6];
    private final Element edgePart;
    private final Element hotPart;
    private Listener listener;
    private int hot = -1; // the hovered target: (x + 1) + 3 (y + 1) + 9 (z + 1), 13 is none
    private long seenVersion = -1;
    private float centerX;
    private float centerY;
    private float scale;
    private final double[] q = new double[4];
    private final float[] pa = new float[2];
    private final float[] quad = new float[8]; // a projected face's corners
    private final float[] pb = new float[2];
    private final UiListener events = this::handle;

    /** A view cube of {@code ui} following {@code sceneView}'s camera, with style name {@code style} (or null). */
    @AllowAllocation("controls are made when a UI is built")
    public ViewCube(Ui ui, SceneView sceneView, String style) {
        this.ui = ui;
        this.view = sceneView;
        element = ui.element("view-cube", style);
        ui.paint(element, this);
        ui.interaction().on(element, LISTENED, events);
        for (int f = 0; f < 6; f++) {
            faceParts[f] = ui.part(element, "view-cube-face", null).id(FACES[f]);
        }
        edgePart = ui.part(element, "view-cube-edge", null);
        hotPart = ui.part(element, "view-cube-hot", null);
        ui.addFrameListener(this);
    }

    /** The element: add it where the cube shows (a corner of the 3D view). */
    public Element element() {
        return element;
    }

    /** Sets what the cube asks of the camera; returns this. */
    public ViewCube listener(Listener value) {
        listener = value;
        return this;
    }

    /** Follows {@code sceneView}'s camera from now on; returns this. */
    public ViewCube view(SceneView sceneView) {
        view = sceneView;
        ui.repaint(element);
        return this;
    }

    /**
     * The hovered target as its direction ({@code out[0..2]}, each -1, 0 or 1: a face has one non-zero component, an
     * edge two, a corner three); false when none is hovered.
     */
    public boolean hovered(int[] out) {
        if (hot < 0) {
            return false;
        }
        out[0] = hot % 3 - 1;
        out[1] = hot / 3 % 3 - 1;
        out[2] = hot / 9 - 1;
        return true;
    }

    @Override
    public void frame(long nanos) {
        if (view.version() != seenVersion) {
            seenVersion = view.version();
            ui.repaint(element);
        }
    }

    // ---------------------------------------------------------------- input

    private void handle(UiEvent e) {
        switch (e.type()) {
            case MOVE -> hover(target(e.x(), e.y()));
            case EXIT -> hover(-1);
            case PRESS -> e.consume(); // the cube's own: nothing behind it gets the press
            case CLICK -> {
                int t = target(e.x(), e.y());
                if (t >= 0 && listener != null) {
                    int dx = t % 3 - 1;
                    int dy = t / 3 % 3 - 1;
                    int dz = t / 9 - 1;
                    align(dx, dy, dz, q);
                    listener.align(q[0], q[1], q[2], q[3], dx, dy, dz);
                }
                e.consume();
            }
            case DRAG_START -> e.consume();
            case DRAG -> {
                if (listener != null) {
                    float speed = ORBIT_SPEED.get();
                    listener.orbit(-e.deltaX() * speed, -e.deltaY() * speed);
                }
                e.consume();
            }
            default -> {
            }
        }
    }

    private void hover(int target) {
        if (target != hot) {
            hot = target;
            hotPart.state(UiState.HOVER, target >= 0);
            ui.repaint(element);
        }
    }

    /**
     * The face, edge or corner under {@code x, y}: of the targets facing the viewer, the nearest on the screen
     * (corners and edges before faces near them); -1 for none.
     */
    int target(float x, float y) {
        if (scale <= 0) {
            return -1;
        }
        int best = -1;
        double bestDistance = scale * 0.75;
        for (int t = 0; t < 27; t++) {
            if (t == 13) {
                continue;
            }
            int dx = t % 3 - 1;
            int dy = t / 3 % 3 - 1;
            int dz = t / 9 - 1;
            double depth = camera(dx, dy, dz, 2);
            if (depth < 0.05) {
                continue; // facing away
            }
            project(dx, dy, dz, pa);
            int nonZero = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            double d = Maths.hypot(pa[0] - x, pa[1] - y) * (nonZero == 1 ? 1.6 : 1);
            if (d < bestDistance) {
                bestDistance = d;
                best = t;
            }
        }
        return best;
    }

    /** The camera rotation looking at the centre from direction {@code d}, up kept as the world's +Y. */
    static void align(int dx, int dy, int dz, double[] out) {
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double zx = dx / l;
        double zy = dy / l;
        double zz = dz / l;
        // up: the world's +Y, or for views straight down (up) -Z (+Z)
        double ux = 0;
        double uy = 1;
        double uz = 0;
        if (dx == 0 && dz == 0) {
            uy = 0;
            uz = dy > 0 ? -1 : 1;
        }
        double xx = uy * zz - uz * zy;
        double xy = uz * zx - ux * zz;
        double xz = ux * zy - uy * zx;
        double xl = Math.sqrt(xx * xx + xy * xy + xz * xz);
        xx /= xl;
        xy /= xl;
        xz /= xl;
        double yx = zy * xz - zz * xy;
        double yy = zz * xx - zx * xz;
        double yz = zx * xy - zy * xx;
        // the quaternion of the matrix with columns x, y, z
        double trace = xx + yy + zz;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1) * 2;
            out[3] = s / 4;
            out[0] = (yz - zy) / s;
            out[1] = (zx - xz) / s;
            out[2] = (xy - yx) / s;
        } else if (xx > yy && xx > zz) {
            double s = Math.sqrt(1 + xx - yy - zz) * 2;
            out[3] = (yz - zy) / s;
            out[0] = s / 4;
            out[1] = (yx + xy) / s;
            out[2] = (zx + xz) / s;
        } else if (yy > zz) {
            double s = Math.sqrt(1 + yy - xx - zz) * 2;
            out[3] = (zx - xz) / s;
            out[0] = (yx + xy) / s;
            out[1] = s / 4;
            out[2] = (zy + yz) / s;
        } else {
            double s = Math.sqrt(1 + zz - xx - yy) * 2;
            out[3] = (xy - yx) / s;
            out[0] = (zx + xz) / s;
            out[1] = (zy + yz) / s;
            out[2] = s / 4;
        }
        GizmoMath.normalize4(out, 0);
    }

    /** Component {@code c} of world direction {@code (x, y, z)} in the camera's space (2: towards the viewer). */
    private double camera(double x, double y, double z, int c) {
        return GizmoMath.rotate(-view.rotationX(), -view.rotationY(), -view.rotationZ(), view.rotationW(), x, y, z, c);
    }

    /** Where cube point {@code (x, y, z)} (the cube from -1 to 1) shows in the element. */
    private void project(double x, double y, double z, float[] out) {
        out[0] = (float) (centerX + camera(x, y, z, 0) * scale);
        out[1] = (float) (centerY - camera(x, y, z, 1) * scale);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public long measure(int slot, float width, int widthMode, float height, int heightMode) {
        float s = 96 * ui.layout().scale();
        return Measure.size(widthMode == Measure.UNDEFINED ? s : width, heightMode == Measure.UNDEFINED ? s : height);
    }

    @Override
    public int spare(int recorded) {
        return recorded + 16;
    }

    @Override
    public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
        centerX = (x0 + x1) / 2;
        centerY = (y0 + y1) / 2;
        // the cube's corners reach sqrt(3) from its centre
        scale = Math.min(x1 - x0, y1 - y0) / 2 / 1.8f;
        ResolvedStyle edge = ui.styles().styleOf(edgePart.slot());
        long edgeColor = Rgba.fade(edge.get(Styles.COLOR).rgba(), opacity);
        float edgeWidth = edge.get(Styles.WIDTH).unit() == Length.Unit.PX ? edge.get(Styles.WIDTH).value()
            * ui.layout().scale() : ui.layout().scale();
        for (int f = 0; f < 6; f++) {
            int a = f / 2;
            int sign = (f & 1) == 0 ? 1 : -1;
            double nx = a == 0 ? sign : 0;
            double ny = a == 1 ? sign : 0;
            double nz = a == 2 ? sign : 0;
            if (camera(nx, ny, nz, 2) <= 0) {
                continue; // facing away
            }
            ResolvedStyle face = ui.styles().styleOf(faceParts[f].slot());
            long fill = Rgba.fade(face.get(Styles.COLOR).rgba(), opacity * face.getFloat(Styles.OPACITY));
            int u = (a + 1) % 3;
            int v = (a + 2) % 3;
            // the face's corners (-1, -1), (1, -1), (1, 1), (-1, 1) in u, v: two triangles
            for (int k = 0; k < 4; k++) {
                corner(a, sign, u, k == 1 || k == 2 ? 1 : -1, v, k >= 2 ? 1 : -1, pa);
                quad[2 * k] = pa[0];
                quad[2 * k + 1] = pa[1];
            }
            Gizmo.quad(list, quad, fill);
            for (int e = 0; e < 4; e++) {
                double su = e < 2 ? -1 : 1;
                double sv = e % 2 == 0 ? -1 : 1;
                if (e < 2) {
                    corner(a, sign, u, su, v, -1, pa);
                    corner(a, sign, u, su, v, 1, pb);
                } else {
                    corner(a, sign, u, -1, v, sv, pa);
                    corner(a, sign, u, 1, v, sv, pb);
                }
                list.line(pa[0], pa[1], pb[0], pb[1], edgeWidth, edgeColor);
            }
        }
        if (hot >= 0) {
            ResolvedStyle h = ui.styles().styleOf(hotPart.slot());
            float size = h.get(Styles.WIDTH).unit() == Length.Unit.PX ? h.get(Styles.WIDTH).value() * ui.layout().scale()
                : 12 * ui.layout().scale();
            project(hot % 3 - 1, hot / 3 % 3 - 1, hot / 9 - 1, pa);
            float r = size / 2;
            list.box(pa[0] - r, pa[1] - r, pa[0] + r, pa[1] + r, Rgba.fade(h.get(Styles.COLOR).rgba(), opacity), r,
                r, r, r, 0, 0);
        }
    }

    /** The point of face {@code a} (side {@code sign}) at {@code su} along axis {@code u} and {@code sv} along {@code v}. */
    private void corner(int a, int sign, int u, double su, int v, double sv, float[] out) {
        double x = a == 0 ? sign : u == 0 ? su : sv;
        double y = a == 1 ? sign : u == 1 ? su : sv;
        double z = a == 2 ? sign : u == 2 ? su : sv;
        project(x, y, z, out);
    }

    /** Takes the cube off its UI's frame; the element stays for the caller to destroy. */
    public void dispose() {
        ui.removeFrameListener(this);
    }
}
