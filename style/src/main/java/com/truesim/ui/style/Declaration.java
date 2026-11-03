package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.expr.TokenExpr;
import java.util.Objects;

/**
 * One key's value in a rule: a typed constant, or a live expression over tokens (evaluated when the style is resolved
 * and again when one of its tokens changes). Exactly one of {@code value} and {@code live} is set.
 */
public record Declaration(StyleKey<?> key, Object value, TokenExpr live) {

    public Declaration {
        Objects.requireNonNull(key, "key");
        if ((value == null) == (live == null)) {
            throw new IllegalArgumentException("a declaration has a value or a live expression");
        }
    }

    /** {@code key} set to {@code value} (checked by the key's type). */
    @AllowAllocation("sheets are built at load")
    public static <T> Declaration of(StyleKey<T> key, T value) {
        return new Declaration(key, key.type().check(value), null);
    }

    /** {@code key} set to a live expression over tokens. */
    @AllowAllocation("sheets are built at load")
    public static Declaration live(StyleKey<?> key, TokenExpr expression) {
        return new Declaration(key, null, Objects.requireNonNull(expression, "expression"));
    }

    /** Whether it depends on tokens. */
    public boolean isLive() {
        return live != null;
    }

    @Override
    public String toString() {
        return key.name() + ": " + (live != null ? live : value);
    }
}
