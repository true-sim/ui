package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedList;
import com.truesim.common.state.VersionedTree;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/**
 * Lists laid out across, wide rows scrolling sideways, tree loading rows, renderers chosen by the style key
 * {@code renderer}, and records rendered by their components.
 */
class ListsMoreTest {

    @Test
    void aHorizontalListVirtualizesAlongXAndMovesByLeftAndRight() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        VersionedList<String> items = ListViewTest.names(f, 1_000);
        ListView<String> list = ListView.of(f.ui, "Cards", items).horizontal(true);
        f.ui.root().add(list.root());
        f.run(2);
        assertThat(list.horizontalAxis()).isTrue();
        assertThat(list.variableHeights()).isFalse(); // a fixed width per item
        assertThat(list.horizontal().range()).isEqualTo(1_000 * 50 - 300);
        assertThat(list.vertical().range()).isZero();
        assertThat(list.firstBound()).isZero();
        assertThat(list.lastBound()).isEqualTo(8); // 6 show in 300, 2 more of overscan
        Element third = list.rowElement(2);
        assertThat(f.ui.layout().x(third.slot()) - f.ui.layout().x(list.viewport().slot())).isEqualTo(100);
        // the wheel scrolls across: the view only scrolls that way
        f.wheel(50, 50, -5);
        assertThat(list.horizontal().position()).isEqualTo(100);
        assertThat(list.firstBound()).isZero();
        assertThat(list.lastBound()).isEqualTo(10);
        // keys: RIGHT and LEFT move the current item; END reveals the last
        f.ui.interaction().focus(list.root(), 0);
        f.synthetic.press(UiCommand.RIGHT, UiCommand.RIGHT);
        f.run();
        assertThat(list.current()).isGreaterThan(0);
        int at = list.current();
        f.synthetic.press(UiCommand.LEFT);
        f.run();
        assertThat(list.current()).isEqualTo(at - 1);
        f.synthetic.press(UiCommand.END);
        f.run(2);
        assertThat(list.current()).isEqualTo(999);
        assertThat(list.horizontal().position()).isEqualTo(list.horizontal().range());
        assertThat(list.selection().contains("item 999")).isTrue();
        // and back down
        list.horizontal(false);
        f.run(2);
        assertThat(list.horizontal().range()).isZero();
        assertThat(list.vertical().range()).isEqualTo(1_000 * 20 - 200);
    }

    @Test
    void wideRowsScrollSideways() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        VersionedList<String> items = ListViewTest.names(f, 50);
        items.set(3, "a very long row ".repeat(12));
        ListView<String> list = ListView.of(f.ui, "Wide", items);
        f.ui.root().add(list.root());
        f.run(3);
        assertThat(list.horizontal().range()).isGreaterThan(100);
        float s = f.ui.layout().scale();
        float w0 = f.ui.layout().width(list.rowElement(0).slot()) / s;
        float w3 = f.ui.layout().width(list.rowElement(3).slot()) / s;
        assertThat(w0).isEqualTo(w3); // every row as wide as the widest
        list.scrollTo(80, 0);
        f.run();
        Element row = list.rowElement(0);
        assertThat(f.ui.layout().x(row.slot()) - f.ui.layout().x(list.viewport().slot())).isEqualTo(-80);
    }

    @Test
    void aLoadingNodeShowsAPlaceholderRowWhoseDotsChase() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.nativeText());
        VersionedTree<String> model = f.ui.store().clock().tree("root");
        VersionedTree.Node<String> remote = model.root().add("remote");
        model.root().add("local");
        TreeView<String> tree = new TreeView<>(f.ui, "Tree", model, Renderers.text());
        tree.lazy(remote, node -> tree.treeRows().loading(node)); // the children come later
        f.ui.root().add(tree.root());
        f.run();
        tree.expand(remote, true);
        f.run(2);
        assertThat(tree.rows().size()).isEqualTo(3); // remote, its placeholder, local
        assertThat(tree.treeRows().placeholder(1)).isTrue();
        assertThat(tree.rows().selectable(1)).isFalse();
        Element placeholder = tree.rowElement(1);
        assertThat(placeholder.type()).isEqualTo("tree-loading");
        assertThat(f.ui.store().label(placeholder.slot())).isEqualTo("Loading…");
        assertThat(tree.loadingRows()).isEqualTo(1);
        // one dot lit at a time, chasing
        UiStore store = f.ui.store();
        int indicator = store.nextSibling(store.firstChild(placeholder.slot()));
        int lit = litDot(f.ui, indicator);
        assertThat(lit).isBetween(0, 2);
        boolean moved = false;
        for (int i = 0; i < 20 && !moved; i++) {
            f.run();
            moved = litDot(f.ui, indicator) != lit;
        }
        assertThat(moved).isTrue();
        // keys pass over it without selecting it
        f.ui.interaction().focus(tree.root(), 0);
        f.synthetic.press(UiCommand.HOME, UiCommand.DOWN);
        f.run();
        assertThat(tree.current()).isEqualTo(1);
        assertThat(tree.selection().size()).isEqualTo(1); // remote still
        // allocation-free while it shows
        Runnable idle = () -> f.run();
        for (int i = 0; i < 30; i++) {
            idle.run();
        }
        AllocationAssertions.assertAllocationFree(idle);
        // the children arrive
        remote.add("a.bin");
        tree.treeRows().loaded(remote);
        f.run();
        assertThat(tree.rows().size()).isEqualTo(3); // remote, a.bin, local
        assertThat(tree.treeRows().placeholder(1)).isFalse();
        assertThat(tree.loadingRows()).isZero();
    }

    private static int litDot(Ui ui, int indicator) {
        UiStore store = ui.store();
        int d = 0;
        for (int dot = store.firstChild(indicator); dot != UiStore.NONE; dot = store.nextSibling(dot), d++) {
            if (ui.layout().width(dot) > 5 * ui.layout().scale()) {
                return d;
            }
        }
        return -1;
    }

    @Test
    void theStyleKeyRendererChoosesARendererByName() {
        StyleSheet sheet = Tss.compileString("named.tss", DataFrame.RULES + """
            list-view#scores { renderer: number; }
            table-header-cell#hp { renderer: check; }
            """);
        DataFrame f = new DataFrame(sheet, UiTestAccess.jdkText());
        VersionedList<Object> values = f.ui.store().clock().list();
        values.add(1.23456);
        values.add(7);
        ValueRenderer<Object> text = Renderers.text();
        ListView<Object> list = new ListView<>(f.ui, "Scores", RowSource.of(values), text);
        list.renderers(new ValueRenderers());
        f.ui.root().add(list.root());
        f.run(2);
        assertThat(list.renderer()).isSameAs(text); // no id yet: its own
        list.root().id("scores");
        f.run(2);
        assertThat(list.renderer().viewType()).isEqualTo("number");
        Element view = f.ui.store().element(f.ui.store().firstChild(list.rowElement(0).slot()));
        assertThat(view.id()).isEqualTo("number");
        assertThat(f.ui.text(view).toString()).isEqualTo("1.235");
        list.root().id(null);
        f.run(2);
        assertThat(list.renderer()).isSameAs(text);
    }

    @Test
    void recordsShowTheirComponentsWithoutAllocating() {
        DataFrame f = new DataFrame(DataFrame.SHEET, UiTestAccess.nativeText());
        VersionedList<Object> values = f.ui.store().clock().list();
        for (int i = 0; i < 200; i++) {
            values.add(new Spot("spot " + (i % 7), i % 13, i * 0.5, i % 2 == 0, new Inner(i % 5)));
        }
        ListView<Object> list = ListView.of(f.ui, "Spots", values);
        f.ui.root().add(list.root());
        f.run(2);
        Element view = f.ui.store().element(f.ui.store().firstChild(list.rowElement(1).slot()));
        assertThat(view.type()).isEqualTo("record-view");
        UiStore store = f.ui.store();
        int field = store.firstChild(view.slot());
        assertThat(store.id(field)).isEqualTo("name");
        assertThat(f.ui.text(store.element(store.lastChild(field))).toString()).isEqualTo("spot 1");
        int count = store.nextSibling(field);
        assertThat(f.ui.text(store.element(store.lastChild(count))).toString()).isEqualTo("1");
        int ratio = store.nextSibling(count);
        assertThat(f.ui.text(store.element(store.lastChild(ratio))).toString()).isEqualTo("0.5");
        int flag = store.nextSibling(ratio);
        assertThat(store.type(store.lastChild(flag))).isEqualTo("value-check");
        int inner = store.nextSibling(flag);
        assertThat(store.type(store.lastChild(inner))).isEqualTo("record-view"); // nested
        assertThat(f.ui.store().label(list.rowElement(1).slot())).isEqualTo("spot 1");
        int[] step = {0};
        Runnable scrolling = () -> {
            list.scrollTo(0, (step[0]++ % 40) * 37);
            f.run();
        };
        for (int i = 0; i < 80; i++) {
            scrolling.run();
        }
        AllocationAssertions.assertAllocationFree(scrolling);
    }

    record Inner(int depth) {
    }

    record Spot(String name, int count, double ratio, boolean flag, Inner inner) {
    }
}
