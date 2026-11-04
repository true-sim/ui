package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.bind.Bindable;
import com.truesim.ui.core.bind.Binding;
import com.truesim.ui.core.bind.Converter;
import com.truesim.ui.core.bind.Validator;
import com.truesim.ui.props.annotations.Group;
import com.truesim.ui.props.annotations.Hidden;
import com.truesim.ui.props.annotations.MaxSize;
import com.truesim.ui.props.annotations.MinSize;
import com.truesim.ui.props.annotations.Order;
import com.truesim.ui.props.annotations.Range;
import com.truesim.ui.props.annotations.ShowIf;
import com.truesim.ui.props.annotations.Unique;
import com.truesim.ui.props.annotations.Validate;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The steps of the property builder pipeline (design 5.11, UI17), each replaceable with
 * {@link PropertyBuilder#replace} or reset to its neutral form with {@link PropertyBuilder#remove}:
 *
 * <pre>
 * PropertyModel → FILTER (visibility: {@code @Hidden}, {@code @ShowIf}; style {@code visible} above it)
 *              → ORDER ({@code @Order}; style {@code order} above it)
 *              → GROUP ({@code @Group}, {@code @Foldout} → sections)
 *              → EDITOR (per row: code &gt; style {@code editor} &gt; annotation &gt; type &gt; fallback)
 *              → LAYOUT (form, grid, inspector, compact, row; code &gt; style {@code props-layout} &gt; default)
 *              → BIND (lenses into the root's holder, validation, edits)
 *              → element tree ({@code props.row[type=float][annotation=Range][property=roughness][group=Surface]})
 * </pre>
 */
@AllowAllocation("panels are built when a UI is built")
public final class Steps {

    private Steps() {
    }

    /**
     * A pipeline step's key: its name, its standard implementation and its neutral one (what removing it leaves).
     *
     * @param <S> the step's interface
     */
    public static final class Key<S> {
        private final String name;
        private final S standard;
        private final S neutral;

        Key(String name, S standard, S neutral) {
            this.name = name;
            this.standard = standard;
            this.neutral = neutral;
        }

        /** The name. */
        public String name() {
            return name;
        }

        /** The standard implementation. */
        public S standard() {
            return standard;
        }

        /** What removing the step leaves. */
        public S neutral() {
            return neutral;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Which rows show (re-evaluated whenever the panel's value changes, for {@code @ShowIf}). */
    @FunctionalInterface
    public interface Filter {
        /** Whether {@code row} shows while the panel's value is {@code value}. */
        boolean visible(PropertyRow row, Object value);
    }

    /** The order of rows (sorted in place); rows carry their styled order ({@link PropertyRow#styledOrder()}). */
    @FunctionalInterface
    public interface Ordering {
        /** Sorts {@code rows}. */
        void order(List<PropertyRow> rows);
    }

    /** The section a row goes in. */
    @FunctionalInterface
    public interface Grouping {
        /** The group's name, or the empty string for none. */
        String group(PropertyRow row);
    }

    /** The editor of a row, by name. */
    @FunctionalInterface
    public interface EditorChoice {
        /** The editor name for {@code row}; {@code styled} is the row's style {@code editor} (empty if unset). */
        String choose(PropertyRow row, String styled);
    }

    /** Connects an editor's control to the row's value. */
    @FunctionalInterface
    public interface Binder {
        /**
         * Binds {@code control} to {@code row}'s value through {@code converter}, {@code invalid} showing
         * {@code :invalid} for values not written; the binding is polled by the UI until the row is rebuilt.
         */
        <C> Binding<Object, C> bind(PropertyRow row, Bindable<C> control, Converter<Object, C> converter,
            Element invalid);
    }

    /** Visibility: {@code @Hidden} hides; {@code @ShowIf} shows while another property holds. */
    public static final Key<Filter> FILTER = new Key<>("filter", Steps::standardVisible, (row, value) -> true);
    /** Order: the style's {@code order}, else {@code @Order}, else 0; ties by declaration. */
    public static final Key<Ordering> ORDER = new Key<>("order",
        rows -> rows.sort(Comparator.comparingInt(Steps::orderOf).thenComparingInt(r -> r.property().index())),
        rows -> rows.sort(Comparator.comparingInt(r -> r.property().index())));
    /** Groups: {@code @Group}. */
    public static final Key<Grouping> GROUP = new Key<>("group", row -> {
        Group g = row.property().annotation(Group.class);
        return g == null ? "" : g.value();
    }, row -> "");
    /** Editors: code, then the style's {@code editor}, then annotations, then the type, then the fallback. */
    public static final Key<EditorChoice> EDITOR = new Key<>("editor", Steps::standardEditor,
        (row, styled) -> row.builder().editors().byType(row.property()));
    /** Layout: code, then the style's {@code props-layout}, then the builder's default. */
    public static final Key<LayoutStrategy> LAYOUT = new Key<>("layout", Layouts.BY_NAME, Layouts.BY_NAME);
    /** Binding: through the row's lens into the root's holder, validated, edits reported. */
    public static final Key<Binder> BIND = new Key<>("bind", Steps::standardBind, Steps::standardBind);

    /** Every key, in pipeline order. */
    public static List<Key<?>> all() {
        return List.of(FILTER, ORDER, GROUP, EDITOR, LAYOUT, BIND);
    }

    static boolean standardVisible(PropertyRow row, Object value) {
        Property<?, ?> p = row.property();
        if (p.annotation(Hidden.class) != null) {
            return false;
        }
        ShowIf showIf = p.annotation(ShowIf.class);
        if (showIf == null || value == null) {
            return true;
        }
        Property<Object, ?> other = row.panel().propertyOf(showIf.value());
        if (other == null) {
            return true;
        }
        return holds(other.get(value), showIf.is());
    }

    /** Whether {@code v} holds: true, non-null and non-empty when {@code is} is empty, else its name equals it. */
    static boolean holds(Object v, String is) {
        if (is.isEmpty()) {
            return v instanceof Boolean b ? b : v instanceof CharSequence s ? !s.isEmpty()
                : v instanceof Collection<?> c ? !c.isEmpty() : v != null;
        }
        return v != null && (v instanceof Enum<?> e ? e.name().equals(is) : is.equals(v.toString()));
    }

    static int orderOf(PropertyRow row) {
        Integer styled = row.styledOrder();
        if (styled != null) {
            return styled;
        }
        Order o = row.property().annotation(Order.class);
        return o == null ? 0 : o.value();
    }

    static String standardEditor(PropertyRow row, String styled) {
        Editors editors = row.builder().editors();
        String code = row.builder().codeEditor(row.property().name());
        if (code != null && editors.has(code)) {
            return code;
        }
        if (!styled.isEmpty() && editors.has(styled)) {
            return styled;
        }
        String annotated = editors.byAnnotations(row.property(), row.builder().handlers());
        if (annotated != null) {
            return annotated;
        }
        return editors.byType(row.property());
    }

    static <C> Binding<Object, C> standardBind(PropertyRow row, Bindable<C> control, Converter<Object, C> converter,
        Element invalid) {
        Binding<Object, C> b = new Binding<>(row.model(), control, converter).validator(validator(row)).element(invalid)
            .listener(row);
        row.add(b);
        return b;
    }

    /** The validator of a row's values: {@code @Range}, {@code @Validate}, sizes, uniqueness and handlers'. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Validator<Object> validator(PropertyRow row) {
        Property<?, ?> p = row.property();
        Validator<Object> v = Validator.valid();
        Range r = p.annotation(Range.class);
        if (r != null && Editors.isNumber(p.type())) {
            Validator<Number> range = Validator.range(r.min(), r.max());
            v = v.and(o -> o instanceof Number n ? range.validate(n) : null);
        }
        MinSize min = p.annotation(MinSize.class);
        MaxSize max = p.annotation(MaxSize.class);
        if (min != null || max != null) {
            int lo = min == null ? 0 : min.value();
            int hi = max == null ? Integer.MAX_VALUE : max.value();
            v = v.and(o -> {
                int n = CollectionOps.size(o);
                return n < lo ? "needs at least " + lo + " items" : n > hi ? "takes at most " + hi + " items" : null;
            });
        }
        if (p.annotation(Unique.class) != null) {
            v = v.and(o -> CollectionOps.unique(o) ? null : "items must differ");
        }
        Validate validate = p.annotation(Validate.class);
        if (validate != null) {
            for (Class<? extends Validator<?>> c : validate.value()) {
                try {
                    Validator<Object> custom = (Validator<Object>) c.getDeclaredConstructor().newInstance();
                    v = v.and(custom);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot make validator " + c.getName(), e);
                }
            }
        }
        for (Annotation a : p.annotations().list()) {
            AnnotationHandler h = row.builder().handlers().get(a.annotationType());
            if (h != null) {
                Validator<Object> custom = h.validator(a, p);
                if (custom != null) {
                    v = v.and(custom);
                }
            }
        }
        return v;
    }
}
