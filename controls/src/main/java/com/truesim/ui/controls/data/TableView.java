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
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
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
import java.util.function.Predicate;

/**
 * A virtualized table, a data grid (design 5.6): {@link Column}s defined in code over a sorted and filtered
 * {@link DataView}, rows and columns virtualized (only the visible rows have elements, and in them only the visible
 * columns' cells, recycled per renderer and kind), a header that sorts (click; Shift adds a column to the sort),
 * resizes (drag a header's edge) and moves columns (drag a header), frozen columns on the left, a filter row or
 * per-column filters, inline editing with a {@link ValueEditor} per column, keyboard cell navigation and row, multi-row
 * or cell selection.
 *
 * <p><b>Elements and styles.</b> The root is a {@code table-view} (role TABLE); above the viewport a
 * {@code table-header} holds the {@code table-header-row} of {@code table-header-cell}s (role COLUMN_HEADER: a label,
 * a {@code table-sort-indicator}, a {@code table-column-grip}; facts {@code [sort=ascending]} or
 * {@code [sort=descending]} when sorted) and, when a column has a filter view, the {@code table-filter-row} of
 * {@code table-filter-cell}s. Rows are {@code table-row}s (role ROW) of {@code table-cell}s (role CELL,
 * {@code :selected} and {@code :focused} in cell selection, {@code :editing} while edited); frozen cells sit in the
 * row's {@code table-frozen} part, drawn over the scrolling {@code table-cells}. A {@code table-column-drop-indicator}
 * shows where a dragged column would go. A fixed row height comes from {@code table-row}'s {@code height}.
 *
 * <pre>{@code
 * TableView<Unit> table = TableView.of(ui, "Units", clock, units);
 * table.add(Column.ofText("Name", Unit::name).width(160));
 * table.add(Column.ofInt("HP", Unit::hp).editor(Editors.integer(0, 999, 1), Unit::hp, Unit::withHp));
 * table.freeze(1);
 * }</pre>
 *
 * @param <R> the row type
 */
public final class TableView<R> extends VirtualView<R> {

    /** How cells are selected. */
    public enum SelectionMode {
        /** One row. */
        ROW,
        /** Any rows (Shift ranges, shortcut toggles). */
        MULTI_ROW,
        /** One cell: arrows move between cells. */
        CELL
    }

    /** The payload of a dragged column header. */
    public record ColumnDrag(TableView<?> table, Column<?> column) {
    }

    /** Drag and drop format of a dragged column header. */
    public static final DataFormat<ColumnDrag> COLUMN = DataFormat.of("ui.table.column", ColumnDrag.class);

    private static final Facts ASCENDING = Facts.of("sort", "ascending");
    private static final Facts DESCENDING = Facts.of("sort", "descending");

    private final DataView<R> view;
    private final ViewContext context;
    private final List<Column<R>> columns = new ArrayList<>(); // by id
    private final List<Column<R>> shown = new ArrayList<>(); // display order: frozen first
    private final Map<Object, Integer> rendererIds = new HashMap<>(); // by view type
    private int[] rendererOf = new int[8]; // column id -> renderer id
    private float[] columnX = new float[8]; // display index -> x in panel units
    private int frozen;
    private float frozenWidth;
    private float totalWidth;
    private final Element header;
    private final Element headerRow;
    private final Element filterRow;
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
    private int[] columnBySlot = new int[64]; // cell slot -> column id + 1

