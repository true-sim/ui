package com.truesim.ui.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.basic.Menu;
import com.truesim.ui.core.event.DataFormat;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiListener;
import java.util.Arrays;

/**
 * What makes a scene node take part in pointer interaction (design 4.7, UI4): its listeners, with the full interaction
 * set of UI elements (enter, exit, move, press, release, click with counts, long press, context, wheel, drag start,
 * drag, drag end, drop enter, over, leave, drop, tooltip) through the same {@link UiListener} API, how precisely it is
 * hit (its box, its mesh, or a test of its own), and the hooks the UI fills in for it: a tooltip, a context menu, a
 * drag payload, the drop formats it takes. Added to a node with {@link ScenePicking#add}: only nodes with an
 * {@code Interactive} are on the interactive layer, so nodes without one cost picking nothing.
 *
 * <pre>{@code
 * picking.add(crate, Interactive.of(EventType.CLICK, e -> open(picking.node(e)))
 *     .mesh(crateMesh)                          // precise: the mesh's triangles, not its box
 *     .tooltip("Supply crate")
 *     .contextMenu(crateMenu)
 *     .draggable(ITEM, crateItem, DragOperation.MOVE));
 * }</pre>
 *
 * <p>A listener reads the node with {@link ScenePicking#node(com.truesim.ui.core.event.UiEvent)} and where the ray hit
 * it with {@link ScenePicking#hitPoint}. Events bubble from a node to its nearest interactive scene-graph ancestor
 * (Lemur's hit target walk), and an event no listener consumed passes on to what is behind along the ray.
 *
 * <p>A description to add, not a live component: build one when the node is set up and add it; change it by adding
 * a new one.
 */
public final class Interactive {

    /** How precisely a node is hit. */
    public enum Shape {
        /** Its world box: free, the spatial index already tested it. */
        BOUNDS,
        /** Its mesh's triangles ({@link PickMesh}), tested where the box is hit. */
        MESH,
        /** A test of the game's own ({@link NarrowPhase}): a sphere, a capsule, a GPU result. */
        CUSTOM
    }

    /** A node's own precise test: where a world ray hits it. */
    @FunctionalInterface
    public interface NarrowPhase {

        /**
         * How far along the world ray (in units of its direction's length) it hits {@code node}, whose box it enters at
         * {@code boxDistance}; {@link Double#POSITIVE_INFINITY} for a miss. Must not allocate.
         */
        double test(long node, double boxDistance, double ox, double oy, double oz, double dx, double dy, double dz,
            double max);
    }

    int[] masks = new int[2];
    UiListener[] listeners = new UiListener[2];
    boolean[] capture = new boolean[2];
    int count;
    Shape shape = Shape.BOUNDS;
    PickMesh mesh;
    NarrowPhase narrow;
    CharSequence tooltip;
    Menu contextMenu;
    DataFormat<?> dragFormat;
    Object dragValue;
    int dragOperations;
    DataFormat<?>[] dropFormats = new DataFormat<?>[0];
    int dropOperations;

    private Interactive() {
    }

    /** An interactive node with no listeners yet: it is hit, hovered and passes events on. */
    @AllowAllocation("components are made when a scene is built")
    public static Interactive create() {
        return new Interactive();
    }

    /** An interactive node whose {@code listener} hears every pointer event. */
    @AllowAllocation("components are made when a scene is built")
    public static Interactive of(UiListener listener) {
        return create().on(EventType.POINTER_MASK, listener);
    }

    /** An interactive node whose {@code listener} hears events of {@code type}. */
    @AllowAllocation("components are made when a scene is built")
    public static Interactive of(EventType type, UiListener listener) {
        return create().on(type.bit(), listener);
    }

    /** An interactive node whose {@code listener} hears the event types in {@code mask} ({@link EventType#bit()} or'ed). */
    @AllowAllocation("components are made when a scene is built")
    public static Interactive of(int mask, UiListener listener) {
        return create().on(mask, listener);
    }

    /** Adds {@code listener} for events of {@code type} (its own, and its interactive descendants' that bubble). */
    public Interactive on(EventType type, UiListener listener) {
        return on(type.bit(), listener);
    }

    /** Adds {@code listener} for the event types in {@code mask} ({@link EventType#bit()} or'ed); returns this. */
    @AllowAllocation("components are made when a scene is built")
    public Interactive on(int mask, UiListener listener) {
        return add(mask, listener, false);
    }

    /** Adds {@code listener} for {@code mask} in the capture phase, before its descendants' listeners. */
    @AllowAllocation("components are made when a scene is built")
    public Interactive onCapture(int mask, UiListener listener) {
        return add(mask, listener, true);
    }

    @AllowAllocation("components are made when a scene is built")
    private Interactive add(int mask, UiListener listener, boolean inCapture) {
        if (count == listeners.length) {
            masks = Arrays.copyOf(masks, count * 2);
            listeners = Arrays.copyOf(listeners, count * 2);
            capture = Arrays.copyOf(capture, count * 2);
        }
        masks[count] = mask;
        listeners[count] = listener;
        capture[count++] = inCapture;
        return this;
    }

    /** Hit by its world box (the default); returns this. */
    public Interactive bounds() {
        shape = Shape.BOUNDS;
        mesh = null;
        narrow = null;
        return this;
    }

    /** Hit by the triangles of {@code pickMesh}, in the node's space; returns this. */
    public Interactive mesh(PickMesh pickMesh) {
        shape = Shape.MESH;
        mesh = pickMesh;
        narrow = null;
        return this;
    }

    /** Hit by a test of its own; returns this. */
    public Interactive shape(NarrowPhase test) {
        shape = Shape.CUSTOM;
        narrow = test;
        mesh = null;
        return this;
    }

    /** Shows {@code text} as a tooltip when a pointer rests on the node; returns this. */
    public Interactive tooltip(CharSequence text) {
        tooltip = text;
        return this;
    }

    /** Opens {@code menu} at the pointer when a context menu is asked for on the node; returns this. */
    public Interactive contextMenu(Menu menu) {
        contextMenu = menu;
        return this;
    }

    /**
     * Makes the node a drag source: dragging it starts a session carrying {@code value} in {@code format} with the
     * allowed {@link com.truesim.ui.core.event.DragOperation operations}; returns this.
     */
    public <T> Interactive draggable(DataFormat<T> format, T value, int operations) {
        dragFormat = format;
        dragValue = value;
        dragOperations = operations;
        return this;
    }

    /**
     * Makes the node a drop target for payloads in any of {@code formats} with any of {@code operations}: it accepts
     * them while dragged over (the {@code drop-target} state shows) and its DROP listener receives them; returns this.
     */
    @AllowAllocation("components are made when a scene is built")
    public Interactive acceptDrops(int operations, DataFormat<?>... formats) {
        dropFormats = formats.clone();
        dropOperations = operations;
        return this;
    }

    /** How the node is hit. */
    public Shape shapeKind() {
        return shape;
    }

    /** Its mesh, or null. */
    public PickMesh pickMesh() {
        return mesh;
    }

    /** How many listeners it has. */
    public int listenerCount() {
        return count;
    }
}
