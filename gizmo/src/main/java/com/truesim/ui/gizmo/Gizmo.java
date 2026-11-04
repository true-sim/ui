package com.truesim.ui.gizmo;

import com.truesim.common.math.Maths;
import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.geometry.Aabb;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.ui.controls.Painter;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.scene.HandlePicker;
import com.truesim.ui.scene.ScenePicking;
import com.truesim.ui.scene.SceneView;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;

/**
 * A 3D transform gizmo (design 8, stage 12): translate, rotate, scale, bounds and universal handles over a selection
 * of scene nodes, in local, world, parent or view space, about the median, each node's own origin, the active node, a
 * cursor or the selection's bounds centre. Dragging solves in doubles ({@link GizmoSolver}) and writes the nodes'
 * transforms through the scene's API; a held {@code ui.gizmo.snap} snaps to increments; typing a number while dragging
 * sets the value (Blender); Escape, a right click or {@code GIZMO_CANCEL} cancel and restore the start; the
 * {@link GizmoListener} hooks give an editor its undo.
 *
 * <p><b>Picking.</b> The pointer's ray against the handles' analytic shapes with a tolerance of
 * {@code ui.gizmo.pickTolerance} pixels, before the scene's objects ({@link HandlePicker} of a {@link ScenePicking}).
 * XR controllers pick with their rays, with an angular tolerance; {@link GizmoGrab} adds direct grabs and two-handed
 * scaling and turning.
 *
 * <p><b>Drawing.</b> Projected 2D primitives on the {@code overlay} layer, a screen-constant size (the theme's
 * {@code gizmo-size} height in pixels), on top of the scene with the parts the scene hides faded (UI26), the theme's
 * {@code gizmo-occluded} opacity how faded. What is hidden is decided per pixel by the renderer in the same frame when
 * the gizmo is {@linkplain #depthTest depth tested} (its handles recorded with their view depth,
 * {@link com.truesim.ui.core.draw.Primitive#FLAG_DEPTH}, compared with the scene's depth of the frame drawn: exact, no
 * delay; the engine's renderer does it for its main view), or per part on the CPU by a {@link GizmoOcclusion}
 * (by the scene's boxes, {@link RayOcclusion}: for views whose depth the renderer does not have). Every colour,
 * width, size and the looks of hovered and grabbed handles come from style parts of the {@code gizmo} element:
 * {@code gizmo-axis#x #y #z}, {@code gizmo-plane#xy #yz #zx}, {@code gizmo-ring#x #y #z #view},
 * {@code gizmo-trackball}, {@code gizmo-center}, {@code gizmo-tip}, {@code gizmo-bounds}, {@code gizmo-bounds-handle}
 * (and {@code .active}), {@code gizmo-occluded}, {@code gizmo-size}; a hovered handle's part has the {@code hover}
 * state, a dragged one {@code pressed}.
 *
 * <pre>{@code
 * Gizmo gizmo = new Gizmo(picking, picking.mainView(), null).listener(undoHooks);
 * gizmo.select(new long[] {crate, barrel}, 2, barrel);
 * gizmo.mode(GizmoMode.ROTATE).space(GizmoSpace.LOCAL).pivot(GizmoPivot.INDIVIDUAL);
 * }</pre>
 *
 * <p>Steady-state frames, hovering and dragging allocate nothing.
 */
public final class Gizmo implements Painter, Ui.FrameListener, HandlePicker {

    /** How far from a handle (in pixels) the pointer still grabs it. */
    public static final Tunable PICK_TOLERANCE = Tunable.of("ui.gizmo.pickTolerance", 6, 0, 32,
        "Pixels from a gizmo handle the pointer still grabs it.");
    /** The translation increment when snapping. */
    public static final Tunable SNAP_TRANSLATE = Tunable.of("ui.gizmo.snapTranslate", 0.5f, 0.001f, 1000,
        "World units a gizmo moves by when snapping.");
    /** The rotation increment when snapping, in degrees. */
    public static final Tunable SNAP_ROTATE = Tunable.of("ui.gizmo.snapRotate", 15, 0.1f, 180,
        "Degrees a gizmo turns by when snapping.");
    /** The scale increment when snapping. */
    public static final Tunable SNAP_SCALE = Tunable.of("ui.gizmo.snapScale", 0.1f, 0.001f, 10,
        "The scale factor step of a gizmo when snapping.");
    /** Whether gizmos snap unless the snap modifier is held (1) or only while it is held (0). */
    public static final Tunable SNAP_BY_DEFAULT = Tunable.of("ui.gizmo.snapByDefault", 0, 0, 1,
        "1: gizmos snap unless ui.gizmo.snap is held; 0: only while it is held.");
    /** An XR gizmo's angular size, in degrees, seen from the head. */
    public static final Tunable XR_ANGULAR_SIZE = Tunable.of("ui.gizmo.xrAngularSize", 9, 1, 60,
        "Degrees an XR gizmo spans from the head (its size by angular size).");
    /** How far from a handle (in degrees seen from the controller) an XR ray still grabs it. */
    public static final Tunable XR_PICK_ANGLE = Tunable.of("ui.gizmo.xrPickAngle", 1.5f, 0, 10,
        "Degrees from a gizmo handle (seen from the controller) an XR ray still grabs it.");

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    private static final int LISTENED = EventType.PRESS.bit() | EventType.RELEASE.bit() | EventType.CONTEXT.bit();

    private final Ui ui;
    private final ScenePicking picking;
    private final SceneGraph scene;
    private SceneView view;
    private final Element element;
    private final Element[] axisParts = new Element[3];
    private final Element[] planeParts = new Element[3];
    private final Element[] ringParts = new Element[4];
    private final Element trackballPart;
    private final Element centerPart;
    private final Element tipPart;
    private final Element boundsPart;
    private final Element boundsHandlePart;
    private final Element boundsActivePart;
    private final Element occludedPart;
    private final Element sizePart;

    // configuration
    private GizmoMode mode = GizmoMode.TRANSLATE;
    private GizmoSpace space = GizmoSpace.WORLD;
    private GizmoPivot pivot = GizmoPivot.MEDIAN;
    private final double[] cursor = new double[3];
    private GizmoListener listener;
    private GizmoOcclusion occlusion;
    private boolean depthTest;
    private boolean visible = true;
    private final double[] head = new double[3];
    private boolean hasHead;

