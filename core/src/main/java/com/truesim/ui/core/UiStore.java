package com.truesim.ui.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.Versioned;
import java.util.Arrays;

/**
 * The elements of one UI, as structure-of-arrays slots with generations (design UI8): the tree links, the element's
 * type, id and style name (what styles select by), its state bits, what needs doing for it (dirty bits), its range of
 * the draw list and its semantics. {@link Element}s are thin facades over a slot; modules keep further per-slot arrays
 * of their own (layout's inputs and outputs, a control's text) indexed by the same slot numbers, sized by
 * {@link #capacity()}.
 *
 * <p><b>Dirty work.</b> {@link #markDirty} adds bits and queues the slot once; the UI's frame visits only the queued
 * slots ({@link #dirtySlots()}), so its work is proportional to what changed. Every change is stamped on the store's
 * {@link ChangeClock}: {@link #version()} is the latest, and a slot's {@link #changed(int)} its own.
 *
 * <p>Not thread-safe: one UI thread (the clock's owner, if named).
 */
public final class UiStore implements Versioned {

    /** Dirty bit: the element's style must be resolved again (its state, id, style name or the sheet changed). */
    public static final int DIRTY_STYLE = 1;
    /** Dirty bit: the element must be measured and arranged again. */
    public static final int DIRTY_LAYOUT = 2;
    /** Dirty bit: the element must record its primitives again. */
    public static final int DIRTY_DRAW = 4;
    /** Dirty bit: the element's text must be shaped again. */
    public static final int DIRTY_TEXT = 8;
    /** Dirty bit: children were added, removed or moved: draw ranges are rebuilt in tree order. */
    public static final int DIRTY_STRUCTURE = 16;

    /** No slot. */
    public static final int NONE = -1;

    private final ChangeClock clock;
    private int capacity;
    private int highWater;
    private int freeHead = NONE;
    private int count;

    private int[] generation;
    private boolean[] alive;
    private int[] parent;
    private int[] firstChild;
    private int[] lastChild;
    private int[] nextSibling;
    private int[] previousSibling;
    private String[] type;
    private String[] id;
    private String[] style;
    private int[] state;
    private int[] dirty;
    private long[] changed;
    private int[] rangeStart;
    private int[] rangeCapacity;
    private Role[] role;
    private CharSequence[] label;
    private AccessibleValue[] value;
    private Element[] facades;

    private int[] dirtyQueue;
    private boolean[] queued;
    private int dirtyCount;
    private long version;
    private final int root;
    private boolean[] isRoot;
    private int[] roots = new int[8];
    private int rootCount;

    /** A store with a root element (type {@code "root"}) on its own clock. */
    public UiStore() {
        this(new ChangeClock());
    }

    /** A store stamping its changes on {@code clock}. */
    @AllowAllocation("one per UI")
    public UiStore(ChangeClock clock) {
        this.clock = clock;
        allocate(64);
        root = createRoot("root", "root").slot();
        role[root] = Role.GROUP;
    }

    /**
     * A new top-level element of {@code type} with id {@code id}: the root of a further tree (a UI layer: pop-ups,
     * tooltips, modal dialogs), laid out over the whole screen like {@link #root()} and never a child. Roots stay
     * until the store is dropped.
     */
    @AllowAllocation("roots are made when a UI is built")
    public Element createRoot(String type, String id) {
        Element e = create(type, id);
        int slot = e.slot();
        isRoot[slot] = true;
        if (rootCount == roots.length) {
            roots = Arrays.copyOf(roots, rootCount * 2);
        }
        roots[rootCount++] = slot;
        return e;
    }

    /** Whether {@code slot} is a root ({@link #root()} or one made by {@link #createRoot}). */
    public boolean isRoot(int slot) {
        return slot >= 0 && slot < highWater && isRoot[slot];
    }

    /** How many roots exist ({@link #root()} first). */
    public int rootCount() {
        return rootCount;
    }

    /** Root {@code index} in the order they were made; {@link #rootSlot()} is index 0. */
    public int rootAt(int index) {
        return roots[index];
    }

