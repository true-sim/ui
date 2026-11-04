package com.truesim.ui.core.event;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.Clipboard;
import com.truesim.ui.core.input.Cursor;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import java.util.Arrays;
import java.util.List;

/**
 * A UI's interaction (design 4.7): pointers, picking, event routing, hover, press and capture, clicks, long presses,
 * context menus, the wheel, tooltips' timing, drag and drop, focus per player with navigation and modal scopes, and
 * the routing of commands and text to the focused element. Fed once a frame by {@link #process}, at the start of the
 * frame before styles and layout, so whatever a listener changes shows in the same frame. Nothing here allocates per
 * frame: events are reused objects, listeners live in arrays, picking reads prebuilt grids.
 *
 * <p><b>Routing.</b> Events that bubble run DOM-like: capture listeners from the layer's root down to the target,
 * then the target's own, then bubble listeners back up; {@link UiEvent#consume()} stops the route. A pointer event no
 * element consumed passes to the next element under the pointer (Lemur's pass-through), across layers, until a
 * blocking layer. A press captures the pointer for the element that consumed it (or the front-most hit), so it gets
 * the moves, drags and the release even off itself. Disabled elements (state {@link UiState#DISABLED} on them or an
 * ancestor) get no events, but still stop the pointer.
 *
 * <p><b>States.</b> Hover (per pointer, the front-most hit and its ancestors), pressed, focused, focus-visible,
 * drop-target and dragging are set on the store's state bits, so {@code :hover}, {@code :pressed}, {@code :focused},
 * {@code :focus-visible}, {@code :drop-target} and {@code :dragging} styles apply without code in controls.
 *
 * <p><b>Focus.</b> One focus per player ({@link #MAX_PLAYERS}). Navigation asks, in order: the focused element's
 * explicit {@linkplain #neighbour neighbour}, its containers' {@link FocusTraversal}s from the nearest up, then the
 * default: a weighted spatial search for up, down, left and right, tree order for next and previous. Containers may
 * {@linkplain #rememberFocus remember} their last focused descendant. A {@linkplain #pushModal modal scope} (or a
 * {@linkplain Layer#modal() modal layer} showing something) traps focus; {@code CANCEL} closes the top-most one.
 *
 * <p><b>Commands and text.</b> Each {@link UiCommand} of the frame goes to its player's focused element as a
 * {@link EventType#COMMAND} event. Unconsumed, navigation commands move focus, {@code ACCEPT} clicks the focused
 * element, {@code CANCEL} closes the top-most modal scope and {@code CONTEXT_MENU} sends {@link EventType#CONTEXT}.
 * Committed text and the IME composition go to the focused {@linkplain #textInput text-taking} element of the
 * {@linkplain #textPlayer() keyboard's player}; the engine's {@link TextInputHost} is started when such an element
 * gains focus and stopped when it loses it.
 */
public final class Interaction {

    /** The most local players with their own focus. */
    public static final int MAX_PLAYERS = 8;
    /** The most elements a pointer is over at once (pass-through stops there). */
    public static final int MAX_HITS = 16;

    /** How long a press must be held still to be a long press. */
    public static final Tunable LONG_PRESS_DELAY = Tunable.of("ui.input.longPressDelay", 0.5f, 0.1f, 5,
        "Seconds a press is held still before it counts as a long press (context menu on touch).");
    /** The most time between clicks of a double click. */
    public static final Tunable DOUBLE_CLICK_TIME = Tunable.of("ui.input.doubleClickTime", 0.4f, 0.1f, 2,
        "Seconds between the clicks of a double click.");
    /** The farthest the pointer may move between clicks of a double click. */
    public static final Tunable DOUBLE_CLICK_DISTANCE = Tunable.of("ui.input.doubleClickDistance", 6, 0, 64,
        "UI pixels the pointer may move between the clicks of a double click.");
    /** How far a pressed mouse or pen moves before it drags. */
    public static final Tunable DRAG_THRESHOLD = Tunable.of("ui.input.dragThreshold", 5, 0, 64,
        "UI pixels a pressed mouse or pen moves before it starts a drag.");
    /** How far a pressed finger moves before it drags. */
    public static final Tunable TOUCH_DRAG_THRESHOLD = Tunable.of("ui.input.touchDragThreshold", 12, 0, 128,
        "UI pixels a finger moves before it starts a drag.");
    /** How long a pointer rests on an element before its tooltip shows. */
    public static final Tunable TOOLTIP_DELAY = Tunable.of("ui.input.tooltipDelay", 0.6f, 0, 10,
        "Seconds the pointer rests on an element before its tooltip shows.");
    /** The weight of the sideways distance in spatial focus navigation. */
    public static final Tunable FOCUS_SIDEWAYS_WEIGHT = Tunable.of("ui.focus.sidewaysWeight", 2, 0, 20,
        "How much sideways distance counts against a candidate in directional focus navigation.");

    // per-slot flags
    private static final int FOCUSABLE = 1;
    private static final int TEXT_INPUT = 2;
    private static final int REMEMBER = 4;
    private static final int IME_AREA = 8;
    private static final int OUTSIDE_WATCH = 16;
    private static final int KIND_SHIFT = 5; // bits 5-6: the text input kind
    private static final int KIND_MASK = 3 << KIND_SHIFT;

    private static final int DIRECTIONS = 6; // explicit neighbours: up, down, left, right, next, previous
    private static final int MAX_DEPTH = 16; // nested dispatches (a listener's focus change dispatches focus events)

    private final UiStore store;
    private final Layers layers;
    private final PickGeometry geometry;
    private final Picking picking;

    // per slot
    private int[] seenGen = new int[0];
    private int[] flags = new int[0];
    private int[] listenerHead = new int[0];
    private int[] listenerMask = new int[0];
    private byte[] pickOverride = new byte[0];
    private int[] hoverCount = new int[0];
    private int[] focusCount = new int[0];
    private int[] visibleCount = new int[0];
    private int[] visited = new int[0];
    private FocusTraversal[] traversals = new FocusTraversal[0];
    private Element[] neighbours = new Element[0];
    private Element[] memory = new Element[0];
    private float[] imeArea = new float[0];

    // listeners
    private UiListener[] fns = new UiListener[64];
    private int[] masks = new int[64];
    private boolean[] captures = new boolean[64];
    private int[] nexts = new int[64];
    private int listenerCount;
    private int freeListener = -1;
    private int[] watchers = new int[8];
    private int watcherCount;

    // pointers
    private final int[] pointerIds = new int[UiInput.MAX_POINTERS];
    private final boolean[] pointerActive = new boolean[UiInput.MAX_POINTERS];
    private final boolean[] pointerSeen = new boolean[UiInput.MAX_POINTERS];
    private final Pointer.Kind[] kinds = new Pointer.Kind[UiInput.MAX_POINTERS];
    private final int[] players = new int[UiInput.MAX_POINTERS];
    private final float[] lastX = new float[UiInput.MAX_POINTERS];
    private final float[] lastY = new float[UiInput.MAX_POINTERS];
    private final int[] lastButtons = new int[UiInput.MAX_POINTERS];
    private final int[] lastModifiers = new int[UiInput.MAX_POINTERS];
    private final int[][] hits = new int[UiInput.MAX_POINTERS][MAX_HITS];
    private final int[] hitCount = new int[UiInput.MAX_POINTERS];
    private final boolean[] blocked = new boolean[UiInput.MAX_POINTERS];
    private final int[][] hoverSlots = new int[UiInput.MAX_POINTERS][32];
    private final int[][] hoverGens = new int[UiInput.MAX_POINTERS][32];
    private final int[] hoverLength = new int[UiInput.MAX_POINTERS];
    private final int[] captured = new int[UiInput.MAX_POINTERS];
    private final int[] capturedGen = new int[UiInput.MAX_POINTERS];
    private final int[] pressButton = new int[UiInput.MAX_POINTERS];
    private final float[] pressX = new float[UiInput.MAX_POINTERS];
    private final float[] pressY = new float[UiInput.MAX_POINTERS];
    private final long[] pressTime = new long[UiInput.MAX_POINTERS];
    private final boolean[] dragging = new boolean[UiInput.MAX_POINTERS];
    private final boolean[] longFired = new boolean[UiInput.MAX_POINTERS];
    private final boolean[] suppressClick = new boolean[UiInput.MAX_POINTERS];
    private final long[] clickTime = new long[UiInput.MAX_POINTERS];
    private final float[] clickX = new float[UiInput.MAX_POINTERS];
    private final float[] clickY = new float[UiInput.MAX_POINTERS];
    private final int[] clickSlot = new int[UiInput.MAX_POINTERS];
    private final int[] clickCount = new int[UiInput.MAX_POINTERS];
    private final long[] restTime = new long[UiInput.MAX_POINTERS];
    private final boolean[] tooltipFired = new boolean[UiInput.MAX_POINTERS];
    private final DragSession[] sessions = new DragSession[UiInput.MAX_POINTERS];

    // focus
    private final int[] focused = new int[MAX_PLAYERS];
    private final int[] focusedGen = new int[MAX_PLAYERS];
    private final boolean[] focusShown = new boolean[MAX_PLAYERS];
    private boolean keyboardModality;
    private Element[] modalRoots = new Element[4];
    private Runnable[] modalCancels = new Runnable[4];
    private Element[] modalPrior = new Element[4 * MAX_PLAYERS];
    private final Element[] priorScratch = new Element[MAX_PLAYERS];
    private int modalCount;
    private int trapRoot = UiStore.NONE;
    private int[] candidates = new int[64];
    private int candidateCount;