    private final List<Column<R>> sortBy = new ArrayList<>();
    private final List<Boolean> sortAscending = new ArrayList<>();
    private final Predicate<R> columnFilters = this::passes;
    private Predicate<? super R> tableFilter;

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
        shown.add(column);
        int id = column.id;
        if (id >= headerCells.length) {
            headerCells = Arrays.copyOf(headerCells, id * 2);
            filterCells = Arrays.copyOf(filterCells, id * 2);
            editorViews = Arrays.copyOf(editorViews, id * 2);
            rendererOf = Arrays.copyOf(rendererOf, id * 2);
        }
        Integer rid = rendererIds.get(column.renderer().viewType());
        if (rid == null) {
            rid = rendererIds.size();
            rendererIds.put(column.renderer().viewType(), rid);
        }
        rendererOf[id] = rid;
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
        columnsChanged();
        return this;
    }

    /** The columns in display order (frozen first). */
    public List<Column<R>> columns() {
        return List.copyOf(shown);
    }

    /** The column shown at display position {@code index}. */
    public Column<R> column(int index) {
        return shown.get(index);
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
     * Moves {@code column} to display position {@code before} (counted before the move; the end: the column count),
     * within its group (frozen or not).
     */
    public void moveColumn(Column<R> column, int before) {
        int from = shown.indexOf(column);
        boolean isFrozen = from < frozen;
        int low = isFrozen ? 0 : frozen;
        int high = isFrozen ? frozen : shown.size();
        int target = Math.max(low, Math.min(high, before));
        if (target == from || target == from + 1) {
            return;
        }
        shown.remove(from);
        shown.add(target > from ? target - 1 : target, column);
        columnsChanged();
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
    }

    /** Sorts by nothing: rows in the list's order. */
    @AllowAllocation("a user action")
    public void unsort() {
        sortBy.clear();
        sortAscending.clear();
        applySort();
    }

    /** Whether rows are sorted by {@code column}: 1 ascending, -1 descending, 0 not. */
    public int sortedBy(Column<R> column) {
        int at = sortBy.indexOf(column);
        return at < 0 ? 0 : sortAscending.get(at) ? 1 : -1;
    }

    @AllowAllocation("a user action: the comparator is composed anew")
    @SuppressWarnings("unchecked")
    private void applySort() {
        Comparator<R> order = null;
        for (int i = 0; i < sortBy.size(); i++) {
            Comparator<R> c = (Comparator<R>) sortBy.get(i).comparator();
            if (!sortAscending.get(i)) {
                c = c.reversed();
            }
            order = order == null ? c : order.thenComparing(c);
        }
        view.sort(order);
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

    // ---------------------------------------------------------------- rows and cells

    @Override
    protected boolean afterLayout() {
        boolean changed = super.afterLayout();
        if (focusAfterLayout != null) {
            Element target = focusAfterLayout;
            focusAfterLayout = null;
            if (editCell != null && target.alive()) {
                interaction.focus(target, 0);
            }
        }
        return changed;
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
        return 0;
    }

    @Override
    protected boolean rowSelected(int index, R value) {
        return mode != SelectionMode.CELL && super.rowSelected(index, value);
    }

    @Override
    @AllowAllocation("rows are created while the visible window fills, then recycled")
    protected Element createRow(int kind) {
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

    @Override
    protected void unbindRow(Element row, int kind) {
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
        return row == null ? null : cellsBySlot[row.slot()].cellOf(column.id);
    }

    @AllowAllocation("cells are created while the visible window fills, then recycled")
    Element newCell(Column<R> column, Element part, int kind) {
        Element cell = ui.element("table-cell", null);
        cell.add(column.renderer().create(kind, context));
        part.add(cell);
        ui.codeLayout(cell, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH);
        layout.anchors(cell.slot(), 0, 0, 0, 1);
        int slot = cell.slot();
        if (slot >= columnBySlot.length) {
            columnBySlot = Arrays.copyOf(columnBySlot, Math.max(slot + 1, columnBySlot.length * 2));
        }
        cell.semantics(Role.CELL, null);
        interaction.pickable(cell, PointerEvents.ALL); // clicks find their column; cells show :hover
        return cell;
    }

    void cellColumn(Element cell, int columnId) {
        int slot = cell.slot();
        if (slot >= columnBySlot.length) {
            columnBySlot = Arrays.copyOf(columnBySlot, Math.max(slot + 1, columnBySlot.length * 2));
        }
        columnBySlot[slot] = columnId + 1;
    }

    /** The display position of the column of the cell {@code slot} is in (or is), or -1. */
    private int columnAt(int slot) {
        UiStore store = ui.store();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (s < columnBySlot.length && columnBySlot[s] > 0 && "table-cell".equals(store.type(s))) {
                return shown.indexOf(columns.get(columnBySlot[s] - 1));
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
            table.cellColumn(cells[found], c.id);
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
        if (mode == SelectionMode.CELL && e.command() == com.truesim.ui.core.input.UiCommand.ACCEPT
            && current() >= 0 && currentColumn < shown.size() && shown.get(currentColumn).editable()) {
            edit(current(), shown.get(currentColumn));
            e.consume();
            return;
        }
        super.command(e);
    }

    @Override
    protected void rowCommand(UiEvent e) {
        switch (e.command()) {
            case LEFT, RIGHT -> {
                int by = e.command() == com.truesim.ui.core.input.UiCommand.LEFT ? -1 : 1;
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
        if (mode == SelectionMode.CELL) {
            int d = columnAt(e.targetSlot());
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
        if (!column.editable() || index < 0 || index >= rows().size()) {
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
        editSource = view.sourceIndex(index);
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
                    }
                }
                case CLICK -> {
                    if (store.type(e.targetSlot()).equals("table-column-grip")) {
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

    /** Double clicks on cells (edit) and focus leaving an editor (commit). */
    private final class TableEvents implements UiListener {

        @Override
        public void handle(UiEvent e) {
            if (e.type() == EventType.CLICK && e.clickCount() == 2) {
                int index = indexOf(ui.store().element(e.targetSlot()));
                int d = columnAt(e.targetSlot());
                if (index >= 0 && d >= 0 && shown.get(d).editable()) {
                    edit(index, shown.get(d));
                    e.consume();
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
