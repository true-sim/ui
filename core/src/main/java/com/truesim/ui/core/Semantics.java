package com.truesim.ui.core;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * The semantics (accessibility) tree of a store (design UI27): the elements with a role, nested as in the element
 * tree, elements without one ({@link Role#NONE}) passing their children up. Taken on demand, by an OS bridge (later)
 * or a test, never per frame.
 */
public final class Semantics {

    /** One node: a role, a name, and the nodes under it. */
    public record Node(Role role, String label, int slot, List<Node> children) {

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder();
            write(out, 0);
            return out.toString();
        }

        @AllowAllocation("debug text")
        private void write(StringBuilder out, int depth) {
            out.append("  ".repeat(depth)).append(role).append(label == null ? "" : " \"" + label + "\"").append('\n');
            for (Node child : children) {
                child.write(out, depth + 1);
            }
        }
    }

    private Semantics() {
    }

    /** The tree under the store's root. */
    @AllowAllocation("taken on demand, not per frame")
    public static Node of(UiStore store) {
        List<Node> top = new ArrayList<>();
        collect(store, store.rootSlot(), top);
        Role rootRole = store.role(store.rootSlot());
        if (top.size() == 1 && rootRole != Role.NONE) {
            return top.getFirst();
        }
        return new Node(Role.GROUP, null, store.rootSlot(), top);
    }

    @AllowAllocation("taken on demand, not per frame")
    private static void collect(UiStore store, int slot, List<Node> into) {
        List<Node> children = new ArrayList<>();
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            collect(store, c, children);
        }
        Role role = store.role(slot);
        if (role == Role.NONE) {
            into.addAll(children);
        } else {
            into.add(new Node(role, store.label(slot), slot, List.copyOf(children)));
        }
    }
}