    // text
    private TextInputHost textHost;
    private Clipboard clipboard = Clipboard.inMemory();
    private int textPlayer;
    private int textOwner = UiStore.NONE;
    private int textOwnerGen;
    private final float[] textArea = new float[4];
    private final StringBuilder composition = new StringBuilder();

    // dispatch
    private final UiEvent[] events = new UiEvent[MAX_DEPTH];
    private final int[][] paths = new int[MAX_DEPTH][32];
    private int[] chain = new int[32];
    private int depth;
    private int stamp;
    private long now;

    /** The interaction of {@code layers}' UI, reading element geometry from {@code geometry}. */
    @AllowAllocation("one per UI")
    public Interaction(Layers layers, PickGeometry geometry) {
        this.layers = layers;
        this.store = layers.store();
        this.geometry = geometry;
        for (int i = 0; i < MAX_DEPTH; i++) {
            events[i] = new UiEvent(this);
        }
        for (int i = 0; i < UiInput.MAX_POINTERS; i++) {
            sessions[i] = new DragSession();
            captured[i] = UiStore.NONE;
            clickSlot[i] = UiStore.NONE;
        }
        Arrays.fill(focused, UiStore.NONE);
        this.picking = new Picking(this);
        ensure();
    }

    /** The tunables interaction reads, for an engine to register as settings. */
    @AllowAllocation("asked once")
    public static List<Tunable> tunables() {
        return List.of(LONG_PRESS_DELAY, DOUBLE_CLICK_TIME, DOUBLE_CLICK_DISTANCE, DRAG_THRESHOLD,
            TOUCH_DRAG_THRESHOLD, TOOLTIP_DELAY, FOCUS_SIDEWAYS_WEIGHT);
    }

    // ================================================================ per-slot data

    @AllowAllocation("growing with the store, rarely")
    private void ensure() {
        int n = store.capacity();
        if (seenGen.length < n) {
            seenGen = Arrays.copyOf(seenGen, n);
            flags = Arrays.copyOf(flags, n);
            int old = listenerHead.length;
            listenerHead = Arrays.copyOf(listenerHead, n);
            Arrays.fill(listenerHead, old, n, -1);
            listenerMask = Arrays.copyOf(listenerMask, n);
            pickOverride = Arrays.copyOf(pickOverride, n);
            hoverCount = Arrays.copyOf(hoverCount, n);
            focusCount = Arrays.copyOf(focusCount, n);
            visibleCount = Arrays.copyOf(visibleCount, n);
            visited = Arrays.copyOf(visited, n);
            traversals = Arrays.copyOf(traversals, n);
            neighbours = Arrays.copyOf(neighbours, n * DIRECTIONS);
            memory = Arrays.copyOf(memory, n);
            imeArea = Arrays.copyOf(imeArea, n * 4);
        }
    }

    /** Makes {@code slot}'s data its current element's: a reused slot starts clean. */
    private void own(int slot) {
        if (slot >= seenGen.length) {
            ensure();
        }
        int gen = store.generation(slot);
        if (seenGen[slot] == gen) {
            return;
        }
        seenGen[slot] = gen;
        for (int l = listenerHead[slot]; l >= 0; ) {
            int next = nexts[l];
            freeListener(l);
            l = next;
        }
        if ((flags[slot] & OUTSIDE_WATCH) != 0) {
            removeWatcher(slot);
        }
        listenerHead[slot] = -1;
        listenerMask[slot] = 0;
        flags[slot] = 0;
        pickOverride[slot] = 0;
        hoverCount[slot] = 0;
        focusCount[slot] = 0;
        visibleCount[slot] = 0;
        traversals[slot] = null;
        for (int d = 0; d < DIRECTIONS; d++) {
            neighbours[slot * DIRECTIONS + d] = null;
        }
        memory[slot] = null;
    }

    private int slotOf(Element e) {
        int slot = e.slot();
        own(slot);
        return slot;
    }

    // ================================================================ listeners

    /** Listens to events of {@code type} at {@code element} (its own and its descendants' that bubble). */
    public void on(Element element, EventType type, UiListener listener) {
        add(element, type.bit(), listener, false);
    }

    /** Listens to events of the types in {@code mask} ({@link EventType#bit()} or'ed) at {@code element}. */
    public void on(Element element, int mask, UiListener listener) {
        add(element, mask, listener, false);
    }

    /** Listens to events of {@code type} in their capture phase, before the target's own listeners. */
    public void onCapture(Element element, EventType type, UiListener listener) {
        add(element, type.bit(), listener, true);
    }

    /** Listens to events of the types in {@code mask} in their capture phase. */
    public void onCapture(Element element, int mask, UiListener listener) {
        add(element, mask, listener, true);
    }

    /** Removes every registration of {@code listener} at {@code element}. */
    public void off(Element element, UiListener listener) {
        int slot = slotOf(element);
        int previous = -1;
        for (int l = listenerHead[slot]; l >= 0; ) {
            int next = nexts[l];
            if (fns[l] == listener) {
                if (previous < 0) {
                    listenerHead[slot] = next;
                } else {
                    nexts[previous] = next;
                }
                freeListener(l);
            } else {
                previous = l;
            }
            l = next;
        }
        listenersChanged(slot);
    }

    private void add(Element element, int mask, UiListener listener, boolean capture) {
        int slot = slotOf(element);
        if (listener == null) {
            throw new IllegalArgumentException("no listener");
        }
        int l = allocateListener();
        fns[l] = listener;
        masks[l] = mask;
        captures[l] = capture;
        nexts[l] = -1;
        // appended, so listeners run in the order they were added
        int last = listenerHead[slot];
        if (last < 0) {
            listenerHead[slot] = l;
        } else {
            while (nexts[last] >= 0) {
                last = nexts[last];
            }
            nexts[last] = l;
        }
        listenersChanged(slot);
    }

    private void listenersChanged(int slot) {
        int mask = 0;
        for (int l = listenerHead[slot]; l >= 0; l = nexts[l]) {
            mask |= masks[l];
        }
        int before = listenerMask[slot];
        listenerMask[slot] = mask;
        boolean watch = (mask & EventType.PRESS_OUTSIDE.bit()) != 0;
        if (watch != ((flags[slot] & OUTSIDE_WATCH) != 0)) {
            if (watch) {
                addWatcher(slot);
            } else {
                removeWatcher(slot);
            }
        }
        if (((before ^ mask) & EventType.POINTER_MASK) != 0) {
            picking.invalidate(slot);
        }
    }

    @AllowAllocation("growing with the listeners, rarely")
    private int allocateListener() {
        if (freeListener >= 0) {
            int l = freeListener;
            freeListener = nexts[l];
            return l;
        }
        if (listenerCount == fns.length) {
            int n = listenerCount * 2;
            fns = Arrays.copyOf(fns, n);
            masks = Arrays.copyOf(masks, n);
            captures = Arrays.copyOf(captures, n);
            nexts = Arrays.copyOf(nexts, n);
        }
        return listenerCount++;
    }

    private void freeListener(int l) {
        fns[l] = null;
        masks[l] = 0;
        nexts[l] = freeListener;
        freeListener = l;
    }

    @AllowAllocation("growing with the pop-ups, rarely")
    private void addWatcher(int slot) {
        if (watcherCount == watchers.length) {
            watchers = Arrays.copyOf(watchers, watcherCount * 2);
        }
        watchers[watcherCount++] = slot;
        flags[slot] |= OUTSIDE_WATCH;
    }

    private void removeWatcher(int slot) {
        flags[slot] &= ~OUTSIDE_WATCH;
        for (int i = 0; i < watcherCount; i++) {
            if (watchers[i] == slot) {
                watchers[i] = watchers[--watcherCount];
                return;
            }
        }
    }

    /**
     * What assistive technology may do with {@code slot} ({@link com.truesim.ui.core.Semantics} action bits), from its
     * listeners and flags: activate (it handles clicks), focus, show a menu, scroll, drag, take drops, edit text.
     */
    public int semanticActions(int slot) {
        own(slot);
        int mask = listenerMask[slot];
        int actions = 0;
        if ((mask & (EventType.CLICK.bit() | EventType.PRESS.bit())) != 0) {
            actions |= com.truesim.ui.core.Semantics.ACTIVATE;
        }
        if ((flags[slot] & FOCUSABLE) != 0) {
            actions |= com.truesim.ui.core.Semantics.FOCUS;
        }
        if ((mask & EventType.CONTEXT.bit()) != 0) {
            actions |= com.truesim.ui.core.Semantics.SHOW_MENU;
        }
        if ((mask & EventType.WHEEL.bit()) != 0) {
            actions |= com.truesim.ui.core.Semantics.SCROLL;
        }
        if ((mask & EventType.DRAG_START.bit()) != 0) {
            actions |= com.truesim.ui.core.Semantics.DRAG;
        }
        if ((mask & (EventType.DROP_OVER.bit() | EventType.DROP.bit())) != 0) {
            actions |= com.truesim.ui.core.Semantics.DROP;
        }
        if ((flags[slot] & TEXT_INPUT) != 0) {
            actions |= com.truesim.ui.core.Semantics.EDIT_TEXT;
        }
        return actions;
    }

    /** The event types {@code element} listens to ({@link EventType#bit()} or'ed). */
    public int listening(Element element) {
        int slot = slotOf(element); // first: it may grow the arrays
        return listenerMask[slot];
    }

    // ================================================================ pickability and flags

