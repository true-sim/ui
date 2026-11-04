package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The fallback {@link PropertySource} (design 5.11, UI19 (b)): builds a model by reflection on first use, for types
 * compiled without the annotation processor (mods, scripts). Records: their components, read through cached
 * {@link MethodHandle}s of the accessors, written by the canonical constructor. Classes: each field (superclasses'
 * first) with a public getter ({@code getX}, {@code isX}) and, if it has one, setter, or a public field. Annotations
 * are those on the component, field and accessors. {@link PropertyModels} caches the result.
 */
@AllowAllocation("metadata is built once per type")
final class ReflectionModels implements PropertySource {

    /** The source name of models made here. */
    static final String SOURCE = "reflection";

    private static final MethodType GET = MethodType.methodType(Object.class, Object.class);
    private static final MethodType SET = MethodType.methodType(void.class, Object.class, Object.class);

    @Override
    public int priority() {
        return PropertyModels.ANNOTATIONS;
    }

    @Override
    public <T> PropertyModel<T> model(Class<T> type) {
        if (type.isPrimitive() || type.isArray() || type.isEnum() || type.getName().startsWith("java.")) {
            return null;
        }
        PropertyModel.Builder<T> b = PropertyModel.builder(type).source(SOURCE).annotations(annotations(type));
        MethodHandles.Lookup lookup = lookup(type);
        if (type.isRecord()) {
            record(type, lookup, b);
        } else if (!type.isInterface()) {
            bean(type, lookup, b);
        }
        b.factory(Defaults.factory(type));
        for (Class<? extends T> s : Defaults.subtypes(type)) {
            b.subtype(s);
        }
        return b.build();
    }

    private static MethodHandles.Lookup lookup(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException | RuntimeException e) {
            return MethodHandles.publicLookup();
        }
    }

    // ---------------------------------------------------------------- records

    private static <T> void record(Class<T> type, MethodHandles.Lookup lookup, PropertyModel.Builder<T> b) {
        RecordComponent[] cs = type.getRecordComponents();
        int n = cs.length;
        MethodHandle[] accessors = new MethodHandle[n];
        Class<?>[] types = new Class<?>[n];
        for (int i = 0; i < n; i++) {
            types[i] = cs[i].getType();
            Method accessor = cs[i].getAccessor();
            accessors[i] = handle(() -> lookup.unreflect(accessor).asType(GET));
        }
        MethodHandle canonical = handle(() -> lookup.findConstructor(type, MethodType.methodType(void.class, types))
            .asSpreader(Object[].class, n).asType(MethodType.methodType(Object.class, Object[].class)));
        for (int i = 0; i < n; i++) {
            RecordComponent c = cs[i];
            int index = i;
            MethodHandle get = accessors[i];
            Function<T, Object> getter = o -> invoke(get, o);
            BiFunction<T, Object, T> wither = (o, v) -> {
                Object[] args = new Object[n];
                for (int j = 0; j < n; j++) {
                    args[j] = j == index ? orZero(types[j], v) : invoke(accessors[j], o);
                }
                return type.cast(construct(canonical, args));
            };
            Property.Builder<T, Object> p = Property.<T, Object>builder(c.getName(), c.getType()).getter(getter)
                .wither(wither).annotations(merged(c, field(type, c.getName()), c.getAccessor()));
            generics(p, c.getGenericType());
            b.property(p.build());
        }
    }

    // ---------------------------------------------------------------- classes

    private static <T> void bean(Class<T> type, MethodHandles.Lookup lookup, PropertyModel.Builder<T> b) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            chain.addFirst(c);
        }
        List<String> seen = new ArrayList<>();
        for (Class<?> c : chain) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || seen.contains(f.getName())) {
                    continue;
                }
                Property<T, Object> p = beanProperty(type, lookup, f);
                if (p != null) {
                    seen.add(f.getName());
                    b.property(p);
                }
            }
        }
    }

    private static <T> Property<T, Object> beanProperty(Class<T> type, MethodHandles.Lookup lookup, Field f) {
        String cap = Character.toUpperCase(f.getName().charAt(0)) + f.getName().substring(1);
        Method getter = method(type, (f.getType() == boolean.class ? "is" : "get") + cap);
        if (getter == null) {
            getter = method(type, "get" + cap);
        }
        Method setter = method(type, "set" + cap, f.getType());
        Property.Builder<T, Object> p = Property.builder(f.getName(), f.getType());
        if (getter != null) {
            Method g = getter;
            MethodHandle get = handle(() -> lookup.unreflect(g).asType(GET));
            p.getter(o -> invoke(get, o));
            if (setter != null) {
                Method s = setter;
                MethodHandle set = handle(() -> lookup.unreflect(s).asType(SET));
                p.setter((o, v) -> invokeSet(set, o, orZero(f.getType(), v)));
            }
            p.annotations(merged(f, getter, setter));
        } else if (Modifier.isPublic(f.getModifiers())) {
            MethodHandle get = handle(() -> lookup.unreflectGetter(f).asType(GET));
            p.getter(o -> invoke(get, o));
            if (!Modifier.isFinal(f.getModifiers())) {
                MethodHandle set = handle(() -> lookup.unreflectSetter(f).asType(SET));
                p.setter((o, v) -> invokeSet(set, o, orZero(f.getType(), v)));
            }
            p.annotations(merged(f));
        } else {
            return null;
        }
        generics(p, f.getGenericType());
        return p.build();
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method m = type.getMethod(name, parameters);
            return Modifier.isStatic(m.getModifiers()) ? null : m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- shared

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Annotations annotations(Class<?> type) {
        return Annotations.of(type.getAnnotations());
    }

    private static Annotations merged(AnnotatedElement... elements) {
        List<Annotation> all = new ArrayList<>();
        for (AnnotatedElement e : elements) {
            if (e != null) {
                all.addAll(List.of(e.getAnnotations()));
            }
        }
        return Annotations.of(all.toArray(new Annotation[0]));
    }

    /** Sets the element (and key) type of a collection, array or map property from its generic type. */
    static void generics(Property.Builder<?, ?> p, Type type) {
        if (type instanceof Class<?> c && c.isArray()) {
            p.element(c.getComponentType());
        } else if (type instanceof GenericArrayType g) {
            p.element(raw(g.getGenericComponentType()));
        } else if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            Type[] args = pt.getActualTypeArguments();
            if (Map.class.isAssignableFrom(raw) && args.length == 2) {
                p.key(raw(args[0]));
                p.element(raw(args[1]));
            } else if (Collection.class.isAssignableFrom(raw) && args.length == 1) {
                p.element(raw(args[0]));
            }
        }
    }

    private static Class<?> raw(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType p) {
            return raw(p.getRawType());
        }
        if (t instanceof GenericArrayType g) {
            return raw(g.getGenericComponentType()).arrayType();
        }
        return Object.class; // a type variable or wildcard
    }

    private static Object orZero(Class<?> type, Object value) {
        return value == null && type.isPrimitive() ? Defaults.of(type) : value;
    }

    private interface HandleMaker {
        MethodHandle make() throws ReflectiveOperationException;
    }

    private static MethodHandle handle(HandleMaker maker) {
        try {
            return maker.make();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object invoke(MethodHandle h, Object o) {
        try {
            return (Object) h.invokeExact(o);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private static void invokeSet(MethodHandle h, Object o, Object v) {
        try {
            h.invokeExact(o, v);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object construct(MethodHandle h, Object[] args) {
        try {
            return (Object) h.invokeExact(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
