package com.truesim.ui.a11y;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.AccessibleValue;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/**
 * What an OS accessibility bridge (Windows UI Automation, later others) needs of a UI, without the OS (design UI27).
 * The UI thread calls {@link #update()} once a frame: it runs what assistive technology asked for (activate, toggle,
 * focus, set a value: queued from its threads, run here, so nothing touches the UI elsewhere and nothing waits more
 * than to the next frame), and, while assistive technology is listening or has asked since the last frame, takes a
 * new {@link AccessibleTree} when the store's version or the focus moved, telling {@link Events} what changed (the
 * focus, names, values, toggles, the enabled state, the nodes). With nobody listening and nothing asked, a frame is
 * two comparisons: no allocation.
 *
 * <p>The bridge's threads read {@link #tree()} (the newest snapshot, immutable) and call {@link #request}; a snapshot
 * may be up to a frame old.
 */
public final class AccessibilityBridge {

    /** What changed between two snapshots, told on the UI thread from {@link #update()}. */
    public interface Events {

        /** The focus moved to {@code node} of {@code tree} ({@link AccessibleTree#NONE}: nothing focused). */
        void focusChanged(AccessibleTree tree, int node);

        /** {@code property} of the element at {@code node} in {@code tree} changed (it was {@code oldNode} in {@code old}). */
        void propertyChanged(AccessibleTree tree, int node, Property property, AccessibleTree old, int oldNode);

        /** Nodes came or went (or moved) since the last snapshot. */
        void structureChanged(AccessibleTree tree);
    }

    /** A property {@link Events#propertyChanged} reports. */
    public enum Property { NAME, VALUE, TOGGLE_STATE, ENABLED }

    /** What assistive technology can ask for. */
    public enum Action {
        /** Activate (a click): buttons, links, menu items, tabs. */
        INVOKE,
        /** Toggle (a click on a checkbox, toggle button or switch). */
        TOGGLE,
        /** Give it the focus. */
        FOCUS,
        /** Set its value from text ({@link AccessibleValue#set}). */
        SET_VALUE
    }

    private record Request(Action action, int slot, int generation, String text) {
    }

    private final UiStore store;
    private final Interaction interaction;
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
    private BooleanSupplier listening = () -> false;
    private Events events;
    private volatile AccessibleTree tree;
    private volatile boolean asked;
    private long seenVersion = Long.MIN_VALUE;
    private int seenFocus = Integer.MIN_VALUE;
    private int refused;

    /** A bridge over {@code store} as {@code interaction} presents it (layers, actions, focus, boxes). */
    public AccessibilityBridge(UiStore store, Interaction interaction) {
        this.store = Objects.requireNonNull(store);
        this.interaction = Objects.requireNonNull(interaction);
    }

    /** Where changes are told (null: nowhere); returns this. */
    public AccessibilityBridge events(Events value) {
        events = value;
        return this;
    }

    /** Asks whether assistive technology listens for events (the OS knows): snapshots follow changes then. */
    public AccessibilityBridge listening(BooleanSupplier value) {
        listening = Objects.requireNonNull(value);
        return this;
    }

    /**
     * The newest snapshot, or null before the first; any thread. A bridge answering a request calls {@link #asked()}
     * too, so the next frame brings it up to date.
     */
    public AccessibleTree tree() {
        return tree;
    }

    /** Notes that assistive technology asked something (any thread): the next {@link #update()} refreshes the tree. */
    public void asked() {
        asked = true;
    }

    /**
     * Queues {@code action} on the element in {@code slot} of {@code generation} (any thread), with {@code text} for
     * {@link Action#SET_VALUE}; it runs in the next {@link #update()}.
     */
    @AllowAllocation("an assistive tool's request, not per frame")
    public void request(Action action, int slot, int generation, String text) {
        requests.add(new Request(Objects.requireNonNull(action), slot, generation, text));
        asked = true;
    }

    /** Requests refused since the bridge was made (the element went, was disabled, or refused the value). */
    public int refused() {
        return refused;
    }

    /**
     * The UI thread's frame: runs the queued requests, then refreshes the tree if assistive technology listens or
     * asked. Returns whether a new snapshot was taken.
     */
    public boolean update() {
        for (Request r = requests.poll(); r != null; r = requests.poll()) {
            if (!run(r)) {
                refused++;
            }
        }
        if (!asked && !listening.getAsBoolean()) {
            return false;
        }
        asked = false;
        return refresh();
    }

    /**
     * On the UI thread: takes a new snapshot if the store's version or the focus moved since the last (or there is
     * none), telling {@link Events} what changed; returns whether it took one. A bridge answering the OS on the UI
     * thread (a window message) calls it to answer from the present state.
     */
    public boolean refresh() {
        long version = store.version();
        int focus = interaction.focusedSlot(0);
        if (tree != null && version == seenVersion && focus == seenFocus) {
            return false;
        }
        seenVersion = version;
        seenFocus = focus;
        AccessibleTree old = tree;
        AccessibleTree now = AccessibleTree.take(store, interaction);
        tree = now;
        if (old != null && events != null) {
            tell(old, now);
        }
        return true;
    }

    @AllowAllocation("comparing snapshots, taken only on a change")
    private void tell(AccessibleTree old, AccessibleTree now) {
        boolean structure = old.count() != now.count();
        for (int n = 1; n < now.count(); n++) {
            int o = old.find(now.slot(n), now.generation(n));
            if (o == AccessibleTree.NONE) {
                structure = true;
                continue;
            }
            structure |= old.parent(o) == AccessibleTree.ROOT ? now.parent(n) != AccessibleTree.ROOT
                : now.parent(n) == AccessibleTree.ROOT || old.key(old.parent(o)) != now.key(now.parent(n));
            if (!Objects.equals(old.name(o), now.name(n))) {
                events.propertyChanged(now, n, Property.NAME, old, o);
            }
            if (!Objects.equals(old.value(o), now.value(n))) {
                events.propertyChanged(now, n, Property.VALUE, old, o);
            }
            if (now.toggles(n) && old.toggleState(o) != now.toggleState(n)) {
                events.propertyChanged(now, n, Property.TOGGLE_STATE, old, o);
            }
            if (old.is(o, UiState.DISABLED) != now.is(n, UiState.DISABLED)) {
                events.propertyChanged(now, n, Property.ENABLED, old, o);
            }
        }
        if (structure) {
            events.structureChanged(now);
        }
        if (focusKey(old) != focusKey(now)) {
            events.focusChanged(now, now.focus());
        }
    }

    private static long focusKey(AccessibleTree t) {
        return t.focus() == AccessibleTree.NONE ? Long.MIN_VALUE : t.key(t.focus());
    }

    @AllowAllocation("an assistive tool's request, not per frame")
    private boolean run(Request r) {
        if (r.slot() < 0 || r.slot() >= store.highWater() || !store.alive(r.slot())
            || store.generation(r.slot()) != r.generation()) {
            return false;
        }
        Element element = store.element(r.slot());
        if (r.action() != Action.FOCUS && interaction.disabled(r.slot())) {
            return false;
        }
        return switch (r.action()) {
            case INVOKE, TOGGLE -> {
                interaction.dispatch(EventType.CLICK, element, 0);
                yield true;
            }
            case FOCUS -> interaction.focus(element);
            case SET_VALUE -> {
                AccessibleValue v = store.accessibleValue(r.slot());
                yield v != null && !v.readOnly() && r.text() != null && v.set(r.text());
            }
        };
    }
}
