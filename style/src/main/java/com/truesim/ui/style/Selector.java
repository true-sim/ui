package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.UiState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which elements a rule applies to (Lemur's model plus additions, design 4.4): a chain of {@link Compound}s joined by
 * descendant ("contains") or child ({@code >}) combinators, the last one being the element itself. A compound tests
 * the element type, the style name (a theme or variant, inherited from the parent when an element has none), the
 * element id path by its tail ({@code #thumb.button} matches an element with id {@code button} under one with id
 * {@code thumb}), state pseudo-classes ({@code :hover}, ...) and attribute facts ({@code [type=Transform]}).
 *
 * <p>Built in Java ({@code sel("button").style("glass").state(HOVER)}) or parsed ({@link #parse}): both give equal
 * values. Text form: {@code type.style#id.path[name=value]:state%placeholder}; the style name comes before the id
 * because id paths are dotted.
 */
@AllowAllocation("selectors are built when a sheet is built")
public record Selector(List<Compound> compounds, List<Combinator> combinators) {

    /** How two compounds relate. */
    public enum Combinator {
        /** The left one is an ancestor ("contains"). */
        DESCENDANT,
        /** The left one is the parent ({@code >}). */
        CHILD
    }

    /** An attribute fact test: {@code [name=value]}, or {@code [name]} (value null) for presence. */
    public record Fact(String name, String value) {
        public Fact {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String toString() {
            return "[" + name + (value == null ? "" : "=" + value) + "]";
        }
    }

    /**
     * One element test. Null fields test nothing; {@code id} is the dotted path's segments (empty for none);
     * {@code states} are {@link UiState} bits that must all be on; {@code placeholder} marks a {@code %placeholder}
     * that only {@code @extend} uses.
     */
    public record Compound(String type, String style, List<String> id, int states, List<Fact> facts,
        String placeholder) {

        /** Matches every element. */
        public static final Compound ANY = new Compound(null, null, List.of(), 0, List.of(), null);

        public Compound {
            id = List.copyOf(id);
            facts = List.copyOf(facts);
        }

        Compound withType(String value) {
            return new Compound(value, style, id, states, facts, placeholder);
        }

        Compound withStyle(String value) {
            return new Compound(type, value, id, states, facts, placeholder);
        }

        Compound withId(List<String> value) {
            return new Compound(type, style, value, states, facts, placeholder);
        }

        Compound withStates(int value) {
            return new Compound(type, style, id, value, facts, placeholder);
        }

        Compound withFact(Fact fact) {
            List<Fact> f = new ArrayList<>(facts);
            f.add(fact);
            return new Compound(type, style, id, states, f, placeholder);
        }

        Compound withPlaceholder(String value) {
            return new Compound(type, style, id, states, facts, value);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (type != null) {
                sb.append(type);
            }
            if (style != null) {
                sb.append('.').append(style);
            }
            if (!id.isEmpty()) {
                sb.append('#').append(String.join(".", id));
            }
            for (Fact f : facts) {
                sb.append(f);
            }
            for (int bit = 1; bit < 1 << UiState.count(); bit <<= 1) {
                if ((states & bit) != 0) {
                    sb.append(':').append(UiState.name(bit));
                }
            }
            if (placeholder != null) {
                sb.append('%').append(placeholder);
            }
            return sb.length() == 0 ? "*" : sb.toString();
        }
    }

    public Selector {
        compounds = List.copyOf(compounds);
        combinators = List.copyOf(combinators);
        if (compounds.isEmpty() || combinators.size() != compounds.size() - 1) {
            throw new IllegalArgumentException("a selector has n compounds and n - 1 combinators");
        }
    }

    /** Elements of {@code type} ({@code "*"} or null for any). */
    public static Selector sel(String type) {
        Compound c = type == null || type.equals("*") ? Compound.ANY : Compound.ANY.withType(type);
        return new Selector(List.of(c), List.of());
    }

    /** Every element. */
    public static Selector any() {
        return sel(null);
    }

    /**
     * A selector written as text ({@code panel.hud > label.title:hover}).
     *
     * @throws com.truesim.ui.style.expr.ValueException if it is not one, or is a list
     */
    public static Selector parse(String text) {
        List<Selector> list = SelectorParser.parseList(text);
        if (list.size() != 1) {
            throw new com.truesim.ui.style.expr.ValueException("expected one selector, got " + list.size() + ": "
                + text);
        }
        return list.get(0);
    }

    /** A comma-separated selector list. */
    public static List<Selector> parseList(String text) {
        return SelectorParser.parseList(text);
    }

    private Selector withSubject(Compound subject) {
        List<Compound> c = new ArrayList<>(compounds);
        c.set(c.size() - 1, subject);
        return new Selector(c, combinators);
    }

    /** The element itself: the last compound. */
    public Compound subject() {
        return compounds.get(compounds.size() - 1);
    }

    /** This with the element's style name {@code style}. */
    public Selector style(String style) {
        return withSubject(subject().withStyle(style));
    }

    /** This with the element id path {@code dottedPath} ({@code "thumb.button"}), matched by its tail. */
    public Selector id(String dottedPath) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= dottedPath.length(); i++) {
            if (i == dottedPath.length() || dottedPath.charAt(i) == '.') {
                if (i == start) {
                    throw new IllegalArgumentException("empty id segment in " + dottedPath);
                }
                segments.add(dottedPath.substring(start, i));
                start = i + 1;
            }
        }
        return withSubject(subject().withId(segments));
    }

    /** This with state bits {@code bits} ({@link UiState}) required too. */
    public Selector state(int bits) {
        return withSubject(subject().withStates(subject().states() | bits));
    }

    /** This with fact {@code [name=value]} required too. */
    public Selector fact(String name, String value) {
        return withSubject(subject().withFact(new Fact(name, value)));
    }

    /** This with placeholder name {@code name} ({@code %name}). */
    public Selector placeholder(String name) {
        return withSubject(subject().withPlaceholder(name));
    }

    /** {@code child} where this matches its parent: {@code this > child}. */
    public Selector child(Selector child) {
        return join(child, Combinator.CHILD);
    }

    /** {@code descendant} where this matches one of its ancestors: {@code this descendant}. */
    public Selector descendant(Selector descendant) {
        return join(descendant, Combinator.DESCENDANT);
    }

    private Selector join(Selector right, Combinator combinator) {
        List<Compound> c = new ArrayList<>(compounds);
        c.addAll(right.compounds);
        List<Combinator> j = new ArrayList<>(combinators);
        j.add(combinator);
        j.addAll(right.combinators);
        return new Selector(c, j);
    }

    /** Whether a compound names a placeholder: such rules only serve {@code @extend}. */
    public boolean hasPlaceholder() {
        for (Compound c : compounds) {
            if (c.placeholder != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The specificity, computed once: style names, then id segments, then facts, then types, then compounds, each
     * counted over the whole selector (Lemur: a style's own rules beat the root style's, a longer id path beats a
     * shorter one, a containment selector beats a plain one). State pseudo-classes are ranked apart (see
     * {@link StyleResolver}).
     */
    public long specificity() {
        long styles = 0;
        long ids = 0;
        long facts = 0;
        long types = 0;
        for (Compound c : compounds) {
            styles += c.style != null ? 1 : 0;
            ids += c.id.size();
            facts += c.facts.size();
            types += c.type != null ? 1 : 0;
        }
        return cap(styles) << 48 | cap(ids) << 36 | cap(facts) << 24 | cap(types) << 12 | cap(compounds.size());
    }

    private static long cap(long v) {
        return Math.min(v, 4095);
    }

    /** How many state pseudo-classes it tests, over all compounds. */
    public int stateCount() {
        int n = 0;
        for (Compound c : compounds) {
            n += Integer.bitCount(c.states);
        }
        return n;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(compounds.get(0).toString());
        for (int i = 1; i < compounds.size(); i++) {
            sb.append(combinators.get(i - 1) == Combinator.CHILD ? " > " : " ").append(compounds.get(i));
        }
        return sb.toString();
    }
}
