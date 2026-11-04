package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedList;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.testkit.DrawListDump;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tables: virtualized rows and columns, sorting, filters, resizing, moving and freezing columns, editing, keys. */
class TableViewTest {

    enum Kind { SCOUT, TANK, MEDIC }

    record Unit(String name, int hp, double speed, boolean alive, Kind kind) {
        Unit withHp(int value) {
            return new Unit(name, value, speed, alive, kind);
        }

        Unit withAlive(boolean value) {
            return new Unit(name, hp, speed, value, kind);
        }

        Unit withKind(Kind value) {
            return new Unit(name, hp, speed, alive, value);
        }
    }

    static final Kind[] KINDS = Kind.values();

    static VersionedList<Unit> units(DataFrame f, int n) {
        VersionedList<Unit> list = f.ui.store().clock().list();
        for (int i = 0; i < n; i++) {
            list.add(new Unit("unit " + i, (i * 37) % 100, i * 0.25, i % 3 != 0, KINDS[i % 3]));
        }
        return list;
    }

    /** Name 80, HP 60, Speed 60, Alive 40, Kind 60: 300 wide in a 290-wide viewport. */
    static TableView<Unit> table(DataFrame f, VersionedList<Unit> units, TableView.SelectionMode mode) {
        TableView<Unit> t = new TableView<>(f.ui, "Units", new DataView<>(f.ui.store().clock(), units), mode);
        t.add(Column.ofText("Name", Unit::name).width(80));
        t.add(Column.ofInt("HP", Unit::hp).width(60).editor(Editors.integer(0, 100, 5), Unit::hp, Unit::withHp));
        t.add(Column.ofDouble("Speed", Unit::speed, 2).width(60));
        t.add(Column.ofBoolean("Alive", Unit::alive).width(40).editor(Editors.bool(), Unit::alive,
            Unit::withAlive));
        t.add(Column.of("Kind", Unit::kind, Renderers.enumeration(), Enum::compareTo).width(60)
            .editor(Editors.enumeration(Kind.class), Unit::kind, Unit::withKind));
        f.ui.root().add(t.root());
        return t;
    }

    static String text(DataFrame f, Element cell) {
        UiStore store = f.ui.store();
        return f.ui.text(store.element(store.firstChild(cell.slot()))).toString();
    }

    @Test
    void cellsShowTheirColumnsAndTheHeaderSitsAboveTheRows() {
        DataFrame f = new DataFrame();
        VersionedList<Unit> units = units(f, 100);
        TableView<Unit> t = table(f, units, TableView.SelectionMode.ROW);
        f.run();
        Column<Unit> hp = t.column(1);
        Element cell = t.cell(3, hp);
        assertThat(text(f, cell)).isEqualTo("11");
        assertThat(text(f, t.cell(3, t.column(2)))).isEqualTo("0.75");
        assertThat(f.ui.layout().x(cell.slot())).isEqualTo(80);
        assertThat(f.ui.layout().y(cell.slot())).isEqualTo(20 + 60); // below the 20-px header
        assertThat(f.ui.layout().width(cell.slot())).isEqualTo(60);
        assertThat(t.cell(3, t.column(3)).is(UiState.CHECKED)).isFalse(); // the check is the view
        assertThat(t.vertical().viewport()).isEqualTo(170); // 200 less the header and the horizontal bar
        assertThat(t.horizontal().range()).isEqualTo(10);
        assertThat(t.columns()).extracting(Column::header).containsExactly("Name", "HP", "Speed", "Alive", "Kind");
        Semantics.Node sem = Semantics.of(f.ui.store(), f.ui.interaction());
        assertThat(sem.toString()).contains("TABLE \"Units\"").contains("COLUMN_HEADER \"HP\"")
            .contains("CELL \"unit 3\"").contains("CELL \"0.75\"");
        assertThat(Semantics.unnamed(sem)).isEmpty();
    }

