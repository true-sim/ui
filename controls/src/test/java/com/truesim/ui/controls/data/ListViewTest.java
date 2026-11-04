package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedList;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.DrawListDump;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Virtualized list boxes: windows, recycling, updates, selection, keys, type-ahead, reordering, semantics. */
class ListViewTest {

    static VersionedList<String> names(DataFrame f, int n) {
        VersionedList<String> list = f.ui.store().clock().list();
        for (int i = 0; i < n; i++) {
            list.add("item " + i);
        }
        return list;
    }

    /** A text renderer counting its binds. */
    static final class Counting implements ValueRenderer<String> {
        final ValueRenderer<Object> text = Renderers.text();
        int binds;
        int creates;

        @Override
        public Element create(int kind, ViewContext context) {
            creates++;
            return text.create(kind, context);
        }

        @Override
        public void bind(Element view, String value, CellState state) {
            binds++;
            text.bind(view, value, state);
        }
    }

    static ListView<String> list(DataFrame f, VersionedList<String> items, Counting renderer) {
        ListView<String> list = new ListView<>(f.ui, "Items", RowSource.of(items), renderer);
        f.ui.root().add(list.root());
        return list;
    }

    @Test
    void onlyTheVisibleRowsAreBoundAndScrollingRecyclesThem() {
        DataFrame f = new DataFrame(DataFrame.SHEET, UiTestAccess.jdkText());
        VersionedList<String> items = names(f, 10_000);
        ListView<String> list = ListView.of(f.ui, "Items", items);
        f.ui.root().add(list.root());
        f.run();
        assertThat(list.variableHeights()).isFalse();
        assertThat(list.extents()).isInstanceOf(FixedExtents.class);
        assertThat(list.firstBound()).isZero();
        assertThat(list.lastBound()).isEqualTo(12); // rows 0 to 10 show in 200 px, 2 more of overscan
        assertThat(list.vertical().range()).isEqualTo(10_000 * 20 - 200);
        f.wheel(50, 50, -5); // 100 px down
        assertThat(list.vertical().position()).isEqualTo(100);
        assertThat(list.firstBound()).isEqualTo(3);
        assertThat(list.lastBound()).isEqualTo(17);
        assertThat(list.rowElements()).isEqualTo(15); // 10 rows show, 11 partly, 2 more each side
        for (int i = 0; i < 50; i++) {
            f.wheel(50, 50, -3 - i % 5);
        }
        assertThat(list.rowElements()).isEqualTo(15); // recycled, never more
        assertThat(list.created()).isEqualTo(15);
        // the row at the top shows its item, at its offset in the viewport
        int top = list.extents().indexAt(list.vertical().position());
        Element row = list.rowElement(top);
        assertThat(f.ui.text(f.ui.store().element(f.ui.store().firstChild(row.slot()))).toString())
            .isEqualTo("item " + top);
        assertThat(f.ui.layout().y(row.slot()) - f.ui.layout().y(list.viewport().slot()))
            .isEqualTo((float) (top * 20 - list.vertical().position()));
        assertThat(list.indexOf(row)).isEqualTo(top);
        assertThat(list.rowElement(top + 100)).isNull();
    }

    @Test
    void memoryIsOfTheVisibleRowsWhateverTheListsSize() {
        int[] elements = new int[2];
        int[] sizes = {1_000, 1_000_000};
        for (int k = 0; k < 2; k++) {
            DataFrame f = new DataFrame();
            ListView<String> list = list(f, names(f, sizes[k]), new Counting());
            f.run();
            for (int i = 0; i < 20; i++) {
                f.wheel(50, 50, -40);
            }
            elements[k] = f.ui.store().count();
            assertThat(list.rowElements()).isLessThanOrEqualTo(15);
        }
        assertThat(elements[1]).isEqualTo(elements[0]);
    }

