package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedDouble;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.VersionedLong;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.CheckState;
import com.truesim.ui.controls.basic.Checkbox;
import com.truesim.ui.controls.basic.ChoiceGroup;
import com.truesim.ui.controls.basic.Collapsible;
import com.truesim.ui.controls.basic.ColorPicker;
import com.truesim.ui.controls.basic.Control;
import com.truesim.ui.controls.basic.DropDown;
import com.truesim.ui.controls.basic.RadioGroup;
import com.truesim.ui.controls.basic.SegmentedControl;
import com.truesim.ui.controls.basic.Slider;
import com.truesim.ui.controls.data.Format;
import com.truesim.ui.controls.text.NumberField;
import com.truesim.ui.controls.text.PasswordField;
import com.truesim.ui.controls.text.TextEditor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.bind.Bindable;
import com.truesim.ui.core.bind.Binding;
import com.truesim.ui.core.bind.Converter;
import com.truesim.ui.core.bind.Validator;
import com.truesim.ui.props.annotations.Angle;
import com.truesim.ui.props.annotations.AssetType;
import com.truesim.ui.props.annotations.Color;
import com.truesim.ui.props.annotations.EnumStyle;
import com.truesim.ui.props.annotations.Field;
import com.truesim.ui.props.annotations.Foldout;
import com.truesim.ui.props.annotations.Label;
import com.truesim.ui.props.annotations.Multiline;
import com.truesim.ui.props.annotations.Password;
import com.truesim.ui.props.annotations.Range;
import com.truesim.ui.props.annotations.Step;
import com.truesim.ui.props.annotations.Unit;
import com.truesim.ui.style.Facts;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The editor registry of a {@link PropertyBuilder} (design 5.11): editors by name, the default editor name per value
 * type and per annotation. The shipped editors, over the existing controls:
 * <ul>
 * <li>{@code number}: a {@link NumberField} ({@code @Range} clamps, {@code @Step}, {@code @Unit} after it);
 * <li>{@code slider}: a {@link Slider} over {@code @Range} with the value beside it ({@code @Slider}, or any number
 * with a range unless {@code @Field});
 * <li>{@code angle}: a number field in degrees over radians ({@code @Angle});
 * <li>{@code checkbox}: booleans; {@code text}, {@code multiline}, {@code password}: strings;
 * <li>{@code dropdown}, {@code radio}, {@code segmented}: enums ({@code @EnumStyle});
 * <li>{@code color}: a {@link ColorPicker} over a draw-list colour ({@code long}, {@link Rgba}) or an 8-bit sRGB
 * {@code int} {@code 0xRRGGBBAA} ({@code @Color});
 * <li>{@code asset}: a text field with fact {@code asset=<kind>} ({@code @AssetType}; the engine registers its asset
 * picker under this name);
 * <li>{@code record}: a nested panel of a record or class in a collapsible, through the lens chain;
 * <li>{@code collection}: a {@link CollectionEditor} of lists, sets, maps and arrays;
 * <li>{@code readonly}: the value as text (types nothing else edits).
 * </ul>
 */
@AllowAllocation("editors are made when a panel is built or restyled")
public final class Editors {

    private final Map<String, EditorFactory> byName = new LinkedHashMap<>();
    private final Map<Class<?>, String> byType = new HashMap<>();
    private final Map<Class<? extends Annotation>, String> byAnnotation = new LinkedHashMap<>();

    /** A registry with the shipped editors. */
    public static Editors standard() {
        Editors e = new Editors();
        e.register("number", Editors::number);
        e.register("slider", Editors::slider);
        e.register("angle", Editors::angle);
        e.register("checkbox", Editors::checkbox);
        e.register("text", row -> text(row, TextEditor.field(row.ui(), null, row.property().label())));
        e.register("multiline", Editors::multiline);
        e.register("password", Editors::password);
        e.register("dropdown", Editors::dropdown);
        e.register("radio", row -> choice(row, new RadioGroup(row.controls(), row.property().label(),
            row.controls().clock().intValue(-1))));
        e.register("segmented", row -> choice(row, new SegmentedControl(row.controls(), row.property().label(),
            row.controls().clock().intValue(-1))));
        e.register("color", Editors::color);
        e.register("asset", Editors::asset);
        e.register("record", Editors::record);
        e.register("collection", CollectionEditor::create);
        e.register("readonly", Editors::readonly);
        return e;
    }

