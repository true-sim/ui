package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * A typed style attribute, declared as a constant by the control or component that reads it (like {@code Setting}
 * and {@code PluginKey}): its name in style sheets, its {@link StyleType}, and the neutral fallback used when no rule
 * sets it, so a missing theme is visible rather than a crash. Declaring a key registers it ({@link StyleKeys}), so a
 * new control's keys are usable in {@code .tss} files at once. Each key gets a dense index: resolved styles are flat
 * slot arrays read by it.
 *
 * <pre>{@code
 * public static final StyleKey<Paint> BACKGROUND = StyleKey.of("background", StyleTypes.paint(), Paint.NONE);
 * public static final StyleKey<Float> FONT_SIZE = StyleKey.number("font-size", 14, 1, 512);
 * }</pre>
 *
 * <p>Identity equality: one key per name.
 *
 * @param <T> the value type
 */
public final class StyleKey<T> {

    private final String name;
    private final StyleType<T> type;
    private final T defaultValue;
    private final int index;

    @AllowAllocation("keys are declared once")
    private StyleKey(String name, StyleType<T> type, T defaultValue) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.defaultValue = type.check(defaultValue);
        this.index = StyleKeys.register(this);
    }

    /** Declares and registers key {@code name} of {@code type}, {@code defaultValue} when no rule sets it. */
    public static <T> StyleKey<T> of(String name, StyleType<T> type, T defaultValue) {
        return new StyleKey<>(name, type, defaultValue);
    }

    /** A number key in {@code min..max} (unitless or px in sheets). */
    @AllowAllocation("keys are declared once")
    public static StyleKey<Float> number(String name, float defaultValue, float min, float max) {
        return new StyleKey<>(name, StyleTypes.number(min, max), defaultValue);
    }

    /** An enumeration key: constants are written in kebab case ({@code SPACE_BETWEEN} is {@code space-between}). */
    public static <E extends Enum<E>> StyleKey<E> of(String name, Class<E> type, E defaultValue) {
        return new StyleKey<>(name, StyleTypes.enumeration(type), defaultValue);
    }

    /** Its name in style sheets. */
    public String name() {
        return name;
    }

    /** Its type. */
    public StyleType<T> type() {
        return type;
    }

    /** The value when no rule sets it. */
    public T defaultValue() {
        return defaultValue;
    }

    /** Its dense index: the slot resolved styles keep its value in. */
    public int index() {
        return index;
    }

    @Override
    public String toString() {
        return name;
    }
}