    /** The root of the tree {@code slot} is in, or {@link #NONE} if that tree is detached. */
    public int rootOf(int slot) {
        int s = slot;
        while (parent[s] != NONE) {
            s = parent[s];
        }
        return isRoot[s] ? s : NONE;
    }

    @AllowAllocation("growing, rarely")
    private void allocate(int newCapacity) {
        generation = grow(generation, newCapacity);
        alive = alive == null ? new boolean[newCapacity] : Arrays.copyOf(alive, newCapacity);
        queued = queued == null ? new boolean[newCapacity] : Arrays.copyOf(queued, newCapacity);
        isRoot = isRoot == null ? new boolean[newCapacity] : Arrays.copyOf(isRoot, newCapacity);
        parent = grow(parent, newCapacity);
        firstChild = grow(firstChild, newCapacity);
        lastChild = grow(lastChild, newCapacity);
        nextSibling = grow(nextSibling, newCapacity);
        previousSibling = grow(previousSibling, newCapacity);
        type = grow(type, newCapacity);
        id = grow(id, newCapacity);
        style = grow(style, newCapacity);
        state = grow(state, newCapacity);
        dirty = grow(dirty, newCapacity);
        changed = changed == null ? new long[newCapacity] : Arrays.copyOf(changed, newCapacity);
        rangeStart = grow(rangeStart, newCapacity);
        rangeCapacity = grow(rangeCapacity, newCapacity);
        role = role == null ? new Role[newCapacity] : Arrays.copyOf(role, newCapacity);
        label = label == null ? new CharSequence[newCapacity] : Arrays.copyOf(label, newCapacity);
        value = value == null ? new AccessibleValue[newCapacity] : Arrays.copyOf(value, newCapacity);
        facades = facades == null ? new Element[newCapacity] : Arrays.copyOf(facades, newCapacity);
        dirtyQueue = grow(dirtyQueue, newCapacity); // each slot is queued at most once
        capacity = newCapacity;
    }

    /** The generation of {@code slot}: it grows each time the slot is reused for a new element. */
    public int generation(int slot) {
        return generation[slot];
    }

    private static int[] grow(int[] a, int n) {
        return a == null ? new int[n] : Arrays.copyOf(a, n);
    }

    private static String[] grow(String[] a, int n) {
        return a == null ? new String[n] : Arrays.copyOf(a, n);
    }

    /**
     * A new element of {@code type} (what selectors name: {@code panel}, {@code label}) with element id {@code id}
     * (a dotted path segment, or null), not yet in the tree.
     */
    @AllowAllocation("elements are made when a UI is built")
    public Element create(String type, String id) {
        int slot;
        if (freeHead != NONE) {
            slot = freeHead;
            freeHead = nextSibling[slot];
        } else {
            if (highWater == capacity) {
                allocate(capacity * 2);
            }
            slot = highWater++;
        }
        generation[slot]++;
        alive[slot] = true;
        parent[slot] = NONE;
        firstChild[slot] = NONE;
        lastChild[slot] = NONE;
        nextSibling[slot] = NONE;
        previousSibling[slot] = NONE;
        this.type[slot] = type;
        this.id[slot] = id;
        style[slot] = null;
        state[slot] = 0;
        dirty[slot] = 0;
        rangeStart[slot] = 0;
        rangeCapacity[slot] = 0;
        role[slot] = Role.NONE;
        label[slot] = null;
        value[slot] = null;
        count++;
        Element element = new Element(this, slot, generation[slot]);
        facades[slot] = element;
        markDirty(slot, DIRTY_STYLE | DIRTY_LAYOUT | DIRTY_DRAW | DIRTY_TEXT);
        return element;
    }

    /** The root element: the screen or panel the UI covers. */
    public Element root() {
        return facades[root];
    }

    /** The root's slot. */
    public int rootSlot() {
        return root;
    }