    @Test
    void aMillionRowsKeepTheirPlacesFarDown() {
        DataFrame f = new DataFrame();
        VersionedList<String> items = names(f, 1_000_000);
        ListView<String> list = list(f, items, new Counting());
        f.run();
        list.scrollTo(0, 19_000_000.0 + 7); // beyond float precision of plain offsets
        f.run();
        int top = list.extents().indexAt(list.vertical().position());
        assertThat(top).isEqualTo(950_000);
        Element row = list.rowElement(top);
        assertThat(f.ui.layout().y(row.slot()) - f.ui.layout().y(list.viewport().slot())).isEqualTo(-7);
        list.scrollTo(0, list.vertical().range());
        f.run();
        assertThat(list.lastBound()).isEqualTo(999_999);
    }

    @Test
    void changedRowsRebindAloneAndStructuralChangesOnlyTheWindow() {
        DataFrame f = new DataFrame();
        VersionedList<String> items = names(f, 1_000);
        Counting r = new Counting();
        ListView<String> list = list(f, items, r);
        f.run(2);
        r.binds = 0;
        f.run(5);
        assertThat(r.binds).isZero(); // idle
        items.set(5, "five");
        items.set(500, "far away"); // not shown: not bound
        f.run();
        assertThat(r.binds).isEqualTo(1);
        r.binds = 0;
        items.add(0, "new first"); // indices shift: the window is bound again, nothing more
        f.run();
        assertThat(r.binds).isEqualTo(list.lastBound() - list.firstBound() + 1);
        assertThat(r.binds).isLessThanOrEqualTo(13);
        r.binds = 0;
        items.add("appended at the end");
        f.run();
        assertThat(r.binds).isZero(); // the window's keys and values did not change
        assertThat(list.vertical().content()).isEqualTo(1_002 * 20);
        // a list emptied and filled again
        items.clear();
        f.run();
        assertThat(list.lastBound()).isEqualTo(-1);
        items.add("again");
        f.run();
        assertThat(list.rowElement(0)).isNotNull();
        assertThat(r.creates).isLessThanOrEqualTo(13);
    }

    @Test
    void clicksSelectShiftExtendsAndTheShortcutToggles() {
        DataFrame f = new DataFrame();
        VersionedList<String> items = names(f, 100);
        ListView<String> list = list(f, items, new Counting());
        list.selection().mode(Selection.Mode.MULTIPLE);
        f.run();
        f.mouse(50, 25, Pointer.PRIMARY); // row 1
        f.mouse(50, 25, 0);
        assertThat(list.selection().items()).containsExactly("item 1");
        assertThat(list.rowElement(1).is(UiState.SELECTED)).isTrue();
        assertThat(list.rowElement(1).is(UiState.FOCUSED)).isTrue(); // the current row of the focused list
        f.synthetic.modifiers(SyntheticInput.MOUSE, Pointer.SHIFT);
        f.mouse(50, 85, Pointer.PRIMARY); // row 4
        f.mouse(50, 85, 0);
        assertThat(list.selection().items()).containsExactlyInAnyOrder("item 1", "item 2", "item 3", "item 4");
        f.synthetic.modifiers(SyntheticInput.MOUSE, Pointer.SHORTCUT);
        f.mouse(50, 45, Pointer.PRIMARY); // row 2 toggled off
        f.mouse(50, 45, 0);
        assertThat(list.selection().items()).containsExactlyInAnyOrder("item 1", "item 3", "item 4");
        assertThat(list.rowElement(2).is(UiState.SELECTED)).isFalse();
        // the selection follows items, not indices
        items.add(0, "inserted");
        f.run();
        assertThat(list.rowElement(2).is(UiState.SELECTED)).isTrue(); // item 1, one down
        f.synthetic.modifiers(SyntheticInput.MOUSE, 0);
        int[] activated = {-1};
        list.onActivate(i -> activated[0] = i);
        f.mouse(50, 65, Pointer.PRIMARY);
        f.mouse(50, 65, 0);
        f.mouse(50, 65, Pointer.PRIMARY);
        f.mouse(50, 65, 0); // a double click
        assertThat(activated[0]).isEqualTo(3);
        list.selection().mode(Selection.Mode.SINGLE);
        assertThat(list.selection().size()).isLessThanOrEqualTo(1);
        list.selection().mode(Selection.Mode.NONE);
        assertThat(list.selection().size()).isZero();
    }