    @Test
    void rowsAndColumnsVirtualize() {
        DataFrame f = new DataFrame();
        VersionedList<Unit> units = units(f, 100_000);
        TableView<Unit> t = new TableView<>(f.ui, "Wide", new DataView<>(f.ui.store().clock(), units),
            TableView.SelectionMode.ROW);
        for (int c = 0; c < 20; c++) {
            int k = c;
            t.add(Column.<Unit>ofInt("C" + c, u -> u.hp() + k).width(50));
        }
        f.ui.root().add(t.root());
        f.run();
        assertThat(t.firstShownColumn()).isZero();
        assertThat(t.lastShownColumn()).isEqualTo(5); // 290 px of 50-px columns
        int elements = f.ui.store().count();
        f.synthetic.modifiers(SyntheticInput.MOUSE, Pointer.SHIFT);
        f.wheel(100, 100, -10); // 200 px sideways
        assertThat(t.horizontal().position()).isEqualTo(200);
        assertThat(t.firstShownColumn()).isEqualTo(4);
        assertThat(t.lastShownColumn()).isEqualTo(9);
        Element cell = t.cell(0, t.column(5));
        assertThat(f.ui.layout().x(cell.slot())).isEqualTo(50);
        assertThat(t.cell(0, t.column(0))).isNull(); // scrolled away
        f.synthetic.modifiers(SyntheticInput.MOUSE, 0);
        for (int i = 0; i < 30; i++) {
            f.wheel(100, 100, -20);
        }
        int warm = f.ui.store().count();
        assertThat(warm).isLessThanOrEqualTo(elements + 40); // a few spare cells, no more
        for (int i = 0; i < 100; i++) {
            f.wheel(100, 100, -20 - i % 3);
        }
        assertThat(f.ui.store().count()).isEqualTo(warm); // cells recycled
        assertThat(t.rowElements()).isLessThanOrEqualTo(14);
    }

    @Test
    void headersSortAndShiftAddsColumnsToTheSort() {
        DataFrame f = new DataFrame();
        VersionedList<Unit> units = units(f, 30);
        TableView<Unit> t = table(f, units, TableView.SelectionMode.ROW);
        f.run();
        f.mouse(100, 10, Pointer.PRIMARY); // the HP header
        f.mouse(100, 10, 0);
        f.run();
        assertThat(t.sortedBy(t.column(1))).isEqualTo(1);
        assertThat(t.rows().get(0).hp()).isZero();
        assertThat(t.rows().get(29).hp()).isEqualTo(99);
        assertThat(DrawListDump.of(f.ui.layers()[0])).contains("a=(0.00 1.00 0.00 1.00)"); // [sort=ascending]
        f.mouse(100, 10, Pointer.PRIMARY);
        f.mouse(100, 10, 0);
        f.run();
        assertThat(t.sortedBy(t.column(1))).isEqualTo(-1);
        assertThat(t.rows().get(0).hp()).isEqualTo(99);
        // shift: kind first (by hp descending), then add kind as the second key
        f.synthetic.modifiers(SyntheticInput.MOUSE, Pointer.SHIFT);
        f.mouse(270, 10, Pointer.PRIMARY);
        f.mouse(270, 10, 0);
        f.run();
        assertThat(t.sortedBy(t.column(4))).isEqualTo(1);
        f.mouse(270, 10, Pointer.PRIMARY);
        f.mouse(270, 10, 0);
        assertThat(t.sortedBy(t.column(4))).isEqualTo(-1);
        t.unsort();
        f.run();
        assertThat(t.rows().get(0).name()).isEqualTo("unit 0");
        // a column without an order does not sort
        Column<Unit> plain = Column.ofRow("Row", Renderers.ofText(Unit::name), null);
        t.add(plain);
        t.sort(plain, false);
        assertThat(t.sortedBy(plain)).isZero();
        assertThat(plain.sortable()).isFalse();
    }

