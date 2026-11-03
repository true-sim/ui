package com.truesim.ui.core;

/**
 * One element of a {@link UiStore}: a thin, final facade over its slot (design UI8). It holds no data of its own; a
 * facade whose element was destroyed throws on use. Made once with the element, never per frame.
 */
public final class Element {

    private final UiStore store;
    private final int slot;
    private final int generation;

    Element(UiStore store, int slot, int generation) {
        this.store = store;
        this.slot = slot;
        this.generation = generation;
    }

    /** The store the element lives in. */
    public UiStore store() {
        return store;
    }

    /** The element's slot: the index into per-slot arrays. */
    public int slot() {
        check();
        return slot;
    }

    /** Whether the element still exists. */
    public boolean alive() {
        return store.alive(slot, generation);
    }

    private void check() {
        if (!store.alive(slot, generation)) {
            throw new IllegalStateException("the element was destroyed");
        }
    }

    /** Adds {@code child} as the last child; returns this. */
    public Element add(Element child) {
        check();
        store.append(slot, child.slot());
        return this;
    }

    /** Takes the element out of its parent. */
    public Element detach() {
        check();
        store.detach(slot);
        return this;
    }

    /** Destroys the element and its subtree. */
    public void destroy() {
        check();
        store.destroy(slot);
    }

    /** The parent, or null. */
    public Element parent() {
        check();
        int p = store.parent(slot);
        return p == UiStore.NONE ? null : store.element(p);
    }

    /** The type selectors match. */
    public String type() {
        check();
        return store.type(slot);
    }

    /** The element id, or null. */
    public String id() {
        check();
        return store.id(slot);
    }

    /** Sets the element id; returns this. */
    public Element id(String value) {
        check();
        store.id(slot, value);
        return this;
    }

    /** The style name, or null. */
    public String style() {
        check();
        return store.style(slot);
    }

    /** Sets the style name; returns this. */
    public Element style(String value) {
        check();
        store.style(slot, value);
        return this;
    }

    /** Whether state {@code bit} ({@link UiState}) is on. */
    public boolean is(int bit) {
        check();
        return (store.state(slot) & bit) != 0;
    }

    /** Turns state {@code bits} on or off; returns this. */
    public Element state(int bits, boolean on) {
        check();
        store.state(slot, bits, on);
        return this;
    }

    /** Sets the element's semantics; returns this. */
    public Element semantics(Role role, String label) {
        check();
        store.semantics(slot, role, label);
        return this;
    }

    /** Marks the element dirty with {@code bits} ({@link UiStore#DIRTY_LAYOUT} and the others). */
    public void markDirty(int bits) {
        check();
        store.markDirty(slot, bits);
    }

    @Override
    public String toString() {
        if (!alive()) {
            return "Element[destroyed]";
        }
        String id = store.id(slot);
        return store.type(slot) + (id == null ? "" : "#" + id) + "@" + slot;
    }
}
