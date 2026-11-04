package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedList;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.DataFormat;
import com.truesim.ui.core.event.DragOperation;
import com.truesim.ui.core.event.DragSession;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import java.util.function.Function;

/**
 * A virtualized list box (design 5.6): a {@link RowSource} (a {@link VersionedList}, a sorted and filtered
 * {@link DataView}) shown through a {@link ValueRenderer}, single or multiple selection, keyboard navigation and
 * type-ahead, and drag and drop reordering. Everything virtual is {@link VirtualView}'s; scrolling is
 * {@link ScrollView}'s.
 *
 * <p><b>Elements and styles.</b> The root is a {@code list-view} (role LIST) holding the {@code scroll-viewport} with
 * the {@code list-body}; each row is a {@code list-item} (role LIST_ITEM, named by its renderer) holding the
 * renderer's view, with {@code :selected}, {@code :focused} (the current row while the list has focus),
 * {@code :focus-visible}, {@code :hover} and {@code :pressed}. A fixed {@code height} on {@code list-item} gives fixed
 * rows (O(1) virtualization); without one rows are measured. Dragging shows a {@code list-drag-preview} (a label) and
 * a {@code list-drop-indicator} where the row would go.
 *
 * <pre>{@code
 * VersionedList<String> names = clock.list();
 * ListView<String> list = ListView.of(ui, "Players", names);
 * parent.add(list.root());
 * list.selection().mode(Selection.Mode.MULTIPLE);
 * list.reorderable(true);
 * }</pre>
 *
 * @param <T> the item type
 */
public final class ListView<T> extends VirtualView<T> {

    /** The payload of a row dragged out of a list: the list and the row. */
    public record Drag(ListView<?> list, int index, Object item) {
    }

    /** Drag and drop format of a dragged list row. */
    public static final DataFormat<Drag> ROW = DataFormat.of("ui.list.row", Drag.class);

    private static final com.truesim.ui.style.Facts HORIZONTAL = com.truesim.ui.style.Facts.of("axis", "horizontal");

    /** Moves a row: the list's reorder action. */
    @FunctionalInterface
    public interface Reorder {
        /** Moves row {@code from} so it lands before row {@code before} (indices before the move; size: the end). */
        void move(int from, int before);
    }

    private final StyledRenderer<T> renderer;
    private final ViewContext context;
    private Function<? super T, ? extends CharSequence> textOf;
    private boolean reorderable;
    private Reorder reorder;
    private final Element indicator;
    private Element preview;
    private int dropBefore = -1;
    private final DragEvents dragEvents = new DragEvents();
    private final Function<T, CharSequence> typedText = this::textOf;

    /** A list named {@code name} showing {@code rows} through {@code renderer}, selecting one row at most. */
    @AllowAllocation("controls are made when a UI is built")
    public ListView(Ui ui, String name, RowSource<T> rows, ValueRenderer<? super T> renderer) {
        super(ui, "list-view", "list-body", name, rows, Role.LIST_ITEM, Selection.Mode.SINGLE);
        this.renderer = new StyledRenderer<>(renderer, renderer instanceof ValueRenderers.Dynamic d ? d.registry() : null);
        this.context = new ViewContext(ui, "list-view");
        root.semantics(Role.LIST, name);
        interaction.textInput(root, true); // type-ahead
        indicator = ui.element("list-drop-indicator", null);
        body.add(indicator);
        ui.codeLayout(indicator, Ui.CODE_PLACEMENT);
        layout.anchors(indicator.slot(), 0, 0, 1, 0).visible(indicator.slot(), false);
        interaction.on(body, EventType.DRAG_START.bit() | EventType.DROP_OVER.bit() | EventType.DROP.bit()
            | EventType.DROP_LEAVE.bit() | EventType.DRAG_END.bit(), dragEvents);
    }

    /** A list of {@code items} rendered by the built-in renderers ({@link ValueRenderers#dynamic()}). */
    @AllowAllocation("controls are made when a UI is built")
    public static <T> ListView<T> of(Ui ui, String name, VersionedList<T> items) {
        return new ListView<>(ui, name, RowSource.of(items), new ValueRenderers().dynamic());
    }

    /** The text type-ahead matches rows by (default: text as it is, else {@code String.valueOf}); returns this. */
    public ListView<T> textOf(Function<? super T, ? extends CharSequence> text) {
        textOf = text;
        return this;
    }

    /**
     * Lets rows be dragged to new places (the mouse or a pen; touch drags scroll); the move is {@link #reorder}'s,
     * by default within a {@link VersionedList} source. Returns this.
     */
    public ListView<T> reorderable(boolean on) {
        reorderable = on;
        return this;
    }

    /** Whether rows can be dragged to new places. */
    public boolean reorderable() {
        return reorderable;
    }

    /**
     * Lays the items out in a row ({@code true}: virtualized along x, each item as wide as {@code list-item}'s
     * {@code width} or measured; LEFT and RIGHT move, the wheel scrolls sideways; the list has the fact
     * {@code [axis=horizontal]} for themes) or in a column (the default). Returns this.
     */
    public ListView<T> horizontal(boolean on) {
        horizontalAxis(on);
        ui.styles().facts(root.slot(), on ? HORIZONTAL : com.truesim.ui.style.Facts.NONE);
        return this;
    }

    @Override
    protected boolean rowsDrag() {
        return reorderable;
    }

    /** Moves rows with {@code action} instead of within the source list; returns this. */
    public ListView<T> reorder(Reorder action) {
        reorder = action;
        return this;
    }

    /** Where a dragged row would be dropped (before that row), or -1 when no drag is over the list. */
    public int dropBefore() {
        return dropBefore;
    }

    @Override
    protected int kindOf(int index, T row) {
        return kindOf(renderer.active(), row);
    }

