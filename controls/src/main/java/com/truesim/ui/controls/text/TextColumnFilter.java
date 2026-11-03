package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.data.Column;
import java.util.function.Function;

/**
 * A text field in a table's filter row ({@link Column#filterView}): the column shows only rows whose text contains what
 * is typed, ignoring case, refiltered as it is typed (the table's data view moves only the changed rows). The field is
 * a {@code textfield} with style name {@code column-filter}; Escape empties it.
 *
 * @param <R> the row type
 */
public final class TextColumnFilter<R> implements TextEditor.Listener {

    private final Column<R> column;
    private final Function<? super R, ? extends CharSequence> text;
    private final TextEditor field;
    private String query = "";

    private TextColumnFilter(Ui ui, Column<R> column, Function<? super R, ? extends CharSequence> text, String label) {
        this.column = column;
        this.text = text;
        field = TextEditor.field(ui, "column-filter", label);
        field.listener(this);
        column.filterView(field.element());
    }

    /** Puts a filter field over {@code column}, matching the text {@code text} gives of each row. */
    @AllowAllocation("controls are made when a UI is built")
    public static <R> TextColumnFilter<R> attach(Ui ui, Column<R> column, Function<? super R, ? extends CharSequence> text,
        String label) {
        return new TextColumnFilter<>(ui, column, text, label);
    }

    /** The field. */
    public TextEditor field() {
        return field;
    }

    /** What the column filters by now. */
    public String query() {
        return query;
    }

    @Override
    @AllowAllocation("typing a filter makes its query and predicate")
    public void changed(TextEditor editor) {
        String q = editor.text().toString();
        if (q.equals(query)) {
            return;
        }
        query = q;
        column.filter(q.isEmpty() ? null : row -> contains(text.apply(row), q));
    }

    /** Whether {@code haystack} contains {@code needle}, ignoring case. Allocates nothing. */
    static boolean contains(CharSequence haystack, String needle) {
        if (haystack == null) {
            return false;
        }
        int n = needle.length();
        for (int i = 0; i + n <= haystack.length(); i++) {
            int k = 0;
            while (k < n && same(haystack.charAt(i + k), needle.charAt(k))) {
                k++;
            }
            if (k == n) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(char a, char b) {
        return a == b || Character.toLowerCase(a) == Character.toLowerCase(b);
    }
}
