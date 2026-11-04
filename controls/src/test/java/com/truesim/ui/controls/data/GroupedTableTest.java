package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedList;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.controls.basic.ColumnMenu;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;

/**
 * Tables' columns hidden (by code, by the column menu, in the layout description), header groups spanning columns,
 * and rows grouped by columns: headers, counts and sums, collapsing, and the incremental update against a regrouping
 * from scratch under random changes.
 */
class GroupedTableTest {

    record Row(int id, int group, int sub, int value) {
        Row with(int g, int s, int v) {
            return new Row(id, g, s, v);
        }
    }

    static TableView<Row> table(DataFrame f, VersionedList<Row> rows) {
        TableView<Row> t = new TableView<>(f.ui, "Rows", new DataView<>(f.ui.store().clock(), rows),
            TableView.SelectionMode.MULTI_ROW);
        t.add(Column.<Row>ofInt("Id", Row::id).width(60));
        t.add(Column.<Row>ofInt("Group", Row::group).width(60));
        t.add(Column.<Row>ofInt("Sub", Row::sub).width(60));
        t.add(Column.<Row>ofInt("Value", Row::value).width(60));
        f.ui.root().add(t.root());
        return t;
    }

    static VersionedList<Row> rows(DataFrame f, int n, Random random) {
        VersionedList<Row> rows = f.ui.store().clock().list(4096);
        for (int i = 0; i < n; i++) {
            rows.add(new Row(i, random.nextInt(4), random.nextInt(3), random.nextInt(100)));
        }
        return rows;
    }

