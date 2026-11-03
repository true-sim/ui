package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.expr.TokenExpr;
import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.value.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * An immutable, typed style sheet: rules in source order, the default values of live tokens, and named token sets
 * ({@code light}, {@code dark}, {@code high-contrast}). Built by the Java {@link Builder} or compiled from {@code .tss}
 * ({@code com.truesim.ui.style.tss.Tss}); both give equal sheets for the same content ({@link #equals} compares
 * content). Each sheet has its own {@link #version()}, so a reload is a new version even with equal content.
 *
 * <pre>{@code
 * StyleSheet.Builder sheet = StyleSheet.builder();
 * sheet.rule(sel("button").style("glass").state(HOVER))
 *      .set(BACKGROUND, Paint.box(Colors.lighten(GLASS, 0.08)))
 *      .set(RADIUS, Radii.of(6));
 * StyleSheet built = sheet.build();
 * }</pre>
 */
@AllowAllocation("sheets are built at load")
public final class StyleSheet {

    private static final AtomicLong VERSIONS = new AtomicLong();

    private final List<Rule> rules;
    private final Map<String, Value> tokens;
    private final Map<String, Map<String, Value>> tokenSets;
    private final long version;

    /** A sheet of {@code rules}, token defaults {@code tokens} and token sets {@code tokenSets}. */
    public StyleSheet(List<Rule> rules, Map<String, Value> tokens, Map<String, Map<String, Value>> tokenSets) {
        this.rules = List.copyOf(rules);
        this.tokens = Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
        Map<String, Map<String, Value>> sets = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Value>> e : tokenSets.entrySet()) {
            sets.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        this.tokenSets = Collections.unmodifiableMap(sets);
        this.version = VERSIONS.incrementAndGet();
    }

    /** An empty sheet. */
    public static StyleSheet empty() {
        return new StyleSheet(List.of(), Map.of(), Map.of());
    }

    /** A builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** The rules, in source order. */
    public List<Rule> rules() {
        return rules;
    }

    /** The live tokens' default values, by name (without {@code --}). */
    public Map<String, Value> tokens() {
        return tokens;
    }

    /** The token sets by name: each overrides some tokens' defaults. */
    public Map<String, Map<String, Value>> tokenSets() {
        return tokenSets;
    }

    /** This sheet's version: unique per sheet made, growing. */
    public long version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StyleSheet s && rules.equals(s.rules) && tokens.equals(s.tokens)
            && tokenSets.equals(s.tokenSets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rules, tokens, tokenSets);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Rule r : rules) {
            sb.append(r).append('\n');
        }
        return sb.append("tokens ").append(tokens).append(", sets ").append(tokenSets).toString();
    }

    /** Builds a sheet in Java: variables are constants, mixins are methods taking a {@link RuleBuilder}. */
    @AllowAllocation("sheets are built at load")
    public static final class Builder {
        private final List<RuleBuilder> rules = new ArrayList<>();
        private final Map<String, Value> tokens = new LinkedHashMap<>();
        private final Map<String, Map<String, Value>> tokenSets = new LinkedHashMap<>();

        private Builder() {
        }

        /** A rule for {@code selectors} (one rule each, sharing the declarations), after the rules so far. */
        public RuleBuilder rule(Selector... selectors) {
            RuleBuilder r = new RuleBuilder(this, List.of(selectors));
            rules.add(r);
            return r;
        }

        /** The default value of live token {@code name} (without {@code --}). */
        public Builder token(String name, Value value) {
            tokens.put(name, value);
            return this;
        }

        /** The default colour of live token {@code name}. */
        public Builder token(String name, Color value) {
            return token(name, Value.color(value));
        }

        /** Token {@code name}'s value {@code value} in token set {@code set}. */
        public Builder tokenSet(String set, String name, Value value) {
            tokenSets.computeIfAbsent(set, k -> new LinkedHashMap<>()).put(name, value);
            return this;
        }

        /** The sheet. */
        public StyleSheet build() {
            List<Rule> out = new ArrayList<>();
            for (RuleBuilder r : rules) {
                for (Selector s : r.selectors) {
                    out.add(new Rule(s, r.declarations));
                }
            }
            return new StyleSheet(out, tokens, tokenSets);
        }
    }

    /** The declarations of one rule. */
    @AllowAllocation("sheets are built at load")
    public static final class RuleBuilder {
        private final Builder sheet;
        private final List<Selector> selectors;
        private final List<Declaration> declarations = new ArrayList<>();

        private RuleBuilder(Builder sheet, List<Selector> selectors) {
            this.sheet = sheet;
            this.selectors = selectors;
        }

        /** Sets {@code key} to {@code value}. */
        public <T> RuleBuilder set(StyleKey<T> key, T value) {
            declarations.add(Declaration.of(key, value));
            return this;
        }

        /** Sets the keys {@code shorthand} expands to. */
        public <T> RuleBuilder set(Shorthand<T> shorthand, T value) {
            declarations.addAll(shorthand.expand(value));
            return this;
        }

        /** Sets {@code key} to a live expression over tokens ({@code TokenExpr.call("lighten", token("accent"), ...)}). */
        public RuleBuilder live(StyleKey<?> key, TokenExpr expression) {
            declarations.add(Declaration.live(key, expression));
            return this;
        }

        /** Applies {@code mixin} (a Java mixin: a method taking the rule) to this rule. */
        public RuleBuilder with(Consumer<RuleBuilder> mixin) {
            mixin.accept(this);
            return this;
        }

        /** Starts the next rule. */
        public RuleBuilder rule(Selector... next) {
            return sheet.rule(next);
        }

        /** The sheet. */
        public StyleSheet build() {
            return sheet.build();
        }
    }
}
