package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.IndexVisitor;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;
import java.util.Arrays;
import java.util.function.Function;

/**
 * The virtualizing core of lists, trees and tables (design 5.6): only the rows inside the viewport plus
 * {@link DataTunables#OVERSCAN} rows on each side have elements, recycled by kind; scrolling never touches the other
 * rows.
 *
 * <ul>
 *   <li><b>Axis.</b> Rows follow one another down (the default) or across ({@link #horizontalAxis}: a row of items,
 *       virtualized along x; the arrows LEFT and RIGHT move, the wheel scrolls sideways). "Row" below means an item
 *       along the axis.</li>
 *   <li><b>Extents.</b> A row style with a fixed height ({@code height: 24px}; across: a fixed {@code width}) gives
 *       {@link FixedExtents}, O(1); otherwise rows are measured after layout into {@link MeasuredExtents} (a Fenwick
 *       tree of measured and estimated heights, O(log n)), keeping the first visible row in place while heights above
 *       it settle.</li>
 *   <li><b>Wide content.</b> Rows of a vertical view fill its width and grow to their content; the widest row seen
 *       makes the content wide, so the view scrolls sideways and every row gets that width (the widest seen until
 *       the rows are replaced).</li>
 *   <li><b>Updates.</b> Once a frame the view compares its {@link RowSource}'s version (idle: one comparison); value
 *       changes rebind only the changed rows it shows ({@link RowSource#changesSince}); structural changes reconcile
 *       the visible window only, by row key, so a row that moved keeps its element and is only placed again.</li>
 *   <li><b>Placement.</b> Rows sit at their offsets in the {@code body} (anchors, code-owned) and the body scrolls
 *       by a layout scroll offset: scrolling moves rows without laying them out. Offsets are taken from a moving
 *       origin so a million rows keep float precision.</li>
 *   <li><b>Selection and keys.</b> A {@link Selection} of items; the current row shows {@code :focused} (and
 *       {@code :focus-visible}) while the view has focus; arrows, page up and down, home and end move it (with
 *       {@link UiInput#EXTEND_SELECTION}, the range grows), ACCEPT toggles or activates; pointer clicks select,
 *       Shift extends, the shortcut modifier toggles, double clicks activate.</li>
 *   <li><b>Box selection</b> (Dear ImGui 1.91; multiple selection): dragging from empty space (or from a row of a
 *       view whose rows do not drag) draws a {@code selection-box} over the viewport and selects the rows it covers:
 *       alone, with Shift added to the selection, with the shortcut modifier toggled. Dragging past an edge scrolls
 *       ({@link DataTunables#BOX_SCROLL_RATE}); rows are covered by index range, so rows never bound are selected
 *       too. While dragging, rows show the result ({@link Selection#previewed}) and nothing allocates; the selection
 *       changes once, on release. CANCEL drops the box; a click in empty space selects nothing.</li>
 * </ul>
 *
 * <p>Steady-state frames (idle, scrolling, rows changing, a box dragged) allocate nothing once the visible rows exist.
 *
 * @param <R> the row type
 */
public abstract class VirtualView<R> extends ScrollView {

    /** Rows at most bound at once, whatever the viewport says (a zero row height would ask for all). */
    static final int MAX_WINDOW = 4096;
    /** Rows are placed relative to an origin moved in steps of this, keeping offsets small for floats. */
    static final double REBASE = 65_536;

    /** The element rows go into. */
    protected final Element body;
    /** The reused cell state handed to renderers. */
    protected final CellState cell;
    /** The selection. */
    protected final Selection<R> selection;
    private RowSource<R> rows;
    private final FixedExtents fixed = new FixedExtents(0, 0);
    private final MeasuredExtents measured = new MeasuredExtents(24);
    private Extents extents = fixed;
    private boolean variable;
    private boolean heightKnown;
    private float fixedHeight = -1;
    private final Role rowRole;
    private boolean across;
    private float wideWidth;
    private float appliedWidth = -1;

    // rows, by row id
    private Element[] rowElement = new Element[16];
    private int[] rowIndex = new int[16];
    private long[] rowKey = new long[16];
    private long[] rowVersion = new long[16];
    private int[] rowKind = new int[16];
    private float[] rowPlaced = new float[16];
    private float[] rowWidth = new float[16];
    private boolean[] rowStale = new boolean[16];
    private boolean[] rowShown = new boolean[16];
    private CharSequence[] rowName = new CharSequence[16];
    private int rowCount;
    private int[] slotRow = new int[64]; // slot -> row id + 1
    private int created;

    // the window
    private int first;
    private int last = -1;
    private int[] window = new int[64];
    private int[] windowKind = new int[64];
    private long[] hashKey = new long[256];
    private int[] hashRow = new int[256];
    private int hashMask = 255;
    private double base;

    // the model
    private long seenVersion = Long.MIN_VALUE;
    private long seenStructure = Long.MIN_VALUE;
    private int seenSize = -1;
    private long seenSelection = -1;
    private boolean reconcile = true;
    private boolean statesDirty = true;
    private boolean anyStale;
    private final IndexVisitor stale = this::stale;