    @Test
    void filtersHideRowsAndAFilterRowShowsTheirViews() {
        DataFrame f = new DataFrame();
        VersionedList<Unit> units = units(f, 30);
        TableView<Unit> t = table(f, units, TableView.SelectionMode.ROW);
        f.run();
        t.column(3).filter(Unit::alive);
        f.run();
        assertThat(t.rows().size()).isEqualTo(20);
        t.filter(u -> u.hp() > 50);
        f.run();
        for (int i = 0; i < t.rows().size(); i++) {
            assertThat(t.rows().get(i).alive() && t.rows().get(i).hp() > 50).isTrue();
        }
        t.filter(null);
        t.column(3).filter(null);
        f.run();
        assertThat(t.rows().size()).isEqualTo(30);
        // a filter view (a text field, say) in the filter row
        Element field = f.ui.panel(null);
        t.column(0).filterView(field);
        f.run();
        assertThat(f.ui.store().parent(f.ui.store().parent(field.slot()))).isNotEqualTo(UiStore.NONE);
        assertThat(t.vertical().viewport()).isEqualTo(150); // the header grew by the filter row
        assertThat(t.column(0).filterView()).isSameAs(field);
    }

    @Test
    void headersResizeByTheirGripAndMoveByDragging() {
        DataFrame f = new DataFrame();
        VersionedList<Unit> units = units(f, 30);
        TableView<Unit> t = table(f, units, TableView.SelectionMode.ROW);
        f.run();
        // Name's grip: 76..80
        f.mouse(78, 10, 0);
        f.mouse(78, 10, Pointer.PRIMARY);
        f.mouse(118, 10, Pointer.PRIMARY);
        assertThat(t.column(0).width()).isEqualTo(120);
        f.mouse(-200, 10, Pointer.PRIMARY);
        assertThat(t.column(0).width()).isEqualTo(30); // column-min-width
        f.mouse(-200, 10, 0);
        f.run();
        assertThat(f.ui.layout().x(t.cell(0, t.column(1)).slot())).isEqualTo(30);
        assertThat(t.sortedBy(t.column(0))).isZero(); // the grip does not sort
        // drag Speed's header (x 90..150) before Name
        f.mouse(120, 10, Pointer.PRIMARY);
        f.mouse(110, 10, Pointer.PRIMARY);
        f.mouse(5, 10, Pointer.PRIMARY);
        assertThat(t.dropBefore()).isZero();
        f.mouse(5, 10, 0);
        assertThat(t.columns()).extracting(Column::header).containsExactly("Speed", "Name", "HP", "Alive", "Kind");
        assertThat(t.dropBefore()).isEqualTo(-1);
        f.run();
        assertThat(f.ui.layout().x(t.cell(0, t.column(1)).slot())).isEqualTo(60); // Name after Speed
        t.moveColumn(t.column(0), 5);
        assertThat(t.columns()).extracting(Column::header).containsExactly("Name", "HP", "Alive", "Kind", "Speed");
        t.column(0).resizable(false).movable(false).sortable(false);
        assertThat(t.column(0).resizable()).isFalse();
    }

    @Test
    void frozenColumnsStayWhileTheRestScrollsSideways() {
        DataFrame f = new DataFrame();
        VersionedList<Unit> units = units(f, 30);
        TableView<Unit> t = table(f, units, TableView.SelectionMode.ROW);
        t.column(0).width(150);
        t.freeze(1);
        assertThat(t.frozen()).isEqualTo(1);
        f.run();
        t.scrollTo(60, 0);
        f.run();
        Element name = t.cell(0, t.column(0));
        assertThat(f.ui.layout().x(name.slot())).isZero();
        assertThat(t.cell(0, t.column(1))).isNull(); // HP (150..210) is under Name now
        assertThat(f.ui.layout().x(t.cell(0, t.column(2)).slot())).isEqualTo(210 - 60);
        UiStore store = f.ui.store();
        assertThat(store.type(store.parent(name.slot()))).isEqualTo("table-frozen");
        // the frozen part draws over the scrolled one: after it
        assertThat(store.nextSibling(store.parent(t.cell(0, t.column(2)).slot())))
            .isEqualTo(store.parent(name.slot()));
        // a frozen column moves within the frozen ones only
        t.moveColumn(t.column(0), 4);
        assertThat(t.column(0).header()).isEqualTo("Name");
        t.freeze(0);
        f.run();
        assertThat(store.type(store.parent(t.cell(0, t.column(0)).slot()))).isEqualTo("table-cells");
    }