    /** A copy (builders get their own). */
    public Editors copy() {
        Editors e = new Editors();
        e.byName.putAll(byName);
        e.byType.putAll(byType);
        e.byAnnotation.putAll(byAnnotation);
        return e;
    }

    /** Registers {@code factory} as editor {@code name} (replacing one of that name); returns this. */
    public Editors register(String name, EditorFactory factory) {
        byName.put(Objects.requireNonNull(name), Objects.requireNonNull(factory));
        return this;
    }

    /** Edits values of {@code type} with editor {@code name} by default; returns this. */
    public Editors forType(Class<?> type, String name) {
        byType.put(type, name);
        return this;
    }

    /** Edits properties carrying {@code annotation} with editor {@code name} by default; returns this. */
    public Editors forAnnotation(Class<? extends Annotation> annotation, String name) {
        byAnnotation.put(annotation, name);
        return this;
    }

    /** Whether an editor is registered as {@code name}. */
    public boolean has(String name) {
        return byName.containsKey(name);
    }

    /** The editor {@code name} ({@code readonly} for an unknown name). */
    public EditorFactory get(String name) {
        EditorFactory f = byName.get(name);
        return f != null ? f : byName.getOrDefault("readonly", Editors::readonly);
    }

    /** The editor names registered. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    // ---------------------------------------------------------------- choice

    /** The editor {@code p}'s annotations ask for (handlers, registered ones, then the shipped ones), or null. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    String byAnnotations(Property<?, ?> p, Map<Class<? extends Annotation>, AnnotationHandler<?>> handlers) {
        for (Annotation a : p.annotations().list()) {
            AnnotationHandler h = handlers.get(a.annotationType());
            if (h != null) {
                String n = h.editor(a, p);
                if (n != null && has(n)) {
                    return n;
                }
            }
        }
        for (Map.Entry<Class<? extends Annotation>, String> e : byAnnotation.entrySet()) {
            if (p.annotations().has(e.getKey()) && has(e.getValue())) {
                return e.getValue();
            }
        }
        Class<?> t = p.type();
        if (p.annotation(AssetType.class) != null) {
            return "asset";
        }
        if (p.annotation(Color.class) != null && (unboxed(t) == long.class || unboxed(t) == int.class)) {
            return "color";
        }
        if (isNumber(t)) {
            if (p.annotation(Angle.class) != null) {
                return "angle";
            }
            if (p.annotation(Field.class) != null) {
                return "number";
            }
            if (p.annotation(com.truesim.ui.props.annotations.Slider.class) != null) {
                return "slider";
            }
            if (p.annotation(Range.class) != null) {
                return "slider";
            }
        }
        if (t == String.class) {
            if (p.annotation(Password.class) != null) {
                return "password";
            }
            if (p.annotation(Multiline.class) != null) {
                return "multiline";
            }
        }
        EnumStyle es = p.annotation(EnumStyle.class);
        if (es != null && t.isEnum()) {
            return switch (es.value()) {
                case RADIO -> "radio";
                case SEGMENTED -> "segmented";
                case DROPDOWN -> "dropdown";
            };
        }
        return null;
    }

    /** The editor for {@code p}'s type (registered ones first). */
    String byType(Property<?, ?> p) {
        Class<?> t = p.type();
        String registered = byType.get(t);
        if (registered != null && has(registered)) {
            return registered;
        }
        if (unboxed(t) == boolean.class) {
            return "checkbox";
        }
        if (isNumber(t)) {
            return "number";
        }
        if (t == String.class || t == CharSequence.class) {
            return "text";
        }
        if (t.isEnum()) {
            return "dropdown";
        }
        if (t.isArray() || Collection.class.isAssignableFrom(t) || Map.class.isAssignableFrom(t)) {
            return "collection";
        }
        if (!t.isPrimitive() && !t.getName().startsWith("java.")) {
            return "record";
        }
        return "readonly";
    }

    /** The primitive type of {@code t} (itself or its box), or null. */
    static Class<?> unboxed(Class<?> t) {
        if (t.isPrimitive()) {
            return t;
        }
        return t == Double.class ? double.class : t == Float.class ? float.class : t == Integer.class ? int.class
            : t == Long.class ? long.class : t == Short.class ? short.class : t == Byte.class ? byte.class
            : t == Boolean.class ? boolean.class : t == Character.class ? char.class : null;
    }