    private int current = -1;
    private boolean focusedNow;
    private boolean focusVisibleNow;
    /** The current row last moved by keys (its focus shows) rather than by a pointer. */
    private boolean byKeys;
    private RowAction onActivate;
    private final ViewEvents viewEvents = new ViewEvents();

    // type-ahead
    private final StringBuilder prefix = new StringBuilder();
    private double lastTyped = Double.NEGATIVE_INFINITY;

    // box selection
    private boolean boxSelect = true;
    private final Element box;
    private int pressPointer = -1;
    private boolean pressEmpty;
    private int rowPressPointer = -1;
    private long rowPressTime;
    private double pressContentX;
    private double pressContentY;
    private int boxPointer = -1;
    private int boxMode;
    private double boxStartX;
    private double boxStartY;
    private float boxNowX;
    private float boxNowY;
    private int boxFrom = 0;
    private int boxTo = -1;
    private int boxStartIndex = -1;
    private double boxTime = -1;

    /** What happens to a row: activated (double click, ACCEPT in a single selection). */
    @FunctionalInterface
    public interface RowAction {
        void act(int index);
    }

    /**
     * A view of type {@code type} named {@code name}, its rows in a {@code bodyType} element, each with {@code role},
     * showing {@code rows} with a selection of {@code mode}.
     */
    @AllowAllocation("controls are made when a UI is built")
    protected VirtualView(Ui ui, String type, String bodyType, String name, RowSource<R> rows, Role role,
        Selection.Mode mode) {
        super(ui, type, name);
        this.rows = rows;
        this.rowRole = role;
        this.cell = new CellState(ui);
        this.selection = new Selection<>(ui.store().clock(), mode);
        body = ui.element(bodyType, null);
        body.semantics(Role.GROUP, null); // it takes the rows' pointer events
        viewport.add(body);
        ui.codeLayout(body, Ui.CODE_KIND | Ui.CODE_PLACEMENT);
        ui.scrollRegion(body, true); // scrolling moves the body's clip offset: rows are not recorded again
        layout.kind(body.slot(), LayoutKind.ANCHORS).anchors(body.slot(), 0, 0, 1, 1)
            .independent(body.slot(), true);
        box = ui.element("selection-box", null);
        viewport.add(box); // over the rows, in the viewport's space
        ui.codeLayout(box, Ui.CODE_PLACEMENT);
        layout.anchors(box.slot(), 0, 0, 0, 0).visible(box.slot(), false);
        interaction.focusable(root, true);
        interaction.on(body, EventType.PRESS.bit() | EventType.CLICK.bit(), viewEvents);
        interaction.on(root, EventType.TEXT.bit() | EventType.PRESS.bit() | EventType.DRAG_START.bit()
            | EventType.DRAG.bit() | EventType.RELEASE.bit() | EventType.DRAG_END.bit(), viewEvents);
    }

    // ---------------------------------------------------------------- what subclasses provide

    /** The kind of row {@code index} (recycled per kind). */
    protected abstract int kindOf(int index, R row);

    /** A new row element of {@code kind} with its content; the view adds and places it. */
    protected abstract Element createRow(int kind);

    /**
     * Shows {@code value} (row {@code index}) in {@code row}; states are set on the row and in {@code state}. Name
     * the row with {@link CellState#name}.
     */
    protected abstract void bindRow(Element row, int kind, int index, R value, CellState state);

    /** {@code row} no longer shows a value. */
    protected void unbindRow(Element row, int kind) {
    }

    /** Whether row {@code index} shows as selected (tables selecting cells say no). */
    protected boolean rowSelected(int index, R value) {
        if (!rows.selectable(index)) {
            return false; // a group header, a placeholder
        }
        if (boxPointer >= 0) {
            return selection.previewed(value, index >= boxFrom && index <= boxTo, boxMode);
        }
        return selection.contains(value);
    }

    /** Whether a structural change binds every visible row again (their look depends on the structure). */
    protected boolean rebindOnStructure() {
        return false;
    }

    /** Called with the rows' new states before a bind (trees add their depth). */
    protected void prepare(int index, R value, CellState state) {
    }

    /** Whether a drag starting on a row moves it (reordering): then only drags from empty space draw a box. */
    protected boolean rowsDrag() {
        return false;
    }

    /** Whether rows grow to their content's width and the view scrolls sideways (tables place their own cells). */
    protected boolean wideRows() {
        return true;
    }

    // ---------------------------------------------------------------- API

    /** The rows shown. */
    public RowSource<R> rows() {
        return rows;
    }

    /** Shows {@code source} instead (a new list or view); the window is rebound. */
    public void rows(RowSource<R> source) {
        replaceRows(source);
        current = -1;
        selection.clear();
    }

    /** Shows {@code source} instead, keeping the selection and the current row (the same items, regrouped). */
    protected final void replaceRows(RowSource<R> source) {
        rows = source;
        seenSize = -1;
        wideWidth = 0;
        reconcile = true;
        invalidate();
    }

    /** The selection. */
    public Selection<R> selection() {
        return selection;
    }

    /** The current (focus) row, or -1. */
    public int current() {
        return current;
    }