    @Test
    void cellsEditInlineAndCommitOrCancel() {
        DataFrame f = new DataFrame();
        VersionedList<Unit> units = units(f, 30);
        TableView<Unit> t = table(f, units, TableView.SelectionMode.ROW);
        f.run();
        // double click HP of row 1 (37): rows start below the 20-px header
        f.mouse(110, 50, Pointer.PRIMARY);
        f.mouse(110, 50, 0);
        f.mouse(110, 50, Pointer.PRIMARY);
        f.mouse(110, 50, 0);
        assertThat(t.editing()).isTrue();
        assertThat(t.cell(1, t.column(1)).is(UiState.EDITING)).isTrue();
        f.synthetic.press(UiCommand.UP, UiCommand.UP); // +5 twice
        f.synthetic.press(UiCommand.ACCEPT);
        f.run();
        assertThat(t.editing()).isFalse();
        assertThat(units.get(1).hp()).isEqualTo(47);
        assertThat(text(f, t.cell(1, t.column(1)))).isEqualTo("47");
        assertThat(f.ui.interaction().focused(0)).isEqualTo(t.root());
        // typed digits, submitted
        t.edit(2, t.column(1));
        f.run();
        f.synthetic.type("42");
        f.run();
        f.synthetic.press(UiCommand.SUBMIT);
        f.run();
        assertThat(units.get(2).hp()).isEqualTo(42);
        // cancelled
        t.edit(2, t.column(1));
        f.run();
        f.synthetic.press(UiCommand.DOWN, UiCommand.CANCEL);
        f.run();
        assertThat(units.get(2).hp()).isEqualTo(42);
        // an invalid number keeps editing
        t.edit(2, t.column(1));
        f.run();
        f.synthetic.press(UiCommand.DELETE_BACK, UiCommand.DELETE_BACK);
        f.run();
        f.synthetic.type("-");
        f.run();
        f.synthetic.press(UiCommand.ACCEPT);
        f.run();
        assertThat(t.editing()).isTrue();
        t.cancel();
        // booleans toggle, enums cycle
        t.edit(0, t.column(3));
        f.run(); // the editor takes focus once laid out
        f.synthetic.press(UiCommand.RIGHT, UiCommand.ACCEPT);
        f.run();
        assertThat(units.get(0).alive()).isTrue();
        t.edit(0, t.column(4));
        f.run(); // the editor takes focus once laid out
        f.synthetic.press(UiCommand.RIGHT, UiCommand.RIGHT, UiCommand.LEFT, UiCommand.ACCEPT);
        f.run();
        assertThat(units.get(0).kind()).isEqualTo(Kind.TANK);
        // focus leaving the editor commits
        t.edit(5, t.column(1));
        f.run(); // the editor takes focus once laid out
        f.synthetic.press(UiCommand.UP);
        f.run();
        f.ui.interaction().blur(0);
        f.run();
        assertThat(units.get(5).hp()).isEqualTo((5 * 37) % 100 + 5);
        // not editable
        assertThat(t.edit(0, t.column(0))).isFalse();
        // scrolled away while editing: committed
        t.edit(0, t.column(1));
        f.run(); // the editor takes focus once laid out
        f.synthetic.press(UiCommand.UP);
        f.run();
        t.scrollTo(0, 400);
        f.run();
        assertThat(t.editing()).isFalse();
        assertThat(units.get(0).hp()).isEqualTo(5);
    }

