package com.truesim.ui.a11y;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.AccessibleValue;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.PickGeometry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of a UI's semantics tree for an OS accessibility bridge (design UI27): taken on the UI thread,
 * read by the bridge on the assistive technology's threads. Node 0 ({@link #ROOT}) stands for the window; under it
 * the {@link Semantics} tree's nodes of the shown layers, in z-order, each with its role, name, element id, state,
 * actions, value and box (UI pixels). Nodes are numbered depth first; an element is found again by its slot and
 * generation ({@link #find}), which stay its identity across snapshots.
 */
@AllowAllocation("taken only when the semantics changed while assistive technology listens, read on its threads")
public final class AccessibleTree {

    /** The window's node. */
    public static final int ROOT = 0;
    /** No node. */
    public static final int NONE = -1;

    private static final int HAS_VALUE = 1;
    private static final int READ_ONLY = 2;
    private static final int PASSWORD = 4;

    private final int count;
    private final int[] slot;
    private final int[] generation;
    private final int[] parent;
    private final int[] firstChild;
    private final int[] lastChild;
    private final int[] nextSibling;
    private final int[] previousSibling;
    private final int[] state;
    private final int[] actions;
    private final Role[] role;
    private final String[] name;
    private final String[] id;
    private final String[] value;
    private final int[] valueFlags;
    private final float[] x;
    private final float[] y;
    private final float[] width;
    private final float[] height;
    private final Map<Long, Integer> byKey;
    private final int focus;
    private final long version;

    private AccessibleTree(List<Semantics.Node> nodes, List<Integer> parents, UiStore store, PickGeometry geometry,
        int focusedSlot, long version) {
        count = nodes.size() + 1;
        slot = new int[count];
        generation = new int[count];
        parent = new int[count];
        firstChild = new int[count];
        lastChild = new int[count];
        nextSibling = new int[count];
        previousSibling = new int[count];
        state = new int[count];
        actions = new int[count];
        role = new Role[count];
        name = new String[count];
        id = new String[count];
        value = new String[count];
        valueFlags = new int[count];
        x = new float[count];
        y = new float[count];
        width = new float[count];
        height = new float[count];
        byKey = new HashMap<>(count * 2);
        this.version = version;
        java.util.Arrays.fill(firstChild, NONE);
        java.util.Arrays.fill(lastChild, NONE);
        java.util.Arrays.fill(nextSibling, NONE);
        java.util.Arrays.fill(previousSibling, NONE);
        slot[ROOT] = UiStore.NONE;
        parent[ROOT] = NONE;
        role[ROOT] = Role.WINDOW;
        for (int i = 1; i < count; i++) {
            Semantics.Node n = nodes.get(i - 1);
            int s = n.slot();
            int p = parents.get(i - 1);
            slot[i] = s;
            generation[i] = store.generation(s);
            parent[i] = p;
            role[i] = n.role();
            name[i] = n.label();
            id[i] = store.id(s);
            state[i] = n.state();
            actions[i] = n.actions();
            x[i] = geometry.x(s);
            y[i] = geometry.y(s);
            width[i] = geometry.width(s);
            height[i] = geometry.height(s);
            AccessibleValue v = store.accessibleValue(s);
            if (v != null) {
                int flags = HAS_VALUE | (v.readOnly() ? READ_ONLY : 0) | (v.password() ? PASSWORD : 0);
                valueFlags[i] = flags;
                value[i] = v.password() ? "" : v.text();
            }
            if (lastChild[p] == NONE) {
                firstChild[p] = i;
            } else {
                nextSibling[lastChild[p]] = i;
                previousSibling[i] = lastChild[p];
            }
            lastChild[p] = i;
            byKey.put(key(s, generation[i]), i);
        }
        int f = NONE;
        for (int s = focusedSlot; s != UiStore.NONE && f == NONE; s = store.parent(s)) {
            f = find(s, store.generation(s));
        }
        focus = f;
    }

    /**
     * Takes the tree of {@code store} as {@code interaction} sees it (its layers in z-order, the actions its listeners
     * offer, player 0's focus): the elements {@link Semantics} lists, less those not shown and what is under them.
     */
    @AllowAllocation("taken when the semantics changed and assistive technology is listening, not per frame")
    public static AccessibleTree take(UiStore store, Interaction interaction) {
        Semantics.Node top = Semantics.of(store, interaction);
        List<Semantics.Node> nodes = new ArrayList<>();
        List<Integer> parents = new ArrayList<>();
        PickGeometry geometry = interaction.geometry();
        boolean wrapper = top.slot() == store.rootSlot() && top.role() == Role.GROUP
            && store.role(store.rootSlot()) == Role.NONE;
        if (wrapper) {
            for (Semantics.Node child : top.children()) {
                add(child, ROOT, geometry, nodes, parents);
            }
        } else {
            add(top, ROOT, geometry, nodes, parents);
        }
        return new AccessibleTree(nodes, parents, store, geometry, interaction.focusedSlot(0), store.version());
    }

    @AllowAllocation("taken when the semantics changed, not per frame")
    private static void add(Semantics.Node node, int parentIndex, PickGeometry geometry, List<Semantics.Node> nodes,
        List<Integer> parents) {
        if (!geometry.shown(node.slot())) {
            return;
        }
        nodes.add(node);
        parents.add(parentIndex);
        int index = nodes.size(); // its number: node 0 is the window
        for (Semantics.Node child : node.children()) {
            add(child, index, geometry, nodes, parents);
        }
    }

    /** An element's identity across snapshots. */
    public static long key(int slot, int generation) {
        return (long) slot << 32 | generation & 0xffff_ffffL;
    }

    /** The node of the element in {@code slot} of {@code generation}, or {@link #NONE}. */
    public int find(int slot, int generation) {
        Integer i = byKey.get(key(slot, generation));
        return i == null ? NONE : i;
    }

    /** The node of {@code key} ({@link #key}), or {@link #NONE}. */
    public int find(long key) {
        Integer i = byKey.get(key);
        return i == null ? NONE : i;
    }

    /** The identity of node {@code node} ({@link #key}); the root's is -1. */
    public long key(int node) {
        return node == ROOT ? -1 : key(slot[node], generation[node]);
    }

    /**
     * The deepest node whose box holds the point ({@code px}, {@code py} in UI pixels), the top-most where they
     * overlap (later siblings are above earlier ones); {@link #ROOT} when no node does.
     */
    public int at(float px, float py) {
        int found = ROOT;
        int scan = firstChild[ROOT];
        while (scan != NONE) {
            int hit = NONE;
            for (int c = lastChild[found]; c != NONE; c = previousSibling[c]) {
                if (px >= x[c] && py >= y[c] && px < x[c] + width[c] && py < y[c] + height[c]) {
                    hit = c;
                    break;
                }
            }
            if (hit == NONE) {
                break;
            }
            found = hit;
            scan = firstChild[found];
        }
        return found;
    }

    /** Nodes, the root included. */
    public int count() {
        return count;
    }

    /** The node player 0 focuses (the nearest node at or above the focused element), or {@link #NONE}. */
    public int focus() {
        return focus;
    }

    /** The store's version the snapshot was taken at. */
    public long version() {
        return version;
    }

    public int slot(int node) {
        return slot[node];
    }

    public int generation(int node) {
        return generation[node];
    }

    public int parent(int node) {
        return parent[node];
    }

    public int firstChild(int node) {
        return firstChild[node];
    }

    public int lastChild(int node) {
        return lastChild[node];
    }

    public int nextSibling(int node) {
        return nextSibling[node];
    }

    public int previousSibling(int node) {
        return previousSibling[node];
    }

    public Role role(int node) {
        return role[node];
    }

    /** The accessible name, or null. */
    public String name(int node) {
        return name[node];
    }

    /** The element id, or null. */
    public String id(int node) {
        return id[node];
    }

    /** The state bits ({@link UiState}: focused, disabled, selected, checked, expanded, editing, indeterminate). */
    public int state(int node) {
        return state[node];
    }

    /** Whether state {@code bit} ({@link UiState}) is on. */
    public boolean is(int node, int bit) {
        return (state[node] & bit) != 0;
    }

    /** The actions ({@link Semantics#ACTIVATE} and the others). */
    public int actions(int node) {
        return actions[node];
    }

    /** Whether the node offers action {@code bit} ({@link Semantics#ACTIVATE} and the others). */
    public boolean can(int node, int bit) {
        return (actions[node] & bit) != 0;
    }

    /** Whether the node has a value ({@link AccessibleValue}). */
    public boolean hasValue(int node) {
        return (valueFlags[node] & HAS_VALUE) != 0;
    }

    /** The value's text ("" for a password), or null without a value. */
    public String value(int node) {
        return value[node];
    }

    /** Whether the value cannot be set. */
    public boolean valueReadOnly(int node) {
        return (valueFlags[node] & READ_ONLY) != 0;
    }

    /** Whether the value is a password. */
    public boolean password(int node) {
        return (valueFlags[node] & PASSWORD) != 0;
    }

    /** The box's left in UI pixels. */
    public float x(int node) {
        return x[node];
    }

    /** The box's top in UI pixels. */
    public float y(int node) {
        return y[node];
    }

    public float width(int node) {
        return width[node];
    }

    public float height(int node) {
        return height[node];
    }

    /** Whether the node is toggled by activation: a checkbox, a toggle button, a switch. */
    public boolean toggles(int node) {
        Role r = role[node];
        return r == Role.CHECKBOX || r == Role.TOGGLE_BUTTON || r == Role.SWITCH;
    }

    /** A toggle's state: 0 off, 1 on, 2 indeterminate (UI Automation's {@code ToggleState} numbering). */
    public int toggleState(int node) {
        if ((state[node] & UiState.INDETERMINATE) != 0) {
            return 2;
        }
        return (state[node] & UiState.CHECKED) != 0 ? 1 : 0;
    }

    @Override
    @AllowAllocation("debug text")
    public String toString() {
        StringBuilder out = new StringBuilder();
        write(out, ROOT, 0);
        return out.toString();
    }

    @AllowAllocation("debug text")
    private void write(StringBuilder out, int node, int depth) {
        out.append("  ".repeat(depth)).append(role[node]);
        if (name[node] != null) {
            out.append(" \"").append(name[node]).append('"');
        }
        if (value[node] != null) {
            out.append(" = \"").append(value[node]).append('"');
        }
        if (node == focus) {
            out.append(" (focus)");
        }
        out.append('\n');
        for (int c = firstChild[node]; c != NONE; c = nextSibling[c]) {
            write(out, c, depth + 1);
        }
    }
}