    /**
     * Overrides the style's {@code pointer-events} for {@code element}: {@link PointerEvents#ALL} makes it pickable
     * without listeners, {@link PointerEvents#NONE} never pickable, {@link PointerEvents#AUTO} leaves it to the style.
     */
    public void pickable(Element element, PointerEvents value) {
        int slot = slotOf(element);
        pickOverride[slot] = (byte) value.ordinal();
        picking.invalidate(slot);
    }

    /**
     * Whether pointers hit {@code slot} now: its pointer-events (code, then style), listeners and focusability; what
     * the hit grids hold.
     */
    public boolean pickable(int slot) {
        return pickableNow(slot, geometry);
    }

    /** Whether pointers hit {@code slot} now: its pointer-events, listeners and focusability (for picking). */
    boolean pickableNow(int slot, PickGeometry geo) {
        own(slot);
        int override = pickOverride[slot];
        if (override == PointerEvents.ALL.ordinal()) {
            return true;
        }
        if (override == PointerEvents.NONE.ordinal()) {
            return false;
        }
        PointerEvents style = geo.pointerEvents(slot);
        if (style != PointerEvents.AUTO) {
            return style == PointerEvents.ALL;
        }
        return (listenerMask[slot] & EventType.POINTER_MASK) != 0 || (flags[slot] & FOCUSABLE) != 0;
    }

    /** Makes {@code element} focusable (by pointer, navigation and {@link #focus}) or not. */
    public void focusable(Element element, boolean on) {
        int slot = slotOf(element);
        setFlag(slot, FOCUSABLE, on);
        picking.invalidate(slot);
    }

    /** Whether {@code element} is focusable. */
    public boolean focusable(Element element) {
        int slot = slotOf(element); // first: it may grow the arrays
        return (flags[slot] & FOCUSABLE) != 0;
    }

    /**
     * Marks {@code element} as taking text: while it has the keyboard player's focus it gets {@link EventType#TEXT}
     * and {@link EventType#COMPOSITION} events and the engine's text input (and IME) runs. It is made focusable too.
     */
    public void textInput(Element element, boolean on) {
        int slot = slotOf(element);
        setFlag(slot, TEXT_INPUT, on);
        if (on) {
            setFlag(slot, FOCUSABLE, true);
            picking.invalidate(slot);
        }
    }

    /**
     * The kind of text {@code element} takes ({@link TextInputHost#TEXT}, {@link TextInputHost#PASSWORD},
     * {@link TextInputHost#NUMBER}, {@link TextInputHost#MULTILINE}): the engine's text input starts with it (a
     * password field gets no IME). Takes effect the next time text input starts for it.
     */
    public void textInputKind(Element element, int kind) {
        int slot = slotOf(element);
        flags[slot] = flags[slot] & ~KIND_MASK | (kind & 3) << KIND_SHIFT;
    }

    /** The kind of text {@code element} takes. */
    public int textInputKind(Element element) {
        return (flags[slotOf(element)] & KIND_MASK) >>> KIND_SHIFT;
    }

    /**
     * Where the IME's candidate window goes while {@code element} takes text: the caret's rectangle in UI pixels.
     * Without one, the element's own rectangle is used. Setting it while text input runs moves the IME at once.
     */
    public void imeArea(Element element, float x, float y, float width, float height) {
        int slot = slotOf(element);
        int o = slot * 4;
        imeArea[o] = x;
        imeArea[o + 1] = y;
        imeArea[o + 2] = width;
        imeArea[o + 3] = height;
        setFlag(slot, IME_AREA, true);
        if (slot == textOwner && textHost != null) {
            area(slot);
            textHost.area(textArea[0], textArea[1], textArea[2], textArea[3]);
        }
    }

    private void setFlag(int slot, int bit, boolean on) {
        flags[slot] = on ? flags[slot] | bit : flags[slot] & ~bit;
    }

    // ================================================================ the frame

    /**
     * Processes this frame's input at time {@code nanos} (the UI clock, real time): pointers are picked against the
     * layout last drawn, events dispatched, states set, focus moved, commands and text routed. Call at the start of
     * the frame, before styles and layout, so the effects show in the same frame.
     */
    public void process(UiInput input, long nanos) {
        now = nanos;
        ensure();
        validate();
        for (int i = 0; i < UiInput.MAX_POINTERS; i++) {
            pointerSeen[i] = false;
        }
        for (int i = 0; i < input.pointerCount(); i++) {
            Pointer pointer = input.pointerAt(i);
            int p = pointerIndex(pointer);
            pointerSeen[p] = true;
            pointer(p, pointer);
        }
        for (int p = 0; p < UiInput.MAX_POINTERS; p++) {
            if (pointerActive[p] && !pointerSeen[p]) {
                removePointer(p);
            }
        }
        for (int i = 0; i < input.commandCount(); i++) {
            command(input.command(i), input.player(i), input.flags(i));
        }
        text(input);
        syncTextInput();
    }

    /** Clears focus, capture and modal scopes whose elements were destroyed, disabled or taken out of the tree. */
    private void validate() {
        for (int m = modalCount - 1; m >= 0; m--) {
            Element root = modalRoots[m];
            if (!root.alive() || store.rootOf(root.slot()) == UiStore.NONE) {
                removeModal(m, true);
            }
        }
        updateTrap();
        for (int pl = 0; pl < MAX_PLAYERS; pl++) {
            int f = focused[pl];
            if (f == UiStore.NONE) {
                continue;
            }
            if (!store.alive(f) || store.generation(f) != focusedGen[pl] || !focusableNow(f) || !inTrap(f)) {
                blur(pl);
            }
        }
        if (trapRoot != UiStore.NONE && focused[0] == UiStore.NONE) {
            int first = firstIn(trapRoot, false);
            if (first != UiStore.NONE) {
                focusSlot(0, remembering(UiStore.NONE, first), keyboardModality);
            }
        }
        for (int p = 0; p < UiInput.MAX_POINTERS; p++) {
            int c = captured[p];
            if (c != UiStore.NONE && (!store.alive(c) || store.generation(c) != capturedGen[p])) {
                captured[p] = UiStore.NONE;
            }
        }
    }

    private int pointerIndex(Pointer pointer) {
        int free = -1;
        for (int p = 0; p < UiInput.MAX_POINTERS; p++) {
            if (pointerActive[p] && pointerIds[p] == pointer.id()) {
                return p;
            }
            if (!pointerActive[p] && free < 0) {
                free = p;
            }
        }
        pointerActive[free] = true;
        pointerIds[free] = pointer.id();
        kinds[free] = pointer.kind();
        lastX[free] = pointer.x();
        lastY[free] = pointer.y();
        lastButtons[free] = 0;
        hitCount[free] = 0;
        hoverLength[free] = 0;
        captured[free] = UiStore.NONE;
        dragging[free] = false;
        clickCount[free] = 0;
        clickSlot[free] = UiStore.NONE;
        restTime[free] = now;
        tooltipFired[free] = false;
        return free;
    }

    // ================================================================ pointers

    private void pointer(int p, Pointer pointer) {
        float x = pointer.x();
        float y = pointer.y();
        players[p] = pointer.player();
        kinds[p] = pointer.kind();
        lastModifiers[p] = pointer.modifiers();
        int n = picking.pick(x, y, hits[p]);
        hitCount[p] = n;
        blocked[p] = picking.blockedBy() >= 0;
        int top = n > 0 ? hits[p][0] : UiStore.NONE;
        if (hover(p, top, x, y)) {
            restTime[p] = now;
            tooltipFired[p] = false;
        }

        float dx = x - lastX[p];
        float dy = y - lastY[p];
        if (dx != 0 || dy != 0) {
            restTime[p] = now;
            int c = captured[p];
            int to = c != UiStore.NONE ? c : top;
            if (to != UiStore.NONE) {
                UiEvent e = begin(EventType.MOVE, to);
                try {
                    pointerFields(e, p, x, y, pointer.buttons());
                    e.deltaX = dx;
                    e.deltaY = dy;
                    route(e);
                } finally {
                    end();
                }
            }
            if (c != UiStore.NONE && pressButton[p] != 0) {
                drag(p, x, y, dx, dy, pointer.buttons());
            }
        }

        int buttons = pointer.buttons();
        int down = buttons & ~lastButtons[p];
        int up = lastButtons[p] & ~buttons;
        for (int bit = Pointer.PRIMARY; bit <= Pointer.MIDDLE; bit <<= 1) {
            if ((down & bit) != 0) {
                press(p, bit, x, y, buttons);
            }
        }
        for (int bit = Pointer.PRIMARY; bit <= Pointer.MIDDLE; bit <<= 1) {
            if ((up & bit) != 0) {
                release(p, bit, x, y, buttons);
            }
        }
        if (pointer.scrollX() != 0 || pointer.scrollY() != 0) {
            UiEvent e = begin(EventType.WHEEL, UiStore.NONE);
            try {
                pointerFields(e, p, x, y, buttons);
                e.scrollX = pointer.scrollX();
                e.scrollY = pointer.scrollY();
                passThrough(e, p);
            } finally {
                end();
            }
        }
        longPress(p, x, y, buttons);
        tooltip(p, top, x, y, buttons);
        lastX[p] = x;
        lastY[p] = y;
        lastButtons[p] = buttons;
    }

    private void pointerFields(UiEvent e, int p, float x, float y, int buttons) {
        e.pointerId = pointerIds[p];
        e.pointerKind = kinds[p];
        e.player = players[p];
        e.x = x;
        e.y = y;
        e.buttons = buttons;
        e.modifiers = lastModifiers[p];
        e.pressX = pressX[p];
        e.pressY = pressY[p];
    }

