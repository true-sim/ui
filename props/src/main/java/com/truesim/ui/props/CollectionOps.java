package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.bind.Lens;
import java.lang.reflect.Array;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable collection updates (design 5.11, UI18): every operation makes a new list, set, map or array from the old
 * one, which a lens chain writes into a new root. Lists, sets and arrays are seen as lists of their items, maps as
 * lists of entries ({@link Map.Entry}, keys kept unique); {@link #rebuild} turns a list back into the declared
 * container type. Copies are made on edit, never per frame. Also deep copies through metadata (duplicate) and the
 * size and uniqueness checks of {@code @MinSize}, {@code @MaxSize} and {@code @Unique}.
 */
@AllowAllocation("collections are copied on edit")
public final class CollectionOps {

    private CollectionOps() {
    }

    /** The number of items of a collection, map or array (0 for null or anything else). */
    public static int size(Object c) {
        if (c instanceof Collection<?> col) {
            return col.size();
        }
        if (c instanceof Map<?, ?> m) {
            return m.size();
        }
        if (c != null && c.getClass().isArray()) {
            return Array.getLength(c);
        }
        return 0;
    }

    /** Whether the items (or a map's keys, always distinct) are distinct. */
    public static boolean unique(Object c) {
        if (c instanceof Map<?, ?> || c instanceof Set<?> || c == null) {
            return true;
        }
        List<Object> items = items(c);
        return new HashSet<>(items).size() == items.size();
    }

    /** The items of a collection or array, or a map's entries, as a new list. */
    public static List<Object> items(Object c) {
        List<Object> out = new ArrayList<>();
        if (c instanceof Collection<?> col) {
            out.addAll(col);
        } else if (c instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
            }
        } else if (c != null && c.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(c); i++) {
                out.add(Array.get(c, i));
            }
        }
        return out;
    }

    /**
     * {@code items} as a value of {@code type}: an unmodifiable list, an unmodifiable set keeping order, an
     * unmodifiable map keeping order (from entries; a key repeated keeps its last value), or an array of
     * {@code element}s.
     */
    public static Object rebuild(Class<?> type, Class<?> element, List<?> items) {
        if (type.isArray()) {
            Object a = Array.newInstance(type.getComponentType(), items.size());
            for (int i = 0; i < items.size(); i++) {
                Array.set(a, i, items.get(i));
            }
            return a;
        }
        if (Map.class.isAssignableFrom(type)) {
            Map<Object, Object> m = new LinkedHashMap<>();
            for (Object o : items) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                m.put(e.getKey(), e.getValue());
            }
            return Collections.unmodifiableMap(m);
        }
        if (Set.class.isAssignableFrom(type)) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(items));
        }
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    /** Whether a map's entries have distinct keys. */
    public static boolean uniqueKeys(List<?> entries) {
        Set<Object> keys = new HashSet<>();
        for (Object o : entries) {
            if (o instanceof Map.Entry<?, ?> e && !keys.add(e.getKey())) {
                return false;
            }
        }
        return true;
    }

    /** {@code items} with {@code item} inserted at {@code at}. */
    public static List<Object> add(List<?> items, int at, Object item) {
        List<Object> out = new ArrayList<>(items);
        out.add(at, item);
        return out;
    }

    /** {@code items} without item {@code index}. */
    public static List<Object> remove(List<?> items, int index) {
        List<Object> out = new ArrayList<>(items);
        out.remove(index);
        return out;
    }

    /** {@code items} with item {@code from} moved to land before item {@code before} (indices before the move). */
    public static List<Object> move(List<?> items, int from, int before) {
        List<Object> out = new ArrayList<>(items);
        Object item = out.remove(from);
        out.add(before > from ? before - 1 : before, item);
        return out;
    }

    /** {@code items} with item {@code index} replaced by {@code item}. */
    public static List<Object> set(List<?> items, int index, Object item) {
        List<Object> out = new ArrayList<>(items);
        out.set(index, item);
        return out;
    }

    /**
     * The lens from a container value of {@code type} to its item {@code index} (a map's entry): writing makes a new
     * container.
     */
    public static Lens<Object, Object> item(Class<?> type, Class<?> element, int index) {
        return Lens.of(c -> {
            if (c instanceof List<?> l) {
                return index < l.size() ? l.get(index) : null;
            }
            List<Object> items = items(c);
            return index < items.size() ? items.get(index) : null;
        }, (c, v) -> rebuild(type, element, set(items(c), index, v)));
    }

    /**
     * A deep copy of {@code value} through its metadata: collections, maps and arrays item by item, records by their
     * withers, mutable objects made new by their model's factory and set property by property; strings, numbers,
     * enums and other values without properties as they are.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object copy(Object value, PropertyModels models) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
            || value instanceof Character || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof Map.Entry<?, ?> e) {
            return new AbstractMap.SimpleImmutableEntry<>(copy(e.getKey(), models), copy(e.getValue(), models));
        }
        if (value instanceof Collection<?> || value instanceof Map<?, ?> || value.getClass().isArray()) {
            List<Object> items = items(value);
            items.replaceAll(o -> copy(o, models));
            Class<?> type = value.getClass().isArray() ? value.getClass() : value instanceof Map<?, ?> ? Map.class
                : value instanceof Set<?> ? Set.class : List.class;
            return rebuild(type, null, items);
        }
        PropertyModel model = models.of(value.getClass());
        if (model.properties().isEmpty()) {
            return value;
        }
        Object copy = value;
        boolean inPlace = false;
        for (Object o : model.properties()) {
            inPlace |= ((Property) o).inPlace();
        }
        if (inPlace) {
            if (model.factory() == null) {
                return value;
            }
            copy = model.factory().get();
        }
        for (Object o : model.properties()) {
            Property p = (Property) o;
            if (p.writable()) {
                copy = p.with(copy, copy(p.get(value), models));
            }
        }
        return copy;
    }
}