    @Test
    void keysMoveTheCurrentRowExtendTheSelectionAndScrollItIntoView() {
        DataFrame f = new DataFrame();
        VersionedList<String> items = names(f, 100);
        ListView<String> list = list(f, items, new Counting());
        list.selection().mode(Selection.Mode.MULTIPLE);
        f.run();
        f.ui.interaction().focus(list.root());
        f.synthetic.press(UiCommand.DOWN);
        f.run();
        assertThat(list.current()).isZero(); // the first press picks the top row
        f.synthetic.press(UiCommand.DOWN, UiCommand.DOWN);
        f.run();
        assertThat(list.current()).isEqualTo(2);
        assertThat(list.selection().items()).containsExactly("item 2");
        f.synthetic.command(UiCommand.DOWN, 0, UiInput.EXTEND_SELECTION);
        f.synthetic.command(UiCommand.DOWN, 0, UiInput.EXTEND_SELECTION);
        f.run();
        assertThat(list.selection().items()).containsExactlyInAnyOrder("item 2", "item 3", "item 4");
        f.synthetic.press(UiCommand.PAGE_DOWN);
        f.run();
        assertThat(list.current()).isEqualTo(13); // a page of 9 more rows
        assertThat(list.vertical().position()).isEqualTo(14 * 20 - 200); // revealed at the bottom
        assertThat(list.rowElement(13).is(UiState.FOCUS_VISIBLE)).isTrue();
        f.synthetic.press(UiCommand.END);
        f.run();
        assertThat(list.current()).isEqualTo(99);
        assertThat(list.vertical().position()).isEqualTo(list.vertical().range());
        f.synthetic.press(UiCommand.PAGE_UP, UiCommand.UP, UiCommand.HOME);
        f.run();
        assertThat(list.current()).isZero();
        assertThat(list.vertical().position()).isZero();
        f.synthetic.press(UiCommand.ACCEPT); // toggles in a multiple selection
        f.run();
        assertThat(list.selection().items()).isEmpty();
        f.synthetic.press(UiCommand.SELECT_ALL);
        f.run();
        assertThat(list.selection().size()).isEqualTo(100);
        list.selection().mode(Selection.Mode.SINGLE);
        int[] activated = {-1};
        list.onActivate(i -> activated[0] = i);
        f.synthetic.press(UiCommand.ACCEPT);
        f.run();
        assertThat(activated[0]).isZero();
        list.current(50);
        f.run();
        assertThat(list.vertical().position()).isEqualTo(51 * 20 - 200);
        f.synthetic.press(UiCommand.LEFT); // nothing for lists: focus may move on
        f.run();
    }

    @Test
    void typingFindsRowsByTheirStart() {
        DataFrame f = new DataFrame();
        VersionedList<String> items = f.ui.store().clock().list();
        for (String s : List.of("apple", "banana", "blueberry", "cherry", "Bread", "date")) {
            items.add(s);
        }
        ListView<String> list = list(f, items, new Counting());
        f.run();
        f.ui.interaction().focus(list.root());
        f.synthetic.type("b");
        f.run();
        assertThat(list.current()).isEqualTo(1);
        f.synthetic.type("l");
        f.run();
        assertThat(list.current()).isEqualTo(2); // "bl"
        f.run(70); // the search times out
        f.synthetic.type("b");
        f.run();
        assertThat(list.current()).isEqualTo(4); // the next "b", case-insensitive
        f.run(70);
        f.synthetic.type("z");
        f.run();
        assertThat(list.current()).isEqualTo(4);
        list.textOf(s -> s.substring(1));
        f.run(70);
        f.synthetic.type("h");
        f.run();
        assertThat(list.current()).isEqualTo(3); // cHerry
    }

