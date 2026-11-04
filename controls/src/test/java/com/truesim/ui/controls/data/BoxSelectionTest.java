package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedList;
import com.truesim.common.state.VersionedTree;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.testkit.SyntheticInput;
import org.junit.jupiter.api.Test;

/**
 * Box selection (Dear ImGui 1.91): from empty space or from rows of views whose rows do not drag, with Shift adding
 * and the shortcut toggling, scrolling past the edges (rows never bound selected by index), CANCEL, a click in empty
 * space, tables and trees, the free canvas helper, and no allocation while dragging.
 */
class BoxSelectionTest {

    static VersionedList<String> names(DataFrame f, int n) {
        return ListViewTest.names(f, n);
    }

    static ListView<String> list(DataFrame f, int n) {
        ListView<String> list = ListView.of(f.ui, "Items", names(f, n));
        list.selection().mode(Selection.Mode.MULTIPLE);
        f.ui.root().add(list.root());
        return list;
    }

    @Test
    void aBoxFromEmptySpaceSelectsTheRowsItCoversOnRelease() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        ListView<String> list = list(f, 5); // rows 0 to 100, empty below
        f.run();
        f.mouse(50, 150, Pointer.PRIMARY); // empty space
        f.mouse(50, 140, Pointer.PRIMARY); // past the drag threshold: the box starts
        assertThat(list.boxing()).isTrue();
        f.mouse(50, 30, Pointer.PRIMARY);
        assertThat(list.boxFrom()).isEqualTo(1);
        assertThat(list.boxTo()).isEqualTo(4);
        assertThat(list.selection().size()).isZero(); // nothing changes until released
        assertThat(list.rowElement(2).is(UiState.SELECTED)).isTrue(); // the rows show the result
        assertThat(list.rowElement(0).is(UiState.SELECTED)).isFalse();
        Element box = list.selectionBox();
        assertThat(f.ui.layout().shown(box.slot())).isTrue();
        assertThat(f.ui.layout().height(box.slot())).isEqualTo(120);
        f.mouse(50, 30, 0);
        assertThat(list.boxing()).isFalse();
        assertThat(f.ui.layout().shown(box.slot())).isFalse();
        assertThat(list.selection().items()).containsExactlyInAnyOrder("item 1", "item 2", "item 3", "item 4");
        // a click in empty space selects nothing
        f.mouse(50, 170, Pointer.PRIMARY);
        f.mouse(50, 170, 0);
        assertThat(list.selection().size()).isZero();
    }

    @Test
    void shiftAddsAndTheShortcutToggles() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        ListView<String> list = list(f, 6);
        f.run();
        list.selection().select(0, "item 0");
        list.selection().toggle(2, "item 2");
        f.synthetic.modifiers(SyntheticInput.MOUSE, Pointer.SHIFT);
        f.mouse(50, 180, Pointer.PRIMARY);
        f.mouse(50, 170, Pointer.PRIMARY);
        f.mouse(50, 90, Pointer.PRIMARY); // rows 4 and 5
        assertThat(list.rowElement(0).is(UiState.SELECTED)).isTrue(); // kept, shown while dragging
        f.mouse(50, 90, 0);
        assertThat(list.selection().items()).containsExactlyInAnyOrder("item 0", "item 2", "item 4", "item 5");
        f.synthetic.modifiers(SyntheticInput.MOUSE, Pointer.SHORTCUT);
        f.mouse(50, 180, Pointer.PRIMARY);
        f.mouse(50, 170, Pointer.PRIMARY);
        f.mouse(50, 50, Pointer.PRIMARY); // rows 2 to 5 flip
        assertThat(list.rowElement(2).is(UiState.SELECTED)).isFalse();
        assertThat(list.rowElement(3).is(UiState.SELECTED)).isTrue();
        f.mouse(50, 50, 0);
        assertThat(list.selection().items()).containsExactlyInAnyOrder("item 0", "item 3");
        // CANCEL drops a box: the selection stays as it was
        f.synthetic.modifiers(SyntheticInput.MOUSE, 0);
        f.mouse(50, 180, Pointer.PRIMARY);
        f.mouse(50, 170, Pointer.PRIMARY);
        f.mouse(50, 10, Pointer.PRIMARY);
        assertThat(list.boxing()).isTrue();
        f.synthetic.press(UiCommand.CANCEL);
        f.run();
        assertThat(list.boxing()).isFalse();
        f.mouse(50, 10, 0);
        assertThat(list.selection().items()).containsExactlyInAnyOrder("item 0", "item 3");
    }

    @Test
    void draggingPastTheEdgeScrollsAndSelectsRowsNeverBound() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        ListView<String> list = list(f, 10_000); // rows do not drag: a box starts on a row too
        f.run();
        float rate = DataTunables.BOX_SCROLL_RATE.get();
        try {
            DataTunables.BOX_SCROLL_RATE.set(200);
            f.mouse(50, 50, Pointer.PRIMARY); // row 2
            f.mouse(50, 60, Pointer.PRIMARY);
            assertThat(list.boxing()).isTrue();
            boolean skipped = false;
            int lastSeen = list.lastBound();
            for (int i = 0; i < 10; i++) {
                f.mouse(50, 400, Pointer.PRIMARY); // far below: fast
                skipped |= list.firstBound() > lastSeen + 1; // rows between were never bound
                lastSeen = list.lastBound();
            }
            assertThat(skipped).isTrue();
            assertThat(list.vertical().position()).isGreaterThan(5_000);
            int to = list.boxTo();
            f.mouse(50, 400, 0);
            assertThat(list.selection().size()).isEqualTo(to - 2 + 1);
            assertThat(list.selection().contains("item 100")).isTrue();
            assertThat(list.selection().contains("item 1")).isFalse();
        } finally {
            DataTunables.BOX_SCROLL_RATE.set(rate);
        }
        // the keys are unaffected: Shift+Down still extends from the anchor
        f.synthetic.command(UiCommand.HOME, 0, 0);
        f.run();
        assertThat(list.current()).isZero();
    }

    @Test
    void reorderableRowsDragThemselvesAndBoxOnlyFromEmptySpace() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        ListView<String> list = list(f, 4).reorderable(true);
        f.run();
        f.mouse(50, 10, Pointer.PRIMARY); // row 0
        f.mouse(50, 30, Pointer.PRIMARY);
        assertThat(list.boxing()).isFalse(); // a row drag
        f.mouse(50, 30, 0);
        f.mouse(50, 150, Pointer.PRIMARY);
        f.mouse(50, 140, Pointer.PRIMARY);
        assertThat(list.boxing()).isTrue(); // empty space
        f.mouse(50, 140, 0);
        list.boxSelect(false);
        f.mouse(50, 150, Pointer.PRIMARY);
        f.mouse(50, 140, Pointer.PRIMARY);
        assertThat(list.boxing()).isFalse();
        f.mouse(50, 140, 0);
        // single selection: no box
        list.boxSelect(true);
        list.selection().mode(Selection.Mode.SINGLE);
        f.mouse(50, 150, Pointer.PRIMARY);
        f.mouse(50, 140, Pointer.PRIMARY);
        assertThat(list.boxing()).isFalse();
        f.mouse(50, 140, 0);
    }

    @Test
    void tablesAndTreesBoxSelectRows() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        VersionedList<TableViewTest.Unit> units = TableViewTest.units(f, 30);
        TableView<TableViewTest.Unit> t = TableViewTest.table(f, units, TableView.SelectionMode.MULTI_ROW);
        f.run();
        float top = f.ui.layout().y(t.viewport().slot());
        f.mouse(20, top + 5, Pointer.PRIMARY);
        f.mouse(20, top + 15, Pointer.PRIMARY);
        f.mouse(20, top + 65, Pointer.PRIMARY);
        f.mouse(20, top + 65, 0);
        assertThat(t.selection().size()).isEqualTo(4); // rows 0 to 3
        t.root().detach();

        DataFrame g = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        VersionedTree<String> model = g.ui.store().clock().tree("root");
        for (int i = 0; i < 5; i++) {
            model.root().add("node " + i);
        }
        TreeView<String> tree = new TreeView<>(g.ui, "Tree", model, Renderers.text());
        tree.selection().mode(Selection.Mode.MULTIPLE);
        g.ui.root().add(tree.root());
        g.run();
        g.mouse(50, 150, Pointer.PRIMARY);
        g.mouse(50, 140, Pointer.PRIMARY);
        g.mouse(50, 50, Pointer.PRIMARY);
        g.mouse(50, 50, 0);
        assertThat(tree.selection().size()).isEqualTo(3); // rows 2 to 4
    }

    @Test
    void theCanvasHelperSelectsItemsTouchingTheBox() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        Element canvas = f.ui.panel("canvas");
        f.ui.styles().inline(canvas.slot(), InlineStyle.builder()
            .set(Styles.LAYOUT, com.truesim.ui.style.value.LayoutKind.NONE)
            .set(Styles.HEIGHT, Length.px(200)).set(Styles.POINTER_EVENTS, com.truesim.ui.core.event.PointerEvents.ALL)
            .build());
        f.ui.root().add(canvas);
        boolean[] on = new boolean[4];
        BoxSelect select = new BoxSelect(f.ui, canvas, new BoxSelect.Items() {
            public int count() {
                return 4;
            }

            public float x(int i) {
                return 10 + i * 50;
            }

            public float y(int i) {
                return 10;
            }

            public float width(int i) {
                return 30;
            }

            public float height(int i) {
                return 30;
            }

            public boolean selected(int i) {
                return on[i];
            }

            public void selected(int i, boolean value) {
                on[i] = value;
            }
        });
        f.run();
        f.mouse(5, 100, Pointer.PRIMARY);
        f.mouse(15, 100, Pointer.PRIMARY);
        f.mouse(75, 20, Pointer.PRIMARY); // touches items 0 and 1
        assertThat(select.active()).isTrue();
        assertThat(on).containsExactly(true, true, false, false);
        f.mouse(75, 120, Pointer.PRIMARY); // below them: none
        assertThat(on).containsExactly(false, false, false, false);
        f.mouse(175, 20, Pointer.PRIMARY);
        f.mouse(175, 20, 0);
        assertThat(on).containsExactly(true, true, true, true);
        assertThat(select.active()).isFalse();
        f.synthetic.modifiers(SyntheticInput.MOUSE, Pointer.SHORTCUT);
        f.mouse(5, 100, Pointer.PRIMARY);
        f.mouse(15, 100, Pointer.PRIMARY);
        f.mouse(75, 20, Pointer.PRIMARY);
        select.cancel();
        assertThat(on).containsExactly(true, true, true, true);
        f.mouse(75, 20, 0);
        f.synthetic.modifiers(SyntheticInput.MOUSE, 0);
        f.mouse(5, 150, Pointer.PRIMARY);
        f.mouse(5, 150, 0); // a click on empty canvas
        assertThat(on).containsExactly(false, false, false, false);
    }

    @Test
    void draggingABoxAllocatesNothing() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.nativeText());
        ListView<String> list = list(f, 10_000);
        f.run(2);
        f.mouse(50, 50, Pointer.PRIMARY);
        f.mouse(50, 60, Pointer.PRIMARY);
        assertThat(list.boxing()).isTrue();
        int[] step = {0};
        Runnable dragging = () -> {
            int s = step[0]++;
            f.input.beginFrame();
            // inside, then past the bottom edge (scrolling), then back up
            float y = s % 60 < 20 ? 60 + s % 20 * 6 : s % 60 < 40 ? 230 : 120 - s % 20 * 5;
            f.input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE).moveTo(50, y).buttons(Pointer.PRIMARY);
            f.ui.update(f.width, f.height, f.input, f.now);
            f.now += 16_000_000L;
        };
        for (int i = 0; i < 200; i++) {
            dragging.run(); // every row in reach bound once
        }
        AllocationAssertions.assertAllocationFree(dragging);
        assertThat(list.boxing()).isTrue();
    }
}