    // the selection
    private long[] selected = new long[4];
    private int count;
    private long active = NodeHandle.NONE;
    private double[] bounds = new double[4 * 6]; // local bounds per node: min x, y, z, max x, y, z (NaN: none)
    private final long[] drivers = new long[64]; // the selected nodes no selected ancestor moves already
    private int driverCount;

    // this frame's shapes, drag state
    private final GizmoShapes shapes = new GizmoShapes();
    private final GizmoSolver solver = new GizmoSolver();
    private final GizmoDelta delta = new GizmoDelta();
    private final GizmoTransforms initial = new GizmoTransforms();
    private final GizmoTransforms finals = new GizmoTransforms();
    private boolean dragging;
    private int dragPointer = -1;
    private GizmoHandle dragHandle = GizmoHandle.NONE;
    private int dragPart;
    private final StringBuilder typed = new StringBuilder();
    private final int[] pointerIds = new int[UiInput.MAX_POINTERS];
    private final GizmoHandle[] pointerHandles = new GizmoHandle[UiInput.MAX_POINTERS];
    private final int[] pointerParts = new int[UiInput.MAX_POINTERS];
    private GizmoHandle hovered = GizmoHandle.NONE;
    private int hoveredPart;
    private final double[] ray = new double[6];
    private final double[] p = new double[3];
    private final double[] q = new double[3];
    private final double[] n = new double[3];
    private final float[] sa = new float[3];
    private final float[] quad = new float[8]; // a projected square handle's corners
    private final float[] quadDepth = new float[4]; // their view depths
    private final float[] sb = new float[3];
    private long signature;
    private final UiListener events = this::handle;

