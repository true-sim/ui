package com.truesim.ui.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.UiInput;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A UI in the 3D world (design 6.4, UI12): a {@link Ui} of its own (a world-space one, sharp text at any angle) on a
 * rectangle in the scene, for in-game screens, VR menus and labels. Pointers reach it by their ray: the ray against
 * the panel's plane, then the ordinary 2D hit test of the panel's UI at the local point, merged by distance with the
 * scene's hits ({@link ScenePicking#addPanel}). How the panel is drawn, rendered to a texture or drawn directly in the
 * 3D view, is the engine's choice; it reads {@link #ui()}'s draw lists and {@link #transform}.
 *
 * <p>The panel lies in the plane of its local X and Y axes, centred on its origin, facing its local +Z: its UI's
 * top-left pixel at local ({@code -width / 2}, {@code +height / 2}) in metres. Place it in world space ({@link #place})
 * or let it follow a scene node ({@link #follow}).
 *
 * <pre>{@code
 * WorldPanel screen = new WorldPanel(new Ui(theme, text), 800, 600).pixelsPerMeter(800).follow(scene, monitor);
 * picking.addPanel(screen);
 * }</pre>
 */
public final class WorldPanel {

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;

    private final Ui ui;
    private final UiInput input = new UiInput(4);
    private float width;
    private float height;
    private float pixelsPerMeter = 1000;
    private boolean passThrough = true;
    private SceneGraph scene;
    private long node = NodeHandle.NONE;
    // the world transform: position, rotation, uniform scale
    private double tx;
    private double ty;
    private double tz;
    private double qx;
    private double qy;
    private double qz;
    private double qw = 1;
    private double scale = 1;
    Element proxy;

    /** A panel showing {@code panelUi}, {@code widthPx x heightPx} of its UI pixels; marks the UI world-space. */
    @AllowAllocation("panels are made when a scene is built")
    public WorldPanel(Ui panelUi, float widthPx, float heightPx) {
        this.ui = panelUi;
        size(widthPx, heightPx);
        panelUi.worldSpace(true);
    }

    /** The panel's UI. */
    public Ui ui() {
        return ui;
    }

    /** The input the panel's UI receives: the pointers whose rays reach it, at their local points. */
    public UiInput input() {
        return input;
    }

    /** Sets the panel's size in its UI pixels; returns this. */
    public WorldPanel size(float widthPx, float heightPx) {
        if (!(widthPx > 0) || !(heightPx > 0)) {
            throw new IllegalArgumentException("an empty panel: " + widthPx + " x " + heightPx);
        }
        width = widthPx;
        height = heightPx;
        return this;
    }

    /** How many of its UI pixels make one metre (its resolution in the world); returns this. */
    public WorldPanel pixelsPerMeter(float value) {
        if (!(value > 0)) {
            throw new IllegalArgumentException("pixels per metre: " + value);
        }
        pixelsPerMeter = value;
        return this;
    }

    /**
     * Whether rays pass through the panel where its UI has nothing to hit (the default: a HUD-like panel over the
     * world); false makes the whole rectangle stop rays, as an opaque screen does. Returns this.
     */
    public WorldPanel passThrough(boolean value) {
        passThrough = value;
        return this;
    }

    /** Whether rays pass through where nothing of its UI is hit. */
    public boolean passThrough() {
        return passThrough;
    }

    /** Places the panel's centre at {@code x, y, z} turned by {@code qx, qy, qz, qw}, scaled by {@code s}; returns this. */
    public WorldPanel place(double x, double y, double z, double rx, double ry, double rz, double rw, double s) {
        double length = Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
        if (!(length > 0) || !(s > 0)) {
            throw new IllegalArgumentException("not a placement");
        }
        scene = null;
        node = NodeHandle.NONE;
        tx = x;
        ty = y;
        tz = z;
        qx = rx / length;
        qy = ry / length;
        qz = rz / length;
        qw = rw / length;
        scale = s;
        return this;
    }

    /**
     * Makes the panel follow {@code panelNode}'s world transform in {@code graph} (its first scale component scales
     * the panel), read each time a ray is tested; returns this.
     */
    public WorldPanel follow(SceneGraph graph, long panelNode) {
        scene = graph;
        node = panelNode;
        return this;
    }

    /** The node the panel follows, or {@link NodeHandle#NONE}. */
    public long node() {
        return node;
    }

    /** The panel's width in its UI pixels. */
    public float width() {
        return width;
    }

    /** The panel's height in its UI pixels. */
    public float height() {
        return height;
    }

    /** Its UI pixels per metre. */
    public float pixelsPerMeter() {
        return pixelsPerMeter;
    }

    /** The element standing for the panel in the main UI's picking, once added to a {@link ScenePicking}. */
    public Element element() {
        return proxy;
    }

    /**
     * The panel's world transform for drawing it: position, rotation (x, y, z, w) and the scale of one UI pixel in
     * metres, into {@code out[0..7]}; false if it follows a node that is gone.
     */
    public boolean transform(double[] out) {
        if (!sync()) {
            return false;
        }
        out[0] = tx;
        out[1] = ty;
        out[2] = tz;
        out[3] = qx;
        out[4] = qy;
        out[5] = qz;
        out[6] = qw;
        out[7] = scale / pixelsPerMeter;
        return true;
    }

    /** Reads the followed node's transform; false if it is gone. */
    private boolean sync() {
        if (scene == null) {
            return true;
        }
        if (!scene.contains(node)) {
            return false;
        }
        MemorySegment s = scene.worldTransforms().segment();
        long o = NodeHandle.index(node) * scene.worldTransforms().stride();
        tx = s.get(DOUBLE, o);
        ty = s.get(DOUBLE, o + 8);
        tz = s.get(DOUBLE, o + 16);
        qx = s.get(DOUBLE, o + 24);
        qy = s.get(DOUBLE, o + 32);
        qz = s.get(DOUBLE, o + 40);
        qw = s.get(DOUBLE, o + 48);
        scale = s.get(DOUBLE, o + 56);
        return true;
    }

    /**
     * Intersects the world ray with the panel's plane: the distance along the ray (in units of its direction), with
     * the local point in the panel's UI pixels into {@code local[0..1]} (outside the panel when off its rectangle);
     * {@link Double#POSITIVE_INFINITY} when the ray is parallel to the plane, points away from it or the panel's node
     * is gone. {@code bounded}: only hits on the panel's rectangle count.
     */
    public double intersect(double ox, double oy, double oz, double dx, double dy, double dz, boolean bounded,
        float[] local) {
        if (!sync()) {
            return Double.POSITIVE_INFINITY;
        }
        // into the panel's space: undo the translation, the rotation and the scale
        double lox = inverseX(ox - tx, oy - ty, oz - tz) / scale;
        double loy = inverseY(ox - tx, oy - ty, oz - tz) / scale;
        double loz = inverseZ(ox - tx, oy - ty, oz - tz) / scale;
        double ldx = inverseX(dx, dy, dz) / scale;
        double ldy = inverseY(dx, dy, dz) / scale;
        double ldz = inverseZ(dx, dy, dz) / scale;
        if (ldz == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double t = -loz / ldz;
        if (t < 0) {
            return Double.POSITIVE_INFINITY;
        }
        double hx = lox + ldx * t;
        double hy = loy + ldy * t;
        float u = (float) ((hx * pixelsPerMeter) + width / 2);
        float v = (float) (height / 2 - (hy * pixelsPerMeter));
        local[0] = u;
        local[1] = v;
        if (bounded && (u < 0 || v < 0 || u >= width || v >= height)) {
            return Double.POSITIVE_INFINITY;
        }
        return t;
    }

    /** Whether the panel's UI has something to hit at local point {@code u, v}, or stops rays anyway. */
    boolean takes(float u, float v) {
        return !passThrough || ui.interaction().picking().pickTop(u, v) != UiStore.NONE;
    }

    private double inverseX(double x, double y, double z) {
        double ux = 2 * (-qy * z + qz * y);
        double uy = 2 * (-qz * x + qx * z);
        double uz = 2 * (-qx * y + qy * x);
        return x + qw * ux + (-qy * uz + qz * uy);
    }

    private double inverseY(double x, double y, double z) {
        double ux = 2 * (-qy * z + qz * y);
        double uy = 2 * (-qz * x + qx * z);
        double uz = 2 * (-qx * y + qy * x);
        return y + qw * uy + (-qz * ux + qx * uz);
    }

    private double inverseZ(double x, double y, double z) {
        double ux = 2 * (-qy * z + qz * y);
        double uy = 2 * (-qz * x + qx * z);
        double uz = 2 * (-qx * y + qy * x);
        return z + qw * uz + (-qx * uy + qy * ux);
    }
}