    /** Adds {@code child} as the last child of {@code parentSlot}, out of wherever it was. */
    public void append(int parentSlot, int child) {
        check(parentSlot);
        check(child);
        if (isRoot[child]) {
            throw new IllegalArgumentException("a root has no parent");
        }
        for (int p = parentSlot; p != NONE; p = parent[p]) {
            if (p == child) {
                throw new IllegalArgumentException("an element cannot be added under itself");
            }
        }
        detach(child);
        parent[child] = parentSlot;
        int last = lastChild[parentSlot];
        previousSibling[child] = last;
        if (last == NONE) {
            firstChild[parentSlot] = child;
        } else {
            nextSibling[last] = child;
        }
        lastChild[parentSlot] = child;
        markDirty(parentSlot, DIRTY_LAYOUT | DIRTY_STRUCTURE);
        markSubtree(child, DIRTY_STYLE | DIRTY_LAYOUT | DIRTY_DRAW);
    }

    /** Takes {@code child} out of its parent; it and its subtree stay alive. */
    public void detach(int child) {
        check(child);
        int p = parent[child];
        if (p == NONE) {
            return;
        }
        int prev = previousSibling[child];
        int next = nextSibling[child];
        if (prev == NONE) {
            firstChild[p] = next;
        } else {
            nextSibling[prev] = next;
        }
        if (next == NONE) {
            lastChild[p] = prev;
        } else {
            previousSibling[next] = prev;
        }
        parent[child] = NONE;
        nextSibling[child] = NONE;
        previousSibling[child] = NONE;
        markDirty(p, DIRTY_LAYOUT | DIRTY_STRUCTURE);
    }

    /** Destroys {@code slot} and its subtree: their facades stop working. */
    public void destroy(int slot) {
        check(slot);
        if (isRoot[slot]) {
            throw new IllegalArgumentException("roots stay");
        }
        detach(slot);
        destroyTree(slot);
    }

    private void destroyTree(int slot) {
        for (int c = firstChild[slot]; c != NONE; ) {
            int next = nextSibling[c];
            destroyTree(c);
            c = next;
        }
        alive[slot] = false;
        facades[slot] = null;
        type[slot] = null;
        id[slot] = null;
        style[slot] = null;
        label[slot] = null;
        value[slot] = null;
        nextSibling[slot] = freeHead;
        freeHead = slot;
        count--;
        version = clock.stamp();
    }

    private void markSubtree(int slot, int bits) {
        markDirty(slot, bits);
        for (int c = firstChild[slot]; c != NONE; c = nextSibling[c]) {
            markSubtree(c, bits);
        }
    }

    /** Adds dirty {@code bits} to {@code slot}, queueing it for the next frame. */
    public void markDirty(int slot, int bits) {
        if (!queued[slot] && bits != 0) {
            queued[slot] = true;
            dirtyQueue[dirtyCount++] = slot;
        }
        dirty[slot] |= bits;
        long stamp = clock.stamp();
        changed[slot] = stamp;
        version = stamp;
    }

    /** Marks every element in a root's tree dirty with {@code bits}: a new style sheet, a token switch, a new scale. */
    public void markAll(int bits) {
        for (int i = 0; i < rootCount; i++) {
            markSubtree(roots[i], bits);
        }
    }

    /** The slots queued since {@link #clearDirtyQueue()}: the first {@link #dirtyCount()} entries. */
    public int[] dirtySlots() {
        return dirtyQueue;
    }

    /** How many slots are queued. */
    public int dirtyCount() {
        return dirtyCount;
    }

    /**
     * Empties the queue; each slot's bits are cleared by whoever did the work ({@link #clearDirty}). A slot is queued
     * once until then, whatever bits it still has, so work cleared bit by bit never loses a later mark.
     */
    public void clearDirtyQueue() {
        for (int i = 0; i < dirtyCount; i++) {
            queued[dirtyQueue[i]] = false;
        }
        dirtyCount = 0;
    }

    /** Clears {@code bits} of {@code slot}. */
    public void clearDirty(int slot, int bits) {
        dirty[slot] &= ~bits;
    }

    /** The dirty bits of {@code slot}. */
    public int dirty(int slot) {
        return dirty[slot];
    }

    void check(int slot) {
        if (slot < 0 || slot >= highWater || !alive[slot]) {
            throw new IllegalArgumentException("no element in slot " + slot);
        }
    }

    boolean alive(int slot, int gen) {
        return slot < highWater && alive[slot] && generation[slot] == gen;
    }

    // ---------------------------------------------------------------- per-slot data

