package com.truesim.ui.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.PickHits;
import com.truesim.scene.core.Pickable;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ScenePicker;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.Overlay;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.DataFormat;
import com.truesim.ui.core.event.DragSession;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.event.WorldPicking;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import java.util.Arrays;
import java.util.List;

/**
 * The scene bridge of a UI's pointers (design 4.7, steps 2 to 5; UI4, UI5, UI12): world panels and scene objects
 * picked by each pointer's world ray after the screen layers, nearest first, so 3D objects, VR panels and the HUD
 * respond through one listener API and one {@link Interaction}: routing, pass-through, pointer capture, hover, clicks
 * with counts, long presses, context menus, tooltips, the wheel, and drag and drop between UI elements and scene nodes
 * with one {@link DragSession}.
 *
 * <p><b>Picking.</b> A screen pointer's ray comes from the {@link SceneView} it is over: a {@linkplain #view(Element,
 * SceneView) camera view element} it hits (a minimap), else the main view; an XR pointer brings its own
 * ({@link Pointer#ray}). Then, in order:
 * <ol>
 *   <li>{@linkplain #addHandles handles} (gizmos), which come before everything in the scene;</li>
 *   <li>{@linkplain #addPanel world panels}: the ray against each panel's plane, then the 2D hit test of the panel's
 *       own UI at the local point (a panel where nothing is hit lets the ray through, unless it is opaque);</li>
 *   <li>scene objects: the scene's engine-independent picking ({@link ScenePicker}: {@link Pickable} components, their
 *       narrow phases, the frame's picks shared with the engine's scene interaction, so a pointer's ray is cast once a
 *       frame), up to {@code ui.pick.sceneHits} objects along the ray, for pass-through. A pickable node hit stands
 *       for its nearest scene-graph ancestor (or itself) with an {@link Interactive}; one with none is not the UI's
 *       (the engine's scene interaction may route it).</li>
 * </ol>
 * Panels and objects are merged by distance and follow the screen layers' hits. Each interactive node and panel is
 * stood for by an element of its own (on a hidden layer, never drawn), so the interaction treats them as it treats
 * elements; a node's element is under its nearest interactive scene-graph ancestor's, so events bubble up the scene
 * graph.
 *
 * <p><b>The frame.</b> Call {@link #input} instead of {@code ui.input}: it processes the UI's input, then hands each
 * world panel the pointers whose rays reach it (at their points in the panel's pixels), and {@link #update} after the
 * game's update to update the panels' UIs. The scene and its {@link SpatialIndex} must be up to date before
 * {@code input} (the engine updates both at the frame's start). Steady-state frames allocate nothing.
 *
 * <pre>{@code
 * ScenePicking picking = new ScenePicking(ui, picker).mainView(camera);     // picker: the scene's ScenePicker
 * picking.add(door, Interactive.of(EventType.CLICK, e -> open(picking.node(e))));
 * // each frame
 * picking.input(input, nanos);   // pointers: screen layers, panels, gizmos, scene
 * game.update();
 * picking.update(width, height);
 * }</pre>
 */
public final class ScenePicking implements WorldPicking {

    /** The most scene objects one pointer's ray reaches (pass-through goes this deep). */
    public static final Tunable SCENE_HITS = Tunable.of("ui.pick.sceneHits", 4, 1, 8,
        "How many scene objects along a pointer's ray take part in picking (pass-through goes this deep).");
    /** How far an XR pointer's ray reaches. */
    public static final Tunable RAY_LENGTH = Tunable.of("ui.pick.rayLength", 1000, 1, 1_000_000,
        "Metres an XR pointer's ray reaches into the scene (a screen pointer's reaches its camera's far plane).");

    /** The name of the layer the elements standing for scene nodes and panels are on. */
    public static final String LAYER = "scene";

    private static final int MAX = Interaction.MAX_HITS;
    private static final int HOOK_MASK = EventType.TOOLTIP.bit() | EventType.EXIT.bit() | EventType.PRESS.bit()
        | EventType.CONTEXT.bit() | EventType.DRAG_START.bit() | EventType.DROP_OVER.bit();

    private final Ui ui;
    private final UiStore store;
    private final Interaction interaction;
    private final SceneGraph scene;
    private final ScenePicker picker;
    private final ComponentStore<Pickable> pickables;
    private final Layer proxyLayer;
    private int pickLayers = -1;

