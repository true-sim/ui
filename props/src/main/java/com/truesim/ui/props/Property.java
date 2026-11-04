package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.bind.Lens;
import com.truesim.ui.props.annotations.Label;
import java.lang.annotation.Annotation;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * One property of a type (design 5.11): its name, value type, getter, and wither (records: a copy with the field
 * replaced) or setter (mutable classes: set in place, returning the same object), and its annotations as typed facts.
 * A property is a {@link Lens}, so editors bind to it through the versioned holder of the whole value, and lenses chain
 * into nested records and collections. Collections carry their element type (and a map its key type).
 *
 * @param <T> the owning type
 * @param <V> the value type (boxed for primitives)
 */
@AllowAllocation("metadata is built once per type")
public final class Property<T, V> implements Lens<T, V> {

    private final String name;
    private final Class<?> type;
    private final Class<?> elementType;
    private final Class<?> keyType;
    private final Function<? super T, ? extends V> getter;
    private final BiFunction<? super T, ? super V, ? extends T> wither;
    private final boolean inPlace;
    private final Annotations annotations;
    private final int index;
    private final Class<?> owner; // the type it is a property of (its model's), or null: any

    private Property(Builder<T, V> b) {
        this.name = b.name;
        this.type = b.type;
        this.elementType = b.elementType;
        this.keyType = b.keyType;
        this.getter = b.getter;
        this.wither = b.wither;
        this.inPlace = b.inPlace;
        this.annotations = b.annotations;
        this.index = b.index;
        this.owner = b.owner;
    }

    /** A property named {@code name} of declared type {@code type} (a primitive class for primitives). */
    public static <T, V> Builder<T, V> builder(String name, Class<?> type) {
        return new Builder<>(name, type);
    }

    /** The name ({@code roughness}). */
    public String name() {
        return name;
    }

    /** The declared type: a primitive class for primitives, the raw class for generic types. */
    public Class<?> type() {
        return type;
    }

    /** The element type of a collection or array (a map's value type), or null. */
    public Class<?> elementType() {
        return elementType;
    }

    /** The key type of a map, or null. */
    public Class<?> keyType() {
        return keyType;
    }

    /** The type it is a property of (its model's type), or null where it was not told. */
    public Class<?> owner() {
        return owner;
    }

    /** The place in declaration order. */
    public int index() {
        return index;
    }

    /** The annotations. */
    public Annotations annotations() {
        return annotations;
    }

    /** The annotation of {@code kind}, or null. */
    public <A extends Annotation> A annotation(Class<A> kind) {
        return annotations.get(kind);
    }

    /** Whether it can be written (a wither or setter). */
    public boolean writable() {
        return wither != null;
    }

    /** Whether writing changes the object in place (a setter) rather than making a copy (a wither). */
    public boolean inPlace() {
        return inPlace;
    }

    /** The label: {@code @Label}, else the name split into words ({@code baseColor}: "Base color"). */
    public String label() {
        Label l = annotations.get(Label.class);
        return l != null ? l.value() : words(name);
    }

    /** {@code camelCase} as "Camel case". */
    static String words(String name) {
        StringBuilder b = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0) {
                b.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c) && !Character.isUpperCase(name.charAt(i - 1))) {
                b.append(' ').append(Character.toLowerCase(c));
            } else if (c == '_') {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * The value of {@code whole}; null where {@code whole} is not of its {@link #owner} type: a lens into a
     * polymorphic element read after the element there changed type (a list reordered, an item replaced), before its
     * rows were rebuilt for the new one, reads nothing instead of casting it.
     */
    @Override
    public V get(T whole) {
        if (owner != null && whole != null && !owner.isInstance(whole)) {
            return null;
        }
        return getter.apply(whole);
    }

    /** A copy of {@code whole} with the value; {@code whole} unchanged where it is not of its {@link #owner} type. */
    @Override
    public T with(T whole, V value) {
        if (wither == null) {
            throw new UnsupportedOperationException(name + " is read-only");
        }
        if (owner != null && whole != null && !owner.isInstance(whole)) {
            return whole;
        }
        return wither.apply(whole, value);
    }

    /** This property with {@code merged} annotations (a merge of sources). */
    Property<T, V> annotations(Annotations merged) {
        Builder<T, V> b = toBuilder();
        b.annotations = merged;
        return b.build();
    }

    /** A builder holding this property's parts. */
    Builder<T, V> toBuilder() {
        Builder<T, V> b = new Builder<>(name, type);
        b.elementType = elementType;
        b.keyType = keyType;
        b.getter = getter;
        b.wither = wither;
        b.inPlace = inPlace;
        b.annotations = annotations;
        b.index = index;
        b.owner = owner;
        return b;
    }

    @Override
    public String toString() {
        return name + ": " + type.getSimpleName() + (elementType == null ? "" : "<" + elementType.getSimpleName() + ">")
            + (annotations.size() == 0 ? "" : " " + annotations);
    }

    /**
     * Builds a property.
     *
     * @param <T> the owning type
     * @param <V> the value type
     */
    @AllowAllocation("metadata is built once per type")
    public static final class Builder<T, V> {
        private final String name;
        private final Class<?> type;
        private Class<?> elementType;
        private Class<?> keyType;
        private Function<? super T, ? extends V> getter;
        private BiFunction<? super T, ? super V, ? extends T> wither;
        private boolean inPlace;
        private Annotations annotations = Annotations.NONE;
        private int index = -1;
        private Class<?> owner;

        Builder(String name, Class<?> type) {
            this.name = Objects.requireNonNull(name, "name");
            this.type = Objects.requireNonNull(type, "type");
        }

        /** The getter. */
        public Builder<T, V> getter(Function<? super T, ? extends V> value) {
            getter = value;
            return this;
        }

        /** The wither: a copy with the value replaced (records). */
        public Builder<T, V> wither(BiFunction<? super T, ? super V, ? extends T> value) {
            wither = value;
            inPlace = false;
            return this;
        }

        /** The setter: sets the value in place (mutable classes). */
        public Builder<T, V> setter(java.util.function.BiConsumer<? super T, ? super V> value) {
            wither = (o, v) -> {
                value.accept(o, v);
                return o;
            };
            inPlace = true;
            return this;
        }

        /** The element type of a collection, array or map's values. */
        public Builder<T, V> element(Class<?> value) {
            elementType = value;
            return this;
        }

        /** The key type of a map. */
        public Builder<T, V> key(Class<?> value) {
            keyType = value;
            return this;
        }

        /** The annotations. */
        public Builder<T, V> annotations(Annotation... value) {
            annotations = Annotations.of(value);
            return this;
        }

        /** The annotations. */
        public Builder<T, V> annotations(Annotations value) {
            annotations = value == null ? Annotations.NONE : value;
            return this;
        }

        /** The type it is a property of (by default: the type of the model it is added to). */
        public Builder<T, V> owner(Class<?> value) {
            owner = value;
            return this;
        }

        /** The place in declaration order (by default: the order properties are added to a model). */
        public Builder<T, V> index(int value) {
            index = value;
            return this;
        }

        /** The property. */
        public Property<T, V> build() {
            if (getter == null) {
                throw new IllegalStateException(name + " has no getter");
            }
            return new Property<>(this);
        }
    }
}
