package com.truesim.ui.core.bind;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A view of one field of an immutable value (design 4.2, UI20): {@link #get} reads it, {@link #with} makes a copy with
 * it replaced. Records are immutable, so an editor bound to a record's field writes through a lens: the new record goes
 * into the versioned holder, and through a chain of lenses ({@code root -> waypoints -> element 3 -> position}) an
 * edit deep inside produces a new root. Copies are made on edit, never per frame. A lens of a mutable object may set
 * the field in place and return the same object; the holder is then {@linkplain Bindable#touch touched}.
 *
 * @param <R> the whole value (a record)
 * @param <F> the field
 */
public interface Lens<R, F> {

    /** The field of {@code whole}. */
    F get(R whole);

    /** A copy of {@code whole} with the field {@code value} (or {@code whole} itself, changed in place). */
    R with(R whole, F value);

    /** A lens from a getter and a wither ({@code Waypoint::position}, {@code Waypoint::withPosition}). */
    @AllowAllocation("lenses are made when a UI is built")
    static <R, F> Lens<R, F> of(Function<? super R, ? extends F> getter,
        BiFunction<? super R, ? super F, ? extends R> wither) {
        Objects.requireNonNull(getter, "getter");
        Objects.requireNonNull(wither, "wither");
        return new Lens<>() {
            @Override
            public F get(R whole) {
                return getter.apply(whole);
            }

            @Override
            public R with(R whole, F value) {
                return wither.apply(whole, value);
            }
        };
    }

    /** This lens, then {@code inner} on its field: {@code root -> field -> inner field}. */
    @AllowAllocation("lenses are made when a UI is built")
    default <G> Lens<R, G> then(Lens<F, G> inner) {
        Lens<R, F> outer = this;
        return new Lens<>() {
            @Override
            public G get(R whole) {
                return inner.get(outer.get(whole));
            }

            @Override
            public R with(R whole, G value) {
                return outer.with(whole, inner.with(outer.get(whole), value));
            }
        };
    }

    /** The value itself. */
    @AllowAllocation("lenses are made when a UI is built")
    static <T> Lens<T, T> identity() {
        return of(Function.identity(), (whole, value) -> value);
    }

    /**
     * Element {@code index} of a list: {@link #with} makes a new list (an unmodifiable copy) with the element replaced,
     * the old list untouched.
     */
    @AllowAllocation("lenses are made when a UI is built; a copy is made on edit")
    static <E> Lens<List<E>, E> element(int index) {
        return new Lens<>() {
            @Override
            public E get(List<E> whole) {
                return whole.get(index);
            }

            @Override
            public List<E> with(List<E> whole, E value) {
                List<E> copy = new ArrayList<>(whole);
                copy.set(index, value);
                return Collections.unmodifiableList(copy);
            }
        };
    }

    /** The value of {@code key} in a map: {@link #with} makes a new map (insertion order kept) with it replaced. */
    @AllowAllocation("lenses are made when a UI is built; a copy is made on edit")
    static <K, V> Lens<Map<K, V>, V> entry(K key) {
        return new Lens<>() {
            @Override
            public V get(Map<K, V> whole) {
                return whole.get(key);
            }

            @Override
            public Map<K, V> with(Map<K, V> whole, V value) {
                Map<K, V> copy = new LinkedHashMap<>(whole);
                copy.put(key, value);
                return Collections.unmodifiableMap(copy);
            }
        };
    }
}
