package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedList;
import com.truesim.ui.controls.text.TextCellEditor;
import com.truesim.ui.controls.text.TextColumnFilter;
import com.truesim.ui.core.input.UiCommand;
import org.junit.jupiter.api.Test;

/** The text controls in the data controls' hooks: a text cell editor for strings and a filter row field. */
class TextTableHooksTest {

    record Row(String name, int n) {
        Row withName(String value) {
            return new Row(value, n);
        }
    }

    private static VersionedList<Row> rows(DataFrame f) {
        VersionedList<Row> list = f.ui.store().clock().list();
        for (int i = 0; i < 30; i++) {
            list.add(new Row((i % 2 == 0 ? "Alpha " : "Beta ") + i, i));
        }
        return list;
    }

    @Test
    void stringCellsEditInATextFieldCommittedByEnterAndCancelledByEscape() {
        DataFrame f = new DataFrame();
        VersionedList<Row> rows = rows(f);
        TableView<Row> t = new TableView<>(f.ui, "Rows", new DataView<>(f.ui.store().clock(), rows),
            TableView.SelectionMode.ROW);
        TextCellEditor editor = TextCellEditor.create("Name").validator(s -> s.length() > 0).maxLength(20);
        t.add(Column.ofText("Name", Row::name).width(120).editor(editor, Row::name, Row::withName));
        t.add(Column.ofInt("N", Row::n).width(60));
        f.ui.root().add(t.root());
        f.run();
        assertThat(t.edit(1, t.column(0))).isTrue();
        f.run(2);
        f.synthetic.type("Gamma");
        f.run();
        f.synthetic.press(UiCommand.SUBMIT);
        f.run();
        assertThat(t.editing()).isFalse();
        assertThat(rows.get(1).name()).isEqualTo("Gamma");
        // cancelled
        t.edit(2, t.column(0));
        f.run(2);
        f.synthetic.type("Delta");
        f.run();
        f.synthetic.press(UiCommand.CANCEL);
        f.run();
        assertThat(t.editing()).isFalse();
        assertThat(rows.get(2).name()).isEqualTo("Alpha 2");
        // empty text is refused: editing goes on
        t.edit(3, t.column(0));
        f.run(2);
        f.synthetic.press(UiCommand.DELETE_BACK);
        f.run();
        f.synthetic.press(UiCommand.SUBMIT);
        f.run();
        assertThat(t.editing()).isTrue();
        t.cancel();
        assertThat(rows.get(3).name()).isEqualTo("Beta 3");
    }

    @Test
    void aFilterFieldShowsOnlyRowsContainingItsText() {
        DataFrame f = new DataFrame();
        VersionedList<Row> rows = rows(f);
        TableView<Row> t = new TableView<>(f.ui, "Rows", new DataView<>(f.ui.store().clock(), rows),
            TableView.SelectionMode.ROW);
        Column<Row> name = Column.ofText("Name", Row::name).width(120);
        t.add(name);
        TextColumnFilter<Row> filter = TextColumnFilter.attach(f.ui, name, Row::name, "Filter names");
        f.ui.root().add(t.root());
        f.run();
        assertThat(name.filterView()).isEqualTo(filter.field().element());
        assertThat(t.view().size()).isEqualTo(30);
        f.ui.interaction().focus(filter.field().element());
        f.run();
        f.synthetic.type("beta 1");
        f.run(2);
        assertThat(filter.query()).isEqualTo("beta 1");
        assertThat(t.view().size()).isEqualTo(6); // Beta 1, 11, 13, 15, 17, 19
        f.synthetic.type("x");
        f.run(2);
        assertThat(t.view().size()).isZero();
        filter.field().text("");
        filter.changed(filter.field());
        f.run();
        assertThat(t.view().size()).isEqualTo(30);
    }
}