    /**
     * A gizmo over {@code scenePicking}'s scene, drawn through {@code sceneView} (the main 3D view), with style name
     * {@code style} (or null): its element is on the {@code overlay} layer over the view's viewport, and its handles
     * are picked before the scene's objects.
     */
    @AllowAllocation("controls are made when a UI is built")
    public Gizmo(ScenePicking scenePicking, SceneView sceneView, String style) {
        this.picking = scenePicking;
        this.ui = scenePicking.ui();
        this.scene = scenePicking.scene();
        this.view = sceneView;
        element = ui.element("gizmo", style);
        ui.layer(Layers.OVERLAY).root().add(element);
        ui.codeLayout(element, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
        ui.paint(element, this);
        // hit only through its handles' shapes, never as a rectangle
        ui.interaction().pickable(element, PointerEvents.NONE);
        ui.interaction().on(element, LISTENED, events);
        String[] xyz = {"x", "y", "z"};
        String[] planes = {"yz", "zx", "xy"}; // by normal axis
        for (int a = 0; a < 3; a++) {
            axisParts[a] = ui.part(element, "gizmo-axis", null).id(xyz[a]);
            planeParts[a] = ui.part(element, "gizmo-plane", null).id(planes[a]);
            ringParts[a] = ui.part(element, "gizmo-ring", null).id(xyz[a]);
        }
        ringParts[3] = ui.part(element, "gizmo-ring", null).id("view");
        trackballPart = ui.part(element, "gizmo-trackball", null);
        centerPart = ui.part(element, "gizmo-center", null);
        tipPart = ui.part(element, "gizmo-tip", null);
        boundsPart = ui.part(element, "gizmo-bounds", null);
        boundsHandlePart = ui.part(element, "gizmo-bounds-handle", null);
        boundsActivePart = ui.part(element, "gizmo-bounds-handle", "active");
        occludedPart = ui.part(element, "gizmo-occluded", null);
        sizePart = ui.part(element, "gizmo-size", null);
        Arrays.fill(pointerIds, -1);
        Arrays.fill(pointerHandles, GizmoHandle.NONE);
        scenePicking.addHandles(this);
        ui.addFrameListener(this);
    }

    /** The tunables gizmos read, for an engine to register as settings. */
    @AllowAllocation("asked once")
    public static List<Tunable> tunables() {
        return List.of(PICK_TOLERANCE, SNAP_TRANSLATE, SNAP_ROTATE, SNAP_SCALE, SNAP_BY_DEFAULT, XR_ANGULAR_SIZE,
            XR_PICK_ANGLE);
    }

    // ================================================================ configuration

    /** The gizmo's element (type {@code gizmo}): its parts carry the looks. */
    public Element element() {
        return element;
    }

    /** Sets what it does; returns this. Ignored while dragging. */
    public Gizmo mode(GizmoMode value) {
        if (!dragging && value != mode) {
            mode = value;
            ui.repaint(element);
        }
        return this;
    }

    public GizmoMode mode() {
        return mode;
    }

    /** Sets the space its axes follow; returns this. Ignored while dragging. */
    public Gizmo space(GizmoSpace value) {
        if (!dragging) {
            space = value;
        }
        return this;
    }

    public GizmoSpace space() {
        return space;
    }

    /** Sets the pivot; returns this. Ignored while dragging. */
    public Gizmo pivot(GizmoPivot value) {
        if (!dragging) {
            pivot = value;
        }
        return this;
    }

    public GizmoPivot pivot() {
        return pivot;
    }

    /** Places the cursor (the {@link GizmoPivot#CURSOR} pivot); returns this. */
    public Gizmo cursor(double x, double y, double z) {
        cursor[0] = x;
        cursor[1] = y;
        cursor[2] = z;
        return this;
    }

    /** Sets the undo hooks (null: none); returns this. */
    public Gizmo listener(GizmoListener value) {
        listener = value;
        return this;
    }

    /** The undo hooks, or null. */
    public GizmoListener listener() {
        return listener;
    }

    /** Sets what says which parts the scene hides (null: none faded); returns this. */
    public Gizmo occlusion(GizmoOcclusion value) {
        occlusion = value;
        ui.repaint(element);
        return this;
    }

    /** What says which parts the scene hides, or null. */
    public GizmoOcclusion occlusion() {
        return occlusion;
    }

    /**
     * Whether its handles are depth tested by the renderer (default false): recorded with their view depth
     * ({@link com.truesim.ui.core.draw.Primitive#FLAG_DEPTH}), faded per pixel where the scene drawn under the UI in the
     * same frame is nearer; its {@link #occlusion} is then not asked. For a gizmo of the view whose depth the renderer
     * draws the UI over (the engine's main view). Returns this.
     */
    public Gizmo depthTest(boolean value) {
        depthTest = value;
        ui.repaint(element);
        return this;
    }

    /** Whether its handles are depth tested by the renderer. */
    public boolean depthTest() {
        return depthTest;
    }

    /** Sets the view it draws through and sizes itself for; returns this. */
    public Gizmo view(SceneView value) {
        view = value;
        return this;
    }

    /** Shows or hides it (hidden: not drawn, not picked); returns this. */
    public Gizmo visible(boolean value) {
        visible = value;
        ui.repaint(element);
        return this;
    }

    /** Where the XR head is: an XR gizmo is sized by its angular size from here. Returns this. */
    public Gizmo head(double x, double y, double z) {
        head[0] = x;
        head[1] = y;
        head[2] = z;
        hasHead = true;
        return this;
    }

    // ================================================================ the selection

    /**
     * Selects the first {@code n} of {@code nodes} with {@code activeNode} (one of them, or NONE for the last) as the
     * active one. A drag in progress is cancelled.
     */
    @AllowAllocation("a selection changes on a click, not per frame")
    public void select(long[] nodes, int n, long activeNode) {
        if (dragging) {
            cancel();
        }
        if (selected.length < n) {
            selected = Arrays.copyOf(selected, n);
            bounds = Arrays.copyOf(bounds, n * 6);
        }
        count = 0;
        for (int i = 0; i < n; i++) {
            if (scene.contains(nodes[i])) {
                selected[count] = nodes[i];
                Aabb b = scene.hasBounds(nodes[i]) ? scene.localBounds(nodes[i]) : null;
                int o = count * 6;
                bounds[o] = b == null ? Double.NaN : b.min().x();
                bounds[o + 1] = b == null ? Double.NaN : b.min().y();
                bounds[o + 2] = b == null ? Double.NaN : b.min().z();
                bounds[o + 3] = b == null ? Double.NaN : b.max().x();
                bounds[o + 4] = b == null ? Double.NaN : b.max().y();
                bounds[o + 5] = b == null ? Double.NaN : b.max().z();
                count++;
            }
        }
        active = activeNode != NodeHandle.NONE && contains(activeNode) ? activeNode
            : count > 0 ? selected[count - 1] : NodeHandle.NONE;
        ui.repaint(element);
    }

    /** Selects one node. */
    @AllowAllocation("a selection changes on a click, not per frame")
    public void select(long node) {
        select(new long[] {node}, 1, node);
    }

    /** Clears the selection. */
    public void clear() {
        if (dragging) {
            cancel();
        }
        count = 0;
        active = NodeHandle.NONE;
        ui.repaint(element);
    }

    /** How many nodes are selected. */
    public int count() {
        return count;
    }

    /** Selected node {@code i}. */
    public long selected(int i) {
        return selected[i];
    }

    /** The active node, or NONE. */
    public long active() {
        return active;
    }

    /** Whether {@code node} is selected. */
    public boolean contains(long node) {
        for (int i = 0; i < count; i++) {
            if (selected[i] == node) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ state

    /** Whether a drag is in progress. */
    public boolean dragging() {
        return dragging;
    }

    /** The handle being dragged, or else the one under a pointer, or NONE. */
    public GizmoHandle hovered() {
        return dragging ? dragHandle : hovered;
    }

    /** The bounds corner or face of {@link #hovered()}. */
    public int handlePart() {
        return dragging ? dragPart : hoveredPart;
    }

    /** What the current (or last) drag did. */
    public GizmoDelta delta() {
        return delta;
    }

    /** The text typed while dragging (numeric entry). */
    public CharSequence typed() {
        return typed;
    }

    /** The gizmo's origin this frame (the pivot), component {@code c}. */
    public double origin(int c) {
        return shapes.origin[c];
    }

    /** The gizmo's size this frame in world units (its axes' length). */
    public double size() {
        return shapes.size;
    }

    // ================================================================ the frame: its shapes

    /**
     * Lays the handles out for this frame: origin from the pivot, axes from the space, the selection's box in those
     * axes, the size from the theme's pixels through {@code through} (or by angular size from the head for XR).
     * False without a selection.
     */
    private boolean layout(SceneView through, double rayOx, double rayOy, double rayOz) {
        if (count == 0 || !scene.contains(active)) {
            return false;
        }
        drivers();
        MemorySegment s = scene.worldTransforms().segment();
        long stride = scene.worldTransforms().stride();
        // the origin
        double ox = 0;
        double oy = 0;
        double oz = 0;
        switch (pivot) {
            case ACTIVE -> {
                long o = NodeHandle.index(active) * stride;
                ox = s.get(DOUBLE, o);
                oy = s.get(DOUBLE, o + 8);
                oz = s.get(DOUBLE, o + 16);
            }
            case CURSOR -> {
                ox = cursor[0];
                oy = cursor[1];
                oz = cursor[2];
            }
            case BOUNDS_CENTER -> {
                double minX = Double.POSITIVE_INFINITY;
                double minY = Double.POSITIVE_INFINITY;
                double minZ = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY;
                double maxY = Double.NEGATIVE_INFINITY;
                double maxZ = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < count; i++) {
                    int slot = NodeHandle.index(selected[i]);
                    if (scene.hasBounds(selected[i])) {
                        minX = Math.min(minX, scene.worldBounds().get(slot, 0));
                        minY = Math.min(minY, scene.worldBounds().get(slot, 1));
                        minZ = Math.min(minZ, scene.worldBounds().get(slot, 2));
                        maxX = Math.max(maxX, scene.worldBounds().get(slot, 3));
                        maxY = Math.max(maxY, scene.worldBounds().get(slot, 4));
                        maxZ = Math.max(maxZ, scene.worldBounds().get(slot, 5));
                    } else {
                        long o = slot * stride;
                        minX = Math.min(minX, s.get(DOUBLE, o));
                        minY = Math.min(minY, s.get(DOUBLE, o + 8));
                        minZ = Math.min(minZ, s.get(DOUBLE, o + 16));
                        maxX = Math.max(maxX, s.get(DOUBLE, o));
                        maxY = Math.max(maxY, s.get(DOUBLE, o + 8));
                        maxZ = Math.max(maxZ, s.get(DOUBLE, o + 16));
                    }
                }
                ox = (minX + maxX) / 2;
                oy = (minY + maxY) / 2;
                oz = (minZ + maxZ) / 2;
            }
            default -> {
                // the median, relative to the first node: exact far from the origin
                long o0 = NodeHandle.index(selected[0]) * stride;
                double bx = s.get(DOUBLE, o0);
                double by = s.get(DOUBLE, o0 + 8);
                double bz = s.get(DOUBLE, o0 + 16);
                double sx = 0;
                double sy = 0;
                double sz = 0;
                for (int i = 0; i < count; i++) {
                    long o = NodeHandle.index(selected[i]) * stride;
                    sx += s.get(DOUBLE, o) - bx;
                    sy += s.get(DOUBLE, o + 8) - by;
                    sz += s.get(DOUBLE, o + 16) - bz;
                }
                ox = bx + sx / count;
                oy = by + sy / count;
                oz = bz + sz / count;
            }
        }
        shapes.origin[0] = ox;
        shapes.origin[1] = oy;
        shapes.origin[2] = oz;
        shapes.mode = mode;
        // the axes
        switch (space) {
            case LOCAL -> {
                long o = NodeHandle.index(active) * stride;
                shapes.axes(s.get(DOUBLE, o + 24), s.get(DOUBLE, o + 32), s.get(DOUBLE, o + 40), s.get(DOUBLE, o + 48));
            }
            case PARENT -> {
                long parent = scene.parent(active);
                if (parent == NodeHandle.NONE) {
                    shapes.axes(0, 0, 0, 1);
                } else {
                    long o = NodeHandle.index(parent) * stride;
                    shapes.axes(s.get(DOUBLE, o + 24), s.get(DOUBLE, o + 32), s.get(DOUBLE, o + 40),
                        s.get(DOUBLE, o + 48));
                }
            }
            case VIEW -> {
                if (view != null) {
                    shapes.axes(view.rotationX(), view.rotationY(), view.rotationZ(), view.rotationW());
                } else {
                    shapes.axes(0, 0, 0, 1);
                }
            }
            default -> shapes.axes(0, 0, 0, 1);
        }
        // the view's directions
        if (through != null) {
            for (int c = 0; c < 3; c++) {
                shapes.forward[c] = through.forward(c);
                shapes.right[c] = through.right(c);
                shapes.up[c] = through.up(c);
            }
            double upp = through.unitsPerPixel(through.depth(ox, oy, oz));
            shapes.size = sizePx() * upp;
            shapes.boundsHandle = boundsHandlePx() / 2 * upp;
        } else {
            double hx = hasHead ? head[0] : rayOx;
            double hy = hasHead ? head[1] : rayOy;
            double hz = hasHead ? head[2] : rayOz;
            double fx = ox - hx;
            double fy = oy - hy;
            double fz = oz - hz;
            double distance = Math.max(GizmoMath.length(fx, fy, fz), 1e-6);
            shapes.forward[0] = fx / distance;
            shapes.forward[1] = fy / distance;
            shapes.forward[2] = fz / distance;
            // right and up across the direction, up as near the world's +Y as can be
            double rx = -shapes.forward[2];
            double rz = shapes.forward[0];
            double rl = Maths.hypot(rx, rz);
            if (rl < 1e-9) {
                rx = 1;
                rz = 0;
                rl = 1;
            }
            shapes.right[0] = rx / rl;
            shapes.right[1] = 0;
            shapes.right[2] = rz / rl;
            shapes.up[0] = shapes.right[1] * shapes.forward[2] - shapes.right[2] * shapes.forward[1];
            shapes.up[1] = shapes.right[2] * shapes.forward[0] - shapes.right[0] * shapes.forward[2];
            shapes.up[2] = shapes.right[0] * shapes.forward[1] - shapes.right[1] * shapes.forward[0];
            double angular = Maths.tan(Maths.toRadians(XR_ANGULAR_SIZE.get()) / 2) * 2;
            shapes.size = distance * angular;
            shapes.boundsHandle = shapes.size * 0.06;
        }
        box();
        return true;
    }

    /** The selection's box in the gizmo's axes, from each node's local bounds corners (or origin). */
    private void box() {
        double[] min = shapes.boxMin;
        double[] max = shapes.boxMax;
        for (int c = 0; c < 3; c++) {
            min[c] = Double.POSITIVE_INFINITY;
            max[c] = Double.NEGATIVE_INFINITY;
        }
        MemorySegment s = scene.worldTransforms().segment();
        long stride = scene.worldTransforms().stride();
        double ax = shapes.axes[0];
        double ay = shapes.axes[1];
        double az = shapes.axes[2];
        double aw = shapes.axes[3];
        for (int i = 0; i < count; i++) {
            long o = NodeHandle.index(selected[i]) * stride;
            int b = i * 6;
            boolean none = Double.isNaN(bounds[b]);
            for (int k = 0; k < (none ? 1 : 8); k++) {
                double lx = none ? 0 : (k & 1) != 0 ? bounds[b + 3] : bounds[b];
                double ly = none ? 0 : (k & 2) != 0 ? bounds[b + 4] : bounds[b + 1];
                double lz = none ? 0 : (k & 4) != 0 ? bounds[b + 5] : bounds[b + 2];
                lx *= s.get(DOUBLE, o + 56);
                ly *= s.get(DOUBLE, o + 64);
                lz *= s.get(DOUBLE, o + 72);
                double qx = s.get(DOUBLE, o + 24);
                double qy = s.get(DOUBLE, o + 32);
                double qz = s.get(DOUBLE, o + 40);
                double qw = s.get(DOUBLE, o + 48);
                double wx = GizmoMath.rotate(qx, qy, qz, qw, lx, ly, lz, 0) + s.get(DOUBLE, o) - shapes.origin[0];
                double wy = GizmoMath.rotate(qx, qy, qz, qw, lx, ly, lz, 1) + s.get(DOUBLE, o + 8) - shapes.origin[1];
                double wz = GizmoMath.rotate(qx, qy, qz, qw, lx, ly, lz, 2) + s.get(DOUBLE, o + 16)
                    - shapes.origin[2];
                for (int c = 0; c < 3; c++) {
                    double v = GizmoMath.rotate(-ax, -ay, -az, aw, wx, wy, wz, c);
                    min[c] = Math.min(min[c], v);
                    max[c] = Math.max(max[c], v);
                }
            }
        }
    }

    /** The selected nodes no other selected node is an ancestor of: those a drag moves (their children follow). */
    private void drivers() {
        driverCount = 0;
        for (int i = 0; i < count && driverCount < drivers.length; i++) {
            boolean under = false;
            for (long a = scene.parent(selected[i]); a != NodeHandle.NONE && !under; a = scene.parent(a)) {
                under = contains(a);
            }
            if (!under) {
                drivers[driverCount++] = selected[i];
            }
        }
    }

    private float sizePx() {
        return length(style(sizePart).get(Styles.HEIGHT), 90);
    }

    private float boundsHandlePx() {
        return length(style(boundsHandlePart).get(Styles.WIDTH), 9);
    }

    private ResolvedStyle style(Element part) {
        return ui.styles().styleOf(part.slot());
    }

    private float length(Length l, float fallback) {
        return l.unit() == Length.Unit.PX ? l.value() * ui.layout().scale() : fallback * ui.layout().scale();
    }

    // ================================================================ picking

    @Override
    public int pick(Pointer pointer, SceneView through, double[] pointerRay) {
        int slot = pointerSlot(pointer.id());
        if (dragging) {
            return pointer.id() == dragPointer ? element.slot() : UiStore.NONE;
        }
        if (!visible || !layout(through, pointerRay[0], pointerRay[1], pointerRay[2])) {
            pointerHandles[slot] = GizmoHandle.NONE;
            return UiStore.NONE;
        }
        double tolerance;
        if (through != null) {
            tolerance = PICK_TOLERANCE.get() * through.unitsPerPixel(through.depth(shapes.origin[0], shapes.origin[1],
                shapes.origin[2]));
        } else {
            double distance = GizmoMath.length(shapes.origin[0] - pointerRay[0], shapes.origin[1] - pointerRay[1],
                shapes.origin[2] - pointerRay[2]);
            tolerance = distance * Maths.tan(Maths.toRadians(XR_PICK_ANGLE.get()));
        }
        boolean hit = shapes.pick(pointerRay[0], pointerRay[1], pointerRay[2], pointerRay[3], pointerRay[4],
            pointerRay[5], tolerance);
        pointerHandles[slot] = hit ? shapes.handle : GizmoHandle.NONE;
        pointerParts[slot] = shapes.part;
        return hit ? element.slot() : UiStore.NONE;
    }

    private int pointerSlot(int id) {
        int free = -1;
        for (int i = 0; i < pointerIds.length; i++) {
            if (pointerIds[i] == id) {
                return i;
            }
            if (free < 0 && pointerIds[i] == -1) {
                free = i;
            }
        }
        if (free < 0) {
            free = 0;
        }
        pointerIds[free] = id;
        return free;
    }

    // ================================================================ events

    private void handle(UiEvent e) {
        int slot = pointerSlot(e.pointerId());
        if (e.type() == EventType.PRESS) {
            if (dragging && e.button() == Pointer.SECONDARY) {
                cancel();
                e.consume();
            } else if (!dragging && e.button() == Pointer.PRIMARY && pointerHandles[slot] != GizmoHandle.NONE) {
                if (begin(e.pointerId(), pointerHandles[slot], pointerParts[slot], e.x(), e.y())) {
                    e.consume();
                }
            }
        } else if (e.type() == EventType.RELEASE) {
            if (dragging && e.pointerId() == dragPointer && e.button() == Pointer.PRIMARY) {
                commit();
                e.consume();
            }
        } else if (e.type() == EventType.CONTEXT && dragging) {
            e.consume(); // the right click cancelled: no context menu
        }
    }

    /** Starts dragging {@code grabbed} with pointer {@code pointerId}; false if its ray cannot grab it. */
    private boolean begin(int pointerId, GizmoHandle grabbed, int part, float x, float y) {
        if (!picking.ray(pointerId, ray)) {
            return false;
        }
        SceneView through = picking.viewOf(pointerId);
        if (!layout(through, ray[0], ray[1], ray[2])) {
            return false;
        }
        float cx = Float.NaN;
        float cy = Float.NaN;
        float px = Float.NaN;
        float py = Float.NaN;
        if (through != null && through.project(shapes.origin[0], shapes.origin[1], shapes.origin[2], sa)) {
            cx = sa[0];
            cy = sa[1];
            px = x;
            py = y;
        }
        double ballPx = shapes.ringRadius() * sizePx();
        solver.frame(shapes.origin[0], shapes.origin[1], shapes.origin[2], shapes.axes[0], shapes.axes[1],
            shapes.axes[2], shapes.axes[3], shapes.forward[0], shapes.forward[1], shapes.forward[2], shapes.right[0],
            shapes.right[1], shapes.right[2], shapes.up[0], shapes.up[1], shapes.up[2], ballPx,
            shapes.ringRadius() * shapes.size);
        solver.box(shapes.boxMin[0], shapes.boxMin[1], shapes.boxMin[2], shapes.boxMax[0], shapes.boxMax[1],
            shapes.boxMax[2]);
        if (!solver.begin(grabbed, part, ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], px, py, cx, cy)) {
            return false;
        }
        initial.capture(scene, drivers, driverCount);
        dragging = true;
        dragPointer = pointerId;
        dragHandle = grabbed;
        dragPart = part;
        typed.setLength(0);
        delta.reset(grabbed);
        states();
        if (listener != null) {
            listener.begin(initial);
        }
        return true;
    }

    /** Ends the drag, keeping what it did. */
    public void commit() {
        if (!dragging) {
            return;
        }
        dragging = false;
        finals.capture(scene, drivers, driverCount);
        typed.setLength(0);
        states();
        if (listener != null) {
            listener.commit(initial, finals);
        }
    }

    /** Ends the drag, restoring the nodes' transforms from its start. */
    public void cancel() {
        if (!dragging) {
            return;
        }
        dragging = false;
        initial.restore(scene);
        typed.setLength(0);
        delta.reset(GizmoHandle.NONE);
        states();
        if (listener != null) {
            listener.cancel(initial);
        }
    }

    // ================================================================ the frame

    @Override
    public void frame(long nanos) {
        UiInput input = ui.input();
        if (input != null && ui.interaction().textOwner() == null) {
            for (int i = 0; i < input.commandCount(); i++) {
                command(input.command(i));
            }
            if (dragging) {
                CharSequence text = input.text();
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c >= '0' && c <= '9' || c == '.' || c == '-' && typed.isEmpty()) {
                        typed.append(c);
                    }
                }
            }
        }
        if (dragging) {
            drag(input);
        }
        hover();
        place();
        long sig = signature();
        if (sig != signature) {
            signature = sig;
            ui.repaint(element);
        }
    }

    private void command(UiCommand command) {
        switch (command) {
            case GIZMO_TRANSLATE -> mode(GizmoMode.TRANSLATE);
            case GIZMO_ROTATE -> mode(GizmoMode.ROTATE);
            case GIZMO_SCALE -> mode(GizmoMode.SCALE);
            case GIZMO_UNIVERSAL -> mode(GizmoMode.UNIVERSAL);
            case GIZMO_TOGGLE_SPACE -> space(space.next());
            case GIZMO_CANCEL, CANCEL -> cancel();
            case ACCEPT -> commit();
            case DELETE_BACK -> {
                if (dragging && !typed.isEmpty()) {
                    typed.setLength(typed.length() - 1);
                }
            }
            default -> {
            }
        }
    }

    private void drag(UiInput input) {
        if (!picking.ray(dragPointer, ray)) {
            return; // the pointer was not picked this frame (gone, or blocked): nothing moves
        }
        float x = Float.NaN;
        float y = Float.NaN;
        int modifiers = 0;
        if (input != null) {
            for (int i = 0; i < input.pointerCount(); i++) {
                Pointer pointer = input.pointerAt(i);
                if (pointer.id() == dragPointer) {
                    modifiers = pointer.modifiers();
                    if (picking.viewOf(dragPointer) != null) {
                        x = pointer.x();
                        y = pointer.y();
                    }
                }
            }
        }
        boolean snapping = SNAP_BY_DEFAULT.get() >= 0.5f != ((modifiers & Pointer.GIZMO_SNAP) != 0);
        boolean changed;
        double value = typedValue();
        if (!Double.isNaN(value)) {
            changed = solver.typed(value, delta);
        } else {
            changed = solver.drag(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], x, y, snapping,
                SNAP_TRANSLATE.get(), Maths.toRadians(SNAP_ROTATE.get()), SNAP_SCALE.get(), delta);
        }
        if (changed) {
            delta.individual = pivot == GizmoPivot.INDIVIDUAL;
            GizmoApply.apply(scene, initial, delta);
            if (listener != null) {
                listener.update(delta);
            }
        }
    }

    /** The typed number, or NaN when nothing (or nothing complete) was typed. */
    private double typedValue() {
        int length = typed.length();
        if (length == 0) {
            return Double.NaN;
        }
        double value = 0;
        double fraction = 0;
        boolean digits = false;
        boolean negative = false;
        for (int i = 0; i < length; i++) {
            char c = typed.charAt(i);
            if (c == '-') {
                negative = true;
            } else if (c == '.') {
                if (fraction > 0) {
                    return Double.NaN;
                }
                fraction = 1;
            } else {
                digits = true;
                if (fraction > 0) {
                    fraction /= 10;
                    value += (c - '0') * fraction;
                } else {
                    value = value * 10 + (c - '0');
                }
            }
        }
        return digits ? negative ? -value : value : Double.NaN;
    }

    /** The handle under a pointer (the first that is over one) for the hover looks. */
    private void hover() {
        GizmoHandle h = GizmoHandle.NONE;
        int part = 0;
        for (int i = 0; i < pointerIds.length && h == GizmoHandle.NONE; i++) {
            if (pointerIds[i] != -1 && pointerHandles[i] != GizmoHandle.NONE
                && picking.worldHitCount(pointerIds[i]) > 0 && picking.worldHit(pointerIds[i], 0) == element) {
                h = pointerHandles[i];
                part = pointerParts[i];
            }
        }
        if (h != hovered || part != hoveredPart) {
            hovered = h;
            hoveredPart = part;
            states();
        }
    }

    /** Sets the hover and pressed states on the handles' parts. */
    private void states() {
        GizmoHandle h = hovered();
        for (int a = 0; a < 3; a++) {
            state(axisParts[a], h == GizmoShapes.translateHandle(a) || h == GizmoShapes.scaleHandle(a));
            state(planeParts[a], h.plane() && h.axis() == a);
            state(ringParts[a], h == GizmoShapes.ringHandle(a));
        }
        state(ringParts[3], h == GizmoHandle.ROTATE_VIEW);
        state(trackballPart, h == GizmoHandle.ROTATE_TRACKBALL);
        state(centerPart, h == GizmoHandle.TRANSLATE_SCREEN || h == GizmoHandle.SCALE_UNIFORM);
        state(boundsActivePart, h == GizmoHandle.BOUNDS_CORNER || h == GizmoHandle.BOUNDS_FACE);
    }

    private void state(Element part, boolean on) {
        part.state(UiState.HOVER, on && !dragging);
        part.state(UiState.PRESSED, on && dragging);
    }

    /** Keeps the element over the view's viewport. */
    private void place() {
        if (view == null) {
            return;
        }
        int slot = element.slot();
        ui.layout().anchors(slot, 0, 0, 0, 0).offsets(slot, view.viewportX(), view.viewportY(), 0, 0)
            .width(slot, view.viewportWidth() / ui.layout().scale(), Unit.PX)
            .height(slot, view.viewportHeight() / ui.layout().scale(), Unit.PX);
    }

    /** What the drawing depends on, hashed: the view, the nodes' versions, the mode, space, pivot, states. */
    private long signature() {
        long h = view == null ? 0 : view.version();
        h = h * 31 + mode.ordinal();
        h = h * 31 + space.ordinal();
        h = h * 31 + pivot.ordinal();
        h = h * 31 + (visible ? 1 : 0);
        h = h * 31 + hovered().ordinal() * 16 + handlePart();
        h = h * 31 + (dragging ? 1 : 0);
        h = h * 31 + count;
        for (int i = 0; i < count; i++) {
            h = h * 31 + (scene.contains(selected[i]) ? scene.version(selected[i]) : -1);
        }
        h = h * 31 + Double.hashCode(cursor[0] + cursor[1] * 3 + cursor[2] * 7);
        return h;
    }

    // ================================================================ drawing

    @Override
    public long measure(int slot, float width, int widthMode, float height, int heightMode) {
        return Measure.size(widthMode == Measure.UNDEFINED ? 0 : width, heightMode == Measure.UNDEFINED ? 0 : height);
    }

    @Override
    public int spare(int recorded) {
        return recorded + 64;
    }

    @Override
    public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
        if (!visible || view == null || !layout(view, view.x(), view.y(), view.z())) {
            return;
        }
        double ox = shapes.origin[0];
        double oy = shapes.origin[1];
        double oz = shapes.origin[2];
        if (!view.project(ox, oy, oz, sa)) {
            return;
        }
        float cx = sa[0];
        float cy = sa[1];
        float fade = style(occludedPart).getFloat(Styles.OPACITY);
        float upp = (float) view.unitsPerPixel(view.depth(ox, oy, oz));
        if (mode == GizmoMode.BOUNDS) {
            paintBounds(list, opacity, fade);
            return;
        }
        if (shapes.shows(GizmoHandle.ROTATE_TRACKBALL)) {
            float r = (float) (shapes.ringRadius() * shapes.size / upp);
            long c = color(trackballPart, opacity);
            list.box(cx - r, cy - r, cx + r, cy + r, c, r, r, r, r, 0, 0);
        }
        for (int a = 0; a < 3; a++) {
            GizmoHandle ring = GizmoShapes.ringHandle(a);
            if (shapes.shows(ring)) {
                paintRing(list, ring, ringParts[a], shapes.ringRadius(), opacity, fade);
            }
        }
        if (shapes.shows(GizmoHandle.ROTATE_VIEW)) {
            paintRing(list, GizmoHandle.ROTATE_VIEW, ringParts[3], shapes.viewRingRadius(), opacity, fade);
        }
        for (int a = 0; a < 3; a++) {
            GizmoHandle plane = a == 0 ? GizmoHandle.TRANSLATE_YZ : a == 1 ? GizmoHandle.TRANSLATE_ZX
                : GizmoHandle.TRANSLATE_XY;
            if (shapes.shows(plane)) {
                paintPlane(list, plane, planeParts[a], opacity, fade);
            }
        }
        float tip = length(style(tipPart).get(Styles.WIDTH), 12);
        for (int a = 0; a < 3; a++) {
            Element part = axisParts[a];
            float w = length(style(part).get(Styles.WIDTH), 3);
            if (shapes.shows(GizmoShapes.translateHandle(a))) {
                shapes.segment(a, shapes.arrowStart(), shapes.tipStart(), p, q);
                segment(list, p, q, w, part, opacity, fade);
                shapes.segment(a, shapes.tipStart(), shapes.arrowEnd(), p, q);
                // the tip: a triangle from the tip's base, across the axis on the screen, to its point
                if (view.project(p[0], p[1], p[2], sa) && view.project(q[0], q[1], q[2], sb)) {
                    float dx = sb[0] - sa[0];
                    float dy = sb[1] - sa[1];
                    float l = (float) Maths.hypot(dx, dy);
                    if (l > 0.5f) {
                        float nx = -dy / l * tip / 2;
                        float ny = dx / l * tip / 2;
                        if (depthTest) {
                            list.depthTriangle(sb[0], sb[1], sb[2], sa[0] + nx, sa[1] + ny, sa[2], sa[0] - nx,
                                sa[1] - ny, sa[2], color(part, opacity),
                                Primitive.EDGE_AB | Primitive.EDGE_BC | Primitive.EDGE_CA, fade);
                        } else {
                            boolean hidden = occluded((p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2);
                            triangle(list, sb[0], sb[1], sa[0] + nx, sa[1] + ny, sa[0] - nx, sa[1] - ny,
                                color(part, hidden ? opacity * fade : opacity));
                        }
                    }
                }
            }
            if (shapes.shows(GizmoShapes.scaleHandle(a))) {
                if (mode == GizmoMode.SCALE) {
                    shapes.segment(a, shapes.arrowStart(), shapes.cubeAt(), p, q);
                    segment(list, p, q, w, part, opacity, fade);
                }
                shapes.segment(a, shapes.cubeAt(), shapes.cubeAt(), p, q);
                dot(list, p, tip, style(tipPart).get(Styles.RADIUS).topLeft() * ui.layout().scale(), part, opacity,
                    fade);
            }
        }
        if (shapes.shows(GizmoHandle.TRANSLATE_SCREEN) || shapes.shows(GizmoHandle.SCALE_UNIFORM)) {
            float d = length(style(centerPart).get(Styles.WIDTH), 14);
            long c = color(centerPart, opacity);
            float r = d / 2;
            list.box(cx - r, cy - r, cx + r, cy + r, c, r, r, r, r, 0, 0);
        }
    }

    private void paintRing(DrawList list, GizmoHandle ring, Element part, double radius, float opacity, float fade) {
        double[] normal = shapes.ringNormal(ring, n);
        float w = length(style(part).get(Styles.WIDTH), 2.5f);
        double depth = view.depth(shapes.origin[0], shapes.origin[1], shapes.origin[2]);
        shapes.ringPoint(normal, 0, radius, p);
        for (int i = 1; i <= GizmoShapes.RING_SEGMENTS; i++) {
            shapes.ringPoint(normal, i % GizmoShapes.RING_SEGMENTS, radius, q);
            double mx = (p[0] + q[0]) / 2;
            double my = (p[1] + q[1]) / 2;
            double mz = (p[2] + q[2]) / 2;
            // the back half of an axis ring is behind the gizmo's ball: drawn as hidden
            boolean back = ring != GizmoHandle.ROTATE_VIEW && view.depth(mx, my, mz) > depth + 1e-9 * depth;
            if (view.project(p[0], p[1], p[2], sa) && view.project(q[0], q[1], q[2], sb)) {
                if (depthTest && !back) {
                    list.depthLine(sa[0], sa[1], sa[2], sb[0], sb[1], sb[2], w, color(part, opacity), fade);
                } else {
                    boolean hidden = back || !depthTest && occluded(mx, my, mz);
                    list.line(sa[0], sa[1], sb[0], sb[1], w, color(part, hidden ? opacity * fade : opacity));
                }
            }
            p[0] = q[0];
            p[1] = q[1];
            p[2] = q[2];
        }
    }

    private void paintPlane(DrawList list, GizmoHandle plane, Element part, float opacity, float fade) {
        int u = GizmoShapes.planeU(plane);
        int v = GizmoShapes.planeV(plane);
        double lo = shapes.planeMin();
        double hi = shapes.planeMax();
        double mid = (lo + hi) / 2;
        // the centre for occlusion
        double[] au = shapes.axis[u];
        double[] av = shapes.axis[v];
        double s = shapes.size;
        double cx = shapes.origin[0] + (au[0] + av[0]) * mid * s;
        double cy = shapes.origin[1] + (au[1] + av[1]) * mid * s;
        double cz = shapes.origin[2] + (au[2] + av[2]) * mid * s;
        boolean hidden = !depthTest && occluded(cx, cy, cz);
        long fill = color(part, hidden ? opacity * fade : opacity);
        // the square's corners (lo, lo), (hi, lo), (hi, hi), (lo, hi) in u, v: two triangles
        for (int k = 0; k < 4; k++) {
            double fu = k == 1 || k == 2 ? hi : lo;
            double fv = k >= 2 ? hi : lo;
            for (int c = 0; c < 3; c++) {
                p[c] = shapes.origin[c] + (au[c] * fu + av[c] * fv) * s;
            }
            if (!view.project(p[0], p[1], p[2], sa)) {
                return;
            }
            quad[2 * k] = sa[0];
            quad[2 * k + 1] = sa[1];
            quadDepth[k] = sa[2];
        }
        if (depthTest) {
            float[] xy = quad;
            float[] z = quadDepth;
            list.depthTriangle(xy[0], xy[1], z[0], xy[2], xy[3], z[1], xy[4], xy[5], z[2], fill,
                Primitive.EDGE_AB | Primitive.EDGE_BC, fade);
            list.depthTriangle(xy[0], xy[1], z[0], xy[4], xy[5], z[2], xy[6], xy[7], z[3], fill,
                Primitive.EDGE_BC | Primitive.EDGE_CA, fade);
        } else {
            quad(list, quad, fill);
        }
    }

    /**
     * A filled quadrilateral of corners {@code xy} (x, y of four corners in order) as two triangles: its outline
     * anti-aliased, the diagonal they share hard, so a translucent fill shows no seam.
     */
    static void quad(DrawList list, float[] xy, long color) {
        list.triangle(xy[0], xy[1], xy[2], xy[3], xy[4], xy[5], color, Primitive.EDGE_AB | Primitive.EDGE_BC);
        list.triangle(xy[0], xy[1], xy[4], xy[5], xy[6], xy[7], color, Primitive.EDGE_BC | Primitive.EDGE_CA);
    }

    private void paintBounds(DrawList list, float opacity, float fade) {
        float w = length(style(boundsPart).get(Styles.WIDTH), 1.5f);
        for (int c = 0; c < 8; c++) {
            for (int a = 0; a < 3; a++) {
                int other = c | 1 << a;
                if (other != c) {
                    shapes.corner(c, p);
                    shapes.corner(other, q);
                    segment(list, p, q, w, boundsPart, opacity, fade);
                }
            }
        }
        float size = boundsHandlePx();
        float radius = style(boundsHandlePart).get(Styles.RADIUS).topLeft() * ui.layout().scale();
        GizmoHandle h = hovered();
        int part = handlePart();
        for (int c = 0; c < 8; c++) {
            shapes.corner(c, p);
            boolean on = h == GizmoHandle.BOUNDS_CORNER && part == c;
            dot(list, p, size, radius, on ? boundsActivePart : boundsHandlePart, opacity, fade);
        }
        for (int f = 0; f < 6; f++) {
            shapes.face(f, p);
            boolean on = h == GizmoHandle.BOUNDS_FACE && part == f;
            dot(list, p, size * 0.8f, radius, on ? boundsActivePart : boundsHandlePart, opacity, fade);
        }
    }

    /** A segment from {@code a} to {@code b}, faded where hidden (tested at its middle). */
    private void segment(DrawList list, double[] a, double[] b, float width, Element part, float opacity,
        float fade) {
        lineTo(list, a, b[0], b[1], b[2], width, part, opacity, fade);
    }

    private void lineTo(DrawList list, double[] a, double bx, double by, double bz, float width, Element part,
        float opacity, float fade) {
        if (!view.project(a[0], a[1], a[2], sa) || !view.project(bx, by, bz, sb)) {
            return;
        }
        if (depthTest) {
            list.depthLine(sa[0], sa[1], sa[2], sb[0], sb[1], sb[2], width, color(part, opacity), fade);
            return;
        }
        boolean hidden = occluded((a[0] + bx) / 2, (a[1] + by) / 2, (a[2] + bz) / 2);
        list.line(sa[0], sa[1], sb[0], sb[1], width, color(part, hidden ? opacity * fade : opacity));
    }

    /** Whether its {@link #occlusion} says world point {@code x, y, z} is hidden (never without one). */
    private boolean occluded(double x, double y, double z) {
        return occlusion != null && occlusion.occluded(view, x, y, z);
    }

    /** A square (or round, by its radius) handle of {@code size} pixels at {@code at}. */
    private void dot(DrawList list, double[] at, float size, float radius, Element part, float opacity, float fade) {
        if (!view.project(at[0], at[1], at[2], sa)) {
            return;
        }
        float h = size / 2;
        float r = Math.min(radius, h);
        if (depthTest) {
            list.depthBox(sa[0] - h, sa[1] - h, sa[0] + h, sa[1] + h, sa[2], color(part, opacity), r, r, r, r, 0, 0,
                fade);
            return;
        }
        boolean hidden = occluded(at[0], at[1], at[2]);
        list.box(sa[0] - h, sa[1] - h, sa[0] + h, sa[1] + h, color(part, hidden ? opacity * fade : opacity), r, r, r,
            r, 0, 0);
    }

    private long color(Element part, float opacity) {
        ResolvedStyle s = style(part);
        return Rgba.fade(s.get(Styles.COLOR).rgba(), opacity * s.getFloat(Styles.OPACITY));
    }

    /** A filled triangle with its point at {@code a} and base {@code b..c}, its edges anti-aliased. */
    static void triangle(DrawList list, float ax, float ay, float bx, float by, float cx, float cy, long color) {
        list.triangle(ax, ay, bx, by, cx, cy, color, Primitive.EDGE_AB | Primitive.EDGE_BC | Primitive.EDGE_CA);
    }

    /** Takes the gizmo off its UI and picking; the element is destroyed. */

    public void dispose() {
        cancel();
        ui.removeFrameListener(this);
        picking.removeHandles(this);
        ui.destroy(element);
    }
}