    /** Whether {@code t} is a number type (primitive or boxed). */
    static boolean isNumber(Class<?> t) {
        Class<?> u = unboxed(t);
        return u != null && u != boolean.class && u != char.class;
    }

    static boolean integral(Class<?> t) {
        Class<?> u = unboxed(t);
        return u == int.class || u == long.class || u == short.class || u == byte.class;
    }

    /** Converts a number of the property's type to a double and back (rounded for whole numbers). */
    static Converter<Object, Double> numbers(Class<?> type) {
        Class<?> u = unboxed(type);
        return Converter.of(v -> v instanceof Number n ? n.doubleValue() : 0.0, d -> {
            double x = d == null ? 0 : d;
            long whole = x >= 0 ? (long) (x + 0.5) : -(long) (-x + 0.5);
            if (u == int.class) {
                return (int) whole;
            } else if (u == long.class) {
                return whole;
            } else if (u == short.class) {
                return (short) whole;
            } else if (u == byte.class) {
                return (byte) whole;
            } else if (u == float.class) {
                return (float) x;
            }
            return x;
        });
    }

    private static double step(PropertyRow row) {
        Step s = row.annotation(Step.class);
        return s != null ? s.value() : integral(row.property().type()) ? 1 : 0.1;
    }

    private static int decimals(PropertyRow row) {
        if (integral(row.property().type())) {
            return 0;
        }
        Step s = row.annotation(Step.class);
        if (s == null) {
            return 2;
        }
        int d = 0;
        for (double v = s.value(); d < 6 && v != (long) v; v *= 10) {
            d++;
        }
        return d;
    }

    // ---------------------------------------------------------------- shipped editors

    /** An editor of {@code control}'s element that stops its polling when dropped. */
    static PropertyEditor of(Element element, Control... controls) {
        return new PropertyEditor() {
            @Override
            public Element element() {
                return element;
            }

            @Override
            public void dispose() {
                for (Control c : controls) {
                    c.dispose();
                }
            }
        };
    }

    /** A number field's value as a bindable: changed by its listener (steps, drags, commits). */
    static final class FieldValue implements Bindable<Double>, NumberField.Listener {
        private final NumberField field;
        private final ChangeClock clock;
        private long version;

        FieldValue(NumberField field, ChangeClock clock) {
            this.field = field;
            this.clock = clock;
            field.listener(this);
        }

        @Override
        public Double get() {
            return field.value();
        }

        @Override
        public boolean set(Double value) {
            double v = value == null ? 0 : value;
            if (v == field.value()) {
                return false;
            }
            field.value(v);
            version = clock.stamp();
            return true;
        }

        @Override
        public void touch() {
            version = clock.stamp();
        }

        @Override
        public long version() {
            return version;
        }

        @Override
        public void changed(NumberField f, double value) {
            version = clock.stamp();
        }
    }

    static PropertyEditor number(PropertyRow row) {
        return numberField(row, numbers(row.property().type()), row.annotation(Range.class), decimals(row));
    }

    private static PropertyEditor numberField(PropertyRow row, Converter<Object, Double> converter, Range range,
        int decimals) {
        Ui ui = row.ui();
        String label = row.property().label();
        NumberField f = NumberField.create(ui, null, label, "Decrease " + label, "Increase " + label);
        if (range != null) {
            f.range(range.min(), range.max());
        }
        f.step(step(row)).decimals(decimals);
        row.bind(new FieldValue(f, row.controls().clock()), converter, f.element());
        Unit unit = row.annotation(Unit.class);
        if (unit == null) {
            return PropertyEditor.of(f.element());
        }
        Element box = ui.element("props", "editor");
        box.add(f.element());
        box.add(ui.label("prop-unit", unit.value()));
        return PropertyEditor.of(box);
    }

    static PropertyEditor angle(PropertyRow row) {
        Angle a = row.annotation(Angle.class);
        Converter<Object, Double> n = numbers(row.property().type());
        Converter<Object, Double> c = a != null && a.radians() ? n.then(Converter.degrees()) : n;
        Range r = row.annotation(Range.class);
        if (r != null && a != null && a.radians()) {
            r = Hints.range(r.min() * (180 / Math.PI), r.max() * (180 / Math.PI));
        }
        return numberField(row, c, r, integral(row.property().type()) ? 0 : 1);
    }