    @Test
    void rowsReorderByDragAndDrop() {
        DataFrame f = new DataFrame(DataFrame.SHEET, UiTestAccess.jdkText());
        VersionedList<String> items = names(f, 10);
        ListView<String> list = list(f, items, new Counting());
        list.reorderable(true);
        assertThat(list.reorderable()).isTrue();
        f.run();
        f.mouse(50, 15, 0);
        f.mouse(50, 15, Pointer.PRIMARY); // row 0
        f.mouse(50, 40, Pointer.PRIMARY);
        f.mouse(50, 75, Pointer.PRIMARY); // over row 3's top half: before row 3
        assertThat(list.dropBefore()).isEqualTo(4);
        assertThat(DrawListDump.of(f.ui.layers()[0])).contains("a=(1.00 0.00 0.00 1.00)"); // the indicator
        f.mouse(50, 66, Pointer.PRIMARY);
        assertThat(list.dropBefore()).isEqualTo(3);
        f.mouse(50, 66, 0);
        assertThat(items.subList(0, 4)).containsExactly("item 1", "item 2", "item 0", "item 3");
        assertThat(list.dropBefore()).isEqualTo(-1);
        assertThat(list.current()).isEqualTo(2);
        // a custom move
        List<String> moves = new ArrayList<>();
        list.reorder((from, before) -> moves.add(from + ">" + before));
        f.mouse(50, 155, Pointer.PRIMARY); // row 7
        f.mouse(50, 130, Pointer.PRIMARY);
        f.mouse(50, 22, Pointer.PRIMARY); // before row 1
        f.mouse(50, 22, 0);
        assertThat(moves).containsExactly("7>1");
        // touch drags scroll instead
        list.reorderable(false);
        f.mouse(50, 15, Pointer.PRIMARY);
        f.mouse(50, 60, Pointer.PRIMARY);
        f.mouse(50, 60, 0);
        assertThat(moves).hasSize(1);
    }

    static final StyleSheet VARIABLE = Tss.compileString("variable.tss", """
        root { layout: flex; direction: column; align-items: stretch; }
        list-view { height: 200px; width: 300px; scroll-step: 20; }
        scroll-viewport { clip: true; }
        scroll-bar { width: 10px; }
        list-item { layout: flex; direction: column; }
        bars { layout: stack; direction: column; }
        panel { height: 10px; }
        """);

    /** Rows of 1 to 5 ten-pixel panels: heights 10 to 50. */
    static class Bars implements ValueRenderer<Integer> {
        @Override
        public int kind(Integer value) {
            return value % 5;
        }

        @Override
        public Element create(int kind, ViewContext context) {
            Element box = context.ui().element("bars", null);
            for (int i = 0; i <= kind; i++) {
                box.add(context.ui().panel(null));
            }
            return box;
        }

        @Override
        public void bind(Element view, Integer value, CellState state) {
            state.name("bar");
        }
    }

    @Test
    void rowsOfDifferentHeightsAreMeasuredAndTheTopRowStaysPut() {
        DataFrame f = new DataFrame(VARIABLE, null);
        VersionedList<Integer> items = f.ui.store().clock().list();
        for (int i = 0; i < 1_000; i++) {
            items.add(i);
        }
        ListView<Integer> list = new ListView<>(f.ui, "Bars", RowSource.of(items), new Bars());
        f.ui.root().add(list.root());
        f.run(3);
        assertThat(list.variableHeights()).isTrue();
        MeasuredExtents m = (MeasuredExtents) list.extents();
        assertThat(m.measured(0)).isTrue();
        assertThat(m.size(3)).isEqualTo(40);
        assertThat(m.offset(5)).isEqualTo(10 + 20 + 30 + 40 + 50);
        // the rows sit at their measured offsets
        Element row4 = list.rowElement(4);
        assertThat(f.ui.layout().y(row4.slot())).isEqualTo(100);
        list.scrollTo(0, 3_000);
        f.run(3);
        int top = m.indexAt(list.vertical().position());
        Element topRow = list.rowElement(top);
        float y = f.ui.layout().y(topRow.slot());
        f.run(3); // measuring more rows below does not move the top row
        assertThat(f.ui.layout().y(topRow.slot())).isEqualTo(y);
        assertThat(m.meanMeasured()).isBetween(25.0, 35.0);
        assertThat(m.estimate()).isPositive();
    }

