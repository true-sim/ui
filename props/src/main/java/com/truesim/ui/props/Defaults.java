package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * New values of a type, for a collection's add (design 5.11): a type's public static {@code DEFAULTS} (as the Slang
 * material records have), else a no-argument constructor, else a record's canonical constructor with each component's
 * default (zero, false, the empty string, the first enum constant, empty collections, nested records made the same
 * way). Also the permitted concrete subtypes of a sealed type, which an add offers in a type chooser.
 */
@AllowAllocation("new values are made on edit")
public final class Defaults {

    private static final int DEPTH = 8;

    private Defaults() {
    }

    /** Makes new values of {@code type}, or null when it has no way. */
    public static <T> Supplier<T> factory(Class<T> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers()) || type.isPrimitive()
            || type.isArray() || type.isEnum()) {
            return null;
        }
        try {
            Field f = type.getField("DEFAULTS");
            if (Modifier.isStatic(f.getModifiers()) && type.isAssignableFrom(f.getType())) {
                return () -> type.cast(read(f));
            }
        } catch (NoSuchFieldException e) {
            // no DEFAULTS
        }
        try {
            Constructor<T> c = type.getDeclaredConstructor();
            c.setAccessible(true);
            return () -> construct(c);
        } catch (NoSuchMethodException | RuntimeException e) {
            // no constructor without arguments, or none we may call
        }
        if (type.isRecord()) {
            return () -> type.cast(of(type, 0));
        }
        return null;
    }

    private static Object read(Field f) {
        try {
            return f.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T construct(Constructor<T> c) {
        try {
            return c.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot make a " + c.getDeclaringClass().getSimpleName(), e);
        }
    }

    /** The default value of {@code type} (see the class comment), or null. */
    public static Object of(Class<?> type) {
        return of(type, 0);
    }

    private static Object of(Class<?> type, int depth) {
        if (type.isPrimitive()) {
            return Array.get(Array.newInstance(type, 1), 0);
        }
        if (type == String.class) {
            return "";
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants.length == 0 ? null : constants[0];
        }
        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }
        if (type == List.class || type == Collection.class) {
            return List.of();
        }
        if (type == Set.class) {
            return Set.of();
        }
        if (type == Map.class) {
            return Map.of();
        }
        if (Number.class.isAssignableFrom(type) || type == Boolean.class || type == Character.class) {
            return boxedZero(type);
        }
        if (type.isRecord() && depth < DEPTH) {
            RecordComponent[] cs = type.getRecordComponents();
            Class<?>[] types = new Class<?>[cs.length];
            Object[] args = new Object[cs.length];
            for (int i = 0; i < cs.length; i++) {
                types[i] = cs[i].getType();
                args[i] = of(types[i], depth + 1);
            }
            try {
                Constructor<?> c = type.getDeclaredConstructor(types);
                c.setAccessible(true);
                return c.newInstance(args);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        if (depth > 0) {
            Supplier<?> f = factory(type);
            return f == null ? null : f.get();
        }
        return null;
    }

    private static Object boxedZero(Class<?> type) {
        if (type == Boolean.class) {
            return Boolean.FALSE;
        }
        if (type == Character.class) {
            return '\0';
        }
        for (Class<?> p : new Class<?>[] {int.class, long.class, double.class, float.class, short.class,
            byte.class}) {
            if (Array.get(Array.newInstance(p, 1), 0).getClass() == type) {
                return Array.get(Array.newInstance(p, 1), 0);
            }
        }
        return null;
    }

    /**
     * The concrete types a value of {@code type} may be: its permitted subclasses, recursively through sealed
     * subtypes, in declaration order; empty when {@code type} is not sealed.
     */
    @SuppressWarnings("unchecked")
    public static <T> List<Class<? extends T>> subtypes(Class<T> type) {
        List<Class<? extends T>> out = new ArrayList<>();
        if (!type.isSealed()) {
            return out;
        }
        for (Class<?> c : type.getPermittedSubclasses()) {
            if (c.isSealed()) {
                out.addAll((List<Class<? extends T>>) (List<?>) subtypes(c));
            }
            if (!c.isInterface() && !Modifier.isAbstract(c.getModifiers())) {
                out.add((Class<? extends T>) c);
            }
        }
        return out;
    }
}