    /** Updates pointer {@code p}'s hover chain for its new front-most hit; true if that hit changed. */
    private boolean hover(int p, int top, float x, float y) {
        int[] slots = hoverSlots[p];
        int[] gens = hoverGens[p];
        int length = hoverLength[p];
        if (length > 0 && slots[0] == top && store.alive(top) && gens[0] == store.generation(top)) {
            return false;
        }
        if (length == 0 && top == UiStore.NONE) {
            return false;
        }
        int freshLength = 0;
        for (int s = top; s != UiStore.NONE; s = store.parent(s)) {
            if (freshLength == chain.length) {
                growChain();
            }
            chain[freshLength++] = s;
        }
        int[] fresh = chain;
        // exits: deepest first
        for (int i = 0; i < length; i++) {
            int s = slots[i];
            if (!store.alive(s) || store.generation(s) != gens[i]) {
                continue;
            }
            if (!contains(fresh, freshLength, s)) {
                own(s);
                if (--hoverCount[s] == 0) {
                    store.state(s, UiState.HOVER, false);
                }
                targetOnly(EventType.EXIT, s, p, x, y, top);
            }
        }
        // enters: outermost first
        int previousTop = length > 0 ? slots[0] : UiStore.NONE;
        for (int i = freshLength - 1; i >= 0; i--) {
            int s = fresh[i];
            if (!containsLive(slots, gens, length, s)) {
                own(s);
                if (hoverCount[s]++ == 0) {
                    store.state(s, UiState.HOVER, true);
                }
                targetOnly(EventType.ENTER, s, p, x, y, previousTop);
            }
        }
        if (hoverSlots[p].length < freshLength) {
            growHover(p, freshLength);
        }
        slots = hoverSlots[p];
        gens = hoverGens[p];
        for (int i = 0; i < freshLength; i++) {
            slots[i] = fresh[i];
            gens[i] = store.generation(fresh[i]);
        }
        hoverLength[p] = freshLength;
        return true;
    }

    @AllowAllocation("deeper trees than ever before, rarely")
    private void growChain() {
        chain = Arrays.copyOf(chain, chain.length * 2);
    }

    @AllowAllocation("deeper trees than ever before, rarely")
    private void growHover(int p, int n) {
        hoverSlots[p] = Arrays.copyOf(hoverSlots[p], n);
        hoverGens[p] = Arrays.copyOf(hoverGens[p], n);
    }

