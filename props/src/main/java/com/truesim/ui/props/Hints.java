package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.bind.Validator;
import com.truesim.ui.props.annotations.Addable;
import com.truesim.ui.props.annotations.Angle;
import com.truesim.ui.props.annotations.AssetType;
import com.truesim.ui.props.annotations.Color;
import com.truesim.ui.props.annotations.ElementLabel;
import com.truesim.ui.props.annotations.EnumStyle;
import com.truesim.ui.props.annotations.Field;
import com.truesim.ui.props.annotations.Foldout;
import com.truesim.ui.props.annotations.Group;
import com.truesim.ui.props.annotations.Hidden;
import com.truesim.ui.props.annotations.Label;
import com.truesim.ui.props.annotations.ListStyle;
import com.truesim.ui.props.annotations.MaxSize;
import com.truesim.ui.props.annotations.MinSize;
import com.truesim.ui.props.annotations.Multiline;
import com.truesim.ui.props.annotations.Order;
import com.truesim.ui.props.annotations.Password;
import com.truesim.ui.props.annotations.Range;
import com.truesim.ui.props.annotations.ReadOnly;
import com.truesim.ui.props.annotations.Removable;
import com.truesim.ui.props.annotations.Reorderable;
import com.truesim.ui.props.annotations.ShowIf;
import com.truesim.ui.props.annotations.Slider;
import com.truesim.ui.props.annotations.Step;
import com.truesim.ui.props.annotations.Tooltip;
import com.truesim.ui.props.annotations.Unique;
import com.truesim.ui.props.annotations.Unit;
import com.truesim.ui.props.annotations.Validate;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Annotation instances made in code, for property models that do not come from annotated Java types: the engine's
 * settings ({@code Hints.range(0, 1)} from a setting's range) and generated material records, and the code the
 * annotation processor writes. {@link #annotation} makes any annotation type, including custom ones, from element
 * values (elements left out take their defaults).
 */
@AllowAllocation("metadata is built once per type")
public final class Hints {

    private Hints() {
    }

    /**
     * An instance of annotation {@code type} with the elements {@code namesAndValues} (name, value pairs); elements not
     * given take their declared defaults. Equal and hashed like the JDK's own annotation instances.
     */
    public static <A extends Annotation> A annotation(Class<A> type, Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("elements come in name, value pairs");
        }
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        for (Method m : type.getDeclaredMethods()) {
            if (!values.containsKey(m.getName())) {
                Object d = m.getDefaultValue();
                if (d == null) {
                    throw new IllegalArgumentException("@" + type.getSimpleName() + " needs " + m.getName());
                }
                values.put(m.getName(), d);
            }
        }
        for (String name : values.keySet()) {
            try {
                type.getDeclaredMethod(name);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("@" + type.getSimpleName() + " has no element " + name, e);
            }
        }
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> type;
                case "hashCode" -> hash(values);
                case "equals" -> args[0] instanceof Annotation other && other.annotationType() == type
                    && same(type, values, other);
                case "toString" -> "@" + type.getName() + values;
                default -> values.get(method.getName());
            }));
    }

    private static int hash(Map<String, Object> values) {
        int h = 0;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            int vh = v.getClass().isArray() ? Arrays.deepHashCode(new Object[] {v}) - 31 : v.hashCode();
            h += (127 * e.getKey().hashCode()) ^ vh;
        }
        return h;
    }

    private static boolean same(Class<?> type, Map<String, Object> values, Object other) throws Exception {
        for (Method m : type.getDeclaredMethods()) {
            Object a = values.get(m.getName());
            Object b = m.invoke(other);
            if (a.getClass().isArray() ? !Arrays.deepEquals(new Object[] {a}, new Object[] {b})
                : !Objects.equals(a, b)) {
                return false;
            }
        }
        return true;
    }

    /** {@code @Label(text)}. */
    public static Label label(String text) {
        return annotation(Label.class, "value", text);
    }

    /** {@code @Tooltip(text)}. */
    public static Tooltip tooltip(String text) {
        return annotation(Tooltip.class, "value", text);
    }

    /** {@code @Range(min, max)}. */
    public static Range range(double min, double max) {
        return annotation(Range.class, "min", min, "max", max);
    }

    /** {@code @Step(step)}. */
    public static Step step(double step) {
        return annotation(Step.class, "value", step);
    }

    /** {@code @Unit(unit)}. */
    public static Unit unit(String unit) {
        return annotation(Unit.class, "value", unit);
    }

    /** {@code @Color(hdr)}. */
    public static Color color(boolean hdr) {
        return annotation(Color.class, "hdr", hdr);
    }

    /** {@code @Angle(radians)}. */
    public static Angle angle(boolean radians) {
        return annotation(Angle.class, "radians", radians);
    }

    /** {@code @Slider}. */
    public static Slider slider() {
        return annotation(Slider.class);
    }

    /** {@code @Field}. */
    public static Field field() {
        return annotation(Field.class);
    }

    /** {@code @Multiline(lines)}. */
    public static Multiline multiline(int lines) {
        return annotation(Multiline.class, "lines", lines);
    }

    /** {@code @Password}. */
    public static Password password() {
        return annotation(Password.class);
    }

    /** {@code @EnumStyle(kind)}. */
    public static EnumStyle enumStyle(EnumStyle.Kind kind) {
        return annotation(EnumStyle.class, "value", kind);
    }

    /** {@code @Hidden}. */
    public static Hidden hidden() {
        return annotation(Hidden.class);
    }

    /** {@code @ReadOnly}. */
    public static ReadOnly readOnly() {
        return annotation(ReadOnly.class);
    }

    /** {@code @Group(name)}. */
    public static Group group(String name) {
        return annotation(Group.class, "value", name);
    }

    /** {@code @Order(order)}. */
    public static Order order(int order) {
        return annotation(Order.class, "value", order);
    }

    /** {@code @Foldout(expanded)}. */
    public static Foldout foldout(boolean expanded) {
        return annotation(Foldout.class, "expanded", expanded);
    }

    /** {@code @ShowIf(property, is)}. */
    public static ShowIf showIf(String property, String is) {
        return annotation(ShowIf.class, "value", property, "is", is);
    }

    /** {@code @Validate(validators)}. */
    @SafeVarargs
    public static Validate validate(Class<? extends Validator<?>>... validators) {
        Object array = Array.newInstance(Class.class, validators.length);
        for (int i = 0; i < validators.length; i++) {
            Array.set(array, i, validators[i]);
        }
        return annotation(Validate.class, "value", array);
    }

    /** {@code @AssetType(kind)}. */
    public static AssetType assetType(String kind) {
        return annotation(AssetType.class, "value", kind);
    }

    /** {@code @ListStyle(kind)}. */
    public static ListStyle listStyle(String kind) {
        return annotation(ListStyle.class, "value", kind);
    }

    /** {@code @Addable(on)}. */
    public static Addable addable(boolean on) {
        return annotation(Addable.class, "value", on);
    }

    /** {@code @Removable(on)}. */
    public static Removable removable(boolean on) {
        return annotation(Removable.class, "value", on);
    }

    /** {@code @Reorderable(on)}. */
    public static Reorderable reorderable(boolean on) {
        return annotation(Reorderable.class, "value", on);
    }

    /** {@code @MinSize(n)}. */
    public static MinSize minSize(int n) {
        return annotation(MinSize.class, "value", n);
    }

    /** {@code @MaxSize(n)}. */
    public static MaxSize maxSize(int n) {
        return annotation(MaxSize.class, "value", n);
    }

    /** {@code @Unique}. */
    public static Unique unique() {
        return annotation(Unique.class);
    }

    /** {@code @ElementLabel(property)}. */
    public static ElementLabel elementLabel(String property) {
        return annotation(ElementLabel.class, "value", property);
    }
}
