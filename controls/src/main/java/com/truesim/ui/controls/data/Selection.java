package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.Versioned;
import com.truesim.common.state.VersionedSet;

/**
 * What is selected in a list, tree or table (design 5.6): items (by equality) in a {@link VersionedSet}, so the
 * selection survives sorting, filtering and rows moving, and views redraw only when it changed. Single or multiple,
 * with an anchor for range selection (Shift) and toggling (the shortcut modifier).
 *
 * @param <T> the item type
 */
public final class Selection<T> implements Versioned {

    /** How many items may be selected. */
    public enum Mode {
        /** None: rows only show focus. */
        NONE,
        /** At most one. */
        SINGLE,
        /** Any number, with ranges and toggling. */
        MULTIPLE
    }

    private final VersionedSet<T> items;
    private Mode mode;
    private int anchor = -1;

    /** A selection of {@code mode} stamped on {@code clock}. */
    @AllowAllocation("controls are made when a UI is built")
    public Selection(ChangeClock clock, Mode mode) {
        this.items = clock.set();
        this.mode = mode;
    }

    /** The mode. */
    public Mode mode() {
        return mode;
    }

    /** Sets the mode; a single selection keeps at most one item. Returns this. */
    @AllowAllocation("a user or program action")
    public Selection<T> mode(Mode value) {
        mode = value;
        if (value == Mode.NONE) {
            items.clear();
        } else if (value == Mode.SINGLE && items.size() > 1) {
            T keep = items.iterator().next();
            items.clear();
            items.add(keep);
        }
        return this;
    }

    /** Whether {@code item} is selected. */
    public boolean contains(T item) {
        return item != null && !items.isEmpty() && items.contains(item);
    }

    /** How many items are selected. */
    public int size() {
        return items.size();
    }

    /** The selected items (a live, versioned set). */
    public VersionedSet<T> items() {
        return items;
    }

    /** Selects only {@code item} (row {@code index}, the new anchor). */
    @AllowAllocation("a user or program action")
    public void select(int index, T item) {
        if (mode == Mode.NONE) {
            return;
        }
        if (items.size() != 1 || !items.contains(item)) {
            items.clear();
            items.add(item);
        }
        anchor = index;
    }

    /** Toggles {@code item} (row {@code index}, the new anchor); in a single selection selects it alone or none. */
    @AllowAllocation("a user or program action")
    public void toggle(int index, T item) {
        if (mode == Mode.NONE) {
            return;
        }
        if (items.contains(item)) {
            items.remove(item);
        } else {
            if (mode == Mode.SINGLE) {
                items.clear();
            }
            items.add(item);
        }
        anchor = index;
    }

    /**
     * Selects the rows from the anchor to {@code index} of {@code rows} (only {@code index} without an anchor or in a
     * single selection); with {@code add}, keeps what was selected.
     */
    @AllowAllocation("a user or program action")
    public void range(RowSource<? extends T> rows, int index, boolean add) {
        if (mode != Mode.MULTIPLE || anchor < 0 || anchor >= rows.size()) {
            select(index, rows.get(index));
            return;
        }
        if (!add) {
            items.clear();
        }
        int from = Math.min(anchor, index);
        int to = Math.max(anchor, index);
        for (int i = from; i <= to; i++) {
            if (rows.selectable(i)) {
                items.add(rows.get(i));
            }
        }
    }

    /**
     * Applies a box selection of rows {@code from..to} of {@code rows} (multiple selection only): {@code mode}
     * {@link BoxSelect#REPLACE} selects only them, {@link BoxSelect#ADD} adds them, {@link BoxSelect#TOGGLE} flips
     * each. Rows that are not {@linkplain RowSource#selectable selectable} are skipped; {@code anchorIndex} becomes the
     * range anchor. An empty range ({@code from > to}) with REPLACE selects nothing.
     */
    @AllowAllocation("a user action: the box is released")
    public void box(RowSource<? extends T> rows, int from, int to, int mode, int anchorIndex) {
        if (this.mode != Mode.MULTIPLE) {
            return;
        }
        if (mode == BoxSelect.REPLACE && !items.isEmpty()) {
            items.clear();
        }
        for (int i = Math.max(0, from); i <= to && i < rows.size(); i++) {
            if (!rows.selectable(i)) {
                continue;
            }
            T item = rows.get(i);
            if (mode == BoxSelect.TOGGLE && items.contains(item)) {
                items.remove(item);
            } else {
                items.add(item);
            }
        }
        anchor = anchorIndex;
    }

    /**
     * Whether {@code item} shows as selected while a box of {@code mode} ({@link BoxSelect}) that covers it or not
     * ({@code inBox}) is dragged: the selection itself changes only when the box is released.
     */
    public boolean previewed(T item, boolean inBox, int mode) {
        boolean now = contains(item);
        return switch (mode) {
            case BoxSelect.REPLACE -> inBox;
            case BoxSelect.ADD -> now || inBox;
            default -> now ^ inBox;
        };
    }

    /** Selects every row of {@code rows} (multiple selection only). */
    @AllowAllocation("a user or program action")
    public void all(RowSource<? extends T> rows) {
        if (mode != Mode.MULTIPLE) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.selectable(i)) {
                items.add(rows.get(i));
            }
        }
    }

    /** Selects nothing. */
    public void clear() {
        if (!items.isEmpty()) {
            items.clear();
        }
        anchor = -1;
    }

    /** The row range selection starts from, or -1. */
    public int anchor() {
        return anchor;
    }

    /** Sets the anchor. */
    public void anchor(int index) {
        anchor = index;
    }

    @Override
    public long version() {
        return items.version();
    }
}
