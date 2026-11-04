package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedList;
import com.truesim.common.state.VersionedValue;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.DataFormat;
import com.truesim.ui.core.event.DragOperation;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.Direction;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.Facts;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * A virtualized table, a data grid (design 5.6): {@link Column}s defined in code over a sorted and filtered
 * {@link DataView}, rows and columns virtualized (only the visible rows have elements, and in them only the visible
 * columns' cells, recycled per renderer and kind), a header that sorts (click; Shift adds a column to the sort),
 * resizes (drag a header's edge) and moves columns (drag a header), frozen columns on the left, hidden columns, a
 * filter row or per-column filters, inline editing with a {@link ValueEditor} per column, keyboard cell navigation
 * and row, multi-row or cell selection (multi-row: box selection too, see {@link VirtualView}), rows grouped by
 * columns' values under collapsible group headers ({@link #groupBy}, incremental: {@link GroupedRows}), header groups
 * spanning several columns ({@link #headerGroup}), and a layout description of all that for saving
 * ({@link #layoutDescription()}).
 *
 * <p><b>Elements and styles.</b> The root is a {@code table-view} (role TABLE); above the viewport a
 * {@code table-header} holds, when header groups exist, the {@code table-header-group-row} of
 * {@code table-header-group-cell}s (a label each, spanning their columns), the {@code table-header-row} of
 * {@code table-header-cell}s (role COLUMN_HEADER: a label, a {@code table-sort-indicator}, a
 * {@code table-column-grip}; facts {@code [sort=ascending]} or {@code [sort=descending]} when sorted; style key
 * {@code renderer} chooses the column's renderer by name) and, when a column has a filter view, the
 * {@code table-filter-row} of {@code table-filter-cell}s. Rows are {@code table-row}s (role ROW) of
 * {@code table-cell}s (role CELL, {@code :selected} and {@code :focused} in cell selection, {@code :editing} while
 * edited); frozen cells sit in the row's {@code table-frozen} part, drawn over the scrolling {@code table-cells}. Group
 * headers are {@code table-group-row}s ({@code :expanded} when open; {@code [level=0]}, {@code [level=1]}, ...): a
 * {@code table-group-indent} ({@code group-indent} times the level wide), a {@code table-group-expander}, labels
 * {@code table-group-column} (the column's header), the column's cell view of the group's value,
 * {@code table-group-count} and {@code table-group-sum}. A {@code table-column-drop-indicator} shows where a dragged
 * column would go. A fixed row height comes from {@code table-row}'s {@code height}.
 *
 * <pre>{@code
 * TableView<Unit> table = TableView.of(ui, "Units", clock, units);
 * table.add(Column.ofText("Name", Unit::name).width(160));
 * table.add(Column.ofInt("HP", Unit::hp).editor(Editors.integer(0, 999, 1), Unit::hp, Unit::withHp));
 * table.freeze(1);
 * table.groupBy(List.of(table.column(2)));
 * }</pre>
 *
 * @param <R> the row type
 */
public final class TableView<R> extends VirtualView<R> {

    /** How cells are selected. */
    public enum SelectionMode {
        /** One row. */
        ROW,
        /** Any rows (Shift ranges, shortcut toggles, box selection). */
        MULTI_ROW,
        /** One cell: arrows move between cells. */
        CELL
    }

    /** The payload of a dragged column header. */
    public record ColumnDrag(TableView<?> table, Column<?> column) {
    }

    /** Drag and drop format of a dragged column header. */
    public static final DataFormat<ColumnDrag> COLUMN = DataFormat.of("ui.table.column", ColumnDrag.class);

    /** Row kinds of group headers are below this: {@code GROUP - (level * 1024 + the value view's kind)}. */
    static final int GROUP = -2;

    private static final Facts ASCENDING = Facts.of("sort", "ascending");
    private static final Facts DESCENDING = Facts.of("sort", "descending");
    private static final Facts[] LEVELS = {Facts.of("level", "0"), Facts.of("level", "1"), Facts.of("level", "2"),
        Facts.of("level", "3")};

    private final DataView<R> view;
    private final ViewContext context;
    private final List<Column<R>> columns = new ArrayList<>(); // by id
    private final List<Column<R>> order = new ArrayList<>(); // display order of all columns, hidden ones too
    private final List<Column<R>> shown = new ArrayList<>(); // display order of the shown: frozen first
    private final Map<Object, Integer> rendererIds = new HashMap<>(); // by view type
    private int[] rendererOf = new int[8]; // column id -> renderer id
    private float[] columnX = new float[8]; // display index -> x in panel units
    private int frozen;
    private float frozenWidth;
    private float totalWidth;
    private final Element header;
    private final Element headerRow;
    private final Element filterRow;
    private Element groupRow;
    private Element[] headerCells = new Element[8]; // by column id
    private Element[] filterCells = new Element[8];
    private final Element columnIndicator;
    private Element dragPreview;
    private SelectionMode mode;
    private int currentColumn;
    private double shownX = Double.NaN;
    private double shownWidth = Double.NaN;
    private int cFirst;
    private int cLast = -1;

    private RowCells[] cellsBySlot = new RowCells[64];

    private final List<Column<R>> sortBy = new ArrayList<>();
    private final List<Boolean> sortAscending = new ArrayList<>();
    private final Predicate<R> columnFilters = this::passes;
    private Predicate<? super R> tableFilter;

    // header groups
    private final List<CharSequence> bandTitles = new ArrayList<>();
    private int[] bandOf = new int[8]; // column id -> header group, or -1
    private Element[] bandCells = new Element[0];
    private int[] bandCellBand = new int[0];
    private int bandCellCount;

    // row groups
    private GroupedRows<R> grouped;
    private final List<Column<R>> groupBy = new ArrayList<>();
    private ToDoubleFunction<? super R> groupSum;
    private CharSequence groupSumLabel = "";
    private int groupSumDecimals;
    private final StringBuilder scratch = new StringBuilder();

    private ValueRenderers renderers;
    private Function<? super R, ? extends CharSequence> typeAheadText;
    private Runnable layoutChanged;

    private Column<R> resizing;
    private int resizePointer = -1;
    private float resizeStart;
    private float resizeWidth;
    private int dropBefore = -1;

    private Column<R> editColumn;
    private int editIndex = -1;
    private int editSource = -1;
    private Element editCell;
    private Element editorView;
    private VersionedValue<Object> editValue;
    private Element[] editorViews = new Element[8];
    private boolean ending;
    private Element focusAfterLayout;

    private final HeaderEvents headerEvents = new HeaderEvents();
    private final TableEvents tableEvents = new TableEvents();

    /** A table named {@code name} of {@code view}'s rows, selecting by {@code selectionMode}. */
    @AllowAllocation("controls are made when a UI is built")
    public TableView(Ui ui, String name, DataView<R> view, SelectionMode selectionMode) {
        super(ui, "table-view", "table-body", name, view, Role.ROW,
            selectionMode == SelectionMode.MULTI_ROW ? Selection.Mode.MULTIPLE : Selection.Mode.SINGLE);
        this.view = view;
        this.mode = selectionMode;
        this.context = new ViewContext(ui, "table-view");
        root.semantics(Role.TABLE, name);
        header = ui.element("table-header", null);
        root.add(header);
        ui.codeLayout(header, Ui.CODE_KIND | Ui.CODE_PLACEMENT);
        layout.kind(header.slot(), LayoutKind.STACK).direction(header.slot(), Direction.COLUMN)
            .anchors(header.slot(), 0, 0, 1, 0);
        headerRow = row("table-header-row");
        headerRow.semantics(Role.ROW, null);
        filterRow = row("table-filter-row");
        layout.visible(filterRow.slot(), false);
        columnIndicator = ui.element("table-column-drop-indicator", null);
        headerRow.add(columnIndicator);
        ui.codeLayout(columnIndicator, Ui.CODE_PLACEMENT);
        layout.anchors(columnIndicator.slot(), 0, 0, 0, 1).visible(columnIndicator.slot(), false);
        interaction.on(headerRow, EventType.DROP_OVER.bit() | EventType.DROP.bit() | EventType.DROP_LEAVE.bit()
            | EventType.DRAG_END.bit(), headerEvents);
        interaction.on(body, EventType.CLICK.bit(), tableEvents);
        interaction.on(root, EventType.FOCUS_OUT.bit(), tableEvents);
    }

    /** A table of {@code rows} (sorted and filtered through a new {@link DataView}), one row selected at most. */
    @AllowAllocation("controls are made when a UI is built")
    public static <R> TableView<R> of(Ui ui, String name, ChangeClock clock, VersionedList<R> rows) {
        return new TableView<>(ui, name, new DataView<>(clock, rows), SelectionMode.ROW);
    }

    @AllowAllocation("controls are made when a UI is built")
    private Element row(String type) {
        Element e = ui.element(type, null);
        header.add(e);
        ui.codeLayout(e, Ui.CODE_KIND);
        layout.kind(e.slot(), LayoutKind.ANCHORS);
        return e;
    }

    /** The header element (above the rows): attach a column menu to it. */
    public Element header() {
        return header;
    }

    // ---------------------------------------------------------------- columns

    /** Adds {@code column} at the end (after the frozen ones); returns this. */
    @AllowAllocation("columns are added when a UI is built")
    public TableView<R> add(Column<R> column) {
        if (column.table != null) {
            throw new IllegalArgumentException(column + " is in a table already");
        }
        column.table = this;
        column.id = columns.size();
        columns.add(column);
        order.add(column);
        int id = column.id;
        if (id >= headerCells.length) {
            headerCells = Arrays.copyOf(headerCells, id * 2);
            filterCells = Arrays.copyOf(filterCells, id * 2);
            editorViews = Arrays.copyOf(editorViews, id * 2);
            rendererOf = Arrays.copyOf(rendererOf, id * 2);
            int old = bandOf.length;
            bandOf = Arrays.copyOf(bandOf, id * 2);
            Arrays.fill(bandOf, old, bandOf.length, -1);
        }
        bandOf[id] = -1;
        rendererOf[id] = rendererId(column);
        Element cell = ui.element("table-header-cell", null);
        cell.semantics(Role.COLUMN_HEADER, column.header());
        Element label = ui.label(null, column.header());
        Element indicator = ui.element("table-sort-indicator", null);
        Element grip = ui.element("table-column-grip", null);
        grip.semantics(Role.SEPARATOR, column.header());
        cell.add(label).add(indicator).add(grip);
        headerRow.add(cell);
        ui.codeLayout(cell, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH);
        layout.anchors(cell.slot(), 0, 0, 0, 1);
        interaction.on(cell, EventType.CLICK.bit() | EventType.DRAG_START.bit(), headerEvents);
        interaction.on(grip, EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit(), headerEvents);
        headerCells[id] = cell;
        Element filterCell = ui.element("table-filter-cell", null);
        filterRow.add(filterCell);
        ui.codeLayout(filterCell, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH);
        layout.anchors(filterCell.slot(), 0, 0, 0, 1);
        filterCells[id] = filterCell;
        // the drop indicator draws over the headers
        headerRow.add(columnIndicator);
        reshow();
        return this;
    }

    @AllowAllocation("a renderer of a new view type: once")
    private int rendererId(Column<R> column) {
        Integer rid = rendererIds.get(column.renderer().viewType());
        if (rid == null) {
            rid = rendererIds.size();
            rendererIds.put(column.renderer().viewType(), rid);
        }
        return rid;
    }

    /** The shown columns in display order (frozen first). */
    public List<Column<R>> columns() {
        return List.copyOf(shown);
    }

    /** Every column in display order, hidden ones in their places. */
    public List<Column<R>> allColumns() {
        return List.copyOf(order);
    }

    /** The column shown at display position {@code index}. */
    public Column<R> column(int index) {
        return shown.get(index);
    }

    /** The column whose {@link Column#key()} is {@code key}, or null. */
    public Column<R> column(String key) {
        for (Column<R> c : columns) {
            if (c.key().equals(key)) {
                return c;
            }
        }
        return null;
    }

    /** Freezes the first {@code count} displayed columns: they stay on the left while the rest scroll sideways. */
    public void freeze(int count) {
        int n = Math.max(0, Math.min(shown.size(), count));
        if (n != frozen) {
            frozen = n;
            dropRows(); // cells move between the rows' frozen and scrolling parts
            columnsChanged();
        }
    }

    /** How many columns are frozen. */
    public int frozen() {
        return frozen;
    }

    /**
     * Hides {@code column} or shows it again in its place; a hidden frozen column leaves the frozen ones, one shown
     * again among them joins them. Returns this.
     */
    public TableView<R> hide(Column<R> column, boolean on) {
        if (column.hidden == on || column.table != this) {
            return this;
        }
        if (on && shown.size() == 1) {
            return this; // the last shown column stays
        }
        int before = shown.indexOf(column);
        column.hidden = on;
        reshow();
        int after = shown.indexOf(column);
        if (on && before >= 0 && before < frozen) {
            frozen--;
        } else if (!on && after >= 0 && after < frozen) {
            frozen++;
        }
        if (editColumn == column) {
            cancel();
        }
        dropRows();
        columnsChanged();
        changed();
        return this;
    }

    /** Whether {@code column} is hidden. */
    public boolean hidden(Column<R> column) {
        return column.hidden;
    }

    /** The shown columns again from the display order. */
    @AllowAllocation("a user action: columns shown or moved")
    private void reshow() {
        shown.clear();
        for (Column<R> c : order) {
            if (!c.hidden) {
                shown.add(c);
            }
        }
        frozen = Math.min(frozen, shown.size());
        for (Column<R> c : columns) {
            layout.visible(headerCells[c.id].slot(), !c.hidden);
            layout.visible(filterCells[c.id].slot(), !c.hidden);
        }
        columnsChanged();
    }

    /**
     * Moves {@code column} to display position {@code before} (counted among the shown columns before the move; the
     * end: the column count), within its group (frozen or not).
     */
    @AllowAllocation("a user action")
    public void moveColumn(Column<R> column, int before) {
        int from = shown.indexOf(column);
        if (from < 0) {
            return;
        }
        boolean isFrozen = from < frozen;
        int low = isFrozen ? 0 : frozen;
        int high = isFrozen ? frozen : shown.size();
        int target = Math.max(low, Math.min(high, before));
        if (target == from || target == from + 1) {
            return;
        }
        Column<R> next = target < shown.size() ? shown.get(target) : null;
        order.remove(column);
        if (next == null) {
            order.add(column);
        } else {
            order.add(order.indexOf(next), column);
        }
        reshow();
        changed();
    }

    /** The view's rows, sorted and filtered. */
    public DataView<R> view() {
        return view;
    }

    /** The selection mode. */
    public SelectionMode selectionMode() {
        return mode;
    }

    /** The current cell's column (display position) in cell selection. */
    public int currentColumn() {
        return currentColumn;
    }

    /** Where a dragged column would be dropped (display position), or -1. */
    public int dropBefore() {
        return dropBefore;
    }

    /**
     * Resolves the style key {@code renderer} on header cells in {@code registry} (a column of a named renderer shows
     * its values through it); returns this.
     */
    public TableView<R> renderers(ValueRenderers registry) {
        renderers = registry;
        for (Column<R> c : columns) {
            c.styled(null == registry ? "" : "\u0000", null); // looked up again after the next layout
        }
        return this;
    }

    /** Type-ahead over the rows by {@code text} (null: none): typing moves to the next row starting so. Returns this. */
    public TableView<R> typeAhead(Function<? super R, ? extends CharSequence> text) {
        typeAheadText = text;
        interaction.textInput(root, text != null);
        return this;
    }

    @Override
    protected void typed(CharSequence text) {
        if (typeAheadText != null) {
            typeAhead(text, typeAheadText);
        }
    }

    /** Runs {@code listener} when users change the layout (widths, order, hidden columns, sort, groups). */
    public TableView<R> onLayoutChanged(Runnable listener) {
        layoutChanged = listener;
        return this;
    }

    private void changed() {
        if (layoutChanged != null) {
            layoutChanged.run();
        }
    }

    /** Lays the columns out again (widths, order, filter views changed). */
    void columnsChanged() {
        UiStore store = ui.store();
        if (columnX.length < shown.size() + 1) {
            columnX = new float[shown.size() * 2 + 1];
        }
        float x = 0;
        boolean filters = false;
        for (int d = 0; d < shown.size(); d++) {
            Column<R> c = shown.get(d);
            columnX[d] = x;
            x += c.width();
            if (d + 1 == frozen) {
                frozenWidth = x;
            }
            Element fv = c.filterView();
            filters |= fv != null;
            if (fv != null && store.parent(fv.slot()) != filterCells[c.id].slot()) {
                filterCells[c.id].add(fv);
            }
        }
        if (frozen == 0) {
            frozenWidth = 0;
        }
        columnX[shown.size()] = x;
        totalWidth = x;
        layout.visible(filterRow.slot(), filters);
        if (currentColumn >= shown.size()) {
            currentColumn = Math.max(0, shown.size() - 1);
        }
        shownX = Double.NaN;
        placeHeader(horizontal().position());
        rebindAll();
    }

    /** Places the header (and filter) cells for horizontal position {@code x}. */
    private void placeHeader(double x) {
        if (x == shownX && horizontal().viewport() == shownWidth) {
            return;
        }
        shownX = x;
        shownWidth = horizontal().viewport();
        for (int d = 0; d < shown.size(); d++) {
            Column<R> c = shown.get(d);
            float at = cellX(d, x);
            float w = c.width();
            int h = headerCells[c.id].slot();
            layout.offsets(h, at, 0, at, 0).width(h, w, Unit.PX);
            int f = filterCells[c.id].slot();
            layout.offsets(f, at, 0, at, 0).width(f, w, Unit.PX);
            headerCells[c.id].state(UiState.SELECTED, mode == SelectionMode.CELL && d == currentColumn);
        }
        placeBands(x);
        // the visible scrolling columns
        double left = x + frozenWidth;
        double right = x + Math.max(horizontal().viewport(), 1);
        cFirst = frozen;
        while (cFirst < shown.size() && columnX[cFirst + 1] <= left) {
            cFirst++;
        }
        cLast = cFirst - 1;
        while (cLast + 1 < shown.size() && columnX[cLast + 1] < right) {
            cLast++;
        }
    }

    private float cellX(int d, double x) {
        return (float) (d < frozen ? columnX[d] : columnX[d] - x);
    }

    /** The first scrolling column with cells (display position). */
    public int firstShownColumn() {
        return cFirst;
    }

    /** The last scrolling column with cells (display position). */
    public int lastShownColumn() {
        return cLast;
    }

    // ---------------------------------------------------------------- header groups

    /**
     * A header spanning {@code members} (shown above their headers, one cell per run of them standing side by side);
     * a column is in one header group at most. Returns this.
     */
    @AllowAllocation("header groups are made when a UI is built")
    public TableView<R> headerGroup(CharSequence title, List<Column<R>> members) {
        if (groupRow == null) {
            groupRow = ui.element("table-header-group-row", null);
            ui.codeLayout(groupRow, Ui.CODE_KIND);
            layout.kind(groupRow.slot(), LayoutKind.ANCHORS);
            header.add(groupRow).add(headerRow).add(filterRow); // the group row first
        }
        int band = bandTitles.size();
        bandTitles.add(title);
        for (Column<R> c : members) {
            if (c.table == this) {
                bandOf[c.id] = band;
            }
        }
        shownX = Double.NaN;
        placeHeader(horizontal().position());
        return this;
    }

    /** The title of the header group of {@code column}, or null. */
    public CharSequence headerGroupOf(Column<R> column) {
        int b = bandOf[column.id];
        return b < 0 ? null : bandTitles.get(b);
    }

    /** How many header group cells show (runs of adjacent columns of one group). */
    public int headerGroupCells() {
        return bandShown;
    }

    /** Header group cell {@code index} (in display order). */
    public Element headerGroupCell(int index) {
        return bandCells[index];
    }

    /** Places one header group cell per run of adjacent shown columns of a group, not across the frozen edge. */
    private void placeBands(double x) {
        if (groupRow == null) {
            return;
        }
        int used = 0;
        int d = 0;
        while (d < shown.size()) {
            int band = bandOf[shown.get(d).id];
            int end = d + 1;
            while (end < shown.size() && bandOf[shown.get(end).id] == band && (end != frozen)) {
                end++;
            }
            if (band >= 0) {
                int ci = used++;
                Element cell = bandCell(ci);
                float at = cellX(d, x);
                float w = columnX[end] - columnX[d];
                int s = cell.slot();
                layout.offsets(s, at, 0, at, 0).width(s, w, Unit.PX).visible(s, true);
                if (bandCellBand[ci] != band) {
                    bandCellBand[ci] = band;
                    Element label = ui.store().element(ui.store().firstChild(s));
                    ui.text(label, bandTitles.get(band));
                    cell.semantics(Role.COLUMN_HEADER, bandTitles.get(band).toString());
                }
            }
            d = end;
        }
        for (int i = used; i < bandCellCount; i++) {
            layout.visible(bandCells[i].slot(), false);
        }
        bandShown = used;
    }

    private int bandShown;

    @AllowAllocation("header group cells are made as columns are grouped, then reused")
    private Element bandCell(int index) {
        if (index >= bandCells.length) {
            bandCells = Arrays.copyOf(bandCells, Math.max(4, bandCells.length * 2));
            bandCellBand = Arrays.copyOf(bandCellBand, bandCells.length);
        }
        if (bandCells[index] == null) {
            Element cell = ui.element("table-header-group-cell", null);
            cell.semantics(Role.COLUMN_HEADER, "");
            cell.add(ui.label(null, ""));
            groupRow.add(cell);
            ui.codeLayout(cell, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH);
            layout.anchors(cell.slot(), 0, 0, 0, 1);
            bandCells[index] = cell;
            bandCellBand[index] = -1;
            bandCellCount = index + 1;
        }
        return bandCells[index];
    }

    // ---------------------------------------------------------------- sorting and filtering

    /**
     * Sorts by {@code column} (clicking its header): first by it alone, ascending, then descending; with {@code add}
     * (Shift), it joins the columns sorted by already (or turns around there).
     */
    @AllowAllocation("a user action: the comparator is composed anew")
    public void sort(Column<R> column, boolean add) {
        if (!column.sortable()) {
            return;
        }
        int at = sortBy.indexOf(column);
        if (add) {
            if (at >= 0) {
                sortAscending.set(at, !sortAscending.get(at));
            } else {
                sortBy.add(column);
                sortAscending.add(Boolean.TRUE);
            }
        } else {
            boolean ascending = at != 0 || sortBy.size() != 1 || !sortAscending.get(0);
            sortBy.clear();
            sortAscending.clear();
            sortBy.add(column);
            sortAscending.add(ascending);
        }
        applySort();
        changed();
    }

    /** Sorts by {@code column}, {@code ascending} or not, alone (or added: {@code add}). */
    @AllowAllocation("a user action: the comparator is composed anew")
    public void sort(Column<R> column, boolean ascending, boolean add) {
        if (!add) {
            sortBy.clear();
            sortAscending.clear();
        }
        int at = sortBy.indexOf(column);
        if (at >= 0) {
            sortAscending.set(at, ascending);
        } else if (column.sortable()) {
            sortBy.add(column);
            sortAscending.add(ascending);
        }
        applySort();
    }

    /** Sorts by nothing: rows in the list's order. */
    @AllowAllocation("a user action")
    public void unsort() {
        sortBy.clear();
        sortAscending.clear();
        applySort();
        changed();
    }

    /** Whether rows are sorted by {@code column}: 1 ascending, -1 descending, 0 not. */
    public int sortedBy(Column<R> column) {
        int at = sortBy.indexOf(column);
        return at < 0 ? 0 : sortAscending.get(at) ? 1 : -1;
    }

    @AllowAllocation("a user action: the comparator is composed anew")
    @SuppressWarnings("unchecked")
    private void applySort() {
        Comparator<R> by = null;
        for (int i = 0; i < sortBy.size(); i++) {
            Comparator<R> c = (Comparator<R>) sortBy.get(i).comparator();
            if (!sortAscending.get(i)) {
                c = c.reversed();
            }
            by = by == null ? c : by.thenComparing(c);
        }
        view.sort(grouped != null && !groupBy.isEmpty() ? grouped.order(by) : by);
        for (Column<R> c : columns) {
            int s = sortedBy(c);
            ui.styles().facts(headerCells[c.id].slot(), s > 0 ? ASCENDING : s < 0 ? DESCENDING : Facts.NONE);
        }
    }

    /** Shows only rows {@code test} accepts, besides the columns' filters (null: none). */
    public void filter(Predicate<? super R> test) {
        tableFilter = test;
        refilter();
    }

    /** Applies the filters again: their own state changed (a filter view's text). */
    public void refilter() {
        boolean any = tableFilter != null;
        for (int i = 0; i < columns.size() && !any; i++) {
            any = columns.get(i).filter() != null;
        }
        view.filter(any ? columnFilters : null);
    }

    private boolean passes(R row) {
        if (tableFilter != null && !tableFilter.test(row)) {
            return false;
        }
        for (int i = 0; i < columns.size(); i++) {
            Predicate<? super R> f = columns.get(i).filter();
            if (f != null && !f.test(row)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- grouping

    /**
     * Groups the rows by the values of {@code by} (outermost first; empty: no grouping), each column sorting its
     * groups by its comparator: a group header row (collapsible: a click, ACCEPT, LEFT and RIGHT) before each
     * group's rows. Incremental: see {@link GroupedRows}. The selection stays.
     */
    @AllowAllocation("a user action: grouping changes")
    @SuppressWarnings("unchecked")
    public TableView<R> groupBy(List<Column<R>> by) {
        groupBy.clear();
        List<Comparator<? super R>> keys = new ArrayList<>();
        for (Column<R> c : by) {
            if (c.table == this && c.comparator() != null && groupBy.size() < LEVELS.length) {
                groupBy.add(c);
                keys.add(c.comparator());
            }
        }
        if (editCell != null) {
            cancel();
        }
        if (groupBy.isEmpty()) {
            if (grouped != null) {
                replaceRows(view);
            }
        } else {
            if (grouped == null) {
                grouped = new GroupedRows<>(ui.store().clock(), view);
            }
            grouped.levels(keys).sum(groupSum);
            replaceRows(grouped);
        }
        applySort();
        changed();
        return this;
    }

    /** The columns rows are grouped by, outermost first. */
    public List<Column<R>> groupedBy() {
        return List.copyOf(groupBy);
    }

    /** The grouped rows while grouping, else null. */
    public GroupedRows<R> grouped() {
        return groupBy.isEmpty() ? null : grouped;
    }

    /**
     * Shows a sum of {@code value} in each group header, after {@code label}, with at most {@code decimals} decimals
     * (null: none). Returns this.
     */
    public TableView<R> groupSum(ToDoubleFunction<? super R> value, CharSequence label, int decimals) {
        groupSum = value;
        groupSumLabel = label == null ? "" : label;
        groupSumDecimals = Math.max(0, Math.min(9, decimals));
        if (grouped != null) {
            grouped.sum(value);
        }
        rebindAll();
        return this;
    }

    /** Whether row {@code index} is a group header. */
    public boolean groupHeader(int index) {
        return !groupBy.isEmpty() && grouped.header(index);
    }

    /** Collapses or opens the group of header row {@code index}. */
    public void collapseGroup(int index, boolean on) {
        if (groupHeader(index)) {
            grouped.collapse(index, on);
        }
    }

    /** The view position (in {@link #view()}) of row {@code index}, or -1 for a group header. */
    public int viewPosition(int index) {
        if (groupBy.isEmpty()) {
            return index;
        }
        return grouped.header(index) ? -1 : grouped.position(index);
    }

    // ---------------------------------------------------------------- layout description

    /**
     * The table's layout as text, one entry per line, for saving: {@code column "<key>" <width> [hidden]} for every
     * column in display order, {@code frozen <n>}, {@code sort "<key>" ascending|descending} in sort order,
     * {@code group "<key>"} outermost first. Keys are {@link Column#key()}s.
     */
    @AllowAllocation("a description is asked for when saving")
    public String layoutDescription() {
        StringBuilder out = new StringBuilder();
        for (Column<R> c : order) {
            out.append("column ");
            quote(out, c.key());
            out.append(' ');
            Format.append(out, c.width(), 2);
            if (c.hidden) {
                out.append(" hidden");
            }
            out.append('\n');
        }
        out.append("frozen ").append(frozen).append('\n');
        for (int i = 0; i < sortBy.size(); i++) {
            out.append("sort ");
            quote(out, sortBy.get(i).key());
            out.append(sortAscending.get(i) ? " ascending\n" : " descending\n");
        }
        for (Column<R> c : groupBy) {
            out.append("group ");
            quote(out, c.key());
            out.append('\n');
        }
        return out.toString();
    }

    private static void quote(StringBuilder out, String key) {
        out.append('"');
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (ch == '"' || ch == '\\') {
                out.append('\\');
            }
            out.append(ch);
        }
        out.append('"');
    }

    /**
     * Restores a layout {@link #layoutDescription()} gave: widths, order and hidden columns of the columns it names
     * (others keep their order after them), frozen columns, sort and grouping. Unknown keys and lines are skipped.
     */
    @AllowAllocation("a layout is restored")
    public void layoutDescription(String description) {
        List<Column<R>> newOrder = new ArrayList<>();
        List<Column<R>> sorts = new ArrayList<>();
        List<Boolean> ascending = new ArrayList<>();
        List<Column<R>> groups = new ArrayList<>();
        int newFrozen = frozen;
        for (String line : description.split("\n")) {
            String t = line.strip();
            int q0 = t.indexOf('"');
            String word = q0 < 0 ? t.split(" ")[0] : t.substring(0, q0).strip();
            if (word.equals("frozen")) {
                try {
                    newFrozen = Integer.parseInt(t.substring(6).strip());
                } catch (NumberFormatException e) {
                    // skipped
                }
                continue;
            }
            if (q0 < 0) {
                continue;
            }
            StringBuilder key = new StringBuilder();
            int i = q0 + 1;
            for (; i < t.length() && t.charAt(i) != '"'; i++) {
                char ch = t.charAt(i);
                if (ch == '\\' && i + 1 < t.length()) {
                    ch = t.charAt(++i);
                }
                key.append(ch);
            }
            String rest = i + 1 < t.length() ? t.substring(i + 1).strip() : "";
            Column<R> c = column(key.toString());
            if (c == null) {
                continue;
            }
            switch (word) {
                case "column" -> {
                    if (!newOrder.contains(c)) {
                        newOrder.add(c);
                    }
                    String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+");
                    if (parts.length > 0) {
                        try {
                            c.width(Float.parseFloat(parts[0]));
                        } catch (NumberFormatException e) {
                            // skipped
                        }
                    }
                    c.hidden = parts.length > 1 && parts[1].equals("hidden");
                }
                case "sort" -> {
                    sorts.add(c);
                    ascending.add(!rest.startsWith("desc"));
                }
                case "group" -> groups.add(c);
                default -> {
                }
            }
        }
        for (Column<R> c : order) {
            if (!newOrder.contains(c)) {
                newOrder.add(c);
            }
        }
        if (shownCount(newOrder) == 0) {
            newOrder.get(0).hidden = false;
        }
        order.clear();
        order.addAll(newOrder);
        if (editCell != null) {
            cancel();
        }
        reshow();
        frozen = Math.max(0, Math.min(shown.size(), newFrozen));
        sortBy.clear();
        sortAscending.clear();
        sortBy.addAll(sorts);
        sortAscending.addAll(ascending);
        dropRows();
        columnsChanged();
        groupBy(groups); // sorts too
    }

    private static <R> int shownCount(List<Column<R>> columns) {
        int n = 0;
        for (Column<R> c : columns) {
            if (!c.hidden) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- rows and cells

    @Override
    protected boolean afterLayout() {
        boolean restyled = false;
        for (int i = 0; i < columns.size(); i++) {
            Column<R> c = columns.get(i);
            String name = ui.styles().styleOf(headerCells[c.id].slot()).get(DataStyles.RENDERER);
            if (c.styled(name, registry(name))) {
                rendererOf[c.id] = rendererId(c);
                restyled = true;
            }
        }
        if (restyled) {
            if (editCell != null) {
                cancel();
            }
            dropRows();
        }
        boolean changed = super.afterLayout() | restyled;
        if (focusAfterLayout != null) {
            Element target = focusAfterLayout;
            focusAfterLayout = null;
            if (editCell != null && target.alive()) {
                interaction.focus(target, 0);
            }
        }
        return changed;
    }

    /** The registry named renderers come from (made when a style first names one). */
    @AllowAllocation("once: a style names a renderer")
    private ValueRenderers registry(String name) {
        if (renderers == null && !name.isEmpty()) {
            renderers = new ValueRenderers();
        }
        return renderers;
    }

    @Override
    protected boolean wideRows() {
        return false; // the table places its cells and knows its width
    }

    @Override
    protected float top() {
        return layout.height(header.slot()) / scale();
    }

    @Override
    protected double contentWidth() {
        return totalWidth;
    }

    @Override
    protected void scrolled(double x, double y) {
        if (x != shownX || horizontal().viewport() != shownWidth) {
            placeHeader(x);
            restyleRows();
        }
        super.scrolled(0, y);
    }

    @Override
    protected int kindOf(int index, R row) {
        if (groupHeader(index)) {
            int level = grouped.level(index);
            Column<R> c = groupBy.get(level);
            return GROUP - (level * 1024 + Math.min(1023, Math.max(0, c.renderer().kind(row))));
        }
        return 0;
    }

    @Override
    protected boolean rowSelected(int index, R value) {
        return mode != SelectionMode.CELL && super.rowSelected(index, value);
    }

    @Override
    @AllowAllocation("rows are created while the visible window fills, then recycled")
    protected Element createRow(int kind) {
        if (kind <= GROUP) {
            int level = (GROUP - kind) / 1024;
            int valueKind = (GROUP - kind) % 1024;
            Element row = ui.element("table-group-row", null);
            Element indent = ui.element("table-group-indent", null);
            ui.codeLayout(indent, Ui.CODE_WIDTH);
            Element expander = ui.element("table-group-expander", null);
            Element columnLabel = ui.label("table-group-column", "");
            Element value = groupBy.get(level).renderer().create(valueKind, context);
            Element count = ui.label("table-group-count", "");
            Element sum = ui.label("table-group-sum", "");
            row.add(indent).add(expander).add(columnLabel).add(value).add(count).add(sum);
            ui.styles().facts(row.slot(), LEVELS[level]);
            return row;
        }
        Element row = ui.element("table-row", null);
        ui.codeLayout(row, Ui.CODE_KIND);
        layout.kind(row.slot(), LayoutKind.ANCHORS);
        RowCells rc = new RowCells(part(row, "table-cells"), part(row, "table-frozen"));
        int slot = row.slot();
        if (slot >= cellsBySlot.length) {
            cellsBySlot = Arrays.copyOf(cellsBySlot, Math.max(slot + 1, cellsBySlot.length * 2));
        }
        cellsBySlot[slot] = rc;
        return row;
    }

    @AllowAllocation("rows are created while the visible window fills, then recycled")
    private Element part(Element row, String type) {
        Element p = ui.element(type, null);
        row.add(p);
        ui.codeLayout(p, Ui.CODE_KIND | Ui.CODE_PLACEMENT);
        layout.kind(p.slot(), LayoutKind.ANCHORS).anchors(p.slot(), 0, 0, 1, 1);
        return p;
    }

    @Override
    protected void bindRow(Element row, int kind, int index, R value, CellState state) {
        if (kind <= GROUP) {
            bindGroup(row, index, value, state);
            return;
        }
        if (editCell != null && editIndex != index && rowOfCell(editCell) == row.slot()) {
            commit(); // the edited row scrolled away
        }
        RowCells rc = cellsBySlot[row.slot()];
        rc.begin();
        double x = horizontal().position();
        UiStore store = ui.store();
        boolean selected = super.rowSelected(index, value);
        boolean isCurrent = index == current() && root.is(UiState.FOCUSED);
        CharSequence rowName = null;
        for (int d = 0; d < shown.size(); d++) {
            if (d >= frozen && (d < cFirst || d > cLast)) {
                continue;
            }
            Column<R> c = shown.get(d);
            int ck = c.renderer().kind(value);
            Element cell = rc.claim(this, c, rendererOf[c.id], ck, d < frozen);
            int cs = cell.slot();
            float at = cellX(d, x);
            layout.offsets(cs, at, 0, at, 0).width(cs, c.width(), Unit.PX);
            boolean cellMode = mode == SelectionMode.CELL && d == currentColumn;
            store.state(cs, UiState.SELECTED, cellMode && selected);
            store.state(cs, UiState.FOCUSED, cellMode && isCurrent);
            store.state(cs, UiState.FOCUS_VISIBLE, cellMode && isCurrent && root.is(UiState.FOCUS_VISIBLE));
            if (cell != editCell) {
                state.reset(cell, index, d);
                c.renderer().bind(store.element(store.firstChild(cs)), value, state);
                CharSequence name = state.name();
                if (rc.name(cell) != name) {
                    rc.name(cell, name);
                    store.semantics(cs, Role.CELL, name);
                }
                if (rowName == null) {
                    rowName = name;
                }
            }
        }
        rc.end(this);
        state.reset(row, index, -1);
        state.name(rowName);
    }

    /** A group header: its level's column and value, the count and the sum. */
    private void bindGroup(Element row, int index, R value, CellState state) {
        UiStore store = ui.store();
        int level = grouped.level(index);
        Column<R> c = groupBy.get(level);
        int indent = store.firstChild(row.slot());
        int expander = store.nextSibling(indent);
        int columnLabel = store.nextSibling(expander);
        int view = store.nextSibling(columnLabel);
        int count = store.nextSibling(view);
        int sum = store.nextSibling(count);
        boolean open = !grouped.collapsed(index);
        float step = ui.styles().styleOf(row.slot()).getFloat(DataStyles.GROUP_INDENT);
        layout.width(indent, level * step, Unit.PX);
        store.state(row.slot(), UiState.EXPANDED, open);
        store.state(expander, UiState.EXPANDED, open);
        ui.text(store.element(columnLabel), c.header());
        state.reset(row, index, -1);
        c.renderer().bind(store.element(view), value, state);
        CharSequence name = state.name();
        scratch.setLength(0);
        scratch.append('(').append(grouped.count(index)).append(')');
        ui.text(store.element(count), scratch);
        scratch.setLength(0);
        if (groupSum != null) {
            scratch.append(groupSumLabel);
            if (groupSumLabel.length() > 0) {
                scratch.append(' ');
            }
            Format.append(scratch, grouped.sum(index), groupSumDecimals);
        }
        ui.text(store.element(sum), scratch);
        state.reset(row, index, -1);
        state.name(name);
    }

    @Override
    protected void unbindRow(Element row, int kind) {
        if (kind <= GROUP) {
            return;
        }
        if (editCell != null && rowOfCell(editCell) == row.slot()) {
            commit();
        }
    }

    private int rowOfCell(Element cell) {
        UiStore store = ui.store();
        return store.parent(store.parent(cell.slot()));
    }

    /** The cell element showing {@code column} in row {@code index}, or null. */
    public Element cell(int index, Column<R> column) {
        Element row = rowElement(index);
        if (row == null || row.slot() >= cellsBySlot.length || cellsBySlot[row.slot()] == null
            || groupHeader(index)) {
            return null;
        }
        return cellsBySlot[row.slot()].cellOf(column.id);
    }

    @AllowAllocation("cells are created while the visible window fills, then recycled")
    Element newCell(Column<R> column, Element part, int kind) {
        Element cell = ui.element("table-cell", null);
        cell.add(column.renderer().create(kind, context));
        part.add(cell);
        ui.codeLayout(cell, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH);
        layout.anchors(cell.slot(), 0, 0, 0, 1);
        cell.semantics(Role.CELL, null);
        return cell;
    }

    /**
     * The display position of the column under UI pixel {@code x}, or -1: by the columns' places, so cells need not
     * be pickable (thousands of cells would fill the picking grid).
     */
    public int columnAt(float x) {
        double local = (x - layout.x(viewport.slot())) / scale();
        if (local < 0 || local >= horizontal().viewport()) {
            return -1;
        }
        int from = 0;
        int to = frozen;
        double at = local;
        if (local >= frozenWidth || frozen == 0) {
            from = frozen;
            to = shown.size();
            at = local + horizontal().position();
        }
        for (int d = from; d < to; d++) {
            if (at >= columnX[d] && at < columnX[d + 1]) {
                return d;
            }
        }
        return -1;
    }

    /** The cells of one row element, recycled per renderer and kind. */
    static final class RowCells {

        final Element scrolling;
        final Element frozenPart;
        Element[] cells = new Element[8];
        int[] column = new int[8];
        int[] renderer = new int[8];
        int[] kind = new int[8];
        boolean[] claimed = new boolean[8];
        boolean[] hidden = new boolean[8];
        CharSequence[] names = new CharSequence[8];
        int count;

        RowCells(Element scrolling, Element frozenPart) {
            this.scrolling = scrolling;
            this.frozenPart = frozenPart;
        }

        void begin() {
            Arrays.fill(claimed, 0, count, false);
        }

        /** A cell for {@code c}: its own from before, a spare one of the same renderer and kind, or a new one. */
        <R> Element claim(TableView<R> table, Column<R> c, int rendererId, int cellKind, boolean frozen) {
            Element part = frozen ? frozenPart : scrolling;
            int partSlot = part.slot();
            UiStore store = part.store();
            int found = -1;
            for (int i = 0; i < count && found < 0; i++) {
                if (!claimed[i] && column[i] == c.id && renderer[i] == rendererId && kind[i] == cellKind
                    && store.parent(cells[i].slot()) == partSlot) {
                    found = i;
                }
            }
            for (int i = 0; i < count && found < 0; i++) {
                if (!claimed[i] && !table.wanted(column[i], this) && renderer[i] == rendererId
                    && kind[i] == cellKind && store.parent(cells[i].slot()) == partSlot) {
                    found = i;
                }
            }
            if (found < 0) {
                found = add(table.newCell(c, part, cellKind), rendererId, cellKind);
            }
            claimed[found] = true;
            column[found] = c.id;
            if (hidden[found]) {
                hidden[found] = false;
                table.layout.visible(cells[found].slot(), true);
            }
            return cells[found];
        }

        @AllowAllocation("cells are created while the visible window fills, then recycled")
        private int add(Element cell, int rendererId, int cellKind) {
            if (count == cells.length) {
                int n = count * 2;
                cells = Arrays.copyOf(cells, n);
                column = Arrays.copyOf(column, n);
                renderer = Arrays.copyOf(renderer, n);
                kind = Arrays.copyOf(kind, n);
                claimed = Arrays.copyOf(claimed, n);
                hidden = Arrays.copyOf(hidden, n);
                names = Arrays.copyOf(names, n);
            }
            cells[count] = cell;
            column[count] = -1;
            renderer[count] = rendererId;
            kind[count] = cellKind;
            return count++;
        }

        /** Hides the cells no column claimed. */
        void end(TableView<?> table) {
            for (int i = 0; i < count; i++) {
                if (!claimed[i]) {
                    column[i] = -1;
                    if (!hidden[i]) {
                        hidden[i] = true;
                        table.layout.visible(cells[i].slot(), false);
                    }
                }
            }
        }

        Element cellOf(int columnId) {
            for (int i = 0; i < count; i++) {
                if (column[i] == columnId && !hidden[i]) {
                    return cells[i];
                }
            }
            return null;
        }

        CharSequence name(Element cell) {
            for (int i = 0; i < count; i++) {
                if (cells[i] == cell) {
                    return names[i];
                }
            }
            return null;
        }

        void name(Element cell, CharSequence name) {
            for (int i = 0; i < count; i++) {
                if (cells[i] == cell) {
                    names[i] = name;
                }
            }
        }
    }

    /** Whether column {@code id} still wants its own cell in this binding (it is shown and not yet claimed). */
    boolean wanted(int id, RowCells rc) {
        if (id < 0) {
            return false;
        }
        int d = shown.indexOf(columns.get(id));
        return d >= 0 && (d < frozen || d >= cFirst && d <= cLast);
    }

    // ---------------------------------------------------------------- keys and pointer

    @Override
    protected void command(UiEvent e) {
        if (editCell != null) {
            switch (e.command()) {
                case ACCEPT, SUBMIT -> {
                    commit();
                    e.consume();
                }
                case CANCEL -> {
                    cancel();
                    e.consume();
                }
                default -> {
                }
            }
            return;
        }
        if (mode == SelectionMode.CELL && e.command() == UiCommand.ACCEPT && current() >= 0
            && !groupHeader(current()) && currentColumn < shown.size() && shown.get(currentColumn).editable()) {
            edit(current(), shown.get(currentColumn));
            e.consume();
            return;
        }
        super.command(e);
    }

    @Override
    protected void rowCommand(UiEvent e) {
        int i = current();
        if (i >= 0 && i < rows().size() && groupHeader(i)) {
            switch (e.command()) {
                case LEFT, RIGHT, ACCEPT -> {
                    boolean collapse = e.command() == UiCommand.LEFT
                        || e.command() == UiCommand.ACCEPT && !grouped.collapsed(i);
                    grouped.collapse(i, collapse);
                    e.consume();
                    return;
                }
                default -> {
                }
            }
        }
        switch (e.command()) {
            case LEFT, RIGHT -> {
                int by = e.command() == UiCommand.LEFT ? -1 : 1;
                if (mode == SelectionMode.CELL) {
                    currentColumn(currentColumn + by);
                } else {
                    float step = ui.styles().styleOf(root.slot()).getFloat(DataStyles.SCROLL_STEP);
                    horizontal().scrollBy(by * step);
                    invalidate();
                }
                e.consume();
            }
            default -> {
            }
        }
    }

    /** Makes display column {@code d} current (cell selection) and scrolls it into view. */
    public void currentColumn(int d) {
        int c = Math.max(0, Math.min(shown.size() - 1, d));
        if (c != currentColumn) {
            currentColumn = c;
            shownX = Double.NaN;
        }
        if (c >= frozen) {
            // the scrolling columns show in [x + frozen width, x + viewport]
            double x = horizontal().position();
            double vp = horizontal().viewport();
            double end = columnX[c + 1];
            if (end > x + vp) {
                x = end - vp;
            }
            double start = columnX[c] - frozenWidth;
            if (start < x) {
                x = start;
            }
            horizontal().scrollTo(x);
            invalidate();
        }
        placeHeader(horizontal().position());
        rebindAll();
    }

    @Override
    protected void pressRow(int index, UiEvent e) {
        if (groupHeader(index)) {
            current(index);
            return; // the click opens or closes it
        }
        if (mode == SelectionMode.CELL) {
            int d = columnAt(e.x());
            if (d >= 0 && d != currentColumn) {
                currentColumn = d;
                shownX = Double.NaN;
                rebindAll();
            }
        }
        super.pressRow(index, e);
    }

    // ---------------------------------------------------------------- editing

    /** Whether a cell is being edited. */
    public boolean editing() {
        return editCell != null;
    }

    /** Starts editing row {@code index}'s cell of {@code column} (shown, editable); returns whether it started. */
    @AllowAllocation("editing starts: the edited value's holder is made")
    @SuppressWarnings("unchecked")
    public boolean edit(int index, Column<R> column) {
        if (!column.editable() || column.hidden || index < 0 || index >= rows().size() || groupHeader(index)) {
            return false;
        }
        if (editCell != null) {
            commit();
        }
        revealRow(index);
        Element cell = cell(index, column);
        if (cell == null) {
            return false;
        }
        Column.Editing<R, Object> ed = (Column.Editing<R, Object>) column.editing();
        Element ev = editorViews[column.id];
        if (ev == null) {
            ev = ed.editor().create(context);
            editorViews[column.id] = ev;
        }
        R item = rows().get(index);
        editColumn = column;
        editIndex = index;
        editSource = view.sourceIndex(viewPosition(index));
        editCell = cell;
        editorView = ev;
        editValue = ui.store().clock().value(ed.get().apply(item));
        UiStore store = ui.store();
        layout.visible(store.firstChild(cell.slot()), false);
        cell.add(ev);
        cell.state(UiState.EDITING, true);
        ed.editor().edit(ev, editValue);
        Element target = ed.editor().focusTarget(ev);
        if (!interaction.focus(target, 0)) {
            focusAfterLayout = target; // not laid out yet: focused once it is, in this frame
        }
        return true;
    }

    /** Ends editing, writing the value into the row; false if the editor refused it (editing goes on). */
    @SuppressWarnings("unchecked")
    public boolean commit() {
        if (editCell == null || ending) {
            return false;
        }
        Column.Editing<R, Object> ed = (Column.Editing<R, Object>) editColumn.editing();
        if (!ed.editor().commit(editorView)) {
            return false;
        }
        Object value = editValue.get();
        VersionedList<R> source = view.source();
        if (editSource >= 0 && editSource < source.size()) {
            R item = source.get(editSource);
            if (ed.with() != null) {
                source.set(editSource, ed.with().apply(item, value));
            } else {
                ed.set().accept(item, value);
                source.touch(editSource);
            }
        }
        endEdit();
        return true;
    }

    /** Ends editing without writing. */
    @SuppressWarnings("unchecked")
    public void cancel() {
        if (editCell == null || ending) {
            return;
        }
        ((Column.Editing<R, Object>) editColumn.editing()).editor().cancel(editorView);
        endEdit();
    }

    private void endEdit() {
        ending = true;
        try {
            Element cell = editCell;
            editCell = null;
            if (cell.alive()) {
                UiStore store = ui.store();
                editorView.detach();
                layout.visible(store.firstChild(cell.slot()), true);
                cell.state(UiState.EDITING, false);
            }
            editColumn = null;
            focusAfterLayout = null;
            editIndex = -1;
            editSource = -1;
            editValue = null;
            interaction.focus(root, 0);
            rebindAll();
        } finally {
            ending = false;
        }
    }

    // ---------------------------------------------------------------- events

    /** Header clicks (sort), drags (move columns) and grips (resize). */
    private final class HeaderEvents implements UiListener {

        @Override
        @AllowAllocation("user actions: sorting, a drag's payload")
        public void handle(UiEvent e) {
            UiStore store = ui.store();
            switch (e.type()) {
                case PRESS -> {
                    Column<R> c = columnOfHeader(e.currentSlot());
                    if (c != null && c.resizable() && e.button() == Pointer.PRIMARY) {
                        resizing = c;
                        resizePointer = e.pointerId();
                        resizeStart = e.x();
                        resizeWidth = c.width();
                        e.consume();
                    }
                }
                case MOVE -> {
                    if (resizing != null && e.pointerId() == resizePointer) {
                        float min = ui.styles().styleOf(headerCells[resizing.id].slot())
                            .getFloat(DataStyles.COLUMN_MIN_WIDTH);
                        resizing.width(Math.max(min, resizeWidth + (e.x() - resizeStart) / scale()));
                    }
                }
                case RELEASE -> {
                    if (e.pointerId() == resizePointer) {
                        resizing = null;
                        resizePointer = -1;
                        changed();
                    }
                }
                case CLICK -> {
                    if (store.type(e.targetSlot()).equals("table-column-grip") || e.button() != Pointer.PRIMARY) {
                        return;
                    }
                    Column<R> c = columnOfHeader(e.currentSlot());
                    if (c != null) {
                        sort(c, (e.modifiers() & Pointer.SHIFT) != 0);
                        e.consume();
                    }
                }
                case DRAG_START -> {
                    Column<R> c = columnOfHeader(e.currentSlot());
                    if (c == null || !c.movable() || resizing != null || e.pointerKind() == Pointer.Kind.TOUCH
                        || store.type(e.targetSlot()).equals("table-column-grip")) {
                        return;
                    }
                    if (dragPreview == null) {
                        dragPreview = ui.label(null, "");
                        dragPreview.style("table-drag-preview");
                    }
                    ui.text(dragPreview, c.header());
                    Element h = headerCells[c.id];
                    e.startDrag(DragOperation.MOVE).put(COLUMN, new ColumnDrag(TableView.this, c))
                        .preview(dragPreview, e.x() - layout.x(h.slot()), e.y() - layout.y(h.slot()));
                }
                case DROP_OVER -> {
                    ColumnDrag drag = e.drag() == null ? null : e.drag().get(COLUMN);
                    if (drag != null && drag.table() == TableView.this) {
                        e.acceptDrop(DragOperation.MOVE);
                        double x = (e.x() - layout.x(headerRow.slot())) / scale() + horizontal().position();
                        int before = shown.size();
                        for (int d = 0; d < shown.size(); d++) {
                            double mid = (d < frozen ? columnX[d] + horizontal().position() : columnX[d])
                                + shown.get(d).width() / 2;
                            if (x < mid) {
                                before = d;
                                break;
                            }
                        }
                        dropBefore = before;
                        float at = before < shown.size() ? cellX(before, horizontal().position())
                            : (float) (totalWidth - horizontal().position());
                        layout.offsets(columnIndicator.slot(), at, 0, at, 0).visible(columnIndicator.slot(), true);
                    }
                }
                case DROP -> {
                    ColumnDrag drag = e.drag().get(COLUMN);
                    int before = dropBefore;
                    hideIndicator();
                    if (drag != null && drag.table() == TableView.this && before >= 0) {
                        @SuppressWarnings("unchecked")
                        Column<R> c = (Column<R>) drag.column();
                        moveColumn(c, before);
                    }
                }
                case DROP_LEAVE, DRAG_END -> hideIndicator();
                default -> {
                }
            }
        }
    }

    private void hideIndicator() {
        dropBefore = -1;
        layout.visible(columnIndicator.slot(), false);
    }

    /** The column whose header cell is {@code slot} or holds it, or null. */
    private Column<R> columnOfHeader(int slot) {
        UiStore store = ui.store();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            for (int i = 0; i < columns.size(); i++) {
                if (headerCells[i].slot() == s) {
                    return columns.get(i);
                }
            }
        }
        return null;
    }

    /** Double clicks on cells (edit), clicks on group headers (open, close) and focus leaving an editor (commit). */
    private final class TableEvents implements UiListener {

        @Override
        public void handle(UiEvent e) {
            if (e.type() == EventType.CLICK) {
                int index = indexOf(ui.store().element(e.targetSlot()));
                if (index >= 0 && groupHeader(index)) {
                    if (e.clickCount() == 1) {
                        grouped.collapse(index, !grouped.collapsed(index));
                    }
                    e.consume();
                    return;
                }
                if (e.clickCount() == 2) {
                    int d = columnAt(e.x());
                    if (index >= 0 && d >= 0 && shown.get(d).editable()) {
                        edit(index, shown.get(d));
                        e.consume();
                    }
                }
            } else if (e.type() == EventType.FOCUS_OUT && editCell != null && !ending) {
                int to = e.related() == null ? UiStore.NONE : e.related().slot();
                if (!within(to, editorView.slot())) {
                    commit();
                }
            }
        }
    }

    private boolean within(int slot, int ancestor) {
        UiStore store = ui.store();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (s == ancestor) {
                return true;
            }
        }
        return false;
    }

}
