package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry of every declared {@link StyleKey} and {@link Shorthand}: lookup by name for style sheets, "did you
 * mean" suggestions for unknown names, and the dense index of each key. Keys register themselves when their holder
 * class initialises; {@link #load} initialises holder classes a sheet needs before it is compiled.
 */
@AllowAllocation("keys are registered and looked up when sheets are built")
public final class StyleKeys {

    private static final Map<String, StyleKey<?>> KEYS = new HashMap<>();
    private static final Map<String, Shorthand<?>> SHORTHANDS = new HashMap<>();
    private static final List<StyleKey<?>> BY_INDEX = new ArrayList<>();
    private static volatile int count;

    private StyleKeys() {
    }

    static synchronized int register(StyleKey<?> key) {
        checkFree(key.name());
        KEYS.put(key.name(), key);
        BY_INDEX.add(key);
        count = BY_INDEX.size();
        return BY_INDEX.size() - 1;
    }

    static synchronized void register(Shorthand<?> shorthand) {
        checkFree(shorthand.name());
        SHORTHANDS.put(shorthand.name(), shorthand);
    }

    private static void checkFree(String name) {
        if (KEYS.containsKey(name) || SHORTHANDS.containsKey(name)) {
            throw new IllegalStateException("style key " + name + " is declared twice");
        }
    }

    /** Initialises {@code holders}, the classes declaring keys, so their keys are registered. */
    public static void load(Class<?>... holders) {
        for (Class<?> holder : holders) {
            try {
                Class.forName(holder.getName(), true, holder.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e); // cannot happen: the class is loaded already
            }
        }
    }

    /**
     * Initialises every {@link StyleKeyHolder} on the class path (the context class loader's {@code ServiceLoader}), so
     * keys declared by modules above ui-style are known to sheets; the shipped themes style their controls with them.
     */
    public static void loadInstalled() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (StyleKeyHolder holder : java.util.ServiceLoader.load(StyleKeyHolder.class,
            loader == null ? StyleKeys.class.getClassLoader() : loader)) {
            load(holder.getClass());
        }
    }

    /** The key named {@code name}, or null. */
    public static synchronized StyleKey<?> find(String name) {
        return KEYS.get(name);
    }

    /** The shorthand named {@code name}, or null. */
    public static synchronized Shorthand<?> findShorthand(String name) {
        return SHORTHANDS.get(name);
    }

    /** The key with dense index {@code index}. */
    public static synchronized StyleKey<?> key(int index) {
        return BY_INDEX.get(index);
    }

    /** How many keys are registered: indexes are below this. */
    public static int count() {
        return count;
    }

    /** The registered key or shorthand name closest to {@code name}, or null if none is close. */
    public static synchronized String suggest(String name) {
        List<String> names = new ArrayList<>(KEYS.keySet());
        names.addAll(SHORTHANDS.keySet());
        return closest(name, names);
    }

    /** The candidate closest to {@code name} by edit distance, or null if none is within a third of its length. */
    public static String closest(String name, Iterable<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int limit = Math.max(2, name.length() / 3);
        for (String candidate : candidates) {
            int d = distance(name, candidate);
            if (d <= limit && (d < bestDistance || d == bestDistance && candidate.compareTo(best) < 0)) {
                best = candidate;
                bestDistance = d;
            }
        }
        return best;
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] t = previous;
            previous = current;
            current = t;
        }
        return previous[b.length()];
    }
}
