package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The annotations of a property or type as typed facts (design 5.11): {@code get(Range.class)} answers the range or
 * null. They come from Java annotations (read by the annotation processor or by reflection), or from an external
 * source building them with {@link Hints} (the engine's settings and Slang material records). At most one of each
 * annotation type; merging keeps the higher-priority source's.
 */
@AllowAllocation("metadata is built once per type")
public final class Annotations {

    /** No annotations. */
    public static final Annotations NONE = new Annotations(new Annotation[0]);

    private final Annotation[] list;

    private Annotations(Annotation[] list) {
        this.list = list;
    }

    /** The annotations {@code list} (later ones of a type already present are dropped; nulls skipped). */
    public static Annotations of(Annotation... list) {
        List<Annotation> kept = new ArrayList<>();
        for (Annotation a : list) {
            if (a != null && find(kept, a.annotationType()) == null) {
                kept.add(a);
            }
        }
        return kept.isEmpty() ? NONE : new Annotations(kept.toArray(new Annotation[0]));
    }

    private static Annotation find(List<Annotation> list, Class<? extends Annotation> type) {
        for (Annotation a : list) {
            if (a.annotationType() == type) {
                return a;
            }
        }
        return null;
    }

    /** The annotation of {@code type}, or null. */
    public <A extends Annotation> A get(Class<A> type) {
        for (Annotation a : list) {
            if (a.annotationType() == type) {
                return type.cast(a);
            }
        }
        return null;
    }

    /** Whether there is an annotation of {@code type}. */
    public boolean has(Class<? extends Annotation> type) {
        return get(type) != null;
    }

    /** Every annotation, in declaration order. */
    public List<Annotation> list() {
        return List.of(list);
    }

    /** How many there are. */
    public int size() {
        return list.length;
    }

    /** These, with {@code extra} added (an annotation of a type already here replaces it). */
    public Annotations with(Annotation... extra) {
        List<Annotation> all = new ArrayList<>(Arrays.asList(extra));
        all.addAll(Arrays.asList(list));
        return of(all.toArray(new Annotation[0]));
    }

    /** These over {@code lower}: each annotation type from here if present, else from {@code lower}. */
    public Annotations over(Annotations lower) {
        if (lower.list.length == 0) {
            return this;
        }
        if (list.length == 0) {
            return lower;
        }
        List<Annotation> all = new ArrayList<>(Arrays.asList(list));
        all.addAll(Arrays.asList(lower.list));
        return of(all.toArray(new Annotation[0]));
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("[");
        for (Annotation a : list) {
            if (b.length() > 1) {
                b.append(", ");
            }
            b.append('@').append(a.annotationType().getSimpleName());
        }
        return b.append(']').toString();
    }
}