    /** Makes row {@code index} current and scrolls it into view (no selection change). */
    public void current(int index) {
        int n = rows.size();
        int clamped = n == 0 ? -1 : Math.max(0, Math.min(n - 1, index));
        if (clamped != current) {
            current = clamped;
            statesDirty = true;
        }
        if (clamped >= 0) {
            revealRow(clamped);
        }
        invalidate();
    }

    /** Calls {@code action} when a row is activated (double click; ACCEPT in a single selection). */
    public void onActivate(RowAction action) {
        onActivate = action;
    }

    /** Activates row {@code index} as a double click would (its action runs, if any). */
    public void activate(int index) {
        if (onActivate != null && index >= 0 && index < rows.size() && rows.selectable(index)) {
            onActivate.act(index);
        }
    }

    /** The row extents in use (fixed or measured). */
    public Extents extents() {
        return extents;
    }

    /** Whether rows are measured (variable heights) rather than of one fixed height. */
    public boolean variableHeights() {
        return variable;
    }

    /** Whether rows follow one another across (a row of items) rather than down. */
    public boolean horizontalAxis() {
        return across;
    }

    /**
     * Lays rows out across ({@code true}: a row of items, virtualized along x, each as wide as its style's
     * {@code width} or measured) or down; the rows are made again.
     */
    protected void horizontalAxis(boolean on) {
        if (on == across) {
            return;
        }
        across = on;
        heightKnown = false;
        wideWidth = 0;
        appliedWidth = -1;
        dropRows();
        scrollTo(0, 0);
    }

    /** The scroller along the rows' axis. */
    protected final Scroller along() {
        return across ? scrollX : scrollY;
    }

    /** The first row bound (the window's start, overscan included). */
    public int firstBound() {
        return first;
    }

    /** The last row bound, or {@code firstBound() - 1} when none. */
    public int lastBound() {
        return last;
    }

    /** How many row elements exist (bound and free): O(visible rows). */
    public int rowElements() {
        return rowCount;
    }

    /** How many row elements were ever created. */
    public int created() {
        return created;
    }

    /** The element showing row {@code index}, or null when it is not bound. */
    public Element rowElement(int index) {
        if (index < first || index > last) {
            return null;
        }
        int r = window[index - first];
        return r < 0 ? null : rowElement[r];
    }

    /** The row index {@code element} (a row or inside one) shows, or -1. */
    public int indexOf(Element element) {
        int r = rowOf(element.slot());
        return r < 0 ? -1 : rowIndex[r];
    }

    /** Scrolls row {@code index} into view. */
    public void revealRow(int index) {
        if (index < 0 || index >= extents.count()) {
            return;
        }
        double start = extents.offset(index);
        if (along().reveal(start, start + extents.size(index))) {
            invalidate();
        }
    }

    /** Lets a drag draw a selection box (multiple selection only; on by default); returns this. */
    public VirtualView<R> boxSelect(boolean on) {
        boxSelect = on;
        if (!on) {
            endBox(false);
        }
        return this;
    }

    /** Whether a drag can draw a selection box. */
    public boolean boxSelect() {
        return boxSelect;
    }

    /** Whether a selection box is being dragged. */
    public boolean boxing() {
        return boxPointer >= 0;
    }

    /** The first row the dragged box covers (meaningful while {@link #boxing()}). */
    public int boxFrom() {
        return boxFrom;
    }

    /** The last row the dragged box covers, or {@code boxFrom() - 1} when it covers none. */
    public int boxTo() {
        return boxTo;
    }

    /** The selection box element (in the viewport, shown while dragging): style {@code selection-box}. */
    public Element selectionBox() {
        return box;
    }