    @Test
    void theListExposesItsRowsToAssistiveTechnology() {
        DataFrame f = new DataFrame(DataFrame.SHEET, UiTestAccess.jdkText());
        VersionedList<String> items = names(f, 30);
        ListView<String> list = ListView.of(f.ui, "Inventory", items);
        f.ui.root().add(list.root());
        f.run();
        list.selection().select(1, "item 1");
        f.run();
        Semantics.Node tree = Semantics.of(f.ui.store(), f.ui.interaction());
        String text = tree.toString();
        assertThat(text).contains("LIST \"Inventory\"").contains("LIST_ITEM \"item 0\"")
            .contains("LIST_ITEM \"item 1\" :selected");
        assertThat(Semantics.unnamed(tree)).isEmpty();
        items.set(0, "renamed");
        f.run();
        assertThat(Semantics.of(f.ui.store()).toString()).contains("LIST_ITEM \"renamed\"");
    }

    @Test
    void drawListGolden() {
        DataFrame f = new DataFrame(DataFrame.SHEET, null);
        VersionedList<Integer> items = f.ui.store().clock().list();
        for (int i = 0; i < 50; i++) {
            items.add(i);
        }
        ListView<Integer> list = new ListView<>(f.ui, "Golden", RowSource.of(items), new Bars() {
            @Override
            public int kind(Integer value) {
                return 0;
            }
        });
        f.ui.root().add(list.root());
        list.selection().select(2, 2);
        f.run(2);
        list.scrollTo(0, 30);
        f.run();
        // rows draw under the body's scroll-region clip (2), inside the viewport's (1)
        String expected = """
            box clip=2 0.00,-30.00 290.00,-10.00 a=(0.00 0.00 0.00 0.00) radii=0.00,0.00 0.00,0.00
            box clip=2 0.00,-10.00 290.00,10.00 a=(0.00 0.00 0.00 0.00) radii=0.00,0.00 0.00,0.00
            box clip=2 0.00,10.00 290.00,30.00 a=(0.00 0.00 1.00 1.00) radii=0.00,0.00 0.00,0.00
            """;
        String drawn = DrawListDump.of(f.ui.layers()[0]);
        assertThat(drawn).contains(expected.lines().toList().get(2));
        // the scroll bar's track, thumb at 30 of 800 over a 180-px track
        assertThat(drawn).contains("box 290.00,0.00 300.00,200.00 a=(0.05 0.05 0.05 1.00)"); // active
        assertThat(drawn).doesNotContain("200.00,10.00"); // the hidden horizontal bar draws nothing
    }

    @Test
    void scrollingAndChangingFramesAllocateNothing() {
        DataFrame f = new DataFrame(DataFrame.SHEET, UiTestAccess.nativeText());
        VersionedList<String> items = f.ui.store().clock().list();
        StringBuilder text = new StringBuilder();
        String[] pool = new String[64];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = "row " + i;
        }
        for (int i = 0; i < 10_000; i++) {
            items.add(pool[i % pool.length]);
        }
        ListView<String> list = ListView.of(f.ui, "Rows", items);
        f.ui.root().add(list.root());
        f.run(2);
        int[] step = {0};
        Runnable scrolling = () -> {
            int s = step[0]++;
            f.input.beginFrame();
            f.input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE).moveTo(50, 50 + s % 7).scroll(0, s % 40 < 20 ? -1 : 1);
            f.ui.update(f.width, f.height, f.input, f.now);
            f.now += 16_000_000L;
        };
        Runnable idle = () -> {
            f.input.beginFrame();
            f.ui.update(f.width, f.height, f.input, f.now);
            f.now += 16_000_000L;
        };
        Runnable changing = () -> {
            int s = step[0]++;
            for (int k = 0; k < 100; k++) {
                items.set((s * 37 + k * 101) % items.size(), pool[(s + k) % pool.length]);
            }
            f.input.beginFrame();
            f.ui.update(f.width, f.height, f.input, f.now);
            f.now += 16_000_000L;
        };
        for (int i = 0; i < 400; i++) {
            scrolling.run();
            changing.run();
        }
        AllocationAssertions.assertAllocationFree(idle);
        AllocationAssertions.assertAllocationFree(scrolling);
        AllocationAssertions.assertAllocationFree(changing);
        assertThat(text).isEmpty();
    }
}