    static PropertyEditor slider(PropertyRow row) {
        Range r = row.annotation(Range.class);
        if (r == null) {
            return number(row);
        }
        Ui ui = row.ui();
        Slider s = new Slider(row.controls(), row.property().label(), row.controls().clock().doubleValue(r.min()),
            r.min(), r.max(), false);
        if (row.annotation(Step.class) != null || integral(row.property().type())) {
            s.step(step(row));
        }
        Element box = ui.element("props", "editor");
        Element value = ui.label("prop-value", "");
        box.add(s.element()).add(value);
        VersionedDouble model = s.model(0);
        row.bind(Bindable.of(model), numbers(row.property().type()), s.element());
        int decimals = decimals(row);
        StringBuilder text = new StringBuilder();
        Unit unit = row.annotation(Unit.class);
        return new PropertyEditor() {
            private long seen = -1;

            @Override
            public Element element() {
                return box;
            }

            @Override
            public void poll() {
                if (model.version() != seen) {
                    seen = model.version();
                    text.setLength(0);
                    Format.append(text, model.get(), decimals);
                    if (unit != null) {
                        text.append(' ').append(unit.value());
                    }
                    ui.text(value, text);
                }
            }

            @Override
            public void dispose() {
                s.dispose();
            }
        };
    }

    static PropertyEditor checkbox(PropertyRow row) {
        Checkbox c = new Checkbox(row.controls(), "", row.controls().clock().value(CheckState.UNCHECKED));
        c.name(row.property().label());
        row.bind(Bindable.of(c.model()), Converter.of(
            v -> Boolean.TRUE.equals(v) ? CheckState.CHECKED : CheckState.UNCHECKED,
            s -> s == CheckState.CHECKED), c.element());
        return of(c.element(), c);
    }

    /** A text editor's text as a bindable: written into the model when committed (Enter, leaving the field). */
    static final class TextValue implements Bindable<String>, TextEditor.Listener {
        private final TextEditor editor;
        private final ChangeClock clock;
        private long version;

        TextValue(TextEditor editor, ChangeClock clock) {
            this.editor = editor;
            this.clock = clock;
            editor.listener(this);
        }

        @Override
        public String get() {
            return editor.text().toString();
        }

        @Override
        public boolean set(String value) {
            String v = value == null ? "" : value;
            if (v.contentEquals(editor.text())) {
                return false;
            }
            editor.text(v);
            version = clock.stamp();
            return true;
        }

        @Override
        public void touch() {
            version = clock.stamp();
        }

        @Override
        public long version() {
            return version;
        }

        @Override
        public void submitted(TextEditor e) {
            version = clock.stamp();
        }

        @Override
        public void left(TextEditor e) {
            version = clock.stamp();
        }
    }

    private static PropertyEditor text(PropertyRow row, TextEditor editor) {
        Binding<Object, String> b = row.bind(new TextValue(editor, row.controls().clock()),
            Converter.of(v -> v == null ? "" : v.toString(), s -> s), editor.element());
        Validator<Object> v = Steps.validator(row);
        editor.validator(t -> v.validate(t.toString()) == null); // :invalid while typing, the field's own state
        Objects.requireNonNull(b);
        if (row.readOnly()) {
            editor.readOnly(true);
        }
        return PropertyEditor.of(editor.element());
    }

    static PropertyEditor multiline(PropertyRow row) {
        TextEditor area = TextEditor.area(row.ui(), null, row.property().label());
        return text(row, area);
    }

    static PropertyEditor password(PropertyRow row) {
        PasswordField p = PasswordField.create(row.ui(), null, row.property().label(), "Show");
        text(row, p.editor());
        return PropertyEditor.of(p.element());
    }

    static PropertyEditor asset(PropertyRow row) {
        TextEditor field = TextEditor.field(row.ui(), "asset", row.property().label());
        AssetType a = row.annotation(AssetType.class);
        row.ui().styles().facts(field.element().slot(), Facts.of("asset", a == null ? "any" : a.value()));
        return text(row, field);
    }

