package com.truesim.ui.style;

import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.expr.ValueException;

/**
 * The type of a {@link StyleKey}'s values: how a value written in a {@code .tss} file (or produced by a live token
 * expression) becomes the typed value the key holds, and which typed values are valid (ranges, non-null). Instances
 * come from {@link StyleTypes}; a control may write its own for its own value records.
 *
 * @param <T> the typed value
 */
public interface StyleType<T> {

    /** What it accepts, for messages: {@code "a colour"}, {@code "a length (px, em, % or auto)"}. */
    String description();

    /**
     * {@code value} as this type.
     *
     * @throws ValueException with a message saying what was expected, if it is not one
     */
    T parse(Value value);

    /**
     * Checks a value set from Java.
     *
     * @return {@code value}
     * @throws IllegalArgumentException if it is out of range or null
     */
    default T check(T value) {
        if (value == null) {
            throw new IllegalArgumentException("a style value cannot be null");
        }
        return value;
    }
}