    /** The row element id (by slot) of {@code slot} or an ancestor within the body, or -1. */
    protected final int rowOf(int slot) {
        UiStore store = ui.store();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (s < slotRow.length && slotRow[s] > 0) {
                int r = slotRow[s] - 1;
                return rowElement[r].alive() && rowElement[r].slot() == s ? r : -1;
            }
        }
        return -1;
    }

    /** Destroys every row element: they are made again for the window (their structure changed: frozen columns). */
    protected final void dropRows() {
        for (int r = 0; r < rowCount; r++) {
            if (rowIndex[r] >= 0) {
                unbindRow(rowElement[r], rowKind[r]);
            }
            int slot = rowElement[r].slot();
            slotRow[slot] = 0;
            ui.destroy(rowElement[r]);
            rowElement[r] = null;
            rowName[r] = null;
        }
        rowCount = 0;
        first = 0;
        last = -1;
        reconcile = true;
        invalidate();
    }

    /** Asks for the visible rows to be bound again (their look depends on something outside the model). */
    protected final void rebindAll() {
        statesDirty = true;
        invalidate();
    }

    /** Marks the visible rows for binding again in the window update under way (no new frame asked for). */
    protected final void restyleRows() {
        statesDirty = true;
    }

    /** Asks for the window to be reconciled again (rows changed in a way the source cannot tell). */
    protected final void reconcile() {
        reconcile = true;
        invalidate();
    }

    /** Where UI pixel {@code x, y} is along the rows' axis, in content panel units (scroll included). */
    protected final double contentAlong(float x, float y) {
        float s = scale();
        int v = viewport.slot();
        return across ? (x - layout.x(v)) / s + scrollX.position() : (y - layout.y(v)) / s + scrollY.position();
    }

    // ---------------------------------------------------------------- the frame

    @Override
    protected void beforeLayout(double seconds) {
        rows.refresh();
        int n = rows.size();
        long structure = rows.structureVersion();
        if (n != seenSize || structure != seenStructure) {
            seenSize = n;
            seenStructure = structure;
            seenVersion = rows.version();
            resetExtents(n);
            if (current >= n) {
                current = n - 1;
            }
            statesDirty |= rebindOnStructure();
            reconcile = true;
            invalidate();
        } else {
            long version = rows.version();
            if (version != seenVersion) {
                long since = seenVersion;
                seenVersion = version;
                anyStale = false;
                if (!rows.changesSince(since, stale)) {
                    reconcile = true;
                    invalidate();
                } else if (anyStale) {
                    invalidate();
                }
            }
        }
        if (selection.version() != seenSelection) {
            seenSelection = selection.version();
            statesDirty = true;
            invalidate();
        }
        boolean f = root.is(UiState.FOCUSED);
        boolean fv = root.is(UiState.FOCUS_VISIBLE);
        if (f != focusedNow || fv != focusVisibleNow) {
            focusedNow = f;
            focusVisibleNow = fv;
            if (f && current < 0 && n > 0) {
                current = Math.max(0, Math.min(n - 1, indexAtTop()));
            }
            statesDirty = true;
            invalidate();
        }
        if (boxPointer >= 0) {
            boxFrame(seconds);
        }
    }

    private int indexAtTop() {
        int i = extents.indexAt(along().position());
        return i < 0 ? 0 : i;
    }

    private void stale(int index) {
        if (index >= first && index <= last) {
            int r = window[index - first];
            if (r >= 0) {
                rowStale[r] = true;
                anyStale = true;
            }
        }
    }

    private void resetExtents(int n) {
        if (variable) {
            measured.reset(n);
        } else {
            fixed.set(n, heightKnown ? fixedHeight : 0);
        }
    }

    @Override
    protected double contentHeight() {
        return across ? 0 : extents.total();
    }

    @Override
    protected double contentWidth() {
        return across ? extents.total() : wideWidth; // rows fit the viewport's width unless one is wider
    }

    @Override
    protected void scrolled(double x, double y) {
        if (across) {
            updateWindow(x);
            layout.scroll(body.slot(), (float) (x - base), (float) y);
        } else {
            updateWindow(y);
            layout.scroll(body.slot(), (float) x, (float) (y - base));
        }
    }

    @Override
    protected boolean afterLayout() {
        boolean changed = false;
        if (rowCount > 0) {
            Length h = ui.styles().styleOf(rowElement[0].slot()).get(across ? Styles.WIDTH : Styles.HEIGHT);
            boolean fixedNow = h.unit() == Length.Unit.PX;
            float height = fixedNow ? h.value() : -1;
            if (!heightKnown || fixedNow == variable || fixedNow && height != fixedHeight) {
                heightKnown = true;
                variable = !fixedNow;
                fixedHeight = height;
                extents = variable ? measured : fixed;
                resetExtents(rows.size());
                for (int r = 0; r < rowCount; r++) {
                    rowPlaced[r] = Float.NaN;
                }
                reconcile = true;
                invalidate();
                changed = true;
            }
        }
        if (variable && last >= first) {
            changed |= measureRows();
        }
        if (!across && wideRows() && last >= first) {
            changed |= measureWidth();
        }
        return changed;
    }

    /** Takes the widest bound row as the content's width; every row gets it. */
    private boolean measureWidth() {
        float s = scale();
        float widest = wideWidth;
        for (int i = first; i <= last; i++) {
            int r = window[i - first];
            if (r >= 0 && layout.shown(rowElement[r].slot())) {
                widest = Math.max(widest, layout.width(rowElement[r].slot()) / s);
            }
        }
        float view = layout.width(viewport.slot()) / s;
        if (widest <= view + 0.5f) {
            return false; // nothing wider than the viewport: rows just fill it
        }
        if (widest != wideWidth) {
            wideWidth = widest;
            invalidate();
            return true;
        }
        return false;
    }

    /** Measures the bound rows; keeps the first visible row where it was if rows above it changed height. */
    private boolean measureRows() {
        float s = scale();
        double y = along().position();
        int anchor = extents.indexAt(y);
        double into = anchor < 0 ? 0 : y - extents.offset(anchor);
        boolean any = false;
        for (int i = first; i <= last; i++) {
            int r = window[i - first];
            if (r >= 0 && layout.shown(rowElement[r].slot())) {
                int slot = rowElement[r].slot();
                any |= measured.measure(i, (across ? layout.width(slot) : layout.height(slot)) / s);
            }
        }
        if (!any) {
            return false;
        }
        for (int i = first; i <= last; i++) {
            int r = window[i - first];
            if (r >= 0) {
                rowPlaced[r] = Float.NaN;
            }
        }
        if (anchor >= 0 && anchor < extents.count()) {
            along().extents(extents.total(), along().viewport());
            along().scrollTo(extents.offset(anchor) + into);
        }
        invalidate();
        return true;
    }

    /** Binds the rows {@code y} shows (plus overscan) and places them. */
    private void updateWindow(double y) {
        int n = rows.size();
        int a;
        int b;
        if (n == 0) {
            a = 0;
            b = -1;
        } else {
            int over = (int) DataTunables.OVERSCAN.get();
            a = Math.max(0, extents.indexAt(y) - over);
            b = Math.min(n - 1, extents.indexAt(y + Math.max(0, along().viewport())) + over);
            b = Math.min(b, a + MAX_WINDOW - 1);
        }
        double newBase = Math.floor(y / REBASE) * REBASE;
        if (newBase != base) {
            base = newBase;
            for (int r = 0; r < rowCount; r++) {
                rowPlaced[r] = Float.NaN;
            }
        }
        if (reconcile || a != first || b != last) {
            reconcileWindow(a, b);
        } else {
            for (int i = a; i <= b; i++) {
                int r = window[i - a];
                if (rowStale[r] || statesDirty || rowVersion[r] != rows.version(i)) {
                    bind(r, i);
                }
                place(r, i);
            }
        }
        reconcile = false;
        statesDirty = false;
    }

    private void reconcileWindow(int a, int b) {
        int size = b - a + 1;
        if (window.length < size) {
            growWindow(size);
        }
        hashClear(rowCount);
        for (int r = 0; r < rowCount; r++) {
            if (rowIndex[r] >= 0) {
                hashPut(rowKey[r], r);
            }
        }
        // claim the rows still showing their keys; free the rest, then fill the gaps with them
        for (int i = a; i <= b; i++) {
            int kind = kindOf(i, rows.get(i));
            int r = hashTake(rows.key(i));
            if (r >= 0 && rowKind[r] != kind) {
                free(r);
                r = -1;
            }
            window[i - a] = r;
            windowKind[i - a] = kind;
        }
        for (int h = 0; h <= hashMask; h++) {
            int r = hashRow[h];
            if (r >= 0) {
                free(r);
            }
        }
        for (int i = a; i <= b; i++) {
            int r = window[i - a];
            if (r < 0) {
                r = acquire(windowKind[i - a]);
                window[i - a] = r;
                bind(r, i);
            } else if (rowIndex[r] != i || rowStale[r] || statesDirty || rowVersion[r] != rows.version(i)) {
                bind(r, i);
            }
            place(r, i);
        }
        first = a;
        last = b;
    }

    private void bind(int r, int index) {
        R value = rows.get(index);
        long key = rows.key(index);
        Element row = rowElement[r];
        int slot = row.slot();
        UiStore store = ui.store();
        store.state(slot, UiState.SELECTED, rowSelected(index, value));
        boolean isCurrent = index == current && focusedNow;
        store.state(slot, UiState.FOCUSED, isCurrent);
        store.state(slot, UiState.FOCUS_VISIBLE, isCurrent && (focusVisibleNow || byKeys));
        cell.reset(row, index, -1);
        prepare(index, value, cell);
        bindRow(row, rowKind[r], index, value, cell);
        CharSequence name = cell.name();
        if (name != rowName[r]) {
            rowName[r] = name;
            store.semantics(slot, rowRole, name);
        }
        rowIndex[r] = index;
        rowKey[r] = key;
        rowVersion[r] = rows.version(index);
        rowStale[r] = false;
    }

    private void place(int r, int index) {
        float offset = (float) (extents.offset(index) - base);
        int slot = rowElement[r].slot();
        if (!rowShown[r]) {
            rowShown[r] = true;
            layout.visible(slot, true);
        }
        if (rowPlaced[r] != offset) {
            rowPlaced[r] = offset;
            if (across) {
                layout.offsets(slot, offset, 0, offset, 0);
            } else {
                layout.offsets(slot, 0, offset, 0, offset);
            }
        }
        if (!across && rowWidth[r] != wideWidth) {
            rowWidth[r] = wideWidth;
            if (wideWidth > 0) {
                layout.width(slot, wideWidth, Unit.PX);
            } else {
                layout.width(slot, 0, Unit.AUTO);
            }
        }
    }

    private int acquire(int kind) {
        for (int r = 0; r < rowCount; r++) {
            if (rowIndex[r] < 0 && rowKind[r] == kind) { // rows still to claim in the hash are bound
                return r;
            }
        }
        return newRow(kind);
    }

    @AllowAllocation("rows are created while the visible window fills, then recycled")
    private int newRow(int kind) {
        if (rowCount == rowElement.length) {
            int n = rowCount * 2;
            rowElement = Arrays.copyOf(rowElement, n);
            rowIndex = Arrays.copyOf(rowIndex, n);
            rowKey = Arrays.copyOf(rowKey, n);
            rowVersion = Arrays.copyOf(rowVersion, n);
            rowKind = Arrays.copyOf(rowKind, n);
            rowPlaced = Arrays.copyOf(rowPlaced, n);
            rowWidth = Arrays.copyOf(rowWidth, n);
            rowStale = Arrays.copyOf(rowStale, n);
            rowShown = Arrays.copyOf(rowShown, n);
            rowName = Arrays.copyOf(rowName, n);
        }
        Element row = createRow(kind);
        body.add(row);
        // across, a row's width is its style's (its extent); down, code gives it the content's width
        ui.codeLayout(row, ui.codeLayout(row) | Ui.CODE_PLACEMENT | (across ? 0 : Ui.CODE_WIDTH));
        if (across) {
            layout.anchors(row.slot(), 0, 0, 0, 1);
        } else {
            layout.anchors(row.slot(), 0, 0, 1, 0).width(row.slot(), 0, Unit.AUTO);
        }
        interaction.pickable(row, PointerEvents.ALL);
        int r = rowCount++;
        created++;
        rowElement[r] = row;
        rowIndex[r] = -1;
        rowKind[r] = kind;
        rowPlaced[r] = Float.NaN;
        rowWidth[r] = 0;
        rowShown[r] = true;
        int slot = row.slot();
        if (slot >= slotRow.length) {
            slotRow = Arrays.copyOf(slotRow, Math.max(slot + 1, slotRow.length * 2));
        }
        slotRow[slot] = r + 1;
        row.semantics(rowRole, null);
        return r;
    }

    private void free(int r) {
        unbindRow(rowElement[r], rowKind[r]);
        rowIndex[r] = -1;
        rowStale[r] = false;
        if (rowShown[r]) {
            rowShown[r] = false;
            layout.visible(rowElement[r].slot(), false);
        }
    }

    @AllowAllocation("the window grows to the most rows shown, once")
    private void growWindow(int size) {
        window = new int[Math.max(size, window.length * 2)];
        windowKind = new int[window.length];
    }

    // ---------------------------------------------------------------- key -> row, for reconciling

    @AllowAllocation("grows with the rows shown, once")
    private void hashClear(int entries) {
        if (entries * 2 > hashMask + 1) {
            int n = Integer.highestOneBit(entries * 4);
            hashKey = new long[n];
            hashRow = new int[n];
            hashMask = n - 1;
        }
        Arrays.fill(hashRow, -1);
    }

    private void hashPut(long key, int r) {
        int h = hash(key);
        while (hashRow[h] >= 0) {
            h = (h + 1) & hashMask;
        }
        hashKey[h] = key;
        hashRow[h] = r;
    }

    /** The row bound to {@code key}, removed from the table, or -1. */
    private int hashTake(long key) {
        int h = hash(key);
        while (hashRow[h] >= 0 || hashRow[h] == -2) {
            if (hashRow[h] >= 0 && hashKey[h] == key) {
                int r = hashRow[h];
                hashRow[h] = -2; // a tombstone keeps later probes going
                return r;
            }
            h = (h + 1) & hashMask;
        }
        return -1;
    }

    private int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40) & hashMask;
    }

    // ---------------------------------------------------------------- selection and keys

    /** Moves the current row to {@code index}, selecting by the modifiers; reveals it. */
    protected void moveTo(int index, boolean extend, boolean toggle) {
        int n = rows.size();
        if (n == 0) {
            return;
        }
        int i = Math.max(0, Math.min(n - 1, index));
        R value = rows.get(i);
        if (!rows.selectable(i)) {
            current(i); // a group header or a placeholder: current, never selected
            return;
        }
        if (extend) {
            selection.range(rows, i, toggle);
        } else if (toggle) {
            selection.toggle(i, value);
        } else {
            selection.select(i, value);
        }
        current(i);
    }

    @Override
    protected void command(UiEvent e) {
        int n = rows.size();
        UiCommand c = e.command();
        if (c == UiCommand.CANCEL && boxPointer >= 0) {
            endBox(false);
            e.consume();
            return;
        }
        if (n == 0) {
            return;
        }
        boolean extend = (e.commandFlags() & UiInput.EXTEND_SELECTION) != 0;
        int from = current < 0 ? indexAtTop() : current;
        int page = Math.max(1, (int) (along().viewport() / Math.max(1, extents.size(Math.max(0, from)))) - 1);
        if (across) {
            // across, LEFT and RIGHT move; UP and DOWN are not moves (the subclass's, or the parent's)
            if (c == UiCommand.UP || c == UiCommand.DOWN) {
                rowCommand(e);
                return;
            }
            c = c == UiCommand.LEFT ? UiCommand.UP : c == UiCommand.RIGHT ? UiCommand.DOWN : c;
        }
        if (!byKeys) {
            byKeys = true;
            statesDirty = true;
        }
        switch (c) {
            case UP -> moveTo(current < 0 ? from : from - 1, extend, false);
            case DOWN -> moveTo(current < 0 ? from : from + 1, extend, false);
            case PAGE_UP -> moveTo(from - page, extend, false);
            case PAGE_DOWN -> moveTo(from + page, extend, false);
            case HOME -> moveTo(0, extend, false);
            case END -> moveTo(n - 1, extend, false);
            case ACCEPT -> {
                if (current < 0) {
                    return;
                }
                if (!rows.selectable(current)) {
                    rowCommand(e); // a header: its own action (a group opens or closes)
                    return;
                }
                if (selection.mode() == Selection.Mode.MULTIPLE) {
                    selection.toggle(current, rows.get(current));
                } else {
                    selection.select(current, rows.get(current));
                    if (onActivate != null) {
                        onActivate.act(current);
                    }
                }
            }
            case SELECT_ALL -> selection.all(rows);
            default -> {
                rowCommand(e);
                return;
            }
        }
        e.consume();
    }

    /** A command the view itself does not use (trees take LEFT and RIGHT); consume it to keep it. */
    protected void rowCommand(UiEvent e) {
    }

    /** A pointer pressed row {@code index}; selects by the modifiers. */
    protected void pressRow(int index, UiEvent e) {
        int mods = e.modifiers();
        boolean extend = (mods & Pointer.SHIFT) != 0;
        boolean toggle = (mods & (Pointer.SHORTCUT | Pointer.CTRL | Pointer.META)) != 0;
        moveTo(index, extend, toggle);
    }

    /** Typed text arrived while the view has focus (type-ahead in lists). */
    protected void typed(CharSequence text) {
    }

    /**
     * Type-ahead: moves to the next row whose text ({@code textOf}) starts with what was typed within
     * {@link DataTunables#TYPE_AHEAD_TIMEOUT} (ignoring case); one more character searches from the current row (it
     * may still match), a new search from the next. Returns whether a row matched.
     */
    protected final boolean typeAhead(CharSequence text, Function<? super R, ? extends CharSequence> textOf) {
        int n = rows.size();
        if (n == 0 || text.isEmpty()) {
            return false;
        }
        if (now() - lastTyped > DataTunables.TYPE_AHEAD_TIMEOUT.get()) {
            prefix.setLength(0);
        }
        lastTyped = now();
        prefix.append(text);
        int start = current < 0 ? 0 : current;
        int from = prefix.length() == text.length() ? start + 1 : start;
        for (int k = 0; k < n; k++) {
            int i = (from + k) % n;
            if (rows.selectable(i) && startsWith(textOf.apply(rows.get(i)), prefix)) {
                moveTo(i, false, false);
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(CharSequence text, CharSequence prefix) {
        if (text == null || text.length() < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (Character.toLowerCase(text.charAt(i)) != Character.toLowerCase(prefix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- box selection

    /** Whether UI pixel {@code x, y} is in the viewport. */
    private boolean inViewport(float x, float y) {
        int v = viewport.slot();
        float vx = layout.x(v);
        float vy = layout.y(v);
        return x >= vx && y >= vy && x < vx + layout.width(v) && y < vy + layout.height(v);
    }

    private void startBox(UiEvent e) {
        boxPointer = e.pointerId();
        int mods = e.modifiers();
        boxMode = (mods & Pointer.SHIFT) != 0 ? BoxSelect.ADD
            : (mods & (Pointer.SHORTCUT | Pointer.CTRL | Pointer.META)) != 0 ? BoxSelect.TOGGLE : BoxSelect.REPLACE;
        // where the press was (the drag starts a threshold away from it), in content units then
        boxStartX = pressContentX;
        boxStartY = pressContentY;
        int start = extents.indexAt(across ? boxStartX : boxStartY);
        boxStartIndex = start;
        boxNowX = e.x();
        boxNowY = e.y();
        boxTime = -1;
        layout.visible(box.slot(), true);
        updateBox();
    }

    /** Ends the box: applies it ({@code apply}) or drops it. */
    private void endBox(boolean apply) {
        if (boxPointer < 0) {
            return;
        }
        boxPointer = -1;
        layout.visible(box.slot(), false);
        if (apply) {
            int end = boxTo >= boxFrom ? (boxStartIndex <= boxFrom ? boxTo : boxFrom) : -1;
            selection.box(rows, boxFrom, boxTo, boxMode, boxStartIndex >= 0 ? boxStartIndex : boxFrom);
            if (end >= 0) {
                current = end;
            }
        }
        boxFrom = 0;
        boxTo = -1;
        rebindAll();
    }

    /** A frame while the box is dragged: scrolls at the edges, then covers the rows under it. */
    private void boxFrame(double seconds) {
        double dt = boxTime < 0 ? 0 : Math.min(0.1, seconds - boxTime);
        boxTime = seconds;
        float s = scale();
        int v = viewport.slot();
        float pointer = across ? boxNowX : boxNowY;
        float start = across ? layout.x(v) : layout.y(v);
        float end = start + (across ? layout.width(v) : layout.height(v));
        float edge = DataTunables.BOX_SCROLL_EDGE.get() * s;
        double past = pointer < start + edge ? pointer - (start + edge) : pointer > end - edge ? pointer - (end - edge)
            : 0;
        if (past != 0 && dt > 0) {
            double by = past / s * DataTunables.BOX_SCROLL_RATE.get() * dt;
            if (along().scrollBy(by)) {
                invalidate();
            }
        }
        updateBox();
    }

    /** Covers the rows between the box's start and the pointer, and places the box. */
    private void updateBox() {
        float s = scale();
        int v = viewport.slot();
        double nowX = (boxNowX - layout.x(v)) / s + scrollX.position();
        double nowY = (boxNowY - layout.y(v)) / s + scrollY.position();
        double lowAlong = across ? Math.min(boxStartX, nowX) : Math.min(boxStartY, nowY);
        double highAlong = across ? Math.max(boxStartX, nowX) : Math.max(boxStartY, nowY);
        int n = rows.size();
        int from = 0;
        int to = -1;
        if (n > 0 && lowAlong < extents.total() && highAlong > 0) {
            from = extents.indexAt(Math.max(0, lowAlong));
            to = extents.indexAt(Math.min(extents.total(), highAlong));
            if (to >= 0 && extents.offset(to) >= highAlong && to > from) {
                to--; // ends exactly where a row starts
            }
        }
        if (from != boxFrom || to != boxTo) {
            boxFrom = from;
            boxTo = to;
            rebindAll();
        }
        float left = (float) (Math.min(boxStartX, nowX) - scrollX.position());
        float top = (float) (Math.min(boxStartY, nowY) - scrollY.position());
        float right = (float) (Math.max(boxStartX, nowX) - scrollX.position());
        float bottom = (float) (Math.max(boxStartY, nowY) - scrollY.position());
        layout.offsets(box.slot(), left, top, right, bottom);
    }

    /** The view's own pointer and text events. */
    private final class ViewEvents implements UiListener {

        @Override
        public void handle(UiEvent e) {
            if (e.currentSlot() == root.slot()) {
                rootEvent(e);
                return;
            }
            switch (e.type()) {
                case PRESS -> {
                    if (e.button() != Pointer.PRIMARY || e.pointerKind() == Pointer.Kind.TOUCH) {
                        return; // touches select on click (a drag scrolls)
                    }
                    int r = rowOf(e.targetSlot());
                    if (r >= 0) {
                        rowPressPointer = e.pointerId();
                        rowPressTime = e.time();
                    }
                    if (r >= 0 && rowIndex[r] >= 0) {
                        byKeys = false;
                        statesDirty = true;
                        interaction.focus(root, e.player());
                        pressRow(rowIndex[r], e);
                    }
                }
                case CLICK -> {
                    int r = rowOf(e.targetSlot());
                    if (r < 0 || rowIndex[r] < 0) {
                        return;
                    }
                    if (e.pointerKind() == Pointer.Kind.TOUCH && e.clickCount() == 1) {
                        interaction.focus(root, e.player());
                        pressRow(rowIndex[r], e);
                    }
                    if (e.clickCount() == 2 && onActivate != null && rows.selectable(rowIndex[r])) {
                        onActivate.act(rowIndex[r]);
                        e.consume();
                    }
                }
                default -> {
                }
            }
        }

        /** Events reaching the root: typing, and the presses and drags of box selection. */
        private void rootEvent(UiEvent e) {
            switch (e.type()) {
                case TEXT -> typed(e.text());
                case PRESS -> {
                    if (e.button() != Pointer.PRIMARY || e.pointerKind() == Pointer.Kind.TOUCH
                        || !inViewport(e.x(), e.y())) {
                        pressPointer = -1;
                        return;
                    }
                    pressPointer = e.pointerId();
                    float s = scale();
                    int v = viewport.slot();
                    pressContentX = (e.x() - layout.x(v)) / s + scrollX.position();
                    pressContentY = (e.y() - layout.y(v)) / s + scrollY.position();
                    int target = e.targetSlot();
                    // a press passed on to the root after a row had it (pass-through) is the row's
                    boolean rowHad = rowPressPointer == e.pointerId() && rowPressTime == e.time();
                    pressEmpty = !rowHad && rowOf(target) < 0 && (target == root.slot() || inside(target));
                    if (pressEmpty) {
                        interaction.focus(root, e.player());
                    }
                }
                case DRAG_START -> {
                    if (e.pointerId() != pressPointer || e.consumed() || !boxSelect
                        || selection.mode() != Selection.Mode.MULTIPLE || e.pointerKind() == Pointer.Kind.TOUCH
                        || !pressEmpty && rowsDrag()) {
                        pressEmpty = false; // a drag is no click
                        return;
                    }
                    startBox(e);
                    e.consume();
                }
                case DRAG -> {
                    if (e.pointerId() == boxPointer) {
                        boxNowX = e.x();
                        boxNowY = e.y();
                        updateBox();
                        e.consume();
                    }
                }
                case RELEASE -> {
                    if (e.pointerId() == boxPointer) {
                        boxNowX = e.x();
                        boxNowY = e.y();
                        updateBox();
                        endBox(true);
                    } else if (e.pointerId() == pressPointer && pressEmpty && inViewport(e.x(), e.y())
                        && (e.modifiers() & (Pointer.SHIFT | Pointer.SHORTCUT | Pointer.CTRL | Pointer.META)) == 0) {
                        selection.clear(); // a click in empty space selects nothing
                    }
                    pressPointer = -1;
                }
                case DRAG_END -> {
                    if (e.pointerId() == boxPointer) {
                        endBox(false); // the drag ended without a release: CANCEL, the pointer lost
                    }
                    if (e.pointerId() == pressPointer) {
                        pressPointer = -1; // a drag is no click
                    }
                }
                default -> {
                }
            }
        }
    }
}
