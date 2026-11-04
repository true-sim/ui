package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.ui.controls.data.Column;
import com.truesim.ui.controls.data.TableView;
import java.util.List;

/**
 * A table's column menu (design 5.6): the context menu of its header (right click, the menu key, a gamepad's menu
 * button on the header), a check item per column showing and hiding it. The checks follow the table (a column hidden
 * by code, a layout restored) and the table follows the checks; the last shown column cannot be hidden. What the
 * table hides is in its layout description ({@link TableView#layoutDescription()}).
 *
 * <pre>{@code
 * ColumnMenu.attach(controls, table);
 * }</pre>
 */
public final class ColumnMenu implements Controls.Polled {

    private final TableView<?> table;
    private final Menu menu;
    private final List<? extends Column<?>> columns;
    private final VersionedBoolean[] shown;
    private final long[] seen;

    @AllowAllocation("controls are built once")
    private <R> ColumnMenu(Controls controls, TableView<R> table, String name) {
        this.table = table;
        this.menu = new Menu(controls, name);
        List<Column<R>> all = table.allColumns();
        this.columns = all;
        shown = new VersionedBoolean[all.size()];
        seen = new long[all.size()];
        for (int i = 0; i < all.size(); i++) {
            shown[i] = controls.clock().booleanValue(!table.hidden(all.get(i)));
            menu.check(all.get(i).header(), shown[i]);
            seen[i] = shown[i].version();
        }
        menu.attachContext(table.header());
        controls.poll(this);
    }

    /** Gives {@code table}'s header a column menu named "Columns"; add columns first. Returns the menu. */
    @AllowAllocation("controls are built once")
    public static <R> ColumnMenu attach(Controls controls, TableView<R> table) {
        return new ColumnMenu(controls, table, "Columns");
    }

    /** Whether the check of column {@code index} (of the table's columns when attached) is on. */
    public boolean shown(int index) {
        return shown[index].get();
    }

    /** The menu. */
    public Menu menu() {
        return menu;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void poll(long nanos) {
        TableView raw = table;
        for (int i = 0; i < shown.length; i++) {
            Column c = columns.get(i);
            if (shown[i].version() != seen[i]) {
                raw.hide(c, !shown[i].get()); // a check changed: the table follows
            }
            boolean on = !raw.hidden(c);
            if (shown[i].get() != on) {
                shown[i].set(on); // the table changed (or refused): the check follows
            }
            seen[i] = shown[i].version();
        }
    }
}