    @Test
    void cellSelectionMovesByArrowsAndEditsOnAccept() {
        DataFrame f = new DataFrame();
        VersionedList<Unit> units = units(f, 30);
        TableView<Unit> t = table(f, units, TableView.SelectionMode.CELL);
        f.run();
        f.mouse(110, 70, Pointer.PRIMARY); // row 2, HP
        f.mouse(110, 70, 0);
        assertThat(t.current()).isEqualTo(2);
        assertThat(t.currentColumn()).isEqualTo(1);
        assertThat(t.cell(2, t.column(1)).is(UiState.SELECTED)).isTrue();
        assertThat(t.rowElement(2).is(UiState.SELECTED)).isFalse(); // the cell, not the row
        f.synthetic.press(UiCommand.RIGHT, UiCommand.RIGHT, UiCommand.RIGHT, UiCommand.DOWN);
        f.run();
        assertThat(t.currentColumn()).isEqualTo(4);
        assertThat(t.current()).isEqualTo(3);
        assertThat(t.horizontal().position()).isEqualTo(10); // Kind revealed
        f.synthetic.press(UiCommand.ACCEPT, UiCommand.RIGHT, UiCommand.ACCEPT);
        f.run();
        assertThat(units.get(3).kind()).isEqualTo(Kind.SCOUT); // MEDIC + 1
        f.synthetic.press(UiCommand.LEFT, UiCommand.LEFT, UiCommand.LEFT, UiCommand.LEFT, UiCommand.LEFT);
        f.run();
        assertThat(t.currentColumn()).isZero();
        assertThat(t.selectionMode()).isEqualTo(TableView.SelectionMode.CELL);
        assertThat(t.columnAt(-5)).isEqualTo(-1);
        assertThat(t.columnAt(500)).isEqualTo(-1);
        f.synthetic.press(UiCommand.ACCEPT); // Name is not editable: selects
        f.run();
        assertThat(t.editing()).isFalse();
    }

    @Test
    void multipleRowsSelectAndArrowsScrollSidewaysInRowModes() {
        DataFrame f = new DataFrame();
        VersionedList<Unit> units = units(f, 30);
        TableView<Unit> t = table(f, units, TableView.SelectionMode.MULTI_ROW);
        f.run();
        f.mouse(20, 50, Pointer.PRIMARY); // row 1
        f.mouse(20, 50, 0);
        f.synthetic.modifiers(SyntheticInput.MOUSE, Pointer.SHIFT);
        f.mouse(20, 90, Pointer.PRIMARY); // row 3
        f.mouse(20, 90, 0);
        assertThat(t.selection().size()).isEqualTo(3);
        assertThat(t.rowElement(2).is(UiState.SELECTED)).isTrue();
        f.synthetic.press(UiCommand.RIGHT);
        f.run();
        assertThat(t.horizontal().position()).isEqualTo(10);
        assertThat(t.view()).isNotNull();
    }

    @Test
    void scrollingATableAllocatesNothing() {
        DataFrame f = new DataFrame(DataFrame.SHEET, UiTestAccess.nativeText());
        VersionedList<Unit> units = units(f, 20_000);
        TableView<Unit> t = table(f, units, TableView.SelectionMode.ROW);
        f.run(2);
        int[] step = {0};
        Runnable scrolling = () -> {
            int s = step[0]++;
            f.input.beginFrame();
            f.input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE).moveTo(100, 100 + s % 5)
                .scroll(0, s % 60 < 30 ? -1 : 1);
            f.ui.update(f.width, f.height, f.input, f.now);
            f.now += 16_000_000L;
        };
        for (int i = 0; i < 600; i++) {
            scrolling.run();
        }
        AllocationAssertions.assertAllocationFree(scrolling);
        List<String> none = new ArrayList<>();
        assertThat(none).isEmpty();
        assertThat(t.rowElements()).isLessThanOrEqualTo(14);
    }
}
