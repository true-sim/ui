package com.truesim.ui.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.event.Interaction;
import java.util.ArrayList;
import java.util.List;

/**
 * The semantics (accessibility) tree of a store (design UI27): the elements with a role, nested as in the element
 * tree, elements without one ({@link Role#NONE}) passing their children up, each layer's tree in z-order. With an
 * {@link Interaction}, each node also says what can be done with it ({@link #ACTIVATE}, {@link #FOCUS} and the
 * others, from its listeners and flags), and interactive elements without a role show as {@link Role#NONE} nodes so
 * {@link #unnamed} finds them. Taken on demand, by an OS bridge (later) or a test, never per frame.
 */
public final class Semantics {

    /** Action bit: the element can be activated (it handles clicks). */
    public static final int ACTIVATE = 1;
    /** Action bit: the element can take focus. */
    public static final int FOCUS = 2;
    /** Action bit: the element shows a context menu. */
    public static final int SHOW_MENU = 4;
    /** Action bit: the element scrolls. */
    public static final int SCROLL = 8;
    /** Action bit: the element can be dragged. */
    public static final int DRAG = 16;
    /** Action bit: the element takes drops. */
    public static final int DROP = 32;
    /** Action bit: the element's text can be edited. */
    public static final int EDIT_TEXT = 64;

    private static final String[] ACTION_NAMES = {"activate", "focus", "show-menu", "scroll", "drag", "drop",
        "edit-text"};
    private static final int STATES = UiState.FOCUSED | UiState.DISABLED | UiState.SELECTED | UiState.CHECKED
        | UiState.EXPANDED | UiState.EDITING;

    /** One node: a role, a name, the element's state bits ({@link UiState}), its actions, and the nodes under it. */
    public record Node(Role role, String label, int slot, int state, int actions, List<Node> children) {

        /** Whether {@code bit} ({@link UiState}) is on. */
        public boolean is(int bit) {
            return (state & bit) != 0;
        }

        /** Whether the node offers action {@code bit} ({@link #ACTIVATE} and the others). */
        public boolean can(int bit) {
            return (actions & bit) != 0;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder();
            write(out, 0);
            return out.toString();
        }

        @AllowAllocation("debug text")
        private void write(StringBuilder out, int depth) {
            out.append("  ".repeat(depth)).append(role).append(label == null ? "" : " \"" + label + "\"");
            for (int bit = 1; bit < 1 << UiState.count(); bit <<= 1) {
                if ((state & bit) != 0) {
                    out.append(" :").append(UiState.name(bit));
                }
            }
            for (int i = 0; i < ACTION_NAMES.length; i++) {
                if ((actions & 1 << i) != 0) {
                    out.append(" +").append(ACTION_NAMES[i]);
                }
            }
            out.append('\n');
            for (Node child : children) {
                child.write(out, depth + 1);
            }
        }
    }

    private Semantics() {
    }

    /** The tree of every root of the store, without actions. */
    @AllowAllocation("taken on demand, not per frame")
    public static Node of(UiStore store) {
        return of(store, null);
    }

    /** The tree of every root of the store (layers in z-order when {@code interaction} is given), with actions. */
    @AllowAllocation("taken on demand, not per frame")
    public static Node of(UiStore store, Interaction interaction) {
        List<Node> top = new ArrayList<>();
        if (interaction != null) {
            Layers layers = interaction.layers();
            for (int i = 0; i < layers.count(); i++) {
                collect(store, interaction, layers.at(i).rootSlot(), top);
            }
        } else {
            for (int i = 0; i < store.rootCount(); i++) {
                collect(store, null, store.rootAt(i), top);
            }
        }
        Role rootRole = store.role(store.rootSlot());
        if (top.size() == 1 && rootRole != Role.NONE) {
            return top.getFirst();
        }
        return new Node(Role.GROUP, null, store.rootSlot(), 0, 0, top);
    }

    @AllowAllocation("taken on demand, not per frame")
    private static void collect(UiStore store, Interaction interaction, int slot, List<Node> into) {
        List<Node> children = new ArrayList<>();
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            collect(store, interaction, c, children);
        }
        Role role = store.role(slot);
        int actions = interaction == null ? 0 : interaction.semanticActions(slot);
        if (role == Role.NONE && actions == 0) {
            into.addAll(children);
        } else {
            into.add(new Node(role, store.label(slot), slot, store.state(slot) & STATES, actions,
                List.copyOf(children)));
        }
    }

    /**
     * The interactive nodes of {@code tree} that a screen reader could not announce: no role, or no label where the
     * role needs one (everything but groups, images, separators and layout roles). Controls' tests assert it is
     * empty.
     */
    @AllowAllocation("taken on demand, not per frame")
    public static List<Node> unnamed(Node tree) {
        List<Node> out = new ArrayList<>();
        unnamed(tree, out);
        return out;
    }

    @AllowAllocation("taken on demand, not per frame")
    private static void unnamed(Node node, List<Node> out) {
        if (node.actions() != 0) {
            boolean needsLabel = switch (node.role()) {
                case GROUP, IMAGE, SEPARATOR, LIST, TREE, TABLE, ROW, MENU, MENU_BAR, TAB_LIST, TOOLBAR, SCROLL_VIEW,
                     SCROLL_BAR, TAB_PANEL -> false;
                default -> true;
            };
            if (node.role() == Role.NONE || needsLabel && (node.label() == null || node.label().isEmpty())) {
                out.add(node);
            }
        }
        for (Node child : node.children()) {
            unnamed(child, out);
        }
    }
}