    // the interactive nodes: a sparse set by node slot
    private int[] sparse = new int[0];
    private long[] nodes = new long[16];
    private Interactive[] components = new Interactive[16];
    private Element[] proxies = new Element[16];
    private boolean[] ownPickable = new boolean[16];
    private int count;
    // by element slot: the node (or panel) an element stands for
    private long[] slotNode = new long[0];
    private WorldPanel[] slotPanel = new WorldPanel[0];

    // views, panels, handles
    private SceneView mainView;
    private Element[] viewElements = new Element[4];
    private SceneView[] views = new SceneView[4];
    private int viewCount;
    private WorldPanel[] panels = new WorldPanel[4];
    private int panelCount;
    private HandlePicker[] handles = new HandlePicker[2];
    private int handleCount;

    // per pointer (by input slot): its ray, its world hits, the panel it is on
    private final int[] pointerIds = new int[UiInput.MAX_POINTERS];
    private final boolean[] pointerUsed = new boolean[UiInput.MAX_POINTERS];
    private final long[] pickedFrame = new long[UiInput.MAX_POINTERS];
    private final double[][] rays = new double[UiInput.MAX_POINTERS][6];
    private final boolean[] hasRay = new boolean[UiInput.MAX_POINTERS];
    private final SceneView[] pointerViews = new SceneView[UiInput.MAX_POINTERS];
    private final int[][] worldSlots = new int[UiInput.MAX_POINTERS][MAX];
    private final double[][] worldDistances = new double[UiInput.MAX_POINTERS][MAX];
    private final int[] worldCounts = new int[UiInput.MAX_POINTERS];
    private final int[] panelTarget = new int[UiInput.MAX_POINTERS];
    private final boolean[] taken = new boolean[UiInput.MAX_POINTERS]; // by a screen element, a handle or a panel
    private final double[] reaches = new double[UiInput.MAX_POINTERS];
    private final float[][] panelPoint = new float[UiInput.MAX_POINTERS][2];
    private long frame;

    // one pick's scratch
    private final double[] ray = new double[6];
    private final int[] candidateSlots = new int[MAX];
    private final double[] candidateDistances = new double[MAX];
    private final int[] candidatePanels = new int[MAX];
    private final float[][] candidatePoints = new float[MAX][2];
    private final float[] local = new float[2];
    private final PickHits sceneHits = new PickHits(8);

    // hooks: tooltips, context menus, drag sources, drop targets
    private final UiListener hooks = this::hook;
    private final UiListener panelConsumer = UiEvent::consume;
    private Overlays overlays;
    private Overlay tooltip;
    private Element tooltipLabel;
    private SceneAnchor tooltipAnchor;
    private long tooltipNode = NodeHandle.NONE;

    /**
     * The scene bridge of {@code ui} over {@code graph} and its {@code spatialIndex}, through a {@link ScenePicker} of
     * its own; makes itself the UI interaction's {@link WorldPicking}.
     */
    @AllowAllocation("one per UI")
    public ScenePicking(Ui ui, SceneGraph graph, SpatialIndex spatialIndex) {
        this(ui, new ScenePicker(graph, spatialIndex));
    }

    /**
     * The scene bridge of {@code ui} picking through {@code scenePicker} (shared with the engine's scene interaction:
     * a ray is cast once a frame); makes itself the UI interaction's {@link WorldPicking}.
     */
    @AllowAllocation("one per UI")
    public ScenePicking(Ui ui, ScenePicker scenePicker) {
        this.ui = ui;
        this.store = ui.store();
        this.interaction = ui.interaction();
        this.picker = scenePicker;
        this.scene = scenePicker.scene();
        this.pickables = scene.components().store(Pickable.TYPE);
        Layer existing = ui.layerStack().find(LAYER);
        this.proxyLayer = existing != null ? existing : ui.layerStack().add(LAYER, -1000);
        proxyLayer.input(Layer.Input.NONE); // never hit on the screen: only through rays
        Arrays.fill(panelTarget, -1);
        interaction.worldPicking(this);
    }

    /** The tunables scene picking reads, for an engine to register as settings. */
    @AllowAllocation("asked once")
    public static List<Tunable> tunables() {
        return List.of(SCENE_HITS, RAY_LENGTH);
    }

    // ================================================================ interactive nodes

