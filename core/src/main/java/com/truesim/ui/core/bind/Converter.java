package com.truesim.ui.core.bind;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;
import java.util.function.Function;

/**
 * Converts a model's value to what a control shows and back (design 4.2): radians to degrees, a packed colour to a
 * colour record, an enum to an index. Plain functions; {@link #from} may throw {@link IllegalArgumentException} for a
 * control value with no model value (a binding then marks its element {@code :invalid} with the exception's message
 * and leaves the model alone).
 *
 * @param <M> the model's value type
 * @param <C> the control's value type
 */
public interface Converter<M, C> {

    /** The control's value for model value {@code value}. */
    C to(M value);

    /** The model's value for control value {@code value}. */
    M from(C value);

    /** No conversion. */
    @SuppressWarnings("unchecked")
    static <T> Converter<T, T> identity() {
        return (Converter<T, T>) Identity.INSTANCE;
    }

    /** A converter from two functions. */
    @AllowAllocation("converters are made when a UI is built")
    static <M, C> Converter<M, C> of(Function<? super M, ? extends C> to, Function<? super C, ? extends M> from) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(from, "from");
        return new Converter<>() {
            @Override
            public C to(M value) {
                return to.apply(value);
            }

            @Override
            public M from(C value) {
                return from.apply(value);
            }
        };
    }

    /** The other way round. */
    @AllowAllocation("converters are made when a UI is built")
    default Converter<C, M> inverse() {
        Converter<M, C> self = this;
        return of(self::from, self::to);
    }

    /** This, then {@code next}. */
    @AllowAllocation("converters are made when a UI is built")
    default <D> Converter<M, D> then(Converter<C, D> next) {
        Converter<M, C> self = this;
        return of(m -> next.to(self.to(m)), d -> self.from(next.from(d)));
    }

    /** Radians in the model, degrees in the control. */
    @AllowAllocation("boxed values when an angle changes")
    static Converter<Double, Double> degrees() {
        return of(r -> r == null ? 0.0 : r * (180 / Math.PI), d -> d == null ? 0.0 : d * (Math.PI / 180));
    }

    /** The identity converter. */
    final class Identity implements Converter<Object, Object> {
        static final Identity INSTANCE = new Identity();

        private Identity() {
        }

        @Override
        public Object to(Object value) {
            return value;
        }

        @Override
        public Object from(Object value) {
            return value;
        }
    }
}
