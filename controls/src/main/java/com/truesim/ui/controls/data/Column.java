package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * A table column defined in code (design 5.6): a header, a renderer of the row (usually of one field, without boxing:
 * {@link #ofInt}, {@link #ofDouble}, {@link #ofText}, {@link #ofBoolean}, {@link #of}), the order it sorts by, an
 * optional inline editor writing the field back, an optional filter, its width, and whether users may resize, move
 * and sort it. Freezing is the table's ({@link TableView#freeze}).
 *
 * <pre>{@code
 * Column<Unit> hp = Column.ofInt("HP", Unit::hp).width(60)
 *     .editor(Editors.integer(0, 999, 1), (u, v) -> u.withHp(v));
 * }</pre>
 *
 * @param <R> the row type
 */
public final class Column<R> {

    private final String header;
    private final ValueRenderer<R> own;
    private ValueRenderer<R> renderer;
    private final Function<? super R, ?> value;
    private String styled = "";
    private String key;
    boolean hidden;
    private Comparator<? super R> comparator;
    private float width = 120;
    private boolean resizable = true;
    private boolean movable = true;
    private boolean sortable = true;
    private Predicate<? super R> filter;
    private Element filterView;
    private Editing<R, ?> editing;
    int id = -1;
    TableView<R> table;

    private Column(String header, ValueRenderer<R> renderer, Comparator<? super R> comparator,
        Function<? super R, ?> value) {
        this.header = Objects.requireNonNull(header, "header");
        this.own = Objects.requireNonNull(renderer, "renderer");
        this.renderer = own;
        this.value = value;
        this.comparator = comparator;
    }

    /** A column of an int field: shown as a number, sorted numerically. */
    @AllowAllocation("columns are made when a UI is built")
    public static <R> Column<R> ofInt(String header, ToIntFunction<? super R> field) {
        return new Column<>(header, Renderers.ofInt(field), Comparator.comparingInt(field), field::applyAsInt);
    }

    /** A column of a double field with at most {@code decimals} decimals, sorted numerically. */
    @AllowAllocation("columns are made when a UI is built")
    public static <R> Column<R> ofDouble(String header, ToDoubleFunction<? super R> field, int decimals) {
        return new Column<>(header, Renderers.ofDouble(field, decimals), Comparator.comparingDouble(field),
            field::applyAsDouble);
    }

    /** A column of a text field, sorted by its characters. */
    @AllowAllocation("columns are made when a UI is built")
    public static <R> Column<R> ofText(String header, Function<? super R, ? extends CharSequence> field) {
        return new Column<>(header, Renderers.ofText(field), (a, b) -> CharSequence.compare(field.apply(a),
            field.apply(b)), field);
    }

    /** A column of a boolean field, false first. */
    @AllowAllocation("columns are made when a UI is built")
    public static <R> Column<R> ofBoolean(String header, Predicate<? super R> field) {
        return new Column<>(header, Renderers.ofBoolean(field), (a, b) -> Boolean.compare(field.test(a),
            field.test(b)), field::test);
    }

    /** A column of any field shown by {@code renderer}, sorted by {@code order} (null: not sortable). */
    @AllowAllocation("columns are made when a UI is built")
    public static <R, V> Column<R> of(String header, Function<? super R, ? extends V> field,
        ValueRenderer<? super V> renderer, Comparator<? super V> order) {
        Column<R> c = new Column<>(header, Renderers.of(field, renderer),
            order == null ? null : Comparator.comparing(field, order), field);
        c.sortable = order != null;
        return c;
    }

    /** A column showing the whole row through {@code renderer}, sorted by {@code order} (null: not sortable). */
    public static <R> Column<R> ofRow(String header, ValueRenderer<R> renderer, Comparator<? super R> order) {
        Column<R> c = new Column<>(header, renderer, order, Function.identity());
        c.sortable = order != null;
        return c;
    }

    /** The header text. */
    public String header() {
        return header;
    }

    /** The renderer of the column's cells: its own, or the one its header cell's style names. */
    public ValueRenderer<R> renderer() {
        return renderer;
    }

    /**
     * What identifies the column in a table's layout description ({@link TableView#layoutDescription()}): the
     * header unless set.
     */
    public String key() {
        return key == null ? header : key;
    }

    /** Identifies the column by {@code value} in layout descriptions; returns this. */
    public Column<R> key(String value) {
        key = value;
        return this;
    }

    /** Looks the style's renderer name up again after the next layout (the registry changed). */
    void restyle() {
        styled = null;
    }

    /** Whether the column is hidden ({@link TableView#hide}). */
    public boolean hidden() {
        return hidden;
    }

    /**
     * Takes the renderer named {@code name} in {@code registry} for the column's values ({@link DataStyles#RENDERER}
     * on its header cell; empty: its own); returns whether the renderer changed. Int, double and boolean columns box
     * their values for a named renderer.
     */
    @AllowAllocation("the style names another renderer: an adapter is made")
    boolean styled(String name, ValueRenderers registry) {
        if (name == styled) {
            return false;
        }
        styled = name;
        ValueRenderer<?> named = name.isEmpty() || registry == null ? null : registry.named(name);
        ValueRenderer<R> want = named == null ? own : adapt(named);
        if (named == null && renderer == own) {
            return false;
        }
        renderer = want;
        return true;
    }

    @SuppressWarnings("unchecked")
    private ValueRenderer<R> adapt(ValueRenderer<?> named) {
        return Renderers.of((Function<? super R, Object>) value, (ValueRenderer<Object>) named);
    }

    /** The order the column sorts by, or null. */
    public Comparator<? super R> comparator() {
        return comparator;
    }

    /** Sorts by {@code order} instead; returns this. */
    public Column<R> comparator(Comparator<? super R> order) {
        comparator = order;
        return this;
    }

    /** The width in panel units. */
    public float width() {
        return width;
    }

    /** Sets the width in panel units (the table's {@code column-min-width} at least, when shown); returns this. */
    public Column<R> width(float value) {
        width = value;
        if (table != null) {
            table.columnsChanged();
        }
        return this;
    }

    public boolean resizable() {
        return resizable;
    }

    /** Whether users may resize it by dragging its header's edge; returns this. */
    public Column<R> resizable(boolean on) {
        resizable = on;
        return this;
    }

    public boolean movable() {
        return movable;
    }

    /** Whether users may move it by dragging its header; returns this. */
    public Column<R> movable(boolean on) {
        movable = on;
        return this;
    }

    public boolean sortable() {
        return sortable && comparator != null;
    }

    /** Whether clicking its header sorts by it; returns this. */
    public Column<R> sortable(boolean on) {
        sortable = on;
        return this;
    }

    /** The column's filter, or null. */
    public Predicate<? super R> filter() {
        return filter;
    }

    /**
     * Shows only rows {@code test} accepts (null: all), with the other columns' filters; call again (or
     * {@link TableView#refilter()}) when the filter's own state changes. Returns this.
     */
    public Column<R> filter(Predicate<? super R> test) {
        filter = test;
        if (table != null) {
            table.refilter();
        }
        return this;
    }

    /**
     * The element shown in the column's cell of the filter row (a text field of the text-editing controls, a check
     * box): the per-column filter hook's view. The table shows a filter row when any column has one. Returns this.
     */
    public Column<R> filterView(Element view) {
        filterView = view;
        if (table != null) {
            table.columnsChanged();
        }
        return this;
    }

    /** The filter row's view, or null. */
    public Element filterView() {
        return filterView;
    }

    /** Edits cells with {@code editor} of the field {@code get}, writing a new row made by {@code with}. */
    @AllowAllocation("columns are made when a UI is built")
    public <V> Column<R> editor(ValueEditor<V> editor, Function<? super R, ? extends V> get,
        BiFunction<? super R, ? super V, ? extends R> with) {
        editing = new Editing<>(editor, get, with, null);
        return this;
    }

    /** Edits cells with {@code editor} of the field {@code get}, setting it in place with {@code set}. */
    @AllowAllocation("columns are made when a UI is built")
    public <V> Column<R> editorInPlace(ValueEditor<V> editor, Function<? super R, ? extends V> get,
        BiConsumer<? super R, ? super V> set) {
        editing = new Editing<>(editor, get, null, set);
        return this;
    }

    /** Whether the column's cells can be edited. */
    public boolean editable() {
        return editing != null;
    }

    Editing<R, ?> editing() {
        return editing;
    }

    /** An editor and how its value comes from and goes back into a row. */
    record Editing<R, V>(ValueEditor<V> editor, Function<? super R, ? extends V> get,
        BiFunction<? super R, ? super V, ? extends R> with, BiConsumer<? super R, ? super V> set) {
    }

    @Override
    public String toString() {
        return header;
    }
}