    /**
     * Makes {@code node} interactive as {@code interactive} describes (replacing what it had): gives it an element of
     * its own for routing, and makes it pickable as the interactive's shape says (a node pickable already keeps its
     * own {@link Pickable} unless a shape was set).
     */
    @AllowAllocation("interactive nodes are added when a scene is built")
    public Element add(long node, Interactive interactive) {
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("no " + NodeHandle.toString(node) + " in the scene");
        }
        if (has(node)) {
            remove(node);
        }
        int slot = NodeHandle.index(node);
        if (sparse.length <= slot) {
            sparse = Arrays.copyOf(sparse, Math.max(slot + 1, sparse.length * 2));
        }
        if (count == nodes.length) {
            nodes = Arrays.copyOf(nodes, count * 2);
            components = Arrays.copyOf(components, count * 2);
            proxies = Arrays.copyOf(proxies, count * 2);
            ownPickable = Arrays.copyOf(ownPickable, count * 2);
        }
        Element proxy = proxy("scene-node");
        slotNode[proxy.slot()] = node;
        int d = count++;
        nodes[d] = node;
        components[d] = interactive;
        proxies[d] = proxy;
        sparse[slot] = d + 1;
        for (int i = 0; i < interactive.count; i++) {
            if (interactive.capture[i]) {
                interaction.onCapture(proxy, interactive.masks[i], interactive.listeners[i]);
            } else {
                interaction.on(proxy, interactive.masks[i], interactive.listeners[i]);
            }
        }
        if (interactive.tooltip != null || interactive.contextMenu != null || interactive.dragFormat != null
            || interactive.dropFormats.length > 0) {
            interaction.on(proxy, HOOK_MASK, hooks);
        }
        home(d);
        if (scene.firstChild(node) != NodeHandle.NONE) {
            for (int i = 0; i < count; i++) {
                if (i != d) {
                    home(i); // interactive descendants now bubble to this node
                }
            }
        }
        Pickable shape = interactive.pickable();
        ownPickable[d] = shape != null || !pickables.has(node);
        if (shape != null || !pickables.has(node)) {
            pickables.set(node, shape != null ? shape : Pickable.bounds());
        }
        return proxy;
    }

    /** Takes {@code node}'s interaction away: its element gone, and the pickable it was given. */
    @AllowAllocation("taking components away re-homes the elements of descendants")
    public void remove(long node) {
        int d = dense(node);
        if (d < 0) {
            return;
        }
        Element proxy = proxies[d];
        Element parent = proxy.parent();
        while (store.firstChild(proxy.slot()) != UiStore.NONE) {
            parent.add(store.element(store.firstChild(proxy.slot())));
        }
        slotNode[proxy.slot()] = NodeHandle.NONE;
        ui.destroy(proxy);
        sparse[NodeHandle.index(node)] = 0;
        boolean own = ownPickable[d];
        int last = --count;
        if (d != last) {
            nodes[d] = nodes[last];
            components[d] = components[last];
            proxies[d] = proxies[last];
            ownPickable[d] = ownPickable[last];
            sparse[NodeHandle.index(nodes[d])] = d + 1;
        }
        nodes[last] = NodeHandle.NONE;
        components[last] = null;
        proxies[last] = null;
        if (tooltipNode == node) {
            hideTooltip();
        }
        if (own && scene.contains(node)) {
            pickables.remove(node);
        }
    }

    /** Whether {@code node} is interactive. */
    public boolean has(long node) {
        return dense(node) >= 0;
    }

    /** How many nodes are interactive. */
    public int count() {
        return count;
    }

    /** The component of {@code node}, or null. */
    public Interactive component(long node) {
        int d = dense(node);
        return d < 0 ? null : components[d];
    }

    /** The element standing for {@code node} (its hover and pressed states, its listeners), or null. */
    public Element element(long node) {
        int d = dense(node);
        return d < 0 ? null : proxies[d];
    }

    /** The node {@code element} stands for, or {@link NodeHandle#NONE} (a UI element, a panel). */
    public long node(Element element) {
        return element == null ? NodeHandle.NONE : node(element.slot());
    }

    /** The node the element in {@code slot} stands for, or {@link NodeHandle#NONE}. */
    public long node(int slot) {
        return slot >= 0 && slot < slotNode.length ? slotNode[slot] : NodeHandle.NONE;
    }

    /** The node whose listener runs for {@code event} (its current target), or {@link NodeHandle#NONE}. */
    public long node(UiEvent event) {
        return node(event.currentSlot());
    }

    /** The node {@code event} is for (its target: the hit node, before bubbling), or {@link NodeHandle#NONE}. */
    public long targetNode(UiEvent event) {
        return node(event.targetSlot());
    }

    /** The world panel {@code element} stands for, or null. */
    public WorldPanel panel(Element element) {
        int slot = element.slot();
        return slot < slotPanel.length ? slotPanel[slot] : null;
    }

    /** Whether a pointer is over {@code node} (the {@code hover} state of its element). */
    public boolean hovered(long node) {
        Element e = element(node);
        return e != null && e.is(UiState.HOVER);
    }

    /** Whether {@code node} is pressed (the {@code pressed} state of its element). */
    public boolean pressed(long node) {
        Element e = element(node);
        return e != null && e.is(UiState.PRESSED);
    }

    private int dense(long node) {
        int slot = NodeHandle.index(node);
        if (node == NodeHandle.NONE || slot >= sparse.length) {
            return -1;
        }
        int d = sparse[slot] - 1;
        return d >= 0 && nodes[d] == node ? d : -1;
    }

    @AllowAllocation("elements are made when a scene is built")
    private Element proxy(String type) {
        Element e = ui.element(type, null);
        ui.layout().visible(e.slot(), false);
        proxyLayer.root().add(e);
        int n = store.capacity();
        if (slotNode.length < n) {
            slotNode = Arrays.copyOf(slotNode, n);
            slotPanel = Arrays.copyOf(slotPanel, n);
        }
        slotNode[e.slot()] = NodeHandle.NONE;
        slotPanel[e.slot()] = null;
        return e;
    }

    /** Puts entry {@code d}'s element under its nearest interactive ancestor's (or the layer's root). */
    private void home(int d) {
        Element parent = proxyLayer.root();
        for (long a = scene.parent(nodes[d]); a != NodeHandle.NONE; a = scene.parent(a)) {
            int ad = dense(a);
            if (ad >= 0) {
                parent = proxies[ad];
                break;
            }
        }
        if (proxies[d].parent() != parent) {
            parent.add(proxies[d]);
        }
    }

    /** Takes away the components of nodes no longer in the scene. */
    public void prune() {
        for (int d = count - 1; d >= 0; d--) {
            if (!scene.contains(nodes[d])) {
                remove(nodes[d]);
            }
        }
    }

    // ================================================================ views, panels, handles

    /** Sets the main 3D view: screen pointers not over a camera view get its rays; null: none. Returns this. */
    public ScenePicking mainView(SceneView view) {
        mainView = view;
        return this;
    }

    /** The main 3D view, or null. */
    public SceneView mainView() {
        return mainView;
    }

    /**
     * Makes pointers over {@code element} (a {@link CameraView}, a 3D viewport in a window) pick through
     * {@code view}; null removes it.
     */
    @AllowAllocation("views are set when a UI is built")
    public void view(Element element, SceneView view) {
        for (int i = 0; i < viewCount; i++) {
            if (viewElements[i] == element) {
                if (view == null) {
                    viewElements[i] = viewElements[--viewCount];
                    views[i] = views[viewCount];
                    viewElements[viewCount] = null;
                    views[viewCount] = null;
                } else {
                    views[i] = view;
                }
                return;
            }
        }
        if (view == null) {
            return;
        }
        if (viewCount == views.length) {
            viewElements = Arrays.copyOf(viewElements, viewCount * 2);
            views = Arrays.copyOf(views, viewCount * 2);
        }
        viewElements[viewCount] = element;
        views[viewCount++] = view;
    }

    /** Adds a world panel: pointers reach its UI by their rays. Returns the element standing for it. */
    @AllowAllocation("panels are added when a scene is built")
    public Element addPanel(WorldPanel panel) {
        if (panelCount == panels.length) {
            panels = Arrays.copyOf(panels, panelCount * 2);
        }
        panels[panelCount++] = panel;
        Element proxy = proxy("world-panel");
        slotPanel[proxy.slot()] = panel;
        panel.proxy = proxy;
        // the panel's own UI handles what reaches it: nothing passes on to what is behind it
        interaction.on(proxy, EventType.POINTER_MASK, panelConsumer);
        return proxy;
    }

    /** Removes a world panel. */
    public void removePanel(WorldPanel panel) {
        for (int i = 0; i < panelCount; i++) {
            if (panels[i] == panel) {
                panels[i] = panels[--panelCount];
                panels[panelCount] = null;
                slotPanel[panel.proxy.slot()] = null;
                ui.destroy(panel.proxy);
                panel.proxy = null;
                return;
            }
        }
    }

    /** How many world panels there are. */
    public int panelCount() {
        return panelCount;
    }

    /** Adds handles picked before the scene's objects (a gizmo). */
    @AllowAllocation("handles are added when a UI is built")
    public void addHandles(HandlePicker picker) {
        if (handleCount == handles.length) {
            handles = Arrays.copyOf(handles, handleCount * 2);
        }
        handles[handleCount++] = picker;
    }

    /** Removes handles. */
    public void removeHandles(HandlePicker picker) {
        for (int i = 0; i < handleCount; i++) {
            if (handles[i] == picker) {
                handles[i] = handles[--handleCount];
                handles[handleCount] = null;
                return;
            }
        }
    }

    // ================================================================ the frame

    /**
     * The frame's input phase: the UI's {@link Ui#input} (pointers picked against the screen layers, then by their
     * rays here), then each world panel's, with the pointers that reach it.
     */
    public void input(UiInput input, long nanos) {
        frame++;
        ui.input(input, nanos);
        if (input == null) {
            return;
        }
        for (int k = 0; k < panelCount; k++) {
            WorldPanel panel = panels[k];
            UiInput in = panel.input();
            in.beginFrame();
            for (int i = 0; i < input.pointerCount(); i++) {
                Pointer pointer = input.pointerAt(i);
                int p = slotOf(pointer.id());
                boolean on = false;
                float u = 0;
                float v = 0;
                if (p >= 0 && pickedFrame[p] == frame) {
                    if (panel.ui().interaction().captured(pointer.id()) != null) {
                        double[] r = rays[p];
                        on = panel.intersect(r[0], r[1], r[2], r[3], r[4], r[5], false, local) < Double.POSITIVE_INFINITY;
                        u = local[0];
                        v = local[1];
                    } else if (panelTarget[p] == k && capturedBy(pointer.id(), panel.proxy)) {
                        on = true;
                        u = panelPoint[p][0];
                        v = panelPoint[p][1];
                    }
                }
                if (on) {
                    in.pointer(pointer.id(), pointer.kind()).moveTo(u, v).buttons(pointer.buttons())
                        .scroll(pointer.scrollX(), pointer.scrollY()).modifiers(pointer.modifiers())
                        .player(pointer.player());
                } else {
                    in.removePointer(pointer.id());
                }
            }
            for (int i = in.pointerCount() - 1; i >= 0; i--) {
                if (!inInput(input, in.pointerAt(i).id())) {
                    in.removePointer(in.pointerAt(i).id());
                }
            }
            panel.ui().input(in, nanos);
        }
    }

    /** The frame's update phase: the UI's {@link Ui#update} and each world panel's, at its own size. */
    public void update(float width, float height) {
        ui.update(width, height);
        for (int k = 0; k < panelCount; k++) {
            panels[k].ui().update(panels[k].width(), panels[k].height());
        }
    }

    private boolean capturedBy(int pointerId, Element proxy) {
        Element c = interaction.captured(pointerId);
        return c == null || c == proxy;
    }

    private static boolean inInput(UiInput input, int id) {
        for (int i = 0; i < input.pointerCount(); i++) {
            if (input.pointerAt(i).id() == id) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ picking

    @Override
    public int pick(Pointer pointer, int[] hits, int screenCount) {
        int p = claim(pointer.id());
        pickedFrame[p] = frame;
        worldCounts[p] = 0;
        panelTarget[p] = -1;
        taken[p] = screenCount > 0;
        SceneView view = null;
        double reach;
        if (pointer.hasRay()) {
            ray[0] = pointer.rayOriginX();
            ray[1] = pointer.rayOriginY();
            ray[2] = pointer.rayOriginZ();
            ray[3] = pointer.rayDirectionX();
            ray[4] = pointer.rayDirectionY();
            ray[5] = pointer.rayDirectionZ();
            reach = RAY_LENGTH.get() / Math.sqrt(ray[3] * ray[3] + ray[4] * ray[4] + ray[5] * ray[5]);
        } else {
            view = viewAt(pointer.x(), pointer.y(), hits, screenCount);
            if (view == null) {
                hasRay[p] = false;
                pointerViews[p] = null;
                return screenCount;
            }
            view.ray(pointer.x(), pointer.y(), ray);
            reach = view.far();
        }
        hasRay[p] = true;
        pointerViews[p] = view;
        reaches[p] = reach;
        System.arraycopy(ray, 0, rays[p], 0, 6);
        int n = screenCount;
        int[] slots = worldSlots[p];
        double[] distances = worldDistances[p];
        int w = 0;
        for (int h = 0; h < handleCount && n < hits.length; h++) {
            int slot = handles[h].pick(pointer, view, ray);
            if (slot != UiStore.NONE) {
                hits[n++] = slot;
                slots[w] = slot;
                distances[w++] = 0;
                taken[p] = true;
            }
        }
        int found = 0;
        for (int k = 0; k < panelCount && found < MAX; k++) {
            WorldPanel panel = panels[k];
            double t = panel.intersect(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], true, local);
            if (t <= reach && panel.takes(local[0], local[1])) {
                candidateSlots[found] = panel.proxy.slot();
                candidateDistances[found] = t;
                candidatePanels[found] = k;
                candidatePoints[found][0] = local[0];
                candidatePoints[found][1] = local[1];
                found++;
            }
        }
        int depth = Math.min((int) SCENE_HITS.get(), sceneHits.capacity());
        PickHits hits3d = depth == sceneHits.capacity() ? sceneHits : depthHits(depth);
        int picked = picker.pick(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], reach, pickLayers, hits3d);
        for (int k = 0; k < picked && found < MAX; k++) {
            int d = interactiveOf(hits3d.node(k));
            if (d >= 0 && !candidate(proxies[d].slot(), found)) {
                candidateSlots[found] = proxies[d].slot();
                candidateDistances[found] = hits3d.distance(k);
                candidatePanels[found] = -1;
                found++;
            }
        }
        sortCandidates(found);
        taken[p] |= found > 0 && candidatePanels[0] >= 0;
        for (int i = 0; i < found && n < hits.length; i++) {
            hits[n++] = candidateSlots[i];
            slots[w] = candidateSlots[i];
            distances[w++] = candidateDistances[i];
        }
        worldCounts[p] = w;
        if (screenCount == 0 && w > 0 && found > 0 && slots[0] == candidateSlots[0] && candidatePanels[0] >= 0) {
            panelTarget[p] = candidatePanels[0];
            panelPoint[p][0] = candidatePoints[0][0];
            panelPoint[p][1] = candidatePoints[0][1];
        }
        return n;
    }

    /** The view a screen pointer at {@code x, y} is over: a camera view element it hits, else the main view. */
    private SceneView viewAt(float x, float y, int[] hits, int screenCount) {
        if (viewCount > 0) {
            for (int i = 0; i < screenCount; i++) {
                for (int s = hits[i]; s != UiStore.NONE; s = store.parent(s)) {
                    for (int v = 0; v < viewCount; v++) {
                        if (viewElements[v].slot() == s && viewElements[v].alive()) {
                            return views[v].contains(x, y) ? views[v] : null;
                        }
                    }
                }
            }
        }
        // a view element no listener made pickable: by its rectangle
        for (int v = viewCount - 1; v >= 0; v--) {
            Element e = viewElements[v];
            if (e.alive() && store.rootOf(e.slot()) != UiStore.NONE && interaction.geometry().shown(e.slot())
                && views[v].contains(x, y)) {
                return views[v];
            }
        }
        return mainView != null && mainView.contains(x, y) ? mainView : null;
    }

    private void sortCandidates(int found) {
        for (int i = 1; i < found; i++) {
            int slot = candidateSlots[i];
            double distance = candidateDistances[i];
            int panel = candidatePanels[i];
            float u = candidatePoints[i][0];
            float v = candidatePoints[i][1];
            int j = i - 1;
            while (j >= 0 && candidateDistances[j] > distance) {
                candidateSlots[j + 1] = candidateSlots[j];
                candidateDistances[j + 1] = candidateDistances[j];
                candidatePanels[j + 1] = candidatePanels[j];
                candidatePoints[j + 1][0] = candidatePoints[j][0];
                candidatePoints[j + 1][1] = candidatePoints[j][1];
                j--;
            }
            candidateSlots[j + 1] = slot;
            candidateDistances[j + 1] = distance;
            candidatePanels[j + 1] = panel;
            candidatePoints[j + 1][0] = u;
            candidatePoints[j + 1][1] = v;
        }
    }

    /** The entry of {@code node}'s nearest scene-graph ancestor (or itself) with an {@link Interactive}, or -1. */
    private int interactiveOf(long node) {
        for (long n = node; n != NodeHandle.NONE; n = scene.parent(n)) {
            int d = dense(n);
            if (d >= 0) {
                return d;
            }
        }
        return -1;
    }

    /** Whether element {@code slot} is among the first {@code found} candidates (two parts of one model). */
    private boolean candidate(int slot, int found) {
        for (int i = 0; i < found; i++) {
            if (candidateSlots[i] == slot) {
                return true;
            }
        }
        return false;
    }

    // hits of a smaller depth than the scratch's, for a lowered ui.pick.sceneHits: made once per depth
    private final PickHits[] byDepth = new PickHits[8];

    @AllowAllocation("once per depth, when ui.pick.sceneHits is lowered")
    private PickHits depthHits(int depth) {
        if (byDepth[depth] == null) {
            byDepth[depth] = new PickHits(depth);
        }
        return byDepth[depth];
    }

    /** How many narrow-phase tests the scene's picker ran so far (nodes without a pickable are never tested). */
    public long narrowTests() {
        return picker.narrowTests();
    }

    private int slotOf(int pointerId) {
        for (int p = 0; p < UiInput.MAX_POINTERS; p++) {
            if (pointerUsed[p] && pointerIds[p] == pointerId) {
                return p;
            }
        }
        return -1;
    }

    private int claim(int pointerId) {
        int p = slotOf(pointerId);
        if (p >= 0) {
            return p;
        }
        int oldest = 0;
        for (int i = 0; i < UiInput.MAX_POINTERS; i++) {
            if (!pointerUsed[i]) {
                oldest = i;
                break;
            }
            if (pickedFrame[i] < pickedFrame[oldest]) {
                oldest = i;
            }
        }
        pointerUsed[oldest] = true;
        pointerIds[oldest] = pointerId;
        return oldest;
    }

    // ================================================================ what listeners read

    /**
     * The world point where the ray of {@code event}'s pointer hit its target this frame (the node or panel the event
     * is for) into {@code out[0..2]}; false if the target was not hit by a ray this frame (a captured pointer off
     * it, a screen element).
     */
    public boolean hitPoint(UiEvent event, double[] out) {
        int p = slotOf(event.pointerId());
        if (p < 0 || pickedFrame[p] != frame || !hasRay[p]) {
            return false;
        }
        int target = event.targetSlot();
        for (int i = 0; i < worldCounts[p]; i++) {
            if (worldSlots[p][i] == target) {
                double t = worldDistances[p][i];
                double[] r = rays[p];
                out[0] = r[0] + r[3] * t;
                out[1] = r[1] + r[4] * t;
                out[2] = r[2] + r[5] * t;
                return true;
            }
        }
        return false;
    }

    /** The distance along the ray of {@code event}'s pointer to its target, or infinity (see {@link #hitPoint}). */
    public double hitDistance(UiEvent event) {
        int p = slotOf(event.pointerId());
        if (p >= 0 && pickedFrame[p] == frame && hasRay[p]) {
            for (int i = 0; i < worldCounts[p]; i++) {
                if (worldSlots[p][i] == event.targetSlot()) {
                    return worldDistances[p][i];
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * Pointer {@code pointerId}'s world ray this frame (origin, then direction) into {@code out[0..5]}: for dragging
     * things in the world. False if it had none (not over a 3D view, blocked by a modal layer).
     */
    public boolean ray(int pointerId, double[] out) {
        int p = slotOf(pointerId);
        if (p < 0 || pickedFrame[p] != frame || !hasRay[p]) {
            return false;
        }
        System.arraycopy(rays[p], 0, out, 0, 6);
        return true;
    }

    /**
     * Whether pointer {@code pointerId} is the UI's this frame, not the scene's: over a screen element (or a modal layer
     * that blocked the world), a gizmo handle or a world panel nearer than any scene node, captured by such an element,
     * or not picked at all (off every view). The engine's scene interaction leaves such pointers alone.
     */
    public boolean claimed(int pointerId) {
        int p = slotOf(pointerId);
        if (p < 0 || pickedFrame[p] != frame || !hasRay[p] || taken[p]) {
            return true;
        }
        Element captured = interaction.captured(pointerId);
        return captured != null && node(captured) == NodeHandle.NONE;
    }

    /** How far pointer {@code pointerId}'s ray reached this frame (0 without one), as the scene's picker was asked. */
    public double reach(int pointerId) {
        int p = slotOf(pointerId);
        return p < 0 || pickedFrame[p] != frame || !hasRay[p] ? 0 : reaches[p];
    }

    /** The view pointer {@code pointerId} picked through this frame (null: none, or its own XR ray). */
    public SceneView viewOf(int pointerId) {
        int p = slotOf(pointerId);
        return p < 0 || pickedFrame[p] != frame ? null : pointerViews[p];
    }

    /** How many world hits (handles, panels, nodes) pointer {@code pointerId} has this frame. */
    public int worldHitCount(int pointerId) {
        int p = slotOf(pointerId);
        return p < 0 || pickedFrame[p] != frame ? 0 : worldCounts[p];
    }

    /** The element of world hit {@code i} of pointer {@code pointerId}, nearest first. */
    public Element worldHit(int pointerId, int i) {
        return store.element(worldSlots[slotOf(pointerId)][i]);
    }

    // ================================================================ hooks

    private void hook(UiEvent e) {
        long node = node(e.currentSlot());
        Interactive c = component(node);
        if (c == null) {
            return;
        }
        switch (e.type()) {
            case TOOLTIP -> {
                if (c.tooltip != null && !e.consumed()) {
                    showTooltip(node, c.tooltip, e.pointerId());
                    e.consume();
                }
            }
            case EXIT, PRESS -> {
                if (tooltipNode == node) {
                    hideTooltip();
                }
            }
            case CONTEXT -> {
                if (c.contextMenu != null && !e.consumed()) {
                    e.consume();
                    c.contextMenu.openAt(e.x(), e.y());
                }
            }
            case DRAG_START -> {
                if (c.dragFormat != null && !e.consumed()) {
                    startDrag(e, c);
                }
            }
            case DROP_OVER -> {
                DragSession s = e.drag();
                if (s != null) {
                    for (int i = 0; i < c.dropFormats.length; i++) {
                        if (s.has(c.dropFormats[i])) {
                            e.acceptDrop(c.dropOperations);
                            break;
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void startDrag(UiEvent e, Interactive c) {
        e.startDrag(c.dragOperations).put((DataFormat<T>) c.dragFormat, (T) c.dragValue);
        e.consume();
    }

    @AllowAllocation("the scene tooltip is made on the first one")
    private void showTooltip(long node, CharSequence text, int pointerId) {
        if (ui.textSystem() == null) {
            return;
        }
        if (tooltip == null) {
            overlays = new Overlays(ui);
            Element box = ui.element("tooltip", "scene");
            tooltipLabel = ui.label(null, text);
            box.add(tooltipLabel);
            tooltipAnchor = new SceneAnchor(mainView);
            tooltip = overlays.create(box, ui.layer(Layers.TOOLTIP)).anchor(tooltipAnchor);
        }
        SceneView view = viewOf(pointerId);
        tooltipAnchor.view(view != null ? view : mainView).node(scene, node);
        if (tooltipAnchor.view() == null) {
            return;
        }
        ui.text(tooltipLabel, text);
        tooltipNode = node;
        tooltip.show();
    }

    private void hideTooltip() {
        tooltipNode = NodeHandle.NONE;
        if (tooltip != null) {
            tooltip.hide();
        }
    }

    /** The scene's tooltip overlay (made when the first scene tooltip shows), or null. */
    public Overlay tooltip() {
        return tooltip;
    }

    /** The node whose tooltip shows, or {@link NodeHandle#NONE}. */
    public long tooltipNode() {
        return tooltip != null && tooltip.showing() ? tooltipNode : NodeHandle.NONE;
    }

    /** The UI. */
    public Ui ui() {
        return ui;
    }

    /** The scene graph. */
    public SceneGraph scene() {
        return scene;
    }

    /** The spatial index picking raycasts. */
    public SpatialIndex index() {
        return picker.index();
    }

    /** The scene's picker it picks through. */
    public ScenePicker picker() {
        return picker;
    }

    /** Picks pickables on the pick layers of {@code mask} only (default: every pick layer); returns this. */
    public ScenePicking pickLayers(int mask) {
        pickLayers = mask;
        return this;
    }

    /** The pick layers it picks. */
    public int pickLayers() {
        return pickLayers;
    }
}
