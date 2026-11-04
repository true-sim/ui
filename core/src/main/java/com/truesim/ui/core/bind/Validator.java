package com.truesim.ui.core.bind;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Checks a value before a binding writes it into its model (design 4.2): returns null when it is valid, else a message
 * for display (a tooltip, a status line). An invalid value is not written; the bound element shows {@code :invalid},
 * the state text fields' own validators set.
 *
 * @param <T> the value type
 */
@FunctionalInterface
public interface Validator<T> {

    /** Null when {@code value} is valid, else why not. */
    String validate(T value);

    /** Every value is valid. */
    @SuppressWarnings("unchecked")
    static <T> Validator<T> valid() {
        return (Validator<T>) Valid.INSTANCE;
    }

    /** Valid when {@code test} holds, else {@code message}. */
    @AllowAllocation("validators are made when a UI is built")
    static <T> Validator<T> of(Predicate<? super T> test, String message) {
        Objects.requireNonNull(test, "test");
        return value -> test.test(value) ? null : message;
    }

    /** Numbers in {@code min..max} (null is invalid). */
    @AllowAllocation("validators are made when a UI is built")
    static <N extends Number> Validator<N> range(double min, double max) {
        String message = "must be between " + min + " and " + max;
        return value -> value != null && value.doubleValue() >= min && value.doubleValue() <= max ? null : message;
    }

    /** This, then {@code next}: the first message wins. */
    @AllowAllocation("validators are made when a UI is built")
    default Validator<T> and(Validator<? super T> next) {
        Validator<T> self = this;
        return value -> {
            String m = self.validate(value);
            return m != null ? m : next.validate(value);
        };
    }

    /** The validator accepting everything. */
    final class Valid implements Validator<Object> {
        static final Valid INSTANCE = new Valid();

        private Valid() {
        }

        @Override
        public String validate(Object value) {
            return null;
        }
    }
}
