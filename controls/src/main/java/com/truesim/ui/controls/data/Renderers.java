package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Vec2;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.Vec4;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.style.value.Color;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * The built-in {@link ValueRenderer}s: text (any value, {@code String.valueOf} for what is not text), numbers,
 * booleans (a {@code value-check} element, {@code :checked} when true), enums (the constant's name), vectors and
 * colours as text; and adapters showing a field of a row without boxing ({@link #ofInt}, {@link #ofDouble},
 * {@link #ofText}, {@link #ofBoolean}, {@link #of}), for table columns. Text views are labels with ids {@code text},
 * {@code number}, {@code enum}, {@code vector} or {@code color}, so styles align numbers and colour enums.
 */
public final class Renderers {

    private static final String TRUE = "true";
    private static final String FALSE = "false";

    private Renderers() {
    }

    /** Any value as text: text as it is, others by {@code String.valueOf} (allocates for them: give a renderer). */
    public static <T> ValueRenderer<T> text() {
        return new TextRenderer<>("text");
    }

    /** Numbers: integers in full, fractions with at most {@code decimals} decimals; no allocation. */
    public static ValueRenderer<Number> number(int decimals) {
        return new NumberRenderer(decimals);
    }

    /** Booleans: a {@code value-check} element, {@code :checked} when true. */
    public static ValueRenderer<Boolean> bool() {
        return new BooleanRenderer<>(null);
    }

    /** Enum constants by name. */
    public static ValueRenderer<Enum<?>> enumeration() {
        return new EnumRenderer();
    }

    /** Vectors ({@code Vec2}, {@code Vec3}, {@code Vec4}) as {@code x, y, z} with three decimals at most. */
    public static ValueRenderer<Object> vector() {
        return new VectorRenderer();
    }

    /** Colours as {@code #rrggbbaa}. */
    public static ValueRenderer<Color> color() {
        return new ColorRenderer();
    }

    /** A row's int field, without boxing. */
    public static <R> ValueRenderer<R> ofInt(ToIntFunction<? super R> field) {
        return new IntField<>(field);
    }

    /** A row's double field with at most {@code decimals} decimals, without boxing. */
    public static <R> ValueRenderer<R> ofDouble(ToDoubleFunction<? super R> field, int decimals) {
        return new DoubleField<>(field, decimals);
    }

    /** A row's text field. */
    public static <R> ValueRenderer<R> ofText(Function<? super R, ? extends CharSequence> field) {
        return new TextField<>(field);
    }

    /** A row's boolean field as a check. */
    public static <R> ValueRenderer<R> ofBoolean(Predicate<? super R> field) {
        return new BooleanRenderer<>(field);
    }

    /** A row's field {@code field} shown by {@code renderer} (kinds pass through). */
    public static <R, V> ValueRenderer<R> of(Function<? super R, ? extends V> field, ValueRenderer<? super V> renderer) {
        return new FieldRenderer<>(field, renderer);
    }

    @AllowAllocation("views are made while the visible window fills")
    static Element textView(ViewContext context, String id) {
        Element label = context.ui().label(null, "");
        label.id(id);
        return label;
    }

    /** Sets a text view's text and names the cell with it. */
    static void show(Element view, CharSequence text, CellState state) {
        Ui ui = state.ui();
        ui.text(view, text);
        state.name(ui.text(view));
    }

    /** Any value as text. */
    static final class TextRenderer<T> implements ValueRenderer<T> {

        private final String id;

        TextRenderer(String id) {
            this.id = id;
        }

        @Override
        public Object viewType() {
            return id;
        }

        @Override
        public Element create(int kind, ViewContext context) {
            return textView(context, id);
        }

        @Override
        @AllowAllocation("values that are not text are converted: give such types a renderer of their own")
        public void bind(Element view, T value, CellState state) {
            show(view, value instanceof CharSequence cs ? cs : value == null ? "" : String.valueOf(value), state);
        }
    }

    /** Numbers. */
    static final class NumberRenderer implements ValueRenderer<Number> {

        private final int decimals;
        private final StringBuilder scratch = new StringBuilder();

        NumberRenderer(int decimals) {
            this.decimals = Math.max(0, Math.min(9, decimals));
        }

        @Override
        public Object viewType() {
            return "number";
        }

        @Override
        public Element create(int kind, ViewContext context) {
            return textView(context, "number");
        }

        @Override
        public void bind(Element view, Number value, CellState state) {
            scratch.setLength(0);
            if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
                scratch.append(value.longValue());
            } else if (value != null) {
                Format.append(scratch, value.doubleValue(), decimals);
            }
            show(view, scratch, state);
        }
    }

    /** Booleans as a check element. */
    static final class BooleanRenderer<R> implements ValueRenderer<R> {

        private final Predicate<? super R> field;

        BooleanRenderer(Predicate<? super R> field) {
            this.field = field;
        }

        @Override
        public Object viewType() {
            return "value-check";
        }

        @Override
        @AllowAllocation("views are made while the visible window fills")
        public Element create(int kind, ViewContext context) {
            Element check = context.ui().element("value-check", null);
            check.semantics(Role.CHECKBOX, FALSE);
            return check;
        }

        @Override
        public void bind(Element view, R value, CellState state) {
            boolean on = field == null ? Boolean.TRUE.equals(value) : field.test(value);
            view.state(UiState.CHECKED, on);
            String name = on ? TRUE : FALSE;
            view.semantics(Role.CHECKBOX, name);
            state.name(name);
        }
    }

    /** Enum constants by name. */
    static final class EnumRenderer implements ValueRenderer<Enum<?>> {

        @Override
        public Object viewType() {
            return "enum";
        }

        @Override
        public Element create(int kind, ViewContext context) {
            return textView(context, "enum");
        }

        @Override
        public void bind(Element view, Enum<?> value, CellState state) {
            show(view, value == null ? "" : value.name(), state);
        }
    }

    /** Vectors as text. */
    static final class VectorRenderer implements ValueRenderer<Object> {

        private final StringBuilder scratch = new StringBuilder();

        @Override
        public Element create(int kind, ViewContext context) {
            return textView(context, "vector");
        }

        @Override
        public void bind(Element view, Object value, CellState state) {
            scratch.setLength(0);
            switch (value) {
                case Vec2 v -> component(component(scratch, v.x()).append(", "), v.y());
                case Vec3 v -> component(component(component(scratch, v.x()).append(", "), v.y()).append(", "), v.z());
                case Vec4 v -> component(component(component(component(scratch, v.x()).append(", "), v.y())
                    .append(", "), v.z()).append(", "), v.w());
                default -> scratch.append('?');
            }
            show(view, scratch, state);
        }

        private static StringBuilder component(StringBuilder out, double v) {
            return Format.append(out, v, 3);
        }
    }

    /** Colours as hex. */
    static final class ColorRenderer implements ValueRenderer<Color> {

        private final StringBuilder scratch = new StringBuilder();

        @Override
        public Element create(int kind, ViewContext context) {
            return textView(context, "color");
        }

        @Override
        public void bind(Element view, Color value, CellState state) {
            scratch.setLength(0);
            if (value != null) {
                Format.hex(scratch, value.r(), value.g(), value.b(), value.a());
            }
            show(view, scratch, state);
        }
    }

    /** An int field. */
    static final class IntField<R> implements ValueRenderer<R> {

        private final ToIntFunction<? super R> field;
        private final StringBuilder scratch = new StringBuilder();

        IntField(ToIntFunction<? super R> field) {
            this.field = field;
        }

        @Override
        public Object viewType() {
            return "number";
        }

        @Override
        public Element create(int kind, ViewContext context) {
            return textView(context, "number");
        }

        @Override
        public void bind(Element view, R row, CellState state) {
            scratch.setLength(0);
            scratch.append(field.applyAsInt(row));
            show(view, scratch, state);
        }
    }

    /** A double field. */
    static final class DoubleField<R> implements ValueRenderer<R> {

        private final ToDoubleFunction<? super R> field;
        private final int decimals;
        private final StringBuilder scratch = new StringBuilder();

        DoubleField(ToDoubleFunction<? super R> field, int decimals) {
            this.field = field;
            this.decimals = Math.max(0, Math.min(9, decimals));
        }

        @Override
        public Object viewType() {
            return "number";
        }

        @Override
        public Element create(int kind, ViewContext context) {
            return textView(context, "number");
        }

        @Override
        public void bind(Element view, R row, CellState state) {
            scratch.setLength(0);
            Format.append(scratch, field.applyAsDouble(row), decimals);
            show(view, scratch, state);
        }
    }

    /** A text field. */
    static final class TextField<R> implements ValueRenderer<R> {

        private final Function<? super R, ? extends CharSequence> field;

        TextField(Function<? super R, ? extends CharSequence> field) {
            this.field = field;
        }

        @Override
        public Object viewType() {
            return "text";
        }

        @Override
        public Element create(int kind, ViewContext context) {
            return textView(context, "text");
        }

        @Override
        public void bind(Element view, R row, CellState state) {
            CharSequence text = field.apply(row);
            show(view, text == null ? "" : text, state);
        }
    }

    /** A field shown by another renderer. */
    static final class FieldRenderer<R, V> implements ValueRenderer<R> {

        private final Function<? super R, ? extends V> field;
        private final ValueRenderer<? super V> renderer;

        FieldRenderer(Function<? super R, ? extends V> field, ValueRenderer<? super V> renderer) {
            this.field = field;
            this.renderer = renderer;
        }

        @Override
        public int kind(R row) {
            return kindOf(renderer, field.apply(row));
        }

        @SuppressWarnings("unchecked")
        private static <V> int kindOf(ValueRenderer<? super V> renderer, V value) {
            return ((ValueRenderer<V>) renderer).kind(value);
        }

        @Override
        public Object viewType() {
            return renderer.viewType();
        }

        @Override
        public Element create(int kind, ViewContext context) {
            return renderer.create(kind, context);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void bind(Element view, R row, CellState state) {
            ((ValueRenderer<V>) renderer).bind(view, field.apply(row), state);
        }

        @Override
        public void unbind(Element view) {
            renderer.unbind(view);
        }
    }
}