    private boolean containsLive(int[] slots, int[] gens, int length, int s) {
        for (int i = 0; i < length; i++) {
            if (slots[i] == s && gens[i] == store.generation(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(int[] slots, int length, int s) {
        for (int i = 0; i < length; i++) {
            if (slots[i] == s) {
                return true;
            }
        }
        return false;
    }

    private void targetOnly(EventType type, int target, int p, float x, float y, int related) {
        UiEvent e = begin(type, target);
        try {
            pointerFields(e, p, x, y, lastButtons[p]);
            e.related = related;
            route(e);
        } finally {
            end();
        }
    }

    private void press(int p, int bit, float x, float y, int buttons) {
        int n = hitCount[p];
        int top = n > 0 ? hits[p][0] : UiStore.NONE;
        outside(p, x, y, buttons);
        int consumer = UiStore.NONE;
        UiEvent e = begin(EventType.PRESS, UiStore.NONE);
        try {
            pointerFields(e, p, x, y, buttons);
            e.button = bit;
            consumer = passThrough(e, p);
        } finally {
            end();
        }
        boolean first = captured[p] == UiStore.NONE;
        int target = consumer != UiStore.NONE ? consumer : top;
        if (first && target != UiStore.NONE && !disabled(target)) {
            capture(p, target);
            pressButton[p] = bit;
            pressX[p] = x;
            pressY[p] = y;
            pressTime[p] = now;
            dragging[p] = false;
            longFired[p] = false;
            suppressClick[p] = false;
            store.state(target, UiState.PRESSED, true);
        } else if (first) {
            pressButton[p] = 0;
        }
        if (bit == Pointer.PRIMARY) {
            keyboardModality = false;
            int f = target == UiStore.NONE || disabled(target) ? UiStore.NONE : focusableAncestor(target);
            if (f != UiStore.NONE) {
                focusSlot(players[p], f, false);
            } else if (trapRoot == UiStore.NONE && (top == UiStore.NONE || !disabled(top))) {
                blur(players[p]);
            }
        }
        if (bit == Pointer.SECONDARY) {
            UiEvent c = begin(EventType.CONTEXT, UiStore.NONE);
            try {
                pointerFields(c, p, x, y, buttons);
                c.button = bit;
                passThrough(c, p);
            } finally {
                end();
            }
        }
        tooltipFired[p] = true; // no tooltip after a press until the pointer moves to another element
    }

    private void outside(int p, float x, float y, int buttons) {
        for (int i = watcherCount - 1; i >= 0; i--) {
            if (i >= watcherCount) {
                continue; // a listener removed watchers
            }
            int w = watchers[i];
            if (!store.alive(w) || store.generation(w) != seenGen[w] || hitInside(p, w)) {
                continue;
            }
            UiEvent e = begin(EventType.PRESS_OUTSIDE, w);
            try {
                pointerFields(e, p, x, y, buttons);
                route(e);
            } finally {
                end();
            }
        }
    }

    /** Whether one of pointer {@code p}'s hits is {@code slot} or under it. */
    private boolean hitInside(int p, int slot) {
        for (int i = 0; i < hitCount[p]; i++) {
            if (isAncestorOrSelf(slot, hits[p][i])) {
                return true;
            }
        }
        return false;
    }

    private void release(int p, int bit, float x, float y, int buttons) {
        int c = captured[p];
        int top = hitCount[p] > 0 ? hits[p][0] : UiStore.NONE;
        int target = c != UiStore.NONE ? c : top;
        if (target != UiStore.NONE) {
            UiEvent e = begin(EventType.RELEASE, target);
            try {
                pointerFields(e, p, x, y, buttons);
                e.button = bit;
                route(e);
            } finally {
                end();
            }
        }
        if (bit != pressButton[p] || c == UiStore.NONE) {
            return;
        }
        if (dragging[p]) {
            endDrag(p, x, y, buttons, false);
        } else if (bit == Pointer.PRIMARY && !suppressClick[p] && hitInside(p, c)) {
            boolean again = clickSlot[p] == c && now - clickTime[p] <= seconds(DOUBLE_CLICK_TIME.get())
                && squared(x - clickX[p], y - clickY[p]) <= DOUBLE_CLICK_DISTANCE.get() * DOUBLE_CLICK_DISTANCE.get();
            clickCount[p] = again ? clickCount[p] + 1 : 1;
            clickTime[p] = now;
            clickX[p] = x;
            clickY[p] = y;
            clickSlot[p] = c;
            UiEvent e = begin(EventType.CLICK, c);
            try {
                pointerFields(e, p, x, y, buttons);
                e.button = bit;
                e.clickCount = clickCount[p];
                route(e);
            } finally {
                end();
            }
        }
        releaseCapture(p);
    }

    private void capture(int p, int slot) {
        if (captured[p] != UiStore.NONE && captured[p] != slot) {
            releaseCapture(p);
        }
        captured[p] = slot;
        capturedGen[p] = store.generation(slot);
    }

    private void releaseCapture(int p) {
        int c = captured[p];
        captured[p] = UiStore.NONE;
        pressButton[p] = 0;
        dragging[p] = false;
        if (c != UiStore.NONE && store.alive(c) && store.generation(c) == capturedGen[p] && !pressedElsewhere(c)) {
            store.state(c, UiState.PRESSED, false);
        }
    }

    private boolean pressedElsewhere(int slot) {
        for (int p = 0; p < UiInput.MAX_POINTERS; p++) {
            if (pointerActive[p] && captured[p] == slot) {
                return true;
            }
        }
        return false;
    }

    private void longPress(int p, float x, float y, int buttons) {
        int c = captured[p];
        if (c == UiStore.NONE || pressButton[p] != Pointer.PRIMARY || dragging[p] || longFired[p]
            || now - pressTime[p] < seconds(LONG_PRESS_DELAY.get())) {
            return;
        }
        longFired[p] = true;
        UiEvent e = begin(EventType.LONG_PRESS, c);
        try {
            pointerFields(e, p, x, y, buttons);
            route(e);
            suppressClick[p] |= e.consumed;
        } finally {
            end();
        }
        if (kinds[p] == Pointer.Kind.TOUCH || kinds[p] == Pointer.Kind.PEN) {
            UiEvent ctx = begin(EventType.CONTEXT, c);
            try {
                pointerFields(ctx, p, x, y, buttons);
                route(ctx);
                suppressClick[p] |= ctx.consumed;
            } finally {
                end();
            }
        }
    }

    private void tooltip(int p, int top, float x, float y, int buttons) {
        if (top == UiStore.NONE || buttons != 0 || tooltipFired[p] || dragging[p]
            || now - restTime[p] < seconds(TOOLTIP_DELAY.get())) {
            return;
        }
        tooltipFired[p] = true;
        UiEvent e = begin(EventType.TOOLTIP, top);
        try {
            pointerFields(e, p, x, y, buttons);
            route(e);
        } finally {
            end();
        }
    }

    private void removePointer(int p) {
        float x = lastX[p];
        float y = lastY[p];
        if (lastButtons[p] != 0) {
            for (int bit = Pointer.PRIMARY; bit <= Pointer.MIDDLE; bit <<= 1) {
                if ((lastButtons[p] & bit) != 0) {
                    lastButtons[p] &= ~bit;
                    release(p, bit, x, y, lastButtons[p]);
                }
            }
        }
        if (sessions[p].active()) {
            endDrag(p, x, y, 0, true);
        }
        hitCount[p] = 0;
        hover(p, UiStore.NONE, x, y);
        releaseCapture(p);
        pointerActive[p] = false;
    }

    // ================================================================ dragging

    private void drag(int p, float x, float y, float dx, float dy, int buttons) {
        int c = captured[p];
        if (!dragging[p]) {
            float threshold = kinds[p] == Pointer.Kind.TOUCH ? TOUCH_DRAG_THRESHOLD.get() : DRAG_THRESHOLD.get();
            if (squared(x - pressX[p], y - pressY[p]) <= threshold * threshold || longFired[p] && suppressClick[p]) {
                return;
            }
            dragging[p] = true;
            UiEvent e = begin(EventType.DRAG_START, c);
            try {
                pointerFields(e, p, x, y, buttons);
                e.deltaX = dx;
                e.deltaY = dy;
                route(e);
            } finally {
                end();
            }
            if (sessions[p].active()) {
                store.state(sessions[p].source().slot(), UiState.DRAGGING, true);
                dropTarget(p, x, y, buttons);
            }
            return;
        }
        UiEvent e = begin(EventType.DRAG, c);
        try {
            pointerFields(e, p, x, y, buttons);
            e.deltaX = dx;
            e.deltaY = dy;
            e.drag = sessions[p].active() ? sessions[p] : null;
            route(e);
        } finally {
            end();
        }
        if (sessions[p].active()) {
            dropTarget(p, x, y, buttons);
        }
    }

    /** Starts a drag session for pointer {@code pointerId} from {@code slot}; for {@link UiEvent#startDrag}. */
    DragSession beginDrag(int pointerId, int slot, int operations, float x, float y) {
        int p = indexOfPointer(pointerId);
        if (p < 0) {
            throw new IllegalStateException("no pointer " + pointerId);
        }
        DragSession s = sessions[p];
        s.begin(pointerId, store.element(slot), operations, x, y);
        return s;
    }

    private int indexOfPointer(int pointerId) {
        for (int p = 0; p < UiInput.MAX_POINTERS; p++) {
            if (pointerActive[p] && pointerIds[p] == pointerId) {
                return p;
            }
        }
        return -1;
    }

    /** Finds the drop target under the pointer through picking: the element that accepts in DROP_OVER. */
    private void dropTarget(int p, float x, float y, int buttons) {
        DragSession s = sessions[p];
        s.moveTo(x, y);
        int accepted = UiStore.NONE;
        int operations = DragOperation.NONE;
        UiEvent e = begin(EventType.DROP_OVER, UiStore.NONE);
        try {
            pointerFields(e, p, x, y, buttons);
            e.drag = s;
            passThrough(e, p);
            accepted = e.acceptedBy;
            operations = e.dropAccepted;
        } finally {
            end();
        }
        int operation = accepted == UiStore.NONE ? DragOperation.NONE
            : DragOperation.preferred(operations & s.allowed(), lastModifiers[p]);
        if (operation == DragOperation.NONE) {
            accepted = UiStore.NONE;
        }
        Element old = s.target();
        int oldSlot = old != null && old.alive() ? old.slot() : UiStore.NONE;
        s.operation(operation);
        if (oldSlot == accepted) {
            return;
        }
        if (oldSlot != UiStore.NONE) {
            store.state(oldSlot, UiState.DROP_TARGET, false);
            dropEvent(EventType.DROP_LEAVE, oldSlot, p, x, y, buttons);
        }
        s.target(accepted == UiStore.NONE ? null : store.element(accepted));
        if (accepted != UiStore.NONE) {
            store.state(accepted, UiState.DROP_TARGET, true);
            dropEvent(EventType.DROP_ENTER, accepted, p, x, y, buttons);
        }
    }

    private void dropEvent(EventType type, int target, int p, float x, float y, int buttons) {
        UiEvent e = begin(type, target);
        try {
            pointerFields(e, p, x, y, buttons);
            e.drag = sessions[p];
            route(e);
        } finally {
            end();
        }
    }

    private void endDrag(int p, float x, float y, int buttons, boolean cancel) {
        DragSession s = sessions[p];
        int c = captured[p];
        if (s.active()) {
            Element target = s.target();
            int t = target != null && target.alive() ? target.slot() : UiStore.NONE;
            if (t != UiStore.NONE) {
                store.state(t, UiState.DROP_TARGET, false);
            }
            if (cancel || t == UiStore.NONE) {
                s.operation(DragOperation.NONE);
                if (t != UiStore.NONE) {
                    dropEvent(EventType.DROP_LEAVE, t, p, x, y, buttons);
                }
            } else {
                dropEvent(EventType.DROP, t, p, x, y, buttons);
            }
            Element source = s.source();
            if (source != null && source.alive()) {
                store.state(source.slot(), UiState.DRAGGING, false);
            }
        }
        if (c != UiStore.NONE) {
            UiEvent e = begin(EventType.DRAG_END, c);
            try {
                pointerFields(e, p, x, y, buttons);
                e.drag = s.active() ? s : null;
                route(e);
            } finally {
                end();
            }
        }
        s.clear();
        dragging[p] = false;
    }

    /** The drag session of pointer {@code pointerId}, active or not; null for an unknown pointer. */
    public DragSession dragSession(int pointerId) {
        int p = indexOfPointer(pointerId);
        return p < 0 ? null : sessions[p];
    }

    /** Pointer slot {@code index}'s drag session (0 to {@link UiInput#MAX_POINTERS}): the UI frame moves previews. */
    public DragSession sessionAt(int index) {
        return sessions[index];
    }

    /** Cancels pointer {@code pointerId}'s drag (session or not): DROP_LEAVE, DRAG_END with no operation. */
    public void cancelDrag(int pointerId) {
        int p = indexOfPointer(pointerId);
        if (p >= 0 && dragging[p]) {
            endDrag(p, lastX[p], lastY[p], lastButtons[p], true);
            suppressClick[p] = true;
        }
    }

    // ================================================================ dispatch

    private UiEvent begin(EventType type, int target) {
        if (depth == MAX_DEPTH) {
            throw new IllegalStateException("events nested deeper than " + MAX_DEPTH);
        }
        UiEvent e = events[depth++];
        e.reset(type, target, now);
        return e;
    }

    private void end() {
        depth--;
    }

    /**
     * Routes {@code e} over pointer {@code p}'s hits, front-most first, until one consumes it: each hit runs its full
     * route, skipping elements an earlier hit's route visited. Returns the element whose listener consumed it, or
     * NONE. A disabled hit swallows the event.
     */
    private int passThrough(UiEvent e, int p) {
        int pass = ++stamp;
        int n = hitCount[p];
        for (int i = 0; i < n; i++) {
            int target = hits[p][i];
            if (!store.alive(target)) {
                continue;
            }
            if (disabled(target)) {
                return UiStore.NONE;
            }
            e.target = target;
            e.current = target;
            routeVisiting(e, pass);
            if (e.consumed) {
                return e.current;
            }
        }
        return UiStore.NONE;
    }

    /** Routes {@code e} to its target (one route, no pass-through). Disabled targets get nothing. */
    private void route(UiEvent e) {
        if (disabled(e.target)) {
            return;
        }
        routeVisiting(e, ++stamp);
    }

    private void routeVisiting(UiEvent e, int pass) {
        int target = e.target;
        own(target);
        if (!e.type.bubbles()) {
            e.phase = Phase.TARGET;
            e.current = target;
            call(target, e, true);
            if (!e.consumed) {
                call(target, e, false);
            }
            return;
        }
        int[] path = paths[depth - 1];
        int n = 0;
        for (int s = target; s != UiStore.NONE; s = store.parent(s)) {
            if (n == path.length) {
                path = growPath(depth - 1);
            }
            path[n++] = s;
        }
        e.phase = Phase.CAPTURE;
        for (int i = n - 1; i > 0; i--) {
            int s = path[i];
            if (visited[s] != pass) {
                e.current = s;
                call(s, e, true);
                if (e.consumed) {
                    return;
                }
            }
        }
        e.phase = Phase.TARGET;
        e.current = target;
        call(target, e, true);
        if (!e.consumed) {
            call(target, e, false);
        }
        visited[target] = pass;
        if (e.consumed) {
            return;
        }
        e.phase = Phase.BUBBLE;
        for (int i = 1; i < n; i++) {
            int s = path[i];
            if (visited[s] != pass) {
                visited[s] = pass;
                e.current = s;
                call(s, e, false);
                if (e.consumed) {
                    return;
                }
            }
        }
    }

    @AllowAllocation("deeper trees than ever before, rarely")
    private int[] growPath(int level) {
        paths[level] = Arrays.copyOf(paths[level], paths[level].length * 2);
        return paths[level];
    }

    private void call(int slot, UiEvent e, boolean capture) {
        own(slot);
        int bit = e.type.bit();
        if ((listenerMask[slot] & bit) == 0) {
            return;
        }
        for (int l = listenerHead[slot]; l >= 0; ) {
            int next = nexts[l];
            if ((masks[l] & bit) != 0 && captures[l] == capture) {
                fns[l].handle(e);
            }
            l = next;
        }
    }

    /** Whether {@code slot} or an ancestor is disabled. */
    public boolean disabled(int slot) {
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if ((store.state(s) & UiState.DISABLED) != 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isAncestorOrSelf(int ancestor, int slot) {
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (s == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sends a custom event of {@code type} to {@code element} along the usual route (a control raising CLICK for its
     * own reasons, a test); returns whether a listener consumed it.
     */
    public boolean dispatch(EventType type, Element element, int player) {
        UiEvent e = begin(type, element.slot());
        try {
            e.player = player;
            route(e);
            return e.consumed;
        } finally {
            end();
        }
    }

    // ================================================================ focus

    /** Focuses {@code element} for player 0; false if it cannot take focus (or a modal scope keeps it out). */
    public boolean focus(Element element) {
        return focus(element, 0);
    }

    /** Focuses {@code element} for {@code player}; the focus ring shows if the last input was navigation. */
    public boolean focus(Element element, int player) {
        return focusSlot(player, slotOf(element), keyboardModality);
    }

    /** The element {@code player} focuses, or null. */
    public Element focused(int player) {
        int f = focused[player];
        return f == UiStore.NONE || !store.alive(f) || store.generation(f) != focusedGen[player] ? null
            : store.element(f);
    }

    /** The slot {@code player} focuses, or NONE. */
    public int focusedSlot(int player) {
        Element e = focused(player);
        return e == null ? UiStore.NONE : e.slot();
    }

    /** Takes focus from {@code player}'s element. */
    public void blur(int player) {
        int old = focused[player];
        if (old == UiStore.NONE) {
            return;
        }
        boolean shown = focusShown[player];
        focused[player] = UiStore.NONE;
        if (!store.alive(old) || store.generation(old) != focusedGen[player]) {
            return; // destroyed: its counts went with it
        }
        unfocus(old, shown);
        focusEvent(EventType.FOCUS_OUT, old, UiStore.NONE, player, shown);
    }

    private boolean focusSlot(int player, int slot, boolean visible) {
        own(slot);
        if (!focusableNow(slot) || !inTrap(slot)) {
            return false;
        }
        int old = focused[player];
        if (old == slot && focusedGen[player] == store.generation(slot)) {
            if (visible != focusShown[player]) {
                focusShown[player] = visible;
                visibleCount[slot] += visible ? 1 : -1;
                store.state(slot, UiState.FOCUS_VISIBLE, visibleCount[slot] > 0);
            }
            return true;
        }
        boolean oldShown = focusShown[player];
        boolean oldLive = old != UiStore.NONE && store.alive(old) && store.generation(old) == focusedGen[player];
        focused[player] = slot;
        focusedGen[player] = store.generation(slot);
        focusShown[player] = visible;
        if (oldLive) {
            unfocus(old, oldShown);
            focusEvent(EventType.FOCUS_OUT, old, slot, player, oldShown);
        }
        if (focused[player] != slot) {
            return true; // a FOCUS_OUT listener moved focus elsewhere
        }
        if (focusCount[slot]++ == 0) {
            store.state(slot, UiState.FOCUSED, true);
        }
        if (visible && visibleCount[slot]++ == 0) {
            store.state(slot, UiState.FOCUS_VISIBLE, true);
        }
        for (int a = store.parent(slot); a != UiStore.NONE; a = store.parent(a)) {
            own(a);
            if ((flags[a] & REMEMBER) != 0) {
                memory[a] = store.element(slot);
            }
        }
        focusEvent(EventType.FOCUS_IN, slot, oldLive ? old : UiStore.NONE, player, visible);
        return true;
    }

    private void unfocus(int slot, boolean shown) {
        if (!store.alive(slot)) {
            return;
        }
        own(slot);
        if (focusCount[slot] > 0 && --focusCount[slot] == 0) {
            store.state(slot, UiState.FOCUSED, false);
        }
        if (shown && visibleCount[slot] > 0 && --visibleCount[slot] == 0) {
            store.state(slot, UiState.FOCUS_VISIBLE, false);
        }
    }

    private void focusEvent(EventType type, int target, int related, int player, boolean visible) {
        if (!store.alive(target)) {
            return;
        }
        UiEvent e = begin(type, target);
        try {
            e.related = related;
            e.player = player;
            e.focusVisible = visible;
            route(e);
        } finally {
            end();
        }
    }

    /** Whether {@code slot} can take focus now: focusable, alive in a layer, shown, not disabled. */
    private boolean focusableNow(int slot) {
        if (!store.alive(slot)) {
            return false;
        }
        own(slot);
        if ((flags[slot] & FOCUSABLE) == 0 || store.rootOf(slot) == UiStore.NONE || disabled(slot)) {
            return false;
        }
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (!geometry.shown(s)) {
                return false;
            }
        }
        return true;
    }

    private int focusableAncestor(int slot) {
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            own(s);
            if ((flags[s] & FOCUSABLE) != 0) {
                return focusableNow(s) ? s : UiStore.NONE;
            }
        }
        return UiStore.NONE;
    }

    /** Sets an explicit neighbour: focus moves from {@code from} to {@code to} in {@code direction} (null clears). */
    public void neighbour(Element from, FocusDirection direction, Element to) {
        if (direction.ordinal() >= DIRECTIONS) {
            throw new IllegalArgumentException("no explicit neighbour for " + direction);
        }
        int slot = slotOf(from); // first: it may grow the arrays
        neighbours[slot * DIRECTIONS + direction.ordinal()] = to;
    }

    /** Gives {@code container} its own focus navigation (null removes it). */
    public void traversal(Element container, FocusTraversal traversal) {
        int slot = slotOf(container); // first: it may grow the arrays
        traversals[slot] = traversal;
    }

    /** Makes {@code container} remember its last focused descendant and return focus to it when entered again. */
    public void rememberFocus(Element container, boolean on) {
        int slot = slotOf(container);
        setFlag(slot, REMEMBER, on);
        if (!on) {
            memory[slot] = null;
        }
    }

    /** The descendant {@code container} remembers as last focused, or null. */
    public Element remembered(Element container) {
        int slot = slotOf(container); // first: it may grow the arrays
        Element m = memory[slot];
        return m != null && m.alive() ? m : null;
    }

    /**
     * Moves {@code player}'s focus in {@code direction} (also what unconsumed navigation commands do); with nothing
     * focused, focuses the first element of the scope. Returns whether focus moved.
     */
    public boolean navigate(int player, FocusDirection direction) {
        int from = focusedSlot(player);
        int target;
        if (from == UiStore.NONE) {
            int scope = scopeRoot(UiStore.NONE);
            boolean last = direction == FocusDirection.PREVIOUS || direction == FocusDirection.LAST;
            target = scope == UiStore.NONE ? UiStore.NONE : firstIn(scope, last);
        } else {
            target = relative(from, direction);
        }
        if (target == UiStore.NONE || target == from) {
            return false;
        }
        target = remembering(from, target);
        return focusSlot(player, target, true);
    }

    private int relative(int from, FocusDirection direction) {
        int scope = scopeRoot(from);
        if (direction.ordinal() < DIRECTIONS) {
            Element n = neighbours[from * DIRECTIONS + direction.ordinal()];
            if (n != null && n.alive() && focusableNow(n.slot()) && isAncestorOrSelf(scope, n.slot())) {
                return n.slot();
            }
        }
        for (int a = store.parent(from); a != UiStore.NONE; a = store.parent(a)) {
            own(a);
            FocusTraversal t = traversals[a];
            if (t != null) {
                int r = t.relative(a, from, direction);
                if (r != UiStore.NONE && focusableNow(r) && isAncestorOrSelf(scope, r)) {
                    return r;
                }
            }
            if (a == scope) {
                break;
            }
        }
        if (scope == UiStore.NONE) {
            return UiStore.NONE;
        }
        gather(scope);
        if (direction.spatial()) {
            return spatial(from, direction);
        }
        int at = -1;
        for (int i = 0; i < candidateCount; i++) {
            if (candidates[i] == from) {
                at = i;
                break;
            }
        }
        if (candidateCount == 0) {
            return UiStore.NONE;
        }
        return switch (direction) {
            case NEXT -> candidates[at < 0 ? 0 : (at + 1) % candidateCount];
            case PREVIOUS -> candidates[at <= 0 ? candidateCount - 1 : at - 1];
            case FIRST -> candidates[0];
            default -> candidates[candidateCount - 1];
        };
    }

    /** Where focus is kept: the modal trap, else the layer of {@code from}, else the top-most layer with focusables. */
    private int scopeRoot(int from) {
        if (trapRoot != UiStore.NONE) {
            return trapRoot;
        }
        if (from != UiStore.NONE) {
            return store.rootOf(from);
        }
        for (int li = layers.count() - 1; li >= 0; li--) {
            int root = layers.at(li).rootSlot();
            if (firstIn(root, false) != UiStore.NONE) {
                return root;
            }
        }
        return UiStore.NONE;
    }

    /** The first (or last) focusable element under {@code root} in tree order, or NONE. */
    private int firstIn(int root, boolean last) {
        gather(root);
        return candidateCount == 0 ? UiStore.NONE : candidates[last ? candidateCount - 1 : 0];
    }

    /** Collects the focusable elements under {@code root} in tree order into {@link #candidates}. */
    private void gather(int root) {
        candidateCount = 0;
        gatherTree(root);
    }

    private void gatherTree(int slot) {
        if (!geometry.shown(slot) || (store.state(slot) & UiState.DISABLED) != 0) {
            return;
        }
        own(slot);
        if ((flags[slot] & FOCUSABLE) != 0) {
            if (candidateCount == candidates.length) {
                growCandidates();
            }
            candidates[candidateCount++] = slot;
        }
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            gatherTree(c);
        }
    }

    @AllowAllocation("growing with the focusable elements, rarely")
    private void growCandidates() {
        candidates = Arrays.copyOf(candidates, candidates.length * 2);
    }

    /**
     * The nearest candidate in {@code direction} from {@code from}: its centre must lie beyond {@code from}'s centre
     * that way, and, unless it overlaps {@code from} sideways, within 45 degrees of the direction (as Dear ImGui's
     * quadrants); the score is the gap along the direction plus the sideways gap weighted by
     * {@link #FOCUS_SIDEWAYS_WEIGHT}, ties going to the nearer centre.
     */
    private int spatial(int from, FocusDirection direction) {
        float fx0 = geometry.x(from);
        float fy0 = geometry.y(from);
        float fx1 = fx0 + geometry.width(from);
        float fy1 = fy0 + geometry.height(from);
        float fcx = (fx0 + fx1) * 0.5f;
        float fcy = (fy0 + fy1) * 0.5f;
        float weight = FOCUS_SIDEWAYS_WEIGHT.get();
        int best = UiStore.NONE;
        float bestScore = Float.MAX_VALUE;
        float bestCentre = Float.MAX_VALUE;
        for (int i = 0; i < candidateCount; i++) {
            int c = candidates[i];
            if (c == from) {
                continue;
            }
            float x0 = geometry.x(c);
            float y0 = geometry.y(c);
            float x1 = x0 + geometry.width(c);
            float y1 = y0 + geometry.height(c);
            float cx = (x0 + x1) * 0.5f;
            float cy = (y0 + y1) * 0.5f;
            float along;
            float sideways;
            switch (direction) {
                case RIGHT -> {
                    if (cx <= fcx) {
                        continue;
                    }
                    along = Math.max(0, x0 - fx1);
                    sideways = gap(y0, y1, fy0, fy1);
                }
                case LEFT -> {
                    if (cx >= fcx) {
                        continue;
                    }
                    along = Math.max(0, fx0 - x1);
                    sideways = gap(y0, y1, fy0, fy1);
                }
                case DOWN -> {
                    if (cy <= fcy) {
                        continue;
                    }
                    along = Math.max(0, y0 - fy1);
                    sideways = gap(x0, x1, fx0, fx1);
                }
                default -> {
                    if (cy >= fcy) {
                        continue;
                    }
                    along = Math.max(0, fy0 - y1);
                    sideways = gap(x0, x1, fx0, fx1);
                }
            }
            float alongCentre = direction == FocusDirection.LEFT || direction == FocusDirection.RIGHT
                ? Math.abs(cx - fcx) : Math.abs(cy - fcy);
            float sidewaysCentre = direction == FocusDirection.LEFT || direction == FocusDirection.RIGHT
                ? Math.abs(cy - fcy) : Math.abs(cx - fcx);
            if (sideways > 0 && sidewaysCentre > alongCentre) {
                continue; // more to the side than that way
            }
            float score = along + weight * sideways;
            float centre = (cx - fcx) * (cx - fcx) + (cy - fcy) * (cy - fcy);
            if (score < bestScore || score == bestScore && centre < bestCentre) {
                best = c;
                bestScore = score;
                bestCentre = centre;
            }
        }
        return best;
    }

    /** The distance between ranges {@code a0..a1} and {@code b0..b1}: 0 when they overlap. */
    private static float gap(float a0, float a1, float b0, float b1) {
        if (a1 < b0) {
            return b0 - a1;
        }
        if (b1 < a0) {
            return a0 - b1;
        }
        return 0;
    }

    /** {@code target}, or what the outermost remembering container entered from outside remembers. */
    private int remembering(int from, int target) {
        int result = target;
        for (int a = store.parent(target); a != UiStore.NONE; a = store.parent(a)) {
            own(a);
            if ((flags[a] & REMEMBER) == 0 || from != UiStore.NONE && isAncestorOrSelf(a, from)) {
                continue;
            }
            Element m = memory[a];
            if (m != null && m.alive() && isAncestorOrSelf(a, m.slot()) && focusableNow(m.slot())) {
                result = m.slot();
            }
        }
        return result;
    }

    // ================================================================ modal scopes

    /**
     * Makes {@code root}'s subtree a modal scope: focus stays inside it (it moves in now, to the remembered or first
     * focusable element) and {@code CANCEL} runs {@code onCancel} (null: CANCEL does nothing) unless a listener
     * consumed it. Focus returns to where it was when the scope is {@linkplain #popModal popped} or its root leaves the
     * tree.
     */
    @AllowAllocation("modal scopes open on user action, not per frame")
    public void pushModal(Element root, Runnable onCancel) {
        if (modalCount == modalRoots.length) {
            modalRoots = Arrays.copyOf(modalRoots, modalCount * 2);
            modalCancels = Arrays.copyOf(modalCancels, modalCount * 2);
            modalPrior = Arrays.copyOf(modalPrior, modalCount * 2 * MAX_PLAYERS);
        }
        for (int pl = 0; pl < MAX_PLAYERS; pl++) {
            modalPrior[modalCount * MAX_PLAYERS + pl] = focused(pl);
        }
        modalRoots[modalCount] = root;
        modalCancels[modalCount] = onCancel;
        modalCount++;
        updateTrap();
        for (int pl = 0; pl < MAX_PLAYERS; pl++) {
            if (focused[pl] != UiStore.NONE && !inTrap(focused[pl])) {
                blur(pl);
            }
        }
        int first = firstIn(root.slot(), false);
        if (first != UiStore.NONE) {
            focusSlot(0, remembering(UiStore.NONE, first), keyboardModality);
        }
    }

    /** Ends the modal scope of {@code root}; focus returns to where it was before. */
    public void popModal(Element root) {
        for (int m = modalCount - 1; m >= 0; m--) {
            if (modalRoots[m] == root) {
                removeModal(m, true);
                return;
            }
        }
    }

    private void removeModal(int m, boolean restore) {
        Element root = modalRoots[m];
        boolean top = m == modalCount - 1;
        System.arraycopy(modalPrior, m * MAX_PLAYERS, priorScratch, 0, MAX_PLAYERS);
        System.arraycopy(modalRoots, m + 1, modalRoots, m, modalCount - m - 1);
        System.arraycopy(modalCancels, m + 1, modalCancels, m, modalCount - m - 1);
        System.arraycopy(modalPrior, (m + 1) * MAX_PLAYERS, modalPrior, m * MAX_PLAYERS,
            (modalCount - m - 1) * MAX_PLAYERS);
        modalCount--;
        modalRoots[modalCount] = null;
        modalCancels[modalCount] = null;
        for (int pl = 0; pl < MAX_PLAYERS; pl++) {
            modalPrior[modalCount * MAX_PLAYERS + pl] = null;
        }
        updateTrap();
        for (int pl = 0; pl < MAX_PLAYERS; pl++) {
            Element prior = priorScratch[pl];
            priorScratch[pl] = null;
            int f = focusedSlot(pl);
            boolean inside = f != UiStore.NONE && root.alive() && isAncestorOrSelf(root.slot(), f);
            if (inside || f == UiStore.NONE) {
                blur(pl);
                if (restore && top && prior != null && prior.alive()) {
                    focusSlot(pl, prior.slot(), keyboardModality);
                }
            }
        }
    }

    private void updateTrap() {
        trapRoot = UiStore.NONE;
        if (modalCount > 0) {
            trapRoot = modalRoots[modalCount - 1].slot();
            return;
        }
        modalLayerTrap();
    }

    private void modalLayerTrap() {
        for (int li = layers.count() - 1; li >= 0; li--) {
            Layer layer = layers.at(li);
            if (layer.modal() && layer.showing()) {
                trapRoot = layer.rootSlot();
                return;
            }
        }
    }

    /** The root of the scope trapping focus now (the top-most modal scope or showing modal layer), or null. */
    public Element modalRoot() {
        updateTrap();
        return trapRoot == UiStore.NONE ? null : store.element(trapRoot);
    }

    private boolean inTrap(int slot) {
        return trapRoot == UiStore.NONE || isAncestorOrSelf(trapRoot, slot);
    }

    /** Runs the top-most modal scope's cancel action; false if there is none. */
    public boolean cancelModal() {
        if (modalCount == 0) {
            return false;
        }
        Runnable cancel = modalCancels[modalCount - 1];
        if (cancel == null) {
            return false;
        }
        cancel.run();
        return true;
    }

    // ================================================================ commands and text

    /**
     * Hears the commands no element consumed (nothing focused, or the focused element's ancestors let it pass): what
     * acts on the whole UI rather than on an element, such as cycling windows ({@code NEXT_WINDOW}).
     */
    @FunctionalInterface
    public interface CommandFallback {
        /** {@code command} from {@code player} went unconsumed; true to take it (nothing else acts on it then). */
        boolean command(UiCommand command, int player, int commandFlags);
    }

    private CommandFallback[] fallbacks = new CommandFallback[0];

    /** Adds {@code fallback}, asked in the order added before the default actions (focus moves, clicks) run. */
    @AllowAllocation("fallbacks are added when a UI is built")
    public void addCommandFallback(CommandFallback fallback) {
        fallbacks = Arrays.copyOf(fallbacks, fallbacks.length + 1);
        fallbacks[fallbacks.length - 1] = fallback;
    }

    /** Removes {@code fallback}. */
    @AllowAllocation("fallbacks are removed when a UI is torn down")
    public void removeCommandFallback(CommandFallback fallback) {
        for (int i = 0; i < fallbacks.length; i++) {
            if (fallbacks[i] == fallback) {
                CommandFallback[] less = new CommandFallback[fallbacks.length - 1];
                System.arraycopy(fallbacks, 0, less, 0, i);
                System.arraycopy(fallbacks, i + 1, less, i, less.length - i);
                fallbacks = less;
                return;
            }
        }
    }

    private void command(UiCommand command, int player, int commandFlags) {
        if (command == UiCommand.CANCEL && cancelDragOf(player)) {
            return;
        }
        int target = focusedSlot(player);
        if (target == UiStore.NONE) {
            updateTrap();
            target = trapRoot;
        }
        if (target != UiStore.NONE) {
            UiEvent e = begin(EventType.COMMAND, target);
            try {
                e.player = player;
                e.command = command;
                e.commandFlags = commandFlags;
                route(e);
                if (e.consumed) {
                    return;
                }
            } finally {
                end();
            }
        }
        for (CommandFallback fallback : fallbacks) {
            if (fallback.command(command, player, commandFlags)) {
                return;
            }
        }
        switch (command) {
            case UP -> navigateByKeys(player, FocusDirection.UP);
            case DOWN -> navigateByKeys(player, FocusDirection.DOWN);
            case LEFT -> navigateByKeys(player, FocusDirection.LEFT);
            case RIGHT -> navigateByKeys(player, FocusDirection.RIGHT);
            case NEXT -> navigateByKeys(player, FocusDirection.NEXT);
            case PREVIOUS -> navigateByKeys(player, FocusDirection.PREVIOUS);
            case HOME -> navigateByKeys(player, FocusDirection.FIRST);
            case END -> navigateByKeys(player, FocusDirection.LAST);
            case ACCEPT -> activate(player, EventType.CLICK);
            case CONTEXT_MENU -> activate(player, EventType.CONTEXT);
            case CANCEL -> cancelModal();
            default -> {
                // text and other commands only reach the focused element
            }
        }
    }

    private void navigateByKeys(int player, FocusDirection direction) {
        keyboardModality = true;
        if (!navigate(player, direction)) {
            int f = focusedSlot(player);
            if (f != UiStore.NONE) {
                focusSlot(player, f, true); // nowhere to go: the ring still shows where focus is
            }
        }
    }

    private void activate(int player, EventType type) {
        int f = focusedSlot(player);
        if (f == UiStore.NONE) {
            return;
        }
        UiEvent e = begin(type, f);
        try {
            e.player = player;
            e.clickCount = 1;
            route(e);
        } finally {
            end();
        }
    }

    private boolean cancelDragOf(int player) {
        for (int p = 0; p < UiInput.MAX_POINTERS; p++) {
            if (pointerActive[p] && dragging[p] && players[p] == player) {
                endDrag(p, lastX[p], lastY[p], lastButtons[p], true);
                suppressClick[p] = true;
                return true;
            }
        }
        return false;
    }

    private void text(UiInput input) {
        int owner = textOwnerNow();
        CharSequence typed = input.text();
        if (typed.length() > 0 && owner != UiStore.NONE) {
            UiEvent e = begin(EventType.TEXT, owner);
            try {
                e.player = textPlayer;
                e.text = typed;
                route(e);
            } finally {
                end();
            }
        }
        CharSequence editing = input.composition();
        if (!same(editing, composition)) {
            composition.setLength(0);
            composition.append(editing);
            if (owner != UiStore.NONE) {
                UiEvent e = begin(EventType.COMPOSITION, owner);
                try {
                    e.player = textPlayer;
                    e.text = composition;
                    e.compositionCursor = input.compositionCursor();
                    route(e);
                } finally {
                    end();
                }
            }
        }
    }

    private static boolean same(CharSequence a, CharSequence b) {
        if (a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int textOwnerNow() {
        int f = focusedSlot(textPlayer);
        return f != UiStore.NONE && (flags[f] & TEXT_INPUT) != 0 ? f : UiStore.NONE;
    }

    /** Starts or stops the engine's text input as text-taking elements gain and lose focus. */
    private void syncTextInput() {
        int owner = textOwnerNow();
        boolean same = owner == textOwner && (owner == UiStore.NONE || store.generation(owner) == textOwnerGen);
        if (same) {
            return;
        }
        if (textOwner != UiStore.NONE && textHost != null) {
            textHost.stop();
        }
        textOwner = owner;
        textOwnerGen = owner == UiStore.NONE ? 0 : store.generation(owner);
        if (owner != UiStore.NONE && textHost != null) {
            area(owner);
            textHost.start(textArea[0], textArea[1], textArea[2], textArea[3],
                (flags[owner] & KIND_MASK) >>> KIND_SHIFT);
        }
    }

    private void area(int slot) {
        if ((flags[slot] & IME_AREA) != 0) {
            System.arraycopy(imeArea, slot * 4, textArea, 0, 4);
        } else {
            textArea[0] = geometry.x(slot);
            textArea[1] = geometry.y(slot);
            textArea[2] = geometry.width(slot);
            textArea[3] = geometry.height(slot);
        }
    }

    /**
     * Tells interaction that layout ran: a text-taking element with text input running that moved (and set no IME
     * area of its own) moves the IME with it. The UI frame calls this after layout.
     */
    public void laidOut() {
        if (textOwner == UiStore.NONE || textHost == null || (flags[textOwner] & IME_AREA) != 0) {
            return;
        }
        float x = textArea[0];
        float y = textArea[1];
        float w = textArea[2];
        float h = textArea[3];
        area(textOwner);
        if (x != textArea[0] || y != textArea[1] || w != textArea[2] || h != textArea[3]) {
            textHost.area(textArea[0], textArea[1], textArea[2], textArea[3]);
        }
    }

    /** The engine's text input (null: none, text still arrives through {@link UiInput}). */
    public void textInputHost(TextInputHost host) {
        if (textHost != null && textOwner != UiStore.NONE) {
            textHost.stop();
        }
        textHost = host;
        textOwner = UiStore.NONE;
    }

    /** The element taking text now (the keyboard player's focused text-taking element), or null. */
    public Element textOwner() {
        int owner = textOwnerNow();
        return owner == UiStore.NONE ? null : store.element(owner);
    }

    /** The player whose focus gets the keyboard's text (default 0). */
    public int textPlayer() {
        return textPlayer;
    }

    /** Sets the player whose focus gets the keyboard's text. */
    public void textPlayer(int player) {
        textPlayer = player;
    }

    /** The clipboard controls copy to and paste from: the engine's, or one in memory. */
    public Clipboard clipboard() {
        return clipboard;
    }

    /** Sets the clipboard (the engine's system clipboard). */
    public void clipboard(Clipboard value) {
        clipboard = value == null ? Clipboard.inMemory() : value;
    }

    // ================================================================ pointer queries and capture

    /** Captures pointer {@code pointerId} for {@code element}: it gets the pointer's moves and release. */
    public void capture(int pointerId, Element element) {
        int p = indexOfPointer(pointerId);
        if (p >= 0) {
            capture(p, slotOf(element));
        }
    }

    /** Releases pointer {@code pointerId}'s capture. */
    public void releaseCapture(int pointerId, Element element) {
        int p = indexOfPointer(pointerId);
        if (p >= 0 && element != null && captured[p] == element.slot()) {
            releaseCapture(p);
        }
    }

    /** The element capturing pointer {@code pointerId}, or null. */
    public Element captured(int pointerId) {
        int p = indexOfPointer(pointerId);
        return p < 0 || captured[p] == UiStore.NONE ? null : store.element(captured[p]);
    }

    /** The front-most element under pointer {@code pointerId}, or null. */
    public Element hovered(int pointerId) {
        int p = indexOfPointer(pointerId);
        return p < 0 || hitCount[p] == 0 ? null : store.element(hits[p][0]);
    }

    /**
     * Whether the UI takes pointer {@code pointerId}: it is over an interactive element or a blocking layer, or
     * captured. When false, the engine passes the pointer on to the scene.
     */
    public boolean overUi(int pointerId) {
        int p = indexOfPointer(pointerId);
        return p >= 0 && (hitCount[p] > 0 || blocked[p] || captured[p] != UiStore.NONE);
    }

    /** The cursor shape for pointer {@code pointerId}: the hovered element's (or nearest ancestor's) {@code cursor}. */
    public Cursor cursor(int pointerId) {
        int p = indexOfPointer(pointerId);
        if (p < 0) {
            return Cursor.DEFAULT;
        }
        int from = captured[p] != UiStore.NONE ? captured[p] : hitCount[p] > 0 ? hits[p][0] : UiStore.NONE;
        for (int s = from; s != UiStore.NONE; s = store.parent(s)) {
            Cursor c = geometry.cursor(s);
            if (c != Cursor.AUTO) {
                return c;
            }
        }
        return Cursor.DEFAULT;
    }

    // ================================================================ access

    /** The hit test. */
    public Picking picking() {
        return picking;
    }

    /** The element store. */
    public UiStore store() {
        return store;
    }

    /** The layer stack. */
    public Layers layers() {
        return layers;
    }

    /** What picking reads of elements. */
    public PickGeometry geometry() {
        return geometry;
    }

    /** The UI clock's time of the current frame, in nanoseconds. */
    public long now() {
        return now;
    }

    private static long seconds(float s) {
        return (long) (s * 1e9);
    }

    private static float squared(float dx, float dy) {
        return dx * dx + dy * dy;
    }
}
