package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Vec2;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.Vec4;
import com.truesim.ui.core.Element;
import com.truesim.ui.style.value.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renderers by type (design 4.3). {@link #find} resolves a class in this order, once per class (then a map lookup):
 * <ol>
 *   <li>a renderer registered for exactly that class;</li>
 *   <li>for records, the records renderer ({@link #records}): by default their components through cached
 *       reflection (a {@code record-view} of {@code record-field}s);</li>
 *   <li>one registered for a superclass, then for an interface (nearest first);</li>
 *   <li>the built-ins: {@code Enum}, {@code Number}, {@code CharSequence}, {@code Boolean}, vectors and colours;</li>
 *   <li>{@code String.valueOf} as text.</li>
 * </ol>
 * Controls take a registry or a renderer of their own; {@link #named} keeps renderers by name for configuration and
 * for styles ({@link DataStyles#RENDERER}: {@code renderer: number}); the built-ins are named {@code text},
 * {@code number}, {@code check}, {@code enum}, {@code vector}, {@code color} and {@code record}.
 * {@link #dynamic()} renders mixed values (a list of {@code Object}), choosing per value and recycling per renderer
 * and kind.
 */
public final class ValueRenderers {

    /** Kinds per renderer in {@link #dynamic()}'s kind numbers. */
    static final int KINDS = 1 << 16;

    private final Map<Class<?>, ValueRenderer<?>> registered = new HashMap<>();
    private final Map<Class<?>, ValueRenderer<?>> resolved = new HashMap<>();
    private final Map<String, ValueRenderer<?>> names = new HashMap<>();
    private final List<ValueRenderer<?>> ids = new ArrayList<>();
    private final Map<ValueRenderer<?>, Integer> idOf = new HashMap<>();
    private final List<Class<?>> builtinTypes = new ArrayList<>();
    private final List<ValueRenderer<?>> builtins = new ArrayList<>();
    private ValueRenderer<? super Record> records = new RecordRenderer(this);
    private final ValueRenderer<Object> fallback = Renderers.text();
    private final Dynamic dynamic = new Dynamic();

    /** A registry with the built-ins only. */
    @AllowAllocation("registries are made when a UI is built")
    public ValueRenderers() {
        builtin(Enum.class, Renderers.enumeration());
        builtin(Number.class, Renderers.number(3));
        builtin(CharSequence.class, Renderers.text());
        builtin(Boolean.class, Renderers.bool());
        ValueRenderer<Object> vectors = Renderers.vector();
        builtin(Vec2.class, vectors);
        builtin(Vec3.class, vectors);
        builtin(Vec4.class, vectors);
        builtin(Color.class, Renderers.color());
        names.put("text", fallback);
        names.put("number", builtins.get(1));
        names.put("check", builtins.get(3));
        names.put("enum", builtins.get(0));
        names.put("vector", vectors);
        names.put("color", builtins.get(builtins.size() - 1));
        names.put("record", records);
    }

    @AllowAllocation("registries are made when a UI is built")
    private void builtin(Class<?> type, ValueRenderer<?> renderer) {
        builtinTypes.add(type);
        builtins.add(renderer);
    }

    /** Uses {@code renderer} for {@code type} (and, by the resolution order, its subtypes); returns this. */
    @AllowAllocation("registries are configured when a UI is built")
    public <T> ValueRenderers register(Class<T> type, ValueRenderer<? super T> renderer) {
        registered.put(type, renderer);
        resolved.clear();
        return this;
    }

    /**
     * Uses {@code renderer} for records without a renderer of their own (by default their components by metadata:
     * the renderer named {@code record}); null turns records over to the later steps. Returns this.
     */
    public ValueRenderers records(ValueRenderer<? super Record> renderer) {
        records = renderer;
        resolved.clear();
        return this;
    }

    /** Keeps {@code renderer} under {@code name}; returns this. */
    @AllowAllocation("registries are configured when a UI is built")
    public ValueRenderers name(String name, ValueRenderer<?> renderer) {
        names.put(name, renderer);
        return this;
    }

    /** The renderer named {@code name}, or null. */
    public ValueRenderer<?> named(String name) {
        return names.get(name);
    }

    /** The renderer for values of {@code type}, by the resolution order. */
    @SuppressWarnings("unchecked")
    public <T> ValueRenderer<? super T> find(Class<T> type) {
        ValueRenderer<?> r = resolved.get(type);
        if (r == null) {
            r = resolve(type);
        }
        return (ValueRenderer<? super T>) r;
    }

    @AllowAllocation("once per class")
    private ValueRenderer<?> resolve(Class<?> type) {
        ValueRenderer<?> r = registered.get(type);
        for (int i = 0; r == null && i < builtinTypes.size(); i++) {
            if (builtinTypes.get(i) == type) {
                r = builtins.get(i); // a built-in of exactly this class (vectors and colours are records)
            }
        }
        if (r == null && type.isRecord() && records != null) {
            r = records;
        }
        if (r == null) {
            for (Class<?> c = type.getSuperclass(); c != null && r == null; c = c.getSuperclass()) {
                r = registered.get(c);
            }
        }
        if (r == null) {
            r = byInterface(type);
        }
        for (int i = 0; r == null && i < builtinTypes.size(); i++) {
            if (builtinTypes.get(i).isAssignableFrom(type)) {
                r = builtins.get(i);
            }
        }
        if (r == null) {
            r = fallback;
        }
        resolved.put(type, r);
        return r;
    }

    /** The renderer of the nearest registered interface, breadth first over the class and its superclasses. */
    @AllowAllocation("once per class")
    private ValueRenderer<?> byInterface(Class<?> type) {
        ArrayDeque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            Class<?> i = queue.poll();
            if (!seen.add(i)) {
                continue;
            }
            ValueRenderer<?> r = registered.get(i);
            if (r != null) {
                return r;
            }
            for (Class<?> parent : i.getInterfaces()) {
                queue.add(parent);
            }
        }
        return null;
    }

    /**
     * A renderer of any value that chooses per value by its class, recycling views per chosen renderer and kind:
     * for lists of mixed values.
     */
    public ValueRenderer<Object> dynamic() {
        return dynamic;
    }

    @AllowAllocation("once per renderer")
    private int id(ValueRenderer<?> renderer) {
        Integer id = idOf.get(renderer);
        if (id == null) {
            id = ids.size();
            ids.add(renderer);
            idOf.put(renderer, id);
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private ValueRenderer<Object> of(Object value) {
        return (ValueRenderer<Object>) (value == null ? fallback : find(value.getClass()));
    }

    /** {@link #dynamic()}. */
    final class Dynamic implements ValueRenderer<Object> {

        /** The registry it chooses from. */
        ValueRenderers registry() {
            return ValueRenderers.this;
        }

        @Override
        public int kind(Object value) {
            ValueRenderer<Object> r = of(value);
            return id(r) * KINDS + r.kind(value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Element create(int kind, ViewContext context) {
            return ((ValueRenderer<Object>) ids.get(kind / KINDS)).create(kind % KINDS, context);
        }

        @Override
        public void bind(Element view, Object value, CellState state) {
            of(value).bind(view, value, state);
        }
    }
}
