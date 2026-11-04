package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.props.annotations.Label;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The metadata of one type (design 5.11): its ordered {@link Property properties}, its own annotations, how to make a
 * new value ({@link #factory()}: a collection's add) and, for a sealed type, the permitted subtypes an add offers.
 * Models come from {@link PropertySource}s (generated classes, reflection, the engine's settings and material records)
 * and are merged by priority ({@link #over}).
 *
 * @param <T> the type
 */
@AllowAllocation("metadata is built once per type")
public final class PropertyModel<T> {

    private final Class<T> type;
    private final List<Property<T, ?>> properties;
    private final Map<String, Property<T, ?>> byName;
    private final Annotations annotations;
    private final Supplier<? extends T> factory;
    private final List<Class<? extends T>> subtypes;
    private final String source;

    private PropertyModel(Builder<T> b) {
        this.type = b.type;
        this.properties = List.copyOf(b.properties);
        this.byName = new LinkedHashMap<>();
        for (Property<T, ?> p : properties) {
            byName.put(p.name(), p);
        }
        this.annotations = b.annotations;
        this.factory = b.factory;
        this.subtypes = List.copyOf(b.subtypes);
        this.source = b.source;
    }

    /** A builder of {@code type}'s model. */
    public static <T> Builder<T> builder(Class<T> type) {
        return new Builder<>(type);
    }

    /** The type. */
    public Class<T> type() {
        return type;
    }

    /** The properties in declaration order. */
    public List<Property<T, ?>> properties() {
        return properties;
    }

    /** The property named {@code name}, or null. */
    public Property<T, ?> property(String name) {
        return byName.get(name);
    }

    /** The type's own annotations. */
    public Annotations annotations() {
        return annotations;
    }

    /** The annotation of {@code kind} on the type, or null. */
    public <A extends Annotation> A annotation(Class<A> kind) {
        return annotations.get(kind);
    }

    /** Makes a new value (defaults), or null when the model knows none. */
    public Supplier<? extends T> factory() {
        return factory;
    }

    /** The permitted concrete subtypes of a sealed type, in declaration order (empty otherwise). */
    public List<Class<? extends T>> subtypes() {
        return subtypes;
    }

    /** Where the model came from ({@code generated}, {@code reflection}, a source's name, or merged ones with +). */
    public String source() {
        return source;
    }

    /** The label: {@code @Label}, else the simple name split into words. */
    public String label() {
        Label l = annotations.get(Label.class);
        return l != null ? l.value() : Property.words(type.getSimpleName());
    }

    /**
     * This model over {@code lower} (a lower-priority source's model of the same type): properties of both, this one's
     * first in its order, then {@code lower}'s others; a property in both keeps this one's accessors and merges the
     * annotations, each annotation type taken from this one if present. The factory and subtypes come from here
     * unless this has none.
     */
    public PropertyModel<T> over(PropertyModel<T> lower) {
        if (lower.type != type) {
            throw new IllegalArgumentException(lower.type + " is not " + type);
        }
        Builder<T> b = builder(type);
        for (Property<T, ?> p : properties) {
            Property<T, ?> q = lower.byName.get(p.name());
            b.property(q == null ? p : merge(p, q));
        }
        for (Property<T, ?> q : lower.properties) {
            if (!byName.containsKey(q.name())) {
                b.property(q);
            }
        }
        b.annotations = annotations.over(lower.annotations);
        b.factory = factory != null ? factory : lower.factory;
        b.subtypes.addAll(subtypes.isEmpty() ? lower.subtypes : subtypes);
        b.source = source + "+" + lower.source;
        return b.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Property<T, ?> merge(Property<T, ?> high, Property<T, ?> low) {
        Property.Builder b = high.toBuilder();
        if (!high.writable() && low.writable()) {
            return ((Property) low).annotations(high.annotations().over(low.annotations()));
        }
        b.annotations(high.annotations().over(low.annotations()));
        if (high.elementType() == null) {
            b.element(low.elementType());
        }
        if (high.keyType() == null) {
            b.key(low.keyType());
        }
        return b.build();
    }

    @Override
    public String toString() {
        return type.getSimpleName() + properties;
    }

    /**
     * Builds a model.
     *
     * @param <T> the type
     */
    @AllowAllocation("metadata is built once per type")
    public static final class Builder<T> {
        private final Class<T> type;
        private final List<Property<T, ?>> properties = new ArrayList<>();
        private Annotations annotations = Annotations.NONE;
        private Supplier<? extends T> factory;
        private final List<Class<? extends T>> subtypes = new ArrayList<>();
        private String source = "code";

        Builder(Class<T> type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        /** Adds {@code property} (its index, if unset, is its place here). */
        public Builder<T> property(Property<T, ?> property) {
            if (property.index() < 0) {
                property = property.toBuilder().index(properties.size()).build();
            }
            if (property.owner() == null && type != Object.class) {
                property = property.toBuilder().owner(type).build(); // its getter checks what it is given
            }
            for (Property<T, ?> p : properties) {
                if (p.name().equals(property.name())) {
                    throw new IllegalArgumentException(type.getSimpleName() + " has two properties " + p.name());
                }
            }
            properties.add(property);
            return this;
        }

        /** The type's annotations. */
        public Builder<T> annotations(Annotation... value) {
            annotations = Annotations.of(value);
            return this;
        }

        /** The type's annotations. */
        public Builder<T> annotations(Annotations value) {
            annotations = value == null ? Annotations.NONE : value;
            return this;
        }

        /** Makes new values. */
        public Builder<T> factory(Supplier<? extends T> value) {
            factory = value;
            return this;
        }

        /** Adds a permitted subtype (a sealed type). */
        public Builder<T> subtype(Class<? extends T> value) {
            subtypes.add(value);
            return this;
        }

        /** Where the model comes from. */
        public Builder<T> source(String value) {
            source = Objects.requireNonNull(value, "source");
            return this;
        }

        /** The model. */
        public PropertyModel<T> build() {
            return new PropertyModel<>(this);
        }
    }
}