    @Override
    @AllowAllocation("rows are created while the visible window fills, then recycled")
    protected Element createRow(int kind) {
        Element row = ui.element("list-item", null);
        row.add(renderer.active().create(kind, context));
        return row;
    }

    @Override
    protected void bindRow(Element row, int kind, int index, T value, CellState state) {
        bind(renderer.active(), content(row), value, state);
    }

    @Override
    protected void unbindRow(Element row, int kind) {
        renderer.active().unbind(content(row));
    }

    @SuppressWarnings("unchecked")
    private static <V> int kindOf(ValueRenderer<? super V> r, V value) {
        return ((ValueRenderer<V>) r).kind(value);
    }

    @SuppressWarnings("unchecked")
    private static <V> void bind(ValueRenderer<? super V> r, Element view, V value, CellState state) {
        ((ValueRenderer<V>) r).bind(view, value, state);
    }

    @Override
    protected boolean afterLayout() {
        boolean changed = false;
        if (renderer.update(ui.styles().styleOf(root.slot()))) {
            dropRows(); // the views are the other renderer's
            changed = true;
        }
        return super.afterLayout() | changed;
    }

    /**
     * Resolves the style key {@code renderer} (a {@link DataStyles#RENDERER} name on {@code list-view}) in
     * {@code registry}; returns this. Lists made by {@link #of} use their dynamic renderer's registry.
     */
    public ListView<T> renderers(ValueRenderers registry) {
        renderer.registry(registry);
        return this;
    }

    /** The renderer in use: the list's own, or the one its style names. */
    public ValueRenderer<? super T> renderer() {
        return renderer.active();
    }

    private Element content(Element row) {
        UiStore store = ui.store();
        return store.element(store.firstChild(row.slot()));
    }

    // ---------------------------------------------------------------- type-ahead

    @Override
    protected void typed(CharSequence text) {
        typeAhead(text, typedText);
    }

    @AllowAllocation("typing: values that are not text are converted")
    private CharSequence textOf(T value) {
        if (textOf != null) {
            return textOf.apply(value);
        }
        return value instanceof CharSequence cs ? cs : String.valueOf(value);
    }

    // ---------------------------------------------------------------- reordering

    /** The label following a dragged row, made at the first drag. */
    @AllowAllocation("made at the first drag")
    private Element preview() {
        if (preview == null) {
            preview = ui.label(null, "");
            preview.style("list-drag-preview");
        }
        return preview;
    }

    /** The row a pointer at UI pixel {@code x, y} would drop before. */
    private int insertionAt(float x, float y) {
        int n = rows().size();
        if (n == 0) {
            return 0;
        }
        double at = contentAlong(x, y);
        int i = extents().indexAt(at);
        return at > extents().offset(i) + extents().size(i) / 2 ? i + 1 : i;
    }

    private void showIndicator(int before) {
        dropBefore = before;
        boolean across = horizontalAxis();
        double base = across ? layout.scrollX(body.slot()) - horizontal().position()
            : layout.scrollY(body.slot()) - vertical().position(); // the rows' origin, see VirtualView
        float at = (float) (extents().offset(before) + base);
        if (across) {
            layout.anchors(indicator.slot(), 0, 0, 0, 1).offsets(indicator.slot(), at, 0, at, 0);
        } else {
            layout.anchors(indicator.slot(), 0, 0, 1, 0).offsets(indicator.slot(), 0, at, 0, at);
        }
        layout.visible(indicator.slot(), true);
    }

    private void hideIndicator() {
        dropBefore = -1;
        layout.visible(indicator.slot(), false);
    }

    /** Moves row {@code from} before row {@code before}: the reorder action or the source list. */
    @SuppressWarnings("unchecked")
    void move(int from, int before) {
        if (before == from || before == from + 1) {
            return;
        }
        if (reorder != null) {
            reorder.move(from, before);
        } else if (rows() instanceof RowSource.ListRows<?> list) {
            VersionedList<Object> items = (VersionedList<Object>) list.list();
            Object item = items.remove(from);
            int to = before > from ? before - 1 : before;
            items.add(to, item);
            current(to);
        }
    }

    /** Drag and drop of rows. */
    private final class DragEvents implements UiListener {

        @Override
        @AllowAllocation("a drag starts: its payload is made once")
        public void handle(UiEvent e) {
            switch (e.type()) {
                case DRAG_START -> {
                    if (!reorderable || e.pointerKind() == Pointer.Kind.TOUCH) {
                        return;
                    }
                    int index = indexOf(ui.store().element(e.targetSlot()));
                    if (index < 0) {
                        return;
                    }
                    T item = rows().get(index);
                    Element row = rowElement(index);
                    Element preview = preview();
                    ui.text(preview, textOf(item));
                    float dx = row == null ? 0 : e.x() - layout.x(row.slot());
                    float dy = row == null ? 0 : e.y() - layout.y(row.slot());
                    DragSession session = e.startDrag(DragOperation.MOVE);
                    session.put(ROW, new Drag(ListView.this, index, item)).preview(preview, dx, dy);
                }
                case DROP_OVER -> {
                    Drag drag = e.drag() == null ? null : e.drag().get(ROW);
                    if (drag != null && drag.list() == ListView.this) {
                        e.acceptDrop(DragOperation.MOVE);
                        showIndicator(insertionAt(e.x(), e.y()));
                    }
                }
                case DROP -> {
                    Drag drag = e.drag().get(ROW);
                    int before = dropBefore >= 0 ? dropBefore : insertionAt(e.x(), e.y());
                    hideIndicator();
                    if (drag != null && drag.list() == ListView.this) {
                        move(drag.index(), before);
                    }
                }
                case DROP_LEAVE, DRAG_END -> hideIndicator();
                default -> {
                }
            }
        }
    }
}