    @Test
    void rowsGroupUnderCollapsibleHeadersWithCountsAndSums() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        VersionedList<Row> rows = f.ui.store().clock().list();
        for (int i = 0; i < 9; i++) {
            rows.add(new Row(i, i % 3, 0, i));
        }
        TableView<Row> t = table(f, rows);
        t.groupBy(List.of(t.column(1)));
        t.groupSum(Row::value, "Total", 0);
        f.run(2);
        // header 0, rows 0 3 6; header 1, rows 1 4 7; header 2, rows 2 5 8
        assertThat(t.rows().size()).isEqualTo(12);
        assertThat(t.groupHeader(0)).isTrue();
        assertThat(t.groupHeader(4)).isTrue();
        assertThat(t.groupHeader(1)).isFalse();
        GroupedRows<Row> g = t.grouped();
        assertThat(g.count(4)).isEqualTo(3);
        assertThat(g.sum(4)).isEqualTo(1 + 4 + 7);
        assertThat(t.rows().get(5).id()).isEqualTo(1);
        assertThat(t.rowElement(4).type()).isEqualTo("table-group-row");
        assertThat(t.rowElement(4).is(UiState.EXPANDED)).isTrue();
        assertThat(t.rows().selectable(4)).isFalse();
        // collapsing hides its rows
        f.mouse(30, 20 + 4 * 20 + 10, Pointer.PRIMARY); // header 1 (the table header is 20 px, then rows)
        f.mouse(30, 20 + 4 * 20 + 10, 0);
        assertThat(g.collapsed(4)).isTrue();
        assertThat(t.rows().size()).isEqualTo(9);
        // keys: RIGHT opens the current header, LEFT closes it
        f.ui.interaction().focus(t.root(), 0);
        t.current(4);
        f.synthetic.press(UiCommand.RIGHT);
        f.run();
        assertThat(t.rows().size()).isEqualTo(12);
        f.synthetic.press(UiCommand.LEFT);
        f.run();
        assertThat(t.rows().size()).isEqualTo(9);
        // select all skips headers
        t.selection().all(t.rows());
        assertThat(t.selection().size()).isEqualTo(6);
        // the selection survives regrouping, editing goes through to the source
        t.groupBy(List.of());
        f.run();
        assertThat(t.rows().size()).isEqualTo(9);
        assertThat(t.selection().size()).isEqualTo(6);
        assertThat(t.viewPosition(3)).isEqualTo(3);
    }

    @Test
    void twoLevelsNestHeaders() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        VersionedList<Row> rows = f.ui.store().clock().list();
        rows.add(new Row(0, 0, 0, 1));
        rows.add(new Row(1, 0, 1, 2));
        rows.add(new Row(2, 1, 0, 3));
        TableView<Row> t = table(f, rows);
        t.groupBy(List.of(t.column(1), t.column(2)));
        f.run(2);
        // group 0: sub 0 (row 0), sub 1 (row 1); group 1: sub 0 (row 2)
        assertThat(t.rows().size()).isEqualTo(3 + 2 + 3);
        GroupedRows<Row> g = t.grouped();
        assertThat(g.level(0)).isZero();
        assertThat(g.level(1)).isEqualTo(1);
        assertThat(g.count(0)).isEqualTo(2);
        assertThat(g.header(3)).isTrue();
        assertThat(g.level(3)).isEqualTo(1);
        g.collapse(0, true);
        f.run();
        assertThat(t.rows().size()).isEqualTo(1 + 3);
        assertThat(g.collapsed(0)).isTrue();
    }

    /** The grouped rows in order as text: headers as "H<level>:<count>", rows as their ids. */
    private static String describe(GroupedRows<Row> g) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < g.size(); i++) {
            if (g.header(i)) {
                out.append('H').append(g.level(i)).append(':').append(g.count(i)).append(':')
                    .append((long) g.sum(i));
            } else {
                out.append(g.get(i).id());
            }
            out.append(' ');
        }
        return out.toString();
    }

    @Test
    void incrementalUpdatesEqualARegroupingFromScratch() {
        Random random = new Random(7);
        ChangeClock clock = new ChangeClock();
        VersionedList<Row> rows = clock.list(4096);
        for (int i = 0; i < 2_000; i++) {
            rows.add(new Row(i, random.nextInt(5), random.nextInt(3), random.nextInt(100)));
        }
        Comparator<Row> byGroup = Comparator.comparingInt(Row::group);
        Comparator<Row> bySub = Comparator.comparingInt(Row::sub);
        DataView<Row> view = new DataView<>(clock, rows).filter(r -> r.value() % 7 != 0);
        GroupedRows<Row> g = new GroupedRows<>(clock, view);
        ToDoubleFunction<Row> value = Row::value;
        g.levels(List.of(byGroup, bySub)).sum(value);
        view.sort(g.order(Comparator.comparingInt(Row::id)));
        g.refresh();
        g.collapse(0, true);
        for (int frame = 0; frame < 60; frame++) {
            for (int c = 0; c < 40; c++) {
                int i = random.nextInt(rows.size());
                Row r = rows.get(i);
                int roll = random.nextInt(10);
                rows.set(i, roll == 0 ? r.with(5 + random.nextInt(2), r.sub(), r.value()) // sometimes a new group
                    : r.with(roll < 4 ? random.nextInt(5) : r.group(), random.nextInt(3), random.nextInt(100)));
            }
            g.refresh();
            DataView<Row> fresh = new DataView<>(clock, rows).filter(r -> r.value() % 7 != 0);
            GroupedRows<Row> regrouped = new GroupedRows<>(clock, fresh);
            regrouped.levels(List.of(byGroup, bySub)).sum(value);
            fresh.sort(regrouped.order(Comparator.comparingInt(Row::id)));
            regrouped.refresh();
            if (g.header(0) && g.collapsed(0)) {
                regrouped.collapse(0, true);
            }
            assertThat(describe(g)).as("frame %d", frame).isEqualTo(describe(regrouped));
        }
    }

    @Test
    void columnsHideByCodeAndByTheMenuAndTheLayoutRoundTrips() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        VersionedList<Row> rows = rows(f, 50, new Random(1));
        TableView<Row> t = table(f, rows);
        Controls controls = new Controls(f.ui);
        ColumnMenu menu = ColumnMenu.attach(controls, t);
        t.freeze(1);
        f.run(2);
        Column<Row> sub = t.column(2);
        t.hide(sub, true);
        f.run(2);
        assertThat(t.columns()).extracting(Column::header).containsExactly("Id", "Group", "Value");
        assertThat(t.allColumns()).hasSize(4);
        assertThat(t.cell(0, sub)).isNull();
        assertThat(menu.menu().count()).isEqualTo(4);
        // the menu's check follows; unchecking another hides it
        assertThat(menu.shown(2)).isFalse();
        assertThat(menu.shown(1)).isTrue();
        // the context menu opens on the header
        f.mouse(30, 10, Pointer.SECONDARY);
        f.mouse(30, 10, 0);
        assertThat(menu.menu().isOpen()).isTrue();
        // hiding a frozen column unfreezes it
        t.hide(t.column(0), true);
        assertThat(t.frozen()).isZero();
        t.hide(t.allColumns().get(0), false);
        assertThat(t.frozen()).isZero(); // no frozen columns to join
        t.freeze(1);
        t.moveColumn(t.column(2), 1); // Value before Group
        t.column(1).width(77);
        t.sort(t.column(1), false);
        t.groupBy(List.of(t.column("Group")));
        String layout = t.layoutDescription();
        assertThat(layout).contains("column \"Sub\" 60 hidden", "frozen 1", "sort \"Value\" ascending",
            "group \"Group\"");
        // a fresh table takes it all back
        DataFrame g = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        TableView<Row> again = table(g, rows(g, 50, new Random(1)));
        again.layoutDescription(layout);
        g.run(2);
        assertThat(again.layoutDescription()).isEqualTo(layout);
        assertThat(again.columns()).extracting(Column::header).containsExactly("Id", "Value", "Group");
        assertThat(again.frozen()).isEqualTo(1);
        assertThat(again.groupedBy()).extracting(Column::header).containsExactly("Group");
        // unknown keys and lines are skipped
        again.layoutDescription("column \"Nope\" 10\nwhatever\nfrozen x\n");
        assertThat(again.columns()).hasSize(3);
    }

    @Test
    void headerGroupsSpanTheirAdjacentColumns() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.jdkText());
        TableView<Row> t = table(f, rows(f, 10, new Random(2)));
        t.headerGroup("Keys", List.of(t.column(1), t.column(2)));
        f.run(2);
        assertThat(t.headerGroupCells()).isEqualTo(1);
        float s = f.ui.layout().scale();
        assertThat(f.ui.layout().width(t.headerGroupCell(0).slot()) / s).isEqualTo(120);
        assertThat(t.headerGroupOf(t.column(1))).isEqualTo("Keys");
        t.moveColumn(t.column(2), 0); // Sub first: the group splits in two runs
        f.run(2);
        assertThat(t.headerGroupCells()).isEqualTo(2);
        t.hide(t.column(0), true); // Sub hidden: one run again
        f.run(2);
        assertThat(t.headerGroupCells()).isEqualTo(1);
        assertThat(f.ui.layout().width(t.headerGroupCell(0).slot()) / s).isEqualTo(60);
    }

    @Test
    void groupedRowsChangingAllocateNothing() {
        DataFrame f = new DataFrame(DataFrame.BOXES, UiTestAccess.nativeText());
        Random random = new Random(3);
        VersionedList<Row> rows = rows(f, 10_000, random);
        TableView<Row> t = table(f, rows);
        t.groupBy(List.of(t.column(1)));
        t.groupSum(Row::value, "Sum", 0);
        f.run(2);
        Row[] pool = new Row[512];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new Row(i, i % 4, i % 3, i % 100);
        }
        int[] step = {0};
        Runnable changing = () -> {
            for (int c = 0; c < 20; c++) {
                int s = step[0]++;
                int i = Math.floorMod(s * 7919, rows.size());
                Row p = pool[s % pool.length];
                rows.set(i, p);
            }
            f.run();
        };
        for (int i = 0; i < 100; i++) {
            changing.run();
        }
        AllocationAssertions.assertAllocationFree(changing);
        List<Integer> unused = new ArrayList<>();
        assertThat(unused).isEmpty();
    }
}
