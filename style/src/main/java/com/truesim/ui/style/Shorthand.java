package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A name that sets several keys at once ({@code font} sets the family, size, weight and style keys; {@code size} sets
 * width and height). It holds no slot of its own: rules store the keys it expands to. Registered by name like keys.
 *
 * @param <T> the value it takes
 */
@AllowAllocation("declared once; expanded when a sheet is built")
public final class Shorthand<T> {

    private final String name;
    private final StyleType<T> type;
    private final Function<T, List<Declaration>> expansion;

    private Shorthand(String name, StyleType<T> type, Function<T, List<Declaration>> expansion) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.expansion = Objects.requireNonNull(expansion, "expansion");
    }

    /** Declares and registers shorthand {@code name}: {@code expansion} turns its value into declarations of keys. */
    public static <T> Shorthand<T> of(String name, StyleType<T> type, Function<T, List<Declaration>> expansion) {
        Shorthand<T> shorthand = new Shorthand<>(name, type, expansion);
        StyleKeys.register(shorthand);
        return shorthand;
    }

    /** Its name in style sheets. */
    public String name() {
        return name;
    }

    /** The type of the value it takes. */
    public StyleType<T> type() {
        return type;
    }

    /** The declarations {@code value} stands for. */
    public List<Declaration> expand(T value) {
        return expansion.apply(type.check(value));
    }

    @Override
    public String toString() {
        return name;
    }
}