    /** The label of an enum constant: its {@code @Label}, else its name in words. */
    static String enumLabel(Enum<?> e) {
        try {
            Label l = e.getDeclaringClass().getField(e.name()).getAnnotation(Label.class);
            if (l != null) {
                return l.value();
            }
        } catch (NoSuchFieldException ex) {
            // not a field: use the name
        }
        return Property.words(e.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
    }

    private static Converter<Object, Integer> ordinals(Object[] constants) {
        return Converter.of(v -> v instanceof Enum<?> e ? e.ordinal() : -1,
            i -> i == null || i < 0 || i >= constants.length ? null : constants[i]);
    }

    static PropertyEditor dropdown(PropertyRow row) {
        Object[] constants = row.property().type().getEnumConstants();
        if (constants == null) {
            return readonly(row);
        }
        DropDown<Object> d = new DropDown<>(row.controls(), row.property().label(), List.of(constants),
            o -> enumLabel((Enum<?>) o), row.controls().clock().intValue(-1));
        row.bind(Bindable.of(d.model()), ordinals(constants), d.element());
        return of(d.element(), d);
    }

    private static PropertyEditor choice(PropertyRow row, ChoiceGroup group) {
        Object[] constants = row.property().type().getEnumConstants();
        if (constants == null) {
            group.dispose();
            group.element().destroy();
            return readonly(row);
        }
        for (Object c : constants) {
            group.add(enumLabel((Enum<?>) c));
        }
        VersionedInt m = group.model();
        row.bind(Bindable.of(m), ordinals(constants), group.element());
        return of(group.element(), group);
    }

    /** A packed 8-bit sRGB {@code 0xRRGGBBAA} as a draw-list colour ({@link Rgba}). */
    static long fromInt(int c) {
        return Rgba.srgb(c >>> 24, c >>> 16 & 0xff, c >>> 8 & 0xff, (c & 0xff) / 255f);
    }

    /** A draw-list colour as a packed 8-bit sRGB {@code 0xRRGGBBAA}. */
    static int toInt(long rgba) {
        return channel(Rgba.toSrgb(Rgba.r(rgba))) << 24 | channel(Rgba.toSrgb(Rgba.g(rgba))) << 16
            | channel(Rgba.toSrgb(Rgba.b(rgba))) << 8 | channel(Rgba.a(rgba));
    }

    private static int channel(float f) {
        float c = f < 0 ? 0 : f > 1 ? 1 : f;
        return (int) (c * 255 + 0.5f);
    }

    static PropertyEditor color(PropertyRow row) {
        ColorPicker p = new ColorPicker(row.controls(), row.property().label(), row.controls().clock().longValue(0));
        Color c = row.annotation(Color.class);
        row.ui().styles().facts(p.element().slot(), Facts.of("hdr", Boolean.toString(c != null && c.hdr())));
        boolean ints = unboxed(row.property().type()) == int.class;
        VersionedLong model = p.model();
        row.bind(Bindable.of(model), Converter.of(v -> v instanceof Number n ? ints ? fromInt(n.intValue())
            : n.longValue() : 0L, l -> ints ? (Object) toInt(l) : (Object) l), p.element());
        return of(p.element(), p);
    }

    /** A nested panel of a record or class, in a collapsible titled with the property's label. */
    static PropertyEditor record(PropertyRow row) {
        Class<?> type = row.property().type();
        PropertyPanel<?> nested = row.builder().nested(type).buildNested(row);
        Foldout f = row.annotation(Foldout.class);
        Collapsible c = row.controls().collapsible(row.property().label(), nested.element(),
            row.controls().clock().booleanValue(f == null || f.expanded()));
        c.element().id(row.property().name());
        return new PropertyEditor() {
            @Override
            public Element element() {
                return c.element();
            }

            @Override
            public void dispose() {
                nested.dispose();
                c.dispose();
            }
        };
    }

    static PropertyEditor readonly(PropertyRow row) {
        Ui ui = row.ui();
        Element label = ui.label("prop-value", "");
        Bindable<Object> m = row.model();
        return new PropertyEditor() {
            private long seen = -1;

            @Override
            public Element element() {
                return label;
            }

            @Override
            @AllowAllocation("the text is made when the value changed")
            public void poll() {
                if (m.version() != seen) {
                    seen = m.version();
                    ui.text(label, String.valueOf(m.get()));
                }
            }
        };
    }
}
