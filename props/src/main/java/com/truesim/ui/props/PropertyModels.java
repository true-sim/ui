package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where property models come from (design 5.11, UI19): {@link PropertySource}s merged by priority, the result cached
 * per type. One source is always present, {@link #annotated()} at priority {@link #ANNOTATIONS}: a type's Java
 * annotations, from the {@code <Type>Properties} class the annotation processor generated ({@link #generated()}),
 * or by reflection when there is none ({@link #reflection()}, the fallback for mods and scripts). The engine adds its
 * own sources (settings, material records) with {@link #register}, usually above it. A type's model is every
 * source's model of it merged, the highest priority's properties and annotations winning.
 */
@AllowAllocation("metadata is built once per type")
public final class PropertyModels {

    /** The priority of a type's Java annotations (generated or by reflection). */
    public static final int ANNOTATIONS = 0;

    private static final PropertyModels SHARED = new PropertyModels();

    private final List<PropertySource> sources = new ArrayList<>();
    private final Map<Class<?>, PropertyModel<?>> cache = new HashMap<>();

    /** Models from Java annotations: generated classes, else reflection. */
    public PropertyModels() {
        sources.add(annotated());
    }

    /** Models from {@code sources} only (tests, benchmarks: one technique alone). */
    public PropertyModels(PropertySource... sources) {
        for (PropertySource s : sources) {
            this.sources.add(Objects.requireNonNull(s));
        }
        this.sources.sort(Comparator.comparingInt(PropertySource::priority).reversed());
    }

    /** The models shared by builders that are given none. */
    public static PropertyModels shared() {
        return SHARED;
    }

    /** Adds {@code source} (the cache is dropped); returns this. */
    public synchronized PropertyModels register(PropertySource source) {
        sources.add(Objects.requireNonNull(source));
        sources.sort(Comparator.comparingInt(PropertySource::priority).reversed());
        cache.clear();
        return this;
    }

    /** The model of {@code type}: every source's merged; a type no source knows has an empty model. */
    @SuppressWarnings("unchecked")
    public synchronized <T> PropertyModel<T> of(Class<T> type) {
        PropertyModel<T> m = (PropertyModel<T>) cache.get(type);
        if (m == null) {
            for (PropertySource s : sources) {
                PropertyModel<T> next = s.model(type);
                if (next != null) {
                    m = m == null ? next : m.over(next);
                }
            }
            if (m == null) {
                m = PropertyModel.builder(type).source("none").build();
            }
            cache.put(type, m);
        }
        return m;
    }

    /** Drops the cached models (after a source's data changed). */
    public synchronized void invalidate() {
        cache.clear();
    }

    /** Java annotations: the generated class's model if there is one, else reflection's. */
    public static PropertySource annotated() {
        PropertySource generated = generated();
        PropertySource reflection = reflection();
        return new PropertySource() {
            @Override
            public int priority() {
                return ANNOTATIONS;
            }

            @Override
            public <T> PropertyModel<T> model(Class<T> type) {
                PropertyModel<T> m = generated.model(type);
                return m != null ? m : reflection.model(type);
            }
        };
    }

    /** Only the generated {@code <Type>Properties} classes (null for a type without one). */
    public static PropertySource generated() {
        return new Generated();
    }

    /** Only reflection. */
    public static PropertySource reflection() {
        return new ReflectionModels();
    }

    /**
     * The name of the class the processor generates for {@code type}: {@code <Type>Properties} beside it, with a
     * nested type's enclosing names joined by {@code _} ({@code Outer_InnerProperties}).
     */
    public static String generatedName(Class<?> type) {
        String name = type.getName();
        String pkg = type.getPackageName();
        String simple = pkg.isEmpty() ? name : name.substring(pkg.length() + 1);
        return (pkg.isEmpty() ? "" : pkg + ".") + simple.replace('$', '_') + "Properties";
    }

    /** Reads {@code MODEL} of the generated class, if there is one. */
    private static final class Generated implements PropertySource {

        @Override
        public int priority() {
            return ANNOTATIONS;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> PropertyModel<T> model(Class<T> type) {
            if (type.isPrimitive() || type.isArray()) {
                return null;
            }
            try {
                Class<?> g = Class.forName(generatedName(type), true, type.getClassLoader());
                Field f = g.getField("MODEL");
                return (PropertyModel<T>) f.get(null);
            } catch (ClassNotFoundException | NoSuchFieldException e) {
                return null;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
