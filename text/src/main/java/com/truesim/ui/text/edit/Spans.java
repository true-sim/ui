package com.truesim.ui.text.edit;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * Rich text's inline spans over a text: each a character range with a {@link Kind} and a value (a colour, a size, a
 * class name, a link target, an image name). Spans nest as markup writes them and may overlap; they are kept sorted
 * by start (an outer span before the inner ones it starts with).
 *
 * <p><b>Editing</b> keeps them with the text: text inserted inside a span, or right after its end (the style of the
 * preceding character), widens it; text inserted where a span starts goes before it (except at the very start, where
 * the following character's style applies); deleted text shrinks spans, removes those left empty and merges spans of
 * the same kind and value that come to touch. An {@link Kind#IMAGE} span covers its one object character and never
 * widens.
 */
public final class Spans {

    /** What a span does. */
    public enum Kind {
        /** Bold weight. */
        BOLD("b"),
        /** Italic style. */
        ITALIC("i"),
        /** A line under. */
        UNDERLINE("u"),
        /** A line through. */
        STRIKE("s"),
        /** A colour: {@code #rrggbb}, {@code #rrggbbaa} or a colour name; the value. */
        COLOR("color"),
        /** A size in pixels (panel units); the value. */
        SIZE("size"),
        /** A background highlight colour; the value. */
        HIGHLIGHT("bg"),
        /** A style class from the theme: {@code span.<value>}. */
        CLASS("class"),
        /** A link to the value (a URL, a console command); clickable, styled {@code link} and {@code link:hover}. */
        LINK("link"),
        /** An inline image or icon of the UI's images, named by the value, over one U+FFFC character. */
        IMAGE("img");

        private static final Kind[] ALL = values();
        private final String tag;

        Kind(String tag) {
            this.tag = tag;
        }

        /** The markup tag name. */
        public String tag() {
            return tag;
        }

        /** Whether the kind takes a value. */
        public boolean valued() {
            return ordinal() >= COLOR.ordinal();
        }

        /** The kind of a markup tag name, or null. */
        public static Kind ofTag(String tag) {
            for (Kind k : ALL) {
                if (k.tag.equals(tag)) {
                    return k;
                }
            }
            return null;
        }

        /** The kind of an ordinal. */
        static Kind of(int ordinal) {
            return ALL[ordinal];
        }
    }

    /** The object replacement character an image span covers. */
    public static final char OBJECT = '\uFFFC';

    private int count;
    private int[] start = new int[8];
    private int[] end = new int[8];
    private byte[] kind = new byte[8];
    private String[] value = new String[8];
    private long version;

    /** No spans. */
    @AllowAllocation("one per rich text")
    public Spans() {
    }

    /** Spans. */
    public int count() {
        return count;
    }

    /** Span {@code i}'s first character. */
    public int start(int i) {
        return start[i];
    }

    /** The character after span {@code i}. */
    public int end(int i) {
        return end[i];
    }

    /** Span {@code i}'s kind. */
    public Kind kind(int i) {
        return Kind.of(kind[i]);
    }

    /** Span {@code i}'s value (null for kinds without one). */
    public String value(int i) {
        return value[i];
    }

    /** Grows with each change. */
    public long version() {
        return version;
    }

    /** Removes every span. */
    public void clear() {
        count = 0;
        version++;
    }

    /** Copies {@code other}'s spans. */
    public void set(Spans other) {
        clear();
        for (int i = 0; i < other.count; i++) {
            add(other.start[i], other.end[i], Kind.of(other.kind[i]), other.value[i]);
        }
    }

    /** Adds a span over {@code [from, to)} (empty spans are ignored) of {@code k} with {@code v}. */
    public void add(int from, int to, Kind k, String v) {
        if (from >= to) {
            return;
        }
        if (k.valued() != (v != null)) {
            throw new IllegalArgumentException(k.valued() ? "this kind of span needs a value"
                : "this kind of span takes no value");
        }
        if (count == start.length) {
            grow();
        }
        // sorted by start, then the longer (outer) first, then by kind, keeping insertion order among equals
        int i = count;
        while (i > 0 && after(start[i - 1], end[i - 1], kind[i - 1], from, to, k.ordinal())) {
            start[i] = start[i - 1];
            end[i] = end[i - 1];
            kind[i] = kind[i - 1];
            value[i] = value[i - 1];
            i--;
        }
        start[i] = from;
        end[i] = to;
        kind[i] = (byte) k.ordinal();
        value[i] = v;
        count++;
        version++;
    }

    private static boolean after(int s, int e, int k, int from, int to, int kOrd) {
        if (s != from) {
            return s > from;
        }
        if (e != to) {
            return e < to;
        }
        return k > kOrd;
    }

    /** Text of {@code n} characters was inserted at {@code at}: spans widen or move (see the class). */
    public void inserted(int at, int n) {
        if (n == 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            boolean image = kind[i] == Kind.IMAGE.ordinal();
            if (start[i] > at || start[i] == at && (at > 0 || image)) {
                start[i] += n;
                end[i] += n;
            } else if (end[i] > at || end[i] == at && !image) {
                end[i] += n; // inside, or right after its end: the preceding character's style
            }
        }
        version++;
    }

    /** Characters {@code [from, to)} were deleted: spans shrink, empty ones go, touching equal ones merge. */
    public void deleted(int from, int to) {
        int n = to - from;
        if (n <= 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            start[i] = shrink(start[i], from, to);
            end[i] = shrink(end[i], from, to);
        }
        normalize();
    }

    private static int shrink(int p, int from, int to) {
        if (p <= from) {
            return p;
        }
        return p >= to ? p - (to - from) : from;
    }

    /** Removes empty spans and merges spans of the same kind and value that overlap or touch. */
    public void normalize() {
        int w = 0;
        for (int i = 0; i < count; i++) {
            if (start[i] < end[i]) {
                move(i, w++);
            }
        }
        count = w;
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < count && !merged; i++) {
                for (int j = i + 1; j < count && !merged; j++) {
                    if (kind[i] == kind[j] && kind[i] != Kind.IMAGE.ordinal() && Objects.equals(value[i], value[j])
                        && start[j] <= end[i] && start[i] <= end[j]) {
                        int s = Math.min(start[i], start[j]);
                        int e = Math.max(end[i], end[j]);
                        Kind k = Kind.of(kind[i]);
                        String v = value[i];
                        remove(j);
                        remove(i);
                        add(s, e, k, v);
                        merged = true;
                    }
                }
            }
        }
        version++;
    }

    private void move(int from, int to) {
        start[to] = start[from];
        end[to] = end[from];
        kind[to] = kind[from];
        value[to] = value[from];
    }

    private void remove(int i) {
        for (int k = i; k < count - 1; k++) {
            move(k + 1, k);
        }
        count--;
        value[count] = null;
    }

    /** Copies the parts of the spans within {@code [from, to)} into {@code out} (cleared), relative to {@code from}. */
    public void copy(int from, int to, Spans out) {
        out.clear();
        for (int i = 0; i < count; i++) {
            int s = Math.max(start[i], from);
            int e = Math.min(end[i], to);
            if (s < e) {
                out.add(s - from, e - from, Kind.of(kind[i]), value[i]);
            }
        }
    }

    /** Adds {@code fragment}'s spans moved to {@code at} (text inserted there before), then normalizes. */
    public void paste(int at, Spans fragment) {
        for (int i = 0; i < fragment.count; i++) {
            add(fragment.start[i] + at, fragment.end[i] + at, Kind.of(fragment.kind[i]), fragment.value[i]);
        }
        normalize();
    }

    /** Removes spans of {@code k} over {@code [from, to)}, splitting those that reach past it. */
    public void remove(int from, int to, Kind k) {
        for (int i = 0; i < count; i++) {
            if (kind[i] != k.ordinal() || end[i] <= from || start[i] >= to) {
                continue;
            }
            int s = start[i];
            int e = end[i];
            String v = value[i];
            remove(i);
            add(s, from, k, v);
            add(to, e, k, v);
            i = -1; // the order changed: look again from the start (the pieces lie outside the range)
        }
        version++;
    }

    /** The innermost span of {@code k} covering character {@code index}, or -1. */
    public int find(Kind k, int index) {
        int found = -1;
        for (int i = 0; i < count && start[i] <= index; i++) {
            if (kind[i] == k.ordinal() && end[i] > index) {
                found = i;
            }
        }
        return found;
    }

    @AllowAllocation("growing, rarely")
    private void grow() {
        int n = start.length * 2;
        start = Arrays.copyOf(start, n);
        end = Arrays.copyOf(end, n);
        kind = Arrays.copyOf(kind, n);
        value = Arrays.copyOf(value, n);
    }

    @Override
    @AllowAllocation("for tests and debugging")
    public String toString() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(Kind.of(kind[i]).tag()).append(value[i] == null ? "" : "=" + value[i]).append(' ')
                .append(start[i]).append("..").append(end[i]);
        }
        return b.append(']').toString();
    }
}