    /** The element of {@code slot}, or null if the slot is free. */
    public Element element(int slot) {
        return slot >= 0 && slot < highWater ? facades[slot] : null;
    }

    /** Whether {@code slot} holds an element. */
    public boolean alive(int slot) {
        return slot >= 0 && slot < highWater && alive[slot];
    }

    /** The parent's slot, or {@link #NONE}. */
    public int parent(int slot) {
        return parent[slot];
    }

    /** The first child's slot, or {@link #NONE}. */
    public int firstChild(int slot) {
        return firstChild[slot];
    }

    /** The last child's slot, or {@link #NONE}. */
    public int lastChild(int slot) {
        return lastChild[slot];
    }

    /** The next sibling's slot, or {@link #NONE}. */
    public int nextSibling(int slot) {
        return nextSibling[slot];
    }

    /** The previous sibling's slot, or {@link #NONE}. */
    public int previousSibling(int slot) {
        return previousSibling[slot];
    }

    /** The element's type: what type selectors match. */
    public String type(int slot) {
        return type[slot];
    }

    /** The element id (a dotted path segment), or null. */
    public String id(int slot) {
        return id[slot];
    }

    /** Sets the element id. */
    public void id(int slot, String value) {
        check(slot);
        id[slot] = value;
        markSubtree(slot, DIRTY_STYLE);
    }

    /** The style name (a theme or variant: {@code glass}, {@code primary}), or null for the inherited one. */
    public String style(int slot) {
        return style[slot];
    }

    /** Sets the style name. */
    public void style(int slot, String value) {
        check(slot);
        style[slot] = value;
        markSubtree(slot, DIRTY_STYLE);
    }

    /** The state bits ({@link UiState}). */
    public int state(int slot) {
        return state[slot];
    }

    /** Turns state {@code bits} on or off. */
    public void state(int slot, int bits, boolean on) {
        check(slot);
        int next = on ? state[slot] | bits : state[slot] & ~bits;
        if (next != state[slot]) {
            state[slot] = next;
            markDirty(slot, DIRTY_STYLE);
        }
    }

    /** When {@code slot} last changed, on the store's clock. */
    public long changed(int slot) {
        return changed[slot];
    }

    /** The first primitive of the element's draw-list range. */
    public int rangeStart(int slot) {
        return rangeStart[slot];
    }

    /** The primitives the element's range holds. */
    public int rangeCapacity(int slot) {
        return rangeCapacity[slot];
    }

    /** Sets the element's draw-list range. */
    public void range(int slot, int start, int capacityInPrimitives) {
        rangeStart[slot] = start;
        rangeCapacity[slot] = capacityInPrimitives;
    }

    /** The element's role for accessibility. */
    public Role role(int slot) {
        return role[slot];
    }

    /** The element's accessible name, or null: taken from the live text when one was given ({@link #semantics}). */
    public String label(int slot) {
        CharSequence l = label[slot];
        return l == null ? null : l.toString();
    }

    /**
     * Sets the element's semantics. The label may be live text (a label's or a row's {@link StringBuilder}): the
     * accessible name is read from it when the semantics tree is taken, so changing text costs nothing here.
     */
    public void semantics(int slot, Role newRole, CharSequence newLabel) {
        check(slot);
        role[slot] = newRole;
        label[slot] = newLabel;
        version = clock.stamp();
    }

    /** The element's value for assistive technology, or null (it has none). */
    public AccessibleValue accessibleValue(int slot) {
        return value[slot];
    }

    /** Gives the element a value for assistive technology ({@link AccessibleValue}; null: none). */
    public void accessibleValue(int slot, AccessibleValue newValue) {
        check(slot);
        value[slot] = newValue;
        version = clock.stamp();
    }

    /** Slots in use are below this. */
    public int highWater() {
        return highWater;
    }

    /** The length per-slot arrays of other modules need: grows when the store grows. */
    public int capacity() {
        return capacity;
    }

    /** Elements alive, the root included. */
    public int count() {
        return count;
    }

    /** The clock stamping the store's changes. */
    public ChangeClock clock() {
        return clock;
    }

    @Override
    public long version() {
        return version;
    }
}
