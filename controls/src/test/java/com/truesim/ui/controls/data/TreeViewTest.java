package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.VersionedTree;
import com.truesim.common.state.VersionedTree.Node;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tree views: lazy children, expand and collapse by pointer and keys, drag and drop, updates, semantics. */
class TreeViewTest {

    static final ValueRenderer<String> TEXT = Renderers.text();

    /** a (a1, a2 lazy: a2x, a2y), b, c (c1). */
    static VersionedTree<String> model(DataFrame f) {
        VersionedTree<String> model = f.ui.store().clock().tree("root");
        Node<String> a = model.root().add("a");
        a.add("a1");
        a.add("a2");
        model.root().add("b");
        model.root().add("c").add("c1");
        return model;
    }

    static TreeView<String> tree(DataFrame f, VersionedTree<String> model, List<String> loads) {
        TreeView<String> tree = new TreeView<>(f.ui, "Files", model, TEXT);
        tree.lazy(model.root().child(0).child(1), node -> {
            loads.add(node.value());
            node.add("a2x");
            node.add("a2y");
        });
        f.ui.root().add(tree.root());
        return tree;
    }

    static List<String> rows(TreeView<String> tree) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tree.rows().size(); i++) {
            out.add(tree.rows().get(i).value());
        }
        return out;
    }

    @Test
    void lazyChildrenLoadOnTheFirstExpandAndKeysOpenAndClose() {
        DataFrame f = new DataFrame();
        List<String> loads = new ArrayList<>();
        VersionedTree<String> model = model(f);
        TreeView<String> tree = tree(f, model, loads);
        f.run();
        assertThat(rows(tree)).containsExactly("a", "b", "c");
        f.ui.interaction().focus(tree.root());
        f.synthetic.press(UiCommand.DOWN, UiCommand.RIGHT); // a: expand
        f.run();
        assertThat(rows(tree)).containsExactly("a", "a1", "a2", "b", "c");
        assertThat(tree.rowElement(0).is(UiState.EXPANDED)).isTrue();
        f.synthetic.press(UiCommand.RIGHT, UiCommand.DOWN); // into a1, then a2
        f.run();
        assertThat(tree.current()).isEqualTo(2);
        Node<String> a2 = tree.rows().get(2);
        assertThat(tree.treeRows().pending(a2)).isTrue();
        assertThat(tree.treeRows().leaf(a2)).isFalse();
        f.synthetic.press(UiCommand.RIGHT); // loads
        f.run();
        assertThat(loads).containsExactly("a2");
        assertThat(rows(tree)).containsExactly("a", "a1", "a2", "a2x", "a2y", "b", "c");
        f.synthetic.press(UiCommand.LEFT, UiCommand.LEFT); // collapse a2, then to its parent
        f.run();
        assertThat(tree.current()).isZero();
        tree.treeRows().expanded(a2, true); // loaded once only
        assertThat(loads).hasSize(1);
        f.synthetic.press(UiCommand.LEFT); // collapse a
        f.run();
        assertThat(rows(tree)).containsExactly("a", "b", "c");
        f.synthetic.press(UiCommand.DOWN, UiCommand.RIGHT, UiCommand.LEFT); // b is a leaf: nothing opens
        f.run();
        assertThat(tree.current()).isEqualTo(1);
        // the expander of a leaf is disabled (themes hide it); indents grow with depth
        UiStore store = f.ui.store();
        Element b = tree.rowElement(1);
        int expander = store.nextSibling(store.firstChild(b.slot()));
        assertThat(store.state(expander) & UiState.DISABLED).isNotZero();
    }

    @Test
    void clicksOnExpandersAndDoubleClicksToggleNodes() {
        DataFrame f = new DataFrame();
        List<String> loads = new ArrayList<>();
        TreeView<String> tree = tree(f, model(f), loads);
        f.run();
        UiStore store = f.ui.store();
        int expander = store.nextSibling(store.firstChild(tree.rowElement(2).slot())); // c's
        float x = f.ui.layout().x(expander) + 3;
        float y = f.ui.layout().y(expander) + 3;
        f.mouse(x, y, Pointer.PRIMARY);
        f.mouse(x, y, 0);
        assertThat(rows(tree)).containsExactly("a", "b", "c", "c1");
        int indent = store.firstChild(tree.rowElement(3).slot());
        assertThat(f.ui.layout().width(indent)).isEqualTo(12); // depth 1
        f.mouse(100, 5, Pointer.PRIMARY); // a's row: a double click
        f.mouse(100, 5, 0);
        f.mouse(100, 5, Pointer.PRIMARY);
        f.mouse(100, 5, 0);
        assertThat(rows(tree)).containsExactly("a", "a1", "a2", "b", "c", "c1");
    }

    @Test
    void nodesMoveByDragAndDrop() {
        DataFrame f = new DataFrame();
        List<String> loads = new ArrayList<>();
        VersionedTree<String> model = model(f);
        TreeView<String> tree = tree(f, model, loads).draggable(true);
        f.run();
        // drag b (row 1, y 20..40) into c's middle (row 2, y 40..60)
        f.mouse(100, 30, Pointer.PRIMARY);
        f.mouse(100, 40, Pointer.PRIMARY);
        f.mouse(100, 50, Pointer.PRIMARY);
        assertThat(tree.dropParent().value()).isEqualTo("c");
        assertThat(tree.rowElement(2).is(UiState.DROP_TARGET)).isTrue();
        f.mouse(100, 50, 0);
        assertThat(model.root().child(1).value()).isEqualTo("c");
        assertThat(model.root().child(1).child(1).value()).isEqualTo("b");
        assertThat(rows(tree)).containsExactly("a", "c", "c1", "b"); // c opened
        // drag b above a (the top quarter of row 0): first at the top level
        f.mouse(100, 70, Pointer.PRIMARY);
        f.mouse(100, 50, Pointer.PRIMARY);
        f.mouse(100, 2, Pointer.PRIMARY);
        assertThat(tree.dropParent()).isSameAs(model.root());
        assertThat(tree.dropIndex()).isZero();
        f.mouse(100, 2, 0);
        assertThat(rows(tree)).containsExactly("b", "a", "c", "c1");
        // a node cannot go into its own subtree
        f.mouse(100, 50, Pointer.PRIMARY); // c
        f.mouse(100, 60, Pointer.PRIMARY);
        f.mouse(100, 70, Pointer.PRIMARY); // c1's middle
        assertThat(tree.dropParent()).isNull();
        f.mouse(100, 70, 0);
        assertThat(rows(tree)).containsExactly("b", "a", "c", "c1");
        // below the last row: the end of the top level
        f.mouse(100, 25, Pointer.PRIMARY); // a
        f.mouse(100, 40, Pointer.PRIMARY);
        f.mouse(100, 150, Pointer.PRIMARY);
        f.mouse(100, 150, 0);
        assertThat(rows(tree)).containsExactly("b", "c", "c1", "a");
        assertThatThrownBy(() -> model.root().remove()).isInstanceOf(IllegalStateException.class);
        Node<String> c = model.root().child(1);
        assertThatThrownBy(() -> c.move(c.child(0), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valueChangesRebindTheirRowAndStructureChangesTheWindow() {
        DataFrame f = new DataFrame();
        List<String> loads = new ArrayList<>();
        VersionedTree<String> model = model(f);
        TreeView<String> tree = tree(f, model, loads);
        f.run();
        long structure = model.structureVersion();
        Node<String> b = model.root().child(1);
        b.value("bee");
        b.value("bee");
        f.run();
        assertThat(model.structureVersion()).isEqualTo(structure);
        assertThat(f.ui.text(f.ui.store().element(f.ui.store().lastChild(tree.rowElement(1).slot()))).toString())
            .isEqualTo("bee");
        b.touch();
        Node<String> d = model.root().add(1, "d");
        f.run();
        assertThat(rows(tree)).containsExactly("a", "d", "bee", "c");
        d.remove();
        f.run();
        assertThat(rows(tree)).containsExactly("a", "bee", "c");
        assertThat(d.parent()).isNull();
        assertThat(b.depth()).isOne();
        assertThat(tree.treeRows().rowOf(d)).isEqualTo(-1);
        Node<String> slow = model.root().add("slow");
        TreeRows<String> rows = tree.treeRows();
        tree.lazy(slow, rows::loading);
        rows.expanded(slow, true);
        assertThat(rows.isLoading(slow)).isTrue();
        assertThat(rows.pending(slow)).isTrue();
        slow.add("late");
        rows.loaded(slow);
        f.run();
        assertThat(rows(tree)).endsWith("slow", "late");
        assertThat(rows.tree()).isSameAs(model);
        assertThat(tree.expanded(slow)).isTrue();
        assertThat(rows.expanded(model.root())).isTrue();
        assertThat(tree.model()).isSameAs(model);
        // many value changes overflow the log: the window reconciles
        for (int i = 0; i < 1024 + 10; i++) {
            b.value("b" + i);
        }
        f.run();
        assertThat(tree.rows().get(1).value()).startsWith("b");
    }

    @Test
    void theTreeExposesItsItems() {
        DataFrame f = new DataFrame();
        TreeView<String> tree = tree(f, model(f), new ArrayList<>());
        f.run();
        tree.expand(tree.rows().get(0), true);
        f.run();
        Semantics.Node sem = Semantics.of(f.ui.store(), f.ui.interaction());
        assertThat(sem.toString()).contains("TREE \"Files\"").contains("TREE_ITEM \"a\" :expanded")
            .contains("TREE_ITEM \"a1\"");
        assertThat(Semantics.unnamed(sem)).isEmpty();
    }
}
