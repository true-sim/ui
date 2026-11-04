package com.truesim.ui.core.bind;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.VersionedDouble;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.VersionedLong;
import com.truesim.common.state.VersionedValue;
import java.util.Objects;

/**
 * One end of a {@link Binding}: a value with a {@link #version()} stamped on each change, read and written as an
 * object. The adapters here wrap the {@code common-state} holders controls and models use ({@link VersionedValue},
 * {@link VersionedDouble}, {@link VersionedInt}, {@link VersionedLong}, {@link VersionedBoolean}) and a field of a
 * record inside another bindable through a {@link Lens} ({@link #through}). Reading the version allocates nothing;
 * reading and writing a primitive holder boxes, which happens only when a value changed.
 *
 * @param <T> the value type
 */
public interface Bindable<T> extends com.truesim.common.state.Versioned {

    /** The value. */
    T get();

    /** Sets the value; returns whether that was a change. */
    boolean set(T value);

    /** Stamps a change without a new value (a mutable value changed in place). */
    void touch();

    /** The value of {@code holder}. */
    @AllowAllocation("bindings are made when a UI is built")
    static <T> Bindable<T> of(VersionedValue<T> holder) {
        Objects.requireNonNull(holder, "holder");
        return new Bindable<>() {
            @Override
            public T get() {
                return holder.get();
            }

            @Override
            public boolean set(T value) {
                return holder.set(value);
            }

            @Override
            public void touch() {
                holder.touch();
            }

            @Override
            public long version() {
                return holder.version();
            }
        };
    }

    /** The value of {@code holder}, boxed (null writes 0). */
    @AllowAllocation("bindings are made when a UI is built; values are boxed when they change")
    static Bindable<Double> of(VersionedDouble holder) {
        Objects.requireNonNull(holder, "holder");
        return new Bindable<>() {
            @Override
            @AllowAllocation("boxed when the value changed")
            public Double get() {
                return holder.get();
            }

            @Override
            public boolean set(Double value) {
                return holder.set(value == null ? 0 : value);
            }

            @Override
            public void touch() {
                // a primitive changes only by a new value
            }

            @Override
            public long version() {
                return holder.version();
            }
        };
    }

    /** The value of {@code holder}, boxed (null writes 0). */
    @AllowAllocation("bindings are made when a UI is built; values are boxed when they change")
    static Bindable<Integer> of(VersionedInt holder) {
        Objects.requireNonNull(holder, "holder");
        return new Bindable<>() {
            @Override
            @AllowAllocation("boxed when the value changed")
            public Integer get() {
                return holder.get();
            }

            @Override
            public boolean set(Integer value) {
                return holder.set(value == null ? 0 : value);
            }

            @Override
            public void touch() {
                // a primitive changes only by a new value
            }

            @Override
            public long version() {
                return holder.version();
            }
        };
    }

    /** The value of {@code holder}, boxed (null writes 0). */
    @AllowAllocation("bindings are made when a UI is built; values are boxed when they change")
    static Bindable<Long> of(VersionedLong holder) {
        Objects.requireNonNull(holder, "holder");
        return new Bindable<>() {
            @Override
            @AllowAllocation("boxed when the value changed")
            public Long get() {
                return holder.get();
            }

            @Override
            public boolean set(Long value) {
                return holder.set(value == null ? 0 : value);
            }

            @Override
            public void touch() {
                // a primitive changes only by a new value
            }

            @Override
            public long version() {
                return holder.version();
            }
        };
    }

    /** The value of {@code holder}, boxed (null writes false). */
    @AllowAllocation("bindings are made when a UI is built; values are boxed when they change")
    static Bindable<Boolean> of(VersionedBoolean holder) {
        Objects.requireNonNull(holder, "holder");
        return new Bindable<>() {
            @Override
            @AllowAllocation("boxed when the value changed")
            public Boolean get() {
                return holder.get();
            }

            @Override
            public boolean set(Boolean value) {
                return holder.set(value != null && value);
            }

            @Override
            public void touch() {
                // a primitive changes only by a new value
            }

            @Override
            public long version() {
                return holder.version();
            }
        };
    }

    /**
     * The field {@code lens} selects in {@code whole}'s value: it changes whenever {@code whole} does (its version is
     * {@code whole}'s), and a write replaces {@code whole}'s value with {@code lens.with} (or touches it, when the lens
     * changed a mutable value in place). Writing an equal field value is no change.
     */
    @AllowAllocation("bindings are made when a UI is built")
    static <R, F> Bindable<F> through(Bindable<R> whole, Lens<R, F> lens) {
        Objects.requireNonNull(whole, "whole");
        Objects.requireNonNull(lens, "lens");
        return new Bindable<>() {
            @Override
            public F get() {
                R r = whole.get();
                return r == null ? null : lens.get(r);
            }

            @Override
            public boolean set(F value) {
                R r = whole.get();
                if (r == null || Objects.equals(lens.get(r), value)) {
                    return false;
                }
                R next = lens.with(r, value);
                if (next == r) {
                    whole.touch();
                    return true;
                }
                return whole.set(next);
            }

            @Override
            public void touch() {
                whole.touch();
            }

            @Override
            public long version() {
                return whole.version();
            }
        };
    }

    /** The field {@code lens} selects in {@code holder}'s value. */
    @AllowAllocation("bindings are made when a UI is built")
    static <R, F> Bindable<F> through(VersionedValue<R> holder, Lens<R, F> lens) {
        return through(of(holder), lens);
    }
}
